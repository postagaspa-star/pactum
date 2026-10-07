package eu.stgm.pactum.figlio.fotografo

import android.os.SystemClock
import eu.stgm.pactum.figlio.faccende.Ancora
import eu.stgm.pactum.figlio.faccende.FaccendaDaFare
import eu.stgm.pactum.figlio.faccende.FaccendaLocale
import eu.stgm.pactum.figlio.faccende.MemoriaBlocco
import eu.stgm.pactum.figlio.faccende.Ordine
import eu.stgm.pactum.figlio.faccende.StatiFaccenda
import eu.stgm.pactum.figlio.fotografo.DatiFinti.MINUTO
import eu.stgm.pactum.figlio.fotografo.DatiFinti.ORA
import eu.stgm.pactum.figlio.fotografo.DatiFinti.adesso
import eu.stgm.pactum.figlio.studio.ChiusureStudio
import eu.stgm.pactum.figlio.studio.ConfigStudio
import eu.stgm.pactum.figlio.studio.ContenutoStudio
import eu.stgm.pactum.figlio.studio.EsitiStudio
import eu.stgm.pactum.figlio.studio.EsitiTratto
import eu.stgm.pactum.figlio.studio.MemoriaStudio
import eu.stgm.pactum.figlio.studio.OriginiStudio
import eu.stgm.pactum.figlio.studio.PartenzaStudio
import eu.stgm.pactum.figlio.studio.RifiutoChiusura
import eu.stgm.pactum.figlio.studio.StatiConfigStudio
import eu.stgm.pactum.figlio.studio.StudioSvolto
import eu.stgm.pactum.figlio.studio.TipiTratto
import eu.stgm.pactum.figlio.studio.TrattoDelServer
import eu.stgm.pactum.figlio.studio.TrattoLocale
import eu.stgm.pactum.figlio.ui.StudioViewModel
import java.time.LocalDate
import java.time.ZoneId

/**
 * (0.18) I dati finti della Sessione Studio di Luca: la configurazione
 * approvata da Mamma (ClasseViva, Drive, Calcolatrice), lo Studio di oggi in
 * corso col timer, una proposta di cambio, lo storico.
 */
object DatiStudio {

    private val zona: String get() = ZoneId.systemDefault().id

    val approvata = ContenutoStudio(
        app = listOf("com.spaggiari.classevivastudenti", "com.google.android.apps.docs", "com.google.android.calculator"),
        nomi = mapOf("com.spaggiari.classevivastudenti" to "ClasseViva Studenti"),
        orariDal = LocalDate.now().minusDays(3).toString(),
        decisaDa = "Mamma",
    )

    private fun config(inAttesa: ContenutoStudio? = null, motivazione: String? = null) =
        ConfigStudio(StatiConfigStudio.APPROVATA, 4, approvata, inAttesa, motivazione)

    private fun domani(): PartenzaStudio {
        val d = LocalDate.now().plusDays(1)
        val inizio = d.atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return PartenzaStudio(d.toString(), inizio, inizio + ORA, 60)
    }

    /** Lo Studio di oggi, iniziato [daMinuti] fa, col timer fermo o in corso. */
    fun inCorso(daMinuti: Int = 50, minutiServer: Int = 30, chiudibileDalTra: Long = 10 * MINUTO, timer: Boolean = true): MemoriaStudio {
        val adesso = adesso()
        val inizio = adesso - daMinuti * MINUTO
        val chiudibile = adesso + chiudibileDalTra
        val studio = StudioSvolto(
            id = 41, origine = OriginiStudio.AUTOMATICA, giorno = LocalDate.now().toString(), inizio = inizio,
            partenze = listOf(PartenzaStudio(LocalDate.now().toString(), inizio, chiudibile, 60)),
            contaDal = inizio, chiudibileDal = chiudibile, minutiMinimi = 60, minutiAttivita = minutiServer,
            chiudibile = false, inCorso = true,
            tratti = listOf(
                TrattoDelServer("s1", 11, TipiTratto.LAVORI_DI_CASA, null, 61, null, inizio + 2 * MINUTO, true, 600, 600, 10, EsitiTratto.FINITO, true),
                TrattoDelServer("s2", 11, TipiTratto.COMPITI, "matematica", null, null, inizio + 25 * MINUTO, true, 1200, 1200, 20, EsitiTratto.FINITO, true),
            ),
        )
        val mono = SystemClock.elapsedRealtime()
        val tratti = if (timer) {
            listOf(
                TrattoLocale(
                    id = "t-in-corso", tipo = TipiTratto.ALTRO, parola = "allenamento", avvio = null,
                    monoInizio = mono - 12 * MINUTO - 34_000, monoSalvato = mono, inizio = adesso - 12 * MINUTO - 34_000,
                    oraAgganciata = true, inCorsoMandato = true, studio = inizio,
                ),
            )
        } else {
            emptyList()
        }
        return MemoriaStudio(
            conosciuto = true, config = config(), inCorsoServer = studio, prossime = listOf(domani()),
            fuso = zona, ordine = Ordine(null, 0), sentito = adesso - MINUTO, tratti = tratti,
            periodi = emptyList(), partitoIl = inizio,
        )
    }

    /** Lo Studio con le condizioni fatte: dopo le 16 e oltre i 60 minuti. */
    fun chiudibile(): MemoriaStudio = inCorso(daMinuti = 75, minutiServer = 58, chiudibileDalTra = -15 * MINUTO)

    /** Lo Studio torna: il server ha rifiutato la chiusura (troppo presto). */
    fun rifiutato(): MemoriaStudio {
        val m = inCorso()
        val chiudibileDal = m.inCorsoServer!!.chiudibileDal
        return m.copy(rifiuto = RifiutoChiusura(EsitiStudio.TROPPO_PRESTO, "Ho fatto gli esercizi di matematica", adesso() - 2 * MINUTO, chiudibileDal))
    }

    /** Lo Studio parte fra 20 minuti. */
    fun inPartenza(): MemoriaStudio {
        val adesso = adesso()
        val inizio = adesso + 20 * MINUTO
        return MemoriaStudio(
            conosciuto = true, config = config(), fuso = zona, ordine = Ordine(null, 0), sentito = adesso - MINUTO,
            prossime = listOf(PartenzaStudio(LocalDate.now().toString(), inizio, inizio + ORA, 60), domani()),
        )
    }

    /** Fuori dallo Studio, con una proposta di cambio che aspetta un genitore. */
    fun conProposta(): MemoriaStudio = inPartenza().copy(
        prossime = listOf(domani()),
        config = config(
            inAttesa = approvata.copy(
                inizio = "15:30", chiusuraMinima = "16:30", minutiMinimi = 45,
                app = approvata.app + "com.duolingo", orariDal = null, decisaDa = null, da = "Telefono di Luca",
            ),
        ),
    )

    /** Mai proposta. */
    fun nessuna(): MemoriaStudio = MemoriaStudio(
        conosciuto = true, config = ConfigStudio(StatiConfigStudio.NESSUNA, 0, null, null, null), fuso = zona,
        ordine = Ordine(null, 0), sentito = adesso() - MINUTO,
    )

    /** Lo storico aperto: ieri chiuso da Luca, l'altro ieri chiuso da Mamma, tre giorni fa non chiuso. */
    fun storicoAperto(): StudioViewModel.StatoStudioUi {
        val oggi = LocalDate.now()
        fun alle(giorni: Long, ore: Int, minuti: Int = 0) = oggi.minusDays(giorni).atTime(ore, minuti).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val svolte = listOf(
            StudioSvolto(
                id = 40, giorno = oggi.minusDays(1).toString(), inizio = alle(1, 15), fine = alle(1, 16, 40), chiusura = ChiusureStudio.FIGLIO,
                dichiarazione = "Esercizi di matematica a pagina 120 e ripasso di storia", minutiAttivita = 65, minutiAllaChiusura = 65,
                tratti = listOf(
                    TrattoDelServer("a", 11, TipiTratto.COMPITI, "matematica", null, null, null, true, 2400, 2400, 40, EsitiTratto.FINITO, true),
                    TrattoDelServer("b", 11, TipiTratto.ALTRO, "allenamento", null, null, null, true, 1500, 1500, 25, EsitiTratto.FINITO, true),
                ),
            ),
            StudioSvolto(
                id = 39, origine = OriginiStudio.MANUALE, giorno = oggi.minusDays(2).toString(), inizio = alle(2, 14, 30), fine = alle(2, 15, 20),
                chiusura = ChiusureStudio.GENITORE, chiusaDa = "Mamma", motivo = "Visita dal dentista", minutiAttivita = 20, minutiAllaChiusura = 20,
            ),
            StudioSvolto(
                id = 38, giorno = oggi.minusDays(3).toString(), inizio = alle(3, 15), fine = alle(4, 0).let { oggi.minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
                chiusura = ChiusureStudio.NON_CHIUSO, minutiAttivita = 40, minutiAllaChiusura = 40,
            ),
        )
        return StudioViewModel.StatoStudioUi(storico = StudioViewModel.Storico(aperto = true, svolte = svolte, altre = true))
    }

    /** Le versioni approvate aperte: la 4 di Mamma (con la lista), la 1 decisa dalla famiglia all'aggiornamento. */
    fun versioniAperte(): StudioViewModel.StatoStudioUi {
        val oggi = LocalDate.now()
        val versioni = listOf(
            approvata.copy(versione = 4, orariDal = oggi.minusDays(3).toString()),
            ContenutoStudio(versione = 1, orariDal = oggi.minusDays(10).toString(), decisaDa = null),
        )
        return StudioViewModel.StatoStudioUi(versioni = StudioViewModel.Versioni(aperte = true, versioni = versioni))
    }

    // --- Parte A: i lavori che aspettano l'approvazione -------------------------------------------

    /** Bloccato: un lavoro da fare e due con la foto che aspetta l'approvazione. */
    fun bloccoConApprovazione(): MemoriaBlocco {
        val adesso = adesso()
        val dal = adesso - 40 * MINUTO
        val daFare = listOf(
            DatiFinti.camera(dal),
            DatiFinti.lavastoviglie(dal).copy(stato = StatiFaccenda.FATTA, fotoIl = adesso - 25 * MINUTO),
            FaccendaDaFare(64, "Stendere il bucato", bloccoDa = dal, genitore = "Papà", stato = StatiFaccenda.FATTA, fotoIl = adesso - 10 * MINUTO),
        )
        return MemoriaBlocco(
            attivo = true, dal = dal, daFare = daFare, ordine = Ordine(null, 0),
            ancora = Ancora(null, 0, adesso - MINUTO), scarto = 0, sentitoIl = adesso - MINUTO,
            episodio = dal, annunciato = dal, conosciuto = true, approvazione = true,
            elenco = daFare.map {
                FaccendaLocale(
                    id = it.id, titolo = it.titolo, nota = it.nota, stato = it.stato, bloccoDa = it.bloccoDa,
                    creataIl = adesso - 2 * ORA, genitore = it.genitore, fotoIl = it.fotoIl, foto = it.fotoIl != null,
                    bocciature = it.bocciature, ultimaBocciatura = it.ultimaBocciatura, daApprovare = it.aspettaApprovazione,
                )
            },
            ordineElenco = Ordine(null, 0), elencoIl = adesso - MINUTO,
        )
    }

    /** Il blocco è dovuto, ma c'è lo Studio: aspetta. */
    fun bloccoRimandato(): MemoriaBlocco = bloccoConApprovazione().copy(rimandato = true)
}
