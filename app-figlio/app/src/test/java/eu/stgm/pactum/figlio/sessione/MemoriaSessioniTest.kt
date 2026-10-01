package eu.stgm.pactum.figlio.sessione

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.11) Quello che il telefono ricorda delle Sessioni: quale è in corso, la
 * chiusura anticipata che vale subito e aspetta la rete, e come si unisce a
 * quello che dice il server senza mai riaprire una sessione chiusa.
 */
class MemoriaSessioniTest {

    private val min = 60_000L
    private val ora = 1_790_000_000_000L

    private fun svolta(
        id: Long,
        inizio: Long = ora,
        durata: Long = 60 * min,
        fineServer: Long? = null,
        fineLocale: Long? = null,
        app: List<String> = listOf("eu.spaggiari.classevivafamiglia"),
        sessioneId: Long = 3,
    ) = SvoltaLocale(
        id = id,
        sessioneId = sessioneId,
        nome = "Studio",
        app = app,
        inizio = inizio,
        finePrevista = inizio + durata,
        fineServer = fineServer,
        fineLocale = fineLocale,
    )

    @Test
    fun `la sessione e' in corso dall'inizio alla fine prevista, fine esclusa`() {
        val memoria = MemoriaSessioni(svolte = listOf(svolta(12)))
        assertEquals(12L, memoria.inCorso(ora)?.svoltaId)
        assertEquals(12L, memoria.inCorso(ora + 59 * min)?.svoltaId)
        assertNull(memoria.inCorso(ora + 60 * min))
        assertEquals(ora + 60 * min, memoria.inCorso(ora + 5 * min)?.fine)
    }

    @Test
    fun `appena avviata c'e' gia', anche se l'orologio del server e' un po' avanti`() {
        val memoria = MemoriaSessioni(svolte = listOf(svolta(12)))
        assertEquals(12L, memoria.inCorso(ora - 30_000)?.svoltaId)
        assertNull(memoria.inCorso(ora - MemoriaSessioni.TOLLERANZA_INIZIO_MS - 1))
    }

    @Test
    fun `una sessione senza app o chiusa dal server non e' in corso`() {
        assertNull(MemoriaSessioni(svolte = listOf(svolta(12, app = emptyList()))).inCorso(ora + min))
        assertNull(MemoriaSessioni(svolte = listOf(svolta(12, fineServer = ora + 10 * min))).inCorso(ora + 20 * min))
    }

    @Test
    fun `Termina la sessione vale subito e aspetta la rete`() {
        val memoria = MemoriaSessioni(svolte = listOf(svolta(12)))
        val (dopo, chiusura) = memoria.conTermine(ora + 20 * min)
        assertEquals(TerminazioneInAttesa(12, ora + 20 * min), chiusura)
        assertNull(dopo.inCorso(ora + 20 * min))
        assertEquals(listOf(TerminazioneInAttesa(12, ora + 20 * min)), dopo.terminazioni)
        // Il periodo che non conta finisce lì.
        assertEquals(ora + 20 * min, dopo.svolte.single().fine)
    }

    @Test
    fun `Termina senza una sessione in corso non fa niente`() {
        val memoria = MemoriaSessioni(svolte = listOf(svolta(12)))
        val (dopo, chiusura) = memoria.conTermine(ora + 2 * 60 * min)
        assertNull(chiusura)
        assertEquals(memoria, dopo)
    }

    @Test
    fun `una lettura del server partita prima del Termina non riapre la sessione`() {
        val (terminata, _) = MemoriaSessioni(svolte = listOf(svolta(12))).conTermine(ora + 20 * min)
        // Per il server è ancora in corso: la chiusura non è ancora arrivata.
        val dopo = terminata.conServer(listOf(svolta(12)), inCorsoServer = svolta(12), adesso = ora + 21 * min)
        assertNull(dopo.inCorso(ora + 21 * min))
        // E la chiusura resta da consegnare: è proprio quella in corso sul server.
        assertEquals(listOf(12L), dopo.terminazioni.map { it.svoltaId })
    }

    @Test
    fun `la chiusura in attesa se ne va solo quando il server la dice gia' terminata`() {
        val (terminata, _) = MemoriaSessioni(svolte = listOf(svolta(12))).conTermine(ora + 20 * min)
        val giaTerminata = terminata.conServer(
            listOf(svolta(12, fineServer = ora + 20 * min).copy(chiusura = ChiusureSessione.TERMINATA)),
            inCorsoServer = null,
            adesso = ora + 25 * min,
        )
        assertTrue(giaTerminata.terminazioni.isEmpty())
    }

    @Test
    fun `una sessione scaduta per il server aspetta ancora la chiusura fatta prima`() {
        // Chiusa qui alle +20 senza rete; il server, passata l'ora, la vede scaduta.
        val (terminata, _) = MemoriaSessioni(svolte = listOf(svolta(12))).conTermine(ora + 20 * min)
        val scaduta = terminata.conServer(
            listOf(svolta(12, fineServer = ora + 60 * min).copy(chiusura = ChiusureSessione.SCADUTA)),
            inCorsoServer = null,
            adesso = ora + 90 * min,
        )
        // Consegnata adesso vale da quando è stata fatta: il genitore vede che è stata chiusa prima.
        assertEquals(listOf(TerminazioneInAttesa(12, ora + 20 * min)), scaduta.daConsegnare())
        // E per la misura la sessione è finita alle +20, non alle +60.
        assertEquals(ora + 20 * min, scaduta.svolte.single().fine)
    }

    @Test
    fun `con un'altra sessione in corso la chiusura vecchia resta, e la decide il server`() {
        val (terminata, _) = MemoriaSessioni(svolte = listOf(svolta(12))).conTermine(ora + 20 * min)
        val unAltraInCorso = terminata.conServer(
            listOf(svolta(13, inizio = ora + 30 * min)),
            inCorsoServer = svolta(13, inizio = ora + 30 * min),
            adesso = ora + 31 * min,
        )
        // Porta svolta_id 12: non può chiudere la 13 (il server risponderà 404).
        assertEquals(listOf(12L), unAltraInCorso.daConsegnare().map { it.svoltaId })
        assertEquals(13L, unAltraInCorso.inCorso(ora + 31 * min)?.svoltaId)
    }

    @Test
    fun `una lettura vecchia che non nomina la sessione non butta la chiusura`() {
        val (terminata, _) = MemoriaSessioni(svolte = listOf(svolta(12))).conTermine(ora + 20 * min)
        val dopo = terminata.conServer(emptyList(), inCorsoServer = null, adesso = ora + 21 * min)
        assertEquals(listOf(12L), dopo.terminazioni.map { it.svoltaId })
        // E la sessione appena avviata resta, anche se il server non la nomina.
        assertEquals(listOf(12L), dopo.svolte.map { it.id })
    }

    @Test
    fun `una fine gia' nota non sparisce per una lettura vecchia`() {
        val memoria = MemoriaSessioni(svolte = listOf(svolta(12, fineServer = ora + 20 * min)))
        val dopo = memoria.conServer(listOf(svolta(12)), inCorsoServer = null, adesso = ora + 30 * min)
        assertEquals(ora + 20 * min, dopo.svolte.single().fineServer)
    }

    @Test
    fun `dopo una reinstallazione la sessione in corso torna dal server`() {
        val dopo = MemoriaSessioni().conServer(listOf(svolta(12)), inCorsoServer = svolta(12), adesso = ora + 5 * min)
        assertEquals(12L, dopo.inCorso(ora + 5 * min)?.svoltaId)
        assertEquals("Studio", dopo.inCorso(ora + 5 * min)?.nome)
        // Il ragazzo non l'ha vista partire da qui: prima di coprire qualcosa, glielo si dice.
        assertFalse(dopo.inCorso(ora + 5 * min)!!.annunciata)
    }

    @Test
    fun `una sessione gia' finita che arriva dal server non ha niente da annunciare`() {
        val finita = svolta(12, fineServer = ora + 20 * min).copy(chiusura = ChiusureSessione.TERMINATA)
        val dopo = MemoriaSessioni().conServer(listOf(finita), inCorsoServer = null, adesso = ora + 30 * min)
        assertTrue(dopo.svolte.single().annunciata)
    }

    @Test
    fun `annunciata una volta, resta annunciata anche dopo altre letture del server`() {
        val nuova = MemoriaSessioni().conServer(listOf(svolta(12)), inCorsoServer = svolta(12), adesso = ora + 5 * min)
        val detta = nuova.conAnnuncio(12)
        assertTrue(detta.inCorso(ora + 5 * min)!!.annunciata)
        val riletta = detta.conServer(listOf(svolta(12)), inCorsoServer = svolta(12), adesso = ora + 6 * min)
        assertTrue(riletta.inCorso(ora + 6 * min)!!.annunciata)
        // E una non detta resta non detta, finché non la si dice.
        val ancoraNo = nuova.conServer(listOf(svolta(12)), inCorsoServer = svolta(12), adesso = ora + 6 * min)
        assertFalse(ancoraNo.inCorso(ora + 6 * min)!!.annunciata)
        // L'annuncio di un'altra sessione non cambia niente.
        assertFalse(nuova.conAnnuncio(99).inCorso(ora + 5 * min)!!.annunciata)
    }

    // --- l'orologio del telefono -----------------------------------------------------

    @Test
    fun `avviata da qui, inizio e fine stanno sull'orologio del telefono`() {
        // L'orologio del server è 3 minuti avanti.
        val dalServer = svolta(12, inizio = ora + 3 * min, durata = 60 * min)
        val ancorata = dalServer.ancorataAlTelefono(adesso = ora, durataMinuti = 60)
        assertEquals(ora, ancorata.inizio)
        assertEquals(ora + 60 * min, ancorata.finePrevista)
        assertTrue(ancorata.ancorataQui)
        assertTrue(ancorata.annunciata)
        // Senza la durata chiesta vale quella del server.
        assertEquals(ora + 60 * min, dalServer.ancorataAlTelefono(adesso = ora).finePrevista)
        // Quella chiesta vince su quella del server.
        assertEquals(ora + 30 * min, dalServer.ancorataAlTelefono(adesso = ora, durataMinuti = 30).finePrevista)
    }

    @Test
    fun `una sessione ancorata qui non si sposta con l'orologio del server`() {
        val ancorata = svolta(12, inizio = ora + 3 * min).ancorataAlTelefono(adesso = ora, durataMinuti = 60)
        val memoria = MemoriaSessioni().conAvvio(ancorata, adesso = ora)
        // Il server rilegge la sua: inizio e fine 3 minuti avanti.
        val riletta = memoria.conServer(listOf(svolta(12, inizio = ora + 3 * min)), svolta(12, inizio = ora + 3 * min), adesso = ora + 10 * min)
        assertEquals(ora, riletta.svolte.single().inizio)
        assertEquals(ora + 60 * min, riletta.inCorso(ora + 10 * min)?.fine)
        // Finita da sola per il server alle sue +63: vale la fine del telefono, +60.
        val scaduta = svolta(12, inizio = ora + 3 * min, fineServer = ora + 63 * min).copy(chiusura = ChiusureSessione.SCADUTA)
        val dopo = memoria.conServer(listOf(scaduta), inCorsoServer = null, adesso = ora + 70 * min)
        assertEquals(ora + 60 * min, dopo.svolte.single().fine)
        assertEquals(ChiusureSessione.SCADUTA, dopo.svolte.single().chiusura)
        // Chiusa prima dal server (dal genitore, o un "Termina" consegnato): quella fine vale.
        val chiusa = svolta(12, inizio = ora + 3 * min, fineServer = ora + 20 * min).copy(chiusura = ChiusureSessione.TERMINATA)
        val terminata = memoria.conServer(listOf(chiusa), inCorsoServer = null, adesso = ora + 25 * min)
        assertEquals(ora + 20 * min, terminata.svolte.single().fine)
        assertNull(terminata.inCorso(ora + 21 * min))
    }

    // --- Inizia senza risposta -------------------------------------------------------

    private val incerto = AvvioIncerto(sessioneId = 3, nome = "Studio", durataMinuti = 60, richiestoIl = ora)

    @Test
    fun `un avvio confermato chiarisce il dubbio, e il ragazzo lo sa`() {
        val conDubbio = MemoriaSessioni().conAvvioIncerto(incerto)
        assertEquals(incerto, conDubbio.avvioIncerto)
        val avviata = conDubbio.conAvvio(svolta(12).copy(annunciata = false), adesso = ora)
        assertNull(avviata.avvioIncerto)
        assertTrue(avviata.inCorso(ora + min)!!.annunciata)
        assertNull(conDubbio.senzaAvvioIncerto().avvioIncerto)
    }

    @Test
    fun `avvio incerto - partita, gia' finita o non partita, guardando il server`() {
        val conDubbio = MemoriaSessioni().conAvvioIncerto(incerto)
        assertNull(MemoriaSessioni().esitoAvvioIncerto(ora))
        // Il server non ha niente di quella sessione: non è partita.
        assertEquals(EsitoAvvioIncerto.NON_PARTITA, conDubbio.esitoAvvioIncerto(ora + min))
        // Il server ce l'ha, in corso: partita (l'orologio del server può essere un po' indietro).
        val partita = conDubbio.conServer(listOf(svolta(12, inizio = ora - 2 * min)), svolta(12, inizio = ora - 2 * min), adesso = ora + min)
        assertEquals(EsitoAvvioIncerto.PARTITA, partita.esitoAvvioIncerto(ora + min))
        // ...ma senza l'annuncio non copre niente.
        assertFalse(partita.inCorso(ora + min)!!.annunciata)
        // Partita e già finita (la rete è tornata tardi).
        val finita = conDubbio.conServer(listOf(svolta(12, durata = 30 * min)), inCorsoServer = null, adesso = ora + 45 * min)
        assertEquals(EsitoAvvioIncerto.GIA_FINITA, finita.esitoAvvioIncerto(ora + 45 * min))
    }

    @Test
    fun `avvio incerto - una sessione vecchia o un'altra sessione non sono quella partita`() {
        val conDubbio = MemoriaSessioni().conAvvioIncerto(incerto)
        // La stessa sessione, ma iniziata ieri.
        val ieri = conDubbio.conServer(listOf(svolta(5, inizio = ora - 24 * 60 * min)), inCorsoServer = null, adesso = ora + min)
        assertEquals(EsitoAvvioIncerto.NON_PARTITA, ieri.esitoAvvioIncerto(ora + min))
        // Un'altra sessione, iniziata adesso.
        val altra = conDubbio.conServer(listOf(svolta(13, sessioneId = 4)), svolta(13, sessioneId = 4), adesso = ora + min)
        assertEquals(EsitoAvvioIncerto.NON_PARTITA, altra.esitoAvvioIncerto(ora + min))
    }

    @Test
    fun `un avvio incerto di piu' di due giorni fa si dimentica`() {
        val giorno = 24 * 60 * min
        val conDubbio = MemoriaSessioni().conAvvioIncerto(incerto)
        assertEquals(incerto, conDubbio.potata(ora + giorno).avvioIncerto)
        assertNull(conDubbio.potata(ora + 2 * giorno).avvioIncerto)
    }

    @Test
    fun `un avvio nuovo non butta le chiusure in attesa, che dicono quale sessione chiudono`() {
        val (terminata, _) = MemoriaSessioni(svolte = listOf(svolta(12))).conTermine(ora + 20 * min)
        val dopo = terminata.conAvvio(svolta(13, inizio = ora + 40 * min), adesso = ora + 40 * min)
        assertEquals(listOf(12L), dopo.terminazioni.map { it.svoltaId })
        assertEquals(13L, dopo.inCorso(ora + 41 * min)?.svoltaId)
        assertEquals(listOf(12L, 13L), dopo.svolte.map { it.id })
    }

    @Test
    fun `la risposta al Termina porta la fine del server e chiude la consegna`() {
        val (terminata, _) = MemoriaSessioni(svolte = listOf(svolta(12))).conTermine(ora + 20 * min)
        val chiusa = svolta(12, fineServer = ora + 20 * min).copy(chiusura = ChiusureSessione.TERMINATA)
        val dopo = terminata.conTerminata(12, chiusa)
        assertTrue(dopo.terminazioni.isEmpty())
        assertEquals(ChiusureSessione.TERMINATA, dopo.svolte.single().chiusura)
        assertEquals(ora + 20 * min, dopo.svolte.single().fineLocale)
    }

    @Test
    fun `si consegna una chiusura finche' la sessione c'e' e non e' gia' terminata`() {
        val (terminata, _) = MemoriaSessioni(svolte = listOf(svolta(12))).conTermine(ora + 20 * min)
        assertEquals(listOf(12L), terminata.daConsegnare().map { it.svoltaId })
        // Già terminata per il server (la risposta si era persa): niente da mandare.
        val giaChiusa = terminata.copy(svolte = terminata.svolte.map { it.copy(chiusura = ChiusureSessione.TERMINATA) })
        assertTrue(giaChiusa.daConsegnare().isEmpty())
        // La sessione non c'è più: niente da mandare.
        assertTrue(terminata.copy(svolte = emptyList()).daConsegnare().isEmpty())
    }

    @Test
    fun `le sessioni finite da piu' di 9 giorni si dimenticano`() {
        val giorno = 24 * 60 * min
        val memoria = MemoriaSessioni(svolte = listOf(svolta(1, inizio = ora - 10 * giorno), svolta(2, inizio = ora - 3 * giorno)))
        assertEquals(listOf(2L), memoria.potata(ora).svolte.map { it.id })
    }

    @Test
    fun `i periodi che non contano sono le sessioni che toccano l'intervallo`() {
        val memoria = MemoriaSessioni(
            svolte = listOf(
                svolta(1, inizio = ora, durata = 30 * min),
                svolta(2, inizio = ora + 2 * 60 * min, durata = 30 * min),
                svolta(3, inizio = ora + 4 * 60 * min, durata = 30 * min, app = emptyList()),
            ),
        )
        assertEquals(listOf(1L), memoria.periodi(ora + 10 * min, ora + 60 * min).map { it.id })
        assertEquals(listOf(1L, 2L), memoria.periodi(ora, ora + 5 * 60 * min).map { it.id })
    }

    @Test
    fun `la memoria fa il giro sul disco senza perdere niente`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val (memoria, _) = MemoriaSessioni(
            svolte = listOf(
                svolta(12).copy(nomi = mapOf("eu.spaggiari.classevivafamiglia" to "ClasseViva")),
                svolta(13, inizio = ora + 2 * 60 * min).copy(annunciata = false),
                svolta(14, inizio = ora + 4 * 60 * min).ancorataAlTelefono(ora + 4 * 60 * min, 30),
            ),
            avvioIncerto = incerto,
        ).conTermine(ora + 20 * min)
        val testo = json.encodeToString(MemoriaSessioni.serializer(), memoria)
        assertEquals(memoria, json.decodeFromString(MemoriaSessioni.serializer(), testo))
    }

    @Test
    fun `un file di prima (senza annuncio e ancoraggio) si legge come sessioni annunciate`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val vecchio = """{"svolte":[{"id":12,"sessione_id":3,"nome":"Studio","app":["a"],"inizio":$ora,"fine_prevista":${ora + 60 * min}}],"terminazioni":[]}"""
        val letta = json.decodeFromString(MemoriaSessioni.serializer(), vecchio)
        assertTrue(letta.svolte.single().annunciata)
        assertFalse(letta.svolte.single().ancorataQui)
        assertNull(letta.avvioIncerto)
    }
}
