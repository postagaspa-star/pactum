package eu.stgm.pactum.genitore.rete

import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.Famiglia
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.PaccoStudio
import eu.stgm.pactum.genitore.dati.PaccoSvolteStudio
import eu.stgm.pactum.genitore.dati.StatiConfigStudio
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.18, contratto v4.0) Come l'app legge le risposte nuove: il `blocco` in GET
 * /api/faccende (che dice anche se il server è dalla v4.0), `da_approvare`,
 * `rimandato` e `studio` nel blocco, le letture dello Studio, la risposta alla
 * configurazione (409 `richiesta_cambiata` con la configurazione di adesso) e la
 * chiusura del genitore (409 `gia_chiuso`), coi corpi che manda.
 */
class StudioRetiTest {

    private val json = PostinoClient.json

    // --- i lavori approvati ------------------------------------------------------------------

    @Test
    fun `GET faccende col blocco e un server v4, senza e uno piu vecchio`() {
        val v40 = """
            {"faccende": [{"id": 5, "titolo": "Rifai il letto", "stato": "fatta", "foto_ts": "2026-10-07T13:10:00+00:00",
                           "foto": true, "confermata_ts": null, "da_approvare": true}],
             "blocco": {"attivo": true, "dal": "2026-10-07T13:00:00+00:00", "prossimo": null, "rimandato": false,
                        "studio": {"in_corso": false, "id": null, "inizio_ts": null},
                        "da_fare": [{"id": 5, "titolo": "Rifai il letto", "nota": null, "blocco_da": "2026-10-07T13:00:00+00:00",
                                     "creata_da": {"id": 2, "nome": "Mamma"}, "bocciature": 0, "ultima_bocciatura": null,
                                     "stato": "fatta", "foto_ts": "2026-10-07T13:10:00+00:00"}]}}
        """.trimIndent()
        val letto = PostinoClient.interpretaFaccende(200, v40) as EsitoFaccende.Lette
        assertTrue(letto.faccende.single().daApprovare)
        val blocco = letto.blocco!!
        assertTrue(blocco.attivo)
        assertEquals(false, blocco.rimandato)
        assertEquals(false, blocco.studio?.inCorso)
        assertEquals("fatta", blocco.daFare.single().stato)
        assertEquals("2026-10-07T13:10:00+00:00", blocco.daFare.single().fotoTs)
        assertEquals(true, letto.conModifiche)
        // Anche con l'elenco vuoto, il blocco dice che il server conosce la conferma.
        val vuoto = PostinoClient.interpretaFaccende(200, """{"faccende": [], "blocco": {"attivo": false, "rimandato": false}}""") as EsitoFaccende.Lette
        assertEquals(true, vuoto.conModifiche)
        // v3.9: niente blocco nell'elenco, niente da_approvare.
        val v39 = PostinoClient.interpretaFaccende(200, """{"faccende": [{"id": 5, "stato": "fatta", "confermata_ts": null}]}""") as EsitoFaccende.Lette
        assertNull(v39.blocco)
        assertFalse(v39.faccende.single().daApprovare)
    }

    @Test
    fun `famiglia e finestra portano i numeri e lo Studio della v4`() {
        val famiglia = json.decodeFromString(
            Famiglia.serializer(),
            """{"figli": [
                {"id": 1, "nome": "Luca", "faccende_da_approvare": 2, "blocco_rimandato": true, "studio_da_approvare": 1, "studio_in_corso": true},
                {"id": 2, "nome": "Sara", "studio_in_corso": {"id": 4, "inizio_ts": "2026-10-07T13:00:00+00:00"}},
                {"id": 3, "nome": "Teo", "studio_in_corso": null},
                {"id": 4, "nome": "Ada"}]}""",
        )
        val (luca, sara, teo, ada) = famiglia.figli
        assertEquals(2, luca.faccendeDaApprovare)
        assertTrue(luca.bloccoRimandato)
        assertEquals(1, luca.studioDaApprovare)
        assertTrue(luca.studioInCorso)
        assertTrue(sara.studioInCorso)
        assertFalse(teo.studioInCorso)
        assertFalse(ada.studioInCorso)
        assertEquals(0, ada.faccendeDaApprovare)

        val finestra = json.decodeFromString(
            Finestra.serializer(),
            """{"faccende_da_approvare": 1, "studio_da_approvare": 1,
                "studio": {"config": {"stato": "in_attesa", "versione": 2, "approvata": null,
                                      "in_attesa": {"giorni": ["lun"], "inizio": "15:00", "chiusura_minima": "16:00", "minuti_minimi": 60,
                                                    "telefono": {"app": ["gruppo:apk"], "nomi": {}},
                                                    "computer": {"programmi": ["exe:winword.exe"], "nomi": {"exe:winword.exe": "Word"},
                                                                 "firme": {"exe:winword.exe": "Microsoft Corporation"}},
                                                    "richiesta_ts": "2026-10-07T12:00:00+00:00",
                                                    "da": {"id": 1, "nome": "Telefono", "tipo": "telefono"}},
                                      "motivazione": null},
                           "in_corso": null,
                           "prossime_partenze": [{"giorno": "2026-10-08", "inizio_ts": "2026-10-08T13:00:00+00:00",
                                                  "chiudibile_dal": "2026-10-08T14:00:00+00:00", "minuti_minimi": 60}]},
                "studio_svolte": [{"id": 41, "origine": "automatica", "giorno": "2026-10-06", "inizio_ts": "2026-10-06T13:00:00+00:00",
                                   "tratti": [{"id": "u1", "dispositivo_id": 1, "tipo": "compiti", "parola": null, "faccenda_id": null,
                                               "inizio": 1791291600000, "fine": 1791293400000, "ora_agganciata": true,
                                               "secondi": 1800, "secondi_contati": 1800, "minuti": 30, "esito": "finito", "conta": true}],
                                   "fine_ts": "2026-10-06T14:40:00+00:00", "chiusura": "figlio",
                                   "chiusa_da": {"id": 1, "nome": "Telefono", "tipo": "telefono"},
                                   "dichiarazione": "Matematica", "minuti_alla_chiusura": 65, "in_corso": false}]}""",
        )
        assertEquals(1, finestra.faccendeDaApprovare)
        assertEquals(StatiConfigStudio.IN_ATTESA, finestra.studio?.config?.stato)
        assertEquals("Microsoft Corporation", finestra.studio?.config?.inAttesa?.computer?.firme?.get("exe:winword.exe"))
        assertEquals("2026-10-08", finestra.studio?.prossimePartenze?.single()?.giorno)
        val svolto = finestra.studioSvolte.single()
        assertEquals(30, svolto.tratti.single().minuti)
        assertEquals(65, svolto.minutiAllaChiusura)
        assertEquals("telefono", svolto.chiusaDa?.tipo)
        // Un server più vecchio: niente studio (null), lo Studio non si mostra.
        assertNull(json.decodeFromString(Finestra.serializer(), "{}").studio)
    }

    // --- le letture dello Studio ------------------------------------------------------------------

    @Test
    fun `le letture dello Studio distinguono server vecchio, collegamento tolto e errore`() {
        val letto = PostinoClient.interpretaLetturaStudio(200, """{"config": {"stato": "nessuna", "versione": 0}, "recenti": []}""", PaccoStudio.serializer())
        assertEquals(StatiConfigStudio.NESSUNA, (letto as EsitoLetturaStudio.Letta).dato.config?.stato)
        assertEquals(EsitoLetturaStudio.ServerVecchio, PostinoClient.interpretaLetturaStudio(404, """{"detail": "Not Found"}""", PaccoStudio.serializer()))
        assertEquals(EsitoLetturaStudio.ServerVecchio, PostinoClient.interpretaLetturaStudio(405, null, PaccoStudio.serializer()))
        assertEquals(EsitoLetturaStudio.NonAutorizzato, PostinoClient.interpretaLetturaStudio(401, null, PaccoStudio.serializer()))
        // Un 404 della rotta (un figlio che non c'è) non è un server vecchio.
        assertEquals(EsitoLetturaStudio.Fallita, PostinoClient.interpretaLetturaStudio(404, """{"detail": "figlio non trovato"}""", PaccoStudio.serializer()))
        assertEquals(EsitoLetturaStudio.Fallita, PostinoClient.interpretaLetturaStudio(200, "non è json", PaccoStudio.serializer()))
        val svolte = PostinoClient.interpretaLetturaStudio(200, """{"svolte": [{"id": 3}], "altre": true}""", PaccoSvolteStudio.serializer())
        assertTrue((svolte as EsitoLetturaStudio.Letta).dato.altre)
        assertEquals("/api/studio/svolte?figlio_id=1&prima_di=40", PostinoClient.percorsoSvolteStudio(1, 40))
        assertEquals("/api/studio/svolte?figlio_id=1", PostinoClient.percorsoSvolteStudio(1, null))
        assertEquals("/api/studio/svolte", PostinoClient.percorsoSvolteStudio(null, null))
    }

    // --- la risposta alla configurazione ------------------------------------------------------------

    @Test
    fun `la risposta alla configurazione`() {
        val decisa = PostinoClient.interpretaRispostaStudio(200, """{"stato": "approvata", "versione": 5}""")
        assertEquals(5, (decisa as EsitoRispostaStudio.Decisa).config?.versione)
        // Un 2xx con un corpo che non si legge è comunque una decisione presa.
        assertEquals(EsitoRispostaStudio.Decisa(null), PostinoClient.interpretaRispostaStudio(200, "???"))
        // 409 richiesta_cambiata: la configurazione di adesso, in `detail` o in cima.
        val cambiata = PostinoClient.interpretaRispostaStudio(
            409,
            """{"detail": {"errore": "richiesta_cambiata", "config": {"stato": "in_attesa", "versione": 7}}}""",
        )
        assertEquals(7, (cambiata as EsitoRispostaStudio.Cambiata).config?.versione)
        val inCima = PostinoClient.interpretaRispostaStudio(409, """{"errore": "richiesta_cambiata", "configurazione": {"versione": 8}}""")
        assertEquals(8, (inCima as EsitoRispostaStudio.Cambiata).config?.versione)
        assertEquals(EsitoRispostaStudio.Cambiata(null), PostinoClient.interpretaRispostaStudio(409, """{"detail": {"errore": "richiesta_cambiata"}}"""))
        assertEquals(
            EsitoRispostaStudio.Rifiutata(CodiciErrore.NIENTE_DA_DECIDERE),
            PostinoClient.interpretaRispostaStudio(409, """{"detail": {"errore": "niente_da_decidere"}}"""),
        )
        assertEquals(EsitoRispostaStudio.Rifiutata(CodiciErrore.NON_TROVATO), PostinoClient.interpretaRispostaStudio(404, """{"detail": "figlio non trovato"}"""))
        assertEquals(EsitoRispostaStudio.Rifiutata(CodiciErrore.SERVER_DA_AGGIORNARE), PostinoClient.interpretaRispostaStudio(404, """{"detail": "Not Found"}"""))
        assertEquals(EsitoRispostaStudio.Rifiutata(PostinoClient.PARAMETRI_NON_VALIDI), PostinoClient.interpretaRispostaStudio(422, "{}"))
        assertEquals(EsitoRispostaStudio.Rifiutata(CodiciErrore.COLLEGAMENTO_NON_VALIDO), PostinoClient.interpretaRispostaStudio(401, null))
        assertEquals(EsitoRispostaStudio.Fallita, PostinoClient.interpretaRispostaStudio(500, null))
    }

    // --- la chiusura del genitore -------------------------------------------------------------------

    @Test
    fun `la chiusura del genitore`() {
        val chiuso = PostinoClient.interpretaChiusuraStudio(200, """{"id": 41, "chiusura": "genitore", "motivo": "visita medica"}""")
        assertEquals("genitore", (chiuso as EsitoChiusuraStudio.Chiuso).studio?.chiusura)
        val gia = PostinoClient.interpretaChiusuraStudio(409, """{"detail": {"errore": "gia_chiuso", "studio": {"id": 41, "chiusura": "figlio"}}}""")
        assertEquals("figlio", (gia as EsitoChiusuraStudio.GiaChiuso).studio?.chiusura)
        assertEquals(EsitoChiusuraStudio.Rifiutata(PostinoClient.PARAMETRI_NON_VALIDI), PostinoClient.interpretaChiusuraStudio(422, "{}"))
        assertEquals(EsitoChiusuraStudio.Rifiutata(CodiciErrore.NON_TROVATO), PostinoClient.interpretaChiusuraStudio(404, """{"detail": "studio non trovato"}"""))
        assertEquals(EsitoChiusuraStudio.Rifiutata(CodiciErrore.SERVER_DA_AGGIORNARE), PostinoClient.interpretaChiusuraStudio(405, null))
    }

    @Test
    fun `i corpi mandati al server`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val postino = PostinoClient(ConfigurazionePostino(server.url("/").toString().trimEnd('/'), "codice-finto"))
            // La risposta: esito, versione vista, figlio; il perché solo se c'è.
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stato": "approvata", "versione": 6}"""))
            postino.rispondiConfigStudio(1, "approva", 5, null)
            val risposta = server.takeRequest()
            assertEquals("POST", risposta.method)
            assertEquals("/api/studio/config/risposta", risposta.path)
            val corpo = json.parseToJsonElement(risposta.body.readUtf8()).jsonObject
            assertEquals("approva", corpo["esito"]?.jsonPrimitive?.content)
            assertEquals(5, corpo["versione"]?.jsonPrimitive?.int)
            assertEquals(1, corpo["figlio_id"]?.jsonPrimitive?.int)
            assertFalse(corpo.containsKey("motivazione"))
            // La chiusura: il motivo (obbligatorio) e il figlio.
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 41}"""))
            postino.chiudiStudio(1, 41, "visita medica")
            val chiusura = server.takeRequest()
            assertEquals("/api/studio/41/chiudi", chiusura.path)
            val corpoChiusura = json.parseToJsonElement(chiusura.body.readUtf8()) as JsonObject
            assertEquals("visita medica", corpoChiusura["motivo"]?.jsonPrimitive?.content)
            assertEquals(1, corpoChiusura["figlio_id"]?.jsonPrimitive?.int)
            // L'approvazione di un lavoro: foto_ts SEMPRE nel corpo.
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 5}"""))
            postino.confermaFaccenda(5, "2026-10-07T13:10:00+00:00")
            val conferma = server.takeRequest()
            assertEquals("/api/faccende/5/conferma", conferma.path)
            val corpoConferma = json.parseToJsonElement(conferma.body.readUtf8()) as JsonObject
            assertEquals("2026-10-07T13:10:00+00:00", corpoConferma["foto_ts"]?.jsonPrimitive?.content)
        } finally {
            server.shutdown()
        }
    }
}
