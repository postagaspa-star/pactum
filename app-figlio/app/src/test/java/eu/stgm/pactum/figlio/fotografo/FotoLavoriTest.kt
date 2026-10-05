package eu.stgm.pactum.figlio.fotografo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.ArchivioCodaFoto
import eu.stgm.pactum.figlio.faccende.MemoriaBlocco
import eu.stgm.pactum.figlio.ui.FaccendeViewModel
import org.junit.Test

/** 06 — La scheda Lavori (pagina "Lavori di casa"): lo stato del blocco compatto, un pulsante per lavoro, i fatti chiusi. */
class FotoLavoriTest : Fotografo() {

    private fun lavori(memoria: MemoriaBlocco, stato: FaccendeViewModel.StatoFaccende = DatiFinti.faccendeLette()): Aperta {
        return apriPactum(StatiFinti(faccende = stato), MainActivity.DEST_FACCENDE).comeAperta()
    }

    private fun prepara(memoria: MemoriaBlocco, conCoda: Boolean = true) {
        Mondo.collegato(app)
        ArchivioBlocco.modifica(app) { memoria }
        if (conCoda) ArchivioCodaFoto.modifica(app) { DatiFinti.codaFoto() to emptyList() }
    }

    @Test
    fun bloccoAttivo() {
        prepara(DatiFinti.bloccoAttivo())
        scatta("06-lavori-bloccato", "Lavori di casa, telefono bloccato: card del blocco, 3 da fare (foto in coda, bocciata 2 volte, foto rifiutata), \"Fatti e annullati\" chiusi", Variante.SCHEDE, pagine = true) {
            lavori(DatiFinti.bloccoAttivo())
        }
    }

    @Test
    fun bloccoProgrammato() {
        prepara(DatiFinti.bloccoProgrammato(), conCoda = false)
        scatta("06-lavori-blocco-fra-poco", "Lavori di casa, non ancora bloccato: una riga \"alle … il telefono si blocca\"") {
            lavori(DatiFinti.bloccoProgrammato())
        }
    }

    @Test
    fun tuttoFatto() {
        prepara(DatiFinti.tuttoFatto(), conCoda = false)
        scatta("06-lavori-tutto-fatto", "Lavori di casa, niente da fare (stato vuoto) e i lavori chiusi negli ultimi 30 giorni") {
            lavori(DatiFinti.tuttoFatto())
        }
    }

    @Test
    fun fattiAperti() {
        prepara(DatiFinti.tuttoFatto(), conCoda = false)
        scatta("06-lavori-fatti-aperti", "Lavori di casa, \"Fatti e annullati\" aperto") {
            apriPactum(StatiFinti(faccende = DatiFinti.faccendeLette()), MainActivity.DEST_FACCENDE)
                .also { tocca("Fatti e annullati", sottostringa = true) }.comeAperta()
        }
    }

    @Test
    fun ricerca() {
        prepara(DatiFinti.tuttoFatto(), conCoda = false)
        scatta("06-lavori-ricerca", "(0.17) \"Fatti e annullati\" aperto, \"Cerca un lavoro\": «letto» su tutta la storia (da fare, confermato, annullato, vecchio senza foto)", pagine = true) {
            apriPactum(StatiFinti(faccende = DatiFinti.ricercaLetto()), MainActivity.DEST_FACCENDE)
                .also { tocca("Fatti e annullati", sottostringa = true) }.comeAperta()
        }
    }

    @Test
    fun ricercaServerVecchio() {
        prepara(DatiFinti.tuttoFatto(), conCoda = false)
        scatta("06-lavori-ricerca-server-vecchio", "(0.17) La ricerca con un server di prima della v3.9: «serve aggiornare il server»", pagine = false) {
            apriPactum(StatiFinti(faccende = DatiFinti.ricercaServerVecchio()), MainActivity.DEST_FACCENDE)
                .also { tocca("Fatti e annullati", sottostringa = true) }.comeAperta()
        }
    }

    @Test
    fun senzaRete() {
        prepara(DatiFinti.bloccoAttivo())
        val stato = DatiFinti.faccendeLette().copy(datiFermi = true)
        scatta("06-lavori-senza-rete", "Lavori di casa senza rete: \"Dati non aggiornati\" sopra il blocco") {
            lavori(DatiFinti.bloccoAttivo(), stato)
        }
    }

    @Test
    fun scollegato() {
        prepara(DatiFinti.tuttoFatto().copy(scollegato = true), conCoda = false)
        val stato = DatiFinti.faccendeLette().copy(scollegato = true)
        scatta("06-lavori-scollegato", "Lavori di casa con il telefono scollegato (401): blocco tolto", pagine = false) {
            lavori(DatiFinti.tuttoFatto(), stato)
        }
    }

    @Test
    fun fotoAperta() {
        prepara(DatiFinti.tuttoFatto(), conCoda = false)
        val stato = DatiFinti.faccendeLette().copy(
            fotoAperta = FaccendeViewModel.FotoAperta(55, "Stendere il bucato", fotoFinta()),
        )
        scatta("06-lavori-foto-aperta", "Lavori di casa, \"Vedi la foto\" di un lavoro fatto (dialogo con la foto)", pagine = false) {
            lavori(DatiFinti.tuttoFatto(), stato)
        }
    }

    /** Una "foto" finta: un bucato steso in giardino, disegnato a mano. */
    private fun fotoFinta(): Bitmap {
        val foto = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val tela = Canvas(foto)
        tela.drawColor(Color.rgb(0x9C, 0xC9, 0xE8))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(0x6D, 0xA0, 0x4A)
        tela.drawRect(0f, 640f, 1200f, 900f, p)
        p.color = Color.rgb(0x55, 0x55, 0x55)
        p.strokeWidth = 6f
        tela.drawLine(80f, 220f, 1120f, 250f, p)
        val colori = listOf(0xFFE57373, 0xFF64B5F6, 0xFFFFF176, 0xFF81C784, 0xFFBA68C8).map { it.toInt() }
        colori.forEachIndexed { i, c ->
            p.color = c
            val x = 140f + i * 190f
            tela.drawRect(x, 230f + i * 6, x + 140f, 420f + i * 6, p)
        }
        return foto
    }
}
