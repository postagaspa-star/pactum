package eu.stgm.pactum.genitore.ui

import androidx.compose.ui.geometry.Offset
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.AutoriProposta
import eu.stgm.pactum.genitore.dati.Dichiarazione
import eu.stgm.pactum.genitore.dati.DispositivoFinestra
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.StatiDichiarazione
import eu.stgm.pactum.genitore.dati.StatiProposta
import eu.stgm.pactum.genitore.dati.StatiSessione
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.TipiRegola
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * (0.15) La logica pura del riordino: le righe in cima alla Panoramica, i numeri
 * di "Da decidere", l'ordine della lista, le liste lunghe, la foto che non esce
 * dallo schermo, i giorni delle fasce e i formati di data.
 */
class LogicaRiordinoTest {

    private val p = ParoleDiProva
    private val adesso = Instant.parse("2026-10-04T15:00:00Z")

    private fun righe(
        daDecidere: Int = 0,
        blocco: StatoBlocco? = null,
        foto: Int = 0,
        silenziosi: List<VistaDispositivo> = emptyList(),
        avvisiAccesi: Boolean = true,
        ultimoControllo: Instant? = adesso.minusSeconds(60),
        inRete: Boolean = true,
        errore: Boolean = false,
    ) = righeInCima(daDecidere, blocco, foto, silenziosi, avvisiAccesi, ultimoControllo, adesso, inRete, errore, null)

    // --- Le righe in cima alla Panoramica ------------------------------------------------------

    @Test
    fun `quando va tutto bene in cima non c'e niente`() {
        assertEquals(emptyList<RigaInCima>(), righe())
        // Nemmeno "Avvisi: ultimo controllo alle…" se il controllo è recente, o se non c'è mai stato.
        assertEquals(emptyList<RigaInCima>(), righe(ultimoControllo = null))
        // E un blocco che non c'è (niente da fare) non è una riga.
        assertEquals(emptyList<RigaInCima>(), righe(blocco = StatoBlocco(false, null, null, 0)))
    }

    @Test
    fun `le righe ci sono solo se c'e qualcosa, in quest'ordine`() {
        val silenzioso = VistaDispositivo(id = 3, nome = "Telefono", statoSilenzio = StatoSilenzio(silente = true))
        val attivo = StatoBlocco(attivo = true, dal = adesso.minusSeconds(600), prossimo = null, daFare = 2)
        val tutte = righe(
            daDecidere = 3,
            blocco = attivo,
            foto = 1,
            silenziosi = listOf(silenzioso),
            avvisiAccesi = false,
            errore = true,
        )
        assertEquals(
            listOf(
                RigaInCima.DaDecidere(3),
                RigaInCima.Blocco(attivo),
                RigaInCima.Silenzioso(silenzioso),
                RigaInCima.AvvisiSpenti,
                RigaInCima.DatiVecchi(null),
            ),
            tutte,
        )
    }

    @Test
    fun `il blocco in arrivo e una riga, le foto da guardare solo senza un blocco`() {
        val inArrivo = StatoBlocco(attivo = false, dal = null, prossimo = adesso.plusSeconds(3600), daFare = 1)
        assertEquals(listOf(RigaInCima.Blocco(inArrivo)), righe(blocco = inArrivo, foto = 2))
        assertEquals(listOf(RigaInCima.FotoDaGuardare(2)), righe(blocco = StatoBlocco(false, null, null, 0), foto = 2))
    }

    @Test
    fun `gli avvisi in ritardo si dicono solo con la rete, senza rete non si propone la batteria`() {
        val vecchio = adesso.minusSeconds(20 * 60)
        assertEquals(listOf(RigaInCima.AvvisiInRitardo(vecchio)), righe(ultimoControllo = vecchio))
        // Senza rete il ritardo non si sistema nelle impostazioni della batteria (B28).
        assertEquals(emptyList<RigaInCima>(), righe(ultimoControllo = vecchio, inRete = false))
        // Avvisi spenti: si dice sempre (si sistema sul telefono, rete o no).
        assertEquals(listOf(RigaInCima.AvvisiSpenti), righe(avvisiAccesi = false, inRete = false))
    }

    @Test
    fun `le righe hanno chiavi stabili, e i silenzi una per dispositivo`() {
        val a = VistaDispositivo(id = 1, nome = "A")
        val b = VistaDispositivo(id = 2, nome = "B")
        assertEquals("riga-silenzio-1", chiaveRiga(RigaInCima.Silenzioso(a)))
        assertEquals("riga-silenzio-2", chiaveRiga(RigaInCima.Silenzioso(b)))
        assertEquals(chiaveRiga(RigaInCima.DaDecidere(1)), chiaveRiga(RigaInCima.DaDecidere(5)))
    }

    // --- Quante cose aspettano il genitore -----------------------------------------------------------

    @Test
    fun `il numero su Da decidere - il figlio scelto dal suo conto, gli altri dalla famiglia`() {
        val figli = listOf(
            Figlio(id = 1, proposteDaDecidere = 2, sessioniDaApprovare = 1),
            Figlio(id = 2, proposteDaDecidere = 0, sessioniDaApprovare = 2),
        )
        // Il conto del figlio scelto non si sa ancora: vale la famiglia per tutti.
        assertEquals(5, quanteDaDecidereInTutto(figli, sceltoId = 1, delScelto = null))
        // Il figlio scelto col suo conto (finestra + dichiarazioni), l'altro dalla famiglia.
        assertEquals(6, quanteDaDecidereInTutto(figli, sceltoId = 1, delScelto = 4))
        assertEquals(3, quanteDaDecidereInTutto(figli, sceltoId = 2, delScelto = 0))
        assertEquals(0, quanteDaDecidereInTutto(emptyList(), null, null))
    }

    @Test
    fun `il conto della finestra e quello della lista - proposte del figlio, sessioni, dichiarazioni`() {
        val finestra = Finestra(
            regole = listOf(
                RegolaFinestra(id = 1, tipo = TipiRegola.LIMITE_TEMPO, dispositivoId = 7),
                RegolaFinestra(id = 2, tipo = TipiRegola.LIMITE_TEMPO, dispositivoId = 8),
            ),
            dispositivi = listOf(DispositivoFinestra(id = 7), DispositivoFinestra(id = 8, revocato = true)),
            propostePendenti = listOf(
                // Del figlio, su una regola del telefono collegato: conta.
                proposta(10, "2026-10-04T12:00:00Z"),
                // Su una regola di un dispositivo scollegato: si può solo rifiutare, non conta (come il server).
                proposta(11, "2026-10-04T12:00:00Z").copy(regolaId = 2),
                // Tua: aspetta il figlio, non te.
                proposta(12, "2026-10-04T12:00:00Z").copy(autore = AutoriProposta.GENITORE),
            ),
            sessioni = listOf(
                Sessione(
                    id = 20,
                    nome = "Studio",
                    stato = StatiSessione.IN_ATTESA,
                    creataTs = "2026-10-04T10:00:00Z",
                    versione = 1,
                    dispositivoId = 7,
                ),
            ),
        )
        val dichiarazioni = listOf(
            dichiarazione(30, StatiDichiarazione.IN_ATTESA),
            dichiarazione(31, StatiDichiarazione.CONFERMATA),
        )
        assertEquals(listOf(10L), proposteDaContare(finestra, emptyMap(), null).map { it.id })
        assertEquals(3, quanteDaDecidereDellaFinestra(finestra, emptyMap(), emptyMap(), null, dichiarazioni))
        // Dichiarazioni non ancora lette: solo proposte e sessioni.
        assertEquals(2, quanteDaDecidereDellaFinestra(finestra, emptyMap(), emptyMap(), null, null))
        // Appena decisa da qui, con la finestra di prima: non conta più (come nella lista).
        assertEquals(
            1,
            quanteDaDecidereDellaFinestra(
                finestra,
                giaChiuse = mapOf(10L to PropostaChiusa(figlioId = 1, alle = 2_000)),
                sessioniDecise = emptyMap(),
                lettaAlle = 1_000,
                dichiarazioni = null,
            ),
        )
    }

    @Test
    fun `contano solo le dichiarazioni ancora da confermare`() {
        val dichiarazioni = listOf(
            dichiarazione(1, StatiDichiarazione.IN_ATTESA),
            dichiarazione(2, StatiDichiarazione.CONFERMATA),
            dichiarazione(3, StatiDichiarazione.IN_ATTESA),
        )
        assertEquals(2, dichiarazioniInAttesa(dichiarazioni))
    }

    // --- L'ordine di "Da decidere" ----------------------------------------------------------------

    private fun proposta(id: Long, ts: String) = Proposta(
        id = id,
        regolaId = 1,
        stato = StatiProposta.PENDENTE,
        tsServer = ts,
        autore = AutoriProposta.FIGLIO,
    )

    private fun dichiarazione(id: Long, stato: String, ts: String = "2026-10-04T09:00:00Z") =
        Dichiarazione(id = id, regolaId = 1, giorno = "2026-10-04", esito = "successo", stato = stato, tsServer = ts)

    private fun sessione(id: Long, ts: String?) =
        checkNotNull(richiestaInAttesa(Sessione(id = id, nome = "Studio", stato = StatiSessione.IN_ATTESA, creataTs = ts, versione = 1)))

    @Test
    fun `Da decidere e una lista sola, dal piu vecchio al piu nuovo`() {
        val voci = vociDaDecidere(
            proposte = listOf(proposta(10, "2026-10-04T12:00:00Z"), proposta(11, "2026-10-03T08:00:00Z")),
            sessioni = listOf(sessione(20, "2026-10-04T10:00:00Z")),
            dichiarazioni = listOf(
                dichiarazione(30, StatiDichiarazione.IN_ATTESA, "2026-10-04T11:00:00Z"),
                dichiarazione(31, StatiDichiarazione.CONFERMATA, "2026-10-01T11:00:00Z"),
            ),
        )
        assertEquals(listOf("proposta-11", "sessione-20", "dichiarazione-30", "proposta-10"), voci.map { it.chiave })
    }

    @Test
    fun `senza orario si va in fondo, e a parita prima le proposte`() {
        val voci = vociDaDecidere(
            proposte = listOf(proposta(10, ""), proposta(12, "2026-10-04T10:00:00Z")),
            sessioni = listOf(sessione(20, "2026-10-04T10:00:00Z")),
            dichiarazioni = emptyList(),
        )
        assertEquals(listOf("proposta-12", "sessione-20", "proposta-10"), voci.map { it.chiave })
    }

    // --- Le liste lunghe -------------------------------------------------------------------------------

    @Test
    fun `le prime voci e quante restano nascoste`() {
        val dodici = (1..12).toList()
        assertEquals((1..10).toList() to 2, primeVoci(dodici, VOCI_TEMPO_VISIBILI, tutte = false))
        assertEquals(dodici to 0, primeVoci(dodici, VOCI_TEMPO_VISIBILI, tutte = true))
        assertEquals(listOf(1, 2) to 0, primeVoci(listOf(1, 2), VOCI_DA_GUARDARE_IN_PANORAMICA, tutte = false))
        assertEquals(3, VOCI_DA_GUARDARE_IN_PANORAMICA)
        assertEquals(10, VOCI_TEMPO_VISIBILI)
        assertEquals(4, APP_VISIBILI_CARD)
    }

    // --- La foto a tutto schermo ------------------------------------------------------------------------

    @Test
    fun `la foto ingrandita si sposta solo finche non esce dallo schermo`() {
        // A 2x su 400x800 sporge di 200 e 400 per lato.
        assertEquals(Offset(200f, -400f), limitaSpostamento(Offset(500f, -900f), 2f, 400f, 800f))
        assertEquals(Offset(50f, 60f), limitaSpostamento(Offset(50f, 60f), 2f, 400f, 800f))
        // A scala 1 non si sposta.
        assertEquals(Offset(0f, 0f), limitaSpostamento(Offset(30f, 30f), 1f, 400f, 800f))
    }

    // --- I giorni delle fasce e i formati di data -------------------------------------------------------

    @Test
    fun `i giorni di una fascia si dicono come li direbbe una persona`() {
        assertEquals("ogni giorno", fraseGiorniFascia(p, listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")))
        assertEquals("ogni giorno", fraseGiorniFascia(p, listOf("dom", "sab", "ven", "gio", "mer", "mar", "lun")))
        assertEquals("da lunedì a venerdì", fraseGiorniFascia(p, listOf("lun", "mar", "mer", "gio", "ven")))
        assertEquals("sabato e domenica", fraseGiorniFascia(p, listOf("sab", "dom")))
        assertEquals("lun, mer, ven", fraseGiorniFascia(p, listOf("ven", "lun", "mer")))
        assertEquals("lun, mar", fraseGiorniFascia(p, listOf("lun", "mar")))
    }

    @Test
    fun `una fascia di tutti i giorni non scrive piu i sette giorni`() {
        val sera = RegolaFinestra(
            id = 1,
            tipo = TipiRegola.FASCIA_ORARIA,
            parametri = buildJsonObject {
                put("dalle", "23:00")
                put("alle", "07:00")
                putJsonArray("giorni") { listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom").forEach { add(JsonPrimitive(it)) } }
            },
        )
        assertEquals("Niente telefono dalle 23:00 alle 07:00 (ogni giorno)", descrizioneRegola(p, sera))
        // Un dato che manca non diventa mai un "?".
        val senzaOrari = RegolaFinestra(id = 2, tipo = TipiRegola.FASCIA_ORARIA)
        val testo = descrizioneRegola(p, senzaOrari)
        assertTrue(testo, !testo.contains("?"))
        assertEquals("Anomalia", descrizioneBuco(p, null as String?))
    }

    @Test
    fun `un solo formato per giorno e per giorno con ora`() {
        val zona = ZoneId.of("Europe/Rome")
        val oggi = LocalDate.of(2026, 10, 4)
        assertEquals("oggi", testoGiorno(p, oggi, oggi))
        assertEquals("ieri", testoGiorno(p, oggi.minusDays(1), oggi))
        assertEquals("01/10", testoGiorno(p, LocalDate.of(2026, 10, 1), oggi))
        assertEquals("oggi 17:10", testoQuando(p, Instant.parse("2026-10-04T15:10:00Z"), zona, oggi))
        assertEquals("ieri 09:05", testoQuando(p, Instant.parse("2026-10-03T07:05:00Z"), zona, oggi))
        assertEquals("14/09 15:10", testoQuando(p, Instant.parse("2026-09-14T13:10:00Z"), zona, oggi))
    }

    @Test
    fun `le parole nuove della barra e delle pagine`() {
        assertEquals("Panoramica", p.testo(R.string.finestra_titolo))
        assertEquals("Da decidere", p.testo(R.string.scheda_da_decidere))
        assertEquals("Lavori", p.testo(R.string.scheda_lavori))
        assertEquals("Lavori di casa", p.testo(R.string.faccende_titolo))
        assertEquals("Risolvi", p.testo(R.string.avvisi_risolvi))
        assertEquals("Riassunto della sera", p.testo(R.string.impostazioni_digest_titolo))
        assertEquals("Versione dell'app", p.testo(R.string.impostazioni_aggiornamenti_titolo))
        assertEquals("Collegamento", p.testo(R.string.impostazioni_connessione_titolo))
        // Le parole approvate che restano.
        assertEquals("Non è andata così", p.testo(R.string.verdetto_ribalta))
        assertEquals("Storico del patto", p.testo(R.string.sezione_storico))
        assertEquals("Dati non aggiornati: questi sono gli ultimi ricevuti.", p.testo(R.string.notifiche_dati_vecchi))
    }
}
