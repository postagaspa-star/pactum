package eu.stgm.pactum.figlio.studio

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.faccende.BloccoEStudio
import eu.stgm.pactum.figlio.faccende.FermatoDuranteBlocco
import eu.stgm.pactum.figlio.faccende.Istante
import eu.stgm.pactum.figlio.faccende.MemoriaBlocco
import eu.stgm.pactum.figlio.faccende.PermessiRevocati
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.sessione.EsitiSessioni
import eu.stgm.pactum.figlio.sessione.EsitoAvvio
import eu.stgm.pactum.figlio.sessione.MemoriaSessioni
import eu.stgm.pactum.figlio.sessione.MotivoBarriera
import eu.stgm.pactum.figlio.sessione.SvoltaLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (0.18, contratto v4.0, parte C) Lo Studio con il resto del telefono: le
 * risposte del server, i corpi, i testi, la barriera, il blocco che aspetta,
 * le sessioni chiuse alla partenza e non avviate durante, le manomissioni.
 */
class StudioIntornoTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val min = 60_000L
    private fun t(ore: Int, minuti: Int = 0) =
        ZonedDateTime.of(LocalDate.parse("2026-10-08"), LocalTime.of(ore, minuti), roma).toInstant().toEpochMilli()

    // --- Le risposte del server --------------------------------------------------------

    @Test
    fun `la chiusura - presa, già chiusa, rifiutata coi numeri, persa, senza rete`() {
        assertTrue(EsitiStudio.chiusura(true, 200, """{ "id": 41, "inizio_ts": "2026-10-08T13:00:00+00:00", "fine_ts": "2026-10-08T15:00:00+00:00" }""") is EsitoChiusura.Chiusa)
        assertTrue(EsitiStudio.chiusura(false, 409, """{ "detail": { "errore": "gia_chiuso", "studio": { "id": 41, "inizio_ts": "2026-10-08T13:00:00+00:00" } } }""") is EsitoChiusura.GiaChiusa)
        val poca = EsitiStudio.chiusura(false, 409, """{ "detail": { "errore": "attivita_insufficiente", "minuti": 45, "minimi": 60 } }""") as EsitoChiusura.Rifiutata
        assertEquals(EsitiStudio.ATTIVITA_INSUFFICIENTE, poca.motivo)
        assertEquals(45, poca.minuti)
        assertEquals(60, poca.minimi)
        val presto = EsitiStudio.chiusura(false, 409, """{ "detail": { "errore": "troppo_presto", "chiudibile_dal": "2026-10-08T14:00:00+00:00" } }""") as EsitoChiusura.Rifiutata
        assertEquals(EsitiStudio.TROPPO_PRESTO, presto.motivo)
        assertEquals(EsitoChiusura.NonTrovata, EsitiStudio.chiusura(false, 404, """{ "detail": "studio non trovato" }"""))
        assertEquals(EsitoChiusura.ServerVecchio, EsitiStudio.chiusura(false, 404, """{ "detail": "Not Found" }"""))
        assertEquals(EsitoChiusura.ServerVecchio, EsitiStudio.chiusura(false, 405, null))
        assertEquals(EsitoChiusura.SenzaRete, EsitiStudio.chiusura(false, 0, null))
        assertEquals(EsitoChiusura.Scollegato, EsitiStudio.chiusura(false, 401, null))
        assertEquals(EsitoChiusura.Scollegato, EsitiStudio.chiusura(false, 409, """{ "errore": "dispositivo_revocato" }"""))
        assertEquals(EsitoChiusura.Errore, EsitiStudio.chiusura(false, 503, null))
    }

    @Test
    fun `l'avvio a mano - i no del server`() {
        for (no in listOf("studio_non_approvato", "troppo_tardi", "blocco_faccende", "avvio_scaduto")) {
            assertEquals(EsitoAvvioStudio.Rifiutato(no), EsitiStudio.avvio(false, 409, """{ "detail": { "errore": "$no" } }"""))
        }
        assertTrue(EsitiStudio.avvio(true, 201, """{ "id": 50, "inizio_ts": "2026-10-08T12:00:00+00:00" }""") is EsitoAvvioStudio.Avviato)
        assertEquals(EsitoAvvioStudio.ServerVecchio, EsitiStudio.avvio(false, 404, null))
        assertEquals(EsitoAvvioStudio.SenzaRete, EsitiStudio.avvio(false, 0, null))
    }

    @Test
    fun `i tratti e la proposta`() {
        assertEquals(EsitoTratti.CONSEGNATI, EsitiStudio.tratti(true, 200, "{}"))
        assertEquals(EsitoTratti.SCARTATI, EsitiStudio.tratti(false, 422, null))
        assertEquals(EsitoTratti.SERVER_VECCHIO, EsitiStudio.tratti(false, 405, null))
        assertEquals(EsitoProposta.No("orari_impossibili"), EsitiStudio.proposta(false, 422, """{ "detail": { "errore": "orari_impossibili" } }"""))
        assertEquals(EsitoProposta.No("niente_da_ritirare"), EsitiStudio.proposta(false, 409, """{ "errore": "niente_da_ritirare" }"""))
        val fatta = EsitiStudio.proposta(true, 200, """{ "stato": "in_attesa", "versione": 2, "approvata": null, "in_attesa": { "giorni": ["lun"] } }""")
        assertEquals(StatiConfigStudio.IN_ATTESA, (fatta as EsitoProposta.Fatta).config?.stato)
    }

    // --- I corpi ---------------------------------------------------------------------------

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `il corpo della chiusura - con l'id nel percorso, o col giorno, o con la chiave`() {
        val conId = json(CorpiStudio.chiudi(ChiusuraLocale("c1", RifStudio(id = 41, giorno = "2026-10-08"), t(15), t(16, 40), "Matematica e storia"), emptyList(), Istante(0, 0, 1)))
        assertEquals("c1", conId["chiave"]!!.jsonPrimitive.content)
        assertEquals(t(16, 40).toString(), conId["ts_device"]!!.jsonPrimitive.content)
        assertEquals("Matematica e storia", conId["dichiarazione"]!!.jsonPrimitive.content)
        assertNull(conId["studio"])
        assertNull("senza tratti, niente campo", conId["tratti"])
        val conGiorno = json(CorpiStudio.chiudi(ChiusuraLocale("c2", RifStudio(giorno = "2026-10-08"), t(15), t(16, 40), "Matematica e storia"), emptyList(), Istante(0, 0, 1)))
        assertEquals("2026-10-08", (conGiorno["studio"] as JsonObject)["giorno"]!!.jsonPrimitive.content)
        val conChiave = json(CorpiStudio.chiudi(ChiusuraLocale("c3", RifStudio(chiave = "k1", giorno = "2026-10-08"), t(14), t(16, 40), "Matematica e storia"), emptyList(), Istante(0, 0, 1)))
        assertEquals("k1", (conChiave["studio"] as JsonObject)["chiave"]!!.jsonPrimitive.content)
        assertNull((conChiave["studio"] as JsonObject)["giorno"])
    }

    @Test
    fun `il corpo dell'avvio a mano`() {
        val o = json(CorpiStudio.avvia(AvvioManuale("k1", t(14), "2026-10-08")))
        assertEquals("k1", o["chiave"]!!.jsonPrimitive.content)
        assertEquals(t(14).toString(), o["ts_device"]!!.jsonPrimitive.content)
    }

    @Test
    fun `la proposta - solo i campi che cambiano, telefono intero`() {
        val base = ContenutoStudio(app = listOf("com.duolingo"))
        assertNull("niente di cambiato", CorpiStudio.proposta(base, base))
        val soloOra = json(CorpiStudio.proposta(base.copy(inizio = "15:30"), base)!!)
        assertEquals(setOf("inizio"), soloOra.keys)
        val lista = json(CorpiStudio.proposta(base.copy(app = listOf("com.duolingo", "gruppo:apk"), nomi = mapOf("com.duolingo" to "Duolingo")), base)!!)
        assertEquals(setOf("telefono"), lista.keys)
        val prima = json(CorpiStudio.proposta(ContenutoStudio(), null)!!)
        assertEquals(setOf("giorni", "inizio", "chiusura_minima", "minuti_minimi", "telefono"), prima.keys)
    }

    // --- I testi -----------------------------------------------------------------------------

    private fun studio(chiudibileDal: Long? = t(16), minimi: Int = 60) = StudioAttivo(
        rif = RifStudio(id = 41), origine = OriginiStudio.AUTOMATICA, giorno = "2026-10-08", inizio = t(15), contaDal = t(15),
        chiudibileDal = chiudibileDal, minutiMinimi = minimi, app = emptyList(), nomi = emptyMap(), mezzanotte = t(23, 59), dalServer = true,
    )

    @Test
    fun `la notifica fissa - Studio dalle 15 · 42 min su 60 · si chiude dopo le 16`() {
        assertEquals("Studio dalle 15:00 · 42 min su 60 · si chiude dopo le 16:00", TestoStudio.stato(studio(), 42, false, roma))
        assertEquals("Studio dalle 15:00 · 65 min su 60 · puoi chiuderlo", TestoStudio.stato(studio(), 65, true, roma))
        assertEquals("Studio dalle 15:00 · 10 min su 45", TestoStudio.stato(studio(chiudibileDal = null, minimi = 45), 10, false, roma))
    }

    @Test
    fun `il timer, i giorni e il riepilogo`() {
        assertEquals("0:00", TestoStudio.durata(0))
        assertEquals("12:34", TestoStudio.durata(754))
        assertEquals("1:02:03", TestoStudio.durata(3723))
        assertEquals("dal lunedì al venerdì", TestoStudio.giorni(GIORNI_FERIALI))
        assertEquals("tutti i giorni", TestoStudio.giorni(GIORNI_STUDIO))
        assertEquals("lunedì, mercoledì e venerdì", TestoStudio.giorni(listOf("ven", "lun", "mer")))
        assertEquals("sabato e domenica", TestoStudio.giorni(listOf("sab", "dom")))
        val r = TestoStudio.riepilogo(
            listOf(
                Triple(TipiTratto.COMPITI, "matematica", 1800L),
                Triple(TipiTratto.LAVORI_DI_CASA, null, 600L),
                Triple(TipiTratto.ALTRO, "allenamento", 1500L),
                Triple(TipiTratto.COMPITI, "matematica", 120L),
                Triple(TipiTratto.COMPITI, null, 30L),
            ),
        )
        assertEquals("Compiti (matematica) 32 min · Lavori di casa 10 min · allenamento 25 min", r)
    }

    // --- La barriera ---------------------------------------------------------------------------

    private fun decidi(s: StudioAttivo?, app: String?, sempre: Set<String> = setOf("eu.stgm.pactum.figlio"), chiamata: Boolean = false) =
        GuardiaStudio.decidi(
            studio = s, oraServer = t(15, 30), primoPiano = app, schermoAcceso = true, sbloccato = true,
            mostraSopra = true, accessoUso = true, inChiamata = chiamata, sempreUsabili = sempre,
            contaNellUso = { true }, nelGruppoApk = { false },
        )

    @Test
    fun `la barriera dello Studio - copre fuori lista, lascia la lista e le sempre usabili`() {
        val conLista = studio().copy(app = listOf("com.spaggiari.classevivastudenti"))
        assertTrue(decidi(conLista, "com.zhiliaoapp.musically").copri)
        assertFalse(decidi(conLista, "com.spaggiari.classevivastudenti").copri)
        assertFalse(decidi(conLista, "eu.stgm.pactum.figlio").copri)
        assertFalse("durante una chiamata no, come le sessioni", decidi(conLista, "com.zhiliaoapp.musically", chiamata = true).copri)
        assertFalse("senza Studio niente", decidi(null, "com.zhiliaoapp.musically").copri)
    }

    @Test
    fun `una lista vuota - solo le sempre usabili, si copre tutto il resto`() {
        val vuota = studio()
        val d = decidi(vuota, "com.whatsapp")
        assertTrue(d.copri)
        assertEquals(MotivoBarriera.FUORI_SESSIONE, d.motivo)
        assertFalse(decidi(vuota, "eu.stgm.pactum.figlio").copri)
    }

    @Test
    fun `la fotocamera aperta da Pactum resta usabile`() {
        assertFalse(decidi(studio(), "com.android.camera", sempre = setOf("eu.stgm.pactum.figlio", "com.android.camera")).copri)
    }

    // --- Il blocco aspetta lo Studio --------------------------------------------------------------

    @Test
    fun `durante lo Studio il blocco aspetta, e parte a fine Studio`() {
        assertFalse(BloccoEStudio.applicato(bloccoDovuto = true, inStudio = true))
        assertTrue(BloccoEStudio.rimandato(bloccoDovuto = true, inStudio = true))
        assertTrue(BloccoEStudio.applicato(bloccoDovuto = true, inStudio = false))
        assertFalse(BloccoEStudio.rimandato(bloccoDovuto = true, inStudio = false))
        assertFalse(BloccoEStudio.applicato(bloccoDovuto = false, inStudio = true))
    }

    // --- Sessioni -------------------------------------------------------------------------------------

    @Test
    fun `alla partenza la sessione normale in corso si chiude all'inizio dello Studio`() {
        val svolta = SvoltaLocale(id = 12, sessioneId = 3, nome = "Gioco", app = listOf("com.supercell.brawlstars"), inizio = t(14), finePrevista = t(17))
        val m = MemoriaSessioni(svolte = listOf(svolta))
        val (dopo, chiusura) = m.conTermineAl(al = t(15), adesso = t(15, 2))
        assertEquals(t(15), chiusura?.tsDevice)
        assertEquals(12L, chiusura?.svoltaId)
        assertNull(dopo.inCorso(t(15, 2)))
        assertEquals(t(15), dopo.svolte.single().fine)
        // Una sessione iniziata dopo l'inizio dello Studio non si tocca.
        val dopoInizio = MemoriaSessioni(svolte = listOf(svolta.copy(inizio = t(15, 1))))
        assertNull(dopoInizio.conTermineAl(al = t(15), adesso = t(15, 2)).second)
    }

    @Test
    fun `durante lo Studio le sessioni non si avviano - 409 studio_in_corso`() {
        assertEquals(EsitoAvvio.StudioInCorso, EsitiSessioni.avvio(false, 409, """{ "detail": { "errore": "studio_in_corso" } }"""))
    }

    // --- Manomissioni ---------------------------------------------------------------------------------

    @Test
    fun `Pactum fermato durante lo Studio - fermato_durante_studio`() {
        val d = FermatoDuranteBlocco.dettagli(FermatoDuranteBlocco.Uscita(t(15, 10), 10), t(15, 40), duranteStudio = true)
        assertEquals("fermato_durante_studio", d["sotto_tipo"]!!.jsonPrimitive.content)
        assertEquals("30", d["minuti"]!!.jsonPrimitive.content)
        assertEquals("fermato_durante_blocco", FermatoDuranteBlocco.dettagli(FermatoDuranteBlocco.Uscita(t(15, 10), 10), t(15, 40))["sotto_tipo"]!!.jsonPrimitive.content)
    }

    @Test
    fun `permesso tolto durante lo Studio - durante studio`() {
        val e = PermessiRevocati.decidi(usoNoto = true, uso = true, sopraNoto = true, sopra = false, bloccoAttivo = false, inStudio = true)
        assertEquals(listOf(MemoriaBlocco.PERMESSO_MOSTRA_SOPRA), e.daSegnalare)
        val d = PermessiRevocati.dettagli(MemoriaBlocco.PERMESSO_MOSTRA_SOPRA, inStudio = true)
        assertEquals("studio", d["durante"]!!.jsonPrimitive.content)
        assertNull(PermessiRevocati.dettagli(MemoriaBlocco.PERMESSO_ACCESSO_USO, inStudio = false)["durante"])
        // Fuori da blocco e Studio "Mostra sopra le altre app" non è una manomissione (come prima).
        assertTrue(PermessiRevocati.decidi(true, true, true, false, bloccoAttivo = false, inStudio = false).daSegnalare.isEmpty())
    }

    // --- Notifiche e patto ---------------------------------------------------------------------------

    @Test
    fun `le notifiche dello Studio - studio_chiuso e studio_risposta rileggono lo Studio`() {
        assertEquals(setOf("studio_chiuso", "studio_risposta"), TipiNotificaStudio.CAMBIANO_LO_STUDIO)
        assertEquals(MainActivity.DEST_SESSIONI, AvvisiLocali.destinazioneTipo(TipiNotificaStudio.STUDIO_RISPOSTA))
        assertEquals(MainActivity.DEST_OGGI, AvvisiLocali.destinazioneTipo(TipiNotificaStudio.STUDIO_CHIUSO))
    }

    @Test
    fun `il patto porta lo studio - senza il campo è un server vecchio`() {
        val json = Json { ignoreUnknownKeys = true }
        val conStudio = json.decodeFromString(
            Patto.serializer(),
            """{ "studio": { "config": { "stato": "approvata", "versione": 1, "approvata": { "giorni": ["lun"], "inizio": "15:00", "chiusura_minima": "16:00", "minuti_minimi": 60, "telefono": { "app": [] } } }, "in_corso": null, "prossime_partenze": [] } }""",
        )
        assertEquals(StatiConfigStudio.APPROVATA, conStudio.studio?.config?.stato)
        val senza = json.decodeFromString(Patto.serializer(), """{ "regole": [] }""")
        assertNull(senza.studio)
    }
}
