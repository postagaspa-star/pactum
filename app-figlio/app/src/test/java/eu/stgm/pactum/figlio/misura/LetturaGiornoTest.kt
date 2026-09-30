package eu.stgm.pactum.figlio.misura

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.valutatore.Sforamento
import eu.stgm.pactum.figlio.valutatore.Valutatore
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * (0.9) Le sequenze del verificatore, lette come le legge il telefono: le
 * stesse finestre (innesco sulle 12 ore prima di mezzanotte, poi il giorno) e
 * un archivio che, come queryEvents, esclude la fine. Il punto: nessuno
 * sforamento notturno falso per il padre.
 */
class LetturaGiornoTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val min = 60_000L
    private val tuttiIGiorni = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"

    private class Voce(val tipo: Int, val pacchetto: String?, val classe: String?, val istante: Long)

    private fun ms(testo: String): Long = LocalDateTime.parse(testo).atZone(roma).toInstant().toEpochMilli()

    private fun app(tipo: Int, pacchetto: String, quando: String) = Voce(tipo, pacchetto, "Main", ms(quando))

    private fun sistema(tipo: Int, quando: String) = Voce(tipo, "android", null, ms(quando))

    /** Come queryEvents: gli eventi con istante in [da, a), in ordine di tempo. */
    private fun archivio(voci: List<Voce>): ScorriEventi = { da, a, azione ->
        voci.filter { it.istante >= da && it.istante < a }
            .sortedBy { it.istante }
            .forEach { azione(it.tipo, it.pacchetto, it.classe, it.istante) }
    }

    private fun leggi(voci: List<Voce>, giorno: String, adesso: String): LetturaGiorno =
        Sessioni.giorno(LocalDate.parse(giorno), roma, ms(adesso), archivio(voci))

    private fun fascia(dalle: String, alle: String) = Regola(
        id = 9,
        tipo = TipiRegola.FASCIA_ORARIA,
        parametri = buildJsonObject {
            put("dalle", dalle)
            put("alle", alle)
            putJsonArray("giorni") { tuttiIGiorni.forEach { add(it) } }
        },
    )

    /** I minuti di telefono dentro la fascia, oggi, come li conta la sentinella. */
    private fun minutiInFascia(lettura: LetturaGiorno, dalle: String, alle: String, adesso: String): Long =
        Valutatore.intervalliProibitiOggi(LocalTime.parse(dalle), LocalTime.parse(alle), tuttiIGiorni, ms(adesso), roma)
            .sumOf { lettura.millisNellIntervallo(it.inizio, it.fine) { true } } / 60_000

    /** Gli sforamenti della fascia, come li manderebbe la sentinella al padre. */
    private fun sforamenti(lettura: LetturaGiorno, dalle: String, alle: String, adesso: String): List<Sforamento> =
        Valutatore.valuta(
            regole = listOf(fascia(dalle, alle)),
            bonusOggiPerRegola = emptyMap(),
            usoMinutiEtichetta = { 0L },
            usoMinutiIntervallo = { da, a -> lettura.millisNellIntervallo(da, a) { true } / 60_000 },
            now = ms(adesso),
            zona = roma,
        )

    // --- 1. una STOPPED o PAUSED senza nessuna RESUMED ---------------------------

    @Test
    fun `Instagram con la sola STOPPED alle 9_28 non e' in primo piano`() {
        val voci = listOf(app(Sessioni.STOP, instagram, "2026-09-30T09:28:00"))
        val lettura = leggi(voci, "2026-09-30", "2026-09-30T10:00:00")
        assertEquals(0L, minutiInFascia(lettura, "22:00", "07:00", "2026-09-30T10:00:00"))
        assertEquals(emptyList<Sforamento>(), sforamenti(lettura, "22:00", "07:00", "2026-09-30T10:00:00"))
        // Stessa regola per i minuti per app e il totale: i tre numeri restano coerenti.
        assertEquals(emptyList<UsoApp>(), lettura.perApp)
    }

    @Test
    fun `una PAUSED senza RESUMED, nemmeno nell'innesco, non conta`() {
        val voci = listOf(
            // La RESUMED è di più di 12 ore prima di mezzanotte: fuori dall'innesco.
            app(Sessioni.RIPRESA, youtube, "2026-09-29T08:00:00"),
            app(Sessioni.PAUSA, youtube, "2026-09-30T06:30:00"),
        )
        val lettura = leggi(voci, "2026-09-30", "2026-09-30T10:00:00")
        assertEquals(emptyList<UsoApp>(), lettura.perApp)
        assertEquals(emptyList<Sforamento>(), sforamenti(lettura, "22:00", "07:00", "2026-09-30T10:00:00"))
    }

    @Test
    fun `YouTube aperto a cavallo della mezzanotte conta i 30 minuti di oggi`() {
        val voci = listOf(
            app(Sessioni.RIPRESA, youtube, "2026-09-29T23:50:00"),
            app(Sessioni.PAUSA, youtube, "2026-09-30T00:30:00"),
        )
        val oggi = leggi(voci, "2026-09-30", "2026-09-30T08:00:00")
        assertEquals(listOf(UsoApp(youtube, 30 * min)), oggi.perApp)
        assertEquals(30L, minutiInFascia(oggi, "22:00", "07:00", "2026-09-30T08:00:00"))
        // Lo sforamento c'è ed è della fascia partita ieri sera.
        val s = sforamenti(oggi, "22:00", "07:00", "2026-09-30T08:00:00").single()
        assertEquals(30, s.minutiOltre)
        assertEquals("2026-09-29", s.giornoAncora)
        // E ieri ha i suoi 10 minuti.
        assertEquals(listOf(UsoApp(youtube, 10 * min)), leggi(voci, "2026-09-29", "2026-09-30T08:00:00").perApp)
    }

    // --- 2. una pausa persa: la sessione rimasta aperta ------------------------

    @Test
    fun `YouTube senza pausa dalle 8 si ferma allo schermo spento, niente fascia alle 21`() {
        val voci = listOf(
            app(Sessioni.RIPRESA, youtube, "2026-09-30T08:00:00"),
            sistema(Sessioni.SCHERMO_SPENTO, "2026-09-30T08:40:00"),
            sistema(Sessioni.BLOCCO, "2026-09-30T08:40:01"),
        )
        val lettura = leggi(voci, "2026-09-30", "2026-09-30T22:00:00")
        assertEquals(0L, minutiInFascia(lettura, "21:00", "23:00", "2026-09-30T22:00:00"))
        assertEquals(emptyList<Sforamento>(), sforamenti(lettura, "21:00", "23:00", "2026-09-30T22:00:00"))
        assertEquals(listOf(UsoApp(youtube, 40 * min)), lettura.perApp)
    }

    @Test
    fun `YouTube senza pausa e senza nessun altro evento non va oltre 12 ore`() {
        val voci = listOf(app(Sessioni.RIPRESA, youtube, "2026-09-30T08:00:00"))
        val lettura = leggi(voci, "2026-09-30", "2026-09-30T22:00:00")
        assertEquals(0L, minutiInFascia(lettura, "21:00", "23:00", "2026-09-30T22:00:00"))
        assertEquals(emptyList<Sforamento>(), sforamenti(lettura, "21:00", "23:00", "2026-09-30T22:00:00"))
        assertEquals(listOf(UsoApp(youtube, 12 * 60 * min)), lettura.perApp)
    }

    @Test
    fun `una sessione chiusa dopo piu' di 12 ore non entra nella fascia della sera`() {
        // Una RESUMED alle 8 e una PAUSED alle 23:30, niente in mezzo: eventi
        // persi. La 0.8 nella fascia dava 0 (guardava solo le 12 ore prima).
        val voci = listOf(
            app(Sessioni.RIPRESA, youtube, "2026-09-30T08:00:00"),
            app(Sessioni.PAUSA, youtube, "2026-09-30T23:30:00"),
        )
        val lettura = leggi(voci, "2026-09-30", "2026-09-30T23:45:00")
        assertEquals(0L, minutiInFascia(lettura, "21:00", "23:00", "2026-09-30T23:45:00"))
        assertEquals(emptyList<Sforamento>(), sforamenti(lettura, "21:00", "23:00", "2026-09-30T23:45:00"))
        assertEquals(listOf(UsoApp(youtube, 12 * 60 * min)), lettura.perApp)
    }

    @Test
    fun `riaperta la sera dopo una pausa persa conta solo la sera`() {
        val voci = listOf(
            app(Sessioni.RIPRESA, youtube, "2026-09-30T08:00:00"),
            sistema(Sessioni.SCHERMO_SPENTO, "2026-09-30T08:40:00"),
            app(Sessioni.RIPRESA, youtube, "2026-09-30T21:10:00"),
            app(Sessioni.PAUSA, youtube, "2026-09-30T21:40:00"),
        )
        val lettura = leggi(voci, "2026-09-30", "2026-09-30T22:00:00")
        assertEquals(30L, minutiInFascia(lettura, "21:00", "23:00", "2026-09-30T22:00:00"))
        assertEquals(listOf(UsoApp(youtube, 70 * min)), lettura.perApp)
    }

    @Test
    fun `una sessione aperta prima di mezzanotte si ferma allo schermo spento di ieri sera`() {
        val voci = listOf(
            app(Sessioni.RIPRESA, youtube, "2026-09-29T22:00:00"),
            sistema(Sessioni.SCHERMO_SPENTO, "2026-09-29T22:30:00"),
        )
        val lettura = leggi(voci, "2026-09-30", "2026-09-30T08:00:00")
        assertEquals(emptyList<UsoApp>(), lettura.perApp)
        assertEquals(emptyList<Sforamento>(), sforamenti(lettura, "22:00", "07:00", "2026-09-30T08:00:00"))
    }

    // --- 3. il millisecondo di mezzanotte ---------------------------------------

    @Test
    fun `TikTok chiuso alle 23_59_59_999 non resta aperto dopo mezzanotte`() {
        val voci = listOf(
            app(Sessioni.RIPRESA, tiktok, "2026-09-29T23:47:00"),
            app(Sessioni.STOP, tiktok, "2026-09-29T23:59:59.999"),
        )
        val oggi = leggi(voci, "2026-09-30", "2026-09-30T01:57:00")
        assertEquals(0L, minutiInFascia(oggi, "00:26", "17:13", "2026-09-30T01:57:00"))
        assertEquals(emptyList<Sforamento>(), sforamenti(oggi, "00:26", "17:13", "2026-09-30T01:57:00"))
        assertEquals(emptyList<UsoApp>(), oggi.perApp)
        // Ieri la sessione c'è tutta, fino all'ultimo millisecondo.
        assertEquals(listOf(UsoApp(tiktok, 13 * min - 1)), leggi(voci, "2026-09-29", "2026-09-30T01:57:00").perApp)
    }

    @Test
    fun `un evento alle 0_00_00_000 e' del giorno nuovo, non di ieri`() {
        val voci = listOf(
            app(Sessioni.RIPRESA, tiktok, "2026-09-29T23:50:00"),
            app(Sessioni.PAUSA, tiktok, "2026-09-30T00:00:00"),
        )
        assertEquals(listOf(UsoApp(tiktok, 10 * min)), leggi(voci, "2026-09-29", "2026-09-30T08:00:00").perApp)
        assertEquals(emptyList<UsoApp>(), leggi(voci, "2026-09-30", "2026-09-30T08:00:00").perApp)
    }
}
