package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.dati.BloccoFaccende
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.ConfigStudio
import eu.stgm.pactum.genitore.dati.ContenutoStudio
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StatiConfigStudio
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.dati.StudioPatto
import eu.stgm.pactum.genitore.dati.VoceBlocco
import eu.stgm.pactum.genitore.sync.Vedetta
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * (0.18, contratto v4.0, parte A) I lavori di casa si sbloccano quando un genitore
 * approva la foto: il gruppo "Da approvare", "Approva" dopo aver aperto la foto
 * qui, "Boccia" senza le 24 ore, la domanda che dice se si sblocca, lo stato del
 * blocco SEMPRE dal server (anche rimandato dallo Studio), i numeri della
 * Panoramica e di "Da decidere", le notifiche, e un server più vecchio della v4.0
 * riconosciuto (allora i testi della v3.9).
 */
class LavoriApprovatiTest {

    private val p = ParoleDiProva
    private val roma = ZoneId.of("Europe/Rome")
    private val adesso = Instant.parse("2026-10-07T14:30:00Z") // 16:30 a Roma
    private val oggi = LocalDate.of(2026, 10, 7)
    private val mamma = RiferimentoGenitore(2, "Mamma")

    private fun lavoro(
        id: Long,
        stato: String = StatiFaccenda.DA_FARE,
        bloccoDa: String = "2026-10-07T13:00:00+00:00",
        fotoTs: String? = null,
        foto: Boolean = fotoTs != null,
        daApprovare: Boolean = false,
        confermataTs: String? = null,
        titolo: String = "Svuota la lavastoviglie",
    ) = Faccenda(
        id = id,
        figlioId = 1,
        titolo = titolo,
        stato = stato,
        bloccoDa = bloccoDa,
        creataTs = "2026-10-07T12:00:00+00:00",
        creataDa = mamma,
        fotoTs = fotoTs,
        foto = foto,
        daApprovare = daApprovare,
        confermataTs = confermataTs,
    )

    private fun daApprovareFoto(id: Long, fotoTs: String = "2026-10-07T14:10:00+00:00") =
        lavoro(id, StatiFaccenda.FATTA, fotoTs = fotoTs, daApprovare = true)

    private fun bloccoServer(
        attivo: Boolean = true,
        rimandato: Boolean? = false,
        vararg aperti: Pair<Long, String>,
    ) = BloccoFaccende(
        attivo = attivo,
        dal = if (attivo) "2026-10-07T13:00:00+00:00" else null,
        prossimo = null,
        rimandato = rimandato,
        daFare = aperti.map { (id, da) -> VoceBlocco(id = id, titolo = "L$id", bloccoDa = da) },
    )

    // --- lo stato del blocco viene dal server -----------------------------------------------

    @Test
    fun `lo stato del blocco e quello del server, anche con le foto da approvare`() {
        // Un lavoro con la foto e basta: l'app di prima lo direbbe sbloccato; il server dice di no.
        val faccende = listOf(daApprovareFoto(1))
        val stato = statoBlocco(faccende, adesso, bloccoServer(true, false, 1L to "2026-10-07T13:00:00+00:00"))
        assertTrue(stato.attivo)
        assertFalse(stato.rimandato)
        assertEquals(0, stato.daFare)
        assertEquals(1, stato.daApprovare)
        assertTrue(stato.conApprovazione)
        assertEquals("Blocco attivo dalle 15:00", testoStatoBlocco(p, stato, roma, oggi))
        assertEquals("Una foto da approvare", testoQuanteDaApprovare(p, stato.daApprovare))
        assertEquals("3 foto da approvare", testoQuanteDaApprovare(p, 3))
        assertNull(testoQuanteDaApprovare(p, 0))
        // E MAI ricalcolato: col blocco del server spento, spento, anche con un da fare scaduto.
        val spento = statoBlocco(listOf(lavoro(2)), adesso, bloccoServer(false, false))
        assertFalse(spento.attivo)
    }

    @Test
    fun `rimandato dallo Studio si dice, e non e in evidenza`() {
        val stato = statoBlocco(listOf(lavoro(1)), adesso, bloccoServer(true, true, 1L to "2026-10-07T13:00:00+00:00"))
        assertTrue(stato.attivo)
        assertTrue(stato.rimandato)
        assertEquals("Il blocco parte a fine Studio", testoStatoBlocco(p, stato, roma, oggi))
        assertEquals("Il blocco dei lavori parte a fine Studio", testoStatoBlocco(p, stato, roma, oggi, perPanoramica = true))
        // Rimandato vuol dire anche attivo: un "rimandato" con attivo falso non c'è.
        val strano = statoBlocco(emptyList(), adesso, BloccoFaccende(attivo = false, rimandato = true))
        assertFalse(strano.rimandato)
    }

    @Test
    fun `un server piu vecchio della v4 si riconosce e il blocco si calcola come prima`() {
        // v3.9: il `blocco` della finestra senza `rimandato` (e GET /api/faccende senza `blocco`).
        val vecchio = statoBlocco(listOf(lavoro(1)), adesso, BloccoFaccende(attivo = true, dal = "2026-10-07T13:00:00+00:00"))
        assertFalse(vecchio.conApprovazione)
        assertFalse(vecchio.rimandato)
        // Senza blocco del server (la scheda Lavori su un server v3.9): il calcolo di prima.
        val calcolato = statoBlocco(listOf(lavoro(1)), adesso)
        assertTrue(calcolato.attivo)
        assertFalse(calcolato.conApprovazione)
    }

    // --- i gruppi e i pulsanti ---------------------------------------------------------------

    @Test
    fun `le foto da approvare stanno nel loro gruppo, dalla piu vecchia, e non tra i fatti`() {
        val nuova = daApprovareFoto(1, "2026-10-07T14:20:00+00:00")
        val vecchia = daApprovareFoto(2, "2026-10-07T14:00:00+00:00")
        val approvata = lavoro(3, StatiFaccenda.FATTA, fotoTs = "2026-10-07T13:00:00+00:00", confermataTs = "2026-10-07T13:30:00+00:00")
        val prima = lavoro(4, StatiFaccenda.FATTA, fotoTs = "2026-10-06T13:00:00+00:00") // foto di prima della v4.0
        val gruppi = faccendeInGruppi(listOf(nuova, approvata, vecchia, prima, lavoro(5)))
        assertEquals(listOf(2L, 1L), gruppi.daApprovare.map { it.id })
        assertEquals(listOf(3L, 4L), gruppi.fatte.map { it.id })
        assertEquals(listOf(5L), gruppi.daFare.map { it.id })
        assertEquals(2, fotoDaApprovare(listOf(nuova, vecchia, approvata, prima, nuova)))
    }

    @Test
    fun `Approva compare solo dopo aver aperto la foto qui, Boccia c'e sempre finche nessuno approva`() {
        val f = daApprovareFoto(1)
        assertEquals(AzioniFatto(PulsanteFatto.GUARDA_FOTO, boccia = true, confermato = false), azioniFatto(f, adesso, vista = false, conConferma = true))
        assertEquals(AzioniFatto(PulsanteFatto.APPROVA, boccia = true, confermato = false), azioniFatto(f, adesso, vista = true, conConferma = true))
        // Senza le 24 ore: anche tre giorni dopo la foto si boccia ancora.
        val traTreGiorni = adesso.plusSeconds(3 * 24 * 3600)
        assertTrue(azioniFatto(f, traTreGiorni, vista = true, conConferma = true).boccia)
        assertEquals(Bocciabile.SenzaScadenza, bocciabile(f, traTreGiorni))
        assertEquals("Puoi bocciarlo finché nessuno lo approva.", testoBocciabile(p, Bocciabile.SenzaScadenza))
        // Senza il file (non dovrebbe succedere: le foto da approvare non si cancellano):
        // niente "Approva" (nessuno approva una foto che non ha visto), "Boccia" sì.
        assertEquals(AzioniFatto(PulsanteFatto.NESSUNO, boccia = true, confermato = false), azioniFatto(f.copy(foto = false), adesso, vista = true, conConferma = true))
        // Approvato: è un lavoro fatto e confermato, come nella v3.9.
        val approvato = f.copy(confermataTs = "2026-10-07T14:20:00+00:00", confermataDa = mamma)
        assertFalse(daApprovare(approvato))
        assertEquals(AzioniFatto(PulsanteFatto.GUARDA_FOTO, boccia = false, confermato = true), azioniFatto(approvato, adesso, vista = true, conConferma = true))
        // Le foto da approvare non sono "da guardare entro 24 ore": hanno il loro conto.
        assertEquals(0, fotoDaGuardare(listOf(f), adesso))
    }

    @Test
    fun `una foto di prima della v4 resta come nella v3_9`() {
        val vecchia = lavoro(1, StatiFaccenda.FATTA, fotoTs = "2026-10-07T10:00:00+00:00")
        assertEquals(PulsanteFatto.SEGNA_SVOLTO, azioniFatto(vecchia, adesso, vista = true, conConferma = true).principale)
        assertTrue(bocciabile(vecchia, adesso) is Bocciabile.Si)
        assertEquals(Bocciabile.Scaduta, bocciabile(vecchia, adesso.plusSeconds(24 * 3600)))
    }

    // --- la domanda prima di approvare ----------------------------------------------------------

    @Test
    fun `la domanda dice che si sblocca solo se e l'ultimo che blocca`() {
        val f = daApprovareFoto(1)
        val ultimo = bloccoServer(true, false, 1L to "2026-10-07T13:00:00+00:00")
        assertEquals(EffettoApprovazione.SBLOCCA, effettoApprovazione(f, ultimo, adesso))
        assertEquals(
            "Non si potrà più bocciare. Telefono e computer di Luca si sbloccano.",
            testoDomandaApprova(p, EffettoApprovazione.SBLOCCA, "Luca"),
        )
        assertEquals("Approvi «Svuota la lavastoviglie»?", p.testo(eu.stgm.pactum.genitore.R.string.approva_titolo, f.titolo))
        // Un altro lavoro (da fare o da approvare) blocca ancora: niente promesse.
        val altri = bloccoServer(true, false, 1L to "2026-10-07T13:00:00+00:00", 2L to "2026-10-07T13:30:00+00:00")
        assertEquals(EffettoApprovazione.NESSUNO, effettoApprovazione(f, altri, adesso))
        assertEquals("Non si potrà più bocciare.", testoDomandaApprova(p, EffettoApprovazione.NESSUNO, "Luca"))
        // Un altro aperto che blocca più tardi: adesso si sblocca (poi riparte da solo).
        val dopo = bloccoServer(true, false, 1L to "2026-10-07T13:00:00+00:00", 2L to "2026-10-07T18:00:00+00:00")
        assertEquals(EffettoApprovazione.SBLOCCA, effettoApprovazione(f, dopo, adesso))
        // Il blocco non è ancora partito (foto mandata in anticipo): niente da dire.
        val nonPartito = bloccoServer(false, false, 1L to "2026-10-07T18:00:00+00:00")
        assertEquals(EffettoApprovazione.NESSUNO, effettoApprovazione(f, nonPartito, adesso))
        // Rimandato dallo Studio: il blocco non partirà a fine Studio.
        val rimandato = bloccoServer(true, true, 1L to "2026-10-07T13:00:00+00:00")
        assertEquals(EffettoApprovazione.NON_PARTE_A_FINE_STUDIO, effettoApprovazione(f, rimandato, adesso))
        assertEquals(
            "Non si potrà più bocciare. Il blocco di Luca non partirà a fine Studio.",
            testoDomandaApprova(p, EffettoApprovazione.NON_PARTE_A_FINE_STUDIO, "Luca"),
        )
        // Server più vecchio (senza `rimandato`) o senza blocco: mai "si sbloccano".
        assertEquals(EffettoApprovazione.NESSUNO, effettoApprovazione(f, ultimo.copy(rimandato = null), adesso))
        assertEquals(EffettoApprovazione.NESSUNO, effettoApprovazione(f, null, adesso))
        // Senza nome del figlio, la frase senza nome.
        assertEquals(
            "Non si potrà più bocciare. Telefono e computer di tuo figlio si sbloccano.",
            testoDomandaApprova(p, EffettoApprovazione.SBLOCCA, " "),
        )
    }

    @Test
    fun `dopo l'approvazione si dice che cosa e successo`() {
        assertEquals("Approvato.", testoApprovato(p, EffettoApprovazione.NESSUNO))
        assertEquals("Approvato: telefono e computer si sbloccano.", testoApprovato(p, EffettoApprovazione.SBLOCCA))
        assertEquals("Approvato: il blocco non partirà a fine Studio.", testoApprovato(p, EffettoApprovazione.NON_PARTE_A_FINE_STUDIO))
    }

    @Test
    fun `i rifiuti dell'approvazione hanno il loro motivo`() {
        assertEquals(
            "Non si può approvare: un altro genitore l'ha già approvato o bocciato. Ho riletto l'elenco.",
            messaggioRifiutoFaccende(p, CodiciErrore.NON_CONFERMABILE, GestoFaccende.APPROVA, "Luca"),
        )
        assertEquals(
            "La foto è cambiata: guardala di nuovo prima di approvarla.",
            messaggioRifiutoFaccende(p, CodiciErrore.FOTO_CAMBIATA, GestoFaccende.APPROVA, "Luca"),
        )
        // "Segna come svolto" (foto di prima) resta com'era.
        assertEquals(
            "Non si può segnare come svolto: qualcuno l'ha già segnato o bocciato.",
            messaggioRifiutoFaccende(p, CodiciErrore.NON_CONFERMABILE, GestoFaccende.CONFERMA, "Luca"),
        )
    }

    // --- i testi che cambiano col server v4.0 ---------------------------------------------------

    @Test
    fun `con un server v4 i testi dicono che sblocca l'approvazione, con uno vecchio la foto`() {
        assertEquals(
            "Finché un genitore non ha approvato la foto di ogni lavoro di Luca, telefono e computer restano bloccati, tranne poche app fondamentali sul telefono.",
            spiegaFaccende(p, "Luca", conApprovazione = true),
        )
        assertTrue(spiegaFaccende(p, "Luca").startsWith("Finché Luca non ha mandato la foto"))
        assertEquals(
            "Il blocco parte appena li dai: telefono e computer di Luca restano bloccati finché un genitore non approva la foto di ogni lavoro.",
            testoInizioBlocco(p, null, "Luca", conApprovazione = true),
        )
        assertTrue(testoInizioBlocco(p, null, "Luca").endsWith("finché non manda la foto di ogni lavoro."))
        assertEquals("Da approvare · foto oggi 16:10", testoRisultato(p, daApprovareFoto(1), roma, oggi))
        // Confermato = approvato, dalla v4.0: lo si dice con la parola del pulsante.
        val approvato = daApprovareFoto(1).copy(confermataTs = "2026-10-07T14:20:00+00:00", confermataDa = mamma)
        assertEquals("Approvato da te · oggi 16:20", testoConfermato(p, approvato, mamma, roma, oggi, approvato = true))
        assertEquals("Approvato da Mamma · oggi 16:20", testoConfermato(p, approvato, null, roma, oggi, approvato = true))
        assertEquals("Confermato da Mamma · oggi 16:20", testoConfermato(p, approvato, null, roma, oggi))
    }

    // --- le notifiche ---------------------------------------------------------------------------

    private val luca = Figlio(id = 1, nome = "Luca")

    private fun notifica(tipo: String, messaggio: String, payload: Map<String, String> = emptyMap()) = Notifica(
        id = 9,
        tipo = tipo,
        messaggio = messaggio,
        payload = buildJsonObject { payload.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
        tsServer = "2026-10-07T14:10:00+00:00",
        figlioId = 1,
    )

    @Test
    fun `la foto da approvare si annuncia come tale, solo se il server la chiede`() {
        val v40 = notifica(
            "faccenda_fatta",
            "Luca ha mandato la foto di «Rifai il letto»: aspetta la vostra approvazione",
            mapOf("faccenda_id" to "5", "titolo" to "Rifai il letto"),
        )
        assertEquals(
            TestoNotifica("Foto da approvare", "Luca ha mandato la foto di «Rifai il letto»: tocca per vedere la foto e approvarla."),
            testoNotifica(p, v40, emptyMap(), listOf(luca)),
        )
        assertEquals(
            TestoNotifica("Foto da approvare", "Luca ha mandato la foto di «Rifai il letto»: aspetta l'approvazione."),
            testoNotifica(p, v40, emptyMap(), listOf(luca), nellaTendina = false),
        )
        // Server v3.9: i testi di prima, senza promettere l'approvazione.
        val v39 = v40.copy(messaggio = "Luca ha fatto «Rifai il letto»")
        assertEquals(
            TestoNotifica("Lavoro fatto", "Luca ha fatto «Rifai il letto»: tocca per vedere la foto."),
            testoNotifica(p, v39, emptyMap(), listOf(luca)),
        )
        // Toccarla apre la foto di quel lavoro, nei Lavori.
        assertEquals(5L, faccendaDellaNotifica(v40))
        assertEquals(MainActivity.DEST_FACCENDE, Vedetta.destinazionePerTipo("faccenda_fatta"))
    }

    @Test
    fun `l'approvazione dell'altro genitore porta ai Lavori, col messaggio del server`() {
        val n = notifica("faccenda_confermata", "Papà ha approvato «Rifai il letto»: telefono e computer sbloccati", mapOf("faccenda_id" to "5"))
        assertEquals(
            TestoNotifica("Lavoro approvato", "Papà ha approvato «Rifai il letto»: telefono e computer sbloccati"),
            testoNotifica(p, n, emptyMap(), listOf(luca)),
        )
        assertTrue(notificaDiFaccende("faccenda_confermata"))
        assertEquals(MainActivity.DEST_FACCENDE, Vedetta.destinazionePerTipo("faccenda_confermata"))
        assertEquals(Schermo.SuScheda(Scheda.LAVORI), destinazioneDellaRiga(n).schermo)
        // Non apre una foto: l'ha già guardata chi l'ha approvata.
        assertNull(faccendaDellaNotifica(n))
    }

    // --- Panoramica e Da decidere ---------------------------------------------------------------

    @Test
    fun `nella Panoramica le foto da approvare stanno nella riga del blocco, o in una loro`() {
        val blocco = StatoBlocco(attivo = true, dal = adesso.minusSeconds(600), prossimo = null, daFare = 1, daApprovare = 2)
        val conBlocco = righeInCima(0, blocco, 0, emptyList(), true, adesso, adesso, true, false, null, fotoDaApprovare = 2)
        assertEquals(listOf<RigaInCima>(RigaInCima.Blocco(blocco, 0, 2)), conBlocco)
        val senzaBlocco = righeInCima(
            0, StatoBlocco(false, null, null, 0), 1, emptyList(), true, adesso, adesso, true, false, null, fotoDaApprovare = 2,
        )
        assertEquals(listOf(RigaInCima.FotoDaApprovare(2), RigaInCima.FotoDaGuardare(1)), senzaBlocco)
        // Il blocco rimandato è una riga anche lui (è dovuto).
        val rimandato = blocco.copy(rimandato = true)
        assertEquals(RigaInCima.Blocco(rimandato, 0, 0), righeInCima(0, rimandato, 0, emptyList(), true, adesso, adesso, true, false, null).first())
    }

    @Test
    fun `le foto da approvare e lo Studio contano in Da decidere`() {
        // Gli altri figli: dalla famiglia.
        val sara = Figlio(id = 2, nome = "Sara", proposteDaDecidere = 1, faccendeDaApprovare = 2, studioDaApprovare = 1)
        assertEquals(4, quanteDaDecidere(sara))
        // Il figlio scelto: dalla finestra (le stesse letture della lista).
        val config = ConfigStudio(stato = StatiConfigStudio.IN_ATTESA, versione = 3, inAttesa = ContenutoStudio(giorni = listOf("lun")))
        val finestra = Finestra(
            faccende = listOf(daApprovareFoto(1), daApprovareFoto(2), lavoro(3)),
            studio = StudioPatto(config = config),
        )
        assertEquals(3, quanteDaDecidereDellaFinestra(finestra, emptyMap(), emptyMap(), null, null))
        // La configurazione appena decisa da qui non conta più, finché la finestra è di prima.
        assertEquals(2, quanteDaDecidereDellaFinestra(finestra, emptyMap(), emptyMap(), null, null, studioDecisaVersione = 3))
        // Un server vecchio non ne manda: zero.
        assertEquals(0, quanteDaDecidereV40(Finestra(faccende = listOf(lavoro(3)))))
        // La lista ha le stesse voci, dalla più vecchia.
        val voci = vociDaDecidere(
            proposte = emptyList(),
            sessioni = emptyList(),
            dichiarazioni = emptyList(),
            foto = finestra.faccende.orEmpty(),
            studio = richiestaStudio(config),
        )
        assertEquals(listOf("foto-1", "foto-2", "studio"), voci.map { it.chiave })
    }
}
