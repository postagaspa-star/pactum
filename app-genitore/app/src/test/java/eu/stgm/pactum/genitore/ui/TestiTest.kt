package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RegolaFinestra
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
}
