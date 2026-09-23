package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `il codice di un computer non e' un codice non valido`() {
        assertEquals(
            EsitoAbbinamento.TipoNonCorrispondente(tipoAtteso = "computer"),
            Abbinamento.esito(false, 409, """{ "errore": "tipo_non_corrispondente", "tipo_atteso": "computer" }"""),
        )
        assertEquals(
            EsitoAbbinamento.TipoNonCorrispondente(tipoAtteso = "computer"),
            Abbinamento.esito(
                false, 409, """{ "detail": { "errore": "tipo_non_corrispondente", "tipo_atteso": " Computer " } }""",
            ),
        )
        // Senza il tipo atteso resta un codice di un altro tipo, non "non valido".
        assertEquals(
            EsitoAbbinamento.TipoNonCorrispondente(tipoAtteso = null),
            Abbinamento.esito(false, 409, """{ "detail": { "errore": "tipo_non_corrispondente" } }"""),
        )
        assertEquals(
            EsitoAbbinamento.TipoNonCorrispondente(tipoAtteso = null),
            Abbinamento.esito(false, 409, """{ "errore": "tipo_non_corrispondente", "tipo_atteso": "" }"""),
        )
    }

    @Test
    fun `il telefono dice sempre che e' un telefono, anche senza scrivere i valori di ripiego`() {
        val richiesta = Abbinamento.richiesta("483920", "0.8.0")
        assertEquals(AbbinaIn(codice = "483920", tipo = "telefono", versioneApp = "0.8.0"), richiesta)
        // Come il client: i valori uguali al ripiego non si scrivono. Il tipo deve partire lo stesso.
        val json = Json { encodeDefaults = false }
        val corpo = json.parseToJsonElement(json.encodeToString(AbbinaIn.serializer(), richiesta)).jsonObject
        assertEquals("telefono", corpo["tipo"]?.jsonPrimitive?.content)
        assertEquals("483920", corpo["codice"]?.jsonPrimitive?.content)
        assertEquals("0.8.0", corpo["versione_app"]?.jsonPrimitive?.content)
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

    // --- Gli sforamenti in coda -----------------------------------------------

    private val altroServer = "http://192.168.1.50:8100"

    @Test
    fun `stesso server anche con la barra in fondo, un altro indirizzo no`() {
        assertTrue(Abbinamento.stessoServer(server, "$server/"))
        assertTrue(Abbinamento.stessoServer(" $server ", server))
        assertFalse(Abbinamento.stessoServer(server, altroServer))
    }

    @Test
    fun `col codice di sei cifre la coda va via se cambia il dispositivo o il server`() {
        assertTrue(Abbinamento.scartaSforamentiInCoda(true, server, server, stessoDispositivo = false))
        assertTrue(Abbinamento.scartaSforamentiInCoda(true, server, altroServer, stessoDispositivo = false))
        assertFalse(Abbinamento.scartaSforamentiInCoda(true, server, server, stessoDispositivo = true))
    }

    @Test
    fun `col codice lungo la coda va via solo se cambia il server`() {
        // Un altro server: i numeri delle regole sono quelli del server vecchio.
        assertTrue(Abbinamento.scartaSforamentiInCoda(true, server, altroServer, stessoDispositivo = null))
        // Stesso server: non si sa di che dispositivo è, niente buttato per un sospetto.
        assertFalse(Abbinamento.scartaSforamentiInCoda(true, server, "$server/", stessoDispositivo = null))
    }

    @Test
    fun `un telefono mai collegato non ha sforamenti da buttare`() {
        assertFalse(Abbinamento.scartaSforamentiInCoda(false, "", server, stessoDispositivo = false))
        assertFalse(Abbinamento.scartaSforamentiInCoda(false, "", server, stessoDispositivo = null))
    }

    // --- Collegato a un dispositivo diverso da prima --------------------------

    private val telefono = Dispositivo(id = 1, nome = "Telefono", tipo = "telefono")
    private val nuovo = Dispositivo(id = 3, nome = "Telefono di Andrea", tipo = "telefono")

    @Test
    fun `aggiungi dispositivo al posto di nuovo codice, il telefono lo dice`() {
        assertEquals(
            CambioDispositivo(nomeNuovo = "Telefono di Andrea", nomePrima = "Telefono", primaScollegato = false),
            Abbinamento.cambioDispositivo(true, stessoServer = true, prima = telefono, dopo = nuovo),
        )
    }

    @Test
    fun `primo collegamento o stesso dispositivo, niente avviso`() {
        assertNull(Abbinamento.cambioDispositivo(false, stessoServer = false, prima = null, dopo = nuovo))
        assertNull(Abbinamento.cambioDispositivo(true, stessoServer = true, prima = nuovo, dopo = nuovo))
        // Il server non dice quale dispositivo: non si sa, non si dice.
        assertNull(Abbinamento.cambioDispositivo(true, stessoServer = true, prima = telefono, dopo = null))
        assertNull(Abbinamento.cambioDispositivo(true, true, telefono, Dispositivo(id = 0, nome = "X")))
    }

    @Test
    fun `lo stesso dispositivo con un altro indirizzo non e' un dispositivo nuovo`() {
        assertNull(Abbinamento.cambioDispositivo(true, stessoServer = false, prima = telefono, dopo = telefono))
    }

    @Test
    fun `mai saputo chi era col codice lungo, era il dispositivo 1`() {
        assertNull(Abbinamento.cambioDispositivo(true, stessoServer = true, prima = null, dopo = telefono))
        // Il nome di prima si prende dal patto appena letto.
        assertEquals(
            CambioDispositivo("Telefono di Andrea", "Telefono", primaScollegato = false),
            Abbinamento.cambioDispositivo(true, true, prima = null, dopo = nuovo, dispositiviDopo = listOf(telefono, nuovo)),
        )
        // Senza patto non si sa come si chiamava.
        assertEquals(
            CambioDispositivo("Telefono di Andrea", "", primaScollegato = false),
            Abbinamento.cambioDispositivo(true, true, prima = null, dopo = nuovo),
        )
    }

    @Test
    fun `il nome di adesso e lo scollegamento vengono dal patto appena letto`() {
        val rinominato = telefono.copy(nome = "Vecchio telefono")
        assertEquals(
            CambioDispositivo("Telefono di Andrea", "Vecchio telefono", primaScollegato = false),
            Abbinamento.cambioDispositivo(true, true, telefono, nuovo, listOf(rinominato, nuovo)),
        )
        // Scollegato dal genitore: non riceve più codici, il consiglio non si darà.
        assertEquals(
            CambioDispositivo("Telefono di Andrea", "Telefono", primaScollegato = true),
            Abbinamento.cambioDispositivo(true, true, telefono, nuovo, listOf(telefono.copy(revocato = true), nuovo)),
        )
    }

    @Test
    fun `su un altro server lo stesso numero e' un altro dispositivo`() {
        // Il dispositivo 1 del server nuovo non è il "Telefono" di prima: non se ne prende niente.
        val altroUno = Dispositivo(id = 1, nome = "Tablet", revocato = true)
        assertEquals(
            CambioDispositivo("Telefono di Andrea", "Telefono", primaScollegato = false),
            Abbinamento.cambioDispositivo(true, stessoServer = false, prima = telefono, dopo = nuovo, dispositiviDopo = listOf(altroUno)),
        )
    }
}
