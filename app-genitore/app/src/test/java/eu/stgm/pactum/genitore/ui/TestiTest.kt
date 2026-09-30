package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.RiferimentoDispositivo
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.UsoApp
import eu.stgm.pactum.genitore.dati.UsoGiorno
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Le frasi che il padre legge: durate e testi delle notifiche. Le parole sono
 * quelle vere di strings.xml (ParoleDiProva). Se sbagliano, il padre legge
 * "1 h 0 min", "Evento sforamento registrato" o una data ISO al posto di una
 * frase: gergo da tecnico dentro un'app che deve essere un testimone.
 */
class TestiTest {

    private val p = ParoleDiProva

    // --- le regole della finestra -------------------------------------------------

    private val tiktok = RegolaFinestra(
        id = 1,
        tipo = "limite_tempo",
        parametri = buildJsonObject {
            put("app_o_categoria", "com.zhiliaoapp.musically")
            put("minuti_al_giorno", 60)
        },
        nome = "TikTok",
    )
    private val social = RegolaFinestra(
        id = 2,
        tipo = "limite_tempo",
        parametri = buildJsonObject {
            put("app_o_categoria", "categoria:social")
            put("minuti_al_giorno", 120)
        },
    )
    private val sera = RegolaFinestra(
        id = 3,
        tipo = "fascia_oraria",
        parametri = buildJsonObject {
            put("dalle", "21:00")
            put("alle", "07:00")
            putJsonArray("giorni") {
                add("lun")
                add("mar")
            }
        },
    )
    private val camminare = RegolaFinestra(
        id = 4,
        tipo = "vita_reale",
        parametri = buildJsonObject {
            put("descrizione", "Camminare un'ora")
            put("arbitro_nome", "Nonna")
            put("frequenza", "ogni giorno")
        },
    )
    private val regole = listOf(tiktok, social, sera, camminare).associateBy { it.id }

    private fun notifica(tipo: String, payload: JsonObject, id: Long = 7) = Notifica(
        id = id,
        tipo = tipo,
        messaggio = "messaggio del server",
        payload = payload,
        tsServer = "2026-09-16T10:00:00+00:00",
    )

    private fun testo(notifica: Notifica) = testoNotifica(p, notifica, regole)

    // --- durate -------------------------------------------------------------------

    @Test
    fun `sotto l'ora solo minuti`() {
        assertEquals("45 min", testoDurata(p, 45))
        assertEquals("0 min", testoDurata(p, 0))
    }

    @Test
    fun `un'ora tonda e 1 h, mai 1 h 0 min`() {
        assertEquals("1 h", testoDurata(p, 60))
        assertEquals("2 h", testoDurata(p, 120))
    }

    @Test
    fun `ore e minuti restano come sono`() {
        assertEquals("2 h 30 min", testoDurata(p, 150))
        assertEquals("1 h 1 min", testoDurata(p, 61))
    }

    @Test
    fun `la regola dice al massimo 1 h al giorno`() {
        assertEquals("TikTok: al massimo 1 h al giorno", descrizioneRegola(p, tiktok))
        assertEquals("Social: al massimo 2 h al giorno", descrizioneRegola(p, social))
    }

    // --- notifiche: le frasi ---------------------------------------------------------

    @Test
    fun `nuova regola col nome dell'app, non il pacchetto`() {
        val t = testo(
            notifica(
                "modifica_regola",
                buildJsonObject {
                    put("regola_id", 1)
                    put("azione", "creazione")
                    putJsonObject("parametri") {
                        put("app_o_categoria", "com.zhiliaoapp.musically")
                        put("minuti_al_giorno", 60)
                    }
                },
            ),
        )
        assertEquals(TestoNotifica("Nuova regola", "TikTok: al massimo 1 h al giorno"), t)
    }

    @Test
    fun `una modifica concordata racconta i parametri nuovi`() {
        val t = testo(
            notifica(
                "modifica_regola",
                buildJsonObject {
                    put("regola_id", 1)
                    put("azione", "modifica")
                    put("direzione", "stringe")
                    put("concordata", true)
                    putJsonObject("prima") {
                        put("app_o_categoria", "com.zhiliaoapp.musically")
                        put("minuti_al_giorno", 60)
                    }
                    putJsonObject("dopo") {
                        put("app_o_categoria", "com.zhiliaoapp.musically")
                        put("minuti_al_giorno", 45)
                    }
                },
            ),
        )
        assertEquals(
            TestoNotifica("Regola stretta · concordata", "TikTok: al massimo 45 min al giorno"),
            t,
        )
    }

    @Test
    fun `una regola eliminata si racconta com'era`() {
        val t = testo(
            notifica(
                "modifica_regola",
                buildJsonObject {
                    put("regola_id", 4)
                    put("azione", "eliminazione")
                    put("concordata", false)
                    put("prima", camminare.parametri)
                },
            ),
        )
        assertEquals(
            TestoNotifica("Regola eliminata", "Camminare un'ora — arbitro: Nonna, ogni giorno"),
            t,
        )
    }

    @Test
    fun `fuori regola dice quale regola`() {
        val t = testo(
            notifica(
                "sforamento",
                buildJsonObject {
                    put("evento_id", "550e8400")
                    putJsonObject("dettagli") { put("regola_id", 1) }
                },
            ),
        )
        assertEquals(TestoNotifica("Fuori regola", "TikTok: al massimo 1 h al giorno"), t)
    }

    @Test
    fun `un'interruzione nella registrazione si dice per quello che e`() {
        fun buco(sottoTipo: String) = testo(
            notifica(
                "manomissione",
                buildJsonObject {
                    put("evento_id", "x")
                    putJsonObject("dettagli") { put("sotto_tipo", sottoTipo) }
                },
            ),
        )
        assertEquals(
            TestoNotifica("Anomalia", "Uso non registrato in questo periodo"),
            buco("silenzio"),
        )
        assertEquals("Cambio manuale dell'ora", buco("cambio_ora").testo)
    }

    @Test
    fun `ogni sotto-tipo che puo arrivare ha la sua frase, mai la chiave grezza`() {
        // Quelli che manda l'app del figlio (SottoTipiManomissione, orologio,
        // worker) e `silenzio` del contratto.
        val attese = mapOf(
            "cambio_ora" to "Cambio manuale dell'ora",
            "cambio_fuso" to "Cambio di fuso orario del telefono",
            "silenzio" to "Uso non registrato in questo periodo",
            "permesso_revocato" to "Accesso ai dati di utilizzo revocato",
            "notifiche_disattivate" to "Notifiche di Pactum disattivate sul telefono del figlio",
            "osservazione_siti_interrotta" to "Osservazione dei siti spenta",
        )
        attese.forEach { (sottoTipo, frase) ->
            assertEquals(sottoTipo, frase, descrizioneBuco(p, sottoTipo))
        }
    }

    @Test
    fun `un sotto-tipo nuovo ripiega su Anomalia`() {
        assertEquals("Anomalia: batteria_strana", descrizioneBuco(p, "batteria_strana"))
    }

    @Test
    fun `l'osservazione dei siti spenta arriva al padre come frase anche in notifica`() {
        val t = testo(
            notifica(
                "manomissione",
                buildJsonObject {
                    put("evento_id", "x")
                    putJsonObject("dettagli") { put("sotto_tipo", "osservazione_siti_interrotta") }
                },
            ),
        )
        assertEquals(TestoNotifica("Anomalia", "Osservazione dei siti spenta"), t)
    }

    // --- i rifiuti del server (409) --------------------------------------------------

    @Test
    fun `ogni rifiuto di una proposta dice il motivo vero`() {
        assertEquals(
            "C'è già una proposta in attesa su questa regola.",
            p.testo(messaggioRifiutoProposta("proposta_gia_pendente")),
        )
        assertEquals(
            "Questa regola non è più attiva.",
            p.testo(messaggioRifiutoProposta("regola_non_valida")),
        )
        assertEquals(
            "Qualche valore non va bene: controlla i campi e riprova.",
            p.testo(messaggioRifiutoProposta("parametri_non_validi")),
        )
        assertEquals(
            "Non sono riuscito a inviare la proposta: riprova.",
            p.testo(messaggioRifiutoProposta(null)),
        )
    }

    @Test
    fun `solo i rifiuti che riprovare non risolve chiudono il dialogo`() {
        assertTrue(rifiutoPropostaDefinitivo("proposta_gia_pendente"))
        assertTrue(rifiutoPropostaDefinitivo("regola_non_valida"))
        assertFalse(rifiutoPropostaDefinitivo("parametri_non_validi"))
        assertFalse(rifiutoPropostaDefinitivo(null))
    }

    @Test
    fun `una conferma arrivata tardi dice che qualcuno ha gia risposto`() {
        assertEquals(
            "Qualcuno ha già risposto a questa dichiarazione.",
            p.testo(messaggioRifiutoVerdetto("dichiarazione_non_in_attesa")),
        )
        assertEquals(
            "Non sono riuscito a registrare la risposta: riprova.",
            p.testo(messaggioRifiutoVerdetto(null)),
        )
    }

    // --- terminologia ------------------------------------------------------------------

    @Test
    fun `le parole scartate da Andrea non tornano in strings xml`() {
        val tutto = File("src/main/res/values/strings.xml").readText().replace("\\'", "'")
        listOf(
            "buchi nel registro",
            "buco nel registro",
            "Il tuo turno",
            "Come è cambiato il patto",
            "Dati fermi",
            "l'app non ha potuto vedere",
        ).forEach {
            assertFalse("'$it' è tornato in strings.xml", tutto.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `la dichiarazione dice cosa e il giorno all'italiana`() {
        fun dichiarazione(esito: String) = testo(
            notifica(
                "dichiarazione",
                buildJsonObject {
                    put("dichiarazione_id", 5)
                    put("regola_id", 4)
                    put("esito", esito)
                    put("giorno", "2026-09-16")
                },
            ),
        )
        assertEquals(
            TestoNotifica("Dichiarazione del figlio", "Dice di aver fatto: Camminare un'ora (16/09)"),
            dichiarazione("successo"),
        )
        assertEquals(
            "Dice di non aver fatto: Camminare un'ora (16/09)",
            dichiarazione("fallimento").testo,
        )
    }

    @Test
    fun `la risposta a una proposta dice su quale regola`() {
        fun risposta(regolaId: Int, esito: String) = testo(
            notifica(
                "proposta_risposta",
                buildJsonObject {
                    put("proposta_id", 9)
                    put("regola_id", regolaId)
                    put("esito", esito)
                },
            ),
        )
        assertEquals(
            TestoNotifica("Risposta a una proposta", "Ha accettato la tua proposta su TikTok"),
            risposta(1, "accetta"),
        )
        assertEquals(
            "Ha rifiutato la tua proposta sulla fascia 21:00–07:00",
            risposta(3, "rifiuta").testo,
        )
    }

    @Test
    fun `una proposta annullata dice perche`() {
        val t = testo(
            notifica(
                "proposta_annullata",
                buildJsonObject {
                    put("proposta_id", 9)
                    put("regola_id", 4)
                    put("motivo", "regola_eliminata")
                },
            ),
        )
        assertEquals(
            TestoNotifica(
                "Proposta annullata",
                "La tua proposta su «Camminare un'ora» non vale più: la regola non è più attiva",
            ),
            t,
        )
    }

    @Test
    fun `un bonus senza motivo non scrive null`() {
        fun bonus(motivo: String?) = testo(
            notifica(
                "bonus",
                buildJsonObject {
                    put("minuti", 15)
                    put("regola_id", 1)
                    if (motivo == null) put("motivo", JsonNull) else put("motivo", motivo)
                    put("residuo_giorno", 15)
                    put("residuo_settimana", 75)
                },
            ),
        )
        assertEquals(TestoNotifica("Bonus", "Si è dato 15 min in più su TikTok"), bonus(null))
        assertEquals(
            "Si è dato 15 min in più su TikTok. Motivo: finisco il video",
            bonus("finisco il video").testo,
        )
    }

    // --- notifiche: quando l'app non sa scrivere la frase ---------------------------

    @Test
    fun `tipo sconosciuto, il messaggio del server`() {
        assertEquals(
            TestoNotifica("Novità dal patto", "messaggio del server"),
            testo(notifica("tipo_del_futuro", buildJsonObject { })),
        )
    }

    @Test
    fun `payload che non basta, il messaggio del server col titolo del tipo`() {
        // Regola che la finestra non conosce.
        assertEquals(
            TestoNotifica("Fuori regola", "messaggio del server"),
            testo(
                notifica(
                    "sforamento",
                    buildJsonObject { putJsonObject("dettagli") { put("regola_id", 99) } },
                ),
            ),
        )
        // Dichiarazione senza giorno.
        assertEquals(
            TestoNotifica("Dichiarazione del figlio", "messaggio del server"),
            testo(
                notifica(
                    "dichiarazione",
                    buildJsonObject {
                        put("regola_id", 4)
                        put("esito", "successo")
                    },
                ),
            ),
        )
        // Buco senza sotto_tipo, modifica con un'azione che non si conosce.
        assertEquals("messaggio del server", testo(notifica("manomissione", buildJsonObject { })).testo)
        assertEquals(
            "messaggio del server",
            testo(
                notifica(
                    "modifica_regola",
                    buildJsonObject {
                        put("regola_id", 1)
                        put("azione", "fusione")
                    },
                ),
            ).testo,
        )
    }

    @Test
    fun `senza regole (finestra non arrivata) nessun crash, solo il ripiego`() {
        val t = testoNotifica(
            p,
            notifica("proposta_risposta", buildJsonObject {
                put("regola_id", 1)
                put("esito", "accetta")
            }),
            emptyMap(),
        )
        assertEquals(TestoNotifica("Risposta a una proposta", "messaggio del server"), t)
    }

    @Test
    fun `nessuna frase costruita contiene date ISO, chiavi grezze o null`() {
        val costruite = listOf(
            notifica("dichiarazione", buildJsonObject {
                put("regola_id", 4)
                put("esito", "successo")
                put("giorno", "2026-09-16")
            }),
            notifica("modifica_regola", buildJsonObject {
                put("regola_id", 2)
                put("azione", "creazione")
                put("parametri", social.parametri)
            }),
            notifica("bonus", buildJsonObject {
                put("minuti", 30)
                put("regola_id", 2)
                put("motivo", JsonNull)
            }),
        ).map(::testo)
        costruite.forEach { t ->
            val tutto = t.titolo + " " + t.testo
            listOf("2026-", "categoria:", "limite_tempo", "null", "successo").forEach {
                assertFalse("'$it' in «$tutto»", tutto.contains(it))
            }
        }
    }

    // --- notifiche: a quale regola si riferiscono, e in che ordine -------------------

    @Test
    fun `la regola di uno sforamento sta nei dettagli, quella di un bonus in cima`() {
        assertEquals(
            1L,
            regolaIdNotifica(
                notifica("sforamento", buildJsonObject { putJsonObject("dettagli") { put("regola_id", 1) } }),
            ),
        )
        assertEquals(
            2L,
            regolaIdNotifica(notifica("bonus", buildJsonObject { put("regola_id", 2) })),
        )
        assertNull(regolaIdNotifica(notifica("segno", buildJsonObject { })))
    }

    @Test
    fun `la lista va dalla piu recente alla piu vecchia`() {
        val lista = listOf(3L, 1L, 2L).map { notifica("bonus", buildJsonObject { }, id = it) }
        assertEquals(listOf(3L, 2L, 1L), dallaPiuRecente(lista).map { it.id })
    }

    // --- v3: i nomi dei programmi e dei siti --------------------------------------------

    @Test
    fun `un sito e un programma senza nome si leggono, mai col prefisso`() {
        assertEquals("youtube.com (sito)", etichettaAppOCategoria("sito:youtube.com"))
        assertEquals("minecraft.exe (programma)", etichettaAppOCategoria("exe:minecraft.exe"))
        assertEquals("Social", etichettaAppOCategoria("categoria:social"))
        assertEquals("com.whatsapp", etichettaAppOCategoria("com.whatsapp"))
        // Una chiave monca resta com'è: meglio onesta che inventata.
        assertEquals("sito:", etichettaAppOCategoria("sito:"))
    }

    @Test
    fun `il nome leggibile vince, ma solo se e un nome`() {
        assertEquals("Minecraft", nomeLeggibile("exe:minecraft.exe", "Minecraft"))
        // Il ripiego del server (il nome = la chiave) non è un nome.
        assertEquals("minecraft.exe (programma)", nomeLeggibile("exe:minecraft.exe", "exe:minecraft.exe"))
        assertEquals("youtube.com (sito)", nomeLeggibile("sito:youtube.com", null))
        assertEquals("youtube.com (sito)", nomeLeggibile("sito:youtube.com", "  "))
        assertEquals("com.zhiliaoapp.musically", nomeLeggibile("com.zhiliaoapp.musically", "com.zhiliaoapp.musically"))
        assertEquals("TikTok", nomeLeggibile("com.zhiliaoapp.musically", "TikTok"))
    }

    private fun regolaComputer(chiave: String, minuti: Int, nome: String? = null) = RegolaFinestra(
        id = 20,
        tipo = "limite_tempo",
        parametri = buildJsonObject {
            put("app_o_categoria", chiave)
            put("minuti_al_giorno", minuti)
        },
        nome = nome,
        dispositivoId = 2,
        dispositivo = RiferimentoDispositivo(2, "Computer di camera", "computer"),
    )

    @Test
    fun `le regole del computer si leggono come quelle del telefono`() {
        assertEquals("Minecraft: al massimo 1 h al giorno", descrizioneRegola(p, regolaComputer("exe:minecraft.exe", 60, "Minecraft")))
        assertEquals(
            "youtube.com (sito): al massimo 30 min al giorno",
            descrizioneRegola(p, regolaComputer("sito:youtube.com", 30)),
        )
        assertEquals(
            "minecraft.exe (programma): al massimo 1 h 30 min al giorno",
            descrizioneRegola(p, regolaComputer("exe:minecraft.exe", 90, "exe:minecraft.exe")),
        )
    }

    @Test
    fun `una fascia oraria del computer dice niente computer`() {
        val fasciaComputer = sera.copy(dispositivo = RiferimentoDispositivo(2, "PC", "computer"))
        assertEquals("Niente computer dalle 21:00 alle 07:00 (lun, mar)", descrizioneRegola(p, fasciaComputer))
        assertEquals("Niente telefono dalle 21:00 alle 07:00 (lun, mar)", descrizioneRegola(p, sera))
    }

    @Test
    fun `una notifica su una regola del computer non mostra mai exe o sito`() {
        val regoleComputer = mapOf(20L to regolaComputer("sito:youtube.com", 30))
        val t = testoNotifica(
            p,
            notifica("sforamento", buildJsonObject { putJsonObject("dettagli") { put("regola_id", 20) } }),
            regoleComputer,
        )
        assertEquals(TestoNotifica("Fuori regola", "youtube.com (sito): al massimo 30 min al giorno"), t)
        assertFalse(t.testo.contains("sito:"))
    }

    // --- v3: le interruzioni del computer -----------------------------------------------

    private val roma: ZoneId = ZoneId.of("Europe/Rome")
    private val oggi24: LocalDate = LocalDate.of(2026, 9, 24)

    /** Un istante del 2026 a Roma, in millisecondi (come `dal`/`al` del computer). */
    private fun ms(giorno: Int, ora: Int, minuto: Int): Long =
        LocalDate.of(2026, 9, giorno).atTime(ora, minuto).atZone(roma).toInstant().toEpochMilli()

    private fun chiuso(dal: Long?, al: Long?, volontario: Boolean? = null) = buildJsonObject {
        put("sotto_tipo", "programma_chiuso")
        if (dal != null) put("dal", dal)
        if (al != null) put("al", al)
        if (volontario != null) put("volontario", volontario)
    }

    @Test
    fun `Pactum chiuso sul computer dice da quando a quando`() {
        assertEquals(
            "Pactum è stato chiuso sul computer (dalle 15:10 alle 15:40)",
            descrizioneBuco(p, chiuso(ms(24, 15, 10), ms(24, 15, 40)), roma, oggi24),
        )
        assertEquals(
            "Pactum è stato chiuso sul computer (il 22/09 dalle 15:10 alle 15:40)",
            descrizioneBuco(p, chiuso(ms(22, 15, 10), ms(22, 15, 40)), roma, oggi24),
        )
        assertEquals(
            "Pactum è stato chiuso sul computer (dal 23/09 alle 22:10 al 24/09 alle 07:30)",
            descrizioneBuco(p, chiuso(ms(23, 22, 10), ms(24, 7, 30)), roma, oggi24),
        )
    }

    @Test
    fun `senza orari validi non si inventa un intervallo`() {
        assertEquals(
            "Pactum è stato chiuso sul computer con «Chiudi Pactum»",
            descrizioneBuco(p, chiuso(null, null, volontario = true), roma, oggi24),
        )
        assertEquals(
            "Pactum è stato chiuso sul computer",
            descrizioneBuco(p, chiuso(null, null), roma, oggi24),
        )
        // La fine prima dell'inizio: intervallo storto, non si racconta.
        assertEquals(
            "Pactum è stato chiuso sul computer",
            descrizioneBuco(p, chiuso(ms(24, 15, 40), ms(24, 15, 10)), roma, oggi24),
        )
        assertEquals("Pactum è stato chiuso sul computer", descrizioneBuco(p, "programma_chiuso"))
    }

    @Test
    fun `siti non leggibili sul computer, e i vecchi sotto-tipi restano come prima`() {
        assertEquals(
            "Sul computer il programma non è riuscito a leggere i siti per un periodo",
            descrizioneBuco(p, buildJsonObject { put("sotto_tipo", "siti_non_leggibili") }, roma, oggi24),
        )
        assertEquals(
            "Uso non registrato in questo periodo",
            descrizioneBuco(p, buildJsonObject { put("sotto_tipo", "silenzio") }, roma, oggi24),
        )
        val t = testo(
            notifica(
                "manomissione",
                buildJsonObject {
                    put("evento_id", "x")
                    putJsonObject("dettagli") { put("sotto_tipo", "siti_non_leggibili") }
                },
            ),
        )
        assertEquals("Anomalia", t.titolo)
        assertEquals("Sul computer il programma non è riuscito a leggere i siti per un periodo", t.testo)
    }

    @Test
    fun `computer spento e riacceso - frasi chiare, e non sono interruzioni`() {
        fun evento(tipo: String, motivo: String?) = testo(
            notifica(
                tipo,
                buildJsonObject {
                    put("evento_id", "x")
                    putJsonObject("dettagli") { if (motivo != null) put("motivo", motivo) }
                },
            ),
        )
        assertEquals(
            TestoNotifica(
                "Computer spento",
                "Il computer è stato spento. Non è un'interruzione nella registrazione.",
            ),
            evento("sospensione", "spegnimento"),
        )
        assertEquals("Computer in sospensione", evento("sospensione", "sospensione").titolo)
        assertEquals("Uscita dall'account", evento("sospensione", "disconnessione").titolo)
        assertEquals("Computer spento", evento("sospensione", null).titolo)
        assertEquals(
            TestoNotifica(
                "Computer acceso",
                "Il computer è stato acceso e Pactum ha ripreso a registrare.",
            ),
            evento("ripresa", "avvio"),
        )
        assertEquals("Computer riattivato", evento("ripresa", "riattivazione").titolo)
        assertEquals("Accesso all'account", evento("ripresa", "accesso").titolo)
    }

    // --- v3: lo stato di un dispositivo ----------------------------------------------

    private fun ts(giorno: Int, ora: Int, minuto: Int): String =
        LocalDate.of(2026, 9, giorno).atTime(ora, minuto).atZone(roma).toOffsetDateTime().toString()

    private fun istante(giorno: Int, ora: Int, minuto: Int): Instant =
        LocalDate.of(2026, 9, giorno).atTime(ora, minuto).atZone(roma).toInstant()

    // "Adesso" nei test: il 24/09 alle 16:00, ora di Roma (mai l'orologio vero).
    private val adesso24 = istante(24, 16, 0)

    @Test
    fun `lo stato del dispositivo a parole, oggi e un altro giorno`() {
        val contatto = StatoSilenzio(ultimoBattito = ts(24, 15, 10), silente = false)
        assertEquals(
            "In contatto — ultimo aggiornamento alle 15:10",
            testoStatoCanale(p, StatoCanale.IN_CONTATTO, contatto, roma, oggi24, adesso24),
        )
        val spento = StatoSilenzio(ultimoBattito = ts(23, 23, 0), silente = false, spento = true, spentoDal = ts(23, 23, 10))
        assertEquals("Spento dal 23/09 alle 23:10", testoStatoCanale(p, StatoCanale.SPENTO, spento, roma, oggi24, adesso24))
        val spentoOggi = spento.copy(spentoDal = ts(24, 0, 5))
        assertEquals("Spento dalle 00:05", testoStatoCanale(p, StatoCanale.SPENTO, spentoOggi, roma, oggi24, adesso24))
        val muto = StatoSilenzio(ultimoBattito = ts(24, 9, 0), silente = true)
        assertEquals("Nessun aggiornamento dalle 09:00", testoStatoCanale(p, StatoCanale.SILENTE, muto, roma, oggi24, adesso24))
        assertEquals(
            "Da collegare: il codice si crea nelle Impostazioni, sezione Famiglia",
            testoStatoCanale(p, StatoCanale.DA_COLLEGARE, null, roma, oggi24, adesso24),
        )
        assertEquals(
            "Scollegato: non manda più dati. La sua storia resta.",
            testoStatoCanale(p, StatoCanale.SCOLLEGATO, null, roma, oggi24, adesso24),
        )
        // Spento senza orario: la frase senza orario, mai un orario inventato
        // (nemmeno quello dell'ultimo battito, che non è l'ora dello spegnimento).
        assertEquals("Spento", testoStatoCanale(p, StatoCanale.SPENTO, spento.copy(spentoDal = null), roma, oggi24, adesso24))
        assertEquals(
            "Spento",
            testoStatoCanale(p, StatoCanale.SPENTO, spento.copy(spentoDal = null, ultimoBattito = null), roma, oggi24, adesso24),
        )
    }

    @Test
    fun `un computer spento da piu di 24 ore non si dice spento`() {
        // Spento il 23/09 alle 15:00: alle 16:00 del 24 sono 25 ore.
        val daIeri = StatoSilenzio(ultimoBattito = ts(23, 14, 55), silente = false, spento = true, spentoDal = ts(23, 15, 0))
        assertEquals(
            "Nessun dato dal computer dal 23/09 alle 15:00: spento, oppure Pactum non è partito",
            testoStatoCanale(p, StatoCanale.SPENTO, daIeri, roma, oggi24, adesso24),
        )
        // 23 ore: è ancora un computer spento.
        val daIeriSera = daIeri.copy(spentoDal = ts(23, 17, 0))
        assertEquals("Spento dal 23/09 alle 17:00", testoStatoCanale(p, StatoCanale.SPENTO, daIeriSera, roma, oggi24, adesso24))
        // Esattamente 24 ore: ancora spento; un minuto dopo, no.
        val esatte = daIeri.copy(spentoDal = ts(23, 16, 0))
        assertEquals("Spento dal 23/09 alle 16:00", testoStatoCanale(p, StatoCanale.SPENTO, esatte, roma, oggi24, adesso24))
        assertEquals(
            "Nessun dato dal computer dal 23/09 alle 16:00: spento, oppure Pactum non è partito",
            testoStatoCanale(p, StatoCanale.SPENTO, esatte, roma, oggi24, adesso24.plusSeconds(60)),
        )
        // Senza l'ora dello spegnimento conta l'ultimo battito.
        val senzaOra = daIeri.copy(spentoDal = null, ultimoBattito = ts(21, 22, 10))
        assertEquals(
            "Nessun dato dal computer dal 21/09 alle 22:10: spento, oppure Pactum non è partito",
            testoStatoCanale(p, StatoCanale.SPENTO, senzaOra, roma, oggi24, adesso24),
        )
    }

    @Test
    fun `l'avviso dopo un silenzio non dice spento se lo e da piu di 24 ore`() {
        val daTreGiorni = StatoSilenzio(ultimoBattito = ts(21, 22, 0), silente = false, spento = true, spentoDal = ts(21, 22, 10))
        assertEquals(
            TestoNotifica(
                "Nessun dato dal computer",
                "Il computer non manda dati dal 21/09 alle 22:10: è spento, oppure Pactum non è partito.",
            ),
            testoAvvisoSilenzio(
                p,
                CambioSilenzio.SPENTO_DOPO_SILENZIO,
                computer = true,
                silenzio = daTreGiorni,
                zona = roma,
                oggi = oggi24,
                adesso = adesso24,
            ),
        )
    }

    @Test
    fun `l'avviso di silenzio per dispositivo dice il tipo e da quando`() {
        val muto = StatoSilenzio(ultimoBattito = ts(24, 15, 10), silente = true)
        assertEquals(
            TestoNotifica("Nessun aggiornamento", "Il computer non invia aggiornamenti dalle 15:10."),
            testoAvvisoSilenzio(p, CambioSilenzio.NUOVO_SILENZIO, computer = true, silenzio = muto, zona = roma, oggi = oggi24),
        )
        assertEquals(
            "Il telefono non invia aggiornamenti dal 23/09 alle 15:10.",
            testoAvvisoSilenzio(
                p,
                CambioSilenzio.NUOVO_SILENZIO,
                computer = false,
                silenzio = muto.copy(ultimoBattito = ts(23, 15, 10)),
                zona = roma,
                oggi = oggi24,
            )?.testo,
        )
        assertEquals(
            TestoNotifica("Aggiornamenti ripresi", "Il telefono ha ripreso a inviare aggiornamenti alle 15:10. Tutto a posto."),
            testoAvvisoSilenzio(p, CambioSilenzio.CONTATTO_TORNATO, computer = false, silenzio = muto.copy(silente = false), zona = roma, oggi = oggi24),
        )
        val spento = StatoSilenzio(ultimoBattito = ts(24, 15, 0), silente = false, spento = true, spentoDal = ts(24, 15, 10))
        assertEquals(
            TestoNotifica(
                "Computer spento",
                "Il computer risulta spento dalle 15:10: non è un'interruzione nella registrazione.",
            ),
            testoAvvisoSilenzio(
                p,
                CambioSilenzio.SPENTO_DOPO_SILENZIO,
                computer = true,
                silenzio = spento,
                zona = roma,
                oggi = oggi24,
                adesso = adesso24,
            ),
        )
        assertNull(testoAvvisoSilenzio(p, CambioSilenzio.BASE, computer = true, silenzio = muto))
        assertNull(testoAvvisoSilenzio(p, CambioSilenzio.NESSUNO, computer = false, silenzio = muto))
    }

    @Test
    fun `aggiungere un telefono che c'e gia - la riga dice dove ricollegarlo`() {
        val telefono = Dispositivo(id = 1, nome = "Telefono", tipo = "telefono")
        assertEquals(
            "Andrea ha già «Telefono». Se è lo stesso telefono da ricollegare, usa «Nuovo codice» sulla sua riga: " +
                "così regole e storia restano insieme.",
            avvisoDispositivoGiaPresente(p, "Andrea", "telefono", listOf(telefono)),
        )
        val pc = Dispositivo(id = 2, nome = "", tipo = "computer")
        assertEquals(
            "Luca ha già «Computer». Se è lo stesso computer da ricollegare, usa «Nuovo codice» sulla sua riga: " +
                "così regole e storia restano insieme.",
            avvisoDispositivoGiaPresente(p, "Luca", "computer", listOf(pc)),
        )
        // Nessuno di quel tipo: niente riga.
        assertNull(avvisoDispositivoGiaPresente(p, "Andrea", "computer", emptyList()))
    }

    @Test
    fun `un elenco di nomi si legge in italiano`() {
        assertEquals("", elencoTraVirgolette(p, emptyList()))
        assertEquals("«Telefono»", elencoTraVirgolette(p, listOf("Telefono")))
        assertEquals("«Telefono» e «Vecchio»", elencoTraVirgolette(p, listOf("Telefono", "Vecchio")))
        assertEquals("«A», «B» e «C»", elencoTraVirgolette(p, listOf("A", "B", "C")))
    }

    @Test
    fun `un dispositivo senza nome si chiama col suo tipo`() {
        assertEquals("Computer", nomeDelDispositivo(p, null, "computer"))
        assertEquals("Telefono", nomeDelDispositivo(p, "  ", "telefono"))
        assertEquals("Dispositivo", nomeDelDispositivo(p, null, "tablet"))
        assertEquals("Computer di camera", nomeDelDispositivo(p, "Computer di camera", "computer"))
    }

    // --- v3: i rifiuti sui gesti della famiglia -----------------------------------------

    @Test
    fun `ogni rifiuto sulla famiglia dice il motivo vero`() {
        assertEquals(
            "Troppi tentativi: riprova tra 9 min.",
            messaggioRifiutoFamiglia(p, "troppi_tentativi", 540),
        )
        assertEquals(
            "Troppi tentativi: riprova tra 2 min.",
            messaggioRifiutoFamiglia(p, "troppi_tentativi", 61),
        )
        assertEquals(
            "Troppi tentativi: riprova tra qualche minuto.",
            messaggioRifiutoFamiglia(p, "troppi_tentativi", null),
        )
        assertEquals(
            "Non trovo più questo figlio o questo dispositivo: ho riletto la famiglia.",
            messaggioRifiutoFamiglia(p, "non_trovato", null),
        )
        assertEquals(
            "Questo dispositivo è scollegato: per usarlo di nuovo aggiungilo come dispositivo nuovo.",
            messaggioRifiutoFamiglia(p, "dispositivo_revocato", null),
        )
        assertEquals(
            "Il nome deve avere da 1 a 40 caratteri.",
            messaggioRifiutoFamiglia(p, "parametri_non_validi", null),
        )
        assertEquals(
            "Il codice non è valido o è scaduto: creane uno nuovo.",
            messaggioRifiutoFamiglia(p, "codice_non_valido", null),
        )
        assertEquals("Non riesco a raggiungere il server: riprova.", messaggioRifiutoFamiglia(p, null, null))
        // Una creazione senza risposta e la famiglia non riletta: prima di riprovare, guardare.
        assertEquals(
            "Il server non ha risposto e non so se la richiesta è arrivata: prima di riprovare, guarda se nella lista c'è già.",
            messaggioRifiutoFamiglia(p, CodiciErrore.ESITO_INCERTO, null),
        )
        // Un codice che non si conosce: si dice che il server non ha accettato, senza inventare un perché.
        assertEquals(
            "Il server non ha accettato la richiesta: aggiorna e riprova.",
            messaggioRifiutoFamiglia(p, "motivo_del_futuro", null),
        )
    }

    @Test
    fun `l'attesa di un 429 arrotonda per eccesso, mai zero minuti`() {
        assertEquals(1L, attesaInMinuti(1))
        assertEquals(1L, attesaInMinuti(60))
        assertEquals(2L, attesaInMinuti(61))
        assertEquals(10L, attesaInMinuti(600))
        assertEquals(1L, attesaInMinuti(0))
    }

    // --- v3: il digest di un figlio ------------------------------------------------------

    private val adesso: Instant = Instant.parse("2026-09-24T19:00:00Z")
    private val fresco = "2026-09-24T18:50:00+00:00"

    private fun usoDiOggi(totale: Int?, vararg app: UsoApp) = listOf(
        UsoGiorno(giorno = "2026-09-24", totaleMinuti = totale, aggiornatoTs = fresco, app = app.toList()),
    )

    @Test
    fun `un telefono solo - il digest della 0,7`() {
        val telefonoSolo = VistaDispositivo(
            id = 1,
            nome = "Telefono",
            usoRecente = usoDiOggi(200, UsoApp("tiktok", "TikTok", minuti = 60, limite = 60), UsoApp("yt", "YouTube", minuti = 40)),
        )
        assertEquals(
            TestoNotifica("Oggi: 3 h 20 min", "TikTok 1 h (limite 1 h) · YouTube 40 min — tocca per il dettaglio"),
            testoDigest(p, listOf(telefonoSolo), adesso),
        )
        // Server 0.7: senza id né nome, stesse parole.
        assertEquals("Oggi: 3 h 20 min", testoDigest(p, listOf(telefonoSolo.copy(id = null, nome = null)), adesso).titolo)
    }

    @Test
    fun `piu dispositivi - una riga ciascuno, mai uno zero finto`() {
        val telefono = VistaDispositivo(
            id = 1,
            nome = "Telefono",
            usoRecente = usoDiOggi(200, UsoApp("tiktok", "TikTok", minuti = 60, limite = 60)),
        )
        val computer = VistaDispositivo(
            id = 2,
            nome = "Computer di camera",
            tipo = "computer",
            usoRecente = usoDiOggi(null),
        )
        val t = testoDigest(p, listOf(telefono, computer), adesso)
        assertEquals("Oggi: Telefono 3 h 20 min · Computer di camera nessun dato", t.titolo)
        assertEquals(
            "Telefono: TikTok 1 h (limite 1 h)\nComputer di camera: nessun dato ricevuto oggi\nTocca per il dettaglio.",
            t.testo,
        )
    }

    @Test
    fun `nel digest i programmi col nome leggibile, e niente dispositivi scollegati o da collegare`() {
        val computer = VistaDispositivo(
            id = 2,
            nome = "PC",
            tipo = "computer",
            usoRecente = usoDiOggi(90, UsoApp("exe:minecraft.exe", "Minecraft", minuti = 70), UsoApp("exe:foo.exe", null, minuti = 20)),
        )
        val vecchio = VistaDispositivo(id = 3, nome = "Vecchio", revocato = true, usoRecente = usoDiOggi(500))
        val nuovo = VistaDispositivo(id = 4, nome = "Nuovo", abbinato = false)
        val t = testoDigest(p, listOf(computer, vecchio, nuovo), adesso)
        assertEquals("Oggi: PC 1 h 30 min", t.titolo)
        assertEquals("PC: Minecraft 1 h 10 min · foo.exe (programma) 20 min\nTocca per il dettaglio.", t.testo)
    }

    @Test
    fun `una fotografia ferma da ore nel digest lo dice`() {
        val telefono = VistaDispositivo(
            id = 1,
            nome = "Telefono",
            usoRecente = listOf(
                UsoGiorno(giorno = "2026-09-24", totaleMinuti = 30, aggiornatoTs = "2026-09-24T12:00:00+00:00"),
            ),
        )
        val computer = VistaDispositivo(id = 2, nome = "PC", tipo = "computer", usoRecente = usoDiOggi(10))
        val t = testoDigest(p, listOf(telefono, computer), adesso)
        assertTrue(t.testo, t.testo.contains("Telefono: ultimo aggiornamento alle"))
        assertFalse(t.testo, t.testo.contains("PC: ultimo aggiornamento"))
    }

    // --- v3.3: il limite su tutto il dispositivo ------------------------------------------

    /** Una regola sul totale ("totale" nel contratto), del dispositivo di tipo [tipo]. */
    private fun totale(minuti: Int, tipo: String?, id: Long = 30, nome: String? = null) = RegolaFinestra(
        id = id,
        tipo = "limite_tempo",
        parametri = buildJsonObject {
            put("app_o_categoria", "totale")
            put("minuti_al_giorno", minuti)
        },
        nome = nome,
        dispositivoId = if (tipo == null) null else 1,
        dispositivo = tipo?.let { RiferimentoDispositivo(1, "Telefono di Andrea", it) },
    )

    @Test
    fun `una regola sul totale si scrive Tutto il telefono o Tutto il computer`() {
        assertEquals("Tutto il telefono: al massimo 3 h al giorno", descrizioneRegola(p, totale(180, "telefono")))
        assertEquals(
            "Tutto il computer: al massimo 2 h 30 min al giorno",
            descrizioneRegola(p, totale(150, "computer")),
        )
        // Senza il dispositivo (un server che non conosce i computer) è il telefono.
        assertEquals("Tutto il telefono: al massimo 3 h al giorno", descrizioneRegola(p, totale(180, null)))
        assertEquals("Tutto il telefono", bersaglioRegola(p, totale(180, null)))
    }

    @Test
    fun `senza dispositivo nella regola anche la frase col su dice il telefono`() {
        val t = testoNotifica(
            p,
            notifica(
                "bonus",
                buildJsonObject {
                    put("minuti", 5)
                    put("regola_id", 30)
                },
            ),
            mapOf(30L to totale(180, null)),
        )
        assertEquals("Si è dato 5 min in più su tutto il telefono", t.testo)
    }

    @Test
    fun `un nome del server su una regola sul totale non prende il posto delle parole giuste`() {
        assertEquals(
            "Tutto il telefono: al massimo 3 h al giorno",
            descrizioneRegola(p, totale(180, "telefono", nome = "totale")),
        )
    }

    @Test
    fun `nelle notifiche il totale si legge come le altre regole, mai con la chiave`() {
        val regoleTotale = mapOf(30L to totale(180, "telefono"), 31L to totale(120, "computer", id = 31))
        fun frase(tipo: String, payload: JsonObject) = testoNotifica(p, notifica(tipo, payload), regoleTotale)

        val sforamento = frase("sforamento", buildJsonObject { putJsonObject("dettagli") { put("regola_id", 30) } })
        assertEquals(TestoNotifica("Fuori regola", "Tutto il telefono: al massimo 3 h al giorno"), sforamento)

        val bonus = frase(
            "bonus",
            buildJsonObject {
                put("minuti", 15)
                put("regola_id", 30)
            },
        )
        assertEquals(TestoNotifica("Bonus", "Si è dato 15 min in più su tutto il telefono"), bonus)

        val risposta = frase(
            "proposta_risposta",
            buildJsonObject {
                put("regola_id", 31)
                put("esito", "accetta")
            },
        )
        assertEquals("Ha accettato la tua proposta su tutto il computer", risposta.testo)

        val nuova = frase(
            "modifica_regola",
            buildJsonObject {
                put("regola_id", 31)
                put("azione", "creazione")
                put("parametri", totale(120, "computer").parametri)
            },
        )
        assertEquals(TestoNotifica("Nuova regola", "Tutto il computer: al massimo 2 h al giorno"), nuova)

        listOf(sforamento, bonus, risposta, nuova).forEach { t ->
            assertFalse("«${t.testo}»", (t.titolo + " " + t.testo).contains("totale"))
        }
    }

    @Test
    fun `lo storico racconta il cambio di bersaglio con le parole giuste`() {
        val oggiSulTotale = totale(180, "telefono")
        val primaSuInstagram = buildJsonObject {
            put("app_o_categoria", "com.instagram.android")
            put("minuti_al_giorno", 60)
        }
        assertEquals(
            "com.instagram.android: al massimo 1 h al giorno",
            descrizioneParametri(p, oggiSulTotale, primaSuInstagram),
        )
        assertEquals(
            "Tutto il telefono: al massimo 2 h al giorno",
            descrizioneParametri(
                p,
                oggiSulTotale,
                buildJsonObject {
                    put("app_o_categoria", "totale")
                    put("minuti_al_giorno", 120)
                },
            ),
        )
        // Il contrario: oggi è su TikTok, prima era su tutto il computer.
        val tiktokSulComputer = tiktok.copy(dispositivo = RiferimentoDispositivo(2, "PC", "computer"))
        assertEquals(
            "Tutto il computer: al massimo 3 h al giorno",
            descrizioneParametri(
                p,
                tiktokSulComputer,
                buildJsonObject {
                    put("app_o_categoria", "totale")
                    put("minuti_al_giorno", 180)
                },
            ),
        )
    }

    @Test
    fun `nel dialogo delle proposte il bersaglio del totale e detto a parole`() {
        assertEquals("Tutto il computer", bersaglioRegola(p, totale(180, "computer")))
        assertEquals("Tutto il telefono", bersaglioRegola(p, totale(180, "telefono")))
        assertEquals("TikTok", bersaglioRegola(p, tiktok))
        assertEquals("Social", bersaglioRegola(p, social))
    }

    private fun telefonoCol(limite: Int, bonus: Int, app: List<UsoApp> = emptyList()) = VistaDispositivo(
        id = 1,
        nome = "Telefono",
        usoRecente = listOf(
            UsoGiorno(
                giorno = "2026-09-24",
                totaleMinuti = 200,
                aggiornatoTs = fresco,
                app = app,
                limite = limite,
                regolaId = 30,
                bonus = bonus,
            ),
        ),
    )

    @Test
    fun `il digest dice il limite sul totale accanto al totale, come per le app`() {
        val telefono = telefonoCol(limite = 180, bonus = 0, app = listOf(UsoApp("tiktok", "TikTok", minuti = 60, limite = 60)))
        assertEquals(
            TestoNotifica("Oggi: 3 h 20 min (limite 3 h)", "TikTok 1 h (limite 1 h) — tocca per il dettaglio"),
            testoDigest(p, listOf(telefono), adesso),
        )
        val computer = VistaDispositivo(id = 2, nome = "Computer", tipo = "computer", usoRecente = usoDiOggi(60))
        assertEquals(
            "Oggi: Telefono 3 h 20 min (limite 3 h) · Computer 1 h",
            testoDigest(p, listOf(telefono, computer), adesso).titolo,
        )
        // Senza una regola sul totale il digest resta com'era.
        assertEquals("Oggi: 1 h", testoDigest(p, listOf(telefono.copy(usoRecente = usoDiOggi(60))), adesso).titolo)
    }

    @Test
    fun `nel digest il limite tiene conto del bonus di oggi, come nel Tempo`() {
        // 200 minuti su 180 + 15 di bonus: il limite di oggi è 3 h 15 min, e il
        // digest non deve far sembrare "oltre" un giorno che per il figlio è oltre
        // solo di 5 minuti (o dentro, con più bonus).
        val telefono = telefonoCol(
            limite = 180,
            bonus = 15,
            app = listOf(UsoApp("tiktok", "TikTok", minuti = 70, limite = 60, regolaId = 1, bonus = 15)),
        )
        assertEquals(
            TestoNotifica(
                "Oggi: 3 h 20 min (limite 3 h + 15 min di bonus)",
                "TikTok 1 h 10 min (limite 1 h + 15 min di bonus) — tocca per il dettaglio",
            ),
            testoDigest(p, listOf(telefono), adesso),
        )
        val computer = VistaDispositivo(id = 2, nome = "Computer", tipo = "computer", usoRecente = usoDiOggi(60))
        assertEquals(
            "Oggi: Telefono 3 h 20 min (limite 3 h + 15 min di bonus) · Computer 1 h",
            testoDigest(p, listOf(telefono, computer), adesso).titolo,
        )
    }

    // --- 0.9: oggi, regola per regola, nella scheda del patto ---------------------------

    private val brawlStars = RegolaFinestra(
        id = 11,
        tipo = "limite_tempo",
        parametri = buildJsonObject {
            put("app_o_categoria", "com.supercell.brawlstars")
            put("minuti_al_giorno", 30)
        },
        nome = "Brawl Stars",
    )

    @Test
    fun `ogni regola di oggi dice il suo stato a parole`() {
        val m = eu.stgm.pactum.design.Segnale.MANTENUTA
        val f = eu.stgm.pactum.design.Segnale.FUORI_REGOLA
        val n = eu.stgm.pactum.design.Segnale.NESSUN_DATO
        // La riga che Andrea vuole vedere subito: il nome come nelle regole, e lo stato.
        assertEquals("Brawl Stars: al massimo 30 min al giorno", descrizioneRegola(p, brawlStars))
        assertEquals("mantenuta", testoStatoRegola(p, RegolaDelGiorno(brawlStars, m)))
        assertEquals("fuori regola · 15 min oltre", testoStatoRegola(p, RegolaDelGiorno(brawlStars, f, minutiOltre = 15)))
        assertEquals("fuori regola · 1 h 5 min oltre", testoStatoRegola(p, RegolaDelGiorno(brawlStars, f, minutiOltre = 65)))
        assertEquals("fuori regola", testoStatoRegola(p, RegolaDelGiorno(brawlStars, f)))
        assertEquals("senza dati", testoStatoRegola(p, RegolaDelGiorno(brawlStars, n)))
        // Fasce orarie e totale: come i limiti.
        assertEquals("mantenuta", testoStatoRegola(p, RegolaDelGiorno(sera, m)))
        // La vita reale: gli stati delle dichiarazioni.
        assertEquals("successo confermato", testoStatoRegola(p, RegolaDelGiorno(camminare, m)))
        assertEquals("non riuscito", testoStatoRegola(p, RegolaDelGiorno(camminare, f)))
        assertEquals("nessuna conferma per ora", testoStatoRegola(p, RegolaDelGiorno(camminare, n)))
        // Una regola eliminata oggi che oggi ha contato: lo dice.
        assertEquals(
            "fuori regola · non più attiva",
            testoStatoRegola(p, RegolaDelGiorno(brawlStars.copy(attiva = false), f)),
        )
    }

    // --- 0.9: Pactum sempre attivo ------------------------------------------------------

    @Test
    fun `la spiegazione della batteria nomina i pulsanti che ci sono davvero`() {
        val testo = p.testo(R.string.batteria_testo)
        // Il nostro pulsante, e quello di Android: «Consenti».
        assertTrue(testo.contains("«${p.testo(R.string.batteria_ok)}»"))
        assertTrue(testo.contains("«Consenti»"))
        assertTrue(testo.contains("telefono è fermo"))
        // Niente frasi che Android non mostra, e niente "rispondi di sì".
        assertFalse(testo.contains("senza limiti di batteria"))
        assertFalse(testo.contains("rispondi di sì"))
    }

    @Test
    fun `la notifica fissa dice cosa fa, senza allarmare, e quando serve cosa non va`() {
        assertEquals("Pactum è attivo", p.testo(R.string.attivo_notifica_titolo))
        assertEquals("Ti avvisa quando succede qualcosa nel patto.", p.testo(R.string.attivo_notifica_testo))
        assertEquals(
            "Gli avvisi possono arrivare in ritardo: tocca per sistemare.",
            p.testo(R.string.attivo_notifica_ritardi),
        )
        assertEquals("Gli avvisi del patto sono spenti: tocca per sistemare.", p.testo(R.string.attivo_notifica_spenti))
    }

    @Test
    fun `nessuna promessa di subito dove Android non la garantisce`() {
        listOf(
            R.string.attivo_notifica_testo,
            R.string.attivo_notifica_ritardi,
            R.string.batteria_titolo,
            R.string.batteria_testo,
            R.string.impostazioni_attivo_titolo,
            R.string.impostazioni_attivo_descrizione,
            R.string.impostazioni_attivo_si,
            R.string.canale_attivo_descrizione,
        ).forEach { id ->
            val frase = p.testo(id).lowercase()
            listOf("subito", "appena", "immediat").forEach { promessa ->
                assertFalse("'$promessa' in «$frase»", frase.contains(promessa))
            }
        }
    }

    @Test
    fun `nell'elenco della batteria si dice che cosa cercare, col nome vero dell'app`() {
        val nome = p.testo(R.string.nome_app)
        assertEquals(
            "Nell'elenco scegli «Tutte le app», cerca «Pactum Genitore» e scegli «Non ottimizzare».",
            p.testo(R.string.batteria_cerca_nell_elenco, nome),
        )
    }

    @Test
    fun `il passo per la marca porta il nome vero dell'app`() {
        val nome = p.testo(R.string.nome_app)
        listOf(R.string.marca_huawei, R.string.marca_vivo, R.string.marca_samsung).forEach { id ->
            assertTrue(p.testo(id, nome).contains("«Pactum Genitore»"))
        }
    }

    @Test
    fun `il riassunto e la riga degli avvisi si leggono in italiano`() {
        assertEquals("Novità da leggere: 12", p.testo(R.string.riassunto_novita_titolo, 12))
        assertEquals("Avvisi: ultimo controllo alle 21:30", p.testo(R.string.avvisi_ultimo_controllo, "alle 21:30"))
        assertEquals("Alcune non si sono segnate (3): riprova.", p.testo(R.string.notifiche_segna_tutte_fallite, 3))
    }

    // --- nessun gergo nelle parole dell'app -------------------------------------------

    @Test
    fun `nelle frasi dell'app niente gergo tecnico`() {
        val documento = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val testi = listOf("string", "item").flatMap { tag ->
            val nodi = documento.getElementsByTagName(tag)
            (0 until nodi.length).map { nodi.item(it).textContent }
        }
        listOf("token", "endpoint", "postino", "figlio_id", "dispositivo_id").forEach { gergo ->
            testi.forEach { frase ->
                assertFalse("'$gergo' in «$frase»", frase.contains(gergo, ignoreCase = true))
            }
        }
        // Le chiavi del computer (`exe:minecraft.exe`, `sito:youtube.com`) non si
        // scrivono mai; "nessun sito: questa lista…" invece è italiano.
        val chiave = Regex("""\b(exe|sito):\S""", RegexOption.IGNORE_CASE)
        testi.forEach { frase -> assertFalse("chiave in «$frase»", chiave.containsMatchIn(frase)) }
    }
}
