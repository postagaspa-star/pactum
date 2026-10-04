package eu.stgm.pactum.figlio.fotografo

import android.content.Intent
import android.os.Looper
import eu.stgm.pactum.figlio.avviso.Avviso
import eu.stgm.pactum.figlio.avviso.AvvisoActivity
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.BarrieraFaccendeActivity
import eu.stgm.pactum.figlio.faccende.CoperturaFinestrelle
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.BarrieraActivity
import eu.stgm.pactum.figlio.sessione.MemoriaSessioni
import eu.stgm.pactum.figlio.sessione.PaginaSessioneActivity
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * 13-17 — Le schermate a tutto schermo che si aprono sopra le altre app: la
 * barriera dei lavori di casa, la barriera della sessione, l'avviso, la
 * pagina animata della sessione (un fotogramma) e la copertura delle finestrelle.
 */
class FotoSchermiInteriTest : Fotografo() {

    // --- 13 Barriera "Prima i lavori di casa" -------------------------------------

    @Test
    fun barrieraLavori() {
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoAttivo() }
        scatta("13-barriera-lavori", "Barriera \"Prima i lavori di casa\" sopra un'app: 3 lavori, \"Apri Pactum\"") {
            apriActivity(BarrieraFaccendeActivity::class.java).comeAperta()
        }
    }

    // --- 14 Barriera della sessione -------------------------------------------------

    private fun barrieraSessione(): Aperta {
        val intent = Intent(app, BarrieraActivity::class.java).putExtra("svolta_id", 501L)
        return apriActivity(BarrieraActivity::class.java, intent).comeAperta()
    }

    @Test
    fun barrieraSessioneStudio() {
        ArchivioSessioni.modifica(app) { MemoriaSessioni(svolte = listOf(DatiFinti.svoltaStudio())) }
        scatta("14-barriera-sessione", "Barriera \"Sei in sessione «Studio»\" sopra un'app fuori dalla sessione, \"Esci\"", pagine = false) {
            barrieraSessione()
        }
    }

    @Test
    fun barrieraSessioneNomeLungo() {
        val svolta = DatiFinti.svoltaStudio().let {
            it.copy(nome = "Compiti di matematica e scienze per domani", finePrevista = DatiFinti.adesso() + 9 * DatiFinti.ORA)
        }
        ArchivioSessioni.modifica(app) { MemoriaSessioni(svolte = listOf(svolta)) }
        scatta("14-barriera-sessione-nome-lungo", "Barriera della sessione con un nome lungo e la fine domani", pagine = false) {
            barrieraSessione()
        }
    }

    // --- 15 Avviso a tutto schermo --------------------------------------------------

    private fun avviso(avvisi: List<Avviso>): Aperta {
        val intent = Intent(app, AvvisoActivity::class.java).putExtra("avvisi", Avviso.inJson(avvisi))
        return apriActivity(AvvisoActivity::class.java, intent).comeAperta()
    }

    private val avvisoInstagram = Avviso(
        regolaId = 1, tipo = TipiRegola.LIMITE_TEMPO, nome = "Instagram",
        minutiUsati = 82, limiteEfficace = 75, limite = 60, minutiOltre = 7,
    )

    @Test
    fun avvisoLimite() {
        scatta("15-avviso-limite", "Avviso a tutto schermo: oltre il limite di Instagram (con 15 min di bonus)", pagine = false) {
            avviso(listOf(avvisoInstagram))
        }
    }

    @Test
    fun avvisoDoppio() {
        val tiktok = Avviso(
            regolaId = 2, tipo = TipiRegola.LIMITE_TEMPO, nome = "TikTok",
            minutiUsati = 52, limiteEfficace = 45, limite = 45, minutiOltre = 7,
        )
        scatta("15-avviso-due-regole", "Avviso a tutto schermo con due regole superate insieme") {
            avviso(listOf(avvisoInstagram, tiktok))
        }
    }

    // --- 16 Pagina animata della sessione (un fotogramma) -------------------------

    private fun pagina(fine: Boolean, nome: String, inizio: Long, termine: Long, chiusaPrima: Boolean, id: Long): Aperta {
        compose.mainClock.autoAdvance = false
        val intent = Intent(app, PaginaSessioneActivity::class.java)
            .putExtra("tipo", if (fine) "fine" else "inizio")
            .putExtra("svolta_id", id)
            .putExtra("nome", nome)
            .putExtra("inizio", inizio)
            .putExtra("fine", termine)
            .putExtra("chiusa_prima", chiusaPrima)
        val controller = apriActivity(PaginaSessioneActivity::class.java, intent)
        // L'entrata dura 1,1 s; a 1,6 s gli adesivi galleggiano già. Gli adesivi
        // si disegnano in un altro filo: si aspetta che siano pronti.
        compose.mainClock.advanceTimeBy(1_600)
        Thread.sleep(1_500)
        compose.mainClock.advanceTimeByFrame()
        calma()
        val aperta = controller.comeAperta()
        return Aperta {
            aperta.chiudi()
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun paginaInizio() {
        val adesso = DatiFinti.adesso()
        scatta("16-pagina-sessione-inizio", "Pagina animata dell'inizio di \"Studio\" (fotogramma a 1,6 s)", pagine = false) {
            pagina(fine = false, nome = "Studio", inizio = adesso, termine = adesso + 90 * DatiFinti.MINUTO, chiusaPrima = false, id = 501)
        }
    }

    @Test
    fun paginaFineChiusaPrima() {
        val adesso = DatiFinti.adesso()
        scatta("16-pagina-sessione-fine-chiusa-prima", "Pagina animata della fine di \"Studio\", chiusa prima (fotogramma a 1,6 s)", pagine = false) {
            pagina(fine = true, nome = "Studio", inizio = adesso - 47 * DatiFinti.MINUTO, termine = adesso, chiusaPrima = true, id = 502)
        }
    }

    // --- 17 Copertura delle finestrelle ---------------------------------------------

    @Test
    fun coperturaFinestrelle() {
        scatta("17-copertura-finestrelle", "Copertura delle finestrelle (sopra un video in riquadro durante il blocco)", pagine = false) {
            val sotto = apriVuota()
            CoperturaFinestrelle.aggiorna(app, setOf("com.google.android.youtube"))
            shadowOf(Looper.getMainLooper()).idle()
            calma()
            val aperta = sotto.comeAperta()
            Aperta {
                CoperturaFinestrelle.togli(app)
                shadowOf(Looper.getMainLooper()).idle()
                aperta.chiudi()
            }
        }
    }
}
