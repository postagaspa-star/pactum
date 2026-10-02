package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.misura.PeriodoSessione
import eu.stgm.pactum.figlio.misura.ScorriEventi
import eu.stgm.pactum.figlio.misura.Sessioni
import eu.stgm.pactum.figlio.misura.TempoInSessione
import eu.stgm.pactum.figlio.servizio.CadenzaSentinella
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * (0.12) "Il tempo sta per finire": a 5 minuti e a 1 minuto dal limite che il
 * ragazzo si è dato (bonus compresi), una volta per soglia, mai a limite già
 * superato; e il giro della sentinella che arriva subito dopo la soglia.
 */
class PreavvisiTest {

    private val min = 60_000L
    private val sec = 1_000L
    private val giorno = "2026-10-02"

    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val whatsapp = "com.whatsapp"
    private val classeViva = "eu.spaggiari.classevivafamiglia"
    private val social = "categoria:social"

    private fun limite(id: Long, chiave: String, minuti: Int, attiva: Boolean = true) = Regola(
        id = id,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = buildJsonObject {
            put("app_o_categoria", chiave)
            put("minuti_al_giorno", minuti)
        },
        attiva = attiva,
    )

    private fun categoria(pacchetto: String): String =
        if (pacchetto == instagram || pacchetto == tiktok) social else "categoria:altro"

    private fun indice(vararg uso: Pair<String, Long>, totaleMinimo: Long = 0L) =
        IndiceUso(uso.toList(), ::categoria, totaleMinimo)

    private fun daDare(regole: List<Regola>, indice: IndiceUso, fatti: Set<String> = emptySet(), bonus: Map<String, Int> = emptyMap()) =
        Preavvisi.daDare(regole, bonus, indice, giorno, fatti)

    // --- le soglie -------------------------------------------------------------

    @Test
    fun `a piu' di 5 minuti dal limite niente`() {
        val regola = limite(1, instagram, 60)
        assertTrue(daDare(listOf(regola), indice(instagram to 54 * min + 59 * sec)).isEmpty())
    }

    @Test
    fun `a 5 minuti dal limite il primo preavviso`() {
        val regola = limite(1, instagram, 60)
        val preavviso = daDare(listOf(regola), indice(instagram to 55 * min)).single()
        assertEquals(1L, preavviso.regolaId)
        assertEquals(5, preavviso.soglia)
        assertEquals(5, preavviso.minutiMancanti)
        assertEquals(60, preavviso.limiteEfficace)
        assertEquals(instagram, preavviso.chiave)
        assertEquals(listOf(5), preavviso.soglieRaggiunte)
    }

    @Test
    fun `a 1 minuto dal limite il secondo preavviso`() {
        val regola = limite(1, instagram, 60)
        val fatti = setOf(Preavvisi.chiave(1, giorno, 60, 5))
        val preavviso = daDare(listOf(regola), indice(instagram to 59 * min), fatti).single()
        assertEquals(1, preavviso.soglia)
        assertEquals(1, preavviso.minutiMancanti)
    }

    @Test
    fun `i minuti che mancano si dicono arrotondati in su, mai oltre la soglia`() {
        val regola = limite(1, instagram, 60)
        // Mancano 3 minuti e 10 secondi: "mancano 4 minuti".
        assertEquals(4, daDare(listOf(regola), indice(instagram to 56 * min + 50 * sec)).single().minutiMancanti)
        // Mancano 4 minuti e 59 secondi: "mancano 5 minuti".
        assertEquals(5, daDare(listOf(regola), indice(instagram to 55 * min + 1 * sec)).single().minutiMancanti)
        // Mancano 20 secondi: "manca 1 minuto".
        val fatti = setOf(Preavvisi.chiave(1, giorno, 60, 5))
        assertEquals(1, daDare(listOf(regola), indice(instagram to 59 * min + 40 * sec), fatti).single().minutiMancanti)
    }

    @Test
    fun `da 6 minuti a 40 secondi in un colpo solo arriva solo quello di 1 minuto, e valgono detti tutti e due`() {
        val regola = limite(1, instagram, 60)
        val preavviso = daDare(listOf(regola), indice(instagram to 59 * min + 20 * sec)).single()
        assertEquals(1, preavviso.soglia)
        assertEquals(listOf(5, 1), preavviso.soglieRaggiunte)
        val dette = Preavvisi.chiaviDette(listOf(preavviso), giorno)
        assertEquals(setOf(Preavvisi.chiave(1, giorno, 60, 5), Preavvisi.chiave(1, giorno, 60, 1)), dette.toSet())
        assertTrue(daDare(listOf(regola), indice(instagram to 59 * min + 30 * sec), dette.toSet()).isEmpty())
    }

    // --- una volta sola -----------------------------------------------------------

    @Test
    fun `una soglia gia' detta non torna`() {
        val regola = limite(1, instagram, 60)
        val primo = daDare(listOf(regola), indice(instagram to 55 * min)).single()
        val fatti = Preavvisi.chiaviDette(listOf(primo), giorno).toSet()
        assertTrue(daDare(listOf(regola), indice(instagram to 56 * min), fatti).isEmpty())
        assertTrue(daDare(listOf(regola), indice(instagram to 58 * min + 59 * sec), fatti).isEmpty())
        // Alla soglia dopo, sì.
        assertEquals(1, daDare(listOf(regola), indice(instagram to 59 * min), fatti).single().soglia)
    }

    @Test
    fun `il giorno dopo si ricomincia`() {
        val regola = limite(1, instagram, 60)
        val ieri = setOf(Preavvisi.chiave(1, "2026-10-01", 60, 5), Preavvisi.chiave(1, "2026-10-01", 60, 1))
        assertEquals(5, daDare(listOf(regola), indice(instagram to 55 * min), ieri).single().soglia)
    }

    @Test
    fun `un bonus alza il limite e le soglie valgono di nuovo`() {
        val regola = limite(1, instagram, 60)
        val fatti = setOf(Preavvisi.chiave(1, giorno, 60, 5), Preavvisi.chiave(1, giorno, 60, 1))
        // +15 di bonus: il limite è 75. A 70 minuti mancano 5 minuti al limite nuovo.
        val bonus = mapOf("1" to 15)
        val preavviso = daDare(listOf(regola), indice(instagram to 70 * min), fatti, bonus).single()
        assertEquals(5, preavviso.soglia)
        assertEquals(75, preavviso.limiteEfficace)
        // A 65 minuti mancano 10 minuti: niente.
        assertTrue(daDare(listOf(regola), indice(instagram to 65 * min), fatti, bonus).isEmpty())
    }

    @Test
    fun `mai a limite gia' superato - li' c'e' lo sforamento`() {
        val regola = limite(1, instagram, 60)
        // 61 minuti: superato (come lo conta il valutatore).
        assertTrue(daDare(listOf(regola), indice(instagram to 61 * min)).isEmpty())
        // 60 minuti e mezzo: non ancora superato, ma il tempo è finito.
        assertTrue(daDare(listOf(regola), indice(instagram to 60 * min + 30 * sec)).isEmpty())
        // Esattamente al limite: niente da dire.
        assertTrue(daDare(listOf(regola), indice(instagram to 60 * min)).isEmpty())
    }

    @Test
    fun `una regola spenta, una fascia o un limite a zero non danno preavvisi`() {
        assertTrue(daDare(listOf(limite(1, instagram, 60, attiva = false)), indice(instagram to 58 * min)).isEmpty())
        assertTrue(daDare(listOf(limite(1, instagram, 0)), indice(instagram to 0L)).isEmpty())
        val fascia = Regola(id = 2, tipo = TipiRegola.FASCIA_ORARIA, parametri = buildJsonObject { put("dalle", "21:00") })
        assertTrue(daDare(listOf(fascia), indice(instagram to 58 * min)).isEmpty())
    }

    @Test
    fun `un limite piccolo avvisa subito quanti minuti restano`() {
        val regola = limite(1, instagram, 3)
        val preavviso = daDare(listOf(regola), indice(instagram to 30 * sec)).single()
        assertEquals(5, preavviso.soglia)
        assertEquals(3, preavviso.minutiMancanti)
    }

    @Test
    fun `un limite piccolo a uso zero non avvisa finche' la sua app non e' davanti`() {
        val regola = limite(1, instagram, 3)
        // Mattina, Instagram mai aperto oggi: niente.
        assertTrue(Preavvisi.daDare(listOf(regola), emptyMap(), indice(whatsapp to 10 * min), giorno, emptySet()).isEmpty())
        assertTrue(Preavvisi.daDare(listOf(regola), emptyMap(), indice(), giorno, emptySet(), listOf(AppDavanti(whatsapp))).isEmpty())
        // Instagram davanti (anche se non ha ancora un secondo contato): "mancano 3 minuti".
        val davanti = Preavvisi.daDare(listOf(regola), emptyMap(), indice(), giorno, emptySet(), listOf(AppDavanti(instagram))).single()
        assertEquals(3, davanti.minutiMancanti)
        // Instagram davanti ma in una Sessione in corso (non conta): niente.
        assertTrue(Preavvisi.daDare(listOf(regola), emptyMap(), indice(), giorno, emptySet(), listOf(AppDavanti(instagram, contaTra = 5 * min))).isEmpty())
        // Una categoria piccola: basta una sua app davanti.
        val socialPiccolo = limite(2, social, 4)
        assertEquals(4, Preavvisi.daDare(listOf(socialPiccolo), emptyMap(), indice(), giorno, emptySet(), listOf(AppDavanti(tiktok))).single().minutiMancanti)
    }

    // --- i preavvisi che non valgono più -------------------------------------------------

    @Test
    fun `un bonus riporta sopra i 5 minuti - il preavviso di oggi si toglie`() {
        val regola = limite(1, instagram, 60)
        val fatti = setOf(Preavvisi.chiave(1, giorno, 60, 5))
        // Senza bonus: 56 minuti, mancano 4 minuti, il preavviso vale.
        assertTrue(Preavvisi.daTogliere(listOf(regola), emptyMap(), indice(instagram to 56 * min), giorno, fatti).isEmpty())
        // +15: mancano 19 minuti, via.
        assertEquals(listOf(1L), Preavvisi.daTogliere(listOf(regola), mapOf("1" to 15), indice(instagram to 56 * min), giorno, fatti))
    }

    @Test
    fun `una regola tolta o spenta - via anche il suo preavviso`() {
        val fatti = setOf(Preavvisi.chiave(1, giorno, 60, 5), Preavvisi.chiave(2, giorno, 30, 1))
        val spenta = limite(2, tiktok, 30, attiva = false)
        assertEquals(listOf(1L, 2L), Preavvisi.daTogliere(listOf(spenta), emptyMap(), indice(tiktok to 29 * min), giorno, fatti))
    }

    @Test
    fun `superato o al limite il preavviso resta - lo sostituisce lo sforamento`() {
        val regola = limite(1, instagram, 60)
        val fatti = setOf(Preavvisi.chiave(1, giorno, 60, 5), Preavvisi.chiave(1, giorno, 60, 1))
        assertTrue(Preavvisi.daTogliere(listOf(regola), emptyMap(), indice(instagram to 61 * min), giorno, fatti).isEmpty())
        assertTrue(Preavvisi.daTogliere(listOf(regola), emptyMap(), indice(instagram to 60 * min + 30 * sec), giorno, fatti).isEmpty())
    }

    @Test
    fun `solo i preavvisi di oggi - quelli di ieri se ne sono andati da soli a mezzanotte`() {
        val regola = limite(1, instagram, 60)
        val ieri = setOf(Preavvisi.chiave(1, "2026-10-01", 60, 5))
        assertTrue(Preavvisi.daTogliere(listOf(regola), mapOf("1" to 15), indice(instagram to 10 * min), giorno, ieri).isEmpty())
    }

    @Test
    fun `un preavviso vale fino a mezzanotte`() {
        val roma = ZoneId.of("Europe/Rome")
        val alle2230 = LocalDateTime.parse("2026-10-02T22:30:00").atZone(roma).toInstant().toEpochMilli()
        assertEquals(90 * min, Preavvisi.finoAFineGiorno(alle2230, roma))
        val mezzanotteMenoUno = LocalDateTime.parse("2026-10-02T23:59:59.999").atZone(roma).toInstant().toEpochMilli()
        assertEquals(1_000L, Preavvisi.finoAFineGiorno(mezzanotteMenoUno, roma))
    }

    // --- categorie e tutto il telefono ---------------------------------------------

    @Test
    fun `una categoria somma le sue app al secondo`() {
        val regola = limite(3, social, 60)
        // Instagram 30:30 + TikTok 25:00 = 55:30: mancano 4 minuti e mezzo.
        val preavviso = daDare(listOf(regola), indice(instagram to 30 * min + 30 * sec, tiktok to 25 * min, whatsapp to 40 * min)).single()
        assertEquals(5, preavviso.soglia)
        assertEquals(5, preavviso.minutiMancanti)
        assertEquals(social, preavviso.chiave)
    }

    @Test
    fun `tutto il telefono conta tutte le app, mai meno del totale gia' visto oggi`() {
        val regola = limite(4, "totale", 120)
        val preavviso = daDare(listOf(regola), indice(instagram to 60 * min, whatsapp to 56 * min)).single()
        assertEquals(5, preavviso.soglia)
        assertEquals(4, preavviso.minutiMancanti)
        // Il totale del giorno non scende: se oggi si erano già visti 119 minuti, manca 1 minuto.
        val conMinimo = daDare(listOf(regola), indice(instagram to 60 * min, totaleMinimo = 119), setOf(Preavvisi.chiave(4, giorno, 120, 5))).single()
        assertEquals(1, conMinimo.soglia)
    }

    @Test
    fun `il tempo nelle app di una Sessione in corso non conta`() {
        val roma = ZoneId.of("Europe/Rome")
        fun ms(testo: String) = LocalDateTime.parse(testo).atZone(roma).toInstant().toEpochMilli()
        val voci = listOf(
            Triple(Sessioni.RIPRESA, classeViva, ms("2026-10-02T15:00:00")),
            Triple(Sessioni.PAUSA, classeViva, ms("2026-10-02T16:00:00")),
        )
        val scorri: ScorriEventi = { da, a, azione ->
            voci.filter { it.third in da until a }.forEach { azione(it.first, it.second, "Main", it.third) }
        }
        val tutto = Sessioni.giorno(LocalDate.parse(giorno), roma, ms("2026-10-02T16:30:00"), scorri)
        val studio = PeriodoSessione(ms("2026-10-02T15:00:00"), ms("2026-10-02T15:58:00")) { it == classeViva }
        val letta = TempoInSessione.togli(tutto, listOf(studio))
        val indiceLetto = IndiceUso.daUso(letta.perApp, { true }, ::categoria)
        // Un'ora di ClasseViva, 58 minuti in sessione: contano 2 minuti su 60. Niente preavviso.
        assertTrue(daDare(listOf(limite(5, classeViva, 60)), indiceLetto).isEmpty())
        // Senza la sessione sarebbe stato il preavviso di 1 minuto.
        val senza = IndiceUso.daUso(tutto.perApp, { true }, ::categoria)
        assertEquals(1, daDare(listOf(limite(5, classeViva, 61)), senza, setOf(Preavvisi.chiave(5, giorno, 61, 5))).single().soglia)
    }

    // --- il giro dopo: subito dopo la soglia ------------------------------------------

    private fun prossima(regole: List<Regola>, indice: IndiceUso, davanti: List<AppDavanti>, fatti: Set<String> = emptySet()) =
        Preavvisi.prossimaSoglia(regole, emptyMap(), indice, giorno, fatti, davanti)

    @Test
    fun `l'app davanti porta la regola alla soglia di 5 minuti fra quanto manca`() {
        val regola = limite(1, instagram, 60)
        // 50 minuti usati: alla soglia dei 5 mancano 5 minuti.
        assertEquals(5 * min, prossima(listOf(regola), indice(instagram to 50 * min), listOf(AppDavanti(instagram))))
        // 54 minuti e mezzo: 30 secondi.
        assertEquals(30 * sec, prossima(listOf(regola), indice(instagram to 54 * min + 30 * sec), listOf(AppDavanti(instagram))))
    }

    @Test
    fun `detta quella di 5, la prossima e' quella di 1`() {
        val regola = limite(1, instagram, 60)
        val fatti = setOf(Preavvisi.chiave(1, giorno, 60, 5))
        assertEquals(3 * min, prossima(listOf(regola), indice(instagram to 56 * min), listOf(AppDavanti(instagram)), fatti))
        // Dette tutte e due: niente in vista.
        assertNull(prossima(listOf(regola), indice(instagram to 59 * min + 30 * sec), listOf(AppDavanti(instagram)), fatti + Preavvisi.chiave(1, giorno, 60, 1)))
    }

    @Test
    fun `un'app davanti che non consuma il limite non anticipa niente`() {
        val regola = limite(1, instagram, 60)
        assertNull(prossima(listOf(regola), indice(instagram to 58 * min), listOf(AppDavanti(whatsapp))))
        assertNull(prossima(listOf(regola), indice(instagram to 58 * min), emptyList()))
    }

    @Test
    fun `una categoria e il totale si consumano con le loro app`() {
        val social60 = limite(3, social, 60)
        val totale = limite(4, "totale", 120)
        val uso = indice(instagram to 30 * min, tiktok to 20 * min, whatsapp to 50 * min)
        // TikTok davanti consuma "social" (50 di 60) e il totale (100 di 120): prima la categoria.
        assertEquals(5 * min, prossima(listOf(social60, totale), uso, listOf(AppDavanti(tiktok))))
        // WhatsApp consuma solo il totale: 15 minuti alla soglia dei 5.
        assertEquals(15 * min, prossima(listOf(social60, totale), uso, listOf(AppDavanti(whatsapp))))
    }

    @Test
    fun `due app davanti sullo stesso totale lo consumano il doppio piu' in fretta`() {
        val totale = limite(4, "totale", 120)
        val uso = indice(instagram to 60 * min, whatsapp to 45 * min)
        assertEquals(5 * min, prossima(listOf(totale), uso, listOf(AppDavanti(instagram), AppDavanti(whatsapp))))
    }

    @Test
    fun `l'app di una Sessione in corso conta solo dopo la fine della sessione`() {
        val regola = limite(5, classeViva, 60)
        val davanti = listOf(AppDavanti(classeViva, contaTra = 10 * min))
        // 57 minuti contati: alla soglia di 1 minuto mancano 2 minuti, ma solo da quando la sessione finisce.
        val fatti = setOf(Preavvisi.chiave(5, giorno, 60, 5))
        assertEquals(12 * min, prossima(listOf(regola), indice(classeViva to 57 * min), davanti, fatti))
    }

    @Test
    fun `il giro dopo arriva un secondo dopo la soglia, mai oltre il minuto`() {
        assertEquals(60_000L, CadenzaSentinella.attesa(null))
        assertEquals(31_000L, CadenzaSentinella.attesa(30_000L))
        assertEquals(60_000L, CadenzaSentinella.attesa(5 * min))
        assertEquals(60_000L, CadenzaSentinella.attesa(59_500L))
        // Una soglia vicinissima, o appena passata: mai meno di un secondo.
        assertEquals(1_200L, CadenzaSentinella.attesa(200L))
        assertEquals(1_000L, CadenzaSentinella.attesa(0L))
        assertEquals(1_000L, CadenzaSentinella.attesa(-5_000L))
    }

    @Test
    fun `un pomeriggio su Instagram - i preavvisi arrivano entro pochi secondi dalla soglia`() {
        // Limite 60, si parte da 50 minuti e 20 secondi; Instagram resta davanti. Il
        // giro guarda, dice quello che c'è da dire e aspetta quanto dice la cadenza.
        val regola = limite(1, instagram, 60)
        var usato = 50 * min + 20 * sec
        var fatti = emptySet<String>()
        val detti = mutableListOf<Pair<Int, Long>>()
        repeat(40) {
            val uso = indice(instagram to usato)
            val nuovi = Preavvisi.daDare(listOf(regola), emptyMap(), uso, giorno, fatti)
            nuovi.forEach { detti += it.soglia to (60 * min - usato) }
            fatti = fatti + Preavvisi.chiaviDette(nuovi, giorno)
            val attesa = CadenzaSentinella.attesa(Preavvisi.prossimaSoglia(listOf(regola), emptyMap(), uso, giorno, fatti, listOf(AppDavanti(instagram))))
            usato += attesa
        }
        assertEquals(listOf(5, 1), detti.map { it.first })
        // Detti al massimo un secondo e mezzo dopo la soglia.
        assertTrue(detti[0].second in (5 * min - 1_500)..(5 * min))
        assertTrue(detti[1].second in (1 * min - 1_500)..(1 * min))
    }

    @Test
    fun `le app davanti sono quelle il cui pezzo arriva fino ad adesso, e contano`() {
        val roma = ZoneId.of("Europe/Rome")
        fun ms(testo: String) = LocalDateTime.parse(testo).atZone(roma).toInstant().toEpochMilli()
        val home = "com.google.android.apps.nexuslauncher"
        val voci = listOf(
            Triple(Sessioni.RIPRESA, whatsapp, ms("2026-10-02T10:00:00")),
            Triple(Sessioni.PAUSA, whatsapp, ms("2026-10-02T10:10:00")),
            Triple(Sessioni.RIPRESA, instagram, ms("2026-10-02T10:10:00")),
        )
        val scorri: ScorriEventi = { da, a, azione ->
            voci.filter { it.third in da until a }.forEach { azione(it.first, it.second, "Main", it.third) }
        }
        val adesso = ms("2026-10-02T10:30:00")
        val letta = Sessioni.giorno(LocalDate.parse(giorno), roma, adesso, scorri)
        assertEquals(listOf(AppDavanti(instagram)), Preavvisi.davanti(letta, { it != home }, null))
        // La Home davanti non conta.
        val conHome = voci + Triple(Sessioni.PAUSA, instagram, ms("2026-10-02T10:20:00")) + Triple(Sessioni.RIPRESA, home, ms("2026-10-02T10:20:00"))
        val scorriHome: ScorriEventi = { da, a, azione ->
            conHome.filter { it.third in da until a }.sortedBy { it.third }.forEach { azione(it.first, it.second, "Main", it.third) }
        }
        assertTrue(Preavvisi.davanti(Sessioni.giorno(LocalDate.parse(giorno), roma, adesso, scorriHome), { it != home }, null).isEmpty())
        // Schermo spento: nessuna.
        val spento = voci + Triple(Sessioni.SCHERMO_SPENTO, "android", ms("2026-10-02T10:25:00"))
        val scorriSpento: ScorriEventi = { da, a, azione ->
            spento.filter { it.third in da until a }.sortedBy { it.third }.forEach { azione(it.first, it.second, "Main", it.third) }
        }
        assertTrue(Preavvisi.davanti(Sessioni.giorno(LocalDate.parse(giorno), roma, adesso, scorriSpento), { true }, null).isEmpty())
    }

    @Test
    fun `l'app di una Sessione in corso e' davanti, ma conta dalla fine della sessione`() {
        val roma = ZoneId.of("Europe/Rome")
        fun ms(testo: String) = LocalDateTime.parse(testo).atZone(roma).toInstant().toEpochMilli()
        val voci = listOf(Triple(Sessioni.RIPRESA, classeViva, ms("2026-10-02T15:10:00")))
        val scorri: ScorriEventi = { da, a, azione ->
            voci.filter { it.third in da until a }.forEach { azione(it.first, it.second, "Main", it.third) }
        }
        val adesso = ms("2026-10-02T15:30:00")
        val fineSessione = ms("2026-10-02T16:00:00")
        val studio = PeriodoSessione(ms("2026-10-02T15:00:00"), fineSessione) { it == classeViva }
        val letta = TempoInSessione.togli(Sessioni.giorno(LocalDate.parse(giorno), roma, adesso, scorri), listOf(studio))
        assertEquals(listOf(AppDavanti(classeViva, contaTra = 30 * min)), Preavvisi.davanti(letta, { true }, fineSessione))
    }
}
