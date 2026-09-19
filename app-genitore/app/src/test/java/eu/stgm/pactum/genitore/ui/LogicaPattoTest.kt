package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.RiepilogoFinestra
import eu.stgm.pactum.genitore.dati.UsoApp
import eu.stgm.pactum.genitore.dati.UsoCategoria
import eu.stgm.pactum.genitore.dati.UsoGiorno
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * La logica pura della finestra e del tempo. Se sbaglia, l'app dice al padre
 * una cosa falsa sul patto del figlio: un giorno fuori regola che non c'è,
 * un'interruzione sparita, una promessa messa in fondo alla lista.
 */
class LogicaPattoTest {

    private val roma: ZoneId = ZoneId.of("Europe/Rome")
    private val oggi: LocalDate = LocalDate.of(2026, 7, 15)

    private val M = Segnale.MANTENUTA
    private val F = Segnale.FUORI_REGOLA
    private val N = Segnale.NESSUN_DATO

    private fun evento(id: String, ts: String) = EventoFinestra(id = id, tipo = "x", tsServer = ts)

    /** Uno sforamento col `giorno` nei dettagli (v2.4): il giorno in cui è successo. */
    private fun sforamento(id: String, ts: String, giorno: String) = EventoFinestra(
        id = id,
        tipo = "sforamento",
        dettagli = buildJsonObject {
            put("regola_id", 1)
            put("giorno", giorno)
        },
        tsServer = ts,
    )

    // Otto giorni dall'8 al 15 luglio: oggi è il 15.
    private fun striscia(vararg segnali: Segnale): List<GiornoPatto> =
        segnali.mapIndexed { i, s -> GiornoPatto("2026-07-%02d".format(8 + i), s) }

    private val ottoGiorni: List<GiornoPatto> get() = striscia(M, M, M, M, M, M, M, M)

    private fun riepilogo(
        giorni: List<GiornoPatto>,
        sforamenti: List<EventoFinestra> = emptyList(),
        manomissioni: List<EventoFinestra> = emptyList(),
        dalServer: RiepilogoFinestra? = null,
        zona: ZoneId = roma,
    ) = riepilogoPatto(dalServer, giorni, sforamenti, manomissioni, zona, oggi)

    private fun daGuardare(
        sforamenti: List<EventoFinestra>,
        manomissioni: List<EventoFinestra>,
        giorni: List<GiornoPatto> = ottoGiorni,
    ) = daGuardareInsieme(sforamenti, manomissioni, giorni, roma, oggi)

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

    @Test
    fun `gli 8 giorni sono le date della striscia, senza striscia oggi e i 7 prima`() {
        assertEquals(
            (8..15).map { LocalDate.of(2026, 7, it) }.toSet(),
            giorniDellaFinestra(ottoGiorni, oggi),
        )
        assertEquals(
            (8..15).map { LocalDate.of(2026, 7, it) }.toSet(),
            giorniDellaFinestra(emptyList(), oggi),
        )
    }

    // --- la riga di riepilogo: quella del server ------------------------------------

    @Test
    fun `il riepilogo del server vince sul conto dell'app`() {
        // Il server conta su tutto il registro e nel fuso del patto: l'app, coi
        // suoi 20 eventi, direbbe 1 e 0. Vale il server.
        val r = riepilogo(
            giorni = striscia(M, M, M, M, M, M, F, M),
            manomissioni = emptyList(),
            dalServer = RiepilogoFinestra(giorniFuoriRegola = 3, interruzioni = 2),
        )
        assertEquals(RiepilogoPatto(3, 2), r)
    }

    @Test
    fun `anche uno zero del server vale, non si ricalcola`() {
        val r = riepilogo(
            giorni = striscia(F, M, M, M, M, M, M, M),
            manomissioni = listOf(evento("m", "2026-07-10T12:00:00+00:00")),
            dalServer = RiepilogoFinestra(giorniFuoriRegola = 0, interruzioni = 0),
        )
        assertEquals(RiepilogoPatto(0, 0), r)
    }

    // --- la riga di riepilogo: il ripiego per un server vecchio ---------------------

    @Test
    fun `i giorni fuori regola sono i quadretti terracotta della striscia`() {
        val r = riepilogo(giorni = striscia(M, M, F, M, N, F, M, M))
        assertEquals(2, r.giorniFuoriRegola)
        assertEquals(0, r.interruzioni)
    }

    @Test
    fun `con la striscia gli sforamenti non contano due volte`() {
        // Tre sforamenti nello stesso giorno sono UN giorno fuori regola: lo dice
        // la striscia, non il numero di eventi.
        val r = riepilogo(
            giorni = striscia(M, M, M, M, M, M, F, M),
            sforamenti = listOf(
                evento("a", "2026-07-14T08:00:00+00:00"),
                evento("b", "2026-07-14T09:00:00+00:00"),
                evento("c", "2026-07-14T10:00:00+00:00"),
            ),
        )
        assertEquals(1, r.giorniFuoriRegola)
    }

    @Test
    fun `le interruzioni sono solo quelle degli 8 giorni della striscia`() {
        val r = riepilogo(
            // La striscia parte l'8 luglio.
            giorni = ottoGiorni,
            manomissioni = listOf(
                evento("dentro", "2026-07-10T12:00:00+00:00"),
                evento("primo-giorno", "2026-07-08T06:00:00+00:00"),
                evento("prima", "2026-07-07T12:00:00+00:00"),
                evento("data-storta", "ieri sera"),
            ),
        )
        assertEquals(2, r.interruzioni)
    }

    @Test
    fun `il giorno di un evento si conta nel fuso del patto, non in quello di chi legge`() {
        // 23:30 UTC del 7 luglio = 01:30 dell'8 a Roma: è dentro la finestra.
        val notte = listOf(evento("notte", "2026-07-07T23:30:00+00:00"))
        assertEquals(1, riepilogo(giorni = ottoGiorni, manomissioni = notte).interruzioni)
        // Un genitore a New York (fuso del telefono) lo metterebbe il 7: fuori.
        // Per questo la finestra passa FUSO_PATTO e mai ZoneId.systemDefault().
        val newYork = ZoneId.of("America/New_York")
        assertEquals(0, riepilogo(giorni = ottoGiorni, manomissioni = notte, zona = newYork).interruzioni)
        assertEquals(roma, FUSO_PATTO)
    }

    @Test
    fun `senza striscia i giorni fuori regola sono i giorni distinti degli sforamenti recenti`() {
        val r = riepilogo(
            giorni = emptyList(),
            sforamenti = listOf(
                evento("a", "2026-07-14T08:00:00+00:00"),
                evento("b", "2026-07-14T18:00:00+00:00"),
                evento("c", "2026-07-12T08:00:00+00:00"),
                // Più vecchio di 8 giorni: fuori dalla finestra.
                evento("d", "2026-07-01T08:00:00+00:00"),
            ),
        )
        assertEquals(2, r.giorniFuoriRegola)
    }

    @Test
    fun `senza striscia uno sforamento arrivato tardi conta nel giorno dei dettagli`() {
        val r = riepilogo(
            giorni = emptyList(),
            sforamenti = listOf(
                // Successo il 12, arrivato il 14: è il 12.
                sforamento("tardi", "2026-07-14T08:00:00+00:00", giorno = "2026-07-12"),
                evento("puntuale", "2026-07-12T10:00:00+00:00"),
                // Successo il 5, arrivato il 9: fuori dagli 8 giorni.
                sforamento("vecchio", "2026-07-09T08:00:00+00:00", giorno = "2026-07-05"),
            ),
        )
        assertEquals(1, r.giorniFuoriRegola)
    }

    @Test
    fun `niente da dire = zero e zero`() {
        assertEquals(RiepilogoPatto(0, 0), riepilogo(giorni = striscia(M, M, N)))
    }

    // --- da guardare insieme ----------------------------------------------------

    @Test
    fun `fuori regola e interruzioni si fondono in una lista sola, dal piu recente`() {
        val lista = daGuardare(
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
        assertEquals(GenereVoce.INTERRUZIONE, lista.first().genere)
        assertEquals(GenereVoce.FUORI_REGOLA, lista[1].genere)
    }

    @Test
    fun `solo gli 8 giorni della striscia, mai uno sforamento di 12 giorni fa`() {
        // La riga di riepilogo dice "Nessun giorno fuori regola": la lista sotto
        // non può smentirla con fatti di prima della striscia.
        val lista = daGuardare(
            sforamenti = listOf(
                evento("vecchio", "2026-07-03T10:00:00+00:00"),
                evento("dentro", "2026-07-09T10:00:00+00:00"),
            ),
            manomissioni = listOf(
                evento("vigilia", "2026-07-07T12:00:00+00:00"),
                evento("primo-giorno", "2026-07-08T06:00:00+00:00"),
            ),
        )
        assertEquals(listOf("dentro", "primo-giorno"), lista.map { it.evento.id })
    }

    @Test
    fun `uno sforamento col giorno nei dettagli cade in quel giorno e lo mostra`() {
        val lista = daGuardare(
            sforamenti = listOf(
                // Successo il 10, consegnato il 16 notte: resta nella lista, col 10.
                sforamento("tardi", "2026-07-15T23:30:00+00:00", giorno = "2026-07-10"),
                // Successo il 7 (prima della striscia), arrivato il 9: fuori.
                sforamento("fuori", "2026-07-09T08:00:00+00:00", giorno = "2026-07-07"),
            ),
            manomissioni = listOf(evento("m", "2026-07-12T10:00:00+00:00")),
        )
        assertEquals(listOf("m", "tardi"), lista.map { it.evento.id })
        val tardi = lista.last()
        assertEquals(LocalDate.of(2026, 7, 10), tardi.giorno)
        assertTrue(tardi.giornoDichiarato)
        assertFalse(lista.first().giornoDichiarato)
    }

    @Test
    fun `un giorno nei dettagli che non e una data vale il giorno d'arrivo nel fuso del patto`() {
        val lista = daGuardare(
            // 23:30 UTC del 7 = l'8 a Roma: dentro, col giorno d'arrivo.
            sforamenti = listOf(sforamento("storto", "2026-07-07T23:30:00+00:00", giorno = "ieri")),
            manomissioni = emptyList(),
        )
        assertEquals(1, lista.size)
        assertEquals(LocalDate.of(2026, 7, 8), lista.single().giorno)
        assertFalse(lista.single().giornoDichiarato)
    }

    @Test
    fun `un'interruzione conta nel giorno d'arrivo nel fuso del patto`() {
        val lista = daGuardare(
            sforamenti = emptyList(),
            manomissioni = listOf(evento("notte", "2026-07-07T23:30:00+00:00")),
        )
        assertEquals(LocalDate.of(2026, 7, 8), lista.single().giorno)
    }

    @Test
    fun `un evento senza giorno leggibile resta fuori, come nel riepilogo`() {
        val lista = daGuardare(
            sforamenti = listOf(evento("storto", "boh")),
            manomissioni = listOf(evento("buono", "2026-07-10T10:00:00+00:00")),
        )
        assertEquals(listOf("buono"), lista.map { it.evento.id })
    }

    @Test
    fun `a parita di istante prima il fuori regola`() {
        val ts = "2026-07-14T10:00:00+00:00"
        val lista = daGuardare(
            sforamenti = listOf(evento("s", ts)),
            manomissioni = listOf(evento("m", ts)),
        )
        assertEquals(listOf("s", "m"), lista.map { it.evento.id })
    }

    @Test
    fun `senza striscia la lista usa oggi e i 7 giorni prima`() {
        val lista = daGuardare(
            sforamenti = listOf(
                evento("dentro", "2026-07-08T10:00:00+00:00"),
                evento("fuori", "2026-07-07T10:00:00+00:00"),
            ),
            manomissioni = emptyList(),
            giorni = emptyList(),
        )
        assertEquals(listOf("dentro"), lista.map { it.evento.id })
    }

    @Test
    fun `lista e riga di riepilogo raccontano gli stessi giorni`() {
        val sforamenti = listOf(
            sforamento("tardi", "2026-07-14T08:00:00+00:00", giorno = "2026-07-12"),
            evento("oggi", "2026-07-15T08:00:00+00:00"),
            evento("vecchio", "2026-07-02T08:00:00+00:00"),
        )
        val manomissioni = listOf(
            evento("m-dentro", "2026-07-11T08:00:00+00:00"),
            evento("m-fuori", "2026-07-01T08:00:00+00:00"),
        )
        val r = riepilogo(giorni = emptyList(), sforamenti = sforamenti, manomissioni = manomissioni)
        val lista = daGuardare(sforamenti, manomissioni, giorni = emptyList())
        assertEquals(
            r.giorniFuoriRegola,
            lista.filter { it.genere == GenereVoce.FUORI_REGOLA }.map { it.giorno }.distinct().size,
        )
        assertEquals(r.interruzioni, lista.count { it.genere == GenereVoce.INTERRUZIONE })
    }

    @Test
    fun `un giorno rosso senza eventi (vita reale) e nella riga ma non nella lista`() {
        // Un fallimento dichiarato di una regola di vita reale colora il giorno
        // ma non è né uno sforamento né un'interruzione: nessuna riga qui.
        val giorni = striscia(M, M, M, F, M, M, M, M)
        assertEquals(1, riepilogo(giorni = giorni).giorniFuoriRegola)
        assertTrue(daGuardare(emptyList(), emptyList(), giorni).isEmpty())
    }

    @Test
    fun `lista vuota se non c'e niente da guardare`() {
        assertTrue(daGuardare(emptyList(), emptyList()).isEmpty())
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

    // --- proposte -------------------------------------------------------------------

    @Test
    fun `una regola con una proposta in attesa non ne offre un'altra`() {
        fun proposta(id: Long, regola: Long, stato: String) =
            Proposta(id = id, regolaId = regola, stato = stato)
        val inAttesa = regoleConPropostaInAttesa(
            listOf(
                proposta(1, regola = 1, stato = "pendente"),
                proposta(2, regola = 2, stato = "accettata"),
                proposta(3, regola = 3, stato = "rifiutata"),
                proposta(4, regola = 3, stato = "annullata"),
            ),
        )
        assertEquals(setOf(1L), inAttesa)
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

    @Test
    fun `oltre si conta su limite piu bonus del giorno, come per il figlio`() {
        // +15 concessi, 70 su 60: per il figlio è dentro (75), e così per il padre.
        assertEquals(0, minutiOltre(70, 60, bonus = 15))
        assertEquals(5, minutiOltre(80, 60, bonus = 15))
        // Server vecchio: niente bonus, vale il limite base.
        assertEquals(10, minutiOltre(70, 60))
        // Un bonus senza limite non crea un limite.
        assertEquals(0, minutiOltre(70, null, bonus = 15))
    }

    @Test
    fun `il bonus della voce passa dall'uso recente all'elenco e alla vicinanza`() {
        val elenco = elencoTempo(
            giorno(
                app = listOf(
                    // 70 su 60+15: dentro, al 93%.
                    UsoApp("tiktok", "TikTok", minuti = 70, limite = 60, bonus = 15),
                    // 58 su 60 senza bonus: il più tirato, al 97%.
                    UsoApp("insta", "Instagram", minuti = 58, limite = 60),
                ),
                categorie = listOf(
                    UsoCategoria("categoria:social", minuti = 130, limite = 120, bonus = 30),
                ),
            ),
        )
        assertEquals(listOf("insta", "tiktok", "categoria:social"), elenco.dentroIlPatto.map { it.chiave })
        val tiktok = elenco.dentroIlPatto.first { it.chiave == "tiktok" }
        assertEquals(15, tiktok.bonus)
        assertEquals(75, tiktok.limiteDelGiorno)
        assertEquals(0, minutiOltre(tiktok.minuti, tiktok.limite, tiktok.bonus))
        assertEquals(150, elenco.dentroIlPatto.last().limiteDelGiorno)
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
