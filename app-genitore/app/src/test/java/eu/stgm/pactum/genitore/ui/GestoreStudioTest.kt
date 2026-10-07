package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.BloccoFaccende
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.ConfigStudio
import eu.stgm.pactum.genitore.dati.ContenutoStudio
import eu.stgm.pactum.genitore.dati.CorpoNuoveFaccende
import eu.stgm.pactum.genitore.dati.EsitiSessione
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.PaccoStudio
import eu.stgm.pactum.genitore.dati.PaccoSvolteStudio
import eu.stgm.pactum.genitore.dati.PaccoVersioniStudio
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.dati.StudioSvolto
import eu.stgm.pactum.genitore.rete.EsitoChiusuraStudio
import eu.stgm.pactum.genitore.rete.EsitoFaccende
import eu.stgm.pactum.genitore.rete.EsitoFoto
import eu.stgm.pactum.genitore.rete.EsitoLetturaStudio
import eu.stgm.pactum.genitore.rete.EsitoRispostaStudio
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.FonteFaccende
import eu.stgm.pactum.genitore.rete.FonteStudio
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * (0.18, contratto v4.0) I motori con un server finto: lo Studio ([GestoreStudio]:
 * lettura, Studi più vecchi, risposta alla configurazione con la versione vista,
 * chiusura col motivo) e l'approvazione dei lavori ([GestoreFaccende]: `foto_ts`
 * sempre nel corpo, il blocco del server tenuto nello stato, un server più vecchio
 * riconosciuto).
 */
class GestoreStudioTest {

    private suspend fun calma() = repeat(50) { yield() }

    private class StudioFinto : FonteStudio {
        var studio: EsitoLetturaStudio<PaccoStudio> = EsitoLetturaStudio.Letta(PaccoStudio())
        var versioni: EsitoLetturaStudio<PaccoVersioniStudio> = EsitoLetturaStudio.Letta(PaccoVersioniStudio())
        var svolte: (Long?) -> EsitoLetturaStudio<PaccoSvolteStudio> = { EsitoLetturaStudio.Letta(PaccoSvolteStudio()) }
        var risposta: EsitoRispostaStudio = EsitoRispostaStudio.Decisa(null)
        var chiusura: EsitoChiusuraStudio = EsitoChiusuraStudio.Chiuso(null)
        val risposte = mutableListOf<Triple<Int, String, String?>>()
        val chiusure = mutableListOf<Pair<Long, String>>()
        var letture = 0

        override suspend fun leggiStudio(figlioId: Long?): EsitoLetturaStudio<PaccoStudio> {
            letture++
            return studio
        }

        override suspend fun leggiVersioniStudio(figlioId: Long?) = versioni
        override suspend fun leggiSvolteStudio(figlioId: Long?, primaDi: Long?) = svolte(primaDi)

        override suspend fun rispondiConfigStudio(figlioId: Long?, esito: String, versione: Int, motivazione: String?): EsitoRispostaStudio {
            risposte += Triple(versione, esito, motivazione)
            return risposta
        }

        override suspend fun chiudiStudio(figlioId: Long?, studioId: Long, motivo: String): EsitoChiusuraStudio {
            chiusure += studioId to motivo
            return chiusura
        }
    }

    @Test
    fun `lettura, Studi piu vecchi e server vecchio`() = runBlocking {
        val server = StudioFinto()
        server.studio = EsitoLetturaStudio.Letta(PaccoStudio(config = ConfigStudio(versione = 2)))
        server.versioni = EsitoLetturaStudio.Letta(PaccoVersioniStudio(listOf(ContenutoStudio(versione = 1))))
        server.svolte = { prima ->
            if (prima == null) {
                EsitoLetturaStudio.Letta(PaccoSvolteStudio((50L downTo 31L).map { StudioSvolto(id = it) }, altre = true))
            } else {
                EsitoLetturaStudio.Letta(PaccoSvolteStudio(listOf(StudioSvolto(id = 30), StudioSvolto(id = 31)), altre = false))
            }
        }
        val g = GestoreStudio(this) { server }
        g.aggiorna(1)
        calma()
        assertTrue(g.stato.value.di(1))
        assertEquals(2, g.stato.value.pacco?.config?.versione)
        assertEquals(1, g.stato.value.versioni?.size)
        assertEquals(20, g.stato.value.svolte?.size)
        assertTrue(g.stato.value.altre)
        g.altri(1)
        calma()
        // Senza doppioni, dal più recente; finiti.
        assertEquals(21, g.stato.value.svolte?.size)
        assertEquals(30L, g.stato.value.svolte?.last()?.id)
        assertFalse(g.stato.value.altre)
        // Un altro figlio non vede mai lo Studio del primo.
        server.studio = EsitoLetturaStudio.ServerVecchio
        g.aggiorna(2)
        assertNull(g.stato.value.pacco)
        calma()
        assertTrue(g.stato.value.serverVecchio)
        // 401: il collegamento non vale più.
        server.studio = EsitoLetturaStudio.NonAutorizzato
        g.aggiorna(2)
        calma()
        assertTrue(g.stato.value.collegamentoNonValido)
        g.dimentica()
    }

    @Test
    fun `dopo altri e una rilettura gli Studi della seconda pagina restano`() = runBlocking {
        val server = StudioFinto()
        var primaPagina = (50L downTo 31L).map { StudioSvolto(id = it) }
        server.svolte = { prima ->
            when (prima) {
                null -> EsitoLetturaStudio.Letta(PaccoSvolteStudio(primaPagina, altre = true))
                31L -> EsitoLetturaStudio.Letta(PaccoSvolteStudio((30L downTo 11L).map { StudioSvolto(id = it) }, altre = true))
                else -> EsitoLetturaStudio.Letta(PaccoSvolteStudio((10L downTo 1L).map { StudioSvolto(id = it) }, altre = false))
            }
        }
        val g = GestoreStudio(this) { server }
        g.aggiorna(1)
        calma()
        g.altri(1)
        calma()
        assertEquals(40, g.stato.value.svolte?.size)
        // La rilettura di ogni minuto: c'è uno Studio nuovo (51) e il 41 è cambiato.
        primaPagina = listOf(StudioSvolto(id = 51)) + (50L downTo 32L).map { StudioSvolto(id = it, minutiAttivita = if (it == 41L) 65 else null) }
        g.aggiorna(1)
        calma()
        val svolte = g.stato.value.svolte.orEmpty()
        // Prima le nuove, poi le vecchie: dal 51 all'11, senza doppioni né buchi.
        assertEquals((51L downTo 11L).toList(), svolte.map { it.id })
        assertEquals(65, svolte.first { it.id == 41L }.minutiAttivita)
        // "Altri" va avanti da dove era arrivato, non ricomincia.
        assertTrue(g.stato.value.altre)
        g.altri(1)
        calma()
        assertEquals((51L downTo 1L).toList(), g.stato.value.svolte?.map { it.id })
        assertFalse(g.stato.value.altre)
        // Una rilettura dopo l'ultima pagina la tiene, e "altre" resta false.
        g.aggiorna(1)
        calma()
        assertEquals(51, g.stato.value.svolte?.size)
        assertFalse(g.stato.value.altre)
        // "Da capo": solo la prima pagina.
        g.aggiorna(1, daCapo = true)
        calma()
        assertEquals(20, g.stato.value.svolte?.size)
        assertTrue(g.stato.value.altre)
        // Cambiando figlio si riparte dalla prima pagina.
        g.altri(1)
        calma()
        g.aggiorna(2)
        calma()
        assertEquals(20, g.stato.value.svolte?.size)
        g.dimentica()
    }

    @Test
    fun `la prima pagina che non tocca le vecchie riparte da capo`() {
        val vecchie = (50L downTo 11L).map { StudioSvolto(id = it) }
        // Più di 20 Studi nuovi in un colpo: in mezzo ne mancherebbero, meglio la sola prima pagina.
        val lontana = (90L downTo 71L).map { StudioSvolto(id = it) }
        assertEquals(lontana to true, unisciPrimaPagina(lontana, true, vecchie, false))
        // La prima pagina è già tutto (altre = false): vale lei.
        val tutta = (50L downTo 40L).map { StudioSvolto(id = it) }
        assertEquals(tutta to false, unisciPrimaPagina(tutta, false, vecchie, true))
        // Senza vecchie: la prima pagina.
        assertEquals(tutta to true, unisciPrimaPagina(tutta, true, null, false))
    }

    @Test
    fun `la risposta alla configurazione porta la versione vista, e un no il suo perche`() = runBlocking {
        val server = StudioFinto()
        val g = GestoreStudio(this) { server }
        g.aggiorna(1)
        calma()
        g.rispondi(1, versione = 5, esito = EsitiSessione.APPROVA, motivazione = "ignorato per il sì")
        // Un doppio tocco non manda due volte.
        g.rispondi(1, versione = 5, esito = EsitiSessione.APPROVA, motivazione = null)
        calma()
        assertEquals(listOf(Triple(5, EsitiSessione.APPROVA, null as String?)), server.risposte)
        assertEquals(EventoStudio.Decisa(EsitiSessione.APPROVA), g.stato.value.evento)
        assertEquals(5, g.stato.value.decise[1L])
        // Dopo una decisione si rilegge.
        assertEquals(2, server.letture)
        g.consumaEvento()

        g.rispondi(1, versione = 6, esito = EsitiSessione.RIFIUTA, motivazione = "  troppi siti  ")
        calma()
        assertEquals(Triple(6, EsitiSessione.RIFIUTA, "troppi siti"), server.risposte.last())

        // Cambiata nel frattempo: niente deciso, e la card resta (non è "decisa").
        server.risposta = EsitoRispostaStudio.Cambiata(ConfigStudio(versione = 8))
        g.rispondi(1, versione = 7, esito = EsitiSessione.APPROVA, motivazione = null)
        calma()
        assertEquals(EventoStudio.Cambiata, g.stato.value.evento)
        assertEquals(6, g.stato.value.decise[1L])

        // Niente da decidere (un altro genitore ha già deciso): la card sparisce lo stesso.
        server.risposta = EsitoRispostaStudio.Rifiutata(CodiciErrore.NIENTE_DA_DECIDERE)
        g.rispondi(1, versione = 9, esito = EsitiSessione.APPROVA, motivazione = null)
        calma()
        assertEquals(EventoStudio.NienteDaDecidere, g.stato.value.evento)
        assertEquals(9, g.stato.value.decise[1L])

        // La rete che cade: lo si dice, e non si rilegge (non è cambiato niente).
        server.risposta = EsitoRispostaStudio.Fallita
        val lettePrima = server.letture
        g.rispondi(1, versione = 10, esito = EsitiSessione.APPROVA, motivazione = null)
        calma()
        assertEquals(EventoStudio.Rifiuto(null, GestoStudio.APPROVA, 1), g.stato.value.evento)
        assertEquals(lettePrima, server.letture)
        g.dimentica()
    }

    @Test
    fun `chiudere lo Studio vuole il motivo, e gia chiuso si dice`() = runBlocking {
        val server = StudioFinto()
        val g = GestoreStudio(this) { server }
        g.aggiorna(1)
        calma()
        // Senza un motivo buono non parte niente.
        g.chiudi(1, 41, "  ")
        g.chiudi(1, 41, "ok")
        calma()
        assertTrue(server.chiusure.isEmpty())
        g.chiudi(1, 41, "  visita medica ")
        calma()
        assertEquals(listOf(41L to "visita medica"), server.chiusure)
        assertEquals(EventoStudio.Chiuso, g.stato.value.evento)
        server.chiusura = EsitoChiusuraStudio.GiaChiuso(null)
        g.chiudi(1, 41, "visita medica")
        calma()
        assertEquals(EventoStudio.GiaChiuso, g.stato.value.evento)
        server.chiusura = EsitoChiusuraStudio.Rifiutata(CodiciErrore.COLLEGAMENTO_NON_VALIDO)
        g.chiudi(1, 41, "visita medica")
        calma()
        assertTrue(g.stato.value.collegamentoNonValido)
        g.dimentica()
    }

    // --- l'approvazione dei lavori nel motore delle faccende ----------------------------------------

    private class FaccendeFinte : FonteFaccende {
        var lettura: EsitoFaccende = EsitoFaccende.Lette(emptyList())
        val conferme = mutableListOf<Pair<Long, String?>>()
        var conferma: EsitoScrittura<Faccenda?> = EsitoScrittura.Riuscito(null)

        override suspend fun leggiFaccende(figlioId: Long?) = lettura
        override suspend fun daiFaccende(corpo: CorpoNuoveFaccende): EsitoScrittura<List<Faccenda>?> = EsitoScrittura.Fallito
        override suspend fun bocciaFaccenda(faccendaId: Long, nota: String?): EsitoScrittura<Faccenda?> = EsitoScrittura.Riuscito(null)
        override suspend fun annullaFaccenda(faccendaId: Long): EsitoScrittura<Faccenda?> = EsitoScrittura.Riuscito(null)
        override suspend fun scaricaFoto(faccendaId: Long): EsitoFoto = EsitoFoto.Fallita
        override suspend fun confermaFaccenda(faccendaId: Long, fotoTs: String?): EsitoScrittura<Faccenda?> {
            conferme += faccendaId to fotoTs
            return conferma
        }
    }

    private val daApprovare = Faccenda(
        id = 5,
        titolo = "Rifai il letto",
        stato = StatiFaccenda.FATTA,
        fotoTs = "2026-10-07T13:10:00+00:00",
        foto = true,
        daApprovare = true,
    )

    @Test
    fun `approvare manda sempre la foto guardata, e dice che cosa e successo`() = runBlocking {
        val server = FaccendeFinte()
        val blocco = BloccoFaccende(attivo = true, rimandato = false)
        server.lettura = EsitoFaccende.Lette(listOf(daApprovare), blocco = blocco)
        val g = GestoreFaccende<String>(this, { server }, { String(it) }, orologio = { Instant.parse("2026-10-07T14:00:00Z") })
        g.aggiorna(1)
        calma()
        // Il blocco del server sta nello stato: è lui a dire com'è.
        assertEquals(blocco, g.stato.value.blocco)
        assertTrue(g.stato.value.conApprovazione)
        // Senza la foto guardata non si approva (il server direbbe 422).
        g.conferma(1, daApprovare, fotoVista = null, effetto = EffettoApprovazione.SBLOCCA)
        calma()
        assertTrue(server.conferme.isEmpty())
        // (correzione 0.18) Lo sblocco promesso si conferma col blocco riletto dopo: qui è spento.
        server.lettura = EsitoFaccende.Lette(listOf(daApprovare.copy(daApprovare = false)), blocco = BloccoFaccende(attivo = false, rimandato = false))
        g.conferma(1, daApprovare, fotoVista = daApprovare.fotoTs, effetto = EffettoApprovazione.SBLOCCA)
        calma()
        assertEquals(listOf(5L to daApprovare.fotoTs), server.conferme)
        assertEquals(EventoFaccende.Approvata(EffettoApprovazione.SBLOCCA), g.stato.value.evento)
        g.consumaEvento()
        // Il blocco riletto è ancora attivo (un lavoro dato intanto, un orologio sfasato): niente sblocco detto.
        server.lettura = EsitoFaccende.Lette(listOf(daApprovare), blocco = blocco)
        g.conferma(1, daApprovare, fotoVista = daApprovare.fotoTs, effetto = EffettoApprovazione.SBLOCCA)
        calma()
        assertEquals(EventoFaccende.Approvata(EffettoApprovazione.NESSUNO), g.stato.value.evento)
        g.consumaEvento()
        // La rilettura fallisce: niente sblocco detto.
        server.lettura = EsitoFaccende.Fallita
        g.conferma(1, daApprovare, fotoVista = daApprovare.fotoTs, effetto = EffettoApprovazione.SBLOCCA)
        calma()
        assertEquals(EventoFaccende.Approvata(EffettoApprovazione.NESSUNO), g.stato.value.evento)
        g.consumaEvento()
        server.lettura = EsitoFaccende.Lette(listOf(daApprovare), blocco = blocco)
        // Un rifiuto dell'approvazione ha il gesto giusto (le sue parole).
        server.conferma = EsitoScrittura.Rifiutato(CodiciErrore.NON_CONFERMABILE)
        g.conferma(1, daApprovare, fotoVista = daApprovare.fotoTs)
        calma()
        assertEquals(EventoFaccende.Rifiuto(CodiciErrore.NON_CONFERMABILE, GestoFaccende.APPROVA, 1), g.stato.value.evento)
        // Una foto di prima della v4.0: "Segna come svolto", come nella v3.9.
        server.conferma = EsitoScrittura.Riuscito(null)
        g.conferma(1, daApprovare.copy(daApprovare = false), fotoVista = daApprovare.fotoTs)
        calma()
        assertEquals(EventoFaccende.Confermata, g.stato.value.evento)
        g.dimentica()
    }

    @Test
    fun `un server piu vecchio della v4 si riconosce dall'elenco senza blocco`() = runBlocking {
        val server = FaccendeFinte()
        server.lettura = EsitoFaccende.Lette(listOf(daApprovare.copy(daApprovare = false)), blocco = BloccoFaccende(attivo = false, rimandato = false))
        val g = GestoreFaccende<String>(this, { server }, { String(it) })
        g.aggiorna(1)
        calma()
        assertTrue(g.stato.value.conApprovazione)
        server.lettura = EsitoFaccende.Lette(listOf(daApprovare.copy(daApprovare = false)), blocco = null)
        g.aggiorna(1)
        calma()
        assertFalse(g.stato.value.conApprovazione)
        assertNull(g.stato.value.blocco)
        server.lettura = EsitoFaccende.ServerVecchio
        g.aggiorna(1)
        calma()
        assertFalse(g.stato.value.conApprovazione)
        g.dimentica()
    }
}
