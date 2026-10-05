package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (0.17, contratto v3.9) I lavori di casa cambiati, confermati e cercati: le
 * notifiche nuove con le parole del figlio, la riga "Confermato da Mamma",
 * l'ora del blocco anche a blocco partito, e la ricerca (testo, esiti,
 * risposte vecchie che non contano).
 */
class LavoriV39Test {

    private val roma = ZoneId.of("Europe/Rome")

    /** Venerdì 2 ottobre 2026, 15:00 a Roma. */
    private val adesso = ZonedDateTime.of(2026, 10, 2, 15, 0, 0, 0, roma).toInstant().toEpochMilli()

    private fun ora(giorno: Int, ore: Int, minuti: Int = 0) =
        ZonedDateTime.of(2026, 10, giorno, ore, minuti, 0, 0, roma).toInstant().toEpochMilli()

    private val parole = ParoleFaccende(
        nuove = { nome, _ -> "$nome ti ha dato lavori" },
        bloccoSubito = "%1\$s · blocco da subito",
        bloccoAlle = "%1\$s · blocco dalle %2\$s",
        bloccoDomani = "%1\$s · blocco domani dalle %2\$s",
        bloccoGiorno = "%1\$s · blocco %2\$s dalle %3\$s",
        nuoveTesto = "",
        bocciata = "%1\$s ha bocciato «%2\$s»",
        bocciataTesto = "",
        annullata = "%1\$s ha annullato «%2\$s»",
        annullataTesto = "",
        genitoreSenzaNome = "Il genitore",
        modificata = "%1\$s ha cambiato un lavoro di casa",
        modificataTitolo = "«%1\$s» (prima «%2\$s»)",
        modificataBloccoSubito = "Ora blocca il telefono da subito.",
        modificataBloccoAlle = "Ora blocca il telefono dalle %1\$s.",
        modificataBloccoDomani = "Ora blocca il telefono domani dalle %1\$s.",
        modificataBloccoGiorno = "Ora blocca il telefono %1\$s dalle %2\$s.",
        modificataNota = "Nota: «%1\$s»",
        modificataNotaTolta = "La nota è stata tolta.",
        confermata = "%1\$s ha confermato «%2\$s»",
        confermataTesto = "Il lavoro è segnato come svolto.",
    )

    private fun payload(testo: String): JsonObject = Json.parseToJsonElement(testo) as JsonObject

    // --- faccenda_modificata ------------------------------------------------------

    @Test
    fun `l'ora del blocco spostata piu' avanti, detta in chiaro`() {
        val p = payload(
            """{ "faccenda_id": 7, "titolo": "Letto", "genitore": { "id": 2, "nome": "Mamma" },
                 "cambi": { "blocco_da": { "prima": "2026-10-02T14:00:00+00:00", "dopo": "2026-10-02T16:00:00+00:00" } } }""",
        )
        val (titolo, testo) = TestoFaccende.avvisoModificata(p, adesso, roma, parole)!!
        assertEquals("Mamma ha cambiato un lavoro di casa", titolo)
        assertEquals("«Letto»\nOra blocca il telefono dalle 18:00.", testo)
    }

    @Test
    fun `diventato subito - blocco da subito`() {
        // "dopo" null (subito) o un'ora già passata.
        val nullo = payload("""{ "titolo": "Letto", "genitore": "Papà", "cambi": { "blocco_da": { "prima": "2026-10-02T16:00:00+00:00", "dopo": null } } }""")
        assertEquals("«Letto»\nOra blocca il telefono da subito.", TestoFaccende.avvisoModificata(nullo, adesso, roma, parole)!!.second)
        val passato = payload("""{ "titolo": "Letto", "cambi": { "blocco_da": { "dopo": "2026-10-02T12:59:00+00:00" } } }""")
        assertEquals("«Letto»\nOra blocca il telefono da subito.", TestoFaccende.avvisoModificata(passato, adesso, roma, parole)!!.second)
        // Senza nome del genitore.
        assertEquals("Il genitore ha cambiato un lavoro di casa", TestoFaccende.avvisoModificata(passato, adesso, roma, parole)!!.first)
    }

    @Test
    fun `domani e un altro giorno`() {
        val domani = payload("""{ "titolo": "Letto", "cambi": { "blocco_da": { "dopo": "2026-10-03T07:30:00+00:00" } } }""")
        assertEquals("«Letto»\nOra blocca il telefono domani dalle 09:30.", TestoFaccende.avvisoModificata(domani, adesso, roma, parole)!!.second)
        val lunedi = payload("""{ "titolo": "Letto", "cambi": { "blocco_da": { "dopo": "2026-10-05T14:00:00+00:00" } } }""")
        assertEquals("«Letto»\nOra blocca il telefono lunedì dalle 16:00.", TestoFaccende.avvisoModificata(lunedi, adesso, roma, parole)!!.second)
    }

    @Test
    fun `titolo cambiato e nota`() {
        val p = payload(
            """{ "titolo": "Leggere 20 pagine", "genitore": { "nome": "Mamma" },
                 "cambi": { "titolo": { "prima": "Leggere", "dopo": "Leggere 20 pagine" }, "nota": { "prima": null, "dopo": "Il libro di storia" } } }""",
        )
        assertEquals(
            "«Leggere 20 pagine» (prima «Leggere»)\nNota: «Il libro di storia»",
            TestoFaccende.avvisoModificata(p, adesso, roma, parole)!!.second,
        )
        val tolta = payload("""{ "titolo": "Letto", "cambi": { "nota": { "prima": "Con le lenzuola", "dopo": "" } } }""")
        assertEquals("«Letto»\nLa nota è stata tolta.", TestoFaccende.avvisoModificata(tolta, adesso, roma, parole)!!.second)
    }

    @Test
    fun `senza cambi il titolo e basta, senza titolo il messaggio del server`() {
        assertEquals("«Letto»", TestoFaccende.avvisoModificata(payload("""{ "titolo": "Letto" }"""), adesso, roma, parole)!!.second)
        assertNull(TestoFaccende.avvisoModificata(payload("""{ "faccenda_id": 7 }"""), adesso, roma, parole))
    }

    // --- faccenda_confermata --------------------------------------------------------

    @Test
    fun `Mamma ha confermato Letto`() {
        val p = payload("""{ "faccenda_id": 7, "titolo": "Letto", "genitore": { "id": 2, "nome": "Mamma" } }""")
        assertEquals("Mamma ha confermato «Letto»" to "Il lavoro è segnato come svolto.", TestoFaccende.avvisoConfermata(p, parole))
        assertNull(TestoFaccende.avvisoConfermata(payload("""{ "faccenda_id": 7 }"""), parole))
    }

    @Test
    fun `le due notifiche nuove vanno nel canale dei lavori e aprono Lavori`() {
        for (tipo in listOf(TipiNotificaFaccende.FACCENDA_MODIFICATA, TipiNotificaFaccende.FACCENDA_CONFERMATA)) {
            assertEquals(AvvisiLocali.CANALE_FACCENDE, AvvisiLocali.canaleTipo(tipo))
            assertEquals(MainActivity.DEST_FACCENDE, AvvisiLocali.destinazioneTipo(tipo))
        }
        // Un lavoro cambiato rilegge subito il blocco; uno confermato non lo cambia.
        assertTrue(TipiNotificaFaccende.FACCENDA_MODIFICATA in TipiNotificaFaccende.CAMBIANO_IL_BLOCCO)
        assertFalse(TipiNotificaFaccende.FACCENDA_CONFERMATA in TipiNotificaFaccende.CAMBIANO_IL_BLOCCO)
    }

    // --- confermata_ts / confermata_da ------------------------------------------------

    @Test
    fun `la faccenda confermata si legge, e senza i campi non lo e'`() {
        val confermata = LetturaFaccende.faccenda(
            Json.parseToJsonElement(
                """{ "id": 7, "titolo": "Letto", "stato": "fatta", "foto_ts": "2026-10-02T12:00:00+00:00",
                     "confermata_ts": "2026-10-02T12:30:00+00:00", "confermata_da": { "id": 2, "nome": "Mamma" } }""",
            ),
        )!!
        assertTrue(confermata.confermata)
        assertEquals("Mamma", confermata.confermataDa)
        val vecchia = LetturaFaccende.faccenda(Json.parseToJsonElement("""{ "id": 8, "titolo": "Cane", "stato": "fatta" }"""))!!
        assertFalse(vecchia.confermata)
        assertNull(vecchia.confermataDa)
        val nulli = LetturaFaccende.faccenda(Json.parseToJsonElement("""{ "id": 9, "stato": "fatta", "confermata_ts": null, "confermata_da": null }"""))!!
        assertFalse(nulli.confermata)
    }

    // --- l'ora del blocco sempre, anche partito ------------------------------------------

    @Test
    fun `da quando blocca un lavoro gia' partito`() {
        assertEquals(TestoFaccende.Partito.Oggi("14:02"), TestoFaccende.partitoDa(ora(2, 14, 2), adesso, roma))
        assertEquals(TestoFaccende.Partito.Prima("1/10", "18:00"), TestoFaccende.partitoDa(ora(1, 18), adesso, roma))
        // Non ancora partito, o l'ora non si sa: niente.
        assertNull(TestoFaccende.partitoDa(ora(2, 16), adesso, roma))
        assertNull(TestoFaccende.partitoDa(null, adesso, roma))
    }

    // --- la ricerca --------------------------------------------------------------------

    @Test
    fun `il testo da cercare`() {
        assertNull(RicercaFaccende.testo(""))
        assertNull(RicercaFaccende.testo("   "))
        assertEquals("letto", RicercaFaccende.testo("  letto "))
        assertEquals(80, RicercaFaccende.testo("a".repeat(120))!!.length)
    }

    @Test
    fun `gli esiti della ricerca`() {
        val trovati = RicercaFaccende.esito(
            """{ "faccende": [ { "id": 3, "titolo": "Rifare il letto", "stato": "fatta" }, { "id": 9, "titolo": "Letto", "stato": "da_fare" } ], "altre": true }""",
            200,
        )
        assertTrue(trovati is RicercaFaccende.Esito.Trovati)
        trovati as RicercaFaccende.Esito.Trovati
        assertEquals(listOf(3L, 9L), trovati.faccende.map { it.id })
        assertTrue(trovati.altre)
        assertEquals(RicercaFaccende.Esito.Trovati(emptyList(), false), RicercaFaccende.esito("""{ "faccende": [], "altre": false }""", 200))
    }

    @Test
    fun `server vecchio, senza rete, scollegato, errore`() {
        // v3.8 ignora "cerca": gli ultimi 30 giorni senza "altre". Non è una ricerca.
        assertEquals(RicercaFaccende.Esito.ServerVecchio, RicercaFaccende.esito("""{ "faccende": [ { "id": 3, "titolo": "Bucato" } ] }""", 200))
        assertEquals(RicercaFaccende.Esito.ServerVecchio, RicercaFaccende.esito(null, 404))
        assertEquals(RicercaFaccende.Esito.ServerVecchio, RicercaFaccende.esito(null, 405))
        assertEquals(RicercaFaccende.Esito.SenzaRete, RicercaFaccende.esito(null, 0))
        assertEquals(RicercaFaccende.Esito.Scollegato, RicercaFaccende.esito(null, 401))
        assertEquals(RicercaFaccende.Esito.Errore, RicercaFaccende.esito(null, 500))
        assertEquals(RicercaFaccende.Esito.Errore, RicercaFaccende.esito("non è json", 200))
        assertEquals(RicercaFaccende.Esito.Errore, RicercaFaccende.esito("""{ "altre": true }""", 200))
    }

    @Test
    fun `vale solo la risposta all'ultima domanda`() {
        val s = RicercaFaccende.Sequenza()
        val le = s.nuova()
        val letto = s.nuova()
        // La risposta a "le" arriva dopo quella a "letto": non conta.
        assertFalse(s.valida(le))
        assertTrue(s.valida(letto))
        // Ricerca cancellata: nemmeno l'ultima vale più.
        s.annulla()
        assertFalse(s.valida(letto))
    }

    @Test
    fun `l'attesa dopo l'ultima lettera`() {
        assertEquals(300L, RicercaFaccende.ATTESA_MS)
    }
}
