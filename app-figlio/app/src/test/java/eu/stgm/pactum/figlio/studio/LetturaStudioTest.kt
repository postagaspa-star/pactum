package eu.stgm.pactum.figlio.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (0.18, contratto v4.0, parte C) Le forme dello Studio lette con pazienza,
 * e le regole che il telefono controlla da solo: i campi di una proposta, la
 * parola, la dichiarazione, le partenze (fuso e cambio dell'ora).
 */
class LetturaStudioTest {

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private val risposta = """
        { "config": { "stato": "approvata", "versione": 4,
            "approvata": {
              "giorni": ["lun", "mar", "mer", "gio", "ven"],
              "inizio": "15:00", "chiusura_minima": "16:00", "minuti_minimi": 60,
              "telefono": { "app": ["com.spaggiari.classevivastudenti", "gruppo:apk"],
                            "nomi": { "com.spaggiari.classevivastudenti": "ClasseViva Studenti" } },
              "computer": { "programmi": ["exe:winword.exe", "sito:classeviva.it"],
                            "nomi": { "exe:winword.exe": "Word" },
                            "firme": { "exe:winword.exe": "Microsoft Corporation" } },
              "orari_dal": "2026-10-08", "approvata_ts": "2026-10-07T10:00:00+00:00", "decisa_da": { "id": 2, "nome": "Mamma" } },
            "in_attesa": null, "motivazione": null },
          "in_corso": { "id": 41, "origine": "automatica", "giorno": "2026-10-08", "chiave": null,
            "inizio_ts": "2026-10-08T13:00:00+00:00", "avviato_da": null,
            "partenze": [ { "giorno": "2026-10-08", "inizio_ts": "2026-10-08T13:00:00+00:00",
                            "chiudibile_dal": "2026-10-08T14:00:00+00:00", "minuti_minimi": 60 } ],
            "conta_dal": "2026-10-08T13:00:00+00:00", "chiudibile_dal": "2026-10-08T14:00:00+00:00",
            "minuti_minimi": 60, "minuti_attivita": 42, "minuti_alla_chiusura": null, "chiudibile": false,
            "tratti": [ { "id": "a1b2", "dispositivo_id": 1, "tipo": "compiti", "parola": null, "faccenda_id": null,
                          "inizio": 1791291600000, "fine": 1791293400000, "ora_agganciata": true,
                          "secondi": 1800, "secondi_contati": 1800, "minuti": 30, "esito": "finito", "conta": true } ],
            "sessione_chiusa": null, "fine_ts": null, "chiusura": null, "chiusa_da": null,
            "dichiarazione": null, "motivo": null, "in_corso": true },
          "prossime_partenze": [ { "giorno": "2026-10-09", "inizio_ts": "2026-10-09T13:00:00+00:00",
                                   "chiudibile_dal": "2026-10-09T14:00:00+00:00", "minuti_minimi": 60 } ],
          "recenti": [] }
    """.trimIndent()

    @Test
    fun `la risposta di GET api studio`() {
        val s = LetturaStudio.statoDaCorpo(risposta)!!
        val c = s.config!!
        assertEquals(StatiConfigStudio.APPROVATA, c.stato)
        assertEquals(4, c.versione)
        val a = c.approvata!!
        assertEquals(GIORNI_FERIALI, a.giorni)
        assertEquals("15:00", a.inizio)
        assertEquals(listOf("com.spaggiari.classevivastudenti", "gruppo:apk"), a.app)
        assertEquals("ClasseViva Studenti", a.nomi["com.spaggiari.classevivastudenti"])
        assertEquals(listOf("exe:winword.exe", "sito:classeviva.it"), a.programmi)
        assertEquals("Mamma", a.decisaDa)
        assertEquals("2026-10-08", a.orariDal)
        val studio = s.inCorso!!
        assertEquals(41L, studio.id)
        assertEquals(ms("2026-10-08T13:00:00+00:00"), studio.inizio)
        assertEquals(42, studio.minutiAttivita)
        assertEquals(ms("2026-10-08T14:00:00+00:00"), studio.chiudibileDal)
        assertEquals(1, studio.partenze.size)
        val t = studio.tratti.single()
        assertEquals("a1b2", t.id)
        assertEquals(1791291600000L, t.inizio)
        assertEquals(1800L, t.secondi)
        assertEquals(true, t.conta)
        assertNull(studio.app)
        assertEquals("2026-10-09", s.prossime.single().giorno)
        assertTrue(s.recenti!!.isEmpty())
    }

    @Test
    fun `il patto ha lo studio senza i recenti - restano quelli di prima`() {
        val s = LetturaStudio.statoDaCorpo("""{ "config": { "stato": "nessuna", "versione": 0, "approvata": null, "in_attesa": null }, "in_corso": null, "prossime_partenze": [] }""")!!
        assertNull(s.recenti)
        assertNull(s.inCorso)
        assertTrue(s.prossime.isEmpty())
        assertEquals(StatiConfigStudio.NESSUNA, s.config!!.stato)
    }

    @Test
    fun `campi scritti male non fanno cadere niente`() {
        assertNull(LetturaStudio.statoDaCorpo("non è json"))
        assertNull(LetturaStudio.statoDaCorpo("[]"))
        val s = LetturaStudio.statoDaCorpo("""{ "config": 5, "in_corso": { "id": "x" }, "prossime_partenze": [ { "giorno": "2026-10-09" }, 7 ] }""")!!
        assertNull(s.config)
        assertNull(s.inCorso)
        assertTrue(s.prossime.isEmpty())
    }

    @Test
    fun `le liste congelate dello Studio in corso - nella forma del server v4_0 (liste-telefono)`() {
        val corpo = """{ "config": null, "prossime_partenze": [],
            "in_corso": { "id": 41, "inizio_ts": "2026-10-08T13:00:00+00:00", "fine_ts": null,
              "liste": { "telefono": { "app": ["com.duolingo", "gruppo:apk"], "nomi": { "com.duolingo": "Duolingo" } },
                         "computer": { "programmi": ["exe:winword.exe"], "nomi": {}, "firme": {} } } } }"""
        val studio = LetturaStudio.statoDaCorpo(corpo)!!.inCorso!!
        assertEquals(listOf("com.duolingo", "gruppo:apk"), studio.app)
        assertEquals("Duolingo", studio.nomi["com.duolingo"])
        // Anche la forma corta (telefono da solo) resta letta.
        val corto = """{ "in_corso": { "id": 42, "inizio_ts": "2026-10-08T13:00:00+00:00", "telefono": { "app": ["com.duolingo"] } } }"""
        assertEquals(listOf("com.duolingo"), LetturaStudio.statoDaCorpo(corto)!!.inCorso!!.app)
    }

    @Test
    fun `uno Studio chiuso non è in corso`() {
        val chiuso = """{ "config": null, "in_corso": { "id": 41, "inizio_ts": "2026-10-08T13:00:00+00:00", "fine_ts": "2026-10-08T15:00:00+00:00", "chiusura": "figlio" }, "prossime_partenze": [] }"""
        assertNull(LetturaStudio.statoDaCorpo(chiuso)!!.inCorso)
    }

    @Test
    fun `gli errori dello Studio nelle due forme, coi loro numeri`() {
        val troppoPresto = """{ "detail": { "errore": "troppo_presto", "chiudibile_dal": "2026-10-08T14:00:00+00:00", "studio": { "id": 41, "inizio_ts": "2026-10-08T13:00:00+00:00" } } }"""
        assertEquals("troppo_presto", LetturaStudio.errore(troppoPresto))
        assertEquals(ms("2026-10-08T14:00:00+00:00"), LetturaStudio.istanteNellErrore(troppoPresto, "chiudibile_dal"))
        assertEquals(41L, LetturaStudio.studioNellErrore(troppoPresto)?.id)
        val poca = """{ "errore": "attivita_insufficiente", "minuti": 45, "minimi": 60 }"""
        assertEquals(45L, LetturaStudio.numeroNellErrore(poca, "minuti"))
        assertEquals(60L, LetturaStudio.numeroNellErrore(poca, "minimi"))
    }

    @Test
    fun `le versioni approvate e lo storico`() {
        val v = LetturaStudio.versioni(
            """{ "versioni": [ { "versione": 4, "giorni": ["lun"], "inizio": "15:30", "chiusura_minima": "16:30", "minuti_minimi": 45,
                 "telefono": { "app": ["com.duolingo"] }, "orari_dal": "2026-10-08", "approvata_ts": "2026-10-07T10:00:00+00:00",
                 "decisa_da": { "id": 3, "nome": "Papà" } },
                 { "versione": 1, "giorni": ["lun","mar","mer","gio","ven"], "inizio": "15:00", "chiusura_minima": "16:00", "minuti_minimi": 60,
                   "telefono": { "app": [] }, "orari_dal": "2026-10-02", "approvata_ts": null, "decisa_da": null } ] }""",
        )!!
        assertEquals(listOf(4, 1), v.map { it.versione })
        assertEquals("Papà", v[0].decisaDa)
        assertNull(v[1].decisaDa)
        val p = LetturaStudio.pagina("""{ "svolte": [ { "id": 41, "inizio_ts": "2026-10-08T13:00:00+00:00", "fine_ts": "2026-10-08T15:00:00+00:00", "chiusura": "figlio", "dichiarazione": "Matematica", "minuti_alla_chiusura": 65 } ], "altre": true }""")!!
        assertTrue(p.altre)
        assertEquals(65, p.svolte.single().minuti)
        assertFalse(p.svolte.single().inCorso)
    }

    // --- Le regole -----------------------------------------------------------------------------

    @Test
    fun `una proposta - i campi del contratto`() {
        assertNull(RegoleStudio.controllaProposta(ContenutoStudio()))
        assertEquals(RegoleStudio.ErroreProposta.GIORNI, RegoleStudio.controllaProposta(ContenutoStudio(giorni = emptyList())))
        assertEquals(RegoleStudio.ErroreProposta.INIZIO, RegoleStudio.controllaProposta(ContenutoStudio(inizio = "25:00")))
        assertEquals(RegoleStudio.ErroreProposta.CHIUSURA_PRIMA, RegoleStudio.controllaProposta(ContenutoStudio(inizio = "16:00", chiusuraMinima = "15:00")))
        assertEquals(RegoleStudio.ErroreProposta.MINUTI, RegoleStudio.controllaProposta(ContenutoStudio(minutiMinimi = 9)))
        assertEquals(RegoleStudio.ErroreProposta.MINUTI, RegoleStudio.controllaProposta(ContenutoStudio(minutiMinimi = 601)))
        // Inizio 23:00 con 120 minuti: non si potrebbe chiudere prima di mezzanotte.
        assertEquals(
            RegoleStudio.ErroreProposta.ORARI_IMPOSSIBILI,
            RegoleStudio.controllaProposta(ContenutoStudio(inizio = "23:00", chiusuraMinima = "23:00", minutiMinimi = 120)),
        )
        assertNull(RegoleStudio.controllaProposta(ContenutoStudio(inizio = "22:00", chiusuraMinima = "23:30", minutiMinimi = 90)))
    }

    @Test
    fun `la parola di altro - da 1 a 30 caratteri, senza caratteri invisibili`() {
        assertEquals("allenamento", RegoleStudio.parola("  allenamento "))
        assertNull(RegoleStudio.parola("   "))
        assertNull(RegoleStudio.parola("a".repeat(31)))
        assertEquals("a".repeat(30), RegoleStudio.parola("a".repeat(30)))
        assertNull(RegoleStudio.parola("calcio​"))
        assertEquals("⚽ calcio", RegoleStudio.parola("⚽ calcio"))
    }

    @Test
    fun `la dichiarazione - da 10 a 1000 caratteri dopo aver tolto gli spazi`() {
        assertNull(RegoleStudio.dichiarazione("   corto    "))
        assertEquals("Ho studiato", RegoleStudio.dichiarazione("  Ho studiato  "))
        assertNull(RegoleStudio.dichiarazione("x".repeat(1001)))
        assertEquals(1000, RegoleStudio.dichiarazione("x".repeat(1000))!!.length)
    }

    @Test
    fun `gli orari scritti a mano`() {
        assertEquals("09:00", Orari.normalizza("9"))
        assertEquals("09:30", Orari.normalizza("9.30"))
        assertEquals("15:00", Orari.normalizza("1500"))
        assertEquals("15:05", Orari.normalizza("15:05"))
        assertNull(Orari.normalizza("25"))
        assertNull(Orari.normalizza("abc"))
    }

    @Test
    fun `cambio dell'ora - un'ora che non esiste vale la prima valida dopo, una doppia vale la prima`() {
        val roma = ZoneId.of("Europe/Rome")
        // 29/03/2026: dalle 02:00 si passa alle 03:00.
        val buco = RegoleStudio.istante(LocalDate.parse("2026-03-29"), "02:30", roma)!!
        assertEquals(ZonedDateTime.of(2026, 3, 29, 3, 0, 0, 0, roma).toInstant().toEpochMilli(), buco)
        // 25/10/2026: le 02:30 ci sono due volte; vale la prima (ancora con l'ora legale, +02:00).
        val doppia = RegoleStudio.istante(LocalDate.parse("2026-10-25"), "02:30", roma)!!
        assertEquals(ms("2026-10-25T02:30:00+02:00"), doppia)
    }

    @Test
    fun `le partenze dalla configurazione - giorni, orari_dal e fuso del patto`() {
        val roma = ZoneId.of("Europe/Rome")
        val orari = ContenutoStudio(giorni = listOf("lun", "mer"), orariDal = "2026-10-12")
        assertNull("domenica", RegoleStudio.partenza(LocalDate.parse("2026-10-11"), orari, roma))
        assertNull("lunedì prima di orari_dal no", RegoleStudio.partenza(LocalDate.parse("2026-10-05"), orari, roma))
        val p = RegoleStudio.partenza(LocalDate.parse("2026-10-12"), orari, roma)!!
        assertEquals(ms("2026-10-12T15:00:00+02:00"), p.inizio)
        assertEquals(ms("2026-10-12T16:00:00+02:00"), p.chiudibileDal)
        assertNotNull(RegoleStudio.partenza(LocalDate.parse("2026-10-14"), orari, roma))
        // Dopo il cambio dell'ora le 15:00 restano le 15:00 di Roma.
        assertEquals(ms("2026-10-26T15:00:00+01:00"), RegoleStudio.partenza(LocalDate.parse("2026-10-26"), orari, roma)!!.inizio)
    }
}
