package eu.stgm.pactum.figlio.valutatore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/**
 * La sentinella: confronta l'uso di oggi con la copia locale del patto
 * (PattoLocale, risincronizzata dal worker) e per ogni sforamento nuovo
 * accoda l'evento al registro e alza una notifica gentile al figlio.
 * MAI blocchi (concept.md): registra e ricorda, la conseguenza vive nella
 * conversazione. Dedup: massimo UNO sforamento per regola per giorno locale,
 * memorizzato in Impostazioni — chiamarla da worker E loop del servizio è
 * quindi innocuo.
 */
class SentinellaPatto(private val context: Context) {

    suspend fun valuta(now: Long = System.currentTimeMillis()) {
        if (!PermessiHelper.haAccessoUso(context)) return
        val patto = PattoLocale(context).leggi() ?: return
        if (patto.regole.isEmpty()) return

        val zona = ZoneId.systemDefault()
        val giorno = Instant.ofEpochMilli(now).atZone(zona).toLocalDate().toString()
        val reader = UsageStatsReader(context)

        // Uso di oggi indicizzato sia per pacchetto sia per etichetta visibile
        // (minuscole): "app_o_categoria" di una regola può essere l'uno o l'altra.
        val minutiPerChiave = HashMap<String, Long>()
        for (uso in reader.usoDelGiorno(zona = zona, adesso = now)) {
            if (uso.pacchetto == context.packageName) continue // Pactum non testimonia contro sé stessa
            val minuti = uso.millisPrimoPiano / 60_000
            minutiPerChiave.merge(uso.pacchetto.lowercase(), minuti, Long::plus)
            val etichetta = etichettaApp(uso.pacchetto).lowercase()
            if (etichetta != uso.pacchetto.lowercase()) {
                minutiPerChiave.merge(etichetta, minuti, Long::plus)
            }
        }

        val sforamenti = Valutatore.valuta(
            regole = patto.regole,
            bonusOggiPerRegola = patto.bonusOggiPerRegola,
            usoMinutiEtichetta = { etichetta ->
                minutiPerChiave[etichetta.trim().lowercase()] ?: 0L
            },
            usoMinutiIntervallo = { inizio, fine ->
                reader.usoNellIntervallo(inizio, fine)
                    .filterNot { it.pacchetto == context.packageName }
                    .sumOf { it.millisPrimoPiano } / 60_000
            },
            now = now,
            zona = zona,
        )
        if (sforamenti.isEmpty()) return

        val impostazioni = Impostazioni(context)
        val coda = CodaEventi(context)
        val regolePerId = patto.regole.associateBy { it.id }
        for (sforamento in sforamenti) {
            if (impostazioni.sforamentoGiaSegnalato(sforamento.regolaId, giorno)) continue
            coda.accoda(
                Evento(
                    tipo = TipiEvento.SFORAMENTO,
                    tsDevice = now,
                    dettagli = buildJsonObject {
                        put("regola_id", sforamento.regolaId)
                        sforamento.limiteEfficace?.let { put("limite_efficace", it) }
                        put("minuti_oltre", sforamento.minutiOltre)
                        put("giorno", giorno)
                    },
                ),
            )
            // Segnato subito dopo l'accodamento: la coda persiste e riconsegna
            // da sola, quindi l'evento arriverà — ri-valutare non deve duplicarlo.
            impostazioni.registraSforamentoSegnalato(sforamento.regolaId, giorno)
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
            testo = context.getString(
                R.string.notifica_sforamento_limite_testo,
                parametri?.let { testoParametro(it, "app_o_categoria") } ?: "?",
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

    private fun etichettaApp(pacchetto: String): String = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pacchetto, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pacchetto, 0)
        }
        info.loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pacchetto
    }
}
