package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.AutoriProposta
import eu.stgm.pactum.figlio.dati.CambioRegola
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.PropostaIn
import eu.stgm.pactum.figlio.dati.StatiProposta
import eu.stgm.pactum.figlio.rete.PostinoClient.RispostaHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.10) Il percorso del blocco dei 4 giorni fino alla proposta, come lo fa
 * RegoleViewModel: il 409 `lock_attivo` porta con sé il cambio fermato, "Chiedi
 * al genitore" manda ESATTAMENTE quello, e la risposta diventa la frase giusta.
 * La rete è finta: si guarda solo cosa parte e cosa si dice.
 */
class RegoleViewModelTest {

    private fun limite(minuti: Int) = buildJsonObject {
        put("app_o_categoria", "com.zhiliaoapp.musically")
        put("minuti_al_giorno", minuti)
    }

    private val lock = RispostaHttp(
        ok = false,
        codice = 409,
        corpo = """{ "detail": { "errore": "lock_attivo", "secondi_rimanenti": 273600, "sblocco_ts": "2026-10-04T09:00:00+00:00" } }""",
    )

    private val parole = ParoleEsitoProposta(
        mandata = "Proposta mandata: %1\$s. Se il genitore accetta, vale subito.",
        mandataEliminazione = "Proposta mandata: eliminare la regola. Se il genitore accetta, vale subito.",
        mandataSenzaConfronto = "Proposta mandata. Se il genitore accetta, vale subito.",
        giaPendente = "C'è già una proposta in attesa su questa regola.",
        giaTua = "Hai già una proposta in attesa su questa regola.",
        regolaNonValida = "Questa regola non è più attiva: non si può proporre di cambiarla.",
        dispositivoRevocato = "Dispositivo scollegato.",
        valoriNonValidi = "Qualche valore non va bene: controlla i campi e riprova.",
        serverDaAggiornare = "Per mandare proposte serve aggiornare il server di Pactum.",
        scollegato = "Non più collegato.",
        senzaRete = "Non riesco a raggiungere il server: controlla la connessione e riprova.",
        errore = "Non sono riuscito a mandare la proposta: riprova.",
    )

    /** Un postino finto: ricorda cosa gli si manda e risponde [risposta]. */
    private class PostinoFinto(val risposta: RispostaHttp) {
        val mandate = mutableListOf<PropostaIn>()
        suspend fun manda(corpo: PropostaIn): RispostaHttp {
            mandate += corpo
            return risposta
        }
    }

    private fun propostaCreata(confronto: String, direzione: String) = RispostaHttp(
        ok = true,
        codice = 200,
        corpo = """
            { "id": 21, "regola_id": 7, "parametri_proposti": {}, "motivazione": null, "confronto": "$confronto",
              "direzione": "$direzione", "stato": "pendente", "usata": false,
              "ts_server": "2026-10-01T09:00:00+00:00", "risposta": null, "autore": "figlio" }
        """.trimIndent(),
    )

    @Test
    fun `il blocco di una modifica porta con se' proprio quella modifica`() {
        val cambio = CambioRegola.Modifica(7, limite(90))
        val evento = eventoCambio(lock, RegoleViewModel.Evento.Salvata, cambio)
        assertEquals(RegoleViewModel.Evento.LockAttivo(273600, cambio), evento)
        assertEquals(false, (evento as RegoleViewModel.Evento.LockAttivo).perEliminazione)
    }

    @Test
    fun `il blocco di un'eliminazione si riconosce`() {
        val evento = eventoCambio(lock, RegoleViewModel.Evento.Eliminata, CambioRegola.Eliminazione(7))
        assertTrue((evento as RegoleViewModel.Evento.LockAttivo).perEliminazione)
    }

    @Test
    fun `chiedi al genitore dopo il blocco manda esattamente il cambio fermato`() = runBlocking {
        val cambio = CambioRegola.Modifica(7, limite(90))
        val bloccato = eventoCambio(lock, RegoleViewModel.Evento.Salvata, cambio) as RegoleViewModel.Evento.LockAttivo
        val postino = PostinoFinto(propostaCreata("+30 min al giorno rispetto ad ora", "allenta"))

        val esito = mandaCambio(bloccato.cambio, "c'è la verifica di storia", postino::manda)

        assertEquals(
            listOf(PropostaIn(regolaId = 7, parametriProposti = limite(90), motivazione = "c'è la verifica di storia")),
            postino.mandate,
        )
        assertEquals(EsitoProposta.Mandata("+30 min al giorno rispetto ad ora", eliminazione = false), esito)
        assertEquals(
            "Proposta mandata: +30 min al giorno rispetto ad ora. Se il genitore accetta, vale subito.",
            TestoProposta.esito(esito, parole),
        )
    }

    @Test
    fun `chiedi al genitore dopo il blocco di un'eliminazione manda il marcatore`() = runBlocking {
        val bloccato = eventoCambio(lock, RegoleViewModel.Evento.Eliminata, CambioRegola.Eliminazione(7))
            as RegoleViewModel.Evento.LockAttivo
        val postino = PostinoFinto(propostaCreata("propone di eliminare la regola", "elimina"))

        val esito = mandaCambio(bloccato.cambio, null, postino::manda)

        assertEquals(
            listOf(PropostaIn(regolaId = 7, parametriProposti = buildJsonObject { put("azione", "elimina") })),
            postino.mandate,
        )
        assertEquals(
            "Proposta mandata: eliminare la regola. Se il genitore accetta, vale subito.",
            TestoProposta.esito(esito, parole),
        )
    }

    @Test
    fun `con un server vecchio si dice di aggiornarlo, mai un errore generico`() = runBlocking {
        val bloccato = eventoCambio(lock, RegoleViewModel.Evento.Salvata, CambioRegola.Modifica(7, limite(90)))
            as RegoleViewModel.Evento.LockAttivo
        val postino = PostinoFinto(RispostaHttp(ok = false, codice = 403, corpo = """{ "detail": "ruolo non autorizzato" }"""))

        val esito = mandaCambio(bloccato.cambio, null, postino::manda)

        assertEquals(EsitoProposta.ServerDaAggiornare, esito)
        assertEquals("Per mandare proposte serve aggiornare il server di Pactum.", TestoProposta.esito(esito, parole))
    }

    @Test
    fun `una proposta gia' in attesa sulla regola lo dice`() = runBlocking {
        val postino = PostinoFinto(
            RispostaHttp(ok = false, codice = 409, corpo = """{ "detail": { "errore": "proposta_gia_pendente" } }"""),
        )
        // In attesa c'è quella del genitore (fra le inviate del figlio non c'è).
        val esito = mandaCambio(CambioRegola.Modifica(7, limite(90)), null, postino::manda, inviate = { emptyList() })
        assertEquals(EsitoProposta.GiaPendente, esito)
        assertEquals("C'è già una proposta in attesa su questa regola.", TestoProposta.esito(esito, parole))
    }

    @Test
    fun `se quella in attesa e' la tua, dopo una risposta persa, lo dice cosi'`() = runBlocking {
        val postino = PostinoFinto(
            RispostaHttp(ok = false, codice = 409, corpo = """{ "detail": { "errore": "proposta_gia_pendente" } }"""),
        )
        val tua = Proposta(id = 21, regolaId = 7, stato = StatiProposta.PENDENTE, autore = AutoriProposta.FIGLIO)
        var rilette = 0
        val esito = mandaCambio(CambioRegola.Modifica(7, limite(90)), null, postino::manda) {
            rilette++
            listOf(tua)
        }
        assertEquals(EsitoProposta.GiaTua, esito)
        assertEquals(1, rilette)
        assertEquals("Hai già una proposta in attesa su questa regola.", TestoProposta.esito(esito, parole))
    }

    @Test
    fun `le inviate si rileggono solo per un 409 gia' in attesa`() = runBlocking {
        var rilette = 0
        mandaCambio(CambioRegola.Modifica(7, limite(90)), null, PostinoFinto(propostaCreata("+30", "allenta"))::manda) {
            rilette++
            emptyList()
        }
        assertEquals(0, rilette)
    }

    @Test
    fun `le altre risposte alla modifica diretta restano quelle di prima`() {
        val cambio = CambioRegola.Modifica(7, limite(30))
        assertEquals(
            RegoleViewModel.Evento.Salvata,
            eventoCambio(RispostaHttp(true, 200, "{}"), RegoleViewModel.Evento.Salvata, cambio),
        )
        assertEquals(
            RegoleViewModel.Evento.UltimaRegola,
            eventoCambio(
                RispostaHttp(false, 409, """{ "detail": { "errore": "ultima_regola" } }"""),
                RegoleViewModel.Evento.Eliminata,
                CambioRegola.Eliminazione(7),
            ),
        )
        assertEquals(
            RegoleViewModel.Evento.Errore,
            eventoCambio(RispostaHttp(false, 0, null), RegoleViewModel.Evento.Salvata, cambio),
        )
        // Creare stringe sempre: un blocco su una creazione non ha niente da chiedere.
        assertEquals(RegoleViewModel.Evento.Errore, eventoCambio(lock, RegoleViewModel.Evento.Salvata, null))
    }
}
