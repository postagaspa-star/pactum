package eu.stgm.pactum.figlio.sessione

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.12) Le pagine animate di una Sessione: quella della fine si mostra una
 * volta sola, entro 2 ore; sopra le altre app solo nei primi 10 minuti, mai a
 * schermo spento o bloccato né sopra una chiamata, mai dopo la notifica;
 * "fatta" solo quando si vede. Quella dell'inizio solo appena confermata. Gli
 * adesivi: da 14 a 20, mai sulla scheda, fermi con "Rimuovi animazioni".
 */
class PagineSessioneTest {

    private val min = 60_000L
    private val ora = 1_790_000_000_000L

    private fun svolta(
        id: Long,
        inizio: Long = ora,
        durata: Long = 60 * min,
        paginaFine: PaginaFine? = PaginaFine.ATTESA,
        fineLocale: Long? = null,
        fineServer: Long? = null,
        chiusura: String? = null,
    ) = SvoltaLocale(
        id = id,
        sessioneId = 3,
        nome = "Studio",
        app = listOf("eu.spaggiari.classevivafamiglia"),
        inizio = inizio,
        finePrevista = inizio + durata,
        fineServer = fineServer,
        fineLocale = fineLocale,
        chiusura = chiusura,
        paginaFine = paginaFine,
    )

    // --- quale pagina della fine -------------------------------------------------

    @Test
    fun `finita da poco e ancora da mostrare - si mostra`() {
        val finita = svolta(12)
        assertEquals(12L, PagineSessione.daMostrare(listOf(finita), ora + 70 * min)?.id)
        // Avvisata con la notifica: ancora da mostrare (all'apertura di Pactum).
        assertEquals(12L, PagineSessione.daMostrare(listOf(finita.copy(paginaFine = PaginaFine.AVVISATA)), ora + 70 * min)?.id)
    }

    @Test
    fun `una volta sola - fatta non torna`() {
        assertNull(PagineSessione.daMostrare(listOf(svolta(12, paginaFine = PaginaFine.FATTA)), ora + 70 * min))
    }

    @Test
    fun `mai per una sessione finita da 2 ore o piu'`() {
        val finita = svolta(12)
        assertEquals(12L, PagineSessione.daMostrare(listOf(finita), ora + 60 * min + 2 * 60 * min - 1)?.id)
        assertNull(PagineSessione.daMostrare(listOf(finita), ora + 60 * min + 2 * 60 * min))
        assertNull(PagineSessione.daMostrare(listOf(finita), ora + 10 * 60 * min))
    }

    @Test
    fun `mai per una sessione ancora in corso`() {
        assertNull(PagineSessione.daMostrare(listOf(svolta(12)), ora + 30 * min))
        // Alla fine esatta sì.
        assertEquals(12L, PagineSessione.daMostrare(listOf(svolta(12)), ora + 60 * min)?.id)
    }

    @Test
    fun `mai per una sessione senza pagina - di prima della 0_12, o che il ragazzo non sapeva partita`() {
        assertNull(PagineSessione.daMostrare(listOf(svolta(12, paginaFine = null)), ora + 70 * min))
    }

    @Test
    fun `terminata prima - vale la fine vera`() {
        // Chiusa dal server dopo 20 minuti: la pagina c'è già alle +25.
        val chiusa = svolta(12, fineServer = ora + 20 * min, chiusura = ChiusureSessione.TERMINATA)
        assertEquals(12L, PagineSessione.daMostrare(listOf(chiusa), ora + 25 * min)?.id)
    }

    @Test
    fun `due da mostrare - l'ultima finita`() {
        val prima = svolta(11, inizio = ora - 90 * min, durata = 30 * min)
        val seconda = svolta(12, inizio = ora, durata = 30 * min)
        assertEquals(12L, PagineSessione.daMostrare(listOf(seconda, prima), ora + 40 * min)?.id)
    }

    // --- come mostrarla ---------------------------------------------------------------

    /** La sessione finisce a ora + 60 minuti: [dopo] = quanto dopo la fine si guarda. */
    private fun come(
        svolta: SvoltaLocale = svolta(12),
        dopo: Long = 1 * min,
        schermo: Boolean = true,
        sbloccato: Boolean = true,
        chiamata: Boolean = false,
        mostraSopra: Boolean = true,
        pactum: Boolean = false,
    ) = PagineSessione.come(svolta, svolta.fine + dopo, schermo, sbloccato, chiamata, mostraSopra, pactum)

    @Test
    fun `appena finita, a schermo acceso e sbloccato, con Mostra sopra le altre app, si apre sopra l'app in uso`() {
        assertEquals(PagineSessione.Come.APRI_SOPRA, come(dopo = 0))
        assertEquals(PagineSessione.Come.APRI_SOPRA, come(dopo = 9 * min + 59_000))
    }

    @Test
    fun `sopra le altre app solo nei primi 10 minuti - poi la notifica, e la pagina in Pactum`() {
        assertEquals(PagineSessione.Come.NOTIFICA, come(dopo = 10 * min))
        assertEquals(PagineSessione.Come.NOTIFICA, come(dopo = 90 * min))
        // In Pactum sì, entro le 2 ore.
        assertEquals(PagineSessione.Come.APRI_IN_PACTUM, come(dopo = 90 * min, pactum = true))
    }

    @Test
    fun `finita a schermo spento - allo sblocco entro 10 minuti sopra l'app, dopo solo la notifica`() {
        assertEquals(PagineSessione.Come.ASPETTA, come(dopo = 3 * min, schermo = false))
        assertEquals(PagineSessione.Come.ASPETTA, come(dopo = 3 * min, sbloccato = false))
        assertEquals(PagineSessione.Come.APRI_SOPRA, come(dopo = 8 * min))
        assertEquals(PagineSessione.Come.ASPETTA, come(dopo = 30 * min, schermo = false))
        assertEquals(PagineSessione.Come.NOTIFICA, come(dopo = 30 * min))
    }

    @Test
    fun `mai sopra una chiamata`() {
        assertEquals(PagineSessione.Come.ASPETTA, come(chiamata = true))
        assertEquals(PagineSessione.Come.ASPETTA, come(chiamata = true, pactum = true))
    }

    @Test
    fun `senza Mostra sopra le altre app - una notifica, e la pagina all'apertura di Pactum`() {
        assertEquals(PagineSessione.Come.NOTIFICA, come(mostraSopra = false))
        // Con Pactum davanti la pagina si apre lì.
        assertEquals(PagineSessione.Come.APRI_IN_PACTUM, come(mostraSopra = false, pactum = true))
    }

    @Test
    fun `una pagina gia' avvisata con la notifica non si apre piu' sopra le altre app`() {
        val avvisata = svolta(12, paginaFine = PaginaFine.AVVISATA)
        assertEquals(PagineSessione.Come.NIENTE, come(avvisata, dopo = 1 * min))
        assertEquals(PagineSessione.Come.NIENTE, come(avvisata, dopo = 1 * min, mostraSopra = false))
        // Solo in Pactum.
        assertEquals(PagineSessione.Come.APRI_IN_PACTUM, come(avvisata, pactum = true))
    }

    @Test
    fun `la notifica se ne va da sola alle 2 ore dalla fine`() {
        val finita = svolta(12)
        assertEquals(2 * 60 * min, PagineSessione.validaAncora(finita, finita.fine))
        assertEquals(90 * min, PagineSessione.validaAncora(finita, finita.fine + 30 * min))
        assertEquals(1_000L, PagineSessione.validaAncora(finita, finita.fine + 3 * 60 * min))
    }

    @Test
    fun `la pagina resta 3,5 secondi, o di piu' se l'accessibilita' lo chiede`() {
        assertEquals(3_500L, PagineSessione.durataPagina(null))
        assertEquals(3_500L, PagineSessione.durataPagina(2_000))
        assertEquals(10_000L, PagineSessione.durataPagina(10_000))
    }

    @Test
    fun `la pagina dell'inizio solo se la conferma e' di adesso`() {
        assertTrue(PagineSessione.inizioDaMostrare(svolta(12, inizio = ora - 5_000), ora))
        // L'orologio del server un po' avanti.
        assertTrue(PagineSessione.inizioDaMostrare(svolta(12, inizio = ora + 30_000), ora))
        // La risposta letta tornando nella scheda dieci minuti dopo: niente festa.
        assertFalse(PagineSessione.inizioDaMostrare(svolta(12, inizio = ora - 10 * min), ora))
        assertFalse(PagineSessione.inizioDaMostrare(null, ora))
    }

    // --- le parole -------------------------------------------------------------------

    @Test
    fun `quanto e' durata davvero`() {
        assertEquals(DurataSvolta(0, 45), PagineSessione.durata(ora, ora + 45 * min))
        assertEquals(DurataSvolta(1, 30), PagineSessione.durata(ora, ora + 90 * min + 59_000))
        assertEquals(DurataSvolta(2, 0), PagineSessione.durata(ora, ora + 120 * min))
        assertEquals(DurataSvolta(0, 0), PagineSessione.durata(ora, ora + 59_000))
        assertEquals(DurataSvolta(0, 0), PagineSessione.durata(ora, ora - 5_000))
    }

    @Test
    fun `chiusa prima della fine - dal ragazzo o dal server, mai se finita da sola`() {
        assertTrue(PagineSessione.chiusaPrima(svolta(12, fineLocale = ora + 20 * min)))
        assertTrue(PagineSessione.chiusaPrima(svolta(12, fineServer = ora + 20 * min, chiusura = ChiusureSessione.TERMINATA)))
        assertFalse(PagineSessione.chiusaPrima(svolta(12, fineServer = ora + 60 * min, chiusura = ChiusureSessione.SCADUTA)))
        assertFalse(PagineSessione.chiusaPrima(svolta(12)))
    }

    @Test
    fun `con Rimuovi animazioni gli adesivi stanno fermi`() {
        assertTrue(PagineSessione.statiche(0f))
        assertFalse(PagineSessione.statiche(1f))
        assertFalse(PagineSessione.statiche(0.5f))
        // Fermi = al loro posto fin dal primo istante, scheda compresa.
        for (a in AdesiviSessione.disponi(6, 1L)) assertEquals(1f, AdesiviSessione.entrata(1f, a.ritardo), 0.0001f)
        assertEquals(1f, AdesiviSessione.scalaScheda(1f), 0.0001f)
        assertEquals(1f, AdesiviSessione.alfaScheda(1f), 0.0001f)
    }

    // --- una volta sola, nella memoria della sessione -----------------------------------

    @Test
    fun `avviata qui o annunciata - la pagina della fine e' dovuta`() {
        val avviata = MemoriaSessioni().conAvvio(svolta(12, paginaFine = null), ora)
        assertEquals(PaginaFine.ATTESA, avviata.svolte.single().paginaFine)
        val dalServer = MemoriaSessioni(svolte = listOf(svolta(13, paginaFine = null).copy(annunciata = false)))
        assertEquals(PaginaFine.ATTESA, dalServer.conAnnuncio(13).svolte.single().paginaFine)
        // Annunciata di nuovo dopo: quella già fatta resta fatta.
        val fatta = MemoriaSessioni(svolte = listOf(svolta(14, paginaFine = PaginaFine.FATTA)))
        assertEquals(PaginaFine.FATTA, fatta.conAnnuncio(14).svolte.single().paginaFine)
    }

    @Test
    fun `una sessione che il telefono non conosceva entra senza pagina`() {
        val dopo = MemoriaSessioni().conServer(listOf(svolta(12, paginaFine = null)), svolta(12, paginaFine = null), ora + 5 * min)
        assertNull(dopo.svolte.single().paginaFine)
    }

    @Test
    fun `Termina la sessione in Pactum - la pagina resta da mostrare finche' non si vede`() {
        val (terminata, _) = MemoriaSessioni(svolte = listOf(svolta(12))).conTermine(ora + 20 * min)
        assertEquals(PaginaFine.ATTESA, terminata.svolte.single().paginaFine)
        // Se Pactum non riesce ad aprirla, la apre chi arriva dopo.
        assertEquals(12L, PagineSessione.daMostrare(terminata.svolte, ora + 21 * min)?.id)
        // Vista: fatta.
        assertNull(PagineSessione.daMostrare(terminata.conPaginaVista(12).svolte, ora + 21 * min))
        // Una sessione di prima della 0.12 terminata adesso: la sua pagina diventa dovuta.
        val (vecchia, _) = MemoriaSessioni(svolte = listOf(svolta(13, paginaFine = null))).conTermine(ora + 20 * min)
        assertEquals(PaginaFine.ATTESA, vecchia.svolte.single().paginaFine)
    }

    @Test
    fun `le letture del server non toccano la pagina della fine`() {
        val memoria = MemoriaSessioni(svolte = listOf(svolta(12, paginaFine = PaginaFine.AVVISATA)))
        val riletta = memoria.conServer(listOf(svolta(12, paginaFine = null)), null, ora + 70 * min)
        assertEquals(PaginaFine.AVVISATA, riletta.svolte.single().paginaFine)
        // Anche per una sessione avviata qui (orologio del telefono).
        val ancorata = MemoriaSessioni().conAvvio(svolta(13, paginaFine = null).ancorataAlTelefono(ora, 60), ora)
        val rilettaAncorata = ancorata.conServer(listOf(svolta(13, paginaFine = null)), null, ora + 70 * min)
        assertEquals(PaginaFine.ATTESA, rilettaAncorata.svolte.single().paginaFine)
    }

    @Test
    fun `fatta solo quando si vede - da qualunque stato, e poi non torna`() {
        for (prima in listOf(PaginaFine.ATTESA, PaginaFine.AVVISATA, null)) {
            val vista = MemoriaSessioni(svolte = listOf(svolta(12, paginaFine = prima))).conPaginaVista(12)
            assertEquals(PaginaFine.FATTA, vista.svolte.single().paginaFine)
            assertNull(PagineSessione.daMostrare(vista.svolte, ora + 70 * min))
        }
        // Un'altra sessione non cambia.
        val due = MemoriaSessioni(svolte = listOf(svolta(12), svolta(13, inizio = ora + 2 * 60 * min))).conPaginaVista(12)
        assertEquals(PaginaFine.ATTESA, due.svolte.first { it.id == 13L }.paginaFine)
    }

    @Test
    fun `la notifica al posto della pagina parte una volta sola - anche con due strade insieme`() {
        val inAttesa = MemoriaSessioni(svolte = listOf(svolta(12)))
        val (avvisata, primo) = inAttesa.conPaginaAvvisata(12)
        assertTrue(primo)
        assertEquals(PaginaFine.AVVISATA, avvisata.svolte.single().paginaFine)
        // La seconda strada (il giro dopo, lo sblocco) non la manda di nuovo.
        val (_, secondo) = avvisata.conPaginaAvvisata(12)
        assertFalse(secondo)
        // Già vista: niente notifica.
        assertFalse(MemoriaSessioni(svolte = listOf(svolta(12, paginaFine = PaginaFine.FATTA))).conPaginaAvvisata(12).second)
        // Senza pagina dovuta: niente notifica.
        assertFalse(MemoriaSessioni(svolte = listOf(svolta(12, paginaFine = null))).conPaginaAvvisata(12).second)
        // Avvisata, resta da mostrare in Pactum entro le 2 ore.
        assertEquals(12L, PagineSessione.daMostrare(avvisata.svolte, ora + 70 * min)?.id)
    }

    @Test
    fun `l'apertura non riuscita non perde la pagina e non ripete la notifica`() {
        // Il servizio prova ad aprirla e Android dice di no: la pagina resta in attesa
        // (nessuno l'ha vista), e la notifica parte una volta sola.
        var memoria = MemoriaSessioni(svolte = listOf(svolta(12)))
        val dopoUnMinuto = memoria.svolte.single().fine + min
        assertEquals(PagineSessione.Come.APRI_SOPRA, PagineSessione.come(memoria.svolte.single(), dopoUnMinuto, true, true, false, true, false))
        val (avvisata, notifica) = memoria.conPaginaAvvisata(12)
        assertTrue(notifica)
        memoria = avvisata
        // Il giro dopo: non si riprova sopra le altre app, e non si rimanda la notifica.
        assertEquals(PagineSessione.Come.NIENTE, PagineSessione.come(memoria.svolte.single(), dopoUnMinuto + min, true, true, false, true, false))
        assertFalse(memoria.conPaginaAvvisata(12).second)
        // Aperto Pactum: la pagina si vede, e allora è fatta.
        assertEquals(12L, PagineSessione.daMostrare(memoria.svolte, dopoUnMinuto + 30 * min)?.id)
        assertNull(PagineSessione.daMostrare(memoria.conPaginaVista(12).svolte, dopoUnMinuto + 30 * min))
    }

    @Test
    fun `la pagina della fine fa il giro sul disco, e un file di prima si legge senza`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val memoria = MemoriaSessioni(svolte = listOf(svolta(12, paginaFine = PaginaFine.AVVISATA), svolta(13, inizio = ora + 2 * 60 * min, paginaFine = null)))
        val testo = json.encodeToString(MemoriaSessioni.serializer(), memoria)
        assertTrue(testo.contains("\"pagina_fine\":\"avvisata\""))
        assertEquals(memoria, json.decodeFromString(MemoriaSessioni.serializer(), testo))
        val vecchio = """{"svolte":[{"id":12,"inizio":$ora,"fine_prevista":${ora + 60 * min}}],"terminazioni":[]}"""
        assertNull(json.decodeFromString(MemoriaSessioni.serializer(), vecchio).svolte.single().paginaFine)
    }

    // --- gli adesivi --------------------------------------------------------------------

    @Test
    fun `da 14 a 20 adesivi, sempre gli stessi per lo stesso seme`() {
        val quanti = (1L..200L).map { AdesiviSessione.disponi(6, it).size }
        assertTrue(quanti.all { it in AdesiviSessione.MINIMI..AdesiviSessione.MASSIMI })
        assertTrue("non sempre lo stesso numero", quanti.toSet().size > 1)
        assertEquals(AdesiviSessione.disponi(6, 42L), AdesiviSessione.disponi(6, 42L))
    }

    @Test
    fun `mai sopra la scheda al centro, tutti dentro lo schermo, di misure e inclinazioni diverse`() {
        for (seme in 1L..200L) {
            val adesivi = AdesiviSessione.disponi(4, seme)
            for (a in adesivi) {
                assertFalse("seme $seme: (${a.x}, ${a.y})", AdesiviSessione.inScheda(a.x, a.y))
                assertTrue(a.x in 0f..1f && a.y in 0f..1f)
                assertTrue(a.lato in AdesiviSessione.LATO_MINIMO_DP..AdesiviSessione.LATO_MASSIMO_DP)
                assertTrue(a.rotazione in -AdesiviSessione.ROTAZIONE_MASSIMA..AdesiviSessione.ROTAZIONE_MASSIMA)
                assertTrue(a.ritardo in 0f..AdesiviSessione.RITARDO_MASSIMO)
                assertTrue(a.emoji in 0 until 4)
            }
            assertTrue(adesivi.map { it.lato }.toSet().size > 1)
        }
    }

    @Test
    fun `tutte le emoji del tema ci sono`() {
        val adesivi = AdesiviSessione.disponi(6, 7L)
        assertEquals((0 until 6).toSet(), adesivi.map { it.emoji }.toSet())
    }

    @Test
    fun `ogni adesivo entra dal bordo piu' vicino, da fuori dallo schermo`() {
        assertEquals(Bordo.SINISTRA, AdesiviSessione.bordoVicino(0.05f, 0.5f))
        assertEquals(Bordo.DESTRA, AdesiviSessione.bordoVicino(0.95f, 0.5f))
        assertEquals(Bordo.SOPRA, AdesiviSessione.bordoVicino(0.5f, 0.05f))
        assertEquals(Bordo.SOTTO, AdesiviSessione.bordoVicino(0.5f, 0.95f))
        for (a in AdesiviSessione.disponi(6, 3L)) {
            assertTrue(a.xDa < 0f || a.xDa > 1f || a.yDa < 0f || a.yDa > 1f)
        }
    }

    @Test
    fun `l'entrata - fermi fuori, poi un piccolo rimbalzo, poi al loro posto`() {
        assertEquals(0f, AdesiviSessione.entrata(0f, 0f), 0.0001f)
        assertEquals(0f, AdesiviSessione.entrata(0.2f, 0.3f), 0.0001f)
        assertEquals(1f, AdesiviSessione.entrata(1f, 0.3f), 0.0001f)
        // A metà strada va un filo oltre il suo posto: il rimbalzo.
        assertTrue((0..100).map { AdesiviSessione.rimbalzo(it / 100f) }.max() > 1f)
        assertEquals(0f, AdesiviSessione.rimbalzo(0f), 0.0001f)
        assertEquals(1f, AdesiviSessione.rimbalzo(1f), 0.0001f)
    }
}
