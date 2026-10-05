package eu.stgm.pactum.genitore.fotografo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import eu.stgm.pactum.genitore.dati.Bocciatura
import eu.stgm.pactum.genitore.dati.BloccoFaccende
import eu.stgm.pactum.genitore.dati.BonusGiorno
import eu.stgm.pactum.genitore.dati.CodiceAbbinamento
import eu.stgm.pactum.genitore.dati.CodiceGenitore
import eu.stgm.pactum.genitore.dati.ContatoreBonus
import eu.stgm.pactum.genitore.dati.Dichiarazione
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.DispositivoFinestra
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Famiglia
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Genitore
import eu.stgm.pactum.genitore.dati.GenitoreFamiglia
import eu.stgm.pactum.genitore.dati.InfoApp
import eu.stgm.pactum.genitore.dati.InfoVersioni
import eu.stgm.pactum.genitore.dati.MediaPeriodo
import eu.stgm.pactum.genitore.dati.Medie
import eu.stgm.pactum.genitore.dati.ModificaSessione
import eu.stgm.pactum.genitore.dati.ModificaStorico
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.PaccoDichiarazioni
import eu.stgm.pactum.genitore.dati.PaccoFaccende
import eu.stgm.pactum.genitore.dati.PaccoGenitori
import eu.stgm.pactum.genitore.dati.PaccoNotifiche
import eu.stgm.pactum.genitore.dati.PaccoProposte
import eu.stgm.pactum.genitore.dati.PaccoSessioni
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.RiepilogoFinestra
import eu.stgm.pactum.genitore.dati.RiferimentoDispositivo
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.RispostaProposta
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.SessioneSvolta
import eu.stgm.pactum.genitore.dati.SitiGiorno
import eu.stgm.pactum.genitore.dati.SitoVisitato
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.UsoApp
import eu.stgm.pactum.genitore.dati.UsoCategoria
import eu.stgm.pactum.genitore.dati.UsoGiorno
import eu.stgm.pactum.genitore.dati.Verdetto
import eu.stgm.pactum.genitore.fotografo.ServerFinto.Companion.corpo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * I DATI FINTI del fotografo: una famiglia realistica, tutta inventata.
 * Due figli (Luca e Sara), due genitori (Mamma, che usa il telefono, e Papà) più
 * la Nonna ancora da collegare. Luca ha un telefono in contatto e un computer
 * spento; Sara un telefono silenzioso da due ore. Regole di ogni tipo con la
 * striscia dei giorni, sforamenti, interruzioni, proposte, sessioni da
 * approvare, lavori di casa in ogni stato (con foto finte), bonus e notifiche.
 *
 * Le date sono sempre relative ad adesso: la striscia finisce oggi.
 */
object DatiFinti {

    val ROMA: ZoneId = ZoneId.of("Europe/Rome")
    private val formato = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    fun ts(istante: Instant): String = formato.format(istante.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC))
    fun minutiFa(minuti: Long): String = ts(Instant.now().minus(minuti, ChronoUnit.MINUTES))
    fun oreFa(ore: Long): String = minutiFa(ore * 60)
    fun giorniFa(giorni: Long, ora: Int = 18, minuto: Int = 0): String =
        ts(oggi().minusDays(giorni).atTime(LocalTime.of(ora, minuto)).atZone(ROMA).toInstant())
    fun traMinuti(minuti: Long): String = ts(Instant.now().plus(minuti, ChronoUnit.MINUTES))
    fun oggi(): LocalDate = LocalDate.now(ROMA)

    /** Gli 8 giorni della finestra, dal più vecchio a oggi. */
    fun giorni8(): List<LocalDate> = (7 downTo 0).map { oggi().minusDays(it.toLong()) }

    fun striscia(vararg stati: String): List<QuadrettoSemaforo> =
        giorni8().zip(stati.toList()) { giorno, stato -> QuadrettoSemaforo(giorno.toString(), stato) }

    const val V = "verde"
    const val R = "rosso"
    const val G = "grigio"

    // --- Chi è chi -------------------------------------------------------------------

    val MAMMA = RiferimentoGenitore(1, "Mamma")
    val PAPA = RiferimentoGenitore(2, "Papà")

    const val LUCA = 1L
    const val SARA = 2L
    const val TEL_LUCA = 11L
    const val PC_LUCA = 12L
    const val TEL_SARA = 21L

    private val telLuca = RiferimentoDispositivo(TEL_LUCA, "Telefono", "telefono")
    private val pcLuca = RiferimentoDispositivo(PC_LUCA, "Computer di camera", "computer")
    private val telSara = RiferimentoDispositivo(TEL_SARA, "Telefono", "telefono")

    fun silenzioInContatto() = StatoSilenzio(ultimoBattito = minutiFa(4), silente = false)
    fun silenzioSpento() = StatoSilenzio(
        ultimoBattito = minutiFa(50),
        silente = false,
        spento = true,
        spentoDal = minutiFa(50),
    )
    fun silenzioSilente() = StatoSilenzio(ultimoBattito = minutiFa(135), silente = true)

    // Strisce: Luca ha due giorni fuori regola e un giorno senza dati; Sara è quasi tutta verde.
    fun strisciaLuca() = striscia(V, V, R, V, G, V, R, V)
    fun strisciaTelLuca() = striscia(V, V, R, V, G, V, R, V)
    fun strisciaPcLuca() = striscia(G, V, V, V, G, V, V, V)
    fun strisciaSara() = striscia(V, V, V, G, V, V, V, V)

    // --- Le regole --------------------------------------------------------------------

    private fun limite(
        id: Long,
        chiave: String,
        minuti: Int,
        dispositivo: RiferimentoDispositivo?,
        nome: String? = null,
        semaforo: List<QuadrettoSemaforo>,
        attiva: Boolean = true,
        creataGiorniFa: Long = 40,
        modificataGiorniFa: Long = 12,
    ) = RegolaFinestra(
        id = id,
        tipo = "limite_tempo",
        parametri = buildJsonObject {
            put("app_o_categoria", chiave)
            put("minuti_al_giorno", minuti)
        },
        nome = nome,
        attiva = attiva,
        creataTs = giorniFa(creataGiorniFa, 9),
        ultimaModificaTs = giorniFa(modificataGiorniFa, 9),
        allentabileDal = giorniFa(modificataGiorniFa - 4, 9),
        semaforo = semaforo,
        dispositivoId = dispositivo?.id,
        dispositivo = dispositivo,
    )

    private fun fascia(
        id: Long,
        dalle: String,
        alle: String,
        giorni: List<String>,
        dispositivo: RiferimentoDispositivo,
        semaforo: List<QuadrettoSemaforo>,
    ) = RegolaFinestra(
        id = id,
        tipo = "fascia_oraria",
        parametri = buildJsonObject {
            put("dalle", dalle)
            put("alle", alle)
            putJsonArray("giorni") { giorni.forEach { add(it) } }
        },
        creataTs = giorniFa(40, 9),
        ultimaModificaTs = giorniFa(3, 9),
        allentabileDal = giorniFa(-1, 9),
        semaforo = semaforo,
        dispositivoId = dispositivo.id,
        dispositivo = dispositivo,
    )

    private fun vitaReale(id: Long, descrizione: String, arbitro: String, frequenza: String, semaforo: List<QuadrettoSemaforo>) =
        RegolaFinestra(
            id = id,
            tipo = "vita_reale",
            parametri = buildJsonObject {
                put("descrizione", descrizione)
                put("arbitro_nome", arbitro)
                put("frequenza", frequenza)
            },
            creataTs = giorniFa(30, 9),
            ultimaModificaTs = giorniFa(30, 9),
            allentabileDal = giorniFa(26, 9),
            semaforo = semaforo,
        )

    private val tuttiIGiorni = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")
    private val giorniDiScuola = listOf("dom", "lun", "mar", "mer", "gio")

    fun regoleLuca(): List<RegolaFinestra> = listOf(
        limite(1, "com.zhiliaoapp.musically", 60, telLuca, "TikTok", striscia(V, V, R, V, G, V, R, V), modificataGiorniFa = 2),
        limite(2, "categoria:giochi", 90, telLuca, semaforo = striscia(V, V, V, V, G, V, V, V)),
        limite(3, "totale", 180, telLuca, semaforo = striscia(V, V, V, V, G, V, V, V)),
        fascia(4, "22:00", "07:00", tuttiIGiorni, telLuca, striscia(V, V, V, V, G, V, V, V)),
        limite(5, "exe:minecraft.exe", 120, pcLuca, "Minecraft", striscia(G, V, V, V, G, V, V, V)),
        limite(6, "sito:youtube.com", 60, pcLuca, semaforo = striscia(G, V, V, V, G, V, V, V), creataGiorniFa = 3, modificataGiorniFa = 3),
        fascia(7, "21:30", "07:00", giorniDiScuola, pcLuca, striscia(G, V, V, V, G, V, V, V)),
        vitaReale(8, "Un'ora di compiti prima dei videogiochi", "Papà", "tutti i giorni di scuola", striscia(V, G, V, V, G, V, G, G)),
        limite(9, "com.instagram.android", 45, telLuca, "Instagram", striscia(V, V, G, G, G, G, G, G), attiva = false),
    )

    fun regoleSara(): List<RegolaFinestra> = listOf(
        limite(21, "com.instagram.android", 60, telSara, "Instagram", striscia(V, V, V, G, V, V, V, V)),
        fascia(22, "21:30", "07:00", giorniDiScuola, telSara, striscia(V, V, V, G, V, V, V, V)),
        vitaReale(23, "Leggere 20 pagine del libro di narrativa", "Mamma", "tre volte a settimana", striscia(V, G, G, G, V, G, V, G)),
    )

    // --- Il tempo d'uso ------------------------------------------------------------------

    private fun giornoTelefono(giorno: LocalDate, fattore: Double, oggi: Boolean, sessione: Int? = null): UsoGiorno {
        fun m(base: Int) = (base * fattore).toInt().coerceAtLeast(1)
        val app = listOf(
            UsoApp("com.zhiliaoapp.musically", "TikTok", m(58), limite = 60, regolaId = 1, bonus = if (oggi) 15 else 0),
            UsoApp("com.google.android.youtube", "YouTube", m(41)),
            UsoApp("com.whatsapp", "WhatsApp", m(27)),
            UsoApp("com.supercell.brawlstars", "Brawl Stars", m(35)),
            UsoApp("com.spotify.music", "Spotify", m(22)),
            UsoApp("com.instagram.android", "Instagram", m(12)),
            UsoApp("eu.spaggiari.classevivafamiglia", "ClasseViva", m(6)),
            UsoApp("com.android.chrome", "Chrome", m(5)),
        ).sortedByDescending { it.minuti }
        val categorie = listOf(
            UsoCategoria("categoria:social", app.filter { it.chiave in setOf("com.zhiliaoapp.musically", "com.whatsapp", "com.instagram.android") }.sumOf { it.minuti }),
            UsoCategoria("categoria:video", app.first { it.chiave == "com.google.android.youtube" }.minuti),
            UsoCategoria("categoria:giochi", app.first { it.chiave == "com.supercell.brawlstars" }.minuti, limite = 90, regolaId = 2),
            UsoCategoria("categoria:musica", app.first { it.chiave == "com.spotify.music" }.minuti),
            UsoCategoria("categoria:altro", app.filter { it.chiave in setOf("eu.spaggiari.classevivafamiglia", "com.android.chrome") }.sumOf { it.minuti }),
        ).sortedByDescending { it.minuti }
        return UsoGiorno(
            giorno = giorno.toString(),
            totaleMinuti = app.sumOf { it.minuti },
            aggiornatoTs = if (oggi) minutiFa(6) else ts(giorno.atTime(23, 50).atZone(ROMA).toInstant()),
            app = app,
            categorie = categorie,
            limite = 180,
            regolaId = 3,
            bonus = 0,
            sessioniMinuti = sessione,
        )
    }

    /**
     * (0.16) Le medie e i totali come li calcola il server (v3.8) dagli STESSI
     * giorni delle barre: la settimana sono gli ultimi 7 degli 8 giorni (oggi
     * compreso), il mese quei 7 più l'ottavo e i 22 giorni ancora prima ([prima],
     * dal più vecchio; null = senza fotografia). Così le foto tornano coi conti.
     */
    fun medieDa(uso: List<UsoGiorno>, prima: List<Int?>): Medie {
        require(prima.size == 22) { "servono i 22 giorni prima degli 8" }
        fun periodo(minuti: List<Int?>): MediaPeriodo? {
            val conDati = minuti.filterNotNull()
            if (conDati.isEmpty()) return null
            val totale = conDati.sum()
            return MediaPeriodo(minuti = Math.round(totale.toDouble() / conDati.size).toInt(), giorni = conDati.size, totale = totale)
        }
        val otto = uso.map { it.totaleMinuti }
        return Medie(settimana = periodo(otto.takeLast(7)), mese = periodo(prima + otto))
    }

    fun usoTelLuca(): List<UsoGiorno> {
        val fattori = listOf(1.0, 0.9, 1.35, 0.8, 0.0, 1.1, 1.4, 0.75)
        return giorni8().mapIndexed { i, giorno ->
            if (fattori[i] == 0.0) {
                UsoGiorno(giorno.toString()) // nessuna fotografia: MAI uno zero finto
            } else {
                giornoTelefono(giorno, fattori[i], oggi = i == 7, sessione = if (i == 6 || i == 7) 45 else null)
            }
        }
    }

    fun usoPcLuca(): List<UsoGiorno> {
        val fattori = listOf(0.0, 1.0, 0.6, 1.2, 0.0, 0.9, 1.1, 0.4)
        return giorni8().mapIndexed { i, giorno ->
            val f = fattori[i]
            if (f == 0.0) return@mapIndexed UsoGiorno(giorno.toString())
            fun m(base: Int) = (base * f).toInt().coerceAtLeast(1)
            val app = listOf(
                UsoApp("exe:minecraft.exe", "Minecraft", m(95), limite = 120, regolaId = 5),
                UsoApp("exe:chrome.exe", "Google Chrome", m(64)),
                UsoApp("exe:winword.exe", "Microsoft Word", m(31)),
                UsoApp("exe:discord.exe", "Discord", m(18)),
            )
            UsoGiorno(
                giorno = giorno.toString(),
                totaleMinuti = app.sumOf { it.minuti },
                aggiornatoTs = if (i == 7) minutiFa(52) else ts(giorno.atTime(22, 40).atZone(ROMA).toInstant()),
                app = app,
                categorie = listOf(
                    UsoCategoria("categoria:giochi", m(95)),
                    UsoCategoria("categoria:video", m(42)),
                    UsoCategoria("categoria:altro", m(53)),
                    UsoCategoria("categoria:social", m(18)),
                ),
            )
        }
    }

    fun usoTelSara(): List<UsoGiorno> = giorni8().mapIndexed { i, giorno ->
        if (i == 3) return@mapIndexed UsoGiorno(giorno.toString())
        val f = listOf(0.9, 1.0, 0.8, 0.0, 1.1, 0.7, 0.95, 0.5)[i]
        fun m(base: Int) = (base * f).toInt().coerceAtLeast(1)
        val app = listOf(
            UsoApp("com.instagram.android", "Instagram", m(52), limite = 60, regolaId = 21),
            UsoApp("com.whatsapp", "WhatsApp", m(44)),
            UsoApp("com.pinterest", "Pinterest", m(20)),
            UsoApp("com.duolingo", "Duolingo", m(15)),
        )
        UsoGiorno(
            giorno = giorno.toString(),
            totaleMinuti = app.sumOf { it.minuti },
            aggiornatoTs = if (i == 7) minutiFa(140) else ts(giorno.atTime(22, 0).atZone(ROMA).toInstant()),
            app = app,
            categorie = listOf(
                UsoCategoria("categoria:social", m(116)),
                UsoCategoria("categoria:altro", m(15)),
            ),
        )
    }

    fun sitiTelLuca(): List<SitiGiorno> = giorni8().mapIndexed { i, giorno ->
        if (i == 4) return@mapIndexed SitiGiorno(giorno.toString())
        SitiGiorno(
            giorno = giorno.toString(),
            totaleDomini = 23,
            dnsCifrato = i == 6,
            aggiornatoTs = if (i == 7) minutiFa(6) else ts(giorno.atTime(23, 50).atZone(ROMA).toInstant()),
            domini = listOf(
                SitoVisitato("instagram.com", 128),
                SitoVisitato("youtube.com", 54),
                SitoVisitato("google.com", 40),
                SitoVisitato("tiktokcdn.com", 33),
                SitoVisitato("whatsapp.net", 21),
                SitoVisitato("wikipedia.org", 12),
                SitoVisitato("spaggiari.eu", 9),
                SitoVisitato("brawlstars.com", 4),
            ),
        )
    }

    fun sitiPcLuca(): List<SitiGiorno> = giorni8().mapIndexed { i, giorno ->
        if (i == 0 || i == 4) return@mapIndexed SitiGiorno(giorno.toString())
        SitiGiorno(
            giorno = giorno.toString(),
            totaleDomini = 9,
            aggiornatoTs = if (i == 7) minutiFa(52) else ts(giorno.atTime(22, 40).atZone(ROMA).toInstant()),
            domini = listOf(
                SitoVisitato("youtube.com", 14, minuti = 42),
                SitoVisitato("wikipedia.org", 5, minuti = 12),
                SitoVisitato("google.com", 20, minuti = 6),
                SitoVisitato("planetminecraft.com", 3, minuti = 5),
            ),
        )
    }

    private fun bonus(usatiGiorno: Int, usatiSettimana: Int) = StatoBonus(
        ContatoreBonus(usatiGiorno, 30, 30 - usatiGiorno),
        ContatoreBonus(usatiSettimana, 90, 90 - usatiSettimana),
    )

    private fun bonusGiornalieri(vararg minuti: Int) = giorni8().zip(minuti.toList()) { g, m -> BonusGiorno(g.toString(), m) }

    // --- I dispositivi nella finestra ----------------------------------------------------

    fun dispositiviFinestraLuca() = listOf(
        DispositivoFinestra(
            id = TEL_LUCA, nome = "Telefono", tipo = "telefono",
            statoSilenzio = silenzioInContatto(),
            striscia = strisciaTelLuca(),
            usoRecente = usoTelLuca(),
            sitiRecenti = sitiTelLuca(),
            medie = medieDa(usoTelLuca(), prima = listOf(150, 172, null, 165, 140, 188, 160, 155, 170, 149, 162, 175, 158, 143, 166, 171, null, 152, 160, 168, 145, 159)),
            bonus = bonus(15, 30),
            bonusGiornalieri = bonusGiornalieri(0, 15, 0, 0, 0, 0, 15, 15),
        ),
        DispositivoFinestra(
            id = PC_LUCA, nome = "Computer di camera", tipo = "computer",
            statoSilenzio = silenzioSpento(),
            striscia = strisciaPcLuca(),
            usoRecente = usoPcLuca(),
            sitiRecenti = sitiPcLuca(),
            medie = medieDa(usoPcLuca(), prima = listOf(120, null, 135, 110, null, 142, 128, null, 117, 133, 125, null, 140, 112, 130, null, 121, 138, null, 126, 119, 131)),
            bonus = bonus(0, 0),
            bonusGiornalieri = bonusGiornalieri(0, 0, 0, 0, 0, 0, 0, 0),
        ),
    )

    // --- Eventi, storia ---------------------------------------------------------------------

    private fun evento(id: String, tipo: String, ts: String, dispositivo: Long?, dettagli: JsonObject) =
        EventoFinestra(id = id, tipo = tipo, dettagli = dettagli, tsServer = ts, dispositivoId = dispositivo)

    fun sforamentiLuca() = listOf(
        evento("e-1", "sforamento", giorniFa(1, 21, 12), TEL_LUCA, buildJsonObject {
            put("regola_id", 1)
            put("giorno", oggi().minusDays(1).toString())
            put("limite_efficace", 75)
            put("minuti_oltre", 22)
        }),
        evento("e-2", "sforamento", giorniFa(5, 19, 40), TEL_LUCA, buildJsonObject {
            put("regola_id", 1)
            put("giorno", oggi().minusDays(5).toString())
            put("limite_efficace", 60)
            put("minuti_oltre", 18)
        }),
    )

    fun manomissioniLuca() = listOf(
        evento("m-1", "manomissione", giorniFa(3, 23, 5), TEL_LUCA, buildJsonObject {
            put("sotto_tipo", "cambio_ora")
            put("drift_secondi", 3600)
        }),
        evento("m-2", "manomissione", giorniFa(6, 16, 20), PC_LUCA, buildJsonObject {
            put("sotto_tipo", "programma_chiuso")
            put("dal", oggi().minusDays(6).atTime(15, 10).atZone(ROMA).toInstant().toEpochMilli())
            put("al", oggi().minusDays(6).atTime(15, 40).atZone(ROMA).toInstant().toEpochMilli())
        }),
    )

    fun storicoLuca(): List<ModificaStorico> = listOf(
        ModificaStorico(
            id = 30, regolaId = 1, azione = "modifica", direzione = "allenta", concordata = true,
            prima = buildJsonObject { put("app_o_categoria", "com.zhiliaoapp.musically"); put("minuti_al_giorno", 45) },
            dopo = buildJsonObject { put("app_o_categoria", "com.zhiliaoapp.musically"); put("minuti_al_giorno", 60) },
            tsServer = giorniFa(2, 9),
        ),
        ModificaStorico(
            id = 29, regolaId = 6, azione = "creazione",
            dopo = buildJsonObject { put("app_o_categoria", "sito:youtube.com"); put("minuti_al_giorno", 60) },
            tsServer = giorniFa(3, 9),
        ),
        ModificaStorico(
            id = 28, regolaId = 4, azione = "modifica", direzione = "stringe",
            prima = buildJsonObject {
                put("dalle", "22:30"); put("alle", "07:00")
                putJsonArray("giorni") { tuttiIGiorni.forEach { add(it) } }
            },
            dopo = buildJsonObject {
                put("dalle", "22:00"); put("alle", "07:00")
                putJsonArray("giorni") { tuttiIGiorni.forEach { add(it) } }
            },
            tsServer = giorniFa(3, 9),
        ),
        ModificaStorico(
            id = 27, regolaId = 9, azione = "eliminazione", direzione = "allenta",
            prima = buildJsonObject { put("app_o_categoria", "com.instagram.android"); put("minuti_al_giorno", 45) },
            tsServer = giorniFa(6, 9),
        ),
    )

    // --- Proposte ---------------------------------------------------------------------------

    fun propostaDiLuca() = Proposta(
        id = 41, regolaId = 2,
        parametriProposti = buildJsonObject { put("app_o_categoria", "categoria:giochi"); put("minuti_al_giorno", 120) },
        motivazione = "Sabato gioco online con Marco e Pietro, finiamo il torneo",
        confronto = "+30 min al giorno rispetto ad ora",
        direzione = "allenta",
        stato = "pendente",
        tsServer = oreFa(1),
        autore = "figlio",
    )

    fun propostaDiPapa() = Proposta(
        id = 42, regolaId = 5,
        parametriProposti = buildJsonObject { put("app_o_categoria", "exe:minecraft.exe"); put("minuti_al_giorno", 90) },
        motivazione = "Nei giorni di scuola bastano un'ora e mezza",
        confronto = "−30 min al giorno rispetto ad ora",
        direzione = "stringe",
        stato = "pendente",
        tsServer = oreFa(20),
        autore = "genitore",
        genitore = PAPA,
    )

    fun proposteChiuseLuca() = listOf(
        Proposta(
            id = 40, regolaId = 1,
            parametriProposti = buildJsonObject { put("app_o_categoria", "com.zhiliaoapp.musically"); put("minuti_al_giorno", 60) },
            motivazione = "Un quarto d'ora in più, ma solo se i compiti sono fatti",
            confronto = "+15 min al giorno rispetto ad ora",
            direzione = "allenta",
            stato = "accettata", usata = true,
            tsServer = giorniFa(3, 20),
            risposta = RispostaProposta("accetta", "Ok, promesso", giorniFa(2, 9)),
            autore = "genitore", genitore = MAMMA,
        ),
        Proposta(
            id = 39, regolaId = 4,
            parametriProposti = buildJsonObject {
                put("dalle", "21:30"); put("alle", "07:00")
                putJsonArray("giorni") { tuttiIGiorni.forEach { add(it) } }
            },
            motivazione = "Mezz'ora prima a letto",
            confronto = "La fascia comincia 30 min prima",
            direzione = "stringe",
            stato = "rifiutata",
            tsServer = giorniFa(5, 20),
            risposta = RispostaProposta("rifiuta", "Il venerdì no però!", giorniFa(5, 21)),
            autore = "genitore", genitore = PAPA,
        ),
        Proposta(
            id = 38, regolaId = 2,
            parametriProposti = buildJsonObject { put("azione", "elimina") },
            motivazione = "Tanto non gioco quasi mai",
            confronto = "propone di eliminare la regola",
            direzione = "elimina",
            stato = "rifiutata",
            tsServer = giorniFa(9, 17),
            risposta = RispostaProposta("rifiuta", "Restiamo così per ora", giorniFa(9, 19)),
            autore = "figlio",
            rispostaDi = MAMMA,
        ),
        Proposta(
            id = 37, regolaId = 3,
            parametriProposti = buildJsonObject { put("app_o_categoria", "totale"); put("minuti_al_giorno", 210) },
            confronto = "+30 min al giorno rispetto ad ora",
            direzione = "allenta",
            stato = "ritirata",
            tsServer = giorniFa(11, 17),
            autore = "figlio",
        ),
    )

    // --- Sessioni ----------------------------------------------------------------------------

    private val nomiStudio = mapOf(
        "eu.spaggiari.classevivafamiglia" to "ClasseViva",
        "com.google.android.apps.classroom" to "Classroom",
        "com.microsoft.office.word" to "Word",
        "gruppo:apk" to "App installate da APK",
    )

    fun sessioniLuca(): List<Sessione> = listOf(
        Sessione(
            id = 31, dispositivoId = TEL_LUCA, dispositivo = telLuca,
            nome = "Studio",
            app = nomiStudio.keys.toList(),
            nomi = nomiStudio,
            stato = "approvata",
            modificaInAttesa = ModificaSessione(
                nome = "Studio",
                app = nomiStudio.keys.toList() + "com.whatsapp",
                nomi = nomiStudio + ("com.whatsapp" to "WhatsApp"),
                richiestaTs = minutiFa(50),
            ),
            versione = 4,
            creataTs = giorniFa(10, 16),
            approvataTs = giorniFa(10, 18),
            decisaDa = MAMMA,
        ),
        Sessione(
            id = 32, dispositivoId = TEL_LUCA, dispositivo = telLuca,
            nome = "Allenamento",
            app = listOf("com.strava", "com.spotify.music"),
            nomi = mapOf("com.strava" to "Strava", "com.spotify.music" to "Spotify"),
            stato = "in_attesa",
            versione = 1,
            creataTs = oreFa(2),
        ),
        Sessione(
            id = 33, dispositivoId = TEL_LUCA, dispositivo = telLuca,
            nome = "Musica",
            app = listOf("com.yousician.yousician", "com.spotify.music"),
            nomi = mapOf("com.yousician.yousician" to "Yousician", "com.spotify.music" to "Spotify"),
            stato = "approvata",
            versione = 2,
            creataTs = giorniFa(20, 16),
            approvataTs = giorniFa(20, 19),
            decisaDa = PAPA,
        ),
    )

    fun sessioniSvolteLuca(): List<SessioneSvolta> = listOf(
        SessioneSvolta(
            id = 74, sessioneId = 31, dispositivoId = TEL_LUCA, nome = "Studio",
            app = nomiStudio.keys.toList(), nomi = nomiStudio,
            inizioTs = minutiFa(25), durataMinuti = 60, finePrevistaTs = traMinuti(35), inCorso = true,
        ),
        SessioneSvolta(
            id = 73, sessioneId = 31, dispositivoId = TEL_LUCA, nome = "Studio",
            app = nomiStudio.keys.toList(), nomi = nomiStudio,
            inizioTs = giorniFa(1, 15, 0), durataMinuti = 90, finePrevistaTs = giorniFa(1, 16, 30),
            fineTs = giorniFa(1, 16, 30), chiusura = "scaduta",
        ),
        SessioneSvolta(
            id = 72, sessioneId = 33, dispositivoId = TEL_LUCA, nome = "Musica",
            app = listOf("com.yousician.yousician"), nomi = mapOf("com.yousician.yousician" to "Yousician"),
            inizioTs = giorniFa(2, 17, 0), durataMinuti = 45, finePrevistaTs = giorniFa(2, 17, 45),
            fineTs = giorniFa(2, 17, 20), chiusura = "terminata",
        ),
        SessioneSvolta(
            id = 71, sessioneId = 31, dispositivoId = TEL_LUCA, nome = "Studio",
            app = nomiStudio.keys.toList(), nomi = nomiStudio,
            inizioTs = giorniFa(3, 15, 30), durataMinuti = 60, finePrevistaTs = giorniFa(3, 16, 30),
            fineTs = giorniFa(3, 16, 30), chiusura = "scaduta",
        ),
    )

    // --- Lavori di casa ----------------------------------------------------------------------

    fun faccendeLuca(): List<Faccenda> = listOf(
        Faccenda(
            id = 101, figlioId = LUCA, titolo = "Svuota la lavastoviglie", nota = "Anche le posate, per favore",
            // Dato con "Subito": il blocco è partito quando è stato dato.
            stato = "da_fare", bloccoDa = minutiFa(75), creataTs = minutiFa(75), creataDa = MAMMA,
        ),
        Faccenda(
            id = 102, figlioId = LUCA, titolo = "Metti in ordine la camera",
            stato = "da_fare", bloccoDa = minutiFa(70), creataTs = oreFa(26), creataDa = PAPA,
            fotoTs = oreFa(3), foto = false, bocciature = 1,
            ultimaBocciatura = Bocciatura(ts = oreFa(2), nota = "Il letto è ancora disfatto e i vestiti sono sulla sedia", da = MAMMA),
        ),
        Faccenda(
            id = 103, figlioId = LUCA, titolo = "Porta fuori la spazzatura",
            stato = "fatta", bloccoDa = oreFa(5), creataTs = oreFa(5), creataDa = MAMMA,
            fotoTs = minutiFa(40), foto = true, chiusaTs = minutiFa(40),
        ),
        Faccenda(
            id = 104, figlioId = LUCA, titolo = "Stendi i panni", nota = "Quelli scuri sullo stendino in terrazza",
            stato = "fatta", bloccoDa = giorniFa(2, 16), creataTs = giorniFa(2, 15), creataDa = PAPA,
            fotoTs = giorniFa(2, 17, 12), foto = true, chiusaTs = giorniFa(2, 17, 12),
            // (0.17) Segnato come svolto da Papà.
            confermataTs = giorniFa(2, 18, 5), confermataDa = PAPA,
        ),
        Faccenda(
            id = 105, figlioId = LUCA, titolo = "Pulisci la gabbia del criceto",
            stato = "fatta", bloccoDa = giorniFa(29, 16), creataTs = giorniFa(29, 15), creataDa = MAMMA,
            fotoTs = giorniFa(29, 17), foto = false, chiusaTs = giorniFa(29, 17),
        ),
        Faccenda(
            id = 106, figlioId = LUCA, titolo = "Compiti di matematica a pagina 112",
            stato = "annullata", bloccoDa = giorniFa(1, 15), creataTs = giorniFa(1, 14), creataDa = PAPA,
            chiusaTs = giorniFa(1, 15, 30), annullataDa = PAPA,
        ),
    )

    fun bloccoLuca() = BloccoFaccende(attivo = true, dal = minutiFa(70))

    /** (0.17) Tutta la storia dei lavori di Luca (la ricerca guarda anche i vecchi, senza più foto). */
    fun storiaLuca(): List<Faccenda> = faccendeLuca() + listOf(
        Faccenda(
            id = 92, figlioId = LUCA, titolo = "Lavatrice: stendi i bianchi",
            stato = "fatta", bloccoDa = giorniFa(45, 16), creataTs = giorniFa(45, 15), creataDa = MAMMA,
            fotoTs = giorniFa(45, 17), foto = false, chiusaTs = giorniFa(45, 17),
        ),
        Faccenda(
            id = 90, figlioId = LUCA, titolo = "Lava i piatti dopo cena",
            stato = "fatta", bloccoDa = giorniFa(62, 21), creataTs = giorniFa(62, 20), creataDa = PAPA,
            fotoTs = giorniFa(62, 21, 40), foto = false, chiusaTs = giorniFa(62, 21, 40),
            confermataTs = giorniFa(62, 22), confermataDa = MAMMA,
        ),
        Faccenda(
            id = 91, figlioId = LUCA, titolo = "Lava la bici in garage",
            stato = "annullata", bloccoDa = giorniFa(80, 15), creataTs = giorniFa(80, 14), creataDa = PAPA,
            chiusaTs = giorniFa(80, 16), annullataDa = PAPA,
        ),
    )

    /** (0.17) La ricerca del server finto: il titolo contiene il testo (maiuscole non contano), dal più recente. */
    fun cercaNellaStoria(figlioId: Long?, testo: String): PaccoFaccende {
        val storia = if (figlioId == SARA) emptyList() else storiaLuca()
        val cercato = testo.trim().lowercase()
        val trovati = storia.filter { it.titolo.lowercase().contains(cercato) }.sortedByDescending { it.creataTs }
        return PaccoFaccende(faccende = trovati.take(50), altre = trovati.size > 50)
    }

    // --- Finestre -------------------------------------------------------------------------

    fun finestraLuca(
        regole: List<RegolaFinestra> = regoleLuca(),
        faccende: List<Faccenda>? = faccendeLuca(),
        conProposte: Boolean = true,
        conSessioni: Boolean = true,
    ): Finestra {
        val dispositivi = dispositiviFinestraLuca()
        val primo = dispositivi.first()
        return Finestra(
            regole = regole,
            sforamentiRecenti = sforamentiLuca(),
            manomissioniRecenti = manomissioniLuca(),
            storicoModifiche = storicoLuca(),
            bonus = primo.bonus,
            bonusGiornalieri = primo.bonusGiornalieri,
            statoSilenzio = primo.statoSilenzio,
            usoRecente = primo.usoRecente,
            medie = primo.medie,
            sitiRecenti = primo.sitiRecenti,
            striscia = strisciaLuca(),
            riepilogo = RiepilogoFinestra(giorniFuoriRegola = 2, interruzioni = 2),
            segnoOggi = false,
            dispositivi = dispositivi,
            propostePendenti = if (conProposte) listOf(propostaDiLuca(), propostaDiPapa()) else emptyList(),
            sessioni = if (conSessioni) sessioniLuca() else emptyList(),
            sessioniDaApprovare = if (conSessioni) 2 else 0,
            sessioniSvolte = if (conSessioni) sessioniSvolteLuca() else emptyList(),
            faccende = faccende,
            blocco = if (faccende.isNullOrEmpty()) BloccoFaccende() else bloccoLuca(),
        )
    }

    fun dispositiviFinestraSara() = listOf(
        DispositivoFinestra(
            id = TEL_SARA, nome = "Telefono", tipo = "telefono",
            statoSilenzio = silenzioSilente(),
            striscia = strisciaSara(),
            usoRecente = usoTelSara(),
            sitiRecenti = null,
            medie = medieDa(usoTelSara(), prima = listOf(118, 130, 112, null, 125, 121, 135, 109, 127, 116, 122, 131, 114, 128, null, 119, 126, 133, 110, 124, 120, 129)),
            bonus = bonus(15, 15),
            bonusGiornalieri = bonusGiornalieri(0, 0, 0, 0, 0, 0, 0, 15),
        ),
    )

    fun finestraSara(): Finestra {
        val dispositivi = dispositiviFinestraSara()
        val primo = dispositivi.first()
        return Finestra(
            regole = regoleSara(),
            storicoModifiche = listOf(
                ModificaStorico(
                    id = 51, regolaId = 21, azione = "creazione",
                    dopo = buildJsonObject { put("app_o_categoria", "com.instagram.android"); put("minuti_al_giorno", 60) },
                    tsServer = giorniFa(20, 10),
                ),
            ),
            bonus = primo.bonus,
            bonusGiornalieri = primo.bonusGiornalieri,
            statoSilenzio = primo.statoSilenzio,
            usoRecente = primo.usoRecente,
            medie = primo.medie,
            striscia = strisciaSara(),
            riepilogo = RiepilogoFinestra(0, 0),
            segnoOggi = true,
            dispositivi = dispositivi,
            faccende = emptyList(),
            blocco = BloccoFaccende(),
        )
    }

    /** Un figlio appena creato: niente dispositivi, niente regole. */
    fun finestraVuota() = Finestra(
        statoSilenzio = StatoSilenzio(silente = true),
        striscia = striscia(G, G, G, G, G, G, G, G),
        riepilogo = RiepilogoFinestra(0, 0),
        faccende = emptyList(),
        blocco = BloccoFaccende(),
    )

    /** Un dispositivo appena collegato, senza nessuna regola ancora. */
    fun finestraSenzaRegole() = Finestra(
        bonus = bonus(0, 0),
        statoSilenzio = silenzioInContatto(),
        striscia = striscia(G, G, G, G, G, G, G, G),
        riepilogo = RiepilogoFinestra(0, 0),
        dispositivi = listOf(
            DispositivoFinestra(
                id = TEL_SARA, nome = "Telefono", tipo = "telefono",
                statoSilenzio = silenzioInContatto(),
                striscia = striscia(G, G, G, G, G, G, G, G),
                usoRecente = giorni8().map { UsoGiorno(it.toString()) },
                sitiRecenti = giorni8().map { SitiGiorno(it.toString()) },
                bonus = bonus(0, 0),
                bonusGiornalieri = bonusGiornalieri(0, 0, 0, 0, 0, 0, 0, 0),
            ),
        ),
        faccende = emptyList(),
        blocco = BloccoFaccende(),
    )

    /** La finestra di un server 0.7: un figlio, un telefono, niente famiglia, niente dispositivi. */
    fun finestraServerVecchio(): Finestra {
        val luca = finestraLuca()
        return Finestra(
            regole = luca.regole.filter { it.dispositivoId != PC_LUCA }.map { it.copy(dispositivoId = null, dispositivo = null) },
            sforamentiRecenti = luca.sforamentiRecenti.map { it.copy(dispositivoId = null) },
            manomissioniRecenti = luca.manomissioniRecenti.take(1).map { it.copy(dispositivoId = null) },
            storicoModifiche = luca.storicoModifiche,
            bonus = luca.bonus,
            bonusGiornalieri = luca.bonusGiornalieri,
            statoSilenzio = luca.statoSilenzio,
            usoRecente = luca.usoRecente,
            // Un server 0.7 non conosce il `totale` della v3.8: solo le medie.
            medie = luca.medie?.let { Medie(it.settimana?.copy(totale = null), it.mese?.copy(totale = null)) },
            sitiRecenti = luca.sitiRecenti,
            striscia = luca.striscia,
            riepilogo = luca.riepilogo,
        )
    }

    // --- La famiglia --------------------------------------------------------------------------

    fun famiglia(soloLuca: Boolean = false, saraVuota: Boolean = false): Famiglia {
        val luca = Figlio(
            id = LUCA, nome = "Luca",
            striscia = strisciaLuca(),
            riepilogo = RiepilogoFinestra(2, 2),
            notificheNonLette = 7,
            dispositivi = listOf(
                Dispositivo(TEL_LUCA, "Telefono", "telefono", versioneApp = "0.14.0", statoSilenzio = silenzioInContatto()),
                Dispositivo(PC_LUCA, "Computer di camera", "computer", versioneApp = "0.14.0", statoSilenzio = silenzioSpento()),
            ),
            proposteDaDecidere = 1,
            sessioniDaApprovare = 2,
            faccendeDaFare = 2,
            bloccoAttivo = true,
        )
        val sara = if (saraVuota) {
            Figlio(id = SARA, nome = "Sara", striscia = striscia(G, G, G, G, G, G, G, G), riepilogo = RiepilogoFinestra(0, 0))
        } else {
            Figlio(
                id = SARA, nome = "Sara",
                striscia = strisciaSara(),
                riepilogo = RiepilogoFinestra(0, 0),
                notificheNonLette = 2,
                dispositivi = listOf(
                    Dispositivo(TEL_SARA, "Telefono", "telefono", versioneApp = "0.13.0", statoSilenzio = silenzioSilente()),
                ),
            )
        }
        return Famiglia(
            figli = if (soloLuca) listOf(luca) else listOf(luca, sara),
            io = MAMMA,
            genitori = listOf(GenitoreFamiglia(1, "Mamma"), GenitoreFamiglia(2, "Papà"), GenitoreFamiglia(3, "Nonna")),
        )
    }

    fun genitori() = PaccoGenitori(
        io = MAMMA,
        genitori = listOf(
            Genitore(1, "Mamma", abbinato = true, creatoTs = giorniFa(60, 9)),
            Genitore(2, "Papà", abbinato = true, creatoTs = giorniFa(2, 9)),
            Genitore(3, "Nonna", abbinato = false, creatoTs = oreFa(1)),
        ),
    )

    // --- Notifiche ----------------------------------------------------------------------------

    /** Le non lette, con gli id in ordine di tempo come sul server vero (autoincrementali). */
    fun notifiche(): List<Notifica> = listOf(
        Notifica(
            id = 201, tipo = "manomissione", messaggio = "Evento manomissione registrato",
            payload = buildJsonObject {
                put("evento_id", "m-1")
                putJsonObject("dettagli") { put("sotto_tipo", "cambio_ora"); put("drift_secondi", 3600) }
            },
            tsServer = giorniFa(3, 23, 5), figlioId = LUCA, dispositivoId = TEL_LUCA,
        ),
        Notifica(
            id = 202, tipo = "faccende_finite", messaggio = "Sara ha finito i lavori di casa: telefono sbloccato",
            payload = buildJsonObject { putJsonArray("faccenda_ids") { add(301); add(302) } },
            tsServer = giorniFa(1, 17, 50), figlioId = SARA,
        ),
        Notifica(
            id = 203, tipo = "dichiarazione", messaggio = "Luca dichiara un successo",
            payload = buildJsonObject {
                put("dichiarazione_id", 61); put("regola_id", 8); put("esito", "successo")
                put("giorno", oggi().minusDays(1).toString())
            },
            tsServer = giorniFa(1, 19, 30), figlioId = LUCA,
        ),
        Notifica(
            id = 204, tipo = "sforamento", messaggio = "Evento sforamento registrato",
            payload = buildJsonObject {
                put("evento_id", "e-1")
                putJsonObject("dettagli") { put("regola_id", 1); put("minuti_oltre", 22) }
            },
            tsServer = giorniFa(1, 21, 12), figlioId = LUCA, dispositivoId = TEL_LUCA,
        ),
        Notifica(
            id = 205, tipo = "bonus", messaggio = "Bonus di 15 minuti",
            payload = buildJsonObject {
                put("minuti", 15); put("regola_id", 21); put("motivo", "Devo finire la chat del gruppo di scienze")
                put("residuo_giorno", 15); put("residuo_settimana", 75)
            },
            tsServer = oreFa(3), figlioId = SARA, dispositivoId = TEL_SARA,
        ),
        Notifica(
            id = 206, tipo = "sessione_da_approvare", messaggio = "Luca chiede di approvare una sessione",
            payload = buildJsonObject { put("sessione_id", 32); put("nome", "Allenamento"); put("cambio", false) },
            tsServer = oreFa(2), figlioId = LUCA, dispositivoId = TEL_LUCA,
        ),
        Notifica(
            id = 207, tipo = "nuova_proposta", messaggio = "Luca propone un cambio",
            payload = buildJsonObject {
                put("proposta_id", 41); put("regola_id", 2); put("confronto", "+30 min al giorno rispetto ad ora")
                put("direzione", "allenta"); put("autore", "figlio")
            },
            tsServer = oreFa(1), figlioId = LUCA, dispositivoId = TEL_LUCA,
        ),
        Notifica(
            id = 208, tipo = "sospensione", messaggio = "Il computer si è spento",
            payload = buildJsonObject { put("evento_id", "s-1"); putJsonObject("dettagli") { put("motivo", "spegnimento") } },
            tsServer = minutiFa(50), figlioId = LUCA, dispositivoId = PC_LUCA,
        ),
        Notifica(
            id = 209, tipo = "faccenda_fatta", messaggio = "Luca ha fatto un lavoro di casa",
            payload = buildJsonObject { put("faccenda_id", 103); put("titolo", "Porta fuori la spazzatura") },
            tsServer = minutiFa(40), figlioId = LUCA,
        ),
    )

    // --- Dichiarazioni --------------------------------------------------------------------

    fun dichiarazioniLuca() = listOf(
        Dichiarazione(
            id = 61, regolaId = 8, giorno = oggi().minusDays(1).toString(), esito = "successo",
            nota = "Fatti prima di cena, anche l'inglese", stato = "in_attesa", tsServer = giorniFa(1, 19, 30),
        ),
        Dichiarazione(
            id = 60, regolaId = 8, giorno = oggi().minusDays(3).toString(), esito = "successo",
            stato = "confermata", tsServer = giorniFa(3, 19),
            verdetto = Verdetto("conferma", registro = "confermato dal genitore", tsServer = giorniFa(3, 21), da = PAPA),
        ),
        Dichiarazione(
            id = 59, regolaId = 8, giorno = oggi().minusDays(4).toString(), esito = "fallimento",
            nota = "Avevo allenamento fino alle 19", stato = "registrata", tsServer = giorniFa(4, 20),
        ),
    )

    // --- Le scritture ---------------------------------------------------------------------

    fun codiceGenitore() = CodiceGenitore(
        genitore = Genitore(4, "Nonno Piero", abbinato = false),
        codice = "482915",
        scadeTs = traMinuti(15),
    )

    fun codiceDispositivo() = CodiceAbbinamento(
        dispositivo = RiferimentoDispositivo(13, "Tablet", "telefono"),
        codice = "730418",
        scadeTs = traMinuti(15),
    )

    // --- La foto finta -----------------------------------------------------------------------

    /**
     * Una "foto" del telefono di Luca: un sacco della spazzatura accanto alla porta.
     * Disegnata qui (Canvas di Android, dentro Robolectric), JPEG come quelle vere,
     * verticale 1200×1600.
     */
    fun fotoFinta(): ByteArray {
        val l = 1200
        val a = 1600
        val bitmap = Bitmap.createBitmap(l, a, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Muro e pavimento.
        p.shader = LinearGradient(0f, 0f, 0f, 1050f, 0xFFE8E1D3.toInt(), 0xFFC9BFAC.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, l.toFloat(), 1050f, p)
        p.shader = LinearGradient(0f, 1050f, 0f, a.toFloat(), 0xFF8A6F52.toInt(), 0xFF5E4833.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 1050f, l.toFloat(), a.toFloat(), p)
        p.shader = null
        // La porta.
        p.color = 0xFF6B4B2E.toInt()
        c.drawRect(620f, 180f, 1040f, 1050f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 10f
        p.color = 0xFF4A331F.toInt()
        c.drawRect(650f, 220f, 1010f, 600f, p)
        c.drawRect(650f, 640f, 1010f, 1020f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFD8B34A.toInt()
        c.drawCircle(698f, 628f, 18f, p)
        // Il sacco.
        p.color = 0xFF22262B.toInt()
        c.drawRoundRect(RectF(180f, 700f, 560f, 1260f), 160f, 160f, p)
        c.drawCircle(370f, 690f, 70f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 14f
        p.color = 0xFF3A4048.toInt()
        c.drawArc(RectF(240f, 760f, 500f, 1060f), 200f, 80f, false, p)
        p.style = Paint.Style.FILL
        // Il bidone dell'umido.
        p.color = 0xFF2F6E3B.toInt()
        c.drawRoundRect(RectF(80f, 980f, 280f, 1280f), 24f, 24f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 34f
        p.isFakeBoldText = true
        c.drawText("UMIDO", 118f, 1140f, p)
        val uscita = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, uscita)
        return uscita.toByteArray()
    }

    // --- Gli scenari pronti ----------------------------------------------------------------

    private val foto by lazy { fotoFinta() }

    /** Tutto in ordine: due figli, dati completi. */
    fun scenarioNormale(soloLuca: Boolean = false): Scenario = Scenario(
        famiglia = { corpo(Famiglia.serializer(), famiglia(soloLuca = soloLuca)) },
        genitori = { corpo(PaccoGenitori.serializer(), genitori()) },
        finestra = { id -> if (id == SARA) corpo(Finestra.serializer(), finestraSara()) else corpo(Finestra.serializer(), finestraLuca()) },
        notifiche = { corpo(PaccoNotifiche.serializer(), PaccoNotifiche(notifiche())) },
        proposte = { id ->
            if (id == SARA) {
                corpo(PaccoProposte.serializer(), PaccoProposte())
            } else {
                corpo(PaccoProposte.serializer(), PaccoProposte(listOf(propostaDiLuca(), propostaDiPapa()) + proposteChiuseLuca()))
            }
        },
        dichiarazioni = { id ->
            corpo(PaccoDichiarazioni.serializer(), PaccoDichiarazioni(if (id == SARA) emptyList() else dichiarazioniLuca()))
        },
        faccende = { id -> corpo(PaccoFaccende.serializer(), PaccoFaccende(if (id == SARA) emptyList() else faccendeLuca())) },
        cerca = { id, testo -> corpo(PaccoFaccende.serializer(), cercaNellaStoria(id, testo)) },
        foto = { ServerFinto.Risposta.Byte(foto) },
        sessioni = { id -> corpo(PaccoSessioni.serializer(), PaccoSessioni(if (id == SARA) emptyList() else sessioniLuca())) },
        versione = { corpo(InfoVersioni.serializer(), InfoVersioni(genitore = InfoApp(14, "0.14.0", "/scarica/pactum-genitore.apk"))) },
        scritture = { metodo, percorso ->
            when {
                metodo == "POST" && percorso == "/api/genitori" -> corpo(CodiceGenitore.serializer(), codiceGenitore(), 201)
                metodo == "POST" && Regex("/api/genitori/\\d+/codice").matches(percorso) ->
                    corpo(CodiceGenitore.serializer(), codiceGenitore())
                metodo == "POST" && Regex("/api/figli/\\d+/dispositivi").matches(percorso) ->
                    corpo(CodiceAbbinamento.serializer(), codiceDispositivo(), 201)
                metodo == "POST" && Regex("/api/dispositivi/\\d+/codice").matches(percorso) ->
                    corpo(CodiceAbbinamento.serializer(), codiceDispositivo())
                else -> ServerFinto.Risposta.Corpo("{}")
            }
        },
    )

    /** Niente da mostrare: Sara appena creata, nessuna notifica, nessuna proposta, nessun lavoro. */
    fun scenarioVuoto(): Scenario = scenarioNormale().copy(
        famiglia = { corpo(Famiglia.serializer(), famiglia(saraVuota = true)) },
        finestra = { id -> if (id == SARA) corpo(Finestra.serializer(), finestraVuota()) else corpo(Finestra.serializer(), finestraLuca(faccende = emptyList(), conProposte = false, conSessioni = false)) },
        notifiche = { corpo(PaccoNotifiche.serializer(), PaccoNotifiche(emptyList())) },
        proposte = { corpo(PaccoProposte.serializer(), PaccoProposte()) },
        dichiarazioni = { corpo(PaccoDichiarazioni.serializer(), PaccoDichiarazioni()) },
        faccende = { corpo(PaccoFaccende.serializer(), PaccoFaccende()) },
        sessioni = { corpo(PaccoSessioni.serializer(), PaccoSessioni()) },
    )

    /** La rete non c'è: ogni richiesta cade. */
    fun scenarioSenzaRete(): Scenario = Scenario()

    /** Il collegamento di questo telefono non vale più (un altro genitore l'ha tolto): 401 ovunque. */
    fun scenarioNonValido(): Scenario {
        val no = ServerFinto.Risposta.Errore(401, "{\"detail\": \"token non valido\"}")
        return Scenario(
            famiglia = { no }, genitori = { no }, finestra = { no }, notifiche = { no }, proposte = { no },
            dichiarazioni = { no }, faccende = { no }, foto = { no }, sessioni = { no }, versione = { no },
            scritture = { _, _ -> no },
        )
    }

    /** Un server 0.7: niente famiglia (404), un figlio e un telefono, niente lavori di casa né genitori. */
    fun scenarioServerVecchio(): Scenario {
        val nonCe = ServerFinto.Risposta.Errore(404)
        return Scenario(
            famiglia = { nonCe },
            genitori = { nonCe },
            finestra = { corpo(Finestra.serializer(), finestraServerVecchio()) },
            notifiche = { corpo(PaccoNotifiche.serializer(), PaccoNotifiche(notifiche().filter { it.tipo in setOf("sforamento", "manomissione", "dichiarazione") }.map { it.copy(figlioId = null, dispositivoId = null) })) },
            proposte = { corpo(PaccoProposte.serializer(), PaccoProposte(proposteChiuseLuca().filter { it.autore == "genitore" }.map { it.copy(genitore = null) })) },
            dichiarazioni = { corpo(PaccoDichiarazioni.serializer(), PaccoDichiarazioni(dichiarazioniLuca().map { it.copy(verdetto = it.verdetto?.copy(da = null)) })) },
            faccende = { nonCe },
            foto = { nonCe },
            sessioni = { nonCe },
            versione = { nonCe },
        )
    }
}
