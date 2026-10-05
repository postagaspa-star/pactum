package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.sync.Vedetta
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.15) La navigazione dell'app del genitore: la tabella degli ingressi (tipo di
 * notifica → destinazione → scheda o pagina), le destinazioni di prima che
 * possono essere ancora nella tendina, il tasto Indietro, il salvataggio della
 * pila e il tocco su una riga della lista delle notifiche.
 */
class NavigazioneTest {

    private val panoramica = Schermo.SuScheda(Scheda.PANORAMICA)
    private val daDecidere = Schermo.SuScheda(Scheda.DA_DECIDERE)
    private val lavori = Schermo.SuScheda(Scheda.LAVORI)
    private val tempo = Schermo.SuScheda(Scheda.TEMPO)
    private val notifiche = Schermo.SuPagina(Pagina.Notifiche)

    /** Dove porta il tocco su una notifica di sistema di quel tipo, partendo dalla Panoramica. */
    private fun doveVa(tipo: String): Schermo = ingresso(Vedetta.destinazionePerTipo(tipo)).inCima

    // --- La tabella degli ingressi ----------------------------------------------------------

    @Test
    fun `tutto quello che aspetta il genitore o gli risponde apre Da decidere`() {
        listOf(
            "nuova_proposta",
            "sessione_da_approvare",
            "proposta_risposta",
            "proposta_annullata",
            "proposta_ritirata",
            "dichiarazione",
        ).forEach { tipo ->
            assertEquals(tipo, MainActivity.DEST_DECIDERE, Vedetta.destinazionePerTipo(tipo))
            assertEquals(tipo, daDecidere, doveVa(tipo))
        }
    }

    @Test
    fun `i lavori di casa aprono la scheda Lavori, la foto la apre la scheda`() {
        assertEquals(MainActivity.DEST_FACCENDE, Vedetta.destinazionePerTipo("faccenda_fatta"))
        assertEquals(MainActivity.DEST_FACCENDE, Vedetta.destinazionePerTipo("faccende_finite"))
        assertEquals(lavori, doveVa("faccenda_fatta"))
        assertEquals(lavori, doveVa("faccende_finite"))
    }

    @Test
    fun `tutto il resto apre la lista delle notifiche, sopra la scheda di adesso`() {
        listOf("sforamento", "manomissione", "bonus", "modifica_regola", "sessione_eliminata", "sospensione", "ripresa", "tipo_del_futuro")
            .forEach { tipo ->
                assertEquals(tipo, MainActivity.DEST_NOTIFICHE, Vedetta.destinazionePerTipo(tipo))
                assertEquals(tipo, notifiche, doveVa(tipo))
            }
        // Sopra la scheda che c'era: Indietro torna lì.
        val dalTempo = ingresso(MainActivity.DEST_NOTIFICHE, Navigazione().scegli(Scheda.TEMPO))
        assertEquals(listOf(tempo, notifiche), dalTempo.pila)
        assertEquals(Navigazione(listOf(tempo)), dalTempo.indietro())
    }

    @Test
    fun `gli avvisi che scrive l'app vanno dove si guarda il fatto`() {
        // Silenzio, contatto ripreso, spento: la Panoramica del figlio (Vedetta.avvisa).
        assertEquals(panoramica, ingresso(MainActivity.DEST_FINESTRA, Navigazione().scegli(Scheda.TEMPO)).inCima)
        // Il riassunto della sera: il Tempo.
        assertEquals(tempo, ingresso(MainActivity.DEST_TEMPO).inCima)
        // "Novità da leggere": la lista.
        assertEquals(notifiche, ingresso(MainActivity.DEST_NOTIFICHE).inCima)
        // La notifica fissa con gli avvisi in ritardo o spenti: Impostazioni, sezione Avvisi.
        assertEquals(
            Schermo.SuPagina(Pagina.Impostazioni(SezioneImpostazioni.AVVISI)),
            ingresso(MainActivity.DEST_AVVISI).inCima,
        )
    }

    @Test
    fun `le destinazioni di prima, ancora nella tendina, funzionano ancora`() {
        assertEquals(panoramica, ingresso("finestra").inCima)
        assertEquals(daDecidere, ingresso("turno").inCima)
        assertEquals(daDecidere, ingresso("proposte").inCima)
        assertEquals(daDecidere, ingresso("verdetti").inCima)
        assertEquals(notifiche, ingresso("notifiche").inCima)
        assertEquals(Schermo.SuPagina(Pagina.Impostazioni(SezioneImpostazioni.AVVISI)), ingresso("avvisi").inCima)
        assertEquals(lavori, ingresso("faccende").inCima)
        assertEquals(tempo, ingresso("tempo").inCima)
        // Un valore che non si conosce: la Panoramica; nessuna destinazione: si resta dove si è.
        assertEquals(panoramica, ingresso("schermata_del_futuro", Navigazione().scegli(Scheda.LAVORI)).inCima)
        val dove = Navigazione().scegli(Scheda.LAVORI).apri(Pagina.Storico)
        assertEquals(dove, ingresso(null, dove))
    }

    @Test
    fun `i valori delle destinazioni non cambiano`() {
        // Le notifiche già nella tendina li portano nel loro PendingIntent.
        assertEquals("finestra", MainActivity.DEST_FINESTRA)
        assertEquals("turno", MainActivity.DEST_TURNO)
        assertEquals("proposte", MainActivity.DEST_PROPOSTE)
        assertEquals("verdetti", MainActivity.DEST_VERDETTI)
        assertEquals("notifiche", MainActivity.DEST_NOTIFICHE)
        assertEquals("avvisi", MainActivity.DEST_AVVISI)
        assertEquals("faccende", MainActivity.DEST_FACCENDE)
        assertEquals("tempo", MainActivity.DEST_TEMPO)
        assertEquals("decidere", MainActivity.DEST_DECIDERE)
    }

    // --- Il tasto Indietro ---------------------------------------------------------------------

    @Test
    fun `Indietro da una scheda torna alla Panoramica, dalla Panoramica esce`() {
        Scheda.entries.filter { it != Scheda.PANORAMICA }.forEach { scheda ->
            assertEquals(Navigazione(), Navigazione().scegli(scheda).indietro())
        }
        assertNull(Navigazione().indietro())
    }

    @Test
    fun `Indietro da una pagina torna da dove si era venuti`() {
        val daTempo = Navigazione().scegli(Scheda.TEMPO).apri(Pagina.Impostazioni())
        assertTrue(daTempo.suUnaPagina)
        assertEquals(Scheda.TEMPO, daTempo.scheda)
        assertEquals(Navigazione(listOf(tempo)), daTempo.indietro())

        // La regola aperta dalla Panoramica, poi "Indietro".
        val regola = Navigazione().apri(Pagina.Regola(7))
        assertEquals(Navigazione(), regola.indietro())

        // "Dai lavori di casa" dalla scheda Lavori.
        val dai = Navigazione().scegli(Scheda.LAVORI).apri(Pagina.DaiLavori)
        assertEquals(Navigazione(listOf(lavori)), dai.indietro())
    }

    @Test
    fun `i Lavori aperti dalle Notifiche tornano alle Notifiche`() {
        val dalleNotifiche = Navigazione().apri(Pagina.Notifiche).apriScheda(Scheda.LAVORI)
        assertEquals(listOf(panoramica, notifiche, lavori), dalleNotifiche.pila)
        // La barra in basso c'è (in cima c'è una scheda) e accende Lavori.
        assertFalse(dalleNotifiche.suUnaPagina)
        assertEquals(Scheda.LAVORI, dalleNotifiche.scheda)
        val indietro = dalleNotifiche.indietro()
        assertEquals(Navigazione(listOf(panoramica, notifiche)), indietro)
        assertEquals(Navigazione(), indietro?.indietro())
    }

    @Test
    fun `un tocco sulla barra riparte da quella scheda, una pagina gia aperta non si impila due volte`() {
        val lunga = Navigazione().apri(Pagina.Notifiche).apriScheda(Scheda.LAVORI)
        assertEquals(Navigazione(listOf(tempo)), lunga.scegli(Scheda.TEMPO))
        // Dalle Notifiche ai Lavori e di nuovo alle Notifiche: si torna a quelle di prima.
        assertEquals(Navigazione(listOf(panoramica, notifiche)), lunga.apri(Pagina.Notifiche))
        // Senza pagine sopra, aprire una scheda è come toccarla nella barra.
        assertEquals(Navigazione(listOf(lavori)), Navigazione().apriScheda(Scheda.LAVORI))
    }

    // --- Il salvataggio della pila (rotazione, app chiusa da Android) -------------------------------

    @Test
    fun `la pila si salva e si ritrova uguale`() {
        val pila = Navigazione()
            .scegli(Scheda.DA_DECIDERE)
            .apri(Pagina.Notifiche)
            .apriScheda(Scheda.LAVORI)
            .apri(Pagina.DaiLavori)
            .apri(Pagina.Regola(42))
            .apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO))
            .apri(Pagina.Storico)
            .apri(Pagina.Sessioni)
            .apri(Pagina.TutteLeRegole)
        assertEquals(pila, decodificaNavigazione(codificaNavigazione(pila)))
        assertEquals(
            Navigazione(listOf(tempo, Schermo.SuPagina(Pagina.Impostazioni()))),
            decodificaNavigazione(codificaNavigazione(Navigazione().scegli(Scheda.TEMPO).apri(Pagina.Impostazioni()))),
        )
    }

    @Test
    fun `una pila rovinata torna alla Panoramica`() {
        assertEquals(Navigazione(), decodificaNavigazione(null))
        assertEquals(Navigazione(), decodificaNavigazione(emptyList()))
        assertEquals(Navigazione(), decodificaNavigazione(listOf("scheda:SCONOSCIUTA")))
        assertEquals(Navigazione(), decodificaNavigazione(listOf("notifiche")))
        assertEquals(Navigazione(), decodificaNavigazione(listOf("scheda:TEMPO", "regola:non-un-numero")))
    }

    // --- Il tocco su una riga della lista delle notifiche ----------------------------------------------

    private fun notifica(tipo: String, payload: Map<String, Any> = emptyMap(), figlio: Long? = 2) = Notifica(
        id = 1,
        tipo = tipo,
        messaggio = "m",
        payload = buildJsonObject {
            payload.forEach { (chiave, valore) ->
                when (valore) {
                    is Number -> put(chiave, valore)
                    is kotlinx.serialization.json.JsonElement -> put(chiave, valore)
                    else -> put(chiave, valore.toString())
                }
            }
        },
        tsServer = "2026-10-04T10:00:00+00:00",
        figlioId = figlio,
    )

    @Test
    fun `una riga della lista porta dove si guarda il fatto, col suo figlio`() {
        assertEquals(ApriDaNotifica(daDecidere, 2), destinazioneDellaRiga(notifica("nuova_proposta")))
        assertEquals(ApriDaNotifica(daDecidere, 2), destinazioneDellaRiga(notifica("dichiarazione")))
        // Un lavoro fatto: i Lavori del figlio con la sua foto.
        assertEquals(
            ApriDaNotifica(lavori, 2, faccendaId = 103),
            destinazioneDellaRiga(notifica("faccenda_fatta", mapOf("faccenda_id" to 103))),
        )
        assertEquals(ApriDaNotifica(lavori, 2), destinazioneDellaRiga(notifica("faccende_finite")))
        // Un fuori regola: la sua regola, se si sa quale; se no la Panoramica.
        val dettagli = buildJsonObject { put("regola_id", JsonPrimitive(9)) }
        assertEquals(
            ApriDaNotifica(Schermo.SuPagina(Pagina.Regola(9)), 2),
            destinazioneDellaRiga(notifica("sforamento", mapOf("dettagli" to dettagli))),
        )
        assertEquals(ApriDaNotifica(panoramica, 2), destinazioneDellaRiga(notifica("sforamento")))
        assertEquals(
            ApriDaNotifica(Schermo.SuPagina(Pagina.Regola(5)), 2),
            destinazioneDellaRiga(notifica("modifica_regola", mapOf("regola_id" to 5))),
        )
        // Un bonus: il Tempo. Spento, acceso, anomalie, tipi nuovi: la Panoramica.
        assertEquals(ApriDaNotifica(tempo, 2), destinazioneDellaRiga(notifica("bonus")))
        listOf("sospensione", "ripresa", "manomissione", "sessione_eliminata", "tipo_del_futuro").forEach {
            assertEquals(it, ApriDaNotifica(panoramica, 2), destinazioneDellaRiga(notifica(it)))
        }
    }

    @Test
    fun `dopo il tocco su una riga, Indietro torna alla lista`() {
        val lista = Navigazione().apri(Pagina.Notifiche)
        val suDaDecidere = lista.dopoLaRiga(ApriDaNotifica(daDecidere, 2))
        assertEquals(listOf(panoramica, notifiche, daDecidere), suDaDecidere.pila)
        assertEquals(lista, suDaDecidere.indietro())
        val suRegola = lista.dopoLaRiga(ApriDaNotifica(Schermo.SuPagina(Pagina.Regola(9)), 2))
        assertEquals(lista, suRegola.indietro())
    }
}
