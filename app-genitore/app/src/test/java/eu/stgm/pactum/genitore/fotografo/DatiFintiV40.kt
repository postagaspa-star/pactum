package eu.stgm.pactum.genitore.fotografo

import eu.stgm.pactum.genitore.dati.BloccoFaccende
import eu.stgm.pactum.genitore.dati.ChiStudio
import eu.stgm.pactum.genitore.dati.ConfigStudio
import eu.stgm.pactum.genitore.dati.ContenutoStudio
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Famiglia
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.ListaComputerStudio
import eu.stgm.pactum.genitore.dati.ListaTelefonoStudio
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.PaccoFaccende
import eu.stgm.pactum.genitore.dati.PaccoNotifiche
import eu.stgm.pactum.genitore.dati.PaccoStudio
import eu.stgm.pactum.genitore.dati.PaccoSvolteStudio
import eu.stgm.pactum.genitore.dati.PaccoVersioniStudio
import eu.stgm.pactum.genitore.dati.PartenzaStudio
import eu.stgm.pactum.genitore.dati.RiferimentoDispositivo
import eu.stgm.pactum.genitore.dati.SessioneChiusaDalloStudio
import eu.stgm.pactum.genitore.dati.StudioDelBlocco
import eu.stgm.pactum.genitore.dati.StudioPatto
import eu.stgm.pactum.genitore.dati.StudioSvolto
import eu.stgm.pactum.genitore.dati.TrattoStudio
import eu.stgm.pactum.genitore.dati.VoceBlocco
import eu.stgm.pactum.genitore.fotografo.DatiFinti.LUCA
import eu.stgm.pactum.genitore.fotografo.DatiFinti.MAMMA
import eu.stgm.pactum.genitore.fotografo.DatiFinti.PAPA
import eu.stgm.pactum.genitore.fotografo.DatiFinti.PC_LUCA
import eu.stgm.pactum.genitore.fotografo.DatiFinti.SARA
import eu.stgm.pactum.genitore.fotografo.DatiFinti.TEL_LUCA
import eu.stgm.pactum.genitore.fotografo.DatiFinti.giorniFa
import eu.stgm.pactum.genitore.fotografo.DatiFinti.minutiFa
import eu.stgm.pactum.genitore.fotografo.DatiFinti.oggi
import eu.stgm.pactum.genitore.fotografo.DatiFinti.traMinuti
import eu.stgm.pactum.genitore.fotografo.ServerFinto.Companion.corpo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * (0.18, contratto v4.0) I DATI FINTI di un server dalla v4.0, sopra quelli di
 * sempre ([DatiFinti]): due foto di Luca da approvare, il blocco che aspetta la
 * fine dello Studio, lo Studio in corso con i suoi tratti, gli Studi dei giorni
 * prima (chiuso da Luca, chiuso da Papà, non chiuso), la configurazione approvata
 * e un cambio da approvare, le versioni approvate e le notifiche nuove.
 */
object DatiFintiV40 {

    private val telLuca = RiferimentoDispositivo(TEL_LUCA, "Telefono", "telefono")

    // --- I lavori di casa: due foto da approvare ---------------------------------------------

    fun faccendeLuca(): List<Faccenda> = listOf(
        Faccenda(
            id = 101, figlioId = LUCA, titolo = "Svuota la lavastoviglie", nota = "Anche le posate, per favore",
            stato = "da_fare", bloccoDa = minutiFa(75), creataTs = minutiFa(75), creataDa = MAMMA,
        ),
        Faccenda(
            id = 103, figlioId = LUCA, titolo = "Porta fuori la spazzatura",
            stato = "fatta", bloccoDa = minutiFa(75), creataTs = minutiFa(80), creataDa = MAMMA,
            fotoTs = minutiFa(40), foto = true, chiusaTs = minutiFa(40), daApprovare = true,
        ),
        Faccenda(
            id = 107, figlioId = LUCA, titolo = "Rifai il letto",
            stato = "fatta", bloccoDa = minutiFa(75), creataTs = minutiFa(80), creataDa = PAPA,
            fotoTs = minutiFa(12), foto = true, chiusaTs = minutiFa(12), daApprovare = true, bocciature = 1,
            ultimaBocciatura = eu.stgm.pactum.genitore.dati.Bocciatura(ts = minutiFa(30), nota = "Manca il cuscino", da = MAMMA),
        ),
        Faccenda(
            id = 104, figlioId = LUCA, titolo = "Stendi i panni",
            stato = "fatta", bloccoDa = giorniFa(1, 16), creataTs = giorniFa(1, 15), creataDa = PAPA,
            fotoTs = giorniFa(1, 17, 12), foto = true, chiusaTs = giorniFa(1, 17, 12),
            confermataTs = giorniFa(1, 18, 5), confermataDa = PAPA,
        ),
    )

    /** Il blocco del server: dovuto da 75 minuti; [rimandato] = aspetta la fine dello Studio. */
    fun bloccoLuca(rimandato: Boolean, studioId: Long? = 45) = BloccoFaccende(
        attivo = true,
        dal = minutiFa(75),
        prossimo = null,
        rimandato = rimandato,
        studio = StudioDelBlocco(inCorso = rimandato, id = studioId.takeIf { rimandato }, inizioTs = minutiFa(50).takeIf { rimandato }),
        daFare = faccendeLuca().filter { it.stato == "da_fare" || it.daApprovare }.map {
            VoceBlocco(id = it.id, titolo = it.titolo, bloccoDa = it.bloccoDa, stato = it.stato, fotoTs = it.fotoTs)
        },
    )

    /** Solo l'ultima foto da approvare blocca ancora: approvarla sblocca. */
    fun faccendeUltimaDaApprovare(): List<Faccenda> = faccendeLuca().filter { it.id != 101L && it.id != 103L }

    fun bloccoUltimaDaApprovare() = BloccoFaccende(
        attivo = true,
        dal = minutiFa(75),
        rimandato = false,
        studio = StudioDelBlocco(),
        daFare = listOf(VoceBlocco(id = 107, titolo = "Rifai il letto", bloccoDa = minutiFa(75), stato = "fatta", fotoTs = minutiFa(12))),
    )

    // --- La configurazione dello Studio ------------------------------------------------------

    private val nomiTelefono = mapOf(
        "eu.spaggiari.classevivafamiglia" to "ClasseViva",
        "com.google.android.calculator" to "Calcolatrice",
        "com.duolingo" to "Duolingo",
    )

    fun approvata() = ContenutoStudio(
        giorni = listOf("lun", "mar", "mer", "gio", "ven"),
        inizio = "15:00",
        chiusuraMinima = "16:00",
        minutiMinimi = 60,
        telefono = ListaTelefonoStudio(
            app = listOf("eu.spaggiari.classevivafamiglia", "com.google.android.calculator"),
            nomi = nomiTelefono,
        ),
        computer = ListaComputerStudio(
            programmi = listOf("exe:winword.exe", "sito:classeviva.it", "sito:wikipedia.org"),
            nomi = mapOf("exe:winword.exe" to "Word"),
            firme = mapOf("exe:winword.exe" to "Microsoft Corporation"),
        ),
        orariDal = oggi().minusDays(5).toString(),
        approvataTs = giorniFa(6, 19, 30),
        decisaDa = PAPA,
    )

    fun inAttesa() = approvata().copy(
        inizio = "14:30",
        telefono = ListaTelefonoStudio(
            app = listOf("eu.spaggiari.classevivafamiglia", "com.google.android.calculator", "com.duolingo"),
            nomi = nomiTelefono,
        ),
        computer = ListaComputerStudio(
            programmi = listOf("exe:winword.exe", "exe:geogebra.exe", "sito:classeviva.it"),
            nomi = mapOf("exe:winword.exe" to "Word", "exe:geogebra.exe" to "GeoGebra"),
            firme = mapOf("exe:winword.exe" to "Microsoft Corporation", "exe:geogebra.exe" to "International GeoGebra Institute"),
        ),
        orariDal = null,
        approvataTs = null,
        decisaDa = null,
        richiestaTs = minutiFa(20),
        da = telLuca,
    )

    fun config(conRichiesta: Boolean = true) = ConfigStudio(
        stato = "approvata",
        versione = 6,
        approvata = approvata(),
        inAttesa = if (conRichiesta) inAttesa() else null,
        motivazione = null,
    )

    fun versioni() = PaccoVersioniStudio(
        listOf(
            approvata().copy(versione = 5),
            approvata().copy(
                versione = 3, inizio = "15:30", chiusuraMinima = "16:30", decisaDa = MAMMA,
                approvataTs = giorniFa(9, 20), orariDal = oggi().minusDays(8).toString(),
                computer = ListaComputerStudio(programmi = listOf("exe:winword.exe"), nomi = mapOf("exe:winword.exe" to "Word")),
            ),
            approvata().copy(
                versione = 1, decisaDa = null, approvataTs = giorniFa(12, 9),
                orariDal = oggi().minusDays(11).toString(),
                telefono = ListaTelefonoStudio(), computer = ListaComputerStudio(),
            ),
        ),
    )

    // --- Gli Studi --------------------------------------------------------------------------

    fun studioInCorso() = StudioSvolto(
        id = 45, origine = "automatica", giorno = oggi().toString(),
        inizioTs = minutiFa(50), contaDal = minutiFa(50), chiudibileDal = traMinuti(10),
        minutiMinimi = 60, minutiAttivita = 38, chiudibile = false,
        partenze = listOf(PartenzaStudio(oggi().toString(), minutiFa(50), traMinuti(10), 60)),
        tratti = listOf(
            tratto("t1", "compiti", "matematica", minuti = 22, fineMinutiFa = 26),
            tratto("t2", "lavori_di_casa", null, minuti = 6, fineMinutiFa = 18, faccenda = 107),
            TrattoStudio(
                id = "t3", dispositivoId = TEL_LUCA, tipo = "compiti", parola = "inglese",
                inizio = Instant.now().minusSeconds(10 * 60).toEpochMilli(), esito = "in_corso", secondi = 600,
            ),
        ),
        sessioneChiusa = SessioneChiusaDalloStudio(id = 73, nome = "Giochi"),
        inCorso = true,
    )

    private fun tratto(
        id: String,
        tipo: String,
        parola: String?,
        minuti: Int,
        fineMinutiFa: Long,
        faccenda: Long? = null,
        esito: String = "finito",
        giorniFaN: Long = 0,
    ): TrattoStudio {
        val fine = Instant.now().minusSeconds(giorniFaN * 86_400 + fineMinutiFa * 60)
        return TrattoStudio(
            id = id, dispositivoId = TEL_LUCA, tipo = tipo, parola = parola, faccendaId = faccenda,
            fine = fine.toEpochMilli(), secondi = minuti * 60L, secondiContati = minuti * 60L, minuti = minuti,
            esito = esito, conta = true,
        )
    }

    fun svolte() = listOf(
        StudioSvolto(
            id = 44, origine = "automatica", giorno = oggi().minusDays(1).toString(),
            inizioTs = giorniFa(1, 15), chiudibileDal = giorniFa(1, 16), minutiMinimi = 60,
            minutiAttivita = 65, minutiAllaChiusura = 65,
            tratti = listOf(
                tratto("s1", "compiti", "matematica", 35, 0, giorniFaN = 1),
                tratto("s2", "lavori_di_casa", null, 10, 0, faccenda = 104, giorniFaN = 1),
                tratto("s3", "altro", "allenamento", 20, 0, giorniFaN = 1),
            ),
            fineTs = giorniFa(1, 16, 40), chiusura = "figlio", chiusaDa = ChiStudio(TEL_LUCA, "Telefono", "telefono"),
            dichiarazione = "Esercizi di matematica a pagina 112, poi i panni e la corsa al parco.",
            dichiarazioneTs = giorniFa(1, 16, 40),
        ),
        StudioSvolto(
            id = 43, origine = "manuale", giorno = oggi().minusDays(2).toString(),
            inizioTs = giorniFa(2, 14, 20), avviatoDa = ChiStudio(TEL_LUCA, "Telefono", "telefono"),
            chiudibileDal = giorniFa(2, 16), minutiMinimi = 60, minutiAttivita = 12, minutiAllaChiusura = 12,
            tratti = listOf(tratto("r1", "compiti", "storia", 12, 0, esito = "interrotto", giorniFaN = 2)),
            fineTs = giorniFa(2, 15, 5), chiusura = "genitore", chiusaDa = ChiStudio(2, "Papà"),
            motivo = "Visita dal dentista alle 15:30",
        ),
        StudioSvolto(
            id = 42, origine = "automatica", giorno = oggi().minusDays(3).toString(),
            inizioTs = giorniFa(3, 15), chiudibileDal = giorniFa(3, 16), minutiMinimi = 60,
            minutiAttivita = 40, minutiAllaChiusura = 40,
            tratti = listOf(tratto("q1", "compiti", null, 40, 0, giorniFaN = 3)),
            fineTs = giorniFa(2, 0, 0), chiusura = "non_chiuso",
        ),
    )

    // --- Famiglia, finestra, notifiche --------------------------------------------------------

    fun famiglia(inCorso: Boolean): Famiglia {
        val base = DatiFinti.famiglia()
        return base.copy(
            figli = base.figli.map { figlio ->
                if (figlio.id != LUCA) {
                    figlio
                } else {
                    figlio.copy(
                        dispositivi = listOf(
                            Dispositivo(TEL_LUCA, "Telefono", "telefono", versioneApp = "0.18.0", statoSilenzio = DatiFinti.silenzioInContatto()),
                            Dispositivo(PC_LUCA, "Computer di camera", "computer", versioneApp = "0.14.0", statoSilenzio = DatiFinti.silenzioSpento()),
                        ),
                        faccendeDaFare = 1,
                        faccendeDaApprovare = 2,
                        studioDaApprovare = 1,
                        bloccoRimandato = inCorso,
                    )
                }
            },
        )
    }

    fun finestraLuca(inCorso: Boolean, conRichiesta: Boolean = true): Finestra = DatiFinti.finestraLuca(faccende = faccendeLuca()).copy(
        // Lo Studio chiude le sessioni normali in corso al suo inizio: nei dati v4.0 non ce n'è.
        sessioniSvolte = DatiFinti.sessioniSvolteLuca().filter { it.fineTs != null },
        blocco = bloccoLuca(rimandato = inCorso),
        faccendeDaApprovare = 2,
        studio = StudioPatto(
            config = config(conRichiesta),
            inCorso = if (inCorso) studioInCorso() else null,
            prossimePartenze = listOf(PartenzaStudio(oggi().plusDays(1).toString(), traMinuti(24 * 60), traMinuti(25 * 60), 60)),
        ),
        studioSvolte = (if (inCorso) listOf(studioInCorso()) else listOf(studioChiusoOggi())) + svolte(),
        studioDaApprovare = if (conRichiesta) 1 else 0,
    )

    /** Lo Studio di oggi, già chiuso da Luca. */
    fun studioChiusoOggi() = studioInCorso().copy(
        minutiAttivita = 64, minutiAllaChiusura = 64, chiudibile = true, inCorso = false,
        tratti = studioInCorso().tratti.dropLast(1),
        fineTs = minutiFa(5), chiusura = "figlio", chiusaDa = ChiStudio(TEL_LUCA, "Telefono", "telefono"),
        dichiarazione = "Matematica e inglese, poi ho rifatto il letto.", dichiarazioneTs = minutiFa(5),
    )

    fun notifiche(): List<Notifica> = DatiFinti.notifiche().filter { it.tipo != "faccenda_fatta" } + listOf(
        Notifica(
            id = 210, tipo = "faccenda_fatta", messaggio = "Luca ha mandato la foto di «Rifai il letto»: aspetta la vostra approvazione",
            payload = buildJsonObject { put("faccenda_id", 107); put("titolo", "Rifai il letto") },
            tsServer = minutiFa(12), figlioId = LUCA,
        ),
        Notifica(
            id = 211, tipo = "faccenda_confermata", messaggio = "Papà ha approvato «Stendi i panni»",
            payload = buildJsonObject { put("faccenda_id", 104); put("titolo", "Stendi i panni"); put("sblocca", false) },
            tsServer = giorniFa(1, 18, 5), figlioId = LUCA,
        ),
        Notifica(
            id = 212, tipo = "studio_iniziato", messaggio = "Luca è in Studio dalle 15:00",
            payload = buildJsonObject { put("studio_id", 45); put("origine", "automatica") },
            tsServer = minutiFa(50), figlioId = LUCA,
        ),
        Notifica(
            id = 213, tipo = "studio_da_approvare", messaggio = "Luca chiede di cambiare lo Studio",
            payload = buildJsonObject { put("versione", 6); put("cambio", true) },
            tsServer = minutiFa(20), figlioId = LUCA,
        ),
        Notifica(
            id = 214, tipo = "studio_non_chiuso", messaggio = "Lo Studio di Luca non è stato chiuso: 40 min di attività",
            payload = buildJsonObject { put("studio_id", 42); put("minuti_attivita", 40) },
            tsServer = giorniFa(2, 0, 0), figlioId = LUCA,
        ),
        Notifica(
            id = 215, tipo = "manomissione", messaggio = "Il computer di Luca non risponde durante lo Studio",
            payload = buildJsonObject {
                put("evento_id", "m-9")
                putJsonObject("dettagli") { put("sotto_tipo", "computer_sparito"); put("durante", "studio") }
            },
            tsServer = minutiFa(8), figlioId = LUCA, dispositivoId = PC_LUCA,
        ),
    )

    // --- Lo scenario -----------------------------------------------------------------------

    /** Un server v4.0: Luca in Studio ([inCorso]) o con lo Studio di oggi già chiuso. */
    fun scenario(
        inCorso: Boolean = true,
        conRichiesta: Boolean = true,
        faccende: List<Faccenda> = faccendeLuca(),
        blocco: BloccoFaccende = bloccoLuca(rimandato = inCorso),
        /** Senza proposte, sessioni e dichiarazioni: in "Da decidere" solo foto e Studio. */
        soloStudio: Boolean = false,
    ): Scenario = DatiFinti.scenarioNormale().copy(
        dichiarazioni = { corpo(eu.stgm.pactum.genitore.dati.PaccoDichiarazioni.serializer(), eu.stgm.pactum.genitore.dati.PaccoDichiarazioni(if (soloStudio) emptyList() else DatiFinti.dichiarazioniLuca())) },
        famiglia = { corpo(Famiglia.serializer(), famiglia(inCorso)) },
        finestra = { id ->
            if (id == SARA) {
                corpo(Finestra.serializer(), DatiFinti.finestraSara())
            } else {
                val finestra = finestraLuca(inCorso, conRichiesta).copy(faccende = faccende, blocco = blocco)
                corpo(
                    Finestra.serializer(),
                    if (soloStudio) finestra.copy(propostePendenti = emptyList(), sessioni = emptyList(), sessioniDaApprovare = 0) else finestra,
                )
            }
        },
        faccende = { id ->
            if (id == SARA) {
                corpo(PaccoFaccende.serializer(), PaccoFaccende(blocco = BloccoFaccende(rimandato = false)))
            } else {
                corpo(PaccoFaccende.serializer(), PaccoFaccende(faccende = faccende, blocco = blocco))
            }
        },
        notifiche = { corpo(PaccoNotifiche.serializer(), PaccoNotifiche(notifiche())) },
        studio = { _ ->
            corpo(
                PaccoStudio.serializer(),
                PaccoStudio(
                    config = config(conRichiesta),
                    inCorso = if (inCorso) studioInCorso() else null,
                    recenti = (if (inCorso) listOf(studioInCorso()) else listOf(studioChiusoOggi())) + svolte().take(1),
                ),
            )
        },
        studioVersioni = { corpo(PaccoVersioniStudio.serializer(), versioni()) },
        studioSvolte = { _, primaDi ->
            val tutte = (if (inCorso) listOf(studioInCorso()) else listOf(studioChiusoOggi())) + svolte()
            corpo(
                PaccoSvolteStudio.serializer(),
                if (primaDi == null) PaccoSvolteStudio(tutte, altre = true) else PaccoSvolteStudio(emptyList(), altre = false),
            )
        },
    )
}
