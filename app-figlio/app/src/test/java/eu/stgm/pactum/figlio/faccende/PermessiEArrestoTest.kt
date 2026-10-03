package eu.stgm.pactum.figlio.faccende

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) I permessi tolti (una volta, solo al passaggio da concesso a tolto)
 * e "Forza arresto" durante il blocco.
 */
class PermessiEArrestoTest {

    private val uso = MemoriaBlocco.PERMESSO_ACCESSO_USO
    private val sopra = MemoriaBlocco.PERMESSO_MOSTRA_SOPRA

    // --- permesso_revocato ----------------------------------------------------

    @Test
    fun `l'accesso all'uso tolto si segnala sempre, una volta`() {
        val primo = PermessiRevocati.decidi(usoNoto = true, uso = false, sopraNoto = true, sopra = true, bloccoAttivo = false)
        assertEquals(listOf(uso), primo.daSegnalare)
        // Il giro dopo (l'altro giro, il worker o il servizio) trova lo stato noto già "tolto": niente doppioni.
        val secondo = PermessiRevocati.decidi(usoNoto = primo.usoNoto, uso = false, sopraNoto = primo.sopraNoto, sopra = true, bloccoAttivo = true)
        assertTrue(secondo.daSegnalare.isEmpty())
    }

    @Test
    fun `Mostra sopra tolto si segnala solo durante il blocco`() {
        assertEquals(listOf(sopra), PermessiRevocati.decidi(true, true, true, false, bloccoAttivo = true).daSegnalare)
        assertTrue(PermessiRevocati.decidi(true, true, true, false, bloccoAttivo = false).daSegnalare.isEmpty())
    }

    @Test
    fun `un permesso già assente all'inizio non è una revoca`() {
        val esito = PermessiRevocati.decidi(usoNoto = null, uso = false, sopraNoto = null, sopra = false, bloccoAttivo = true)
        assertTrue(esito.daSegnalare.isEmpty())
        assertEquals(false, esito.usoNoto)
        assertEquals(false, esito.sopraNoto)
    }

    @Test
    fun `tolti tutti e due durante il blocco - due eventi, poi più niente`() {
        val primo = PermessiRevocati.decidi(true, false, true, false, bloccoAttivo = true)
        assertEquals(listOf(uso, sopra), primo.daSegnalare)
        assertTrue(PermessiRevocati.decidi(primo.usoNoto, false, primo.sopraNoto, false, true).daSegnalare.isEmpty())
        // Ridati e tolti di nuovo: di nuovo.
        val ridati = PermessiRevocati.decidi(false, true, false, true, true)
        assertTrue(ridati.daSegnalare.isEmpty())
        assertEquals(listOf(uso, sopra), PermessiRevocati.decidi(ridati.usoNoto, false, ridati.sopraNoto, false, true).daSegnalare)
    }

    // --- Forza arresto ----------------------------------------------------------

    private val min = 60_000L
    private val accensione = 1_790_000_000_000L
    private val forza = FermatoDuranteBlocco.MOTIVO_FORZA_ARRESTO

    private fun uscita(quando: Long, motivo: Int = forza) = FermatoDuranteBlocco.Uscita(quando, motivo)

    @Test
    fun `forza arresto durante il blocco - si segnala`() {
        val u = uscita(accensione + 30 * min)
        val trovata = FermatoDuranteBlocco.daSegnalare(listOf(u), accensione, aggiornataIl = accensione - 24 * 60 * min, giaVista = null) { true }
        assertEquals(u, trovata)
        val dettagli = FermatoDuranteBlocco.dettagli(u, adesso = u.quando + 42 * min)
        assertEquals("fermato_durante_blocco", (dettagli["sotto_tipo"] as JsonPrimitive).contentOrNull)
        assertEquals(u.quando, (dettagli["dal"] as JsonPrimitive).longOrNull)
        assertEquals(u.quando + 42 * min, (dettagli["al"] as JsonPrimitive).longOrNull)
        assertEquals(42L, (dettagli["minuti"] as JsonPrimitive).longOrNull)
    }

    @Test
    fun `fermata anche in un altro modo dall'utente - si segnala`() {
        val u = uscita(accensione + 30 * min, FermatoDuranteBlocco.MOTIVO_FERMATA)
        assertEquals(u, FermatoDuranteBlocco.daSegnalare(listOf(u), accensione, null, null) { true })
    }

    @Test
    fun `fuori dal blocco, per un crash o per poca memoria - niente`() {
        assertNull(FermatoDuranteBlocco.daSegnalare(listOf(uscita(accensione + min)), accensione, null, null) { false })
        assertNull(FermatoDuranteBlocco.daSegnalare(listOf(uscita(accensione + min, motivo = 4)), accensione, null, null) { true })
        assertNull(FermatoDuranteBlocco.daSegnalare(listOf(uscita(accensione + min, motivo = 3)), accensione, null, null) { true })
    }

    @Test
    fun `mai dopo un riavvio del telefono - solo le uscite di questa accensione`() {
        assertNull(FermatoDuranteBlocco.daSegnalare(listOf(uscita(accensione - min)), accensione, null, null) { true })
    }

    @Test
    fun `mai per un aggiornamento di Pactum`() {
        val aggiornata = accensione + 30 * min
        assertNull(FermatoDuranteBlocco.daSegnalare(listOf(uscita(aggiornata - 5_000)), accensione, aggiornata, null) { true })
    }

    @Test
    fun `una volta sola per uscita, la più recente`() {
        val prima = uscita(accensione + 10 * min)
        val dopo = uscita(accensione + 20 * min)
        assertEquals(dopo, FermatoDuranteBlocco.daSegnalare(listOf(prima, dopo), accensione, null, null) { true })
        assertNull(FermatoDuranteBlocco.daSegnalare(listOf(prima, dopo), accensione, null, giaVista = dopo.quando) { true })
    }
}
