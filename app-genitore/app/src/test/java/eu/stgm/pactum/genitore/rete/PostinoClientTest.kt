package eu.stgm.pactum.genitore.rete

import eu.stgm.pactum.genitore.dati.CodiceAbbinamento
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.CorpoRispostaProposta
import eu.stgm.pactum.genitore.dati.CorpoRispostaSessione
import eu.stgm.pactum.genitore.dati.CorpoSegno
import eu.stgm.pactum.genitore.dati.CorpoVerdetto
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.NuovaProposta
import eu.stgm.pactum.genitore.dati.PaccoNotifiche
import eu.stgm.pactum.genitore.dati.PaccoProposte
import eu.stgm.pactum.genitore.dati.PropostaDecisa
import eu.stgm.pactum.genitore.dati.RiepilogoFinestra
import eu.stgm.pactum.genitore.dati.SegnoMandato
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.ui.FinestraViewModel.EsitoSegno
import eu.stgm.pactum.genitore.ui.esitoDelSegno
import eu.stgm.pactum.genitore.ui.propostaChiusaDopo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.time.Instant

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

    @Test
    fun `v3,3 - il giorno porta il limite sul totale del dispositivo, e senza regola niente`() {
        val f = leggiFinestra(
            """
            { "dispositivi": [ { "id": 1, "nome": "Telefono", "tipo": "telefono",
                "uso_recente": [
                  { "giorno": "2026-09-28", "totale_minuti": null, "aggiornato_ts": null, "app": [], "categorie": [] },
                  { "giorno": "2026-09-29", "totale_minuti": 150, "app": [], "categorie": [] },
                  { "giorno": "2026-09-30", "totale_minuti": 192, "limite": 180, "regola_id": 7, "bonus": 15,
                    "aggiornato_ts": "2026-09-30T18:00:00+00:00",
                    "app": [ { "chiave": "com.zhiliaoapp.musically", "nome": "TikTok", "minuti": 65 } ],
                    "categorie": [] } ] } ],
              "uso_recente": [ { "giorno": "2026-09-30", "totale_minuti": 192, "limite": 180, "regola_id": 7,
                                 "bonus": null, "app": [], "categorie": [] } ] }
            """.trimIndent(),
        )
        val (senzaFotografia, senzaRegola, conLimite) = f.dispositivi.single().usoRecente
        assertEquals(180, conLimite.limite)
        assertEquals(7L, conLimite.regolaId)
        assertEquals(15, conLimite.bonus)
        // Il limite del totale non finisce tra le app.
        assertNull(conLimite.app.single().limite)
        // Un giorno senza regola sul totale, o senza fotografia: niente limite, bonus 0.
        listOf(senzaFotografia, senzaRegola).forEach {
            assertNull(it.limite)
            assertNull(it.regolaId)
            assertEquals(0, it.bonus)
        }
        assertNull(senzaFotografia.totaleMinuti)
        // Il primo livello (primo dispositivo) porta gli stessi campi; un bonus a null vale 0.
        assertEquals(180, f.usoRecente.single().limite)
        assertEquals(0, f.usoRecente.single().bonus)
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
    fun `dopo_id si aggiunge solo con un id valido`() {
        assertEquals("/api/notifiche", PostinoClient.percorsoNotifiche(null))
        assertEquals("/api/notifiche?dopo_id=0", PostinoClient.percorsoNotifiche(0))
        assertEquals("/api/notifiche?dopo_id=42", PostinoClient.percorsoNotifiche(42))
        // Un negativo il server lo rifiuta (422): meglio la lista intera.
        assertEquals("/api/notifiche", PostinoClient.percorsoNotifiche(-1))
    }

    @Test
    fun `una risposta senza la lista delle notifiche non vale zero non lette`() {
        // `{}` o `null` non sono "nessuna notifica": sono una risposta sbagliata.
        // Se valessero una lista vuota, la vedetta dimenticherebbe gli avvisi dati
        // e al giro dopo li ridarebbe tutti.
        listOf("{}", """{"notifiche": null}""").forEach { corpo ->
            val errore = try {
                PostinoClient.json.decodeFromString(PaccoNotifiche.serializer(), corpo)
                null
            } catch (e: SerializationException) {
                e
            }
            assertTrue("«$corpo» non deve valere zero non lette", errore != null)
        }
        // Una lista vuota vera, invece, è "niente di nuovo".
        assertTrue(
            PostinoClient.json.decodeFromString(PaccoNotifiche.serializer(), """{"notifiche": []}""").notifiche.isEmpty(),
        )
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

    // --- v3: le creazioni, senza ritentativi -------------------------------------------

    @Test
    fun `senza ritentativi si prova prima un indirizzo IPv4, poi gli altri nell'ordine del sistema`() {
        val ipv6a = InetAddress.getByAddress("server", ByteArray(16) { if (it == 15) 1 else 0 })
        val ipv4a = InetAddress.getByAddress("server", byteArrayOf(100, 64, 0, 1))
        val ipv6b = InetAddress.getByAddress("server", ByteArray(16) { if (it == 15) 2 else 0 })
        val ipv4b = InetAddress.getByAddress("server", byteArrayOf(100, 64, 0, 2))
        assertEquals(
            listOf(ipv4a, ipv4b, ipv6a, ipv6b),
            PostinoClient.primaIpv4(listOf(ipv6a, ipv4a, ipv6b, ipv4b)),
        )
        assertEquals(listOf(ipv6a), PostinoClient.primaIpv4(listOf(ipv6a)))
        assertEquals(emptyList<InetAddress>(), PostinoClient.primaIpv4(emptyList()))
    }

    @Test
    fun `l'ora del server si legge dall'header Date, e se non si legge non si inventa`() {
        assertEquals(
            Instant.parse("2026-09-24T10:00:00Z"),
            PostinoClient.oraDalHeader("Thu, 24 Sep 2026 10:00:00 GMT"),
        )
        assertNull(PostinoClient.oraDalHeader(null))
        assertNull(PostinoClient.oraDalHeader(""))
        assertNull(PostinoClient.oraDalHeader("ieri sera"))
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

    // --- 0.10: le proposte del figlio sul filo (contratto v3.4) --------------------------

    @Test
    fun `una proposta senza autore e del genitore, come tutte quelle di prima della v3,4`() {
        val proposte = PostinoClient.json.decodeFromString(
            PaccoProposte.serializer(),
            """{ "proposte": [
                 { "id": 1, "regola_id": 7,
                   "parametri_proposti": { "app_o_categoria": "totale", "minuti_al_giorno": 200 },
                   "motivazione": null, "confronto": "+20 min al giorno rispetto ad ora", "direzione": "allenta",
                   "stato": "pendente", "usata": false, "ts_server": "2026-10-01T10:00:00+00:00", "risposta": null },
                 { "id": 2, "regola_id": 8, "stato": "ritirata", "autore": "figlio" },
                 { "id": 3, "regola_id": 9, "stato": "accettata", "autore": null,
                   "risposta": { "esito": "accetta", "motivazione": "va bene", "ts_server": "2026-10-01T11:00:00+00:00" } } ] }""",
        ).proposte
        assertEquals(listOf("genitore", "figlio", "genitore"), proposte.map { it.autore })
        assertEquals("ritirata", proposte[1].stato)
        assertEquals("va bene", proposte[2].risposta?.motivazione)
    }

    @Test
    fun `la finestra porta le proposte in attesa coi loro autori, un server vecchio nessuna`() {
        val f = leggiFinestra(
            """
            { "proposte_pendenti": [
                { "id": 12, "regola_id": 1, "autore": "figlio", "stato": "pendente",
                  "parametri_proposti": { "app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 90 },
                  "motivazione": "sabato niente scuola", "confronto": "+30 min al giorno rispetto ad ora",
                  "direzione": "allenta", "usata": false, "ts_server": "2026-10-01T15:00:00+00:00", "risposta": null },
                { "id": 11, "regola_id": 2, "autore": "genitore", "stato": "pendente",
                  "parametri_proposti": { "azione": "elimina" }, "confronto": "propone di eliminare la regola",
                  "direzione": "elimina" } ] }
            """.trimIndent(),
        )
        val (delFiglio, delGenitore) = f.propostePendenti
        assertEquals("figlio", delFiglio.autore)
        assertEquals("sabato niente scuola", delFiglio.motivazione)
        assertEquals("genitore", delGenitore.autore)
        assertEquals("elimina", delGenitore.direzione)
        // Server più vecchio della v3.4: il campo non c'è (o è null), nessuna proposta.
        assertTrue(finestra("").propostePendenti.isEmpty())
        assertTrue(leggiFinestra("""{ "proposte_pendenti": null }""").propostePendenti.isEmpty())
    }

    @Test
    fun `la famiglia dice quante proposte di ciascun figlio aspettano il genitore`() {
        val conProposte = famigliaV3.replace(
            "\"notifiche_non_lette\": 3,",
            "\"notifiche_non_lette\": 3, \"proposte_da_decidere\": 2,",
        )
        val (andrea, luca) = (PostinoClient.interpretaFamiglia(200, conProposte) as EsitoFamiglia.Letta).famiglia.figli
        assertEquals(2, andrea.proposteDaDecidere)
        assertEquals(0, luca.proposteDaDecidere)
        // Un server più vecchio non lo manda: zero.
        val vecchia = (PostinoClient.interpretaFamiglia(200, famigliaV3) as EsitoFamiglia.Letta).famiglia.figli
        assertEquals(listOf(0, 0), vecchia.map { it.proposteDaDecidere })
    }

    private fun decisione(codice: Int, corpo: String?) = PostinoClient.interpretaDecisione(codice, corpo)

    /** Il dato di un 200 che si legge. */
    private fun decisa(corpo: String): PropostaDecisa =
        checkNotNull((decisione(200, corpo) as EsitoScrittura.Riuscito).dato) { "il corpo doveva leggersi: $corpo" }

    @Test
    fun `la risposta del genitore porta la proposta chiusa e la regola che ne risulta`() {
        val accettata = decisa(
            """{ "proposta": { "id": 12, "regola_id": 1, "autore": "figlio", "stato": "accettata", "usata": true,
                   "confronto": "+30 min al giorno rispetto ad ora", "direzione": "allenta",
                   "risposta": { "esito": "accetta", "motivazione": null, "ts_server": "2026-10-01T16:00:00+00:00" } },
                 "regola": { "id": 1, "tipo": "limite_tempo",
                   "parametri": { "app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 90 },
                   "attiva": true, "figlio_id": 1, "dispositivo_id": 1 } }""",
        )
        assertEquals("accettata", accettata.proposta.stato)
        assertTrue(accettata.proposta.usata)
        assertEquals("accetta", accettata.proposta.risposta?.esito)
        assertTrue(accettata.regola != null)
        // Un'eliminazione accettata (o un rifiuto): niente regola, e va bene lo stesso.
        val eliminata = decisa(
            """{ "proposta": { "id": 13, "regola_id": 2, "autore": "figlio", "stato": "accettata" }, "regola": null }""",
        )
        assertNull(eliminata.regola)
    }

    @Test
    fun `un 2xx e una decisione fatta anche se il corpo non si legge`() {
        // Su un sì la regola è già cambiata: dire "riprova" farebbe sentire al padre,
        // riprovando, che la proposta "non è più in attesa".
        assertEquals(EsitoScrittura.Riuscito(null), decisione(200, "non è json"))
        assertEquals(EsitoScrittura.Riuscito(null), decisione(204, null))
        assertEquals(EsitoScrittura.Riuscito(null), decisione(200, """{ "esito": "accetta" }"""))
        assertEquals(EsitoScrittura.Riuscito(null), PostinoClient.interpretaRitiro(200, "boh"))
    }

    @Test
    fun `i rifiuti della risposta del genitore portano su il loro codice`() {
        listOf("proposta_non_pendente", "ultima_regola", "dispositivo_revocato").forEach { codice ->
            assertEquals(
                EsitoScrittura.Rifiutato(codice),
                decisione(409, """{"detail": {"errore": "$codice"}}"""),
            )
        }
        // Un 404 della rotta: la proposta (o il figlio) non c'è.
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.NON_TROVATO),
            decisione(404, """{"detail": "proposta non trovata"}"""),
        )
        // Una rotta che il server non conosce: va aggiornato, come per il ritiro.
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE),
            decisione(404, """{"detail": "Not Found"}"""),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE),
            decisione(405, """{"detail": "Method Not Allowed"}"""),
        )
        assertEquals(EsitoScrittura.Fallito, decisione(500, null))
    }

    @Test
    fun `il corpo della risposta porta il figlio, e non scrive un perche vuoto`() {
        assertEquals(
            """{"esito":"rifiuta"}""",
            jsonScrittura.encodeToString(CorpoRispostaProposta.serializer(), CorpoRispostaProposta("rifiuta")),
        )
        assertEquals(
            """{"esito":"accetta","motivazione":"solo il sabato","figlio_id":2}""",
            jsonScrittura.encodeToString(
                CorpoRispostaProposta.serializer(),
                CorpoRispostaProposta("accetta", "solo il sabato", figlioId = 2),
            ),
        )
    }

    @Test
    fun `le proposte si chiedono sempre di tutti e due gli autori`() {
        // Senza `autori=tutti` il server v3.4 manda solo quelle del genitore (app 0.8/0.9).
        assertEquals("/api/proposte?autori=tutti", PostinoClient.percorsoProposte(null))
        assertEquals("/api/proposte?figlio_id=2&autori=tutti", PostinoClient.percorsoProposte(2))
    }

    @Test
    fun `un ritiro su un server che non lo conosce dice di aggiornare il server`() {
        // FastAPI su una rotta che non esiste: 404 "Not Found" (o 405 dietro altri giri).
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE),
            PostinoClient.interpretaRitiro(404, """{"detail": "Not Found"}"""),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE),
            PostinoClient.interpretaRitiro(405, """{"detail": "Method Not Allowed"}"""),
        )
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE),
            PostinoClient.interpretaRitiro(404, ""),
        )
        // Un 404 detto dalla rotta che c'è: la proposta non c'è più, il server va bene.
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.NON_TROVATO),
            PostinoClient.interpretaRitiro(404, """{"detail": "proposta non trovata"}"""),
        )
    }

    @Test
    fun `un ritiro riuscito porta la proposta ritirata, uno arrivato tardi il suo codice`() {
        val ritirata = PostinoClient.interpretaRitiro(
            200,
            """{ "id": 5, "regola_id": 1, "autore": "genitore", "stato": "ritirata", "usata": false,
                 "confronto": "−30 min al giorno rispetto ad ora", "direzione": "stringe" }""",
        ) as EsitoScrittura.Riuscito
        assertEquals("ritirata", ritirata.dato?.stato)
        assertEquals(
            EsitoScrittura.Rifiutato(CodiciErrore.PROPOSTA_NON_PENDENTE),
            PostinoClient.interpretaRitiro(409, """{"detail": {"errore": "proposta_non_pendente"}}"""),
        )
        // La proposta di un altro: un 403 non è un codice da dire, "riprova".
        assertEquals(EsitoScrittura.Fallito, PostinoClient.interpretaRitiro(403, """{"detail": "non tua"}"""))
    }

    @Test
    fun `la card sparisce solo quando la proposta non e piu in attesa`() {
        val riuscita = EsitoScrittura.Riuscito(Unit)
        assertTrue(propostaChiusaDopo(riuscita))
        assertTrue(propostaChiusaDopo(EsitoScrittura.Rifiutato(CodiciErrore.PROPOSTA_NON_PENDENTE)))
        assertTrue(propostaChiusaDopo(EsitoScrittura.Rifiutato(CodiciErrore.NON_TROVATO)))
        // Ultima regola o dispositivo scollegato: resta in attesa, si può ancora rifiutare.
        assertFalse(propostaChiusaDopo(EsitoScrittura.Rifiutato(CodiciErrore.ULTIMA_REGOLA)))
        assertFalse(propostaChiusaDopo(EsitoScrittura.Rifiutato(CodiciErrore.DISPOSITIVO_REVOCATO)))
        assertFalse(propostaChiusaDopo(EsitoScrittura.Rifiutato(null)))
        // Rete caduta: non si sa niente, la card resta.
        assertFalse(propostaChiusaDopo(EsitoScrittura.Fallito))
    }

    // --- 0.11: le sessioni sul filo (contratto v3.5) --------------------------------------

    /** Una finestra v3.5 com'è scritta nel contratto, con una sessione per tipo. */
    private val finestraConSessioni = """
        { "sessioni": [
            { "id": 3, "dispositivo_id": 1, "dispositivo": { "id": 1, "nome": "Telefono", "tipo": "telefono" },
              "nome": "Studio", "app": ["eu.spaggiari.classevivafamiglia", "gruppo:apk"],
              "nomi": { "eu.spaggiari.classevivafamiglia": "ClasseViva", "gruppo:apk": "App installate da APK" },
              "stato": "approvata",
              "modifica_in_attesa": { "nome": "Compiti", "app": ["eu.spaggiari.classevivafamiglia"],
                                      "nomi": { "eu.spaggiari.classevivafamiglia": "ClasseViva" },
                                      "richiesta_ts": "2026-10-01T15:30:00+00:00" },
              "motivazione": null, "versione": 4,
              "creata_ts": "2026-10-01T13:00:00+00:00", "approvata_ts": "2026-10-01T13:05:00+00:00" },
            { "id": 4, "dispositivo_id": 1, "nome": "Lavoro", "app": ["com.slack"], "nomi": null,
              "stato": "in_attesa", "modifica_in_attesa": null, "motivazione": null } ],
          "sessioni_da_approvare": 2,
          "sessioni_svolte": [
            { "id": 12, "sessione_id": 3, "dispositivo_id": 1, "nome": "Studio",
              "app": ["eu.spaggiari.classevivafamiglia"], "nomi": { "eu.spaggiari.classevivafamiglia": "ClasseViva" },
              "inizio_ts": "2026-10-01T13:02:00+00:00", "durata_minuti": 120,
              "fine_prevista_ts": "2026-10-01T15:02:00+00:00", "fine_ts": "2026-10-01T14:40:00+00:00",
              "chiusura": "terminata", "in_corso": false },
            { "id": 13, "sessione_id": 4, "dispositivo_id": 1, "nome": "Lavoro", "app": ["com.slack"], "nomi": {},
              "inizio_ts": "2026-10-01T16:30:00+00:00", "durata_minuti": 60,
              "fine_prevista_ts": "2026-10-01T17:30:00+00:00", "fine_ts": null, "chiusura": null, "in_corso": true } ],
          "uso_recente": [ { "giorno": "2026-10-01", "totale_minuti": 95, "sessioni_minuti": 80, "app": [], "categorie": [] } ],
          "dispositivi": [ { "id": 1, "nome": "Telefono", "tipo": "telefono",
              "uso_recente": [ { "giorno": "2026-09-30", "totale_minuti": 120, "app": [], "categorie": [] },
                               { "giorno": "2026-10-01", "totale_minuti": 95, "sessioni_minuti": 80,
                                 "app": [], "categorie": [] } ] } ] }
    """.trimIndent()

    @Test
    fun `la finestra porta le sessioni, quante aspettano e quelle fatte`() {
        val f = leggiFinestra(finestraConSessioni)
        val (studio, lavoro) = f.sessioni
        assertEquals("Studio", studio.nome)
        assertEquals(listOf("eu.spaggiari.classevivafamiglia", "gruppo:apk"), studio.app)
        assertEquals("ClasseViva", studio.nomi["eu.spaggiari.classevivafamiglia"])
        assertEquals("telefono", studio.dispositivo?.tipo)
        assertEquals("approvata", studio.stato)
        assertEquals("Compiti", studio.modificaInAttesa?.nome)
        assertEquals(listOf("eu.spaggiari.classevivafamiglia"), studio.modificaInAttesa?.app)
        assertEquals("2026-10-01T15:30:00+00:00", studio.modificaInAttesa?.richiestaTs)
        assertEquals(4, studio.versione)
        assertEquals("2026-10-01T13:05:00+00:00", studio.approvataTs)
        // Nomi a null, nessun cambio, nessuna versione: vuoti e null, non un errore.
        assertTrue(lavoro.nomi.isEmpty())
        assertNull(lavoro.modificaInAttesa)
        assertNull(lavoro.versione)
        assertEquals("in_attesa", lavoro.stato)
        assertEquals(2, f.sessioniDaApprovare)
        val (chiusaPrima, inCorso) = f.sessioniSvolte
        assertEquals("terminata", chiusaPrima.chiusura)
        assertEquals(120, chiusaPrima.durataMinuti)
        assertEquals("2026-10-01T14:40:00+00:00", chiusaPrima.fineTs)
        assertEquals(3L, chiusaPrima.sessioneId)
        assertTrue(inCorso.inCorso)
        assertNull(inCorso.chiusura)
        assertNull(inCorso.fineTs)
    }

    @Test
    fun `i minuti in sessione arrivano nel giorno, e mancano dove il telefono non li manda`() {
        val f = leggiFinestra(finestraConSessioni)
        assertEquals(80, f.usoRecente.single().sessioniMinuti)
        val (ieri, oggi) = f.dispositivi.single().usoRecente
        assertNull(ieri.sessioniMinuti)
        assertEquals(80, oggi.sessioniMinuti)
        assertNull(leggiFinestra("""{ "uso_recente": [ { "giorno": "2026-10-01", "sessioni_minuti": null } ] }""").usoRecente.single().sessioniMinuti)
    }

    @Test
    fun `un modifica_in_attesa senza qualche campo vale come adesso per quei campi`() {
        val sessione = PostinoClient.json.decodeFromString(
            Sessione.serializer(),
            """{ "id": 3, "nome": "Studio", "app": ["gruppo:apk"], "stato": "approvata",
                 "modifica_in_attesa": { "app": ["com.duolingo", "gruppo:apk"] } }""",
        )
        assertNull(sessione.modificaInAttesa?.nome)
        assertNull(sessione.modificaInAttesa?.nomi)
        assertEquals(listOf("com.duolingo", "gruppo:apk"), sessione.modificaInAttesa?.app)
    }

    @Test
    fun `un server piu vecchio della v3,5 non manda niente delle sessioni, e la finestra si legge`() {
        val vecchia = finestra("")
        assertTrue(vecchia.sessioni.isEmpty())
        assertEquals(0, vecchia.sessioniDaApprovare)
        assertTrue(vecchia.sessioniSvolte.isEmpty())
        val aNull = leggiFinestra("""{ "sessioni": null, "sessioni_da_approvare": null, "sessioni_svolte": null }""")
        assertTrue(aNull.sessioni.isEmpty())
        assertEquals(0, aNull.sessioniDaApprovare)
        assertTrue(aNull.sessioniSvolte.isEmpty())
    }

    @Test
    fun `la famiglia dice quante sessioni di ciascun figlio aspettano il genitore`() {
        val conSessioni = famigliaV3.replace(
            "\"notifiche_non_lette\": 3,",
            "\"notifiche_non_lette\": 3, \"proposte_da_decidere\": 1, \"sessioni_da_approvare\": 2,",
        )
        val (andrea, luca) = (PostinoClient.interpretaFamiglia(200, conSessioni) as EsitoFamiglia.Letta).famiglia.figli
        assertEquals(2, andrea.sessioniDaApprovare)
        assertEquals(1, andrea.proposteDaDecidere)
        assertEquals(0, luca.sessioniDaApprovare)
        // Un server più vecchio non lo manda: zero.
        val vecchia = (PostinoClient.interpretaFamiglia(200, famigliaV3) as EsitoFamiglia.Letta).famiglia.figli
        assertEquals(listOf(0, 0), vecchia.map { it.sessioniDaApprovare })
    }

    @Test
    fun `le sessioni del figlio si leggono, e un server vecchio va aggiornato`() {
        assertEquals("/api/sessioni?figlio_id=2", PostinoClient.conFiglio("/api/sessioni", 2))
        val lette = PostinoClient.interpretaSessioni(
            200,
            """{ "sessioni": [ { "id": 3, "nome": "Studio", "app": ["gruppo:apk"], "stato": "in_attesa" } ] }""",
        ) as EsitoSessioni.Lette
        assertEquals(listOf(3L), lette.sessioni.map { it.id })
        assertEquals(EsitoSessioni.Lette(emptyList()), PostinoClient.interpretaSessioni(200, """{ "sessioni": [] }"""))
        // La rotta che il server non conosce: server più vecchio della v3.5.
        assertEquals(EsitoSessioni.ServerVecchio, PostinoClient.interpretaSessioni(404, """{"detail": "Not Found"}"""))
        assertEquals(EsitoSessioni.ServerVecchio, PostinoClient.interpretaSessioni(405, """{"detail": "Method Not Allowed"}"""))
        // Un 404 della rotta (il figlio non c'è), un errore, un corpo che non si legge: da ritentare.
        assertEquals(EsitoSessioni.Fallita, PostinoClient.interpretaSessioni(404, """{"detail": "figlio non trovato"}"""))
        assertEquals(EsitoSessioni.Fallita, PostinoClient.interpretaSessioni(500, null))
        assertEquals(EsitoSessioni.Fallita, PostinoClient.interpretaSessioni(200, "non è json"))
    }

    private fun rispostaSessione(codice: Int, corpo: String?) = PostinoClient.interpretaRispostaSessione(codice, corpo)

    @Test
    fun `la risposta a una sessione - un 2xx e fatta, anche col corpo che non si legge`() {
        val approvata = rispostaSessione(
            200,
            """{ "id": 3, "nome": "Studio", "app": ["gruppo:apk"], "stato": "approvata",
                 "modifica_in_attesa": null, "versione": 3, "approvata_ts": "2026-10-01T13:05:00+00:00" }""",
        ) as EsitoRispostaSessione.Decisa
        assertEquals("approvata", approvata.sessione?.stato)
        assertEquals(3, approvata.sessione?.versione)
        assertEquals(EsitoRispostaSessione.Decisa(null), rispostaSessione(200, "boh"))
        assertEquals(EsitoRispostaSessione.Decisa(null), rispostaSessione(204, null))
    }

    @Test
    fun `una richiesta cambiata non decide niente, e porta la sessione com'e adesso`() {
        // Come la manda FastAPI: dentro `detail`.
        val cambiata = rispostaSessione(
            409,
            """{"detail": {"errore": "richiesta_cambiata",
                 "sessione": { "id": 3, "nome": "Studio", "app": ["com.duolingo", "gruppo:apk"],
                               "stato": "in_attesa", "versione": 5 } } }""",
        ) as EsitoRispostaSessione.Cambiata
        assertEquals(5, cambiata.sessione?.versione)
        assertEquals(listOf("com.duolingo", "gruppo:apk"), cambiata.sessione?.app)
        // Come la scrive il contratto: in cima.
        val inCima = rispostaSessione(
            409,
            """{"errore": "richiesta_cambiata", "sessione": { "id": 3, "nome": "Studio", "stato": "approvata", "versione": 6 } }""",
        ) as EsitoRispostaSessione.Cambiata
        assertEquals("approvata", inCima.sessione?.stato)
        // Senza la sessione (o con una che non si legge): cambiata lo stesso, e si rilegge.
        assertEquals(
            EsitoRispostaSessione.Cambiata(null),
            rispostaSessione(409, """{"detail": {"errore": "richiesta_cambiata"}}"""),
        )
        assertEquals(
            EsitoRispostaSessione.Cambiata(null),
            rispostaSessione(409, """{"detail": {"errore": "richiesta_cambiata", "sessione": {"nome": "senza id"}}}"""),
        )
    }

    @Test
    fun `i rifiuti della risposta a una sessione portano su il loro codice`() {
        listOf("niente_da_decidere", "dispositivo_revocato").forEach { codice ->
            assertEquals(
                EsitoRispostaSessione.Rifiutata(codice),
                rispostaSessione(409, """{"detail": {"errore": "$codice"}}"""),
            )
        }
        // Server più vecchio della v3.5: "Per le sessioni serve aggiornare il server di Pactum".
        assertEquals(
            EsitoRispostaSessione.Rifiutata(CodiciErrore.SERVER_DA_AGGIORNARE),
            rispostaSessione(404, """{"detail": "Not Found"}"""),
        )
        assertEquals(
            EsitoRispostaSessione.Rifiutata(CodiciErrore.SERVER_DA_AGGIORNARE),
            rispostaSessione(405, """{"detail": "Method Not Allowed"}"""),
        )
        assertEquals(EsitoRispostaSessione.Rifiutata(CodiciErrore.SERVER_DA_AGGIORNARE), rispostaSessione(404, ""))
        // Il 404 della rotta che c'è (contratto v3.5): la sessione non c'è più.
        assertEquals(
            EsitoRispostaSessione.Rifiutata(CodiciErrore.NON_TROVATO),
            rispostaSessione(404, """{"detail": "sessione non trovata"}"""),
        )
        assertEquals(
            EsitoRispostaSessione.Rifiutata(PostinoClient.PARAMETRI_NON_VALIDI),
            rispostaSessione(422, """{"detail": [{"msg": "motivazione"}]}"""),
        )
        assertEquals(EsitoRispostaSessione.Fallita, rispostaSessione(500, null))
        assertEquals(EsitoRispostaSessione.Fallita, rispostaSessione(403, """{"detail": "ruolo sbagliato"}"""))
    }

    @Test
    fun `il corpo della risposta porta la versione vista e il figlio, e non scrive un perche vuoto`() {
        assertEquals(
            """{"esito":"approva","versione":4,"figlio_id":2}""",
            jsonScrittura.encodeToString(
                CorpoRispostaSessione.serializer(),
                CorpoRispostaSessione("approva", versione = 4, figlioId = 2),
            ),
        )
        assertEquals(
            """{"esito":"rifiuta","versione":7,"motivazione":"prima i compiti"}""",
            jsonScrittura.encodeToString(
                CorpoRispostaSessione.serializer(),
                CorpoRispostaSessione("rifiuta", versione = 7, motivazione = "prima i compiti"),
            ),
        )
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
