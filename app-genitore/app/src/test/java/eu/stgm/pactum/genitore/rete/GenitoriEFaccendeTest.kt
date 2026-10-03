package eu.stgm.pactum.genitore.rete

import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.CorpoAbbinaGenitore
import eu.stgm.pactum.genitore.dati.CorpoBoccia
import eu.stgm.pactum.genitore.dati.CorpoFaccenda
import eu.stgm.pactum.genitore.dati.CorpoNuoveFaccende
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Famiglia
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.dati.TIPO_ABBINAMENTO_GENITORE
import eu.stgm.pactum.genitore.dati.Verdetto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) Come l'app legge (e scrive) quello che la v3.6 aggiunge al contratto: i
 * genitori, l'abbinamento col tipo "genitore", le faccende, il blocco, chi ha
 * fatto cosa. E come riconosce un server più vecchio (la rotta che non c'è):
 * allora dice che serve aggiornarlo, invece di "riprova".
 */
class GenitoriEFaccendeTest {

    private val json = PostinoClient.json

    // --- le risposte -------------------------------------------------------------------

    @Test
    fun `una faccenda come la scrive il contratto`() {
        val faccenda = json.decodeFromString(
            Faccenda.serializer(),
            """
            { "id": 5, "figlio_id": 1, "titolo": "Svuota la lavastoviglie", "nota": "anche le pentole",
              "stato": "da_fare", "blocco_da": "2026-10-03T14:00:00+00:00", "creata_ts": "2026-10-02T13:00:00+00:00",
              "creata_da": { "id": 2, "nome": "Mamma" },
              "foto_ts": null, "foto": false,
              "bocciature": 1, "ultima_bocciatura": { "ts": "2026-10-02T12:00:00+00:00", "nota": "non è pulito", "da": { "id": 1, "nome": "Papà" } },
              "chiusa_ts": null, "annullata_da": null, "campo_del_futuro": 3 }
            """,
        )
        assertEquals(5L, faccenda.id)
        assertEquals(StatiFaccenda.DA_FARE, faccenda.stato)
        assertEquals(RiferimentoGenitore(2, "Mamma"), faccenda.creataDa)
        assertEquals("anche le pentole", faccenda.nota)
        assertFalse(faccenda.foto)
        assertEquals(1, faccenda.bocciature)
        assertEquals("non è pulito", faccenda.ultimaBocciatura?.nota)
        assertEquals(RiferimentoGenitore(1, "Papà"), faccenda.ultimaBocciatura?.da)
        assertNull(faccenda.annullataDa)
    }

    @Test
    fun `la finestra senza faccende è un server vecchio, con la lista vuota no`() {
        val vecchia = json.decodeFromString(Finestra.serializer(), """{ "regole": [] }""")
        assertNull(vecchia.faccende)
        assertNull(vecchia.blocco)
        val nuova = json.decodeFromString(
            Finestra.serializer(),
            """{ "regole": [], "faccende": [], "blocco": { "attivo": false, "dal": null, "prossimo": "2026-10-02T14:00:00+00:00", "da_fare": [] } }""",
        )
        assertEquals(emptyList<Faccenda>(), nuova.faccende)
        assertEquals("2026-10-02T14:00:00+00:00", nuova.blocco?.prossimo)
        assertFalse(nuova.blocco!!.attivo)
    }

    @Test
    fun `la famiglia dice chi sei tu, i genitori e le faccende di ogni figlio`() {
        val famiglia = json.decodeFromString(
            Famiglia.serializer(),
            """
            { "io": { "id": 2, "nome": "Mamma" },
              "genitori": [ { "id": 1, "nome": "Papà", "revocato": false }, { "id": 2, "nome": "Mamma", "revocato": false } ],
              "figli": [ { "id": 1, "nome": "Luca", "faccende_da_fare": 3, "blocco_attivo": true, "dispositivi": [] } ] }
            """,
        )
        assertEquals(RiferimentoGenitore(2, "Mamma"), famiglia.io)
        assertEquals(listOf("Papà", "Mamma"), famiglia.genitori.map { it.nome })
        assertEquals(3, famiglia.figli.single().faccendeDaFare)
        assertTrue(famiglia.figli.single().bloccoAttivo)
        // Un server più vecchio: niente io, niente genitori, niente faccende.
        val vecchia = json.decodeFromString(Famiglia.serializer(), """{ "figli": [ { "id": 1, "nome": "Luca" } ] }""")
        assertNull(vecchia.io)
        assertTrue(vecchia.genitori.isEmpty())
        assertEquals(0, vecchia.figli.single().faccendeDaFare)
        assertFalse(vecchia.figli.single().bloccoAttivo)
    }

    @Test
    fun `chi ha fatto cosa nelle proposte, nelle sessioni e nei verdetti`() {
        val proposta = json.decodeFromString(
            Proposta.serializer(),
            """{ "id": 4, "regola_id": 1, "stato": "accettata", "autore": "figlio", "genitore": null,
                 "risposta_di": { "id": 2, "nome": "Mamma" } }""",
        )
        assertNull(proposta.genitore)
        assertEquals(RiferimentoGenitore(2, "Mamma"), proposta.rispostaDi)
        val sessione = json.decodeFromString(
            Sessione.serializer(),
            """{ "id": 3, "nome": "Studio", "stato": "approvata", "decisa_da": { "id": 1, "nome": "Papà" } }""",
        )
        assertEquals(RiferimentoGenitore(1, "Papà"), sessione.decisaDa)
        val verdetto = json.decodeFromString(
            Verdetto.serializer(),
            """{ "verdetto": "conferma", "da": { "id": 2, "nome": "Mamma" } }""",
        )
        assertEquals(RiferimentoGenitore(2, "Mamma"), verdetto.da)
    }

    @Test
    fun `i genitori dal codice HTTP`() {
        val letti = PostinoClient.interpretaGenitori(
            200,
            """{ "io": { "id": 1, "nome": "Genitore" },
                 "genitori": [ { "id": 1, "nome": "Genitore", "abbinato": true, "revocato": false, "creato_ts": "2026-10-02T10:00:00+00:00" },
                               { "id": 2, "nome": "Mamma", "abbinato": false, "revocato": false } ] }""",
        )
        assertTrue(letti is EsitoGenitori.Letti)
        val pacco = (letti as EsitoGenitori.Letti).pacco
        assertEquals(RiferimentoGenitore(1, "Genitore"), pacco.io)
        assertFalse(pacco.genitori[1].abbinato)
        assertEquals(EsitoGenitori.ServerVecchio, PostinoClient.interpretaGenitori(404, """{"detail": "Not Found"}"""))
        assertEquals(EsitoGenitori.ServerVecchio, PostinoClient.interpretaGenitori(405, null))
        assertEquals(EsitoGenitori.Fallita, PostinoClient.interpretaGenitori(500, null))
        assertEquals(EsitoGenitori.Fallita, PostinoClient.interpretaGenitori(200, "non è json"))
    }

    @Test
    fun `le faccende dal codice HTTP`() {
        val lette = PostinoClient.interpretaFaccende(200, """{ "faccende": [ { "id": 1, "titolo": "Rifai il letto", "stato": "fatta", "foto": true } ] }""")
        assertTrue(lette is EsitoFaccende.Lette)
        assertTrue((lette as EsitoFaccende.Lette).faccende.single().foto)
        assertEquals(EsitoFaccende.ServerVecchio, PostinoClient.interpretaFaccende(404, """{"detail":"Not Found"}"""))
        assertEquals(EsitoFaccende.ServerVecchio, PostinoClient.interpretaFaccende(405, null))
        // Un 404 della rotta che c'è (un figlio che non c'è) non è un server vecchio.
        assertEquals(EsitoFaccende.Fallita, PostinoClient.interpretaFaccende(404, """{"detail": "figlio non trovato"}"""))
    }

    @Test
    fun `il codice di un genitore nuovo`() {
        val esito = PostinoClient.interpretaCodiceGenitore(
            201,
            """{ "genitore": { "id": 2, "nome": "Mamma", "abbinato": false, "revocato": false }, "codice": "483920", "scade_ts": "2026-10-02T13:15:00+00:00" }""",
        )
        assertTrue(esito is EsitoScrittura.Riuscito)
        val codice = (esito as EsitoScrittura.Riuscito).dato
        assertEquals("483920", codice.codice)
        assertEquals(2L, codice.genitore?.id)
        // Senza codice nel corpo non c'è niente da mostrare: si rilegge e se ne chiede uno.
        assertEquals(EsitoScrittura.Fallito, PostinoClient.interpretaCodiceGenitore(201, """{ "genitore": { "id": 2 } }"""))
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.TROPPI_GENITORI),
            PostinoClient.interpretaCodiceGenitore(409, """{"detail": {"errore": "troppi_genitori"}}"""),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE),
            PostinoClient.interpretaCodiceGenitore(404, """{"detail": "Not Found"}"""),
        )
    }

    @Test
    fun `rinominare e togliere un genitore`() {
        assertEquals(EsitoScrittura.Riuscito(Unit), PostinoClient.interpretaNuovaRotta(200, null))
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.NON_TE_STESSO),
            PostinoClient.interpretaNuovaRotta(409, """{"detail": {"errore": "non_te_stesso"}}"""),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.ULTIMO_GENITORE),
            PostinoClient.interpretaNuovaRotta(409, """{"errore": "ultimo_genitore"}"""),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.NON_TROVATO),
            PostinoClient.interpretaNuovaRotta(404, """{"detail": "genitore non trovato"}"""),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE),
            PostinoClient.interpretaNuovaRotta(405, null),
        )
        assertEquals(EsitoScrittura.Fallito, PostinoClient.interpretaNuovaRotta(500, null))
    }

    @Test
    fun `i gesti sulle faccende dal codice HTTP`() {
        val bocciata = PostinoClient.interpretaNuovaRotta(
            200,
            """{ "id": 5, "titolo": "Svuota la lavastoviglie", "stato": "da_fare", "bocciature": 1 }""",
            Faccenda.serializer(),
        )
        assertEquals(1, (bocciata as EsitoScrittura.Riuscito).dato?.bocciature)
        // Un sì con un corpo che non si legge resta un sì: niente "riprova" su un gesto fatto.
        assertEquals(EsitoScrittura.Riuscito(null), PostinoClient.interpretaNuovaRotta(200, "boh", Faccenda.serializer()))
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.NON_BOCCIABILE),
            PostinoClient.interpretaNuovaRotta(409, """{"detail": {"errore": "non_bocciabile"}}""", Faccenda.serializer()),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.NON_ANNULLABILE),
            PostinoClient.interpretaNuovaRotta(409, """{"detail": {"errore": "non_annullabile"}}""", Faccenda.serializer()),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.TROPPE_FACCENDE),
            PostinoClient.interpretaNuovaRotta(409, """{"detail": {"errore": "troppe_faccende"}}""", Faccenda.serializer()),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.NON_TROVATO),
            PostinoClient.interpretaNuovaRotta(404, """{"detail": "faccenda non trovata"}""", Faccenda.serializer()),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(PostinoClient.PARAMETRI_NON_VALIDI),
            PostinoClient.interpretaNuovaRotta(422, """{"detail": [{"loc": ["body"], "msg": "x"}]}""", Faccenda.serializer()),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE),
            PostinoClient.interpretaNuovaRotta(404, """{"detail": "Not Found"}""", Faccenda.serializer()),
        )
    }

    @Test
    fun `la foto che non arriva`() {
        assertEquals(EsitoFoto.NonTrovata, PostinoClient.interpretaFotoMancante(404, """{"detail": "foto non trovata"}"""))
        assertEquals(EsitoFoto.ServerVecchio, PostinoClient.interpretaFotoMancante(404, """{"detail": "Not Found"}"""))
        assertEquals(EsitoFoto.ServerVecchio, PostinoClient.interpretaFotoMancante(405, null))
        assertEquals(EsitoFoto.Fallita, PostinoClient.interpretaFotoMancante(500, null))
    }

    @Test
    fun `un 401 dice che il collegamento non vale più, non che manca la rete`() {
        assertEquals(EsitoFamiglia.NonAutorizzato, PostinoClient.interpretaFamiglia(401, """{"detail": "token non valido"}"""))
        assertEquals(EsitoGenitori.NonAutorizzato, PostinoClient.interpretaGenitori(401, null))
        assertEquals(EsitoFaccende.NonAutorizzato, PostinoClient.interpretaFaccende(401, null))
        assertEquals(EsitoFoto.NonAutorizzato, PostinoClient.interpretaFotoMancante(401, null))
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.COLLEGAMENTO_NON_VALIDO),
            PostinoClient.interpretaNuovaRotta(401, """{"detail": "token non valido"}""", Faccenda.serializer()),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.COLLEGAMENTO_NON_VALIDO),
            PostinoClient.interpretaRisposta(401, null, Faccenda.serializer()),
        )
    }

    @Test
    fun `un codice nuovo per un genitore tolto ha il suo rifiuto`() {
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.GENITORE_REVOCATO),
            PostinoClient.interpretaCodiceGenitore(409, """{"detail": {"errore": "genitore_revocato"}}"""),
        )
    }

    @Test
    fun `una foto troppo grande non si legge`() {
        val piccola = ByteArray(100) { it.toByte() }
        assertEquals(100, PostinoClient.leggiAlMassimo(piccola.inputStream(), 100)?.size)
        assertNull(PostinoClient.leggiAlMassimo(ByteArray(101).inputStream(), 100))
        assertNull(PostinoClient.leggiAlMassimo(ByteArray(0).inputStream(), 100))
        assertNull(PostinoClient.leggiAlMassimo(null, 100))
    }

    // --- l'abbinamento col codice di 6 cifre ---------------------------------------------

    @Test
    fun `collegato, il token e chi sei`() {
        assertEquals(
            EsitoAbbinamento.Collegato("abc", RiferimentoGenitore(2, "Mamma")),
            PostinoClient.interpretaAbbinamento(200, """{ "token": " abc ", "genitore": { "id": 2, "nome": "Mamma" } }"""),
        )
        // Un 200 senza token non collega niente.
        assertEquals(EsitoAbbinamento.Errore, PostinoClient.interpretaAbbinamento(200, """{ "token": "" }"""))
        assertEquals(EsitoAbbinamento.Errore, PostinoClient.interpretaAbbinamento(200, "boh"))
    }

    @Test
    fun `i rifiuti dell'abbinamento, ciascuno col suo nome`() {
        assertEquals(
            EsitoAbbinamento.CodiceNonValido,
            PostinoClient.interpretaAbbinamento(409, """{"detail": {"errore": "codice_non_valido"}}"""),
        )
        assertEquals(
            EsitoAbbinamento.TipoNonCorrispondente("telefono"),
            PostinoClient.interpretaAbbinamento(409, """{"detail": {"errore": "tipo_non_corrispondente", "tipo_atteso": " Telefono "}}"""),
        )
        assertEquals(
            EsitoAbbinamento.TipoNonCorrispondente(null),
            PostinoClient.interpretaAbbinamento(409, """{"errore": "tipo_non_corrispondente"}"""),
        )
        assertEquals(
            EsitoAbbinamento.TroppiTentativi(300),
            PostinoClient.interpretaAbbinamento(429, """{"detail": {"errore": "troppi_tentativi", "riprova_tra_secondi": 300}}"""),
        )
        assertEquals(EsitoAbbinamento.TroppiTentativi(null), PostinoClient.interpretaAbbinamento(429, null))
        // Il server più vecchio della v3.6 non conosce il tipo "genitore": 422.
        assertEquals(EsitoAbbinamento.ServerDaAggiornare, PostinoClient.interpretaAbbinamento(422, """{"detail": []}"""))
        assertEquals(EsitoAbbinamento.ServerSenzaCodici, PostinoClient.interpretaAbbinamento(404, """{"detail": "Not Found"}"""))
        assertEquals(EsitoAbbinamento.ServerSenzaCodici, PostinoClient.interpretaAbbinamento(405, null))
        assertEquals(EsitoAbbinamento.Errore, PostinoClient.interpretaAbbinamento(500, null))
    }

    // --- i corpi delle richieste -----------------------------------------------------------

    @Test
    fun `l'abbinamento dice sempre che è un genitore`() {
        val corpo = json.parseToJsonElement(
            json.encodeToString(
                CorpoAbbinaGenitore.serializer(),
                CorpoAbbinaGenitore(codice = "483920", tipo = TIPO_ABBINAMENTO_GENITORE, versioneApp = "0.13.0"),
            ),
        ).jsonObject
        assertEquals("genitore", corpo["tipo"]?.jsonPrimitive?.content)
        assertEquals("483920", corpo["codice"]?.jsonPrimitive?.content)
        assertEquals("0.13.0", corpo["versione_app"]?.jsonPrimitive?.content)
    }

    @Test
    fun `dai faccende, il figlio sempre, subito senza blocco_da, la nota solo se c'è`() {
        val subito = json.parseToJsonElement(
            json.encodeToString(
                CorpoNuoveFaccende.serializer(),
                CorpoNuoveFaccende(figlioId = 1, faccende = listOf(CorpoFaccenda("Rifai il letto"))),
            ),
        ).jsonObject
        assertEquals("1", subito["figlio_id"]?.jsonPrimitive?.content)
        assertFalse("blocco_da" in subito)
        val faccenda = (subito["faccende"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals("Rifai il letto", faccenda["titolo"]?.jsonPrimitive?.content)
        assertFalse("nota" in faccenda)

        val dalle = json.parseToJsonElement(
            json.encodeToString(
                CorpoNuoveFaccende.serializer(),
                CorpoNuoveFaccende(
                    figlioId = 2,
                    faccende = listOf(CorpoFaccenda("Rifai il letto", "anche il cuscino")),
                    bloccoDa = "2026-10-02T16:00:00+02:00",
                ),
            ),
        ).jsonObject
        assertEquals("2026-10-02T16:00:00+02:00", dalle["blocco_da"]?.jsonPrimitive?.content)
        assertEquals(
            "anche il cuscino",
            ((dalle["faccende"] as kotlinx.serialization.json.JsonArray).single() as JsonObject)["nota"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `boccia senza nota manda un corpo vuoto`() {
        assertEquals("{}", json.encodeToString(CorpoBoccia.serializer(), CorpoBoccia(null)))
        assertEquals("""{"nota":"non è pulito"}""", json.encodeToString(CorpoBoccia.serializer(), CorpoBoccia("non è pulito")))
    }

    @Test
    fun `mappa cambia il dato e lascia i rifiuti com'erano`() {
        assertEquals(EsitoScrittura.Riuscito(2), EsitoScrittura.Riuscito("ab").mappa { it.length })
        assertEquals(EsitoScrittura.Rifiutato("x"), EsitoScrittura.Rifiutato("x").mappa { 1 })
        assertEquals(EsitoScrittura.Fallito, EsitoScrittura.Fallito.mappa { 1 })
    }
}
