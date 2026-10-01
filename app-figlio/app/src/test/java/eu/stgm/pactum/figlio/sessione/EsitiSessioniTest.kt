package eu.stgm.pactum.figlio.sessione

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.11) Le risposte del server sulle Sessioni come le deve capire il
 * ragazzo. Un server di prima della v3.5 va aggiornato: non è mai "un errore".
 */
class EsitiSessioniTest {

    private val notFound = """{"detail":"Not Found"}"""
    private val nonTrovata = """{"detail":"sessione non trovata"}"""

    private fun conErrore(codice: String) = """{"detail":{"errore":"$codice"}}"""

    @Test
    fun `l'elenco con 404 o 405 vuol dire server da aggiornare`() {
        assertTrue(EsitiSessioni.elencoDaServerVecchio(404))
        assertTrue(EsitiSessioni.elencoDaServerVecchio(405))
        assertFalse(EsitiSessioni.elencoDaServerVecchio(0))
        assertFalse(EsitiSessioni.elencoDaServerVecchio(500))
        assertFalse(EsitiSessioni.elencoDaServerVecchio(401))
    }

    @Test
    fun `una pagina che non c'e' e' un server vecchio, una sessione che non c'e' no`() {
        assertTrue(EsitiSessioni.serverVecchio(405, null))
        assertTrue(EsitiSessioni.serverVecchio(404, notFound))
        assertTrue(EsitiSessioni.serverVecchio(404, null))
        assertTrue(EsitiSessioni.serverVecchio(404, "<html><body>Not Found</body></html>"))
        assertFalse(EsitiSessioni.serverVecchio(404, nonTrovata))
        assertFalse(EsitiSessioni.serverVecchio(404, conErrore("sessione_non_trovata")))
        assertFalse(EsitiSessioni.serverVecchio(409, notFound))
    }

    @Test
    fun `creare su un server vecchio - serve aggiornare il server`() {
        assertEquals(EsitoSessione.ServerDaAggiornare, EsitiSessioni.sessione(false, 404, notFound))
        assertEquals(EsitoSessione.ServerDaAggiornare, EsitiSessioni.sessione(false, 405, """{"detail":"Method Not Allowed"}"""))
    }

    @Test
    fun `gli errori della sessione nelle due forme`() {
        assertEquals(EsitoSessione.NomeGiaUsato, EsitiSessioni.sessione(false, 409, conErrore("nome_gia_usato")))
        assertEquals(EsitoSessione.NomeGiaUsato, EsitiSessioni.sessione(false, 409, """{"errore":"nome_gia_usato"}"""))
        assertEquals(EsitoSessione.InCorso, EsitiSessioni.sessione(false, 409, conErrore("sessione_in_corso")))
        // Al massimo 20 sessioni per telefono.
        assertEquals(EsitoSessione.TroppeSessioni, EsitiSessioni.sessione(false, 409, conErrore("troppe_sessioni")))
        assertEquals(EsitoSessione.TroppeSessioni, EsitiSessioni.sessione(false, 409, """{"errore":"troppe_sessioni"}"""))
        assertEquals(EsitoSessione.Scollegato, EsitiSessioni.sessione(false, 409, conErrore("dispositivo_revocato")))
        assertEquals(EsitoSessione.NonTrovata, EsitiSessioni.sessione(false, 404, nonTrovata))
        assertEquals(EsitoSessione.ValoriNonValidi, EsitiSessioni.sessione(false, 422, """{"detail":[{"msg":"troppo lungo"}]}"""))
        assertEquals(EsitoSessione.Scollegato, EsitiSessioni.sessione(false, 401, null))
        assertEquals(EsitoSessione.SenzaRete, EsitiSessioni.sessione(false, 0, null))
        assertEquals(EsitoSessione.Errore, EsitiSessioni.sessione(false, 500, "Internal Server Error"))
    }

    @Test
    fun `creata - con la sessione che torna dal server, se si legge`() {
        val esito = EsitiSessioni.sessione(true, 201, """{"id":3,"nome":"Studio","app":["a"],"stato":"in_attesa"}""")
        assertEquals(3L, (esito as EsitoSessione.Fatta).sessione?.id)
        assertEquals(EsitoSessione.Fatta(null), EsitiSessioni.sessione(true, 204, null))
    }

    @Test
    fun `avviata - la sessione svolta diventa la copia del telefono`() {
        val corpo = """{"id":12,"sessione_id":3,"nome":"Studio","app":["a"],"inizio_ts":"2026-10-01T14:00:00+00:00",
            "durata_minuti":90,"fine_prevista_ts":"2026-10-01T15:30:00+00:00","fine_ts":null,"chiusura":null,"in_corso":true}"""
        val esito = EsitiSessioni.avvio(true, 201, corpo) as EsitoAvvio.Avviata
        assertEquals(12L, esito.svolta?.id)
        assertEquals(3L, esito.svolta?.sessioneId)
        // Iniziata ma illeggibile: iniziata lo stesso (la si ritrova dal patto).
        assertEquals(EsitoAvvio.Avviata(null), EsitiSessioni.avvio(true, 201, "boh"))
    }

    @Test
    fun `i no di Inizia`() {
        assertEquals(EsitoAvvio.NonApprovata, EsitiSessioni.avvio(false, 409, conErrore("sessione_non_approvata")))
        assertEquals(EsitoAvvio.GiaInCorso(), EsitiSessioni.avvio(false, 409, conErrore("sessione_gia_in_corso")))
        assertEquals(EsitoAvvio.Scollegato, EsitiSessioni.avvio(false, 409, conErrore("dispositivo_revocato")))
        assertEquals(EsitoAvvio.DurataNonValida, EsitiSessioni.avvio(false, 422, null))
        assertEquals(EsitoAvvio.ServerDaAggiornare, EsitiSessioni.avvio(false, 404, notFound))
        assertEquals(EsitoAvvio.ServerDaAggiornare, EsitiSessioni.avvio(false, 405, null))
        assertEquals(EsitoAvvio.NonTrovata, EsitiSessioni.avvio(false, 404, nonTrovata))
        assertEquals(EsitoAvvio.Scollegato, EsitiSessioni.avvio(false, 401, null))
        // Senza rete la sessione NON è iniziata: lo si dice, non "errore".
        assertEquals(EsitoAvvio.SenzaRete, EsitiSessioni.avvio(false, 0, null))
        assertEquals(EsitoAvvio.Errore, EsitiSessioni.avvio(false, 500, null))
        assertEquals(EsitoAvvio.Errore, EsitiSessioni.avvio(false, 503, "Service Unavailable"))
    }

    @Test
    fun `Inizia partito e risposta persa - non si sa se e' partita, mai senza rete`() {
        // La richiesta è partita, la risposta no (rete caduta a metà, server lento).
        assertEquals(EsitoAvvio.Incerto, EsitiSessioni.avvio(false, 0, null, incerta = true))
        // I gateway in ritardo: il server forse l'ha già fatta partire.
        assertEquals(EsitoAvvio.Incerto, EsitiSessioni.avvio(false, 502, null))
        assertEquals(EsitoAvvio.Incerto, EsitiSessioni.avvio(false, 504, "<html>Gateway Timeout</html>"))
        assertEquals(EsitoAvvio.Incerto, EsitiSessioni.avvio(false, 524, null))
        // Un no chiaro del server resta un no, anche se la risposta era "incerta".
        assertEquals(EsitoAvvio.NonApprovata, EsitiSessioni.avvio(false, 409, conErrore("sessione_non_approvata"), incerta = true))
    }

    @Test
    fun `Termina - consegnata, o niente in corso e si lascia andare`() {
        val chiusa = EsitiSessioni.termina(true, 200, """{"id":12,"chiusura":"terminata","fine_ts":"2026-10-01T14:20:00Z"}""")
        assertEquals(12L, (chiusa as EsitoTermina.Terminata).svolta?.id)
        // 404: non c'è niente in corso, o quella chiusa (svolta_id) è già finita: si lascia andare.
        assertEquals(EsitoTermina.NienteInCorso, EsitiSessioni.termina(false, 404, null))
        assertEquals(EsitoTermina.NienteInCorso, EsitiSessioni.termina(false, 404, """{"detail":"nessuna sessione in corso"}"""))
        assertEquals(EsitoTermina.NienteInCorso, EsitiSessioni.termina(false, 404, nonTrovata))
        assertEquals(EsitoTermina.NienteInCorso, EsitiSessioni.termina(false, 404, notFound))
        // Un 409 che dice "non è in corso", in qualunque forma ragionevole.
        assertEquals(EsitoTermina.NienteInCorso, EsitiSessioni.termina(false, 409, conErrore("sessione_non_in_corso")))
        assertEquals(EsitoTermina.NienteInCorso, EsitiSessioni.termina(false, 409, """{"errore":"nessuna_sessione_in_corso"}"""))
    }

    @Test
    fun `prima di Inizia - un no del server sulle chiusure in attesa non e' niente rete`() {
        assertEquals(null, ConsegnaSessioni.esitoPrimaDellAvvio(ConsegnaSessioni.Consegna.FATTA))
        assertEquals(EsitoAvvio.SenzaRete, ConsegnaSessioni.esitoPrimaDellAvvio(ConsegnaSessioni.Consegna.SENZA_RETE))
        assertEquals(EsitoAvvio.Errore, ConsegnaSessioni.esitoPrimaDellAvvio(ConsegnaSessioni.Consegna.ERRORE_SERVER))
        assertEquals(EsitoAvvio.Scollegato, ConsegnaSessioni.esitoPrimaDellAvvio(ConsegnaSessioni.Consegna.SCOLLEGATO))
    }

    @Test
    fun `Termina - tutto il resto si tiene e si riprova`() {
        assertEquals(EsitoTermina.Scollegato, EsitiSessioni.termina(false, 401, null))
        assertEquals(EsitoTermina.SenzaRete, EsitiSessioni.termina(false, 0, null))
        // Un errore del server (o di un proxy davanti) non è "già finita".
        for (codice in listOf(400, 403, 405, 408, 422, 429, 500, 502, 503, 504)) {
            assertEquals("codice $codice", EsitoTermina.ErroreServer, EsitiSessioni.termina(false, codice, null))
        }
        // Un 409 che non dice "non in corso" (dispositivo revocato, altro): si riprova.
        assertEquals(EsitoTermina.ErroreServer, EsitiSessioni.termina(false, 409, conErrore("dispositivo_revocato")))
        assertEquals(EsitoTermina.ErroreServer, EsitiSessioni.termina(false, 409, null))
    }
}
