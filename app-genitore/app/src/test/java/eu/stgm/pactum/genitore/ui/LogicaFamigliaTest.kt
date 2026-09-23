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
    fun `la striscia di ogni dispositivo compare solo con piu dispositivi attivi`() {
        val due = dispositiviDellaFinestra(Finestra(dispositivi = listOf(telefono, computer)))
        assertEquals(listOf(1L, 2L), strisceDeiDispositivi(due).map { it.id })
        val unoEScollegato = dispositiviDellaFinestra(
            Finestra(dispositivi = listOf(telefono, computer.copy(revocato = true))),
        )
        assertTrue(strisceDeiDispositivi(unoEScollegato).isEmpty())
        assertTrue(strisceDeiDispositivi(dispositiviDellaFinestra(Finestra())).isEmpty())
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

    private val arrivo: Instant = Instant.parse("2026-09-24T10:00:00Z")

    @Test
    fun `il codice scade dopo quanto dice il server, mai oltre 15 minuti`() {
        assertEquals(
            arrivo.plus(Duration.ofMinutes(15)),
            scadenzaCodice("2026-09-24T10:15:00+00:00", arrivo),
        )
        // Telefono in ritardo di 5 minuti: il server sembra lontano 20, ma sono 15.
        assertEquals(
            arrivo.plus(Duration.ofMinutes(15)),
            scadenzaCodice("2026-09-24T10:20:00+00:00", arrivo),
        )
        // Telefono avanti di 5 minuti: si conta prudente, 10.
        assertEquals(
            arrivo.plus(Duration.ofMinutes(10)),
            scadenzaCodice("2026-09-24T10:10:00+00:00", arrivo),
        )
        // Nessuna scadenza leggibile: i 15 minuti del contratto.
        assertEquals(arrivo.plus(Duration.ofMinutes(15)), scadenzaCodice(null, arrivo))
        assertEquals(arrivo.plus(Duration.ofMinutes(15)), scadenzaCodice("presto", arrivo))
        // Già scaduto: nessun minuto.
        assertEquals(arrivo, scadenzaCodice("2026-09-24T09:00:00+00:00", arrivo))
    }

    @Test
    fun `il conto alla rovescia si legge come un orologio e non va sotto zero`() {
        val scadenza = arrivo.plus(Duration.ofMinutes(15))
        assertEquals(900L, secondiRimasti(scadenza, arrivo))
        assertEquals("15:00", testoContoAllaRovescia(900))
        assertEquals("14:05", testoContoAllaRovescia(845))
        assertEquals("0:09", testoContoAllaRovescia(9))
        assertEquals(0L, secondiRimasti(scadenza, scadenza.plusSeconds(30)))
        assertEquals("0:00", testoContoAllaRovescia(-5))
    }

    @Test
    fun `sei cifre in due gruppi, il resto com'e`() {
        assertEquals("483 920", codiceADueGruppi("483920"))
        assertEquals("48392", codiceADueGruppi("48392"))
        assertEquals("abc123", codiceADueGruppi("abc123"))
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
