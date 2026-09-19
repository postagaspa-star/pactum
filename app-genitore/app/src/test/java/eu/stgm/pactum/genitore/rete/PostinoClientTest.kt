package eu.stgm.pactum.genitore.rete

import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.RiepilogoFinestra
import eu.stgm.pactum.genitore.dati.SegnoMandato
import eu.stgm.pactum.genitore.ui.FinestraViewModel.EsitoSegno
import eu.stgm.pactum.genitore.ui.esitoDelSegno
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
