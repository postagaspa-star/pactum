package eu.stgm.pactum.figlio.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'abbinamento col codice di 6 cifre (contratto v3): cosa vuol dire ogni
 * risposta del server, e quando un nuovo collegamento continua la storia dello
 * stesso dispositivo invece di cominciarne un'altra.
 */
class AbbinamentoTest {

    // --- Il campo del codice ----------------------------------------------

    @Test
    fun `nel campo restano solo le cifre, al massimo sei`() {
        assertEquals("483920", Abbinamento.soloCifre("483 920"))
        assertEquals("483920", Abbinamento.soloCifre("483-920"))
        assertEquals("123456", Abbinamento.soloCifre("12345678"))
        assertEquals("12", Abbinamento.soloCifre("1a2b"))
        assertEquals("", Abbinamento.soloCifre("codice"))
    }

    @Test
    fun `si manda solo un codice di sei cifre`() {
        assertTrue(Abbinamento.codiceCompleto("483920"))
        assertFalse(Abbinamento.codiceCompleto("48392"))
        assertFalse(Abbinamento.codiceCompleto("4839201"))
        assertFalse(Abbinamento.codiceCompleto("48392a"))
        assertFalse(Abbinamento.codiceCompleto(""))
    }

    // --- Le risposte del server ---------------------------------------------

    @Test
    fun `collegato, con token, dispositivo e figlio`() {
        val corpo = """
            { "token": "abc123", "dispositivo": { "id": 2, "nome": "Telefono di Andrea", "tipo": "telefono" },
              "figlio": { "id": 1, "nome": "Andrea" }, "altro": true }
        """.trimIndent()
        assertEquals(
            EsitoAbbinamento.Collegato(
                token = "abc123",
                dispositivo = Dispositivo(id = 2, nome = "Telefono di Andrea", tipo = "telefono"),
                figlio = Figlio(id = 1, nome = "Andrea"),
            ),
            Abbinamento.esito(ok = true, codiceHttp = 200, corpo = corpo),
        )
    }

    @Test
    fun `un 200 senza token non collega niente`() {
        assertEquals(EsitoAbbinamento.Errore, Abbinamento.esito(true, 200, """{ "token": "" }"""))
        assertEquals(EsitoAbbinamento.Errore, Abbinamento.esito(true, 200, "non json"))
        assertEquals(EsitoAbbinamento.Errore, Abbinamento.esito(true, 200, null))
    }

    @Test
    fun `codice non valido, nella forma del contratto e in quella di FastAPI`() {
        assertEquals(
            EsitoAbbinamento.CodiceNonValido,
            Abbinamento.esito(false, 409, """{ "errore": "codice_non_valido" }"""),
        )
        assertEquals(
            EsitoAbbinamento.CodiceNonValido,
            Abbinamento.esito(false, 409, """{ "detail": { "errore": "codice_non_valido" } }"""),
        )
        // Corpo illeggibile: decide il codice HTTP.
        assertEquals(EsitoAbbinamento.CodiceNonValido, Abbinamento.esito(false, 409, "<html>"))
    }

    @Test
    fun `troppi tentativi, con l'attesa quando il server la dice`() {
        assertEquals(
            EsitoAbbinamento.TroppiTentativi(riprovaTraSecondi = 540),
            Abbinamento.esito(false, 429, """{ "errore": "troppi_tentativi", "riprova_tra_secondi": 540 }"""),
        )
        assertEquals(
            EsitoAbbinamento.TroppiTentativi(riprovaTraSecondi = 60),
            Abbinamento.esito(
                false, 429, """{ "detail": { "errore": "troppi_tentativi", "riprova_tra_secondi": 60 } }""",
            ),
        )
        assertEquals(EsitoAbbinamento.TroppiTentativi(riprovaTraSecondi = null), Abbinamento.esito(false, 429, null))
        // Un'attesa non positiva non è un'attesa.
        assertEquals(
            EsitoAbbinamento.TroppiTentativi(riprovaTraSecondi = null),
            Abbinamento.esito(false, 429, """{ "errore": "troppi_tentativi", "riprova_tra_secondi": 0 }"""),
        )
    }

    @Test
    fun `l'errore scritto dal server vince sul codice HTTP`() {
        assertEquals(
            EsitoAbbinamento.TroppiTentativi(null),
            Abbinamento.esito(false, 409, """{ "errore": "troppi_tentativi" }"""),
        )
    }

    @Test
    fun `niente rete, server senza codici, tutto il resto`() {
        assertEquals(EsitoAbbinamento.SenzaRete, Abbinamento.esito(false, 0, null))
        // Nessuna pagina /api/abbina: non è il codice a essere sbagliato.
        assertEquals(EsitoAbbinamento.ServerSenzaCodici, Abbinamento.esito(false, 404, """{ "detail": "Not Found" }"""))
        assertEquals(EsitoAbbinamento.ServerSenzaCodici, Abbinamento.esito(false, 405, null))
        assertEquals(EsitoAbbinamento.Errore, Abbinamento.esito(false, 500, null))
        assertEquals(EsitoAbbinamento.Errore, Abbinamento.esito(false, 422, """{ "detail": [ { "msg": "x" } ] }"""))
    }

    // --- Stesso dispositivo, stesso figlio ----------------------------------

    private val server = "https://pactum.taildbae63.ts.net"

    @Test
    fun `codice nuovo per lo stesso dispositivo, la storia continua`() {
        assertTrue(Abbinamento.stessoDispositivo(true, server, server, dispositivoPrima = 2, dispositivoDopo = 2))
        assertTrue(Abbinamento.stessoDispositivo(true, "$server/", server, 2, 2))
    }

    @Test
    fun `il vecchio codice lungo e' il dispositivo 1`() {
        assertTrue(Abbinamento.stessoDispositivo(true, server, server, dispositivoPrima = null, dispositivoDopo = 1))
        assertFalse(Abbinamento.stessoDispositivo(true, server, server, dispositivoPrima = null, dispositivoDopo = 3))
    }

    @Test
    fun `un altro dispositivo, un altro server o un telefono mai collegato ricominciano`() {
        assertFalse(Abbinamento.stessoDispositivo(true, server, server, 1, 3))
        assertFalse(Abbinamento.stessoDispositivo(true, "https://altro.it", server, 2, 2))
        assertFalse(Abbinamento.stessoDispositivo(false, "", server, null, 1))
        assertFalse(Abbinamento.stessoDispositivo(true, server, server, 2, null))
    }

    @Test
    fun `l'impronta del collegamento cambia col token e non lo contiene`() {
        val prima = ConfigurazionePostino(server, "token-vecchio-lungo")
        val dopo = ConfigurazionePostino(server, "token-nuovo")
        assertEquals(prima.impronta, ConfigurazionePostino(server, "token-vecchio-lungo").impronta)
        assertFalse(prima.impronta == dopo.impronta)
        assertFalse(prima.impronta.contains("token"))
        assertEquals(16, prima.impronta.length)
    }

    @Test
    fun `stesso figlio con un altro dispositivo, la serie resta sua`() {
        assertFalse(Abbinamento.stessoDispositivo(true, server, server, 1, 3))
        assertTrue(Abbinamento.stessoFiglio(true, server, server, figlioPrima = 1, figlioDopo = 1))
        assertTrue(Abbinamento.stessoFiglio(true, server, server, figlioPrima = null, figlioDopo = 1))
        assertFalse(Abbinamento.stessoFiglio(true, server, server, figlioPrima = 1, figlioDopo = 2))
    }
}
