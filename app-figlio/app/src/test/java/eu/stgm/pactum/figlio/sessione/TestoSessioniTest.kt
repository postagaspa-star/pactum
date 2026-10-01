package eu.stgm.pactum.figlio.sessione

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * (0.11) Le durate di una Sessione (da 1 minuto a 24 ore, senza tetto del
 * genitore) e le sue parole: mai il nome tecnico di un pacchetto.
 */
class TestoSessioniTest {

    private val roma = ZoneId.of("Europe/Rome")

    private fun ms(testo: String): Long = LocalDateTime.parse(testo).atZone(roma).toInstant().toEpochMilli()

    // --- durate ------------------------------------------------------------------

    @Test
    fun `le scelte rapide sono 30 minuti, un'ora, due e tre`() {
        assertEquals(listOf(30, 60, 120, 180), DurataSessione.SCELTE)
        assertTrue(DurataSessione.SCELTE.all { DurataSessione.valida(it) })
    }

    @Test
    fun `una durata va da 1 minuto a 24 ore`() {
        assertFalse(DurataSessione.valida(0))
        assertTrue(DurataSessione.valida(1))
        assertTrue(DurataSessione.valida(1440))
        assertFalse(DurataSessione.valida(1441))
        assertFalse(DurataSessione.valida(null))
    }

    @Test
    fun `ore e minuti scritti a mano`() {
        assertEquals(120, DurataSessione.daOreMinuti("2", ""))
        assertEquals(45, DurataSessione.daOreMinuti("", "45"))
        assertEquals(95, DurataSessione.daOreMinuti(" 1 ", "35"))
        assertEquals(1440, DurataSessione.daOreMinuti("24", "0"))
        assertNull(DurataSessione.daOreMinuti("24", "1"))
        assertNull(DurataSessione.daOreMinuti("0", "0"))
        assertNull(DurataSessione.daOreMinuti("", ""))
        assertNull(DurataSessione.daOreMinuti("1", "60"))
        assertNull(DurataSessione.daOreMinuti("due", ""))
        assertNull(DurataSessione.daOreMinuti("-1", "30"))
    }

    @Test
    fun `oltre le 24 ore lo si dice sul campo`() {
        assertTrue(DurataSessione.oltreIlMassimo("25", ""))
        assertTrue(DurataSessione.oltreIlMassimo("24", "30"))
        assertFalse(DurataSessione.oltreIlMassimo("24", "0"))
        assertFalse(DurataSessione.oltreIlMassimo("x", "1"))
    }

    // --- quando finisce ---------------------------------------------------------------

    @Test
    fun `fino alle 17_00, o fino a domani`() {
        val adesso = ms("2026-10-01T15:00:00")
        assertEquals(OraFine("17:00", domani = false), TestoSessioni.quandoFinisce(ms("2026-10-01T17:00:00"), adesso, roma))
        assertEquals(OraFine("01:30", domani = true), TestoSessioni.quandoFinisce(ms("2026-10-02T01:30:00"), adesso, roma))
    }

    @Test
    fun `i minuti che mancano si arrotondano in su`() {
        val adesso = ms("2026-10-01T15:00:00")
        assertEquals(60L, TestoSessioni.minutiMancanti(adesso + 60 * 60_000L, adesso))
        assertEquals(1L, TestoSessioni.minutiMancanti(adesso + 10_000L, adesso))
        assertEquals(2L, TestoSessioni.minutiMancanti(adesso + 61_000L, adesso))
        assertEquals(0L, TestoSessioni.minutiMancanti(adesso - 1, adesso))
    }

    // --- i nomi delle app -------------------------------------------------------------

    @Test
    fun `i nomi delle app della sessione, mai i pacchetti`() {
        val nomi = TestoSessioni.nomiApp(
            app = listOf("eu.spaggiari.classevivafamiglia", "com.google.android.calculator", "gruppo:apk", "com.sparita.uno", "com.sparita.due"),
            nomi = mapOf("eu.spaggiari.classevivafamiglia" to "ClasseViva", "com.sparita.uno" to "com.sparita.uno"),
            risolvi = { if (it == "com.google.android.calculator") "Calcolatrice" else it },
            gruppoApk = "App installate da APK",
            sconosciuta = "un'app non installata qui",
        )
        assertEquals(listOf("ClasseViva", "Calcolatrice", "App installate da APK", "un'app non installata qui"), nomi)
    }

    @Test
    fun `un elenco lungo dice quante altre`() {
        val altre = { n: Int -> if (n == 1) "un'altra" else "altre $n" }
        assertEquals("A, B", TestoSessioni.elenco(listOf("A", "B"), massimo = 4, altri = altre))
        assertEquals("A, B e un'altra", TestoSessioni.elenco(listOf("A", "B", "C"), massimo = 2, altri = altre))
        assertEquals("A, B e altre 3", TestoSessioni.elenco(listOf("A", "B", "C", "D", "E"), massimo = 2, altri = altre))
    }

    // --- la risposta del genitore -----------------------------------------------------

    private val parole = ParoleRispostaSessione(
        approvata = "Il genitore ha approvato la sessione «%1\$s»",
        rifiutata = "Il genitore non ha approvato la sessione «%1\$s»",
        cambioApprovato = "Il genitore ha approvato il cambio alla sessione «%1\$s»",
        cambioRifiutato = "Il genitore non ha approvato il cambio alla sessione «%1\$s»",
        approvataTesto = "Puoi iniziarla quando vuoi, dalla scheda Sessioni.",
        rifiutataTesto = "Puoi cambiarla e mandarla di nuovo.",
        cambioApprovatoTesto = "Vale dalla prossima volta che la inizi.",
        cambioRifiutatoTesto = "Resta la sessione di prima.",
        genitoreDice = "Il genitore dice: %1\$s",
    )

    private fun payload(esito: String, cambio: Boolean? = null, nome: String? = "Studio") = buildJsonObject {
        put("sessione_id", 3)
        nome?.let { put("nome", it) }
        put("esito", esito)
        cambio?.let { put("cambio", it) }
    }

    @Test
    fun `il payload di sessione_risposta`() {
        assertEquals(RispostaSessione(3, "Studio", approvata = true, cambio = false), TestoSessioni.risposta(payload("approva")))
        assertEquals(RispostaSessione(3, "Studio", approvata = false, cambio = true), TestoSessioni.risposta(payload("rifiuta", cambio = true)))
        val cambioComeTesto = buildJsonObject {
            put("esito", "approva")
            put("cambio", JsonPrimitive("true"))
        }
        assertEquals(true, TestoSessioni.risposta(cambioComeTesto)?.cambio)
        assertNull(TestoSessioni.risposta(payload("accetta")))
        assertNull(TestoSessioni.risposta(buildJsonObject { }))
    }

    @Test
    fun `approvata e non approvata, la sessione e il suo cambio`() {
        fun avviso(esito: String, cambio: Boolean, motivazione: String? = null) =
            TestoSessioni.avvisoRisposta(TestoSessioni.risposta(payload(esito, cambio))!!, "Studio", motivazione, "dal server", parole)

        assertEquals(
            "Il genitore ha approvato la sessione «Studio»" to "Puoi iniziarla quando vuoi, dalla scheda Sessioni.",
            avviso("approva", cambio = false),
        )
        assertEquals(
            "Il genitore non ha approvato la sessione «Studio»" to "Puoi cambiarla e mandarla di nuovo.\nIl genitore dice: prima i compiti",
            avviso("rifiuta", cambio = false, motivazione = " prima i compiti "),
        )
        assertEquals(
            "Il genitore ha approvato il cambio alla sessione «Studio»" to "Vale dalla prossima volta che la inizi.",
            avviso("approva", cambio = true, motivazione = "vecchia motivazione"),
        )
        assertEquals(
            "Il genitore non ha approvato il cambio alla sessione «Studio»" to "Resta la sessione di prima.",
            avviso("rifiuta", cambio = true),
        )
    }

    @Test
    fun `senza il nome della sessione il titolo e' quello del server`() {
        val risposta = TestoSessioni.risposta(payload("approva", nome = null))!!
        val (titolo, _) = TestoSessioni.avvisoRisposta(risposta, nome = null, motivazione = null, messaggio = "Il genitore ha approvato", parole = parole)
        assertEquals("Il genitore ha approvato", titolo)
    }
}
