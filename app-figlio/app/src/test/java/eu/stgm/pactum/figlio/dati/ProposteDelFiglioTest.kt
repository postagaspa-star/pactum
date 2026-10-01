package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.10) Le proposte del figlio (contratto v3.4): chi le ha fatte, cosa si
 * manda al server e cosa vuol dire ogni risposta. Un server vecchio non deve
 * mai diventare "errore": va aggiornato, e lo si dice.
 */
class ProposteDelFiglioTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun limite(minuti: Int) = buildJsonObject {
        put("app_o_categoria", "com.zhiliaoapp.musically")
        put("minuti_al_giorno", minuti)
    }

    private fun proposta(id: Long, regolaId: Long, stato: String, autore: String?) =
        Proposta(id = id, regolaId = regolaId, stato = stato, autore = autore)

    // --- Chi l'ha fatta -------------------------------------------------------

    @Test
    fun `senza autore la proposta e' del genitore, come prima della v3_4`() {
        val vecchia = json.decodeFromString(
            Proposta.serializer(),
            """{ "id": 4, "regola_id": 1, "stato": "pendente", "confronto": "−30 min al giorno rispetto ad ora" }""",
        )
        assertTrue(vecchia.delGenitore)
        assertFalse(vecchia.delFiglio)
        val conNull = json.decodeFromString(
            Proposta.serializer(),
            """{ "id": 4, "regola_id": 1, "stato": "pendente", "autore": null }""",
        )
        assertTrue(conNull.delGenitore)
    }

    @Test
    fun `l'autore figlio si riconosce, anche scritto in un altro modo`() {
        val corpo = """{ "id": 9, "regola_id": 1, "stato": "pendente", "autore": "figlio" }"""
        assertTrue(json.decodeFromString(Proposta.serializer(), corpo).delFiglio)
        assertTrue(proposta(1, 1, StatiProposta.PENDENTE, " Figlio ").delFiglio)
        assertTrue(proposta(1, 1, StatiProposta.PENDENTE, AutoriProposta.GENITORE).delGenitore)
    }

    // --- Il patto v3.4 ----------------------------------------------------------

    @Test
    fun `il patto v3_4 porta le proposte inviate, e le pendenti restano quelle da decidere`() {
        val corpo = """
            {
              "regole": [ { "id": 1, "tipo": "limite_tempo", "parametri": {} } ],
              "proposte_pendenti": [ { "id": 3, "regola_id": 1, "stato": "pendente", "autore": "genitore" } ],
              "proposte_inviate": [
                { "id": 7, "regola_id": 2, "parametri_proposti": { "azione": "elimina" }, "motivazione": "non serve più",
                  "confronto": "propone di eliminare la regola", "direzione": "elimina", "stato": "pendente",
                  "usata": false, "ts_server": "2026-10-01T09:00:00+00:00", "risposta": null, "autore": "figlio" }
              ]
            }
        """.trimIndent()
        val patto = json.decodeFromString(Patto.serializer(), corpo)
        assertEquals(listOf(3L), patto.propostePendenti.map { it.id })
        val inviata = patto.proposteInviate.single()
        assertTrue(inviata.delFiglio)
        assertEquals("non serve più", inviata.motivazione)
        assertEquals(DirezioniProposta.ELIMINA, inviata.direzione)
    }

    @Test
    fun `un server vecchio senza proposte inviate da' una lista vuota, non un errore`() {
        val patto = json.decodeFromString(
            Patto.serializer(),
            """{ "regole": [], "proposte_pendenti": [ { "id": 3, "regola_id": 1, "stato": "pendente" } ] }""",
        )
        assertTrue(patto.proposteInviate.isEmpty())
        assertTrue(patto.propostePendenti.single().delGenitore)
    }

    // --- Cosa si chiede e cosa si manda ---------------------------------------------

    @Test
    fun `l'elenco delle proposte si chiede sempre con tutti e due gli autori`() {
        // Senza il parametro un server v3.4 manda solo quelle del genitore.
        assertEquals("/api/proposte?autori=tutti", ProposteDelFiglio.PERCORSO_ELENCO)
    }

    @Test
    fun `la modifica parte con proprio quei parametri e il perche' ripulito`() {
        val richiesta = ProposteDelFiglio.richiesta(CambioRegola.Modifica(7, limite(90)), "  c'è la verifica  ")
        assertEquals(PropostaIn(regolaId = 7, parametriProposti = limite(90), motivazione = "c'è la verifica"), richiesta)
    }

    @Test
    fun `l'eliminazione parte col marcatore del contratto`() {
        val richiesta = ProposteDelFiglio.richiesta(CambioRegola.Eliminazione(7), null)
        assertEquals(buildJsonObject { put("azione", "elimina") }, richiesta.parametriProposti)
        assertEquals(7L, richiesta.regolaId)
    }

    @Test
    fun `senza perche' il campo non viaggia proprio`() {
        val client = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val vuoto = ProposteDelFiglio.richiesta(CambioRegola.Modifica(7, limite(90)), "   ")
        assertNull(vuoto.motivazione)
        val corpo = client.parseToJsonElement(client.encodeToString(PropostaIn.serializer(), vuoto)).jsonObject
        assertEquals(setOf("regola_id", "parametri_proposti"), corpo.keys)
        assertEquals("7", corpo["regola_id"]?.jsonPrimitive?.content)
    }

    // --- Cosa vuol dire la risposta ---------------------------------------------

    private val modifica = CambioRegola.Modifica(7, limite(90))
    private val eliminazione = CambioRegola.Eliminazione(7)

    @Test
    fun `arrivata, col confronto del server`() {
        val corpo = """
            { "id": 12, "regola_id": 7, "parametri_proposti": { "app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 90 },
              "motivazione": null, "confronto": "+30 min al giorno rispetto ad ora", "direzione": "allenta",
              "stato": "pendente", "usata": false, "ts_server": "2026-10-01T09:00:00+00:00", "risposta": null, "autore": "figlio" }
        """.trimIndent()
        assertEquals(
            EsitoProposta.Mandata(confronto = "+30 min al giorno rispetto ad ora", eliminazione = false),
            ProposteDelFiglio.esito(modifica, ok = true, codiceHttp = 200, corpo = corpo),
        )
    }

    @Test
    fun `arrivata anche se il corpo non si legge, e l'eliminazione si sa dal cambio`() {
        assertEquals(EsitoProposta.Mandata(null, false), ProposteDelFiglio.esito(modifica, true, 200, "non json"))
        assertEquals(EsitoProposta.Mandata(null, true), ProposteDelFiglio.esito(eliminazione, true, 200, null))
    }

    @Test
    fun `gia' una proposta in attesa, nella forma di FastAPI e in quella del contratto`() {
        assertEquals(
            EsitoProposta.GiaPendente,
            ProposteDelFiglio.esito(modifica, false, 409, """{ "detail": { "errore": "proposta_gia_pendente" } }"""),
        )
        assertEquals(
            EsitoProposta.GiaPendente,
            ProposteDelFiglio.esito(modifica, false, 409, """{ "errore": "proposta_gia_pendente" }"""),
        )
    }

    @Test
    fun `regola non valida e dispositivo scollegato si distinguono`() {
        assertEquals(
            EsitoProposta.RegolaNonValida,
            ProposteDelFiglio.esito(modifica, false, 409, """{ "detail": { "errore": "regola_non_valida" } }"""),
        )
        assertEquals(
            EsitoProposta.DispositivoRevocato,
            ProposteDelFiglio.esito(eliminazione, false, 409, """{ "detail": { "errore": "dispositivo_revocato" } }"""),
        )
    }

    @Test
    fun `un server vecchio risponde 403 e va aggiornato, non e' un errore`() {
        assertEquals(
            EsitoProposta.ServerDaAggiornare,
            ProposteDelFiglio.esito(modifica, false, 403, """{ "detail": "ruolo non autorizzato" }"""),
        )
        assertEquals(EsitoProposta.ServerDaAggiornare, ProposteDelFiglio.esito(eliminazione, false, 403, null))
    }

    @Test
    fun `valori non validi, scollegato, senza rete e il resto`() {
        assertEquals(
            EsitoProposta.ValoriNonValidi,
            ProposteDelFiglio.esito(modifica, false, 422, """{ "detail": [ { "loc": ["body"], "msg": "x" } ] }"""),
        )
        assertEquals(EsitoProposta.Scollegato, ProposteDelFiglio.esito(modifica, false, 401, null))
        assertEquals(EsitoProposta.SenzaRete, ProposteDelFiglio.esito(modifica, false, 0, null))
        assertEquals(EsitoProposta.Errore, ProposteDelFiglio.esito(modifica, false, 500, "Internal Server Error"))
        // Un 409 che non si conosce non è "già in attesa".
        assertEquals(EsitoProposta.Errore, ProposteDelFiglio.esito(modifica, false, 409, """{ "detail": { "errore": "boh" } }"""))
    }

    @Test
    fun `il modulo si chiude solo se la proposta e' arrivata, il resto si dice dentro`() {
        assertTrue(ProposteDelFiglio.chiudeIlModulo(EsitoProposta.Mandata("x", false)))
        for (esito in listOf(
            EsitoProposta.ValoriNonValidi, EsitoProposta.SenzaRete, EsitoProposta.Errore,
            EsitoProposta.GiaPendente, EsitoProposta.GiaTua, EsitoProposta.RegolaNonValida,
            EsitoProposta.DispositivoRevocato, EsitoProposta.ServerDaAggiornare, EsitoProposta.Scollegato,
        )) {
            assertFalse(esito.toString(), ProposteDelFiglio.chiudeIlModulo(esito))
        }
    }

    @Test
    fun `rimandare si puo' solo quando ha senso`() {
        assertTrue(ProposteDelFiglio.riprovabile(EsitoProposta.ValoriNonValidi))
        assertTrue(ProposteDelFiglio.riprovabile(EsitoProposta.SenzaRete))
        assertTrue(ProposteDelFiglio.riprovabile(EsitoProposta.Errore))
        assertFalse(ProposteDelFiglio.riprovabile(EsitoProposta.GiaPendente))
        assertFalse(ProposteDelFiglio.riprovabile(EsitoProposta.GiaTua))
        assertFalse(ProposteDelFiglio.riprovabile(EsitoProposta.RegolaNonValida))
        assertFalse(ProposteDelFiglio.riprovabile(EsitoProposta.ServerDaAggiornare))
        assertTrue(ProposteDelFiglio.serveRileggere(EsitoProposta.Mandata(null, true)))
        assertTrue(ProposteDelFiglio.serveRileggere(EsitoProposta.GiaTua))
        assertFalse(ProposteDelFiglio.serveRileggere(EsitoProposta.ServerDaAggiornare))
    }

    @Test
    fun `gia' in attesa e' la tua solo se fra le tue inviate ce n'e' una su quella regola`() {
        val tua = proposta(21, 7, StatiProposta.PENDENTE, AutoriProposta.FIGLIO)
        val suUnAltra = proposta(22, 8, StatiProposta.PENDENTE, AutoriProposta.FIGLIO)
        assertEquals(
            EsitoProposta.GiaTua,
            ProposteDelFiglio.precisaGiaPendente(EsitoProposta.GiaPendente, modifica, listOf(suUnAltra, tua)),
        )
        assertEquals(
            EsitoProposta.GiaPendente,
            ProposteDelFiglio.precisaGiaPendente(EsitoProposta.GiaPendente, modifica, listOf(suUnAltra)),
        )
        // Gli altri esiti restano com'erano.
        assertEquals(
            EsitoProposta.Errore,
            ProposteDelFiglio.precisaGiaPendente(EsitoProposta.Errore, modifica, listOf(tua)),
        )
    }

    // --- Il ritiro --------------------------------------------------------------

    @Test
    fun `il ritiro, e il server vecchio che non lo conosce`() {
        assertEquals(EsitoRitiro.Ritirata, ProposteDelFiglio.esitoRitiro(true, 200, "{}"))
        assertEquals(
            EsitoRitiro.NonPiuPendente,
            ProposteDelFiglio.esitoRitiro(false, 409, """{ "detail": { "errore": "proposta_non_pendente" } }"""),
        )
        assertEquals(EsitoRitiro.ServerDaAggiornare, ProposteDelFiglio.esitoRitiro(false, 404, """{ "detail": "Not Found" }"""))
        assertEquals(EsitoRitiro.ServerDaAggiornare, ProposteDelFiglio.esitoRitiro(false, 404, null))
        assertEquals(EsitoRitiro.ServerDaAggiornare, ProposteDelFiglio.esitoRitiro(false, 405, null))
        assertEquals(EsitoRitiro.Scollegato, ProposteDelFiglio.esitoRitiro(false, 401, null))
        assertEquals(EsitoRitiro.Errore, ProposteDelFiglio.esitoRitiro(false, 0, null))
        assertEquals(EsitoRitiro.Errore, ProposteDelFiglio.esitoRitiro(false, 403, null))
    }

    @Test
    fun `un server v3_4 che non trova piu' la proposta non e' un server da aggiornare`() {
        assertEquals(
            EsitoRitiro.NonTrovata,
            ProposteDelFiglio.esitoRitiro(false, 404, """{ "detail": "proposta non trovata" }"""),
        )
        assertEquals(
            EsitoRitiro.NonTrovata,
            ProposteDelFiglio.esitoRitiro(false, 404, """{ "detail": " Proposta non trovata " }"""),
        )
    }

    // --- Il blocco dei 4 giorni, conservato attraverso una rotazione ---------------

    @Test
    fun `il blocco si conserva come testo e torna uguale`() {
        val modificaBloccata = BloccoCambio(CambioRegola.Modifica(7, limite(90)), sbloccoAlle = 1_790_000_000_000)
        assertEquals(modificaBloccata, BloccoCambio.daTesto(modificaBloccata.inTesto()))
        val eliminazioneBloccata = BloccoCambio(CambioRegola.Eliminazione(7), sbloccoAlle = 1_790_000_000_000)
        assertEquals(eliminazioneBloccata, BloccoCambio.daTesto(eliminazioneBloccata.inTesto()))
        assertNull(BloccoCambio.daTesto(null))
        assertNull(BloccoCambio.daTesto("non json"))
        assertNull(BloccoCambio.daTesto("""{ "regola_id": 7 }"""))
    }

    @Test
    fun `l'attesa del blocco si conta dall'istante dello sblocco`() {
        val blocco = BloccoCambio(CambioRegola.Eliminazione(7), sbloccoAlle = 10_000_000L)
        assertEquals(3_600L, blocco.secondiRimanenti(adesso = 10_000_000L - 3_600_000L))
        assertEquals(0L, blocco.secondiRimanenti(adesso = 10_000_000L + 5_000L))
    }

    // --- Quali contano dove -----------------------------------------------------

    private val delGenitorePendente = proposta(1, 10, StatiProposta.PENDENTE, AutoriProposta.GENITORE)
    private val vecchiaPendente = proposta(2, 11, StatiProposta.PENDENTE, null)
    private val tuaPendente = proposta(3, 12, StatiProposta.PENDENTE, AutoriProposta.FIGLIO)
    private val tuaRifiutata = proposta(4, 10, StatiProposta.RIFIUTATA, AutoriProposta.FIGLIO)
    private val ritirata = proposta(5, 13, StatiProposta.RITIRATA, AutoriProposta.GENITORE)
    private val tutte = listOf(tuaPendente, delGenitorePendente, tuaRifiutata, vecchiaPendente, ritirata)

    @Test
    fun `da decidere, e nel numero sulla scheda, solo quelle del genitore in attesa`() {
        assertEquals(listOf(2L, 1L), ProposteDelFiglio.daDecidere(tutte).map { it.id })
    }

    @Test
    fun `da decidere non perde una pendente vecchia oltre le 50 della lista`() {
        // La lista (al massimo 50) non ha la 1, che il patto (senza tetto) ha ancora in attesa.
        val lista = listOf(proposta(60, 30, StatiProposta.PENDENTE, AutoriProposta.GENITORE), tuaPendente)
        val delPatto = listOf(delGenitorePendente, proposta(60, 30, StatiProposta.PENDENTE, AutoriProposta.GENITORE))
        assertEquals(listOf(60L, 1L), ProposteDelFiglio.daDecidere(lista, delPatto).map { it.id })
    }

    @Test
    fun `le inviate vengono dal patto, e senza patto dalle proposte`() {
        val patto = Patto(proposteInviate = listOf(tuaPendente))
        assertEquals(listOf(3L), ProposteDelFiglio.inviate(patto, emptyList()).map { it.id })
        assertEquals(listOf(3L), ProposteDelFiglio.inviate(null, tutte).map { it.id })
        // Col patto appena letto vale il patto, anche quando non ne ha.
        assertTrue(ProposteDelFiglio.inviate(Patto(), tutte).isEmpty())
    }

    @Test
    fun `su ogni regola al massimo una proposta in attesa, di chiunque sia`() {
        val perRegola = ProposteDelFiglio.inAttesaPerRegola(listOf(delGenitorePendente, ritirata), listOf(tuaPendente))
        assertEquals(setOf(10L, 12L), perRegola.keys)
        assertEquals(3L, perRegola[12L]?.id)
    }

    @Test
    fun `la storia tiene solo le chiuse, al massimo dieci`() {
        assertEquals(listOf(4L, 5L), ProposteDelFiglio.storia(tutte).map { it.id })
        val tante = (1L..15L).map { proposta(it, it, StatiProposta.ACCETTATA, null) }
        assertEquals((1L..10L).toList(), ProposteDelFiglio.storia(tante).map { it.id })
    }
}
