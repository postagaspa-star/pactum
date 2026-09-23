package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.BonusGiorno
import eu.stgm.pactum.genitore.dati.ContatoreBonus
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.DispositivoFinestra
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.RiferimentoDispositivo
import eu.stgm.pactum.genitore.dati.SilenzioNoto
import eu.stgm.pactum.genitore.dati.SitiGiorno
import eu.stgm.pactum.genitore.dati.SitoVisitato
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.UsoApp
import eu.stgm.pactum.genitore.dati.UsoGiorno
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * La logica della v3 (famiglia, figli, dispositivi). Se sbaglia, l'app dice al
 * padre una cosa falsa: il patto di un figlio sotto il nome di un altro, un
 * computer spento spacciato per un'interruzione, una regola del computer messa
 * sotto il telefono, "0 minuti" su un sito di cui non si sa niente.
 */
class LogicaFamigliaTest {

    // --- dati di prova ---------------------------------------------------------------

    private val bonusVuoto = StatoBonus(ContatoreBonus(0, 30, 30), ContatoreBonus(0, 90, 90))

    private fun limite(id: Long, chiave: String, minuti: Int, dispositivo: Long?, attiva: Boolean = true) =
        RegolaFinestra(
            id = id,
            tipo = "limite_tempo",
            parametri = buildJsonObject {
                put("app_o_categoria", chiave)
                put("minuti_al_giorno", minuti)
            },
            attiva = attiva,
            dispositivoId = dispositivo,
        )

    private fun fascia(id: Long, dispositivo: Long?) = RegolaFinestra(
        id = id,
        tipo = "fascia_oraria",
        parametri = buildJsonObject {
            put("dalle", "22:00")
            put("alle", "07:00")
        },
        dispositivoId = dispositivo,
    )

    private fun vitaReale(id: Long) = RegolaFinestra(
        id = id,
        tipo = "vita_reale",
        parametri = buildJsonObject { put("descrizione", "Camminare") },
    )

    private fun silenzio(
        silente: Boolean,
        battito: String? = "2026-09-24T10:00:00+00:00",
        spento: Boolean = false,
        spentoDal: String? = null,
    ) = StatoSilenzio(ultimoBattito = battito, silente = silente, spento = spento, spentoDal = spentoDal)

    private val telefono = DispositivoFinestra(
        id = 1,
        nome = "Telefono",
        tipo = "telefono",
        statoSilenzio = silenzio(silente = false),
        striscia = listOf(QuadrettoSemaforo("2026-09-24", "verde")),
    )
    private val computer = DispositivoFinestra(
        id = 2,
        nome = "Computer di camera",
        tipo = "computer",
        statoSilenzio = silenzio(silente = false, spento = true, spentoDal = "2026-09-23T21:10:00+00:00"),
        striscia = listOf(QuadrettoSemaforo("2026-09-24", "rosso")),
    )

    private fun vista(d: DispositivoFinestra) = dispositiviDellaFinestra(Finestra(dispositivi = listOf(d))).single()

    // --- quale figlio ------------------------------------------------------------------

    private val andrea = Figlio(id = 1, nome = "Andrea")
    private val luca = Figlio(id = 2, nome = "Luca")

    @Test
    fun `il figlio scelto resta quello salvato, se c'e ancora`() {
        assertEquals(2L, figlioEffettivo(listOf(andrea, luca), scelto = 2)?.id)
    }

    @Test
    fun `senza scelta, o con un figlio che non c'e piu, vale il primo come fa il server`() {
        assertEquals(1L, figlioEffettivo(listOf(andrea, luca), scelto = null)?.id)
        assertEquals(1L, figlioEffettivo(listOf(andrea, luca), scelto = 99)?.id)
        assertNull(figlioEffettivo(emptyList(), scelto = 2))
    }

    // --- compatibilita con il server 0.7 --------------------------------------------------

    @Test
    fun `server vecchio, niente dispositivi - uno solo, dai campi di primo livello`() {
        val uso = listOf(UsoGiorno(giorno = "2026-09-24", totaleMinuti = 90))
        val finestra = Finestra(
            usoRecente = uso,
            bonus = bonusVuoto,
            statoSilenzio = silenzio(silente = true),
            bonusGiornalieri = listOf(BonusGiorno("2026-09-24", 15)),
        )
        val dispositivi = dispositiviDellaFinestra(finestra)
        assertEquals(1, dispositivi.size)
        val unico = dispositivi.single()
        assertNull(unico.id)
        assertNull(unico.nome)
        assertFalse(unico.computer)
        assertEquals(uso, unico.usoRecente)
        assertEquals(bonusVuoto, unico.bonus)
        assertEquals(15, unico.bonusGiornalieri.single().minuti)
        assertFalse(finestraPerDispositivo(finestra))
    }

    @Test
    fun `server v3 - i dispositivi della finestra, in ordine di id`() {
        val finestra = Finestra(dispositivi = listOf(computer, telefono))
        assertTrue(finestraPerDispositivo(finestra))
        assertEquals(listOf(1L, 2L), dispositiviDellaFinestra(finestra).map { it.id })
        assertTrue(dispositiviDellaFinestra(finestra).last().computer)
    }

    // --- lo stato del canale: spento non e silente ------------------------------------------

    @Test
    fun `un computer spento e SPENTO, non un silenzio`() {
        assertEquals(StatoCanale.SPENTO, statoCanale(vista(computer)))
        // Anche se il server dicesse silente: per un computer spento vince.
        val spentoESilente = computer.copy(statoSilenzio = silenzio(silente = true, spento = true))
        assertEquals(StatoCanale.SPENTO, statoCanale(vista(spentoESilente)))
    }

    @Test
    fun `un telefono non e mai spento, decide silente`() {
        val telefonoSpento = telefono.copy(statoSilenzio = silenzio(silente = true, spento = true))
        assertEquals(StatoCanale.SILENTE, statoCanale(vista(telefonoSpento)))
        assertEquals(StatoCanale.IN_CONTATTO, statoCanale(vista(telefono)))
    }

    @Test
    fun `un computer acceso che tace e un silenzio vero`() {
        val muto = computer.copy(statoSilenzio = silenzio(silente = true, spento = false))
        assertEquals(StatoCanale.SILENTE, statoCanale(vista(muto)))
    }

    @Test
    fun `mai sentito, da collegare, scollegato, sconosciuto`() {
        assertEquals(
            StatoCanale.MAI_SENTITO,
            statoCanale(vista(telefono.copy(statoSilenzio = silenzio(silente = true, battito = null)))),
        )
        assertEquals(StatoCanale.DA_COLLEGARE, statoCanale(vista(telefono.copy(abbinato = false))))
        // Scollegato vince su tutto, anche su un vecchio "in contatto".
        assertEquals(StatoCanale.SCOLLEGATO, statoCanale(vista(telefono.copy(revocato = true))))
        assertEquals(StatoCanale.SCONOSCIUTO, statoCanale(vista(telefono.copy(statoSilenzio = null))))
    }

    @Test
    fun `lo stato vale uguale per i dispositivi della famiglia`() {
        val d = Dispositivo(id = 3, nome = "PC", tipo = "computer", statoSilenzio = silenzio(false, spento = true))
        assertEquals(StatoCanale.SPENTO, statoCanale(d))
    }

    // --- la vedetta: quando avvisare ---------------------------------------------------------

    private fun sorvegliato(d: DispositivoFinestra) =
        silenzioDaSorvegliare(d.tipo, d.abbinato, d.revocato, d.statoSilenzio)

    @Test
    fun `la vedetta non sorveglia i dispositivi da collegare, scollegati o ignoti`() {
        assertNull(sorvegliato(telefono.copy(abbinato = false)))
        assertNull(sorvegliato(telefono.copy(revocato = true)))
        assertNull(sorvegliato(telefono.copy(statoSilenzio = null)))
    }

    @Test
    fun `per la vedetta un computer spento non e un allarme`() {
        val attuale = sorvegliato(computer)!!
        assertFalse(attuale.allarme)
        assertTrue(attuale.spento)
        // Da "in contatto" a "spento": nessun avviso.
        val noto = SilenzioNoto(silente = false, ultimoBattito = "2026-09-23T20:00:00+00:00")
        assertEquals(CambioSilenzio.NESSUNO, cambioSilenzio(noto, attuale))
    }

    @Test
    fun `un silenzio nuovo avvisa, lo stesso silenzio no`() {
        val muto = SilenzioAttuale(allarme = true, spento = false, ultimoBattito = "b1")
        assertEquals(
            CambioSilenzio.NUOVO_SILENZIO,
            cambioSilenzio(SilenzioNoto(silente = false, ultimoBattito = "b1"), muto),
        )
        assertEquals(
            CambioSilenzio.NESSUNO,
            cambioSilenzio(SilenzioNoto(silente = true, ultimoBattito = "b1"), muto),
        )
        // Contatto ripreso e riperso fra due giri: battito diverso, silenzio nuovo.
        assertEquals(
            CambioSilenzio.NUOVO_SILENZIO,
            cambioSilenzio(SilenzioNoto(silente = true, ultimoBattito = "b0"), muto),
        )
    }

    @Test
    fun `la prima osservazione prende la base senza allarmare`() {
        val muto = SilenzioAttuale(allarme = true, spento = false, ultimoBattito = "b1")
        assertEquals(CambioSilenzio.BASE, cambioSilenzio(null, muto))
    }

    @Test
    fun `il contatto che torna avvisa, il primo battito di un dispositivo nuovo no`() {
        val vivo = SilenzioAttuale(allarme = false, spento = false, ultimoBattito = "b2")
        assertEquals(
            CambioSilenzio.CONTATTO_TORNATO,
            cambioSilenzio(SilenzioNoto(silente = true, ultimoBattito = "b1"), vivo),
        )
        assertEquals(
            CambioSilenzio.NESSUNO,
            cambioSilenzio(SilenzioNoto(silente = true, ultimoBattito = null), vivo),
        )
    }

    @Test
    fun `un computer che tace e poi risulta spento non era un'interruzione`() {
        val spento = SilenzioAttuale(allarme = false, spento = true, ultimoBattito = "b1")
        assertEquals(
            CambioSilenzio.SPENTO_DOPO_SILENZIO,
            cambioSilenzio(SilenzioNoto(silente = true, ultimoBattito = "b1"), spento),
        )
    }

    // --- un computer "spento" da troppo tempo ------------------------------------------------

    @Test
    fun `spento a lungo solo oltre le 24 ore`() {
        val dal = Instant.parse("2026-09-23T20:10:00Z")
        assertFalse(spentoALungo(dal, dal.plus(Duration.ofHours(23)).plusSeconds(59 * 60)))
        // Esattamente 24 ore: non ancora "più di 24".
        assertFalse(spentoALungo(dal, dal.plus(Duration.ofHours(24))))
        assertTrue(spentoALungo(dal, dal.plus(Duration.ofHours(24)).plusSeconds(1)))
        assertTrue(spentoALungo(dal, dal.plus(Duration.ofDays(3))))
        // Un orario del server nel futuro (orologi storti) non è "a lungo".
        assertFalse(spentoALungo(dal, dal.minusSeconds(60)))
    }

    @Test
    fun `spento a lungo resta SPENTO, mai un allarme per la vedetta`() {
        // Tre giorni "spento": lo stato del canale non cambia, e la vedetta non avvisa.
        val vecchio = computer.copy(
            statoSilenzio = silenzio(silente = false, spento = true, spentoDal = "2026-09-20T21:10:00+00:00"),
        )
        assertEquals(StatoCanale.SPENTO, statoCanale(vista(vecchio)))
        val attuale = sorvegliato(vecchio)!!
        assertFalse(attuale.allarme)
        assertEquals(
            CambioSilenzio.NESSUNO,
            cambioSilenzio(SilenzioNoto(silente = false, ultimoBattito = attuale.ultimoBattito, spento = true), attuale),
        )
    }

    // --- gli id delle notifiche di sistema ---------------------------------------------------

    @Test
    fun `gli avvisi per dispositivo non prendono mai gli id della 0,7`() {
        // Il dispositivo 1 prendeva 2.000.000.001: l'id del digest della 0.7.
        (1L..500L).forEach { id ->
            val avviso = idAvvisoSilenzio(id)
            assertTrue("dispositivo $id", avviso != ID_DIGEST_07 && avviso != ID_AVVISO_UNICO)
            assertTrue("dispositivo $id", avviso != idDigest(id))
        }
        // Il server 0.7 resta sull'id di prima: è lo stesso avviso.
        assertEquals(ID_AVVISO_UNICO, idAvvisoSilenzio(CHIAVE_SERVER_VECCHIO))
        assertEquals(2_000_000_000, ID_AVVISO_UNICO)
        assertEquals(2_000_000_001, ID_DIGEST_07)
    }

    @Test
    fun `gli id restano interi positivi e separati anche con id enormi`() {
        listOf(1L, 99_999_999L, 100_000_000L, Long.MAX_VALUE, -7L).forEach { id ->
            val avviso = idAvvisoSilenzio(id)
            assertTrue("$id -> $avviso", avviso in 2_000_000_010..2_100_000_009)
            val digest = idDigest(id)
            assertTrue("$id -> $digest", digest in 1_900_000_000..1_949_999_999)
        }
    }

    // --- il passaggio dalla 0.7 ------------------------------------------------------------

    private val silenzioDella07 = SilenzioNoto(silente = true, ultimoBattito = "2026-09-24T09:00:00+00:00")
    private val famigliaMigrata = listOf(
        andrea.copy(
            dispositivi = listOf(
                Dispositivo(id = 1, nome = "Telefono"),
                Dispositivo(id = 2, nome = "Computer", tipo = "computer"),
            ),
        ),
        luca.copy(dispositivi = listOf(Dispositivo(id = 3, nome = "Telefono"))),
    )

    @Test
    fun `primo giro v3 - lo stato della 0,7 passa al dispositivo che guardava, e l'avviso vecchio si toglie`() {
        val partenza = partenzaSilenzi(salvati = null, versioneVecchia = silenzioDella07, figli = famigliaMigrata)
        assertTrue(partenza.togliAvvisoUnico)
        assertEquals(mapOf(1L to silenzioDella07), partenza.noti)
        // Lo stesso silenzio non si riavvisa; il contatto che torna sì.
        val stessoSilenzio = SilenzioAttuale(allarme = true, spento = false, ultimoBattito = silenzioDella07.ultimoBattito)
        assertEquals(CambioSilenzio.NESSUNO, cambioSilenzio(partenza.noti[1L], stessoSilenzio))
        val tornato = SilenzioAttuale(allarme = false, spento = false, ultimoBattito = "2026-09-24T10:00:00+00:00")
        assertEquals(CambioSilenzio.CONTATTO_TORNATO, cambioSilenzio(partenza.noti[1L], tornato))
    }

    @Test
    fun `primo giro v3 senza niente della 0,7 - si toglie l'avviso e si prende la base`() {
        val partenza = partenzaSilenzi(salvati = null, versioneVecchia = null, figli = famigliaMigrata)
        assertTrue(partenza.togliAvvisoUnico)
        assertTrue(partenza.noti.isEmpty())
    }

    @Test
    fun `dopo la 0,8 su un server 0,7, lo stato del dispositivo unico passa al primo dispositivo`() {
        val dalServerVecchio = SilenzioNoto(silente = false, ultimoBattito = "b7")
        val partenza = partenzaSilenzi(
            salvati = mapOf(CHIAVE_SERVER_VECCHIO to dalServerVecchio),
            versioneVecchia = silenzioDella07,
            figli = famigliaMigrata,
        )
        assertTrue(partenza.togliAvvisoUnico)
        // Vale quello della 0.8 (più recente), non quello della 0.7.
        assertEquals(mapOf(1L to dalServerVecchio), partenza.noti)
    }

    @Test
    fun `dal secondo giro in poi niente passaggio`() {
        val salvati = mapOf(1L to SilenzioNoto(silente = false, ultimoBattito = "b"), 3L to silenzioDella07)
        val partenza = partenzaSilenzi(salvati, versioneVecchia = silenzioDella07, figli = famigliaMigrata)
        assertFalse(partenza.togliAvvisoUnico)
        assertEquals(salvati, partenza.noti)
        // Un insieme salvato vuoto (nessun dispositivo da sorvegliare) vale "già fatto".
        assertFalse(partenzaSilenzi(emptyMap(), silenzioDella07, famigliaMigrata).togliAvvisoUnico)
    }

    @Test
    fun `il dispositivo ereditato e il primo non scollegato del figlio con l'id piu basso`() {
        assertEquals(1L, dispositivoEreditato(famigliaMigrata)?.id)
        assertEquals(1L, dispositivoEreditato(famigliaMigrata.reversed())?.id)
        val telefonoScollegato = listOf(
            andrea.copy(
                dispositivi = listOf(
                    Dispositivo(id = 4, nome = "Nuovo"),
                    Dispositivo(id = 1, nome = "Telefono", revocato = true),
                ),
            ),
        )
        assertEquals(4L, dispositivoEreditato(telefonoScollegato)?.id)
        assertNull(dispositivoEreditato(listOf(andrea)))
        assertNull(dispositivoEreditato(emptyList()))
    }

    @Test
    fun `su un server 0,7 la 0,8 parte dallo stato della 0,7`() {
        assertEquals(silenzioDella07, silenzioDelServerVecchio(salvati = null, versioneVecchia = silenzioDella07))
        val suo = SilenzioNoto(silente = false, ultimoBattito = "b")
        assertEquals(suo, silenzioDelServerVecchio(mapOf(CHIAVE_SERVER_VECCHIO to suo), silenzioDella07))
        assertNull(silenzioDelServerVecchio(mapOf(1L to suo), silenzioDella07))
    }

    @Test
    fun `il digest mandato oggi dalla 0,7 vale per il figlio con l'id piu basso`() {
        assertEquals(setOf("1|2026-09-24"), digestDopoAggiornamento(null, "2026-09-24", erede = 1))
        // La 0.7 non ne aveva mai mandato uno: si salva vuoto, il passaggio è fatto.
        assertEquals(emptySet<String>(), digestDopoAggiornamento(null, null, erede = 1))
        assertEquals(emptySet<String>(), digestDopoAggiornamento(null, " ", erede = 1))
        // Sul server 0.7 va al "figlio 0".
        assertEquals(setOf("0|2026-09-24"), digestDopoAggiornamento(null, "2026-09-24", erede = CHIAVE_SERVER_VECCHIO))
        // Già fatto: niente da cambiare, anche se il giorno della 0.7 c'è ancora.
        assertNull(digestDopoAggiornamento(setOf("1|2026-09-25"), "2026-09-24", erede = 1))
        assertNull(digestDopoAggiornamento(emptySet(), "2026-09-24", erede = 1))
    }

    @Test
    fun `il digest della 0,8 su un server 0,7 passa al primo figlio quando il server diventa v3`() {
        assertEquals(setOf("1|2026-09-24"), digestDopoAggiornamento(setOf("0|2026-09-24"), null, erede = 1))
        // Se il primo figlio ha già il suo, quello del figlio 0 è vecchio: via.
        assertEquals(
            setOf("1|2026-09-25", "2|2026-09-25"),
            digestDopoAggiornamento(setOf("0|2026-09-24", "1|2026-09-25", "2|2026-09-25"), null, erede = 1),
        )
        // Ancora sul server 0.7: niente da cambiare.
        assertNull(digestDopoAggiornamento(setOf("0|2026-09-24"), null, erede = CHIAVE_SERVER_VECCHIO))
    }

    // --- le regole raggruppate per dispositivo --------------------------------------------

    @Test
    fun `le regole vanno sotto il loro dispositivo, la vita reale negli impegni, in fondo`() {
        val regole = listOf(
            limite(1, "com.zhiliaoapp.musically", 60, dispositivo = 1),
            limite(2, "exe:minecraft.exe", 90, dispositivo = 2),
            vitaReale(3),
            fascia(4, dispositivo = 1),
            limite(5, "sito:youtube.com", 30, dispositivo = 2),
        )
        val gruppi = raggruppaRegole(regole, dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono, computer))))
        assertEquals(
            listOf(GenereGruppo.DISPOSITIVO, GenereGruppo.DISPOSITIVO, GenereGruppo.IMPEGNI),
            gruppi.map { it.genere },
        )
        assertEquals(listOf(1L, 4L), gruppi[0].regole.map { it.id })
        assertEquals("Telefono", gruppi[0].dispositivo?.nome)
        assertEquals(listOf(2L, 5L), gruppi[1].regole.map { it.id })
        assertTrue(gruppi[1].dispositivo!!.computer)
        assertEquals(listOf(3L), gruppi[2].regole.map { it.id })
        assertNull(gruppi[2].dispositivo)
    }

    @Test
    fun `un dispositivo attivo senza regole ha il suo gruppo vuoto, uno scollegato senza regole no`() {
        val scollegato = telefono.copy(id = 3, nome = "Vecchio telefono", revocato = true)
        val gruppi = raggruppaRegole(
            listOf(limite(1, "com.whatsapp", 60, dispositivo = 1)),
            dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono, computer, scollegato))),
        )
        assertEquals(listOf(1L, 2L), gruppi.map { it.dispositivo?.id })
        assertTrue(gruppi[1].regole.isEmpty())
    }

    @Test
    fun `uno scollegato con regole resta, con la sua storia`() {
        val scollegato = telefono.copy(id = 3, nome = "Vecchio telefono", revocato = true)
        val gruppi = raggruppaRegole(
            listOf(limite(7, "com.whatsapp", 60, dispositivo = 3, attiva = true)),
            dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono, scollegato))),
        )
        assertEquals(listOf(1L, 3L), gruppi.map { it.dispositivo?.id })
        assertTrue(gruppi[1].dispositivo!!.revocato)
    }

    @Test
    fun `una regola di un dispositivo che la finestra non elenca non sparisce`() {
        val orfana = limite(9, "exe:steam.exe", 60, dispositivo = null).copy(
            dispositivo = RiferimentoDispositivo(id = 8, nome = "Portatile", tipo = "computer"),
        )
        val gruppi = raggruppaRegole(listOf(orfana), dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono))))
        val suo = gruppi.single { it.dispositivo?.id == 8L }
        assertEquals("Portatile", suo.dispositivo?.nome)
        assertTrue(suo.dispositivo!!.computer)
        assertEquals(listOf(9L), suo.regole.map { it.id })
    }

    @Test
    fun `sulle regole di un dispositivo scollegato non si propone`() {
        val finestra = Finestra(
            regole = listOf(
                limite(1, "com.whatsapp", 60, dispositivo = 1),
                limite(2, "com.whatsapp", 60, dispositivo = 3),
                limite(4, "com.whatsapp", 60, dispositivo = 1, attiva = false),
                vitaReale(5),
            ),
            dispositivi = listOf(telefono, telefono.copy(id = 3, revocato = true)),
        )
        assertEquals(listOf(1L, 5L), regoleProponibili(finestra).map { it.id })
    }

    // --- le strisce dei dispositivi -----------------------------------------------------------

    @Test
    fun `la striscia di ogni dispositivo compare solo con piu dispositivi che contano`() {
        val due = dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono, computer)))
        assertEquals(listOf(1L, 2L), strisceDeiDispositivi(due).map { it.id })
        // Uno solo: la striscia del figlio è già la sua.
        assertTrue(strisceDeiDispositivi(dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono)))).isEmpty())
        assertTrue(strisceDeiDispositivi(dispositiviDellaFinestra(Finestra())).isEmpty())
    }

    @Test
    fun `principio D3 - uno scollegato resta finche ha giorni con dati, come nell'app del figlio`() {
        // Il figlio vede "Computer (non più collegato): 2 su 3": il padre deve
        // vedere la stessa riga, con la stessa regola (RigheDispositivi).
        val scollegatoConDati = computer.copy(
            revocato = true,
            striscia = listOf(
                QuadrettoSemaforo("2026-09-22", "verde"),
                QuadrettoSemaforo("2026-09-23", "rosso"),
                QuadrettoSemaforo("2026-09-24", "grigio"),
            ),
        )
        val conDati = strisceDeiDispositivi(
            dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono, scollegatoConDati))),
        )
        assertEquals(listOf(1L, 2L), conDati.map { it.id })
        assertTrue(conDati.last().revocato)

        // Senza più giorni con dati negli 8 (tutti grigi, o nessun giorno) esce,
        // e col solo telefono attivo non resta niente da mettere accanto.
        val senzaDati = computer.copy(
            revocato = true,
            striscia = listOf(QuadrettoSemaforo("2026-09-24", "grigio")),
        )
        assertTrue(
            strisceDeiDispositivi(dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono, senzaDati)))).isEmpty(),
        )
        assertTrue(
            strisceDeiDispositivi(
                dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono, computer.copy(revocato = true, striscia = emptyList())))),
            ).isEmpty(),
        )

        // Due attivi e uno scollegato senza dati: le due attive, lo scollegato no.
        val tre = dispositiviDellaFinestra(
            Finestra(dispositivi = listOf(telefono, computer, senzaDati.copy(id = 3))),
        )
        assertEquals(listOf(1L, 2L), strisceDeiDispositivi(tre).map { it.id })
    }

    @Test
    fun `un dispositivo attivo senza dati conta lo stesso, come nell'app del figlio`() {
        val nuovo = computer.copy(striscia = listOf(QuadrettoSemaforo("2026-09-24", "grigio")))
        val righe = strisceDeiDispositivi(dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono, nuovo))))
        assertEquals(listOf(1L, 2L), righe.map { it.id })
    }

    @Test
    fun `nel Tempo si guarda il dispositivo scelto, altrimenti il primo non scollegato`() {
        val lista = dispositiviDellaFinestra(
            Finestra(dispositivi = listOf(telefono.copy(revocato = true), computer)),
        )
        assertEquals(2L, dispositivoEffettivo(lista, scelto = null)?.id)
        assertEquals(1L, dispositivoEffettivo(lista, scelto = 1)?.id)
        assertEquals(2L, dispositivoEffettivo(lista, scelto = 42)?.id)
    }

    // --- di chi e una notifica -------------------------------------------------------------

    private fun notifica(figlio: Long?, dispositivo: Long?) = Notifica(
        id = 1,
        tipo = "sforamento",
        messaggio = "",
        tsServer = "2026-09-24T10:00:00+00:00",
        figlioId = figlio,
        dispositivoId = dispositivo,
    )

    private val andreaConDue = andrea.copy(
        dispositivi = listOf(
            Dispositivo(id = 1, nome = "Telefono"),
            Dispositivo(id = 2, nome = "Computer di camera", tipo = "computer"),
        ),
    )
    private val lucaConUno = luca.copy(dispositivi = listOf(Dispositivo(id = 3, nome = "Telefono")))

    @Test
    fun `con piu figli ogni notifica dice di quale figlio e`() {
        val figli = listOf(andreaConDue, lucaConUno)
        assertEquals("Luca", etichettaNotifica(notifica(figlio = 2, dispositivo = 3), figli))
        assertEquals(
            "Andrea · Computer di camera",
            etichettaNotifica(notifica(figlio = 1, dispositivo = 2), figli),
        )
        // Una notifica del figlio intero (vita reale, segno): solo il nome.
        assertEquals("Andrea", etichettaNotifica(notifica(figlio = 1, dispositivo = null), figli))
    }

    @Test
    fun `con un figlio solo la notifica dice il dispositivo solo se sono piu d'uno`() {
        assertEquals(
            "Computer di camera",
            etichettaNotifica(notifica(figlio = 1, dispositivo = 2), listOf(andreaConDue)),
        )
        assertNull(etichettaNotifica(notifica(figlio = 2, dispositivo = 3), listOf(lucaConUno)))
    }

    @Test
    fun `server vecchio o famiglia non letta - nessuna etichetta`() {
        assertNull(etichettaNotifica(notifica(figlio = null, dispositivo = null), listOf(andreaConDue, lucaConUno)))
        assertNull(etichettaNotifica(notifica(figlio = 1, dispositivo = 2), emptyList()))
    }

    // --- il codice di abbinamento -------------------------------------------------------------

    // L'ora del SERVER nella risposta (header Date): 10:00:00.
    private val oraServer: Instant = Instant.parse("2026-09-24T10:00:00Z")

    @Test
    fun `il codice vale quanto dice il server, col suo orologio, mai oltre 15 minuti`() {
        assertEquals(Duration.ofMinutes(15), validitaCodice("2026-09-24T10:15:00+00:00", oraServer))
        // Il server ha risposto con un po' di ritardo: restano 14 minuti e mezzo.
        assertEquals(Duration.ofSeconds(870), validitaCodice("2026-09-24T10:14:30+00:00", oraServer))
        // L'header Date è troncato al secondo: mai oltre i 15 minuti del contratto.
        assertEquals(Duration.ofMinutes(15), validitaCodice("2026-09-24T10:15:00.900+00:00", oraServer))
        // Nessuna ora leggibile (scadenza o header): i 15 minuti del contratto.
        assertEquals(Duration.ofMinutes(15), validitaCodice(null, oraServer))
        assertEquals(Duration.ofMinutes(15), validitaCodice("presto", oraServer))
        assertEquals(Duration.ofMinutes(15), validitaCodice("2026-09-24T10:15:00+00:00", null))
        // Già scaduto per il server: nessun minuto.
        assertEquals(Duration.ZERO, validitaCodice("2026-09-24T09:00:00+00:00", oraServer))
    }

    @Test
    fun `un telefono avanti o indietro non cambia il conto alla rovescia`() {
        // L'ora del telefono non entra da nessuna parte: validità dal server,
        // tempo passato dall'orologio monotono. Un telefono avanti di 5 minuti
        // vede lo stesso codice valido per 15 minuti, non per 10.
        val validita = validitaCodice("2026-09-24T10:15:00+00:00", oraServer)
        assertEquals(900L, secondiRimasti(validita, trascorsoMs = 0))
        assertEquals(600L, secondiRimasti(validita, trascorsoMs = 300_000))
        // Scade solo quando i 15 minuti sono passati davvero.
        assertEquals(1L, secondiRimasti(validita, trascorsoMs = 899_001))
        assertEquals(0L, secondiRimasti(validita, trascorsoMs = 900_000))
    }

    @Test
    fun `il conto alla rovescia si legge come un orologio e non va sotto zero`() {
        val validita = Duration.ofMinutes(15)
        assertEquals(900L, secondiRimasti(validita, 0))
        // Per eccesso: appena arrivato si legge ancora "15:00".
        assertEquals(900L, secondiRimasti(validita, 1))
        assertEquals("15:00", testoContoAllaRovescia(900))
        assertEquals("14:05", testoContoAllaRovescia(845))
        assertEquals("0:09", testoContoAllaRovescia(9))
        assertEquals(0L, secondiRimasti(validita, 930_000))
        // Un tempo passato negativo (non succede con l'orologio monotono) vale zero.
        assertEquals(900L, secondiRimasti(validita, -5_000))
        assertEquals("0:00", testoContoAllaRovescia(-5))
    }

    @Test
    fun `il codice risulta usato quando il dispositivo nuovo si collega`() {
        val daCollegare = Dispositivo(id = 5, nome = "Computer", tipo = "computer", abbinato = false)
        val prima = listOf(andrea.copy(dispositivi = listOf(daCollegare)))
        val dopo = listOf(andrea.copy(dispositivi = listOf(daCollegare.copy(abbinato = true))))
        assertFalse(codiceUsato(prima, dispositivoId = 5, ricollegamento = false))
        assertTrue(codiceUsato(dopo, dispositivoId = 5, ricollegamento = false))
        // Un ricollegamento non si vede dalla famiglia: era già collegato.
        assertFalse(codiceUsato(dopo, dispositivoId = 5, ricollegamento = true))
        // Dispositivo sconosciuto, o scollegato nel frattempo: no.
        assertFalse(codiceUsato(dopo, dispositivoId = 9, ricollegamento = false))
        assertFalse(codiceUsato(dopo, dispositivoId = null, ricollegamento = false))
        val scollegato = listOf(andrea.copy(dispositivi = listOf(daCollegare.copy(abbinato = true, revocato = true))))
        assertFalse(codiceUsato(scollegato, dispositivoId = 5, ricollegamento = false))
    }

    @Test
    fun `sei cifre in due gruppi, il resto com'e`() {
        assertEquals("483 920", codiceADueGruppi("483920"))
        assertEquals("48392", codiceADueGruppi("48392"))
        assertEquals("abc123", codiceADueGruppi("abc123"))
    }

    // --- le creazioni senza risposta ----------------------------------------------------------

    @Test
    fun `dopo una creazione senza risposta, il figlio risulta creato solo se e nuovo e col suo nome`() {
        val prima = listOf(andrea)
        val dopo = listOf(andrea, luca.copy(id = 7, nome = "Marta"))
        assertEquals(7L, figlioCreato(prima, dopo, "  Marta ")?.id)
        assertNull(figlioCreato(prima, dopo, "Luca"))
        // Un omonimo che c'era già non è quello appena creato.
        assertNull(figlioCreato(prima, listOf(andrea), "Andrea"))
        // Non è arrivato niente: si può riprovare senza doppioni.
        assertNull(figlioCreato(prima, prima, "Marta"))
        // Due nuovi con lo stesso nome (un doppione di prima): il più recente.
        assertEquals(9L, figlioCreato(prima, dopo + luca.copy(id = 9, nome = "Marta"), "Marta")?.id)
    }

    @Test
    fun `dopo una creazione senza risposta, il dispositivo risulta creato se e nuovo, del figlio, col nome e il tipo`() {
        val prima = listOf(andreaConDue, lucaConUno)
        val nuovo = Dispositivo(id = 8, nome = "Portatile", tipo = "computer", abbinato = false)
        val dopo = listOf(andreaConDue.copy(dispositivi = andreaConDue.dispositivi + nuovo), lucaConUno)
        assertEquals(8L, dispositivoCreato(prima, dopo, figlioId = 1, nome = "Portatile ", tipo = "computer")?.id)
        // Altro tipo, altro nome, altro figlio: non è lui.
        assertNull(dispositivoCreato(prima, dopo, figlioId = 1, nome = "Portatile", tipo = "telefono"))
        assertNull(dispositivoCreato(prima, dopo, figlioId = 1, nome = "PC", tipo = "computer"))
        assertNull(dispositivoCreato(prima, dopo, figlioId = 2, nome = "Portatile", tipo = "computer"))
        // Il "Telefono" che c'era già non conta come creato.
        assertNull(dispositivoCreato(prima, dopo, figlioId = 1, nome = "Telefono", tipo = "telefono"))
    }

    // --- "Aggiungi un dispositivo" per un telefono che c'è già ---------------------------------

    @Test
    fun `i dispositivi attivi dello stesso tipo, scollegati esclusi`() {
        val figlio = andrea.copy(
            dispositivi = listOf(
                Dispositivo(id = 3, nome = "Vecchio", tipo = "telefono", revocato = true),
                Dispositivo(id = 1, nome = "Telefono", tipo = "telefono"),
                Dispositivo(id = 2, nome = "PC", tipo = "computer"),
                Dispositivo(id = 4, nome = "Nuovo", tipo = "telefono", abbinato = false),
            ),
        )
        assertEquals(listOf(1L, 4L), dispositiviDelloStessoTipo(figlio, "telefono").map { it.id })
        assertEquals(listOf(2L), dispositiviDelloStessoTipo(figlio, "computer").map { it.id })
        assertTrue(dispositiviDelloStessoTipo(andrea, "telefono").isEmpty())
    }

    @Test
    fun `un nome va da 1 a 40 caratteri, spazi esclusi`() {
        assertTrue(nomeValido("Andrea"))
        assertTrue(nomeValido("  Luca  "))
        assertFalse(nomeValido("   "))
        assertFalse(nomeValido("x".repeat(41)))
        assertTrue(nomeValido("x".repeat(40)))
    }

    // --- i siti del computer ------------------------------------------------------------------

    @Test
    fun `sul computer i siti vanno in ordine di minuti, sul telefono di visite`() {
        val computerDelGiorno = SitiGiorno(
            giorno = "2026-09-24",
            totaleDomini = 3,
            domini = listOf(
                SitoVisitato("wikipedia.org", visite = 20, minuti = 5),
                SitoVisitato("youtube.com", visite = 7, minuti = 42),
                SitoVisitato("roblox.com", visite = 2, minuti = 42),
            ),
        )
        assertTrue(sitiConMinuti(computerDelGiorno))
        assertEquals(
            listOf("roblox.com", "youtube.com", "wikipedia.org"),
            sitiOrdinati(computerDelGiorno).map { it.dominio },
        )
        val telefonoDelGiorno = SitiGiorno(
            giorno = "2026-09-24",
            totaleDomini = 2,
            domini = listOf(SitoVisitato("a.it", visite = 3), SitoVisitato("b.it", visite = 9)),
        )
        assertFalse(sitiConMinuti(telefonoDelGiorno))
        assertEquals(listOf("b.it", "a.it"), sitiOrdinati(telefonoDelGiorno).map { it.dominio })
    }

    private val oggi = UsoGiorno(
        giorno = "2026-09-24",
        totaleMinuti = 200,
        app = listOf(UsoApp("exe:minecraft.exe", "Minecraft", minuti = 70, limite = 60, regolaId = 2)),
    )
    private val sitiDiOggi = SitiGiorno(
        giorno = "2026-09-24",
        totaleDomini = 2,
        domini = listOf(
            SitoVisitato("youtube.com", visite = 7, minuti = 42),
            SitoVisitato("wikipedia.org", visite = 3, minuti = 5),
        ),
    )

    @Test
    fun `un limite su un sito prende i minuti dai siti del giorno ed entra nel patto`() {
        val voci = vociSitiNelPatto(
            listOf(limite(5, "sito:youtube.com", 30, dispositivo = 2)),
            oggi,
            sitiDiOggi,
            bonusDelGiorno = 0,
        )
        val voce = voci.single()
        assertEquals("sito:youtube.com", voce.chiave)
        assertEquals(42, voce.minuti)
        assertEquals(30, voce.limite)
        assertTrue(voce.bonusNoto)
        val elenco = elencoTempo(oggi, voci)
        assertEquals(listOf("sito:youtube.com", "exe:minecraft.exe"), elenco.dentroIlPatto.map { it.chiave })
    }

    @Test
    fun `un sito non visitato vale zero solo se la lista e completa`() {
        val regola = listOf(limite(5, "sito:roblox.com", 30, dispositivo = 2))
        assertEquals(0, vociSitiNelPatto(regola, oggi, sitiDiOggi, bonusDelGiorno = 0).single().minuti)
        // Lista tagliata (totale 300, elencati 2): non si sa, niente voce.
        val tagliata = sitiDiOggi.copy(totaleDomini = 300)
        assertTrue(vociSitiNelPatto(regola, oggi, tagliata, bonusDelGiorno = 0).isEmpty())
    }

    @Test
    fun `senza la fotografia dei siti niente voce, mai uno zero finto`() {
        val regola = listOf(limite(5, "sito:youtube.com", 30, dispositivo = 2))
        assertTrue(vociSitiNelPatto(regola, oggi, null, bonusDelGiorno = 0).isEmpty())
        val senzaFotografia = SitiGiorno(giorno = "2026-09-24", totaleDomini = null)
        assertTrue(vociSitiNelPatto(regola, oggi, senzaFotografia, bonusDelGiorno = 0).isEmpty())
    }

    @Test
    fun `siti non leggibili per una parte del giorno - niente zero finto, la voce lo dice`() {
        val regola = listOf(limite(5, "sito:roblox.com", 30, dispositivo = 2))
        val cieco = sitiDiOggi.copy(dnsCifrato = true)
        // Il sito non c'è, ma il programma per un po' non ha letto: non "0 min".
        val voce = vociSitiNelPatto(regola, oggi, cieco, bonusDelGiorno = 0).single()
        assertFalse(voce.minutiNoti)
        assertTrue(voce.parziale)
        assertEquals(30, voce.limite)
        // In fondo al blocco, e mai "oltre".
        assertEquals(0.0, vicinanzaAlLimite(voce), 0.0)
        val elenco = elencoTempo(oggi, listOf(voce))
        assertEquals(listOf("exe:minecraft.exe", "sito:roblox.com"), elenco.dentroIlPatto.map { it.chiave })

        // Il sito c'è: i minuti sono veri, ma possono essere di più, e lo si dice.
        val visto = vociSitiNelPatto(listOf(limite(5, "sito:youtube.com", 30, dispositivo = 2)), oggi, cieco, 0).single()
        assertTrue(visto.minutiNoti)
        assertEquals(42, visto.minuti)
        assertTrue(visto.parziale)

        // Giornata letta tutta: zero vero, niente nota.
        val pieno = vociSitiNelPatto(regola, oggi, sitiDiOggi, bonusDelGiorno = 0).single()
        assertTrue(pieno.minutiNoti)
        assertFalse(pieno.parziale)
    }

    @Test
    fun `con dei bonus quel giorno il bonus del sito non si sa, e oltre non si calcola`() {
        val regola = listOf(limite(5, "sito:youtube.com", 30, dispositivo = 2))
        assertFalse(vociSitiNelPatto(regola, oggi, sitiDiOggi, bonusDelGiorno = 15).single().bonusNoto)
        assertFalse(vociSitiNelPatto(regola, oggi, sitiDiOggi, bonusDelGiorno = null).single().bonusNoto)
    }

    @Test
    fun `un limite gia messo dal server fra le voci del giorno non si duplica`() {
        val giornoConSito = oggi.copy(
            app = oggi.app + UsoApp("sito:youtube.com", null, minuti = 42, limite = 30, regolaId = 5, bonus = 10),
        )
        val voci = vociSitiNelPatto(
            listOf(limite(5, "sito:youtube.com", 30, dispositivo = 2)),
            giornoConSito,
            sitiDiOggi,
            bonusDelGiorno = 10,
        )
        assertTrue(voci.isEmpty())
    }

    @Test
    fun `i limiti su programmi, categorie e regole spente non diventano voci di siti`() {
        val voci = vociSitiNelPatto(
            listOf(
                limite(2, "exe:minecraft.exe", 60, dispositivo = 2),
                limite(3, "categoria:video", 60, dispositivo = 2),
                limite(4, "sito:youtube.com", 30, dispositivo = 2, attiva = false),
                fascia(6, dispositivo = 2),
            ),
            oggi,
            sitiDiOggi,
            bonusDelGiorno = 0,
        )
        assertTrue(voci.isEmpty())
    }

    @Test
    fun `il bonus del giorno di un dispositivo, null se il giorno non c'e`() {
        val giorni = listOf(BonusGiorno("2026-09-23", 0), BonusGiorno("2026-09-24", 15))
        assertEquals(15, bonusDelGiorno(giorni, "2026-09-24"))
        assertEquals(0, bonusDelGiorno(giorni, "2026-09-23"))
        assertNull(bonusDelGiorno(giorni, "2026-09-01"))
    }
}
