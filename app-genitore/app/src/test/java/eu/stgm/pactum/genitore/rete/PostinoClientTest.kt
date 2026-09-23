package eu.stgm.pactum.genitore.rete

import eu.stgm.pactum.genitore.dati.CodiceAbbinamento
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.CorpoSegno
import eu.stgm.pactum.genitore.dati.CorpoVerdetto
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.NuovaProposta
import eu.stgm.pactum.genitore.dati.PaccoNotifiche
import eu.stgm.pactum.genitore.dati.RiepilogoFinestra
import eu.stgm.pactum.genitore.dati.SegnoMandato
import eu.stgm.pactum.genitore.ui.FinestraViewModel.EsitoSegno
import eu.stgm.pactum.genitore.ui.esitoDelSegno
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Come l'app legge le risposte del postino. Il server è FastAPI: i 409 arrivano
 * come `{"detail": {"errore": "…"}}`. Se il codice non si legge, il genitore
 * vede "riprova" dove il server gli stava dicendo "c'è già una proposta in
 * attesa" — o, peggio, "segno già mandato" su un rifiuto che non lo era.
 */
class PostinoClientTest {

    // --- il codice d'errore di un 409 ------------------------------------------------

    @Test
    fun `il codice sta dentro detail, come lo manda FastAPI`() {
        assertEquals(
            "proposta_gia_pendente",
            PostinoClient.codiceErrore("""{"detail": {"errore": "proposta_gia_pendente"}}"""),
        )
        assertEquals(
            "dichiarazione_non_in_attesa",
            PostinoClient.codiceErrore("""{"detail":{"errore":"dichiarazione_non_in_attesa","altro":1}}"""),
        )
    }

    @Test
    fun `il codice in cima, come lo scrive il contratto, vale lo stesso`() {
        assertEquals("regola_non_valida", PostinoClient.codiceErrore("""{"errore": "regola_non_valida"}"""))
    }

    @Test
    fun `nessun codice leggibile = null, mai un codice inventato`() {
        assertNull(PostinoClient.codiceErrore(null))
        assertNull(PostinoClient.codiceErrore(""))
        assertNull(PostinoClient.codiceErrore("non è json"))
        assertNull(PostinoClient.codiceErrore("""{"detail": "notifica non trovata"}"""))
        assertNull(PostinoClient.codiceErrore("""{"detail": [{"loc": ["body"], "msg": "x"}]}"""))
        assertNull(PostinoClient.codiceErrore("""{"detail": {"errore": null}}"""))
        assertNull(PostinoClient.codiceErrore("""[1, 2]"""))
    }

    // --- l'esito di una scrittura ------------------------------------------------------

    private fun esito(codice: Int, corpo: String?) =
        PostinoClient.interpretaRisposta(codice, corpo, SegnoMandato.serializer())

    @Test
    fun `un 409 porta su il codice di detail`() {
        assertEquals(
            EsitoScrittura.Rifiutato("segno_gia_mandato"),
            esito(409, """{"detail": {"errore": "segno_gia_mandato"}}"""),
        )
        assertEquals(EsitoScrittura.Rifiutato(null), esito(409, "boh"))
    }

    @Test
    fun `un 422 e un rifiuto di validazione, il resto e un fallimento`() {
        assertEquals(
            EsitoScrittura.Rifiutato(PostinoClient.PARAMETRI_NON_VALIDI),
            esito(422, """{"detail": [{"msg": "x"}]}"""),
        )
        assertEquals(EsitoScrittura.Fallito, esito(500, """{"detail": {"errore": "x"}}"""))
        assertEquals(EsitoScrittura.Fallito, esito(200, "non è json"))
        assertEquals(
            EsitoScrittura.Riuscito(SegnoMandato(true, "2026-09-19T10:00:00+00:00")),
            esito(200, """{"mandato": true, "ts_server": "2026-09-19T10:00:00+00:00"}"""),
        )
    }

    // --- il segno: "già mandato" solo quando il server lo dice -------------------------

    @Test
    fun `il segno e gia mandato solo col suo 409`() {
        assertEquals(
            EsitoSegno.GIA_MANDATO,
            esitoDelSegno(esito(409, """{"detail": {"errore": "segno_gia_mandato"}}""")),
        )
    }

    @Test
    fun `un altro 409, un 409 senza codice o un 422 non spengono il segno`() {
        assertEquals(EsitoSegno.FALLITO, esitoDelSegno(esito(409, """{"detail": {"errore": "altro"}}""")))
        assertEquals(EsitoSegno.FALLITO, esitoDelSegno(esito(409, "")))
        assertEquals(EsitoSegno.FALLITO, esitoDelSegno(esito(422, "{}")))
        assertEquals(EsitoSegno.FALLITO, esitoDelSegno(EsitoScrittura.Fallito))
        assertEquals(
            EsitoSegno.MANDATO,
            esitoDelSegno(esito(200, """{"mandato": true}""")),
        )
    }

    // --- la finestra v2.4 sul filo --------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    private fun finestra(extra: String): Finestra = json.decodeFromString(
        Finestra.serializer(),
        """
        {
          "bonus": { "giorno": { "usati": 0, "tetto": 30, "residui": 30 },
                     "settimana": { "usati": 0, "tetto": 90, "residui": 90 } },
          "stato_silenzio": { "ultimo_battito": null, "silente": true }
          $extra
        }
        """.trimIndent(),
    )

    @Test
    fun `il riepilogo del server arriva, e manca su un server vecchio`() {
        assertEquals(
            RiepilogoFinestra(giorniFuoriRegola = 2, interruzioni = 1),
            finestra(""", "riepilogo": { "giorni_fuori_regola": 2, "interruzioni": 1 }""").riepilogo,
        )
        assertNull(finestra("").riepilogo)
    }

    @Test
    fun `il bonus accanto al limite arriva, e vale 0 su un server vecchio`() {
        val f = finestra(
            """, "uso_recente": [ { "giorno": "2026-09-19", "totale_minuti": 70,
                 "app": [ { "chiave": "tiktok", "minuti": 70, "limite": 60, "regola_id": 1, "bonus": 15 },
                          { "chiave": "insta", "minuti": 20, "limite": 60, "regola_id": 2 } ],
                 "categorie": [ { "chiave": "categoria:social", "minuti": 90, "limite": 120, "regola_id": 3, "bonus": 30 } ] } ]""",
        )
        val giorno = f.usoRecente.single()
        assertEquals(listOf(15, 0), giorno.app.map { it.bonus })
        assertEquals(30, giorno.categorie.single().bonus)
        assertTrue(f.striscia.isEmpty())
    }

    // --- v3: la famiglia, e il server 0.7 che non la conosce ---------------------------

    /** Com'è fatta GET /api/famiglia sul server v3 (server/app/routes/famiglia.py). */
    private val famigliaV3 = """
        { "figli": [
          { "id": 1, "nome": "Andrea",
            "striscia": [ { "data": "2026-09-24", "stato": "verde" } ],
            "riepilogo": { "giorni_fuori_regola": 1, "interruzioni": 0 },
            "notifiche_non_lette": 3,
            "dispositivi": [
              { "id": 1, "nome": "Telefono", "tipo": "telefono", "abbinato": true, "revocato": false,
                "versione_app": "0.8.0",
                "stato_silenzio": { "ultimo_battito": "2026-09-24T10:00:00+00:00", "silente": false,
                                    "spento": false, "spento_dal": null } },
              { "id": 2, "nome": "Computer di camera", "tipo": "computer", "abbinato": true, "revocato": false,
                "versione_app": null,
                "stato_silenzio": { "ultimo_battito": "2026-09-23T21:00:00+00:00", "silente": false,
                                    "spento": true, "spento_dal": "2026-09-23T21:10:00+00:00" } } ] },
          { "id": 2, "nome": "Luca", "striscia": [], "riepilogo": { "giorni_fuori_regola": 0, "interruzioni": 0 },
            "notifiche_non_lette": 0, "dispositivi": [] } ] }
    """.trimIndent()

    @Test
    fun `la famiglia v3 si legge coi figli e i loro dispositivi`() {
        val esito = PostinoClient.interpretaFamiglia(200, famigliaV3) as EsitoFamiglia.Letta
        val (andrea, luca) = esito.famiglia.figli
        assertEquals("Andrea", andrea.nome)
        assertEquals(3, andrea.notificheNonLette)
        assertEquals(listOf("telefono", "computer"), andrea.dispositivi.map { it.tipo })
        val computer = andrea.dispositivi.last()
        assertTrue(computer.statoSilenzio!!.spento)
        assertEquals("2026-09-23T21:10:00+00:00", computer.statoSilenzio!!.spentoDal)
        assertNull(computer.versioneApp)
        assertTrue(luca.dispositivi.isEmpty())
    }

    @Test
    fun `un 404 su famiglia e il server 0,7, non un errore`() {
        assertEquals(
            EsitoFamiglia.ServerVecchio,
            PostinoClient.interpretaFamiglia(404, """{"detail": "Not Found"}"""),
        )
    }

    @Test
    fun `il resto che non e una famiglia leggibile e un fallimento da ritentare`() {
        assertEquals(EsitoFamiglia.Fallita, PostinoClient.interpretaFamiglia(500, "boh"))
        assertEquals(EsitoFamiglia.Fallita, PostinoClient.interpretaFamiglia(401, """{"detail": "x"}"""))
        assertEquals(EsitoFamiglia.Fallita, PostinoClient.interpretaFamiglia(200, "non è json"))
        assertEquals(EsitoFamiglia.Fallita, PostinoClient.interpretaFamiglia(200, null))
    }

    @Test
    fun `figlio_id si aggiunge solo quando il figlio e noto`() {
        assertEquals("/api/finestra", PostinoClient.conFiglio("/api/finestra", null))
        assertEquals("/api/finestra?figlio_id=2", PostinoClient.conFiglio("/api/finestra", 2))
        assertEquals("/api/proposte?figlio_id=11", PostinoClient.conFiglio("/api/proposte", 11))
    }

    // --- v3: i corpi delle scritture --------------------------------------------------

    // Lo stesso formato con cui il client scrive i corpi: i default non si scrivono.
    private val jsonScrittura = PostinoClient.json

    @Test
    fun `figlio_id nel corpo solo se c'e - il server 0,7 riceve i corpi di prima`() {
        val senza = jsonScrittura.encodeToString(
            NuovaProposta.serializer(),
            NuovaProposta(regolaId = 1, parametriProposti = JsonObject(emptyMap())),
        )
        assertFalse(senza, senza.contains("figlio_id"))
        val con = jsonScrittura.encodeToString(
            NuovaProposta.serializer(),
            NuovaProposta(regolaId = 1, parametriProposti = JsonObject(emptyMap()), figlioId = 2),
        )
        assertTrue(con, con.contains("\"figlio_id\":2"))
        val verdetto = jsonScrittura.encodeToString(CorpoVerdetto.serializer(), CorpoVerdetto("conferma", null, 2))
        assertTrue(verdetto, verdetto.contains("\"figlio_id\":2"))
        assertEquals(
            """{"figlio_id":3}""",
            jsonScrittura.encodeToString(CorpoSegno.serializer(), CorpoSegno(3)),
        )
    }

    @Test
    fun `il codice di un dispositivo nuovo si legge come lo manda il server`() {
        val esito = PostinoClient.interpretaRisposta(
            201,
            """{ "dispositivo": { "id": 5, "nome": "Computer di camera", "tipo": "computer",
                 "abbinato": false, "revocato": false, "figlio_id": 1, "versione_app": null },
                 "codice": "048392", "scade_ts": "2026-09-24T10:15:00+00:00" }""",
            CodiceAbbinamento.serializer(),
        ) as EsitoScrittura.Riuscito
        assertEquals("048392", esito.dato.codice)
        assertEquals(5L, esito.dato.dispositivo?.id)
        assertEquals("computer", esito.dato.dispositivo?.tipo)
        assertEquals("2026-09-24T10:15:00+00:00", esito.dato.scadeTs)
    }

    // --- v3: i rifiuti sulla famiglia --------------------------------------------------

    @Test
    fun `un 429 porta il codice e quanto aspettare`() {
        assertEquals(
            EsitoScrittura.Rifiutato("troppi_tentativi", 540),
            esito(429, """{"detail": {"errore": "troppi_tentativi", "riprova_tra_secondi": 540}}"""),
        )
        // Senza corpo leggibile resta "troppi tentativi", senza attesa inventata.
        assertEquals(EsitoScrittura.Rifiutato("troppi_tentativi", null), esito(429, ""))
        assertEquals(540L, PostinoClient.riprovaTraSecondi("""{"riprova_tra_secondi": 540}"""))
        assertNull(PostinoClient.riprovaTraSecondi("""{"detail": {"riprova_tra_secondi": "tanti"}}"""))
    }

    @Test
    fun `un 404 e un figlio o un dispositivo che non c'e piu`() {
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.NON_TROVATO),
            esito(404, """{"detail": "dispositivo non trovato"}"""),
        )
    }

    @Test
    fun `una revoca va bene con qualunque 2xx, anche senza corpo`() {
        assertEquals(
            EsitoScrittura.Riuscito(Unit),
            PostinoClient.interpretaSenzaDato(200, """{"id": 5, "revocato": true}"""),
        )
        assertEquals(EsitoScrittura.Riuscito(Unit), PostinoClient.interpretaSenzaDato(204, null))
        assertEquals(
            EsitoScrittura.Rifiutato("dispositivo_revocato"),
            PostinoClient.interpretaSenzaDato(409, """{"detail": {"errore": "dispositivo_revocato"}}"""),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(PostinoClient.PARAMETRI_NON_VALIDI),
            PostinoClient.interpretaSenzaDato(422, """{"detail": [{"msg": "nome"}]}"""),
        )
        assertEquals(EsitoScrittura.Fallito, PostinoClient.interpretaSenzaDato(500, null))
    }

    // --- v3: la finestra e le notifiche sul filo -----------------------------------------

    private fun leggiFinestra(testo: String): Finestra =
        PostinoClient.json.decodeFromString(Finestra.serializer(), testo)

    @Test
    fun `la finestra v3 porta i dispositivi, le regole il loro dispositivo, gli eventi da dove`() {
        val f = leggiFinestra(
            """
            { "regole": [ { "id": 7, "tipo": "limite_tempo",
                            "parametri": { "app_o_categoria": "sito:youtube.com", "minuti_al_giorno": 30 },
                            "nome": "sito:youtube.com", "attiva": true, "figlio_id": 1, "dispositivo_id": 2,
                            "dispositivo": { "id": 2, "nome": "Computer di camera", "tipo": "computer" },
                            "semaforo": [] },
                          { "id": 8, "tipo": "vita_reale", "parametri": { "descrizione": "Camminare" },
                            "dispositivo_id": null, "dispositivo": null } ],
              "manomissioni_recenti": [ { "id": "e1", "tipo": "manomissione",
                  "dettagli": { "sotto_tipo": "programma_chiuso", "dal": 1790000000000, "al": 1790001800000 },
                  "ts_device": null, "ts_server": "2026-09-24T10:00:00+00:00", "dispositivo_id": 2 } ],
              "bonus": { "giorno": { "usati": 0, "tetto": 30, "residui": 30 },
                         "settimana": { "usati": 0, "tetto": 90, "residui": 90 } },
              "stato_silenzio": { "ultimo_battito": null, "silente": true, "spento": false, "spento_dal": null },
              "dispositivi": [
                { "id": 2, "nome": "Computer di camera", "tipo": "computer", "abbinato": true, "revocato": false,
                  "stato_silenzio": { "ultimo_battito": "2026-09-24T09:00:00+00:00", "silente": false,
                                      "spento": true, "spento_dal": "2026-09-24T09:05:00+00:00" },
                  "striscia": [ { "data": "2026-09-24", "stato": "verde" } ],
                  "uso_recente": [ { "giorno": "2026-09-24", "totale_minuti": 131,
                     "app": [ { "chiave": "exe:minecraft.exe", "nome": "Minecraft", "minuti": 80 } ], "categorie": [] } ],
                  "siti_recenti": [ { "giorno": "2026-09-24", "totale_domini": 1, "dns_cifrato": false,
                     "aggiornato_ts": null, "domini": [ { "dominio": "youtube.com", "visite": 7, "minuti": 42 } ] } ],
                  "medie": { "settimana": null, "mese": null },
                  "bonus": { "giorno": { "usati": 0, "tetto": 30, "residui": 30 },
                             "settimana": { "usati": 0, "tetto": 90, "residui": 90 } },
                  "bonus_giornalieri": [ { "giorno": "2026-09-24", "minuti": 0 } ] } ] }
            """.trimIndent(),
        )
        val (limite, vita) = f.regole
        assertEquals(2L, limite.dispositivoId)
        assertEquals("computer", limite.dispositivo?.tipo)
        assertNull(vita.dispositivo)
        assertEquals(2L, f.manomissioniRecenti.single().dispositivoId)
        val computer = f.dispositivi.single()
        assertTrue(computer.statoSilenzio!!.spento)
        assertEquals(42, computer.sitiRecenti!!.single().domini.single().minuti)
        assertEquals("exe:minecraft.exe", computer.usoRecente.single().app.single().chiave)
    }

    @Test
    fun `la finestra di un server 0,7 si legge ancora, senza dispositivi`() {
        val f = finestra("")
        assertTrue(f.dispositivi.isEmpty())
        assertFalse(f.statoSilenzio!!.spento)
        assertNull(f.statoSilenzio!!.spentoDal)
    }

    @Test
    fun `un campo a null o mancante non fa sembrare irraggiungibile una finestra buona`() {
        // Un figlio appena creato: niente bonus, silenzio a null, liste a null.
        val f = leggiFinestra("""{ "regole": null, "stato_silenzio": null, "uso_recente": null }""")
        assertTrue(f.regole.isEmpty())
        assertNull(f.bonus)
        assertNull(f.statoSilenzio)
        assertTrue(f.usoRecente.isEmpty())
    }

    @Test
    fun `le notifiche v3 dicono di quale figlio e dispositivo, quelle 0,7 no`() {
        val v3 = PostinoClient.json.decodeFromString(
            PaccoNotifiche.serializer(),
            """{ "notifiche": [ { "id": 7, "tipo": "sforamento", "messaggio": "m", "payload": {},
                 "ts_server": "2026-09-24T10:00:00+00:00", "figlio_id": 2, "dispositivo_id": 5 } ] }""",
        ).notifiche.single()
        assertEquals(2L, v3.figlioId)
        assertEquals(5L, v3.dispositivoId)
        val vecchia = PostinoClient.json.decodeFromString(
            PaccoNotifiche.serializer(),
            """{ "notifiche": [ { "id": 7, "tipo": "sforamento", "messaggio": "m",
                 "ts_server": "2026-09-24T10:00:00+00:00" } ] }""",
        ).notifiche.single()
        assertNull(vecchia.figlioId)
        assertNull(vecchia.dispositivoId)
    }
}
