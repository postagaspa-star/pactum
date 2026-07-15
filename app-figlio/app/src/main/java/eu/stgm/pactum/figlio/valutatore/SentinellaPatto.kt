package eu.stgm.pactum.figlio.valutatore

import android.content.Context
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.zonaPatto
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/**
 * La sentinella: confronta l'uso di oggi con la copia locale del patto
 * (PattoLocale, risincronizzata dal worker) e per ogni sforamento nuovo
 * accoda l'evento al registro e alza una notifica gentile al figlio.
 * MAI blocchi (concept.md): registra e ricorda, la conseguenza vive nella
 * conversazione.
 *
 * Dedup: massimo UNO sforamento per regola per giorno (le limite_tempo nel fuso
 * del telefono, le fasce nel giorno di ancoraggio dell'occorrenza), memorizzato
 * in Impostazioni. Il mutex nel companion serializza l'intero corpo: worker e
 * loop del servizio girano nello stesso processo e senza serializzazione due
 * chiamate concorrenti potrebbero superare entrambe il controllo di dedup ed
 * emettere due eventi per lo stesso sforamento (stesso schema di CodaEventi).
 */
class SentinellaPatto(private val context: Context) {

    suspend fun valuta(now: Long = System.currentTimeMillis()) = mutex.withLock {
        if (!PermessiHelper.haAccessoUso(context)) return@withLock
        val patto = PattoLocale(context).leggi() ?: return@withLock
        if (patto.regole.isEmpty()) return@withLock

        val zona = ZoneId.systemDefault()
        val giorno = Instant.ofEpochMilli(now).atZone(zona).toLocalDate().toString()
        val reader = UsageStatsReader(context)

        // Uso di oggi indicizzato per pacchetto e per categoria: una regola
        // limite_tempo vale su un pacchetto esatto o su una chiave categoria:*
        // (contratto v2.1), e il match dev'essere esatto sull'uno o sull'altra.
        val minutiPerPacchetto = HashMap<String, Long>()
        val minutiPerCategoria = HashMap<String, Long>()
        for (uso in reader.usoDelGiorno(zona = zona, adesso = now)) {
            if (uso.pacchetto == context.packageName) continue // Pactum non testimonia contro sé stessa
            val minuti = uso.millisPrimoPiano / 60_000
            minutiPerPacchetto.merge(uso.pacchetto.lowercase(), minuti, Long::plus)
            minutiPerCategoria.merge(
                CatalogoApp.categoriaDiPacchetto(context, uso.pacchetto),
                minuti,
                Long::plus,
            )
        }

        // I bonus di "oggi" valgono solo se la copia locale è stata sincronizzata
        // OGGI nel fuso del patto: dopo una notte offline il bonus di ieri non
        // deve allargare il limite di oggi (contratto: bonus del giorno). Se il
        // giorno stampato al salvataggio non è più oggi, nessun bonus vale.
        val giornoPatto = Instant.ofEpochMilli(now).atZone(zonaPatto(patto.fuso))
            .toLocalDate().toString()
        val bonusEffettivo =
            if (patto.bonusGiornoLocale == giornoPatto) patto.bonusOggiPerRegola else emptyMap()

        val sforamenti = Valutatore.valuta(
            regole = patto.regole,
            bonusOggiPerRegola = bonusEffettivo,
            usoMinutiEtichetta = { chiave ->
                val k = chiave.trim().lowercase()
                if (k.startsWith(CatalogoApp.PREFISSO_CATEGORIA)) {
                    minutiPerCategoria[k] ?: 0L
                } else {
                    minutiPerPacchetto[k] ?: 0L
                }
            },
            usoMinutiIntervallo = { inizio, fine ->
                reader.usoNellIntervallo(inizio, fine)
                    .filterNot { it.pacchetto == context.packageName }
                    .sumOf { it.millisPrimoPiano } / 60_000
            },
            now = now,
            zona = zona,
        )
        if (sforamenti.isEmpty()) return@withLock

        val impostazioni = Impostazioni(context)
        val coda = CodaEventi(context)
        val regolePerId = patto.regole.associateBy { it.id }
        for (sforamento in sforamenti) {
            // Le limite_tempo dedupano sul giorno (fuso telefono); le fasce sul
            // giorno di ancoraggio dell'occorrenza, così le due parti di una
            // fascia che scavalca la mezzanotte non diventano due sforamenti.
            val giornoDedup = sforamento.giornoAncora ?: giorno
            if (impostazioni.sforamentoGiaSegnalato(sforamento.regolaId, giornoDedup)) continue
            coda.accoda(
                Evento(
                    tipo = TipiEvento.SFORAMENTO,
                    tsDevice = now,
                    dettagli = buildJsonObject {
                        put("regola_id", sforamento.regolaId)
                        sforamento.limiteEfficace?.let { put("limite_efficace", it) }
                        put("minuti_oltre", sforamento.minutiOltre)
                        put("giorno", giornoDedup)
                    },
                ),
            )
            // Segnato subito dopo l'accodamento: la coda persiste e riconsegna
            // da sola, quindi l'evento arriverà — ri-valutare non deve duplicarlo.
            impostazioni.registraSforamentoSegnalato(sforamento.regolaId, giornoDedup)
            avvisaGentile(sforamento, regolePerId[sforamento.regolaId])
        }
    }

    /** Il promemoria al figlio: tono da patto, non da sirena. */
    private fun avvisaGentile(sforamento: Sforamento, regola: Regola?) {
        val parametri = regola?.parametri
        val titolo: String
        val testo: String
        if (sforamento.tipo == TipiRegola.FASCIA_ORARIA) {
            titolo = context.getString(R.string.notifica_sforamento_fascia_titolo)
            testo = context.getString(
                R.string.notifica_sforamento_fascia_testo,
                sforamento.minutiOltre,
                parametri?.let { testoParametro(it, "dalle") } ?: "?",
                parametri?.let { testoParametro(it, "alle") } ?: "?",
            )
        } else {
            titolo = context.getString(R.string.notifica_sforamento_limite_titolo)
            val nomeApp = parametri?.let { testoParametro(it, "app_o_categoria") }
                ?.let { CatalogoApp.etichettaValore(context, it) } ?: "?"
            testo = context.getString(
                R.string.notifica_sforamento_limite_testo,
                nomeApp,
                sforamento.minutiOltre,
                sforamento.limiteEfficace ?: 0,
            )
        }
        AvvisiLocali.avvisa(
            context,
            id = AvvisiLocali.idSforamento(sforamento.regolaId),
            titolo = titolo,
            testo = testo,
            destinazione = MainActivity.DEST_REGOLE,
        )
    }

    private fun testoParametro(parametri: kotlinx.serialization.json.JsonObject, nome: String): String? =
        (parametri[nome] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private companion object {
        val mutex = Mutex()
    }
}
