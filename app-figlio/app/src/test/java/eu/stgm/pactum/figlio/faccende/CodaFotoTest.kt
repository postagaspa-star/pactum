package eu.stgm.pactum.figlio.faccende

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) La coda delle foto delle faccende: una per faccenda, persistente,
 * che riprova da sola finché il server non la prende o dice che non serve
 * più; mai verso un altro collegamento; mai una foto scattata prima di una
 * bocciatura.
 */
class CodaFotoTest {

    private val mio = ChiaveCollegamento.di(ChiaveCollegamento.impronta("codice-di-prova"), dispositivoId = 1, figlioId = 1)
    private val altro = ChiaveCollegamento.di(ChiaveCollegamento.impronta("altro-codice"), dispositivoId = 7, figlioId = 2)
    private val t0 = 1_790_000_000_000L
    private val giorno = 24L * 60 * 60 * 1000

    private fun foto(id: Long, file: String, quando: Long = t0, chiave: ChiaveCollegamento = mio, bocciature: Int = 0) =
        FotoInCoda(faccendaId = id, file = file, scattataIl = quando, collegamento = chiave, bocciature = bocciature)

    @Test
    fun `uno scatto nuovo prende il posto di quello di prima della stessa faccenda`() {
        val (prima, via1) = MemoriaCodaFoto().conScatto(foto(5, "a.jpg"))
        assertTrue(via1.isEmpty())
        val (dopo, via2) = prima.conScatto(foto(5, "b.jpg", t0 + 1000))
        assertEquals(listOf("b.jpg"), dopo.foto.map { it.file })
        assertEquals(listOf("a.jpg"), via2)
    }

    @Test
    fun `faccende diverse, foto diverse, dalla più vecchia`() {
        val coda = MemoriaCodaFoto()
            .conScatto(foto(6, "b.jpg", t0 + 1000)).first
            .conScatto(foto(5, "a.jpg", t0)).first
        assertEquals(listOf(5L, 6L), coda.daMandare(mio).map { it.faccendaId })
    }

    @Test
    fun `mai verso un altro collegamento`() {
        val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg", chiave = altro)).first
        assertTrue(coda.daMandare(mio).isEmpty())
        val (pulita, via) = coda.senzaAltriCollegamenti(mio)
        assertTrue(pulita.foto.isEmpty())
        assertEquals(listOf("a.jpg"), via)
    }

    @Test
    fun `cambiare solo l'indirizzo del server non butta le foto - conta il codice`() {
        // L'impronta è del solo codice: l'indirizzo non c'entra.
        val stessoCodice = ChiaveCollegamento.di(ChiaveCollegamento.impronta("codice-di-prova"), dispositivoId = null, figlioId = null)
        assertTrue(mio.stesso(stessoCodice))
        val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg")).first
        assertEquals(1, coda.daMandare(stessoCodice).size)
        assertTrue(coda.senzaAltriCollegamenti(stessoCodice).second.isEmpty())
    }

    @Test
    fun `lo stesso telefono ricollegato con un codice nuovo tiene le foto`() {
        val ricollegato = ChiaveCollegamento.di(ChiaveCollegamento.impronta("codice-nuovo"), dispositivoId = 1, figlioId = 1)
        assertTrue(mio.stesso(ricollegato))
        assertFalse(mio.stesso(altro))
        assertNotEquals(ChiaveCollegamento.impronta("a"), ChiaveCollegamento.impronta("b"))
        assertFalse("il codice non si salva in chiaro", mio.codice.contains("codice-di-prova"))
    }

    @Test
    fun `senza rete, server fermo, server vecchio o scollegato - resta in coda e si riprova`() {
        for (esito in listOf(EsitoFoto.SENZA_RETE, EsitoFoto.ERRORE, EsitoFoto.SERVER_VECCHIO, EsitoFoto.SCOLLEGATO)) {
            val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg")).first
            val (dopo, via) = coda.conEsito(5, "a.jpg", esito, t0 + 1000)
            assertEquals(esito.name, 1, dopo.daMandare(mio).size)
            assertEquals(esito.name, 1, dopo.foto.single().tentativi)
            assertTrue(esito.name, via.isEmpty())
        }
    }

    @Test
    fun `arrivata - resta come mandata, senza più il file`() {
        val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg")).first
        val (dopo, via) = coda.conEsito(5, "a.jpg", EsitoFoto.ARRIVATA, t0 + 1000)
        assertEquals(StatiFoto.MANDATA, dopo.foto.single().stato)
        assertEquals(t0 + 1000, dopo.foto.single().mandataIl)
        assertTrue(dopo.daMandare(mio).isEmpty())
        assertEquals(listOf("a.jpg"), via)
    }

    @Test
    fun `non serve più (409 non_da_fare) - via`() {
        val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg")).first
        val (dopo, via) = coda.conEsito(5, "a.jpg", EsitoFoto.NON_SERVE, t0 + 1000)
        assertTrue(dopo.foto.isEmpty())
        assertEquals(listOf("a.jpg"), via)
    }

    @Test
    fun `bocciata nel frattempo (409 bocciata_nel_frattempo) - via dalla coda`() {
        val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg")).first
        val (dopo, via) = coda.conEsito(5, "a.jpg", EsitoFoto.BOCCIATA_NEL_FRATTEMPO, t0 + 1000)
        assertTrue(dopo.foto.isEmpty())
        assertEquals(listOf("a.jpg"), via)
    }

    @Test
    fun `rifiutata - non riparte, e si dice di scattarne un'altra`() {
        val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg")).first
        val (dopo, _) = coda.conEsito(5, "a.jpg", EsitoFoto.RIFIUTATA, t0 + 1000)
        assertEquals(StatiFoto.RIFIUTATA, dopo.foto.single().stato)
        assertTrue(dopo.daMandare(mio).isEmpty())
    }

    @Test
    fun `l'esito di un file che non c'è più non tocca lo scatto nuovo`() {
        val coda = MemoriaCodaFoto().conScatto(foto(5, "b.jpg")).first
        val (dopo, via) = coda.conEsito(5, "a.jpg", EsitoFoto.NON_SERVE, t0)
        assertEquals(coda, dopo)
        assertTrue(via.isEmpty())
    }

    @Test
    fun `una foto in coda, già arrivata e poi bocciata, non si rimanda - via col suo file`() {
        // La foto era partita, la risposta si è persa: per il telefono è ancora in coda.
        val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg", bocciature = 0)).first
            .conEsito(5, "a.jpg", EsitoFoto.SENZA_RETE, t0).first
        // Il genitore l'ha bocciata: il blocco dice bocciature 1.
        val (dopo, via) = coda.conDaFare(listOf(FaccendaDaFare(5, "Svuota la lavastoviglie", bocciature = 1)), t0 + 1000)
        assertTrue(dopo.daMandare(mio).isEmpty())
        assertEquals(listOf("a.jpg"), via)
    }

    @Test
    fun `una foto scattata dopo la bocciatura resta`() {
        val coda = MemoriaCodaFoto().conScatto(foto(5, "b.jpg", bocciature = 1)).first
        val (dopo, via) = coda.conDaFare(listOf(FaccendaDaFare(5, "Svuota la lavastoviglie", bocciature = 1)), t0 + 1000)
        assertEquals(1, dopo.daMandare(mio).size)
        assertTrue(via.isEmpty())
    }

    @Test
    fun `quello che dice il server - fatta, annullata o bocciata, la foto mandata se ne va`() {
        val faccenda = FaccendaDaFare(5, "Svuota la lavastoviglie")
        val mandata = MemoriaCodaFoto().conScatto(foto(5, "a.jpg")).first
            .conEsito(5, "a.jpg", EsitoFoto.ARRIVATA, t0).first
        assertEquals(1, mandata.conDaFare(listOf(faccenda), t0 + 1000).first.foto.size)
        assertTrue(mandata.conDaFare(emptyList(), t0 + 1000).first.foto.isEmpty())
        assertTrue(mandata.conDaFare(listOf(faccenda.copy(bocciature = 1)), t0 + 1000).first.foto.isEmpty())
        // Un lavoro che per il server è ancora DA FARE (il server non ha la foto):
        // dopo un giorno la "mandata" se ne va e «Scatta la foto» torna.
        assertTrue(mandata.conDaFare(listOf(faccenda), t0 + giorno + 1).first.foto.isEmpty())
        // (0.18, contratto v4.0) Finché il lavoro è aperto (aspetta l'approvazione)
        // la foto resta "mandata", anche dopo un giorno: non più solo 24 ore.
        val aspetta = faccenda.copy(stato = StatiFaccenda.FATTA, fotoIl = t0)
        assertEquals(1, mandata.conDaFare(listOf(aspetta), t0 + giorno + 1).first.foto.size)
        assertEquals(1, mandata.conDaFare(listOf(aspetta), t0 + 10 * giorno).first.foto.size)
    }

    @Test
    fun `una foto in coda di una faccenda che il blocco non dice resta - decide il server`() {
        val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg")).first
        assertEquals(coda, coda.conDaFare(emptyList(), t0 + 3 * giorno).first)
    }

    @Test
    fun `subito dopo una risposta fresca, le foto di faccende non più da fare se ne vanno`() {
        val coda = MemoriaCodaFoto()
            .conScatto(foto(5, "a.jpg")).first
            .conScatto(foto(6, "b.jpg")).first
        val (dopo, via) = coda.soloDaFare(listOf(FaccendaDaFare(6, "Porta fuori il cane")))
        assertEquals(listOf(6L), dopo.foto.map { it.faccendaId })
        assertEquals(listOf("a.jpg"), via)
    }

    @Test
    fun `al massimo 30 foto - le mandate più vecchie se ne vanno, mai una da mandare`() {
        // (0.18, contratto v4.0) Prima si scattavano e si toglievano le più vecchie
        // anche se non erano ancora partite. Adesso fa posto solo una foto già
        // mandata (che aspetta l'approvazione): 2 mandate, 30 nuove in coda.
        var coda = MemoriaCodaFoto()
        val via = mutableListOf<String>()
        for (i in 1..2) {
            coda = coda.conScatto(foto(i.toLong(), "$i.jpg", t0 + i)).first
            coda = coda.conEsito(i.toLong(), "$i.jpg", EsitoFoto.ARRIVATA, t0 + i).first
        }
        for (i in 3..32) {
            val (nuova, togliere) = coda.conScatto(foto(i.toLong(), "$i.jpg", t0 + i))
            coda = nuova
            via += togliere
        }
        assertEquals(MemoriaCodaFoto.MASSIMO, coda.foto.size)
        assertNull(coda.di(1))
        assertNull(coda.di(2))
        assertEquals(30, coda.daMandare(mio).size)
    }

    @Test
    fun `la coda si salva e si rilegge uguale - con le bocciature dello scatto`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val coda = MemoriaCodaFoto().conScatto(foto(5, "a.jpg", bocciature = 2)).first
            .conEsito(5, "a.jpg", EsitoFoto.SENZA_RETE, t0).first
        val riletta = json.decodeFromString(MemoriaCodaFoto.serializer(), json.encodeToString(MemoriaCodaFoto.serializer(), coda))
        assertEquals(coda, riletta)
        assertEquals(2, riletta.daMandare(mio).single().bocciature)
    }
}
