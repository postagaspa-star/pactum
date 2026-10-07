package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.CorpoNuoveFaccende
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.rete.EsitoFaccende
import eu.stgm.pactum.genitore.rete.EsitoFoto
import eu.stgm.pactum.genitore.rete.EsitoRicercaFaccende
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.FonteFaccende
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * (0.17, contratto v3.9) I lavori di casa: modificarli, segnarli come svolti,
 * cercarli. Il parsing delle risposte (anche di un server vecchio), le logiche dei
 * pulsanti e il motore con un server finto.
 */
class LavoriModificaTest {

    private val p = ParoleDiProva
    private val roma: ZoneId = ZoneId.of("Europe/Rome")
    private val adesso: Instant = Instant.parse("2026-10-05T13:10:00Z")
    private val oggi: LocalDate = LocalDate.of(2026, 10, 5)
    private val mamma = RiferimentoGenitore(2, "Mamma")
    private val papa = RiferimentoGenitore(1, "Papà")

    private fun lavoro(
        id: Long = 5,
        titolo: String = "Svuota la lavastoviglie",
        nota: String? = null,
        stato: String = StatiFaccenda.DA_FARE,
        bloccoDa: String? = "2026-10-05T14:00:00+00:00",
        fotoTs: String? = null,
        foto: Boolean = false,
        confermataTs: String? = null,
        confermataDa: RiferimentoGenitore? = null,
    ) = Faccenda(
        id = id,
        figlioId = 1,
        titolo = titolo,
        nota = nota,
        stato = stato,
        bloccoDa = bloccoDa,
        creataTs = "2026-10-05T08:00:00+00:00",
        creataDa = mamma,
        fotoTs = fotoTs,
        foto = foto,
        confermataTs = confermataTs,
        confermataDa = confermataDa,
    )

    // --- il parsing --------------------------------------------------------------------------

    @Test
    fun `un server v3_9 sa modificare e confermare, uno v3_8 no`() {
        val nuovo = """{"faccende":[{"id":1,"titolo":"Letto","stato":"fatta","foto":true,"foto_ts":"2026-10-05T10:00:00+00:00",
            "confermata_ts":"2026-10-05T11:00:00+00:00","confermata_da":{"id":2,"nome":"Mamma"}}]}"""
        val lette = PostinoClient.interpretaFaccende(200, nuovo) as EsitoFaccende.Lette
        assertEquals(true, lette.conModifiche)
        assertEquals("2026-10-05T11:00:00+00:00", lette.faccende.single().confermataTs)
        assertEquals(mamma, lette.faccende.single().confermataDa)
        // Il campo c'è anche a null: il server lo conosce.
        val aNull = """{"faccende":[{"id":1,"titolo":"Letto","stato":"da_fare","confermata_ts":null,"confermata_da":null}]}"""
        assertEquals(true, (PostinoClient.interpretaFaccende(200, aNull) as EsitoFaccende.Lette).conModifiche)
        // v3.8: le faccende non lo portano.
        val vecchio = """{"faccende":[{"id":1,"titolo":"Letto","stato":"da_fare"}]}"""
        val letteVecchie = PostinoClient.interpretaFaccende(200, vecchio) as EsitoFaccende.Lette
        assertEquals(false, letteVecchie.conModifiche)
        assertNull(letteVecchie.faccende.single().confermataTs)
        // Elenco vuoto: non si sa (vale quello che si sapeva).
        assertNull((PostinoClient.interpretaFaccende(200, """{"faccende":[]}""") as EsitoFaccende.Lette).conModifiche)
    }

    @Test
    fun `la ricerca - risultati, altre, e un server che ignora cerca`() {
        val trovate = PostinoClient.interpretaRicerca(200, """{"faccende":[{"id":3,"titolo":"Lavatrice","stato":"annullata"}],"altre":true}""")
        assertEquals(EsitoRicercaFaccende.Trovate(listOf(Faccenda(id = 3, titolo = "Lavatrice", stato = "annullata")), altre = true), trovate)
        // Un server v3.8 ignora `cerca` e manda l'elenco di sempre, senza `altre`.
        assertEquals(EsitoRicercaFaccende.ServerVecchio, PostinoClient.interpretaRicerca(200, """{"faccende":[]}"""))
        assertEquals(EsitoRicercaFaccende.ServerVecchio, PostinoClient.interpretaRicerca(405, null))
        assertEquals(EsitoRicercaFaccende.ServerVecchio, PostinoClient.interpretaRicerca(404, """{"detail":"Not Found"}"""))
        assertEquals(EsitoRicercaFaccende.NonAutorizzato, PostinoClient.interpretaRicerca(401, null))
        // Un errore del server non è la rete: 500, il figlio che non c'è, un testo non valido.
        assertEquals(EsitoRicercaFaccende.Errore, PostinoClient.interpretaRicerca(500, null))
        assertEquals(EsitoRicercaFaccende.Errore, PostinoClient.interpretaRicerca(404, """{"detail":"figlio non trovato"}"""))
        assertEquals(EsitoRicercaFaccende.Errore, PostinoClient.interpretaRicerca(422, """{"detail":[]}"""))
        assertEquals(EsitoRicercaFaccende.Errore, PostinoClient.interpretaRicerca(200, "non json"))
    }

    @Test
    fun `il percorso della ricerca - testo ripulito e codificato`() {
        assertEquals("/api/faccende?figlio_id=1&cerca=lava%20piatti", PostinoClient.percorsoRicerca(1, "  lava piatti "))
        assertEquals("/api/faccende?figlio_id=2&cerca=caff%C3%A8%20%26%20latte", PostinoClient.percorsoRicerca(2, "caffè & latte"))
        assertEquals("/api/faccende?cerca=letto", PostinoClient.percorsoRicerca(null, "letto"))
        assertEquals("letto", testoDaCercare("  letto  "))
        assertNull(testoDaCercare("   "))
        assertEquals(MASSIMO_RICERCA, testoDaCercare("a".repeat(120))?.length)
    }

    // --- modificare ---------------------------------------------------------------------------

    @Test
    fun `si manda solo quello che cambia`() {
        val prima = lavoro(titolo = "Svuota la lavastoviglie", nota = "Anche le posate")
        assertTrue(cambiDellaModifica(prima, " Svuota la lavastoviglie ", "Anche le posate", BloccoModificato.Invariato).vuota)
        val solo = cambiDellaModifica(prima, "Svuota la lavastoviglie e asciuga", "Anche le posate", BloccoModificato.Invariato)
        assertEquals(ModificaFaccenda(titolo = "Svuota la lavastoviglie e asciuga"), solo)
        // La nota cancellata parte come "" e nel corpo diventa null (il server la toglie).
        val senzaNota = cambiDellaModifica(prima, prima.titolo, "  ", BloccoModificato.Invariato)
        assertEquals("", senzaNota.nota)
        assertEquals(JsonObject(mapOf("nota" to JsonNull)), corpoModifica(senzaNota))
        // Subito = blocco_da null; un'ora = la data col fuso; com'era = assente.
        assertEquals(JsonObject(mapOf("blocco_da" to JsonNull)), corpoModifica(ModificaFaccenda(blocco = BloccoModificato.Subito)))
        assertEquals(
            JsonObject(mapOf("titolo" to JsonPrimitive("Letto"), "blocco_da" to JsonPrimitive("2026-10-05T18:00:00+02:00"))),
            corpoModifica(ModificaFaccenda(titolo = "Letto", blocco = BloccoModificato.Dalle("2026-10-05T18:00:00+02:00"))),
        )
        assertEquals(JsonObject(emptyMap()), corpoModifica(ModificaFaccenda()))
    }

    @Test
    fun `la modifica parte dal blocco com'e - da venire = Dalle, partito = Subito`() {
        assertEquals(Instant.parse("2026-10-05T14:00:00Z"), bloccoIniziale(lavoro(bloccoDa = "2026-10-05T14:00:00+00:00"), adesso))
        assertNull(bloccoIniziale(lavoro(bloccoDa = "2026-10-05T12:00:00+00:00"), adesso))
    }

    // --- "svolto": i pulsanti --------------------------------------------------------------------

    @Test
    fun `i pulsanti di un lavoro fatto - non visto, visto, confermato, foto cancellata, oltre 24 ore`() {
        val fatto = lavoro(stato = StatiFaccenda.FATTA, fotoTs = "2026-10-05T12:00:00+00:00", foto = true)
        // Non ancora guardata qui: "Guarda la foto", e "Boccia" (entro 24 ore).
        assertEquals(AzioniFatto(PulsanteFatto.GUARDA_FOTO, boccia = true, confermato = false), azioniFatto(fatto, adesso, vista = false, conConferma = true))
        // Guardata qui: "Segna come svolto", e ancora "Boccia".
        assertEquals(AzioniFatto(PulsanteFatto.SEGNA_SVOLTO, boccia = true, confermato = false), azioniFatto(fatto, adesso, vista = true, conConferma = true))
        // Confermato: la foto resta da guardare, niente più "Boccia".
        val confermato = fatto.copy(confermataTs = "2026-10-05T12:30:00+00:00", confermataDa = mamma)
        assertEquals(AzioniFatto(PulsanteFatto.GUARDA_FOTO, boccia = false, confermato = true), azioniFatto(confermato, adesso, vista = true, conConferma = true))
        // Foto cancellata (30 giorni): non si guarda, si può segnare svolto.
        val vecchio = fatto.copy(fotoTs = "2026-09-01T12:00:00+00:00", foto = false)
        assertEquals(AzioniFatto(PulsanteFatto.SEGNA_SVOLTO, boccia = false, confermato = false), azioniFatto(vecchio, adesso, vista = false, conConferma = true))
        assertEquals(AzioniFatto(PulsanteFatto.NESSUNO, boccia = false, confermato = true), azioniFatto(vecchio.copy(confermataTs = "2026-09-02T10:00:00+00:00"), adesso, vista = false, conConferma = true))
        // Oltre 24 ore: niente "Boccia", ma "Segna come svolto" sì (dopo averla guardata).
        val ieri = fatto.copy(fotoTs = "2026-10-04T10:00:00+00:00")
        assertEquals(AzioniFatto(PulsanteFatto.GUARDA_FOTO, boccia = false, confermato = false), azioniFatto(ieri, adesso, vista = false, conConferma = true))
        assertEquals(AzioniFatto(PulsanteFatto.SEGNA_SVOLTO, boccia = false, confermato = false), azioniFatto(ieri, adesso, vista = true, conConferma = true))
        // Server più vecchio della v3.9: mai "Segna come svolto".
        assertEquals(AzioniFatto(PulsanteFatto.GUARDA_FOTO, boccia = true, confermato = false), azioniFatto(fatto, adesso, vista = true, conConferma = false))
        assertEquals(AzioniFatto(PulsanteFatto.NESSUNO, boccia = false, confermato = false), azioniFatto(vecchio, adesso, vista = false, conConferma = false))
        // Un lavoro da fare non ha questi pulsanti.
        assertEquals(PulsanteFatto.NESSUNO, azioniFatto(lavoro(), adesso, vista = true, conConferma = true).principale)
    }

    @Test
    fun `chi ha confermato, e lo stato dei risultati della ricerca`() {
        val confermato = lavoro(stato = StatiFaccenda.FATTA, fotoTs = "2026-10-05T12:00:00+00:00", foto = true, confermataTs = "2026-10-05T13:10:00+00:00", confermataDa = mamma)
        assertEquals("Confermato da Mamma · oggi 15:10", testoConfermato(p, confermato, papa, roma, oggi))
        assertEquals("Confermato da te · oggi 15:10", testoConfermato(p, confermato, mamma, roma, oggi))
        assertNull(testoConfermato(p, confermato.copy(confermataTs = null), mamma, roma, oggi))
        assertEquals("Svolto · foto oggi 14:00", testoRisultato(p, confermato, roma, oggi))
        assertEquals("Fatto · foto oggi 14:00", testoRisultato(p, confermato.copy(confermataTs = null), roma, oggi))
        assertEquals("Da fare · dato oggi 10:00", testoRisultato(p, lavoro(), roma, oggi))
        assertEquals("Tolto · ieri 09:00", testoRisultato(p, lavoro(stato = StatiFaccenda.ANNULLATA).copy(chiusaTs = "2026-10-04T07:00:00+00:00"), roma, oggi))
    }

    @Test
    fun `le foto guardate - si scrivono, si rileggono, si potano`() {
        val vista = FotoVista(ChiaveFoto(12, "2026-10-05T12:00:00+00:00"), adesso)
        assertEquals(vista, decodificaFotoVista(codificaFotoVista(vista)))
        assertNull(decodificaFotoVista("rotta"))
        assertNull(decodificaFotoVista("x|2026|5"))
        // Doppioni: vale l'ultima; vecchie di più di 45 giorni: via; al massimo 200.
        val vecchia = FotoVista(ChiaveFoto(1, "a"), adesso.minus(Duration.ofDays(50)))
        val doppia = vista.copy(alle = adesso.minusSeconds(60))
        assertEquals(listOf(vista), potaFotoViste(listOf(vecchia, doppia, vista), adesso))
        val tante = (1..250L).map { FotoVista(ChiaveFoto(it, "t"), adesso.minusSeconds(it)) }
        val tenute = potaFotoViste(tante, adesso)
        assertEquals(FOTO_VISTE_MASSIMO, tenute.size)
        assertEquals(1L, tenute.first().chiave.faccendaId)
    }

    @Test
    fun `le notifiche nuove dei lavori, se arrivano al genitore, hanno un titolo sensato`() {
        val modificata = Notifica(id = 1, tipo = "faccenda_modificata", messaggio = "Mamma ha confermato «Letto»", tsServer = "2026-10-05T10:00:00+00:00")
        assertEquals(TestoNotifica("Novità dal patto", "Mamma ha confermato «Letto»"), testoNotifica(p, modificata, emptyMap()))
        // (0.18, contratto v4.0) `faccenda_confermata` arriva anche ai genitori (l'altro
        // genitore ha approvato): ha il suo titolo, e il messaggio del server.
        val confermata = modificata.copy(tipo = "faccenda_confermata", messaggio = "Mamma ha approvato «Letto»")
        assertEquals(TestoNotifica("Lavoro approvato", "Mamma ha approvato «Letto»"), testoNotifica(p, confermata, emptyMap()))
    }

    @Test
    fun `i rifiuti nuovi hanno il loro motivo`() {
        assertEquals(
            "Nel frattempo è arrivata la foto: non si può più cambiare.",
            messaggioRifiutoFaccende(p, CodiciErrore.NON_MODIFICABILE, GestoFaccende.MODIFICA, "Luca"),
        )
        assertEquals(
            "Per cambiare un lavoro serve aggiornare il server di Pactum.",
            messaggioRifiutoFaccende(p, CodiciErrore.SERVER_DA_AGGIORNARE, GestoFaccende.MODIFICA, "Luca"),
        )
        assertEquals(
            "Per segnare un lavoro come svolto serve aggiornare il server di Pactum.",
            messaggioRifiutoFaccende(p, CodiciErrore.SERVER_DA_AGGIORNARE, GestoFaccende.CONFERMA, "Luca"),
        )
    }

    // --- il motore con un server finto --------------------------------------------------------

    private class ServerFinto : FonteFaccende {
        var elenco: List<Faccenda> = emptyList()
        var conModifiche = true
        var modifiche = mutableListOf<Pair<Long, JsonObject>>()
        var esitoModifica: EsitoScrittura<Faccenda?> = EsitoScrittura.Riuscito(null)
        var conferme = mutableListOf<Pair<Long, String?>>()
        var esitoConferma: EsitoScrittura<Faccenda?> = EsitoScrittura.Riuscito(null)
        var ricerca: suspend (String) -> EsitoRicercaFaccende = { EsitoRicercaFaccende.Trovate(emptyList(), false) }
        val cercati = mutableListOf<String>()

        override suspend fun leggiFaccende(figlioId: Long?): EsitoFaccende =
            EsitoFaccende.Lette(elenco, if (elenco.isEmpty()) null else conModifiche)
        override suspend fun daiFaccende(corpo: CorpoNuoveFaccende): EsitoScrittura<List<Faccenda>?> = EsitoScrittura.Fallito
        override suspend fun bocciaFaccenda(faccendaId: Long, nota: String?): EsitoScrittura<Faccenda?> = EsitoScrittura.Fallito
        override suspend fun annullaFaccenda(faccendaId: Long): EsitoScrittura<Faccenda?> = EsitoScrittura.Fallito
        override suspend fun scaricaFoto(faccendaId: Long): EsitoFoto = EsitoFoto.Arrivata("foto".toByteArray())

        override suspend fun modificaFaccenda(faccendaId: Long, corpo: JsonObject): EsitoScrittura<Faccenda?> {
            modifiche += faccendaId to corpo
            return esitoModifica
        }

        override suspend fun confermaFaccenda(faccendaId: Long, fotoTs: String?): EsitoScrittura<Faccenda?> {
            conferme += faccendaId to fotoTs
            return esitoConferma
        }

        override suspend fun cercaFaccende(figlioId: Long?, testo: String): EsitoRicercaFaccende {
            cercati += testo
            return ricerca(testo)
        }
    }

    private val guardate = mutableListOf<ChiaveFoto>()

    private fun CoroutineScope.gestore(server: ServerFinto) = GestoreFaccende(
        ambito = this,
        fonte = { server },
        decodifica = { String(it) },
        orologio = { adesso },
        fotoGuardata = { guardate += it },
        attesaRicerca = 0,
    )

    private suspend fun calma() = repeat(50) { yield() }

    @Test
    fun `modifica - senza cambi niente server, con cambi solo quelli, server vecchio = niente piu Modifica`() = runBlocking {
        val server = ServerFinto()
        val l = lavoro()
        server.elenco = listOf(l)
        val g = gestore(server)
        g.aggiorna(1)
        calma()

        g.modifica(1, l, ModificaFaccenda())
        assertEquals(EventoFaccende.NessunCambio, g.stato.value.evento)
        assertTrue(server.modifiche.isEmpty())
        g.consumaEvento()

        g.modifica(1, l, ModificaFaccenda(titolo = "Letto"))
        calma()
        assertEquals(listOf(5L to JsonObject(mapOf("titolo" to JsonPrimitive("Letto")))), server.modifiche)
        assertEquals(EventoFaccende.Modificata, g.stato.value.evento)
        g.consumaEvento()

        server.esitoModifica = EsitoScrittura.Rifiutato(CodiciErrore.NON_MODIFICABILE)
        g.modifica(1, l, ModificaFaccenda(titolo = "Letto 2"))
        calma()
        // L'elenco riletto lo dà ancora da fare: non si sa perché, si dice in generale.
        assertEquals(EventoFaccende.Rifiuto(CodiciErrore.LAVORO_NON_PIU_DA_FARE, GestoFaccende.MODIFICA, 1), g.stato.value.evento)
        assertTrue(g.stato.value.conModifiche)
        g.consumaEvento()

        // Un server che non conosce PATCH: lo si dice, e "Modifica" non si offre più.
        server.esitoModifica = EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
        server.conModifiche = false
        g.modifica(1, l, ModificaFaccenda(titolo = "Letto 3"))
        calma()
        assertFalse(g.stato.value.conModifiche)
        g.dimentica()
    }

    @Test
    fun `conferma - svolto, e un server vecchio spegne il pulsante`() = runBlocking {
        val server = ServerFinto()
        val fatto = lavoro(stato = StatiFaccenda.FATTA, fotoTs = "2026-10-05T12:00:00+00:00", foto = true)
        server.elenco = listOf(fatto)
        val g = gestore(server)
        g.aggiorna(1)
        calma()
        // Si manda SEMPRE la foto guardata.
        g.conferma(1, fatto, fotoVista = "2026-10-05T12:00:00+00:00")
        calma()
        assertEquals(listOf(5L to "2026-10-05T12:00:00+00:00"), server.conferme)
        assertEquals(EventoFaccende.Confermata, g.stato.value.evento)
        g.consumaEvento()
        // La foto è cambiata intanto (bocciata e rifatta): non si conferma, lo si dice.
        server.esitoConferma = EsitoScrittura.Rifiutato(CodiciErrore.FOTO_CAMBIATA)
        g.conferma(1, fatto, fotoVista = "2026-10-05T12:00:00+00:00")
        calma()
        assertEquals(EventoFaccende.Rifiuto(CodiciErrore.FOTO_CAMBIATA, GestoFaccende.CONFERMA, 1), g.stato.value.evento)
        g.consumaEvento()
        server.esitoConferma = EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
        server.conModifiche = false
        g.conferma(1, fatto, fotoVista = null)
        calma()
        assertFalse(g.stato.value.conModifiche)
        g.dimentica()
    }

    @Test
    fun `aprire la foto la ricorda come guardata (quella foto, non il lavoro)`() = runBlocking {
        val server = ServerFinto()
        val g = gestore(server)
        g.apriFoto(5, "2026-10-05T12:00:00+00:00")
        calma()
        assertEquals(listOf(ChiaveFoto(5, "2026-10-05T12:00:00+00:00")), guardate)
        // Riaperta dalla memoria: si ricorda di nuovo (l'ora si aggiorna).
        g.chiudiFoto()
        g.apriFoto(5, "2026-10-05T12:00:00+00:00")
        calma()
        assertEquals(2, guardate.size)
        // Senza sapere quale foto, non si ricorda niente.
        g.apriFoto(6, null)
        calma()
        assertEquals(2, guardate.size)
        g.dimentica()
    }

    @Test
    fun `ricerca - risultati, una risposta vecchia si butta, testo vuoto chiude, server vecchio e senza rete`() = runBlocking {
        val server = ServerFinto()
        val g = gestore(server)
        val lenta = CompletableDeferred<EsitoRicercaFaccende>()
        val trovato = lavoro(id = 9, titolo = "Lavatrice")
        server.ricerca = { testo -> if (testo == "lav") lenta.await() else EsitoRicercaFaccende.Trovate(listOf(trovato), altre = true) }

        g.cerca(1, "lav")
        calma()
        assertTrue(g.stato.value.ricerca!!.caricamento)
        // Il genitore scrive ancora: la prima ricerca si annulla.
        g.cerca(1, "lavat")
        calma()
        lenta.complete(EsitoRicercaFaccende.Trovate(emptyList(), altre = false))
        calma()
        val ricerca = g.stato.value.ricerca!!
        assertEquals("lavat", ricerca.testo)
        assertEquals(listOf(trovato), ricerca.risultati)
        assertTrue(ricerca.altre)
        assertFalse(ricerca.caricamento)

        // Vuoto: la ricerca si chiude, senza chiamare il server.
        val prima = server.cercati.size
        g.cerca(1, "   ")
        assertNull(g.stato.value.ricerca)
        assertEquals(prima, server.cercati.size)

        server.ricerca = { EsitoRicercaFaccende.ServerVecchio }
        g.cerca(1, "letto")
        calma()
        assertEquals(ProblemaRicerca.SERVER_VECCHIO, g.stato.value.ricerca!!.problema)

        server.ricerca = { EsitoRicercaFaccende.SenzaRete }
        g.cerca(1, "letto!")
        calma()
        assertEquals(ProblemaRicerca.SENZA_RETE, g.stato.value.ricerca!!.problema)
        assertNull(g.stato.value.ricerca!!.risultati)

        server.ricerca = { EsitoRicercaFaccende.Errore }
        g.cerca(1, "letto?")
        calma()
        assertEquals(ProblemaRicerca.ERRORE, g.stato.value.ricerca!!.problema)

        // Il 401 chiude tutto: il collegamento non vale più.
        server.ricerca = { EsitoRicercaFaccende.NonAutorizzato }
        g.cerca(1, "cane")
        calma()
        assertNull(g.stato.value.ricerca)
        assertTrue(g.stato.value.collegamentoNonValido)
        g.dimentica()
    }

    // --- (0.17, revisione) -------------------------------------------------------------------

    @Test
    fun `il corpo della conferma porta la foto guardata`() {
        assertEquals(JsonObject(mapOf("foto_ts" to JsonPrimitive("2026-10-05T12:00:00+00:00"))), PostinoClient.corpoConferma("2026-10-05T12:00:00+00:00"))
        assertEquals(JsonObject(emptyMap()), PostinoClient.corpoConferma(null))
        assertEquals(
            "La foto è cambiata: guardala di nuovo prima di segnarla come svolta.",
            messaggioRifiutoFaccende(p, CodiciErrore.FOTO_CAMBIATA, GestoFaccende.CONFERMA, "Luca"),
        )
    }

    @Test
    fun `le foto da guardare non contano le confermate ne quelle gia guardate qui`() {
        val fatto = lavoro(id = 7, stato = StatiFaccenda.FATTA, fotoTs = "2026-10-05T12:00:00+00:00", foto = true)
        assertEquals(1, fotoDaGuardare(listOf(fatto), adesso))
        assertEquals(0, fotoDaGuardare(listOf(fatto.copy(confermataTs = "2026-10-05T12:30:00+00:00")), adesso))
        assertEquals(0, fotoDaGuardare(listOf(fatto), adesso, setOf(ChiaveFoto(7, "2026-10-05T12:00:00+00:00"))))
        // Guardata la foto di prima, quella nuova (dopo una bocciatura) è ancora da guardare.
        assertEquals(1, fotoDaGuardare(listOf(fatto), adesso, setOf(ChiaveFoto(7, "2026-10-05T09:00:00+00:00"))))
    }

    @Test
    fun `la pagina ritrovata confronta coi valori di partenza, non col lavoro riletto`() {
        // Partita con "Letto"; intanto un altro genitore l'ha chiamato "Rifai il letto".
        // Chi non ha toccato il titolo non lo rimanda indietro.
        assertTrue(cambiDellaModifica("Letto", "", "Letto", "", BloccoModificato.Invariato).vuota)
        assertEquals("Letto e scrivania", cambiDellaModifica("Letto", "", "Letto e scrivania", "", BloccoModificato.Invariato).titolo)
    }

    @Test
    fun `modifica - un si del server senza cambi e niente da cambiare, e non modificabile dice perche`() = runBlocking {
        val server = ServerFinto()
        val l = lavoro()
        server.elenco = listOf(l)
        val g = gestore(server)
        g.aggiorna(1)
        calma()
        // Il server risponde con gli stessi valori (stesso istante, altro fuso): "Niente da cambiare.".
        server.esitoModifica = EsitoScrittura.Riuscito(l.copy(bloccoDa = "2026-10-05T16:00:00+02:00"))
        g.modifica(1, l, ModificaFaccenda(blocco = BloccoModificato.Dalle("2026-10-05T16:00:00+02:00")))
        calma()
        assertEquals(EventoFaccende.NessunCambio, g.stato.value.evento)
        g.consumaEvento()
        // Non più modificabile: tolto intanto da un altro genitore.
        server.esitoModifica = EsitoScrittura.Rifiutato(CodiciErrore.NON_MODIFICABILE)
        server.elenco = listOf(l.copy(stato = StatiFaccenda.ANNULLATA))
        g.modifica(1, l, ModificaFaccenda(titolo = "Altro"))
        calma()
        assertEquals(EventoFaccende.Rifiuto(CodiciErrore.LAVORO_TOLTO, GestoFaccende.MODIFICA, 1), g.stato.value.evento)
        g.consumaEvento()
        // Arrivata la foto.
        server.elenco = listOf(l.copy(stato = StatiFaccenda.FATTA, fotoTs = "2026-10-05T13:00:00+00:00", foto = true))
        g.modifica(1, l, ModificaFaccenda(titolo = "Altro"))
        calma()
        assertEquals(EventoFaccende.Rifiuto(CodiciErrore.NON_MODIFICABILE, GestoFaccende.MODIFICA, 1), g.stato.value.evento)
        g.dimentica()
        assertEquals(
            "Nel frattempo questo lavoro è stato tolto: non c'è più niente da cambiare.",
            messaggioRifiutoFaccende(p, CodiciErrore.LAVORO_TOLTO, GestoFaccende.MODIFICA, "Luca"),
        )
    }

    @Test
    fun `un server vecchio resta vecchio cambiando figlio e con un elenco vuoto`() = runBlocking {
        val server = ServerFinto()
        server.elenco = listOf(lavoro())
        server.conModifiche = false
        val g = gestore(server)
        g.aggiorna(1)
        calma()
        assertFalse(g.stato.value.conModifiche)
        // Un altro figlio: mentre si legge, e con un elenco vuoto, resta "vecchio".
        server.elenco = emptyList()
        g.aggiorna(2)
        assertFalse(g.stato.value.conModifiche)
        calma()
        assertFalse(g.stato.value.conModifiche)
        // Un elenco che porta confermata_ts dice il contrario.
        server.elenco = listOf(lavoro())
        server.conModifiche = true
        g.aggiorna(2)
        calma()
        assertTrue(g.stato.value.conModifiche)
        g.dimentica()
    }

    @Test
    fun `senza collegamento modifica e conferma dicono il gesto giusto`() = runBlocking {
        val g = GestoreFaccende<String>(ambito = this, fonte = { null }, decodifica = { String(it) }, orologio = { adesso })
        g.modifica(1, lavoro(), ModificaFaccenda(titolo = "Letto"))
        calma()
        assertEquals(EventoFaccende.Rifiuto(CodiciErrore.CONFIGURAZIONE_MANCANTE, GestoFaccende.MODIFICA, 1), g.stato.value.evento)
        g.consumaEvento()
        g.conferma(1, lavoro(stato = StatiFaccenda.FATTA), fotoVista = null)
        calma()
        assertEquals(EventoFaccende.Rifiuto(CodiciErrore.CONFIGURAZIONE_MANCANTE, GestoFaccende.CONFERMA, 1), g.stato.value.evento)
        assertEquals(
            "Prima collega questo telefono al server: Impostazioni, Collegamento.",
            messaggioRifiutoFaccende(p, CodiciErrore.CONFIGURAZIONE_MANCANTE, GestoFaccende.MODIFICA, "Luca"),
        )
        g.dimentica()
    }
}
