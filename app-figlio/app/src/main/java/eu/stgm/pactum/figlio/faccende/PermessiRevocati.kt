package eu.stgm.pactum.figlio.faccende

import android.content.Context
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * (0.13) I permessi che tengono in piedi Pactum, tolti: un evento
 * `manomissione` con `sotto_tipo: "permesso_revocato"` e il permesso nei
 * dettagli (`"permesso": "accesso_uso"` o `"mostra_sopra"`), solo al passaggio
 * da concesso a tolto e una volta sola, sotto un solo lucchetto: lo chiamano
 * il worker e il giro delle faccende, e non fanno doppioni.
 *
 *  - accesso ai dati di utilizzo: sempre (come dalla tappa 6), con l'avviso
 *    sul telefono "Pactum non può più misurare l'uso";
 *  - "Mostra sopra le altre app": solo se succede durante il blocco delle
 *    faccende (contratto v3.6); fuori dal blocco non è una manomissione.
 *
 * La prima osservazione fissa solo la base: un permesso già assente
 * all'inizio non è una revoca.
 */
object PermessiRevocati {

    private val mutex = Mutex()

    /** Cosa segnalare e i nuovi stati noti (logica pura). */
    data class Esito(val daSegnalare: List<String>, val usoNoto: Boolean, val sopraNoto: Boolean)

    fun decidi(usoNoto: Boolean?, uso: Boolean, sopraNoto: Boolean?, sopra: Boolean, bloccoAttivo: Boolean): Esito {
        val segnala = buildList {
            if (usoNoto == true && !uso) add(MemoriaBlocco.PERMESSO_ACCESSO_USO)
            if (sopraNoto == true && !sopra && bloccoAttivo) add(MemoriaBlocco.PERMESSO_MOSTRA_SOPRA)
        }
        return Esito(segnala, usoNoto = uso, sopraNoto = sopra)
    }

    /**
     * Guarda i due permessi adesso: accoda gli eventi delle revoche e salva lo
     * stato noto. Restituisce i permessi segnalati (vuoto = niente di nuovo).
     */
    suspend fun controlla(context: Context, bloccoAttivo: Boolean, adesso: Long = System.currentTimeMillis()): List<String> =
        mutex.withLock {
            val app = context.applicationContext
            val impostazioni = Impostazioni(app)
            val usoNoto = impostazioni.leggiAccessoUsoNoto()
            val sopraNoto = impostazioni.leggiMostraSopraNoto()
            val uso = PermessiHelper.haAccessoUso(app)
            val sopra = PermessiHelper.puoMostrareSopra(app)
            val esito = decidi(usoNoto, uso, sopraNoto, sopra, bloccoAttivo)
            val coda = CodaEventi(app)
            for (permesso in esito.daSegnalare) {
                coda.accoda(
                    Evento(
                        tipo = TipiEvento.MANOMISSIONE,
                        tsDevice = adesso,
                        dettagli = buildJsonObject {
                            put("sotto_tipo", "permesso_revocato")
                            put("permesso", permesso)
                        },
                    ),
                )
            }
            if (MemoriaBlocco.PERMESSO_ACCESSO_USO in esito.daSegnalare) {
                // Le notifiche sono ancora attive (è l'accesso all'uso a mancare):
                // un promemoria gentile aiuta a rimettere a posto il patto.
                AvvisiLocali.avvisa(
                    app,
                    id = AvvisiLocali.ID_MANOMISSIONE_PERMESSO,
                    titolo = app.getString(R.string.notifica_permesso_revocato_titolo),
                    testo = app.getString(R.string.notifica_permesso_revocato_testo),
                )
            }
            if (usoNoto != esito.usoNoto) impostazioni.registraAccessoUsoNoto(esito.usoNoto)
            if (sopraNoto != esito.sopraNoto) impostazioni.registraMostraSopraNoto(esito.sopraNoto)
            esito.daSegnalare
        }
}
