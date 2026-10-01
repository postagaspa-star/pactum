package eu.stgm.pactum.figlio.misura

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.valutatore.IndiceUso
import eu.stgm.pactum.figlio.valutatore.Sforamento
import eu.stgm.pactum.figlio.valutatore.Valutatore
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * (0.11) Il tempo passato in una Sessione nelle sue app non conta: non nei
 * minuti per app, non nel totale, non nelle categorie, non nei limiti e non
 * nelle fasce. Il tempo nelle app fuori dalla lista conta come sempre, anche
 * durante la sessione.
 */
class TempoInSessioneTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val min = 60_000L

    private val classeViva = "eu.spaggiari.classevivafamiglia"
    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"

    private fun ms(testo: String): Long = LocalDateTime.parse(testo).atZone(roma).toInstant().toEpochMilli()

    private fun studio(da: String, a: String, vararg app: String) =
        PeriodoSessione(ms(da), ms(a)) { it in app }

    private class Voce(val tipo: Int, val pacchetto: String?, val istante: Long)

    private fun app(tipo: Int, pacchetto: String, quando: String) = Voce(tipo, pacchetto, ms(quando))

    /** Come queryEvents: gli eventi con istante in [da, a), in ordine di tempo. */
    private fun archivio(voci: List<Voce>): ScorriEventi = { da, a, azione ->
        voci.filter { it.istante >= da && it.istante < a }
            .sortedBy { it.istante }
            .forEach { azione(it.tipo, it.pacchetto, "Main", it.istante) }
    }

    /** La lettura di un giorno come la fa UsageStatsReader, con i periodi di sessione. */
    private fun leggi(voci: List<Voce>, giorno: String, adesso: String, periodi: List<PeriodoSessione>): LetturaGiorno {
        val tutto = Sessioni.giorno(LocalDate.parse(giorno), roma, ms(adesso), archivio(voci))
        val inizio = LocalDate.parse(giorno).atStartOfDay(roma).toInstant().toEpochMilli()
        // Come PeriodiSessione.delGiorno: solo quelli che toccano il giorno letto.
        return TempoInSessione.togli(tutto, periodi.filter { it.inizio < tutto.fine && it.fine > inizio })
    }

    // --- il taglio dei pezzi ---------------------------------------------------------

    @Test
    fun `un pezzo tutto dentro la sessione, in un'app della sessione, non conta`() {
        val pezzo = Sessione(classeViva, ms("2026-10-01T15:10:00"), ms("2026-10-01T15:40:00"))
        val separati = TempoInSessione.separa(listOf(pezzo), listOf(studio("2026-10-01T15:00:00", "2026-10-01T17:00:00", classeViva)))
        assertTrue(separati.contati.isEmpty())
        assertEquals(listOf(pezzo), separati.inSessione)
    }

    @Test
    fun `un'app fuori dalla lista conta come sempre, anche durante la sessione`() {
        val pezzo = Sessione(instagram, ms("2026-10-01T15:10:00"), ms("2026-10-01T15:40:00"))
        val separati = TempoInSessione.separa(listOf(pezzo), listOf(studio("2026-10-01T15:00:00", "2026-10-01T17:00:00", classeViva)))
        assertEquals(listOf(pezzo), separati.contati)
        assertTrue(separati.inSessione.isEmpty())
    }

    @Test
    fun `un pezzo a cavallo dell'inizio e della fine si spezza`() {
        val pezzo = Sessione(classeViva, ms("2026-10-01T14:30:00"), ms("2026-10-01T17:30:00"))
        val separati = TempoInSessione.separa(listOf(pezzo), listOf(studio("2026-10-01T15:00:00", "2026-10-01T17:00:00", classeViva)))
        assertEquals(
            listOf(
                Sessione(classeViva, ms("2026-10-01T14:30:00"), ms("2026-10-01T15:00:00")),
                Sessione(classeViva, ms("2026-10-01T17:00:00"), ms("2026-10-01T17:30:00")),
            ),
            separati.contati,
        )
        assertEquals(listOf(Sessione(classeViva, ms("2026-10-01T15:00:00"), ms("2026-10-01T17:00:00"))), separati.inSessione)
    }

    @Test
    fun `due sessioni sovrapposte non tolgono due volte lo stesso minuto`() {
        val pezzo = Sessione(classeViva, ms("2026-10-01T15:00:00"), ms("2026-10-01T16:00:00"))
        val separati = TempoInSessione.separa(
            listOf(pezzo),
            listOf(
                studio("2026-10-01T14:50:00", "2026-10-01T15:30:00", classeViva),
                studio("2026-10-01T15:20:00", "2026-10-01T15:45:00", classeViva),
            ),
        )
        assertEquals(listOf(Sessione(classeViva, ms("2026-10-01T15:45:00"), ms("2026-10-01T16:00:00"))), separati.contati)
        assertEquals(45 * min, separati.inSessione.sumOf { it.fine - it.inizio })
    }

    @Test
    fun `una sessione con l'app in un'altra sessione non la toglie`() {
        // Due periodi: Studio (ClasseViva) e un altro (YouTube). ClasseViva conta durante il secondo.
        val pezzo = Sessione(classeViva, ms("2026-10-01T18:00:00"), ms("2026-10-01T18:30:00"))
        val separati = TempoInSessione.separa(
            listOf(pezzo),
            listOf(
                studio("2026-10-01T15:00:00", "2026-10-01T16:00:00", classeViva),
                studio("2026-10-01T18:00:00", "2026-10-01T19:00:00", youtube),
            ),
        )
        assertEquals(listOf(pezzo), separati.contati)
    }

    @Test
    fun `senza sessioni la lettura resta quella di sempre`() {
        val voci = listOf(app(Sessioni.RIPRESA, instagram, "2026-10-01T10:00:00"), app(Sessioni.PAUSA, instagram, "2026-10-01T10:30:00"))
        val lettura = leggi(voci, "2026-10-01", "2026-10-01T12:00:00", emptyList())
        assertFalse(lettura.conSessioni)
        assertEquals(listOf(UsoApp(instagram, 30 * min)), lettura.perApp)
        assertTrue(lettura.perAppInSessione.isEmpty())
    }

    // --- il giorno intero: minuti per app, totale, categorie, limiti ----------------------

    private val giornoConSessione = listOf(
        // Prima della sessione: ClasseViva conta.
        app(Sessioni.RIPRESA, classeViva, "2026-10-01T14:40:00"),
        app(Sessioni.PAUSA, classeViva, "2026-10-01T15:20:00"),
        // Durante: Instagram (fuori lista) conta, ClasseViva no.
        app(Sessioni.RIPRESA, instagram, "2026-10-01T15:20:00"),
        app(Sessioni.PAUSA, instagram, "2026-10-01T15:30:00"),
        app(Sessioni.RIPRESA, classeViva, "2026-10-01T15:30:00"),
        app(Sessioni.PAUSA, classeViva, "2026-10-01T16:30:00"),
    )
    private val sessioneStudio = listOf(studio("2026-10-01T15:00:00", "2026-10-01T17:00:00", classeViva))

    @Test
    fun `nel giorno contano solo il tempo fuori sessione e le app fuori lista`() {
        val lettura = leggi(giornoConSessione, "2026-10-01", "2026-10-01T20:00:00", sessioneStudio)
        assertTrue(lettura.conSessioni)
        // ClasseViva: 14:40-15:00 conta (20 min); 15:00-15:20 e 15:30-16:30 no (80 min).
        assertEquals(
            listOf(UsoApp(classeViva, 20 * min), UsoApp(instagram, 10 * min)),
            lettura.perApp,
        )
        assertEquals(listOf(UsoApp(classeViva, 80 * min)), lettura.perAppInSessione)
    }

    @Test
    fun `limiti, totale e categorie non vedono il tempo in sessione`() {
        val lettura = leggi(giornoConSessione, "2026-10-01", "2026-10-01T20:00:00", sessioneStudio)
        val indice = IndiceUso(
            uso = lettura.perApp.map { it.pacchetto to it.millisPrimoPiano },
            categoriaDi = { if (it == instagram) "categoria:social" else "categoria:altro" },
        )
        assertEquals(30L, indice.minuti("totale"))
        assertEquals(20L, indice.minuti(classeViva))
        assertEquals(10L, indice.minuti("categoria:social"))
        assertEquals(20L, indice.minuti("categoria:altro"))
        // Un limite di 30 minuti su ClasseViva non è superato: 100 minuti, ma 80 in sessione.
        val limite = Regola(
            id = 1,
            tipo = TipiRegola.LIMITE_TEMPO,
            parametri = buildJsonObject {
                put("app_o_categoria", classeViva)
                put("minuti_al_giorno", 30)
            },
        )
        val sforamenti = Valutatore.valuta(
            regole = listOf(limite),
            bonusOggiPerRegola = emptyMap(),
            usoMinutiEtichetta = indice::minuti,
            usoMinutiIntervallo = { _, _ -> 0L },
            now = ms("2026-10-01T20:00:00"),
            zona = roma,
        )
        assertEquals(emptyList<Sforamento>(), sforamenti)
    }

    @Test
    fun `una fascia oraria non vede le app della sessione, ma vede quelle fuori`() {
        val fascia = Regola(
            id = 9,
            tipo = TipiRegola.FASCIA_ORARIA,
            parametri = buildJsonObject {
                put("dalle", "15:00")
                put("alle", "17:00")
                putJsonArray("giorni") { listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom").forEach { add(it) } }
            },
        )
        fun sforamenti(voci: List<Voce>): List<Sforamento> {
            val lettura = leggi(voci, "2026-10-01", "2026-10-01T18:00:00", sessioneStudio)
            return Valutatore.valuta(
                regole = listOf(fascia),
                bonusOggiPerRegola = emptyMap(),
                usoMinutiEtichetta = { 0L },
                usoMinutiIntervallo = { da, a -> lettura.millisNellIntervallo(da, a) { true } / 60_000 },
                now = ms("2026-10-01T18:00:00"),
                zona = roma,
            )
        }
        // Solo ClasseViva nella fascia, tutta in sessione: niente fuori regola.
        val soloStudio = listOf(
            app(Sessioni.RIPRESA, classeViva, "2026-10-01T15:10:00"),
            app(Sessioni.PAUSA, classeViva, "2026-10-01T16:50:00"),
        )
        assertEquals(emptyList<Sforamento>(), sforamenti(soloStudio))
        // Instagram nella fascia, anche durante la sessione: fuori regola come sempre.
        val s = sforamenti(giornoConSessione).single()
        assertEquals(10, s.minutiOltre)
    }

    // --- la mezzanotte -----------------------------------------------------------------

    @Test
    fun `una sessione a cavallo della mezzanotte toglie la sua parte da ciascun giorno`() {
        val voci = listOf(
            app(Sessioni.RIPRESA, classeViva, "2026-10-01T23:00:00"),
            app(Sessioni.PAUSA, classeViva, "2026-10-02T01:00:00"),
        )
        val notte = listOf(studio("2026-10-01T23:30:00", "2026-10-02T00:30:00", classeViva))
        val ieri = leggi(voci, "2026-10-01", "2026-10-02T08:00:00", notte)
        val oggi = leggi(voci, "2026-10-02", "2026-10-02T08:00:00", notte)
        // Ieri: 23:00-23:30 conta, 23:30-24:00 no.
        assertEquals(listOf(UsoApp(classeViva, 30 * min)), ieri.perApp)
        assertEquals(listOf(UsoApp(classeViva, 30 * min)), ieri.perAppInSessione)
        // Oggi: 00:00-00:30 no, 00:30-01:00 conta.
        assertEquals(listOf(UsoApp(classeViva, 30 * min)), oggi.perApp)
        assertEquals(listOf(UsoApp(classeViva, 30 * min)), oggi.perAppInSessione)
        assertTrue(ieri.conSessioni && oggi.conSessioni)
    }

    @Test
    fun `l'ultimo minuto di ieri, guardato dopo mezzanotte, resta senza il tempo in sessione`() {
        val voci = listOf(
            app(Sessioni.RIPRESA, classeViva, "2026-10-01T23:50:00"),
            app(Sessioni.PAUSA, classeViva, "2026-10-01T23:59:59.999"),
        )
        val notte = listOf(studio("2026-10-01T23:00:00", "2026-10-02T01:00:00", classeViva))
        // Come la sentinella al primo giro del giorno nuovo: ieri fino al suo ultimo istante.
        val ieri = leggi(voci, "2026-10-01", "2026-10-01T23:59:59.999", notte)
        assertTrue(ieri.perApp.isEmpty())
        assertEquals(10 * min - 1, ieri.perAppInSessione.single().millisPrimoPiano)
    }

    @Test
    fun `un giorno senza la sessione non e' toccato`() {
        val voci = listOf(app(Sessioni.RIPRESA, classeViva, "2026-10-03T10:00:00"), app(Sessioni.PAUSA, classeViva, "2026-10-03T10:30:00"))
        val lettura = leggi(voci, "2026-10-03", "2026-10-03T12:00:00", sessioneStudio)
        assertFalse(lettura.conSessioni)
        assertEquals(listOf(UsoApp(classeViva, 30 * min)), lettura.perApp)
    }

    // --- sessioni_minuti: zero vero, o "non si sa" ------------------------------------------

    private val home = "com.google.android.apps.nexuslauncher"
    private val filtroUso: (String) -> Boolean = { it != home && it != "eu.stgm.pactum.figlio" }

    private fun lettura(voci: List<Voce>, periodi: List<PeriodoSessione>, note: Boolean): LetturaGiorno {
        val tutto = Sessioni.giorno(LocalDate.parse("2026-10-01"), roma, ms("2026-10-01T20:00:00"), archivio(voci))
        return TempoInSessione.togli(tutto, periodi, note)
    }

    @Test
    fun `i minuti in sessione della fotografia, dallo stesso filtro dell'uso`() {
        val conHome = giornoConSessione + listOf(
            app(Sessioni.RIPRESA, home, "2026-10-01T16:30:00"),
            app(Sessioni.PAUSA, home, "2026-10-01T16:40:00"),
        )
        val sessione = listOf(studio("2026-10-01T15:00:00", "2026-10-01T17:00:00", classeViva, home))
        val letta = lettura(conHome, sessione, note = true)
        // 80 minuti di ClasseViva in sessione; i 10 della Home non sono "in sessione".
        assertEquals(80L, UsoContato.minutiInSessione(UsoContato.inSessioneDi(letta), filtroUso))
    }

    @Test
    fun `un giorno senza sessioni ha zero minuti in sessione, se il telefono sa le sue sessioni`() {
        val letta = lettura(giornoConSessione, emptyList(), note = true)
        assertEquals(0L, UsoContato.minutiInSessione(UsoContato.inSessioneDi(letta), filtroUso))
    }

    @Test
    fun `dopo una reinstallazione i minuti in sessione non si sanno finche' il server non riporta le sessioni`() {
        // Il telefono non ha ancora l'archivio: nessun periodo noto, e lo si sa.
        val letta = lettura(giornoConSessione, emptyList(), note = false)
        assertFalse(letta.sessioniNote)
        assertNull(UsoContato.inSessioneDi(letta))
        assertNull(UsoContato.minutiInSessione(UsoContato.inSessioneDi(letta), filtroUso))
        // Riportate dal server: si sanno.
        val dopo = lettura(giornoConSessione, sessioneStudio, note = true)
        assertEquals(80L, UsoContato.minutiInSessione(UsoContato.inSessioneDi(dopo), filtroUso))
    }

    @Test
    fun `i minuti in sessione stanno in un giorno`() {
        val troppi = listOf(UsoApp(classeViva, 25 * 60 * min))
        assertEquals(1440L, UsoContato.minutiInSessione(troppi, filtroUso))
    }
}
