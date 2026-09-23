package eu.stgm.pactum.figlio.giornata

import android.content.Context
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.zonaPatto
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.ui.etichettaChiave
import eu.stgm.pactum.figlio.ui.testoDurata
import eu.stgm.pactum.figlio.valutatore.MomentoFascia
import eu.stgm.pactum.figlio.valutatore.SentinellaPatto
import eu.stgm.pactum.figlio.valutatore.Valutatore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId

/**
 * La chiusura della sera (redesign C5): all'ora scelta, UNA notifica che dice
 * com'è andata la giornata. È l'unico appuntamento che dà un motivo per aprire
 * l'app anche quando è andata bene.
 *
 * Si calcola sul telefono, coi dati che l'app ha già (la copia locale del
 * patto, le misure di oggi, la striscia dell'ultimo aggiornamento). Una volta
 * al giorno anche dopo un riavvio: il giorno dell'ultima chiusura sta nelle
 * Impostazioni, e ogni controllo passa dallo stesso mutex. Il servizio la
 * guarda all'ora esatta, il worker fa da riserva.
 */
object ChiusuraSerale {

    private val mutex = Mutex()

    suspend fun controlla(context: Context, now: Long = System.currentTimeMillis()) {
        mutex.withLock { controllaDentro(context.applicationContext, now) }
    }

    private suspend fun controllaDentro(context: Context, now: Long) {
        val impostazioni = Impostazioni(context)
        val config = impostazioni.leggiChiusuraSerale()
        if (!config.attiva) return
        val adesso = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val oggi = adesso.toLocalDate().toString()
        val passata = !adesso.toLocalTime().isBefore(config.ora)

        val ultima = impostazioni.leggiUltimaChiusura()
        if (ultima == null) {
            // Primissimo controllo (app appena installata o aggiornata): se l'ora
            // è già passata, oggi non si racconta — la giornata non l'ha vista.
            impostazioni.registraUltimaChiusura(
                if (passata) oggi else adesso.toLocalDate().minusDays(1).toString(),
            )
            return
        }
        // ">=" e non "==": con l'orologio portato indietro "oggi" torna a un
        // giorno già chiuso, e la chiusura partirebbe una seconda volta.
        if (!passata || TestoSerale.giaChiusa(ultima, oggi)) return
        // Senza permesso non si segna il giorno: se arriva stasera, parte stasera.
        if (!AvvisiLocali.puoAvvisare(context)) return

        val misura = SentinellaPatto(context).misura(now) ?: return
        val patto = misura.patto
        // (v3) Le regole di questo telefono: quelle del computer le misura il computer.
        val regoleQui = patto.regoleDiQuestoDispositivo()
        val misurabili = regoleQui.any {
            it.attiva && (it.tipo == TipiRegola.LIMITE_TEMPO || it.tipo == TipiRegola.FASCIA_ORARIA)
        }
        if (!misurabili) {
            // Solo regole di vita reale: il telefono non ha niente di vero da dire.
            impostazioni.registraUltimaChiusura(oggi)
            return
        }

        val regolePerId = regoleQui.associateBy { it.id }
        val fuori = misura.sforamenti
            // La coda mattutina della fascia di ieri appartiene a ieri (come nel semaforo).
            .filter { Valutatore.giornoDelloSforamento(it, misura.giorno) == misura.giorno }
            .map { s ->
                val regola = regolePerId[s.regolaId]
                val nome = (regola?.parametri?.get("app_o_categoria") as? JsonPrimitive)
                    ?.content?.let { etichettaChiave(context, it, regola?.nome) }
                FuoriOggi(
                    tipo = if (s.tipo == TipiRegola.FASCIA_ORARIA) TipoFuori.FASCIA else TipoFuori.LIMITE,
                    nome = nome,
                    minuti = s.minutiOltre,
                )
            }
        val oggiPatto = Instant.ofEpochMilli(now).atZone(zonaPatto(patto.fuso)).toLocalDate().toString()
        val giorni = patto.giorniPatto()
        val rossoSulServer = giorni.lastOrNull()?.let {
            it.data == oggiPatto && it.segnale == Segnale.FUORI_REGOLA
        } ?: false
        val serie = Serie.conOggi(giorni, impostazioni.leggiSerieSalvata(), oggiPatto)
        // Una fascia di oggi ancora da venire (22:00-07:00 alle 21:30) o in
        // corso: la giornata non è finita, "dentro" vale solo fin qui.
        val zona = ZoneId.systemDefault()
        val fasciaAperta = regoleQui.any { regola ->
            regola.attiva && when (Valutatore.momentoFascia(regola, now, zona)) {
                is MomentoFascia.Prima, is MomentoFascia.InCorso -> true
                else -> false
            }
        }

        val chiusura = TestoSerale.chiusura(fuori, rossoSulServer, serie, fasciaAperta)
        val testo = TestoSerale.testo(chiusura, parole(context))
        val mandata = AvvisiLocali.avvisa(
            context,
            id = AvvisiLocali.ID_CHIUSURA_SERALE,
            titolo = context.getString(R.string.serale_titolo),
            testo = testo,
            destinazione = MainActivity.DEST_OGGI,
        )
        if (mandata) impostazioni.registraUltimaChiusura(oggi)
    }

    private fun parole(context: Context) = ParoleSerale(
        dentro = context.getString(R.string.serale_dentro),
        finoraDentro = context.getString(R.string.serale_finora_dentro),
        giornoInParole = context.getString(R.string.serale_giorno_in_parole),
        ordinali = context.resources.getStringArray(R.array.serale_ordinali).toList(),
        giornoInCifre = context.getString(R.string.serale_giorno_in_cifre),
        oltre = context.getString(R.string.serale_oltre),
        fascia = context.getString(R.string.serale_fascia),
        fuori = context.getString(R.string.serale_fuori),
        unAltraRegola = context.getString(R.string.serale_un_altra_regola),
        altreRegole = context.getString(R.string.serale_altre_regole),
        domani = context.getString(R.string.serale_domani),
        durata = { testoDurata(context, it.toLong()) },
    )
}
