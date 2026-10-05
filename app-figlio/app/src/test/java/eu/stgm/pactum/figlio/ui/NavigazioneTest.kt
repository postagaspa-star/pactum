package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (0.15) La navigazione a quattro schede: i segnalibri delle notifiche (gli
 * stessi valori di sempre, anche quelli già nella tendina di chi aggiorna
 * dalla 0.14) arrivano al posto nuovo giusto; il nome della scheda salvato da
 * una versione vecchia non fa cadere l'app; Indietro fa quello che si aspetta.
 */
class NavigazioneTest {

    @Test
    fun `ogni segnalibro arriva al posto nuovo giusto`() {
        assertEquals(Ingresso(Scheda.OGGI), Navigazione.ingresso(MainActivity.DEST_OGGI))
        assertEquals(Ingresso(Scheda.REGOLE), Navigazione.ingresso(MainActivity.DEST_REGOLE))
        // Le proposte del genitore: in cima a Regole, dove sta "Da decidere".
        assertEquals(Ingresso(Scheda.REGOLE, inCima = true), Navigazione.ingresso(MainActivity.DEST_PROPOSTE))
        // L'esito di una dichiarazione: lo Storico sulle dichiarazioni, sopra Regole.
        assertEquals(
            Ingresso(Scheda.REGOLE, pagina = Pagina.STORICO_DICHIARAZIONI),
            Navigazione.ingresso(MainActivity.DEST_DIARIO),
        )
        assertEquals(Ingresso(Scheda.SESSIONI), Navigazione.ingresso(MainActivity.DEST_SESSIONI))
        // Lavori di casa: dalle notifiche, da "Apri Pactum" delle barriere e della copertura.
        assertEquals(Ingresso(Scheda.LAVORI), Navigazione.ingresso(MainActivity.DEST_FACCENDE))
        // "Termina la sessione" della notifica fissa: Oggi (la conferma la apre la sessione in corso).
        assertEquals(Ingresso(Scheda.OGGI), Navigazione.ingresso(MainActivity.DEST_TERMINA_SESSIONE))
    }

    @Test
    fun `i valori dei segnalibri restano quelli della 0_14`() {
        // Sono già scritti nelle notifiche in tendina: non devono cambiare.
        assertEquals("oggi", MainActivity.DEST_OGGI)
        assertEquals("regole", MainActivity.DEST_REGOLE)
        assertEquals("proposte", MainActivity.DEST_PROPOSTE)
        assertEquals("diario", MainActivity.DEST_DIARIO)
        assertEquals("sessioni", MainActivity.DEST_SESSIONI)
        assertEquals("termina_sessione", MainActivity.DEST_TERMINA_SESSIONE)
        assertEquals("faccende", MainActivity.DEST_FACCENDE)
        assertEquals("destinazione_iniziale", MainActivity.EXTRA_DESTINAZIONE)
    }

    @Test
    fun `un segnalibro che non si conosce apre Oggi, mai il vuoto`() {
        assertEquals(Ingresso(Scheda.OGGI), Navigazione.ingresso("una_scheda_del_futuro"))
        assertEquals(Ingresso(Scheda.OGGI), Navigazione.ingresso(""))
        assertEquals(Ingresso(Scheda.OGGI), Navigazione.ingresso(null))
    }

    @Test
    fun `il nome di scheda salvato da una versione vecchia diventa la scheda giusta`() {
        assertEquals(Scheda.OGGI, Navigazione.schedaSalvata("OGGI"))
        assertEquals(Scheda.REGOLE, Navigazione.schedaSalvata("REGOLE"))
        assertEquals(Scheda.SESSIONI, Navigazione.schedaSalvata("SESSIONI"))
        assertEquals(Scheda.LAVORI, Navigazione.schedaSalvata("LAVORI"))
        // Le schede che non ci sono più.
        assertEquals(Scheda.REGOLE, Navigazione.schedaSalvata("PROPOSTE"))
        assertEquals(Scheda.REGOLE, Navigazione.schedaSalvata("DIARIO"))
        assertEquals(Scheda.LAVORI, Navigazione.schedaSalvata("FACCENDE"))
        // Nomi sconosciuti o assenti: Oggi, senza cadere.
        assertEquals(Scheda.OGGI, Navigazione.schedaSalvata("BONUS"))
        assertEquals(Scheda.OGGI, Navigazione.schedaSalvata(""))
        assertEquals(Scheda.OGGI, Navigazione.schedaSalvata(null))
    }

    @Test
    fun `la pila delle pagine si salva come testo e torna uguale`() {
        val pila = listOf(Pagina.IMPOSTAZIONI, Pagina.COSA_VEDE)
        assertEquals(pila, Navigazione.pilaDaTesto(Navigazione.pilaInTesto(pila)))
        assertEquals(emptyList<Pagina>(), Navigazione.pilaDaTesto(""))
        assertEquals(emptyList<Pagina>(), Navigazione.pilaDaTesto(null))
        // Una pagina che non si conosce (versione nuova) si salta.
        assertEquals(listOf(Pagina.IMPOSTAZIONI), Navigazione.pilaDaTesto("IMPOSTAZIONI,PAGINA_DEL_FUTURO"))
    }

    @Test
    fun `Indietro chiude la pagina, poi torna a Oggi, poi esce`() {
        // Una pagina aperta: si chiude quella, la scheda resta.
        assertEquals(
            Scheda.REGOLE to listOf(Pagina.IMPOSTAZIONI),
            Navigazione.indietro(Scheda.REGOLE, listOf(Pagina.IMPOSTAZIONI, Pagina.COSA_VEDE)),
        )
        assertEquals(Scheda.OGGI to emptyList<Pagina>(), Navigazione.indietro(Scheda.OGGI, listOf(Pagina.TEMPO)))
        // Nessuna pagina, scheda diversa da Oggi: si torna a Oggi.
        assertEquals(Scheda.OGGI to emptyList<Pagina>(), Navigazione.indietro(Scheda.SESSIONI, emptyList()))
        assertEquals(Scheda.OGGI to emptyList<Pagina>(), Navigazione.indietro(Scheda.LAVORI, emptyList()))
        // Da Oggi, senza pagine: si esce (decide Android).
        assertNull(Navigazione.indietro(Scheda.OGGI, emptyList()))
    }

    @Test
    fun `le schede sono quattro, sempre le stesse e nello stesso ordine`() {
        assertEquals(listOf(Scheda.OGGI, Scheda.REGOLE, Scheda.SESSIONI, Scheda.LAVORI), Scheda.entries.toList())
    }
}
