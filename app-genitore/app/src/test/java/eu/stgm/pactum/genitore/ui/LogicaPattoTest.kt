package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.UsoApp
import eu.stgm.pactum.genitore.dati.UsoCategoria
import eu.stgm.pactum.genitore.dati.UsoGiorno
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * La logica pura della finestra e del tempo. Se sbaglia, l'app dice al padre
 * una cosa falsa sul patto del figlio: un giorno fuori regola che non c'è, un
 * buco nel registro sparito, una promessa messa in fondo alla lista.
 */
class LogicaPattoTest {

    private val roma: ZoneId = ZoneId.of("Europe/Rome")
    private val oggi: LocalDate = LocalDate.of(2026, 7, 15)

    private fun evento(id: String, ts: String) = EventoFinestra(id = id, tipo = "x", tsServer = ts)

    private fun striscia(vararg segnali: Segnale): List<GiornoPatto> =
        segnali.mapIndexed { i, s -> GiornoPatto("2026-07-%02d".format(8 + i), s) }

    private val M = Segnale.MANTENUTA
    private val F = Segnale.FUORI_REGOLA
    private val N = Segnale.NESSUN_DATO

    // --- la striscia del contratto -------------------------------------------------

    @Test
    fun `la striscia del server diventa giorni di core-design, un grigio resta senza dati`() {
        val giorni = giorniDaQuadretti(
            listOf(
                QuadrettoSemaforo("2026-07-14", "verde"),
                QuadrettoSemaforo("2026-07-15", "grigio"),
                QuadrettoSemaforo("2026-07-16", "rosso"),
            ),
        )
        assertEquals(listOf(M, N, F), giorni.map { it.segnale })
        assertEquals("2026-07-14", giorni.first().data)
    }

    @Test
    fun `senza striscia (server vecchio) non si inventa niente`() {
        assertTrue(giorniDaQuadretti(emptyList()).isEmpty())
    }

    // --- la riga di riepilogo -------------------------------------------------------

    @Test
    fun `i giorni fuori regola sono i quadretti terracotta della striscia`() {
        val r = riepilogoPatto(
            giorni = striscia(M, M, F, M, N, F, M, M),
            sforamenti = emptyList(),
            manomissioni = emptyList(),
            zona = roma,
            oggi = oggi,
        )
        assertEquals(2, r.giorniFuoriRegola)
        assertEquals(0, r.buchiNelRegistro)
    }

    @Test
    fun `con la striscia gli sforamenti non contano due volte`() {
        // Tre sforamenti nello stesso giorno sono UN giorno fuori regola: lo dice
        // la striscia, non il numero di eventi.
        val r = riepilogoPatto(
            giorni = striscia(M, M, M, M, M, M, F, M),
            sforamenti = listOf(
                evento("a", "2026-07-14T08:00:00+00:00"),
                evento("b", "2026-07-14T09:00:00+00:00"),
                evento("c", "2026-07-14T10:00:00+00:00"),
            ),
            manomissioni = emptyList(),
            zona = roma,
            oggi = oggi,
        )
        assertEquals(1, r.giorniFuoriRegola)
    }

    @Test
    fun `i buchi nel registro sono solo quelli degli 8 giorni della striscia`() {
        val r = riepilogoPatto(
            // La striscia parte l'8 luglio.
            giorni = striscia(M, M, M, M, M, M, M, M),
            sforamenti = emptyList(),
            manomissioni = listOf(
                evento("dentro", "2026-07-10T12:00:00+00:00"),
                evento("primo-giorno", "2026-07-08T06:00:00+00:00"),
                evento("prima", "2026-07-07T12:00:00+00:00"),
                evento("data-storta", "ieri sera"),
            ),
            zona = roma,
            oggi = oggi,
        )
        assertEquals(2, r.buchiNelRegistro)
    }

    @Test
    fun `il giorno di un evento si conta nel fuso del telefono`() {
        // 23:30 UTC del 7 luglio = 01:30 dell'8 a Roma: è dentro la finestra.
        val r = riepilogoPatto(
            giorni = striscia(M, M, M, M, M, M, M, M),
            sforamenti = emptyList(),
            manomissioni = listOf(evento("notte", "2026-07-07T23:30:00+00:00")),
            zona = roma,
            oggi = oggi,
        )
        assertEquals(1, r.buchiNelRegistro)
    }

    @Test
    fun `senza striscia i giorni fuori regola sono i giorni distinti degli sforamenti recenti`() {
        val r = riepilogoPatto(
            giorni = emptyList(),
            sforamenti = listOf(
                evento("a", "2026-07-14T08:00:00+00:00"),
                evento("b", "2026-07-14T18:00:00+00:00"),
                evento("c", "2026-07-12T08:00:00+00:00"),
                // Più vecchio di 8 giorni: fuori dalla finestra.
                evento("d", "2026-07-01T08:00:00+00:00"),
            ),
            manomissioni = emptyList(),
            zona = roma,
            oggi = oggi,
        )
        assertEquals(2, r.giorniFuoriRegola)
    }

    @Test
    fun `niente da dire = zero e zero`() {
        val r = riepilogoPatto(striscia(M, M, N), emptyList(), emptyList(), roma, oggi)
        assertEquals(RiepilogoPatto(0, 0), r)
    }

    // --- da guardare insieme ----------------------------------------------------

    @Test
    fun `fuori regola e buchi si fondono in una lista sola, dal piu recente`() {
        val lista = daGuardareInsieme(
            sforamenti = listOf(
                evento("s1", "2026-07-14T10:00:00+00:00"),
                evento("s2", "2026-07-12T10:00:00+00:00"),
            ),
            manomissioni = listOf(
                evento("m1", "2026-07-13T10:00:00+00:00"),
                evento("m2", "2026-07-15T10:00:00+00:00"),
            ),
        )
        assertEquals(listOf("m2", "s1", "m1", "s2"), lista.map { it.evento.id })
        assertEquals(GenereVoce.BUCO_NEL_REGISTRO, lista.first().genere)
        assertEquals(GenereVoce.FUORI_REGOLA, lista[1].genere)
    }

    @Test
    fun `una data illeggibile va in fondo, non sparisce`() {
        val lista = daGuardareInsieme(
            sforamenti = listOf(evento("storto", "boh")),
            manomissioni = listOf(evento("buono", "2026-07-01T10:00:00+00:00")),
        )
        assertEquals(listOf("buono", "storto"), lista.map { it.evento.id })
    }

    @Test
    fun `a parita di istante prima il fuori regola`() {
        val ts = "2026-07-14T10:00:00+00:00"
        val lista = daGuardareInsieme(
            sforamenti = listOf(evento("s", ts)),
            manomissioni = listOf(evento("m", ts)),
        )
        assertEquals(listOf("s", "m"), lista.map { it.evento.id })
    }

    @Test
    fun `lista vuota se non c'e niente da guardare`() {
        assertTrue(daGuardareInsieme(emptyList(), emptyList()).isEmpty())
    }

    // --- il segno -------------------------------------------------------------------

    @Test
    fun `il segno e spento se il server dice che oggi e partito`() {
        assertTrue(segnoGiaMandato(segnoOggi = true, mandatoIl = null, oggi = oggi))
    }

    @Test
    fun `il segno e spento se e partito da qui oggi, anche prima che la finestra lo sappia`() {
        assertTrue(segnoGiaMandato(segnoOggi = false, mandatoIl = oggi, oggi = oggi))
    }

    @Test
    fun `il segno di ieri non spegne il pulsante di oggi`() {
        assertFalse(segnoGiaMandato(segnoOggi = false, mandatoIl = oggi.minusDays(1), oggi = oggi))
        assertFalse(segnoGiaMandato(segnoOggi = false, mandatoIl = null, oggi = oggi))
    }

    // --- tempo: dentro il patto / il resto della giornata ---------------------------

    private fun giorno(app: List<UsoApp>, categorie: List<UsoCategoria> = emptyList()) =
        UsoGiorno(giorno = "2026-07-15", totaleMinuti = 300, app = app, categorie = categorie)

    @Test
    fun `dentro il patto le voci sono ordinate per vicinanza al limite, non per minuti`() {
        val elenco = elencoTempo(
            giorno(
                listOf(
                    // 100 su 200 = metà: tanti minuti, ma lontano dal limite.
                    UsoApp("youtube", "YouTube", minuti = 100, limite = 200),
                    // 50 su 60: il più tirato.
                    UsoApp("tiktok", "TikTok", minuti = 50, limite = 60),
                    // 80 su 60: oltre, quindi in cima.
                    UsoApp("insta", "Instagram", minuti = 80, limite = 60),
                ),
            ),
        )
        assertEquals(listOf("insta", "tiktok", "youtube"), elenco.dentroIlPatto.map { it.chiave })
    }

    @Test
    fun `le categorie con un limite entrano nel patto accanto alle app`() {
        val elenco = elencoTempo(
            giorno(
                app = listOf(UsoApp("tiktok", "TikTok", minuti = 30, limite = 60)),
                categorie = listOf(
                    UsoCategoria("categoria:social", minuti = 110, limite = 120),
                    // Senza limite: vive nella legenda della ciambella, non qui.
                    UsoCategoria("categoria:video", minuti = 90, limite = null),
                    // Con limite ma senza uso: niente da raccontare.
                    UsoCategoria("categoria:giochi", minuti = 0, limite = 30),
                ),
            ),
        )
        assertEquals(
            listOf("categoria:social", "tiktok"),
            elenco.dentroIlPatto.map { it.chiave },
        )
        assertTrue(elenco.dentroIlPatto.first().categoria)
    }

    @Test
    fun `il resto della giornata sono le app senza limite, dalla piu usata`() {
        val elenco = elencoTempo(
            giorno(
                listOf(
                    UsoApp("meteo", "Meteo", minuti = 5),
                    UsoApp("tiktok", "TikTok", minuti = 50, limite = 60),
                    UsoApp("chrome", "Chrome", minuti = 40),
                    UsoApp("whatsapp", "WhatsApp", minuti = 40),
                ),
            ),
        )
        assertEquals(
            listOf("chrome", "whatsapp", "meteo"),
            elenco.restoDellaGiornata.map { it.chiave },
        )
        // La scala delle barre senza limite è l'app più usata del giorno, patto o no.
        assertEquals(50, elenco.massimoDelGiorno)
    }

    @Test
    fun `nessuna app nessun elenco`() {
        val elenco = elencoTempo(giorno(emptyList()))
        assertTrue(elenco.dentroIlPatto.isEmpty())
        assertTrue(elenco.restoDellaGiornata.isEmpty())
        assertEquals(0, elenco.massimoDelGiorno)
    }

    @Test
    fun `un limite a zero non fa dividere per zero`() {
        val voce = VoceTempo("x", null, minuti = 10, limite = 0, categoria = false)
        assertEquals(10.0, vicinanzaAlLimite(voce), 0.0)
    }

    @Test
    fun `quanto oltre il limite, mai negativo`() {
        assertEquals(20, minutiOltre(80, 60))
        assertEquals(0, minutiOltre(60, 60))
        assertEquals(0, minutiOltre(30, 60))
        assertEquals(0, minutiOltre(500, null))
    }

    // --- l'orario del server --------------------------------------------------------

    @Test
    fun `un ts_server malformato non fa crashare, e null`() {
        assertEquals(null, istanteServer("non è una data"))
        assertEquals(null, istanteServer(null))
        assertEquals(null, istanteServer(""))
        assertTrue(istanteServer("2026-07-14T09:00:00+00:00") != null)
    }
}
