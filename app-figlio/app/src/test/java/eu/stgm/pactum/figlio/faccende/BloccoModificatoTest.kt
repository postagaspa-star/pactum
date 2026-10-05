package eu.stgm.pactum.figlio.faccende

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.17, contratto v3.9) Un lavoro da fare cambiato dal genitore: l'ora del
 * blocco (`blocco_da`) spostata più avanti (un blocco partito si toglie fino a
 * quell'ora) o più indietro (un blocco programmato parte subito). Il telefono
 * segue sempre il server (MemoriaBlocco), anche dopo un po' senza rete, e la
 * notifica `faccenda_modificata` non confonde niente: fa solo rileggere il
 * blocco (ControlloBlocco.richiedi) e, viceversa, un lavoro cambiato visto
 * nel blocco fa leggere subito le notifiche (NovitaFaccende).
 */
class BloccoModificatoTest {

    private val min = 60_000L
    private val ore = 60 * min
    private val t0 = 1_790_000_000_000L

    private fun ora(muro: Long, monotono: Long = muro - t0 + 3_600_000L, avvio: Int? = 1) = Istante(muro, monotono, avvio)

    private fun letto(bloccoDa: Long) = FaccendaDaFare(7, "Letto", bloccoDa = bloccoDa, genitore = "Mamma")

    private fun attivo(dal: Long, vararg da: FaccendaDaFare) = BloccoDalServer(true, dal, null, da.toList())
    private fun programmato(prossimo: Long, vararg da: FaccendaDaFare) = BloccoDalServer(false, null, prossimo, da.toList())

    /** Una risposta del server, domanda e risposta all'istante [t], con l'orologio del server uguale a quello del telefono. */
    private fun MemoriaBlocco.risposta(r: BloccoDalServer, t: Long) = conServer(r, ora(t), ora(t), dataServer = t)

    @Test
    fun `ora spostata piu' avanti - il blocco partito si toglie fino alla nuova ora`() {
        val bloccato = MemoriaBlocco().risposta(attivo(t0, letto(t0)), t0)
        assertTrue(bloccato.attivoAdesso(ora(t0 + 10 * min)))
        // Mamma sposta il lavoro alle t0 + 3 ore: il server dice "non adesso, alle …".
        val dopo = bloccato.risposta(programmato(t0 + 3 * ore, letto(t0 + 3 * ore)), t0 + 15 * min)
        assertFalse(dopo.attivoAdesso(ora(t0 + 15 * min)))
        assertEquals(t0 + 3 * ore, dopo.prossimaPartenza(ora(t0 + 15 * min)))
        assertEquals(t0 + 3 * ore, dopo.daFare.single().bloccoDa)
        // Senza rete per un po': alla nuova ora parte da solo, non prima.
        assertFalse(dopo.attivoAdesso(ora(t0 + 2 * ore)))
        assertTrue(dopo.attivoAdesso(ora(t0 + 3 * ore)))
    }

    @Test
    fun `ora spostata piu' indietro - il blocco programmato parte subito`() {
        val programmatoAlle18 = MemoriaBlocco().risposta(programmato(t0 + 3 * ore, letto(t0 + 3 * ore)), t0)
        assertFalse(programmatoAlle18.attivoAdesso(ora(t0 + 10 * min)))
        // Mamma lo mette "da subito": il server dice attivo dall'ora del cambio.
        val dopo = programmatoAlle18.risposta(attivo(t0 + 10 * min, letto(t0 + 10 * min)), t0 + 11 * min)
        assertTrue(dopo.attivoAdesso(ora(t0 + 11 * min)))
        assertEquals(t0 + 10 * min, dopo.dalAdesso(ora(t0 + 11 * min)))
        // Poi senza rete: resta bloccato.
        assertTrue(dopo.attivoAdesso(ora(t0 + 2 * ore)))
    }

    @Test
    fun `ora spostata prima ma ancora nel futuro - si aspetta la nuova ora anche offline`() {
        val m = MemoriaBlocco().risposta(programmato(t0 + 3 * ore, letto(t0 + 3 * ore)), t0)
            .risposta(programmato(t0 + ore, letto(t0 + ore)), t0 + 5 * min)
        assertFalse(m.attivoAdesso(ora(t0 + 59 * min)))
        assertTrue(m.attivoAdesso(ora(t0 + ore)))
    }

    @Test
    fun `senza rete prima del cambio - parte all'ora vecchia, poi il server lo toglie`() {
        // Il telefono sapeva "alle t0 + 1 ora" e non ha sentito il cambio (alle t0 + 3 ore).
        val m = MemoriaBlocco().risposta(programmato(t0 + ore, letto(t0 + ore)), t0)
        assertTrue(m.attivoAdesso(ora(t0 + ore + 5 * min)))
        // Torna la rete: il server dice "alle t0 + 3 ore". Si sblocca e si riprogramma.
        val dopo = m.risposta(programmato(t0 + 3 * ore, letto(t0 + 3 * ore)), t0 + ore + 10 * min)
        assertFalse(dopo.attivoAdesso(ora(t0 + ore + 10 * min)))
        assertEquals(t0 + 3 * ore, dopo.prossimaPartenza(ora(t0 + ore + 10 * min)))
    }

    @Test
    fun `spostata avanti di pochissimo - resta bloccato (un ritardo, non uno sblocco)`() {
        val bloccato = MemoriaBlocco().risposta(attivo(t0, letto(t0)), t0)
        val dopo = bloccato.risposta(programmato(t0 + 11 * min, letto(t0 + 11 * min)), t0 + 10 * min)
        assertTrue(dopo.attivoAdesso(ora(t0 + 10 * min)))
    }

    @Test
    fun `una risposta lenta di prima del cambio non rimette l'ora vecchia`() {
        val bloccato = MemoriaBlocco().risposta(attivo(t0, letto(t0)), t0)
        // La domanda partita a t0 + 5 min arriva DOPO quella partita a t0 + 6 min (il cambio).
        val cambiata = bloccato.conServer(programmato(t0 + 3 * ore, letto(t0 + 3 * ore)), ora(t0 + 6 * min), ora(t0 + 6 * min), t0 + 6 * min)
        val lenta = cambiata.conServer(attivo(t0, letto(t0)), ora(t0 + 5 * min), ora(t0 + 7 * min), t0 + 7 * min)
        assertFalse(lenta.attivoAdesso(ora(t0 + 7 * min)))
        assertEquals(t0 + 3 * ore, lenta.prossimo)
    }

    @Test
    fun `un lavoro cambiato nel blocco fa leggere subito le notifiche`() {
        val prima = listOf(letto(t0 + ore))
        assertTrue(NovitaFaccende.cambiate(prima, listOf(letto(t0 + 3 * ore))))
        assertTrue(NovitaFaccende.cambiate(prima, listOf(letto(t0 + ore).copy(titolo = "Rifare il letto"))))
        assertTrue(NovitaFaccende.cambiate(prima, listOf(letto(t0 + ore).copy(nota = "Con le lenzuola pulite"))))
        assertFalse(NovitaFaccende.cambiate(prima, listOf(letto(t0 + ore))))
    }

    @Test
    fun `un blocco tolto dallo spostamento non resta annunciato`() {
        val bloccato = MemoriaBlocco().risposta(attivo(t0, letto(t0)), t0).let { it.conAnnuncio(it.daAnnunciare(ora(t0))!!) }
        val dopo = bloccato.risposta(programmato(t0 + 3 * ore, letto(t0 + 3 * ore)), t0 + 15 * min)
        assertNull(dopo.episodioAdesso(ora(t0 + 15 * min)))
        // Alla nuova ora è un blocco nuovo: si annuncia di nuovo.
        assertEquals(t0 + 3 * ore, dopo.daAnnunciare(ora(t0 + 3 * ore)))
    }
}
