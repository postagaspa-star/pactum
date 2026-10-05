package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.bonus.BonusInSospeso
import eu.stgm.pactum.figlio.dati.ContatoreBonus
import eu.stgm.pactum.figlio.dati.ContestoDispositivi
import eu.stgm.pactum.figlio.dati.Dichiarazione
import eu.stgm.pactum.figlio.dati.DirezioniProposta
import eu.stgm.pactum.figlio.dati.Dispositivo
import eu.stgm.pactum.figlio.dati.DominioVisite
import eu.stgm.pactum.figlio.dati.EsitiDichiarazione
import eu.stgm.pactum.figlio.dati.EsitiRisposta
import eu.stgm.pactum.figlio.dati.GiornoStriscia
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.Riepilogo
import eu.stgm.pactum.figlio.dati.RispostaProposta
import eu.stgm.pactum.figlio.dati.SitiGiorno
import eu.stgm.pactum.figlio.dati.StatiDichiarazione
import eu.stgm.pactum.figlio.dati.StatiProposta
import eu.stgm.pactum.figlio.dati.StatoBonus
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.VerdettoDichiarazione
import eu.stgm.pactum.figlio.dati.inGiorniPatto
import eu.stgm.pactum.figlio.faccende.Ancora
import eu.stgm.pactum.figlio.faccende.Bocciatura
import eu.stgm.pactum.figlio.faccende.ChiaveCollegamento
import eu.stgm.pactum.figlio.faccende.FaccendaDaFare
import eu.stgm.pactum.figlio.faccende.FaccendaLocale
import eu.stgm.pactum.figlio.faccende.FotoInCoda
import eu.stgm.pactum.figlio.faccende.MemoriaBlocco
import eu.stgm.pactum.figlio.faccende.MemoriaCodaFoto
import eu.stgm.pactum.figlio.faccende.Ordine
import eu.stgm.pactum.figlio.faccende.RicercaFaccende
import eu.stgm.pactum.figlio.faccende.StatiFaccenda
import eu.stgm.pactum.figlio.faccende.StatiFoto
import eu.stgm.pactum.figlio.sessione.AvvioIncerto
import eu.stgm.pactum.figlio.sessione.ModificaSessione
import eu.stgm.pactum.figlio.sessione.SessioneDefinita
import eu.stgm.pactum.figlio.sessione.StatiSessione
import eu.stgm.pactum.figlio.sessione.SvoltaLocale
import eu.stgm.pactum.figlio.ui.AppDelGiorno
import eu.stgm.pactum.figlio.ui.CategoriaDelGiorno
import eu.stgm.pactum.figlio.ui.DichiarazioniViewModel
import eu.stgm.pactum.figlio.ui.GiornoTempo
import eu.stgm.pactum.figlio.ui.MedieTempo
import eu.stgm.pactum.figlio.ui.PeriodoTempo
import eu.stgm.pactum.figlio.ui.TempiDispositivo
import eu.stgm.pactum.figlio.ui.FaccendeViewModel
import eu.stgm.pactum.figlio.ui.OggiViewModel
import eu.stgm.pactum.figlio.ui.OggiViewModel.RigaRegola
import eu.stgm.pactum.figlio.ui.OggiViewModel.RigaUso
import eu.stgm.pactum.figlio.ui.ProposteViewModel
import eu.stgm.pactum.figlio.ui.RegoleViewModel
import eu.stgm.pactum.figlio.ui.RigaDispositivo
import eu.stgm.pactum.figlio.ui.SessioniViewModel
import eu.stgm.pactum.figlio.ui.SitiViewModel
import eu.stgm.pactum.figlio.valutatore.MomentoFascia
import eu.stgm.pactum.figlio.valutatore.StatoFascia
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * I dati finti ma realistici di Luca: le sue regole, le proposte con Mamma e
 * Papà, le sessioni, i lavori di casa, il diario, i siti. Tutto è contato da
 * "adesso", così le frasi ("fino alle 18:40", "oggi", "ieri") tornano vere.
 */
object DatiFinti {

    const val QUESTO_TELEFONO = 11L
    const val COMPUTER = 12L
    const val MINUTO = 60_000L
    const val ORA = 60 * MINUTO
    const val GIORNO = 24 * ORA

    fun adesso(): Long = System.currentTimeMillis()

    fun oggi(): LocalDate = LocalDate.now()

    /** Oggi alle [ore]:[minuti], in millisecondi. */
    fun oggiAlle(ore: Int, minuti: Int = 0): Long =
        oggi().atTime(LocalTime.of(ore, minuti)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms - ms % 1000).toString()

    /** Gli 8 giorni della striscia, dal più vecchio a oggi: "v" verde, "r" rosso, "g" grigio. */
    fun striscia(stati: String): List<GiornoStriscia> {
        val oggi = oggi()
        return stati.mapIndexed { i, c ->
            GiornoStriscia(
                data = oggi.minusDays((stati.length - 1 - i).toLong()).toString(),
                stato = when (c) {
                    'v' -> "verde"
                    'r' -> "rosso"
                    else -> "grigio"
                },
            )
        }
    }

    // --- Dispositivi --------------------------------------------------------------

    val telefono = Dispositivo(id = QUESTO_TELEFONO, nome = "Telefono di Luca", tipo = "telefono")
    val computer = Dispositivo(id = COMPUTER, nome = "PC di Luca", tipo = "computer")
    val contesto = ContestoDispositivi(questo = QUESTO_TELEFONO, dispositivi = listOf(telefono, computer))

    // --- Regole -------------------------------------------------------------------

    fun limite(id: Long, chiave: String, minuti: Int, semaforo: String = "vvvvrvvg", allentabileTra: Long? = null) = Regola(
        id = id,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = parametriLimite(chiave, minuti),
        creataTs = iso(adesso() - 20 * GIORNO),
        ultimaModificaTs = iso(adesso() - 3 * GIORNO),
        allentabileDal = allentabileTra?.let { iso(adesso() + it) },
        semaforo = striscia(semaforo),
        dispositivoId = QUESTO_TELEFONO,
    )

    fun parametriLimite(chiave: String, minuti: Int): JsonObject = buildJsonObject {
        put("app_o_categoria", chiave)
        put("minuti_al_giorno", minuti)
    }

    fun parametriFascia(dalle: String, alle: String, giorni: List<String>): JsonObject = buildJsonObject {
        put("dalle", dalle)
        put("alle", alle)
        putJsonArray("giorni") { giorni.forEach { add(it) } }
    }

    fun fascia(id: Long, dalle: String, alle: String, giorni: List<String>, semaforo: String = "vvvvvvvg") = Regola(
        id = id,
        tipo = TipiRegola.FASCIA_ORARIA,
        parametri = parametriFascia(dalle, alle, giorni),
        creataTs = iso(adesso() - 30 * GIORNO),
        semaforo = striscia(semaforo),
        dispositivoId = QUESTO_TELEFONO,
    )

    fun vitaReale(id: Long, descrizione: String, arbitro: String, frequenza: String) = Regola(
        id = id,
        tipo = TipiRegola.VITA_REALE,
        parametri = buildJsonObject {
            put("descrizione", descrizione)
            put("arbitro_nome", arbitro)
            put("frequenza", frequenza)
        },
        creataTs = iso(adesso() - 12 * GIORNO),
        semaforo = striscia("vgvvgvvg"),
    )

    private val TUTTI_I_GIORNI = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")
    private val SCUOLA = listOf("lun", "mar", "mer", "gio", "ven")

    val instagram = limite(1, "com.instagram.android", 60)
    val tiktok = limite(2, "com.zhiliaoapp.musically", 45, semaforo = "vrvvvrvv", allentabileTra = 2 * GIORNO + 5 * ORA)
    val social = limite(3, "categoria:social", 120, semaforo = "vvvvvvvv")
    val notte = fascia(4, "22:30", "07:00", TUTTI_I_GIORNI)
    val compiti = fascia(5, "15:00", "17:00", SCUOLA, semaforo = "vvvrvvvg")
    val calcio = vitaReale(6, "Allenamento di calcio", "Papà", "3 volte a settimana")
    val lettura = vitaReale(7, "Leggere 20 pagine del libro", "Mamma", "ogni giorno")

    /** Due regole sul computer: qui non si vedono, si contano. */
    val minecraft = Regola(
        id = 8,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = parametriLimite("exe:minecraft.exe", 90),
        dispositivoId = COMPUTER,
        dispositivo = computer,
        nome = "Minecraft",
    )

    val regoleTelefono = listOf(instagram, tiktok, social, notte, compiti, calcio, lettura)
    val regoleTutte = regoleTelefono + minecraft

    // --- Oggi ---------------------------------------------------------------------

    fun oggiNormale() = OggiViewModel.StatoOggi(
        caricamento = false,
        striscia = striscia("vvvrvvvg").inGiorniPatto(),
        riepilogo = Riepilogo(giorniFuoriRegola = 1, interruzioni = 0),
        righeDispositivi = listOf(
            RigaDispositivo(QUESTO_TELEFONO, "Telefono di Luca", questo = true, mantenuti = 6, conDati = 7),
            RigaDispositivo(COMPUTER, "PC di Luca", questo = false, mantenuti = 5, conDati = 6),
        ),
        serie = 3,
        record = 9,
        regole = listOf(
            RigaRegola.Tempo(instagram, "Instagram", minuti = 48, limiteEfficace = 60, bonusOggi = 0),
            RigaRegola.Tempo(tiktok, "TikTok", minuti = 52, limiteEfficace = 45, bonusOggi = 0),
            RigaRegola.Tempo(social, "Social", minuti = 131, limiteEfficace = 135, bonusOggi = 15),
            RigaRegola.Fascia(notte, MomentoFascia.Prima(minuti = 205, inizio = LocalTime.of(22, 30)), StatoFascia.Inizia(LocalTime.of(22, 30))),
            // (0.16) Una fascia finita con 12 minuti di telefono dentro.
            RigaRegola.Fascia(compiti, MomentoFascia.Finita, StatoFascia.Fuori(12)),
            RigaRegola.VitaReale(calcio),
            RigaRegola.VitaReale(lettura),
        ),
        bonus = StatoBonus(
            giorno = ContatoreBonus(usati = 15, tetto = 30, residui = 15),
            settimana = ContatoreBonus(usati = 45, tetto = 120, residui = 75),
        ),
        fuso = "Europe/Rome",
        righe = listOf(
            RigaUso("TikTok", "com.zhiliaoapp.musically", 52),
            RigaUso("Instagram", "com.instagram.android", 48),
            RigaUso("YouTube", "com.google.android.youtube", 31),
            RigaUso("WhatsApp", "com.whatsapp", 24),
            RigaUso("ClasseViva Studenti", "com.spaggiari.classevivastudenti", 12),
            RigaUso("Spotify", "com.spotify.music", 9),
            RigaUso("Chrome", "com.android.chrome", 6),
        ),
        minutiTotali = 182,
        tempi = tempiNormali(),
    )

    // --- (0.16) Il tempo: 8 giorni e i totali, telefono + computer ---------------

    private fun giornoTempo(
        indietro: Long,
        minuti: Int?,
        app: List<AppDelGiorno> = emptyList(),
        categorie: List<CategoriaDelGiorno> = emptyList(),
        sessioni: Int? = null,
    ) = GiornoTempo(oggi().minusDays(indietro).toString(), minuti, app, categorie, sessioni)

    /** Oggi letto sul telefono: le stesse app della lista di Oggi (182 min). */
    private fun oggiTelefono() = giornoTempo(
        0,
        182,
        app = listOf(
            AppDelGiorno("com.zhiliaoapp.musically", "TikTok", 52),
            AppDelGiorno("com.instagram.android", "Instagram", 48),
            AppDelGiorno("com.google.android.youtube", "YouTube", 31),
            AppDelGiorno("com.whatsapp", "WhatsApp", 24),
            AppDelGiorno("com.spaggiari.classevivastudenti", "ClasseViva Studenti", 12),
            AppDelGiorno("com.spotify.music", "Spotify", 9),
            AppDelGiorno("com.android.chrome", "Chrome", 6),
        ),
        categorie = listOf(
            CategoriaDelGiorno("categoria:social", 124, 120),
            CategoriaDelGiorno("categoria:video", 31),
            CategoriaDelGiorno("categoria:altro", 18),
            CategoriaDelGiorno("categoria:musica", 9),
        ),
        sessioni = 25,
    )

    fun telefonoTempo() = TempiDispositivo(
        id = QUESTO_TELEFONO,
        nome = "Telefono di Luca",
        tipo = "telefono",
        questo = true,
        giorni = listOf(
            giornoTempo(7, 135),
            giornoTempo(6, 210),
            giornoTempo(5, 95),
            giornoTempo(4, null),
            giornoTempo(3, 160),
            giornoTempo(2, 188),
            giornoTempo(
                1,
                142,
                app = listOf(
                    AppDelGiorno("com.zhiliaoapp.musically", "TikTok", 50),
                    AppDelGiorno("com.instagram.android", "Instagram", 41),
                    AppDelGiorno("com.google.android.youtube", "YouTube", 25),
                    AppDelGiorno("com.whatsapp", "WhatsApp", 14),
                    AppDelGiorno("com.spotify.music", "Spotify", 12),
                ),
                categorie = listOf(
                    CategoriaDelGiorno("categoria:social", 105, 120),
                    CategoriaDelGiorno("categoria:video", 25),
                    CategoriaDelGiorno("categoria:musica", 12),
                ),
            ),
            oggiTelefono(),
        ),
        // Ultimi 7 giorni: 6 con dati (uno senza), 977 min; il mese 27 giorni su 30.
        medie = MedieTempo(
            settimana = PeriodoTempo(minuti = 163, giorni = 6, totale = 977),
            mese = PeriodoTempo(minuti = 158, giorni = 27, totale = 4271),
        ),
        storico = true,
    )

    fun computerTempo() = TempiDispositivo(
        id = COMPUTER,
        nome = "PC di Luca",
        tipo = "computer",
        questo = false,
        giorni = listOf(
            giornoTempo(7, 60),
            giornoTempo(6, null),
            giornoTempo(5, 95),
            giornoTempo(4, 120),
            giornoTempo(3, null),
            giornoTempo(2, 45),
            giornoTempo(1, 80),
            giornoTempo(
                0,
                35,
                app = listOf(AppDelGiorno("exe:minecraft.exe", "Minecraft", 25), AppDelGiorno("exe:chrome.exe", "Chrome", 10)),
            ),
        ),
        medie = MedieTempo(
            settimana = PeriodoTempo(minuti = 75, giorni = 5, totale = 375),
            mese = PeriodoTempo(minuti = 71, giorni = 21, totale = 1490),
        ),
        storico = true,
    )

    fun tempiNormali() = listOf(telefonoTempo(), computerTempo())

    /** Un server di prima della v3.8: solo oggi, letto sul telefono, senza totali. */
    fun tempiServerVecchio() = listOf(
        TempiDispositivo(id = QUESTO_TELEFONO, nome = "Telefono di Luca", tipo = "telefono", questo = true, giorni = listOf(oggiTelefono())),
    )

    /** Il primo giorno: niente serie, niente dati, una regola sola, nessun uso. */
    fun oggiInizio() = OggiViewModel.StatoOggi(
        caricamento = false,
        striscia = striscia("gggggggg").inGiorniPatto(),
        riepilogo = Riepilogo(0, 0),
        serie = 0,
        record = 0,
        regole = listOf(RigaRegola.Tempo(instagram, "Instagram", minuti = 0, limiteEfficace = 60, bonusOggi = 0)),
        bonus = StatoBonus(ContatoreBonus(0, 30, 30), ContatoreBonus(0, 120, 120)),
        righe = emptyList(),
        minutiTotali = 0,
    )

    fun bonusInSospeso(inScrittura: Boolean) = BonusInSospeso(
        id = "bonus-finto-1",
        regolaId = instagram.id,
        minuti = 15,
        giorno = oggi().toString(),
        creatoIl = adesso(),
        inScrittura = inScrittura,
    )

    // --- Regole (scheda) --------------------------------------------------------

    fun regoleNormali() = RegoleViewModel.StatoRegole(
        caricamento = false,
        letto = true,
        regole = regoleTelefono,
        regoleAltrove = 2,
        concordate = setOf(social.id),
        proposteInAttesa = mapOf(tiktok.id to propostaMammaTiktok, instagram.id to propostaTuaInstagram),
    )

    // --- Proposte -----------------------------------------------------------------

    /** Chi ha fatto cosa (contratto v3.6): { "id", "nome" }. */
    private fun genitore(id: Long, nome: String) = buildJsonObject {
        put("id", id)
        put("nome", nome)
    }

    val propostaMammaTiktok = Proposta(
        id = 101,
        regolaId = tiktok.id,
        parametriProposti = parametriLimite("com.zhiliaoapp.musically", 30),
        motivazione = "Questa settimana hai la verifica di storia",
        confronto = "−15 min al giorno rispetto ad ora",
        direzione = DirezioniProposta.STRINGE,
        stato = StatiProposta.PENDENTE,
        tsServer = iso(adesso() - 2 * ORA),
        autore = "genitore",
        genitore = genitore(2, "Mamma"),
    )

    val propostaPapaNotte = Proposta(
        id = 102,
        regolaId = notte.id,
        parametriProposti = parametriFascia("22:00", "07:00", TUTTI_I_GIORNI),
        motivazione = null,
        confronto = "la fascia comincia 30 minuti prima: dalle 22:00",
        direzione = DirezioniProposta.STRINGE,
        stato = StatiProposta.PENDENTE,
        tsServer = iso(adesso() - 26 * ORA),
        autore = "genitore",
        genitore = genitore(3, "Papà"),
    )

    val propostaTuaInstagram = Proposta(
        id = 103,
        regolaId = instagram.id,
        parametriProposti = parametriLimite("com.instagram.android", 90),
        motivazione = "Nel weekend esco con Marco e Giulia, vorrei qualche minuto in più",
        confronto = "+30 min al giorno rispetto ad ora",
        direzione = DirezioniProposta.ALLENTA,
        stato = StatiProposta.PENDENTE,
        tsServer = iso(adesso() - 5 * ORA),
        autore = "figlio",
    )

    val storiaProposte = listOf(
        Proposta(
            id = 90,
            regolaId = social.id,
            parametriProposti = parametriLimite("categoria:social", 120),
            confronto = "−30 min al giorno rispetto ad ora",
            direzione = DirezioniProposta.STRINGE,
            stato = StatiProposta.ACCETTATA,
            usata = true,
            tsServer = iso(adesso() - 3 * GIORNO),
            risposta = RispostaProposta(EsitiRisposta.ACCETTA, "Va bene, è giusto", iso(adesso() - 3 * GIORNO + ORA)),
            autore = "genitore",
            genitore = genitore(2, "Mamma"),
        ),
        Proposta(
            id = 88,
            regolaId = tiktok.id,
            parametriProposti = parametriLimite("com.zhiliaoapp.musically", 75),
            motivazione = "Mi serve per i video del progetto di inglese",
            confronto = "+30 min al giorno rispetto ad ora",
            direzione = DirezioniProposta.ALLENTA,
            stato = StatiProposta.RIFIUTATA,
            tsServer = iso(adesso() - 5 * GIORNO),
            risposta = RispostaProposta(EsitiRisposta.RIFIUTA, "Ne riparliamo dopo la pagella", iso(adesso() - 5 * GIORNO + 3 * ORA)),
            autore = "figlio",
            rispostaDi = genitore(3, "Papà"),
        ),
        Proposta(
            id = 85,
            regolaId = compiti.id,
            parametriProposti = buildJsonObject { put("azione", "elimina") },
            confronto = "propone di eliminare la regola",
            direzione = DirezioniProposta.ELIMINA,
            stato = StatiProposta.RITIRATA,
            tsServer = iso(adesso() - 6 * GIORNO),
            autore = "figlio",
        ),
        Proposta(
            id = 80,
            regolaId = 99,
            parametriProposti = parametriLimite("com.snapchat.android", 20),
            confronto = "−10 min al giorno rispetto ad ora",
            direzione = DirezioniProposta.STRINGE,
            stato = StatiProposta.ANNULLATA,
            tsServer = iso(adesso() - 7 * GIORNO),
            autore = "genitore",
        ),
    )

    fun proposteNormali(): ProposteViewModel.StatoProposte {
        val tutte = listOf(propostaMammaTiktok, propostaPapaNotte, propostaTuaInstagram) + storiaProposte
        return ProposteViewModel.StatoProposte(
            caricamento = false,
            proposte = tutte,
            daDecidere = listOf(propostaMammaTiktok, propostaPapaNotte),
            inviate = listOf(propostaTuaInstagram),
            regole = regoleTutte,
            regoleDiQui = regoleTelefono,
            totaleFiglio = regoleTelefono.size + 1,
            contesto = contesto,
            aggiornateIl = adesso() - 3 * MINUTO,
        )
    }

    fun proposteVuote() = ProposteViewModel.StatoProposte(
        caricamento = false,
        proposte = emptyList(),
        regole = regoleTutte,
        regoleDiQui = regoleTelefono,
        totaleFiglio = regoleTelefono.size + 1,
        contesto = contesto,
        aggiornateIl = adesso(),
    )

    // --- Sessioni -----------------------------------------------------------------

    private fun nomi(vararg pacchetti: String) = pacchetti.associateWith { Mondo.nome(it) }

    val studio = SessioneDefinita(
        id = 41,
        nome = "Studio",
        app = listOf(
            "com.spaggiari.classevivastudenti",
            "com.google.android.apps.classroom",
            "com.google.android.apps.docs",
            "com.google.android.calculator",
            "com.duolingo",
            "gruppo:apk",
        ),
        nomi = nomi(
            "com.spaggiari.classevivastudenti",
            "com.google.android.apps.classroom",
            "com.google.android.apps.docs",
            "com.google.android.calculator",
            "com.duolingo",
        ),
        stato = StatiSessione.APPROVATA,
        modificaInAttesa = null,
        motivazione = null,
    )

    val musica = SessioneDefinita(
        id = 42,
        nome = "Musica",
        app = listOf("com.spotify.music", "com.google.android.youtube"),
        nomi = nomi("com.spotify.music", "com.google.android.youtube"),
        stato = StatiSessione.APPROVATA,
        modificaInAttesa = ModificaSessione(
            nome = "Musica e chitarra",
            app = listOf("com.spotify.music", "com.google.android.youtube", "com.instagram.android"),
            nomi = nomi("com.instagram.android"),
            richiestaTs = adesso() - 3 * ORA,
        ),
        motivazione = null,
    )

    val allenamento = SessioneDefinita(
        id = 43,
        nome = "Allenamento",
        app = listOf("com.spotify.music", "com.whatsapp"),
        nomi = nomi("com.spotify.music", "com.whatsapp"),
        stato = StatiSessione.IN_ATTESA,
        modificaInAttesa = null,
        motivazione = null,
    )

    val videogiochi = SessioneDefinita(
        id = 44,
        nome = "Videogiochi con Marco",
        app = listOf("com.supercell.brawlstars", "com.whatsapp"),
        nomi = nomi("com.supercell.brawlstars", "com.whatsapp"),
        stato = StatiSessione.RIFIUTATA,
        modificaInAttesa = null,
        motivazione = "Durante la settimana no, ne parliamo per il sabato",
        decisaDa = "Papà",
    )

    val lettura2 = SessioneDefinita(
        id = 45,
        nome = "Lettura",
        app = listOf("com.google.android.apps.docs"),
        nomi = nomi("com.google.android.apps.docs"),
        stato = StatiSessione.APPROVATA,
        modificaInAttesa = null,
        motivazione = "Chrome no: per leggere basta Drive",
        decisaDa = "Mamma",
    )

    val sessioni = listOf(studio, musica, allenamento, videogiochi, lettura2)

    fun sessioniNormali() = SessioniViewModel.StatoSessioni(caricamento = false, letto = true, sessioni = sessioni)

    /** Studio in corso da 25 minuti, ne mancano 65. */
    fun svoltaStudio(): SvoltaLocale {
        val adesso = adesso()
        return SvoltaLocale(
            id = 501,
            sessioneId = studio.id,
            nome = studio.nome,
            app = studio.app,
            nomi = studio.nomi,
            inizio = adesso - 25 * MINUTO,
            finePrevista = adesso + 65 * MINUTO,
            annunciata = true,
            ancorataQui = true,
        )
    }

    fun avvioIncerto() = AvvioIncerto(sessioneId = musica.id, nome = "Musica", durataMinuti = 60, richiestoIl = adesso() - 2 * MINUTO)

    // --- Lavori di casa -----------------------------------------------------------

    fun lavastoviglie(bloccoDa: Long) = FaccendaDaFare(
        id = 61,
        titolo = "Svuotare la lavastoviglie",
        nota = "Anche le posate, per favore",
        bloccoDa = bloccoDa,
        genitore = "Mamma",
    )

    fun camera(bloccoDa: Long) = FaccendaDaFare(
        id = 62,
        titolo = "Riordinare la camera",
        bloccoDa = bloccoDa,
        genitore = "Papà",
        bocciature = 2,
        ultimaBocciatura = Bocciatura(ts = adesso() - 20 * MINUTO, nota = "Il letto non è fatto e i vestiti sono sulla sedia", da = "Papà"),
    )

    fun spazzatura(bloccoDa: Long) = FaccendaDaFare(
        id = 63,
        titolo = "Portare fuori la spazzatura (carta e plastica)",
        bloccoDa = bloccoDa,
        genitore = "Mamma",
    )

    private fun locale(d: FaccendaDaFare, creata: Long) = FaccendaLocale(
        id = d.id,
        titolo = d.titolo,
        nota = d.nota,
        stato = StatiFaccenda.DA_FARE,
        bloccoDa = d.bloccoDa,
        creataIl = creata,
        genitore = d.genitore,
        bocciature = d.bocciature,
        ultimaBocciatura = d.ultimaBocciatura,
    )

    private fun chiuse(): List<FaccendaLocale> {
        val adesso = adesso()
        return listOf(
            FaccendaLocale(
                id = 55,
                titolo = "Stendere il bucato",
                stato = StatiFaccenda.FATTA,
                creataIl = adesso - GIORNO - 4 * ORA,
                genitore = "Mamma",
                fotoIl = adesso - GIORNO,
                foto = true,
                chiusaIl = adesso - GIORNO + 10 * MINUTO,
                // (0.17, contratto v3.9) Confermato dalla mamma: "svolto".
                confermataIl = adesso - GIORNO + 2 * ORA,
                confermataDa = "Mamma",
            ),
            FaccendaLocale(
                id = 56,
                titolo = "Dare da mangiare al gatto",
                stato = StatiFaccenda.FATTA,
                creataIl = adesso - 9 * GIORNO,
                genitore = "Papà",
                fotoIl = adesso - 9 * GIORNO + 2 * ORA,
                foto = false,
                chiusaIl = adesso - 9 * GIORNO + 3 * ORA,
            ),
            FaccendaLocale(
                id = 57,
                titolo = "Lavare la macchina",
                stato = StatiFaccenda.ANNULLATA,
                creataIl = adesso - 4 * GIORNO,
                genitore = "Papà",
                chiusaIl = adesso - 3 * GIORNO,
                annullataDa = "Papà",
            ),
        )
    }

    /** Il telefono è bloccato da mezz'ora: tre lavori da fare (uno bocciato due volte), tre chiusi. */
    fun bloccoAttivo(): MemoriaBlocco {
        val adesso = adesso()
        val dal = adesso - 30 * MINUTO
        val daFare = listOf(lavastoviglie(dal), camera(dal), spazzatura(oggiAlle(23, 0).coerceAtLeast(adesso + ORA)))
        return MemoriaBlocco(
            attivo = true,
            dal = dal,
            daFare = daFare,
            ordine = Ordine(null, 0),
            ancora = Ancora(null, 0, adesso - MINUTO),
            scarto = 0,
            sentitoIl = adesso - MINUTO,
            episodio = dal,
            annunciato = dal,
            conosciuto = true,
            elenco = daFare.mapIndexed { i, d -> locale(d, adesso - (3 - i) * ORA) } + chiuse(),
            ordineElenco = Ordine(null, 0),
            elencoIl = adesso - MINUTO,
        )
    }

    /** Non ancora bloccato: si blocca fra un'ora e mezza, se i lavori non sono fatti. */
    fun bloccoProgrammato(): MemoriaBlocco {
        val adesso = adesso()
        val prossimo = adesso + 90 * MINUTO
        val daFare = listOf(lavastoviglie(prossimo), spazzatura(prossimo + 2 * ORA))
        return MemoriaBlocco(
            attivo = false,
            prossimo = prossimo,
            daFare = daFare,
            ordine = Ordine(null, 0),
            ancora = Ancora(null, 0, adesso - MINUTO),
            scarto = 0,
            sentitoIl = adesso - MINUTO,
            conosciuto = true,
            elenco = daFare.mapIndexed { i, d -> locale(d, adesso - (2 - i) * ORA) } + chiuse(),
            ordineElenco = Ordine(null, 0),
            elencoIl = adesso - MINUTO,
        )
    }

    /** Tutto fatto: niente da fare, solo i lavori chiusi. */
    fun tuttoFatto(): MemoriaBlocco {
        val adesso = adesso()
        return MemoriaBlocco(
            attivo = false,
            ordine = Ordine(null, 0),
            ancora = Ancora(null, 0, adesso - MINUTO),
            scarto = 0,
            sentitoIl = adesso - MINUTO,
            conosciuto = true,
            elenco = chiuse(),
            ordineElenco = Ordine(null, 0),
            elencoIl = adesso - MINUTO,
        )
    }

    /** La foto della lavastoviglie è in coda; quella della spazzatura è stata rifiutata. */
    fun codaFoto() = MemoriaCodaFoto(
        foto = listOf(
            FotoInCoda(61, "61.jpg", adesso() - 5 * MINUTO, ChiaveCollegamento("abc"), StatiFoto.IN_CODA, 0, 2),
            FotoInCoda(63, "63.jpg", adesso() - 50 * MINUTO, ChiaveCollegamento("abc"), StatiFoto.RIFIUTATA, 0, 1),
        ),
    )

    fun faccendeLette() = FaccendeViewModel.StatoFaccende(caricamento = false, letto = true)

    /** (0.17, contratto v3.9) "letto": quattro lavori trovati su tutta la storia, di ogni stato. */
    fun ricercaLetto(): FaccendeViewModel.StatoFaccende {
        val adesso = adesso()
        val trovati = listOf(
            FaccendaLocale(
                id = 81, titolo = "Rifare il letto", stato = StatiFaccenda.DA_FARE, creataIl = adesso - 2 * ORA,
                genitore = "Mamma", bloccoDa = adesso + 2 * ORA,
            ),
            FaccendaLocale(
                id = 74, titolo = "Rifare il letto", stato = StatiFaccenda.FATTA, creataIl = adesso - 6 * GIORNO,
                genitore = "Mamma", fotoIl = adesso - 6 * GIORNO + ORA, foto = true, chiusaIl = adesso - 6 * GIORNO + ORA,
                confermataIl = adesso - 6 * GIORNO + 3 * ORA, confermataDa = "Papà",
            ),
            FaccendaLocale(
                id = 40, titolo = "Cambiare le lenzuola del letto", stato = StatiFaccenda.ANNULLATA, creataIl = adesso - 20 * GIORNO,
                genitore = "Papà", chiusaIl = adesso - 19 * GIORNO, annullataDa = "Papà",
            ),
            FaccendaLocale(
                id = 12, titolo = "Rifare il letto", stato = StatiFaccenda.FATTA, creataIl = adesso - 52 * GIORNO,
                genitore = "Mamma", fotoIl = adesso - 52 * GIORNO + ORA, foto = false, chiusaIl = adesso - 52 * GIORNO + ORA,
            ),
        )
        return faccendeLette().copy(
            ricerca = FaccendeViewModel.Ricerca(
                testo = "letto",
                cercato = "letto",
                esito = RicercaFaccende.Esito.Trovati(trovati, altre = false),
            ),
        )
    }

    /** (0.17) La ricerca con un server di prima della v3.9. */
    fun ricercaServerVecchio() = faccendeLette().copy(
        ricerca = FaccendeViewModel.Ricerca(testo = "letto", cercato = "letto", esito = RicercaFaccende.Esito.ServerVecchio),
    )

    // --- Diario -------------------------------------------------------------------

    fun diarioNormale(): DichiarazioniViewModel.StatoDiario {
        val oggi = oggi()
        return DichiarazioniViewModel.StatoDiario(
            caricamento = false,
            regoleVitaReale = listOf(calcio, lettura),
            dichiarazioni = listOf(
                Dichiarazione(
                    id = 31, regolaId = lettura.id, giorno = oggi.toString(), esito = EsitiDichiarazione.SUCCESSO,
                    nota = "Finito il capitolo 4", stato = StatiDichiarazione.IN_ATTESA, tsServer = iso(adesso() - ORA),
                ),
                Dichiarazione(
                    id = 30, regolaId = calcio.id, giorno = oggi.minusDays(1).toString(), esito = EsitiDichiarazione.SUCCESSO,
                    stato = StatiDichiarazione.CONFERMATA, tsServer = iso(adesso() - GIORNO),
                    verdetto = VerdettoDichiarazione("confermata", registro = "Papà ha confermato: allenamento fatto"),
                ),
                Dichiarazione(
                    id = 29, regolaId = calcio.id, giorno = oggi.minusDays(3).toString(), esito = EsitiDichiarazione.FALLIMENTO,
                    nota = "Pioveva, l'allenamento è saltato", stato = StatiDichiarazione.REGISTRATA,
                    tsServer = iso(adesso() - 3 * GIORNO),
                ),
                Dichiarazione(
                    id = 28, regolaId = lettura.id, giorno = oggi.minusDays(4).toString(), esito = EsitiDichiarazione.SUCCESSO,
                    stato = StatiDichiarazione.RIBALTATA, tsServer = iso(adesso() - 4 * GIORNO),
                    verdetto = VerdettoDichiarazione("ribaltata", nota = "Il segnalibro era ancora a pagina 40"),
                ),
                Dichiarazione(
                    id = 27, regolaId = lettura.id, giorno = oggi.minusDays(5).toString(), esito = EsitiDichiarazione.SUCCESSO,
                    stato = StatiDichiarazione.CONFERMATA_PER_CONTO, tsServer = iso(adesso() - 5 * GIORNO),
                ),
            ),
            fuso = "Europe/Rome",
        )
    }

    // --- Siti ---------------------------------------------------------------------

    fun sitiNormali(): SitiViewModel.StatoSiti {
        val oggi = oggi()
        return SitiViewModel.StatoSiti(
            caricamento = false,
            osservazioneAttiva = true,
            dominiOggi = 11,
            giorni = listOf(
                SitiGiorno(giorno = oggi.minusDays(3).toString(), totaleDomini = null),
                SitiGiorno(
                    giorno = oggi.minusDays(2).toString(), totaleDomini = 214, dnsCifrato = true,
                    domini = listOf(
                        DominioVisite("youtube.com", 312), DominioVisite("googlevideo.com", 290),
                        DominioVisite("instagram.com", 140), DominioVisite("cdninstagram.com", 133),
                        DominioVisite("tiktokv.com", 98), DominioVisite("google.com", 41),
                    ),
                ),
                SitiGiorno(giorno = oggi.minusDays(1).toString(), totaleDomini = 0),
                SitiGiorno(
                    giorno = oggi.toString(), totaleDomini = 9,
                    domini = listOf(
                        DominioVisite("youtube.com", 120), DominioVisite("instagram.com", 85),
                        DominioVisite("it.wikipedia.org", 12), DominioVisite("web.spaggiari.eu", 9),
                        DominioVisite("classroom.google.com", 7), DominioVisite("www.treccani.it", 4),
                        DominioVisite("un-dominio-molto-lungo-per-vedere-come-va-a-capo.esempio.com", 2),
                        DominioVisite("maps.google.com", 2), DominioVisite("open.spotify.com", 1),
                    ),
                ),
            ),
        )
    }
}
