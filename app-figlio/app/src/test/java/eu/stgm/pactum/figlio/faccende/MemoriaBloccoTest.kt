package eu.stgm.pactum.figlio.faccende

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) La macchina del blocco: il server è la verità; senza rete il blocco
 * resta com'era; un blocco programmato parte all'ora (del server) giusta anche
 * offline; si sblocca solo quando il server dice che non c'è più niente da
 * fare (o con un 401). L'orologio a muro spostato non cambia niente.
 */
class MemoriaBloccoTest {

    private val min = 60_000L
    private val ore = 60 * min
    private val t0 = 1_790_000_000_000L

    /** Il telefono acceso da [ACCESO] ms prima di t0: monotono e muro vanno insieme, finché nessuno sposta l'ora. */
    private fun ora(muro: Long, monotono: Long = muro - t0 + ACCESO, avvio: Int? = 1) = Istante(muro, monotono, avvio)

    private val lavastoviglie = FaccendaDaFare(1, "Svuota la lavastoviglie", bloccoDa = t0 + ore, genitore = "Mamma")
    private val cane = FaccendaDaFare(2, "Porta fuori il cane", bloccoDa = t0 + ore, genitore = "Papà")

    private fun attivo(dal: Long = t0, vararg da: FaccendaDaFare) = BloccoDalServer(true, dal, null, da.toList())
    private fun programmato(prossimo: Long, vararg da: FaccendaDaFare) = BloccoDalServer(false, null, prossimo, da.toList())
    private val libero = BloccoDalServer(false, null, null, emptyList())

    /** Una risposta del server con l'orologio del server uguale a quello del telefono. */
    private fun MemoriaBlocco.risposta(r: BloccoDalServer, t: Long) = conServer(r, ora(t), ora(t), dataServer = t)

    @Test
    fun `all'inizio niente blocco`() {
        val m = MemoriaBlocco()
        assertFalse(m.attivoAdesso(ora(t0)))
        assertNull(m.prossimaPartenza(ora(t0)))
    }

    @Test
    fun `il server dice bloccato - bloccato`() {
        val m = MemoriaBlocco().risposta(attivo(t0, lavastoviglie), t0)
        assertTrue(m.attivoAdesso(ora(t0)))
        assertEquals(listOf(lavastoviglie), m.daFare)
    }

    @Test
    fun `senza rete un telefono bloccato resta bloccato, anche giorni dopo`() {
        val m = MemoriaBlocco().risposta(attivo(t0, lavastoviglie), t0)
        assertTrue(m.attivoAdesso(ora(t0 + 3 * 24 * ore)))
    }

    @Test
    fun `si sblocca solo quando il server dice che non c'è più niente da fare`() {
        val dopo = MemoriaBlocco().risposta(attivo(t0, lavastoviglie), t0).risposta(libero, t0 + min)
        assertFalse(dopo.attivoAdesso(ora(t0 + min)))
        assertNull(dopo.dalAdesso(ora(t0 + min)))
    }

    @Test
    fun `un server che non conosce le faccende non sblocca`() {
        val dopo = MemoriaBlocco().risposta(attivo(t0, lavastoviglie), t0).conServerVecchio()
        assertTrue(dopo.attivoAdesso(ora(t0 + min)))
        assertTrue(dopo.serverVecchio)
    }

    @Test
    fun `un 401 toglie il blocco e lo dice, la risposta dopo lo rimette`() {
        val bloccato = MemoriaBlocco().risposta(attivo(t0, lavastoviglie), t0)
        val revocato = bloccato.conScollegato(ora(t0 + min), ora(t0 + min))
        assertFalse(revocato.attivoAdesso(ora(t0 + min)))
        assertTrue(revocato.scollegato)
        assertTrue(revocato.daFare.isEmpty())
        // Ricollegato: il server risponde di nuovo.
        val ricollegato = revocato.risposta(attivo(t0, lavastoviglie), t0 + 2 * min)
        assertTrue(ricollegato.attivoAdesso(ora(t0 + 2 * min)))
        assertFalse(ricollegato.scollegato)
    }

    @Test
    fun `una risposta vecchia, arrivata dopo una più fresca, non cambia niente`() {
        val sbloccato = MemoriaBlocco().risposta(attivo(t0, lavastoviglie), t0).risposta(libero, t0 + 2 * min)
        // Una lettura del patto partita prima (ancora col blocco) arriva adesso.
        val dopo = sbloccato.conServer(attivo(t0, lavastoviglie), ora(t0 + min), ora(t0 + 3 * min), t0 + 3 * min)
        assertFalse(dopo.attivoAdesso(ora(t0 + 3 * min)))
    }

    @Test
    fun `orologio avanti al 2030 e poi indietro - la risposta dopo vale`() {
        val anno = 365L * 24 * ore
        // Il ragazzo mette la data avanti di quattro anni: il server risponde "bloccato".
        val avanti = MemoriaBlocco().conServer(attivo(t0, lavastoviglie), ora(t0 + 4 * anno, monotono = ACCESO), ora(t0 + 4 * anno, monotono = ACCESO + 1000), t0)
        // Poi rimette la data giusta; più tardi (sull'orologio che non si sposta) il server sblocca.
        val dopo = avanti.conServer(libero, ora(t0 + 5 * min, monotono = ACCESO + 5 * min), ora(t0 + 5 * min, monotono = ACCESO + 5 * min), t0 + 5 * min)
        assertFalse("la risposta dopo deve valere", dopo.attivoAdesso(ora(t0 + 5 * min, monotono = ACCESO + 5 * min)))
        // E al contrario: un'altra risposta dopo, che blocca, vale anche lei.
        val ancora = dopo.conServer(attivo(t0 + 6 * min, cane), ora(t0 - anno, monotono = ACCESO + 6 * min), ora(t0 - anno, monotono = ACCESO + 6 * min), t0 + 6 * min)
        assertTrue(ancora.attivoAdesso(ora(t0 - anno, monotono = ACCESO + 6 * min)))
    }

    @Test
    fun `dopo un riavvio la risposta nuova vale anche se il contatore è più basso`() {
        val prima = MemoriaBlocco().conServer(attivo(t0, lavastoviglie), ora(t0, monotono = 10 * ore, avvio = 1), ora(t0, monotono = 10 * ore, avvio = 1), t0)
        val dopo = prima.conServer(libero, ora(t0 + ore, monotono = 5 * min, avvio = 2), ora(t0 + ore, monotono = 5 * min, avvio = 2), t0 + ore)
        assertFalse(dopo.attivoAdesso(ora(t0 + ore, monotono = 5 * min, avvio = 2)))
    }

    @Test
    fun `accensione che non si sa - la risposta vale sempre`() {
        val prima = MemoriaBlocco().conServer(attivo(t0, lavastoviglie), ora(t0, monotono = 10 * ore, avvio = null), ora(t0, monotono = 10 * ore, avvio = null), t0)
        val dopo = prima.conServer(libero, ora(t0 + ore, monotono = 5 * min, avvio = null), ora(t0 + ore, monotono = 5 * min, avvio = null), t0 + ore)
        assertFalse(dopo.attivoAdesso(ora(t0 + ore, monotono = 5 * min, avvio = null)))
    }

    @Test
    fun `un cambio d'ora a mano fa ripartire l'ordine ma non sblocca`() {
        val m = MemoriaBlocco().risposta(attivo(t0, lavastoviglie), t0).conCambioOra()
        assertTrue(m.attivoAdesso(ora(t0 + min)))
        assertNull(m.ordine)
        assertNull(m.scarto)
    }

    @Test
    fun `un blocco programmato parte da solo all'ora giusta, anche senza rete`() {
        val prossimo = t0 + ore
        val m = MemoriaBlocco().risposta(programmato(prossimo, lavastoviglie), t0)
        assertFalse(m.attivoAdesso(ora(prossimo - 1)))
        assertEquals(prossimo, m.prossimaPartenza(ora(t0)))
        assertEquals(ore, m.attesaPartenza(ora(t0)))
        assertTrue(m.partitoDaSolo(ora(prossimo)))
        assertTrue(m.attivoAdesso(ora(prossimo + 5 * ore)))
        assertEquals(prossimo, m.dalAdesso(ora(prossimo)))
        assertNull(m.prossimaPartenza(ora(prossimo)))
    }

    @Test
    fun `l'ora del telefono spostata non sposta la partenza - conta l'orologio che non si sposta`() {
        val prossimo = t0 + ore
        val m = MemoriaBlocco().risposta(programmato(prossimo, lavastoviglie), t0)
        // Ora a muro indietro di un giorno, ma è passata un'ora vera: il blocco parte.
        assertTrue(m.attivoAdesso(ora(t0 - 24 * ore, monotono = ACCESO + ore)))
        // Ora a muro avanti di un giorno, ma è passato solo un minuto vero: non parte.
        assertFalse(m.attivoAdesso(ora(t0 + 24 * ore, monotono = ACCESO + min)))
    }

    @Test
    fun `un telefono avanti di un'ora non parte un'ora prima - conta lo scarto misurato`() {
        val prossimo = t0 + 2 * ore
        // Il server dice le t0, il telefono crede siano le t0 + 1 ora.
        val m = MemoriaBlocco().conServer(programmato(prossimo, lavastoviglie), ora(t0 + ore), ora(t0 + ore), dataServer = t0)
        assertEquals(t0, m.oraServer(ora(t0 + ore)))
        // Un'ora vera dopo: per il server sono le t0 + 1 ora, niente blocco.
        assertFalse(m.attivoAdesso(ora(t0 + 2 * ore)))
        // Due ore vere dopo: il blocco parte.
        assertTrue(m.attivoAdesso(ora(t0 + 3 * ore)))
        assertEquals(2 * ore, m.attesaPartenza(ora(t0 + ore)))
    }

    @Test
    fun `dopo un riavvio senza rete si conta sull'ora a muro più lo scarto`() {
        val prossimo = t0 + 2 * ore
        val m = MemoriaBlocco().conServer(programmato(prossimo, lavastoviglie), ora(t0 + ore), ora(t0 + ore), dataServer = t0)
        // Riavviato (accensione 2): il telefono è sempre un'ora avanti.
        assertFalse(m.attivoAdesso(Istante(t0 + 2 * ore, 5 * min, 2)))
        assertTrue(m.attivoAdesso(Istante(t0 + 3 * ore, 65 * min, 2)))
    }

    @Test
    fun `un telefono indietro di un'ora non parte un'ora dopo`() {
        val prossimo = t0 + 2 * ore
        val m = MemoriaBlocco().conServer(programmato(prossimo, lavastoviglie), ora(t0 - ore), ora(t0 - ore), dataServer = t0)
        assertTrue(m.attivoAdesso(ora(t0 + ore)))
    }

    @Test
    fun `partito da solo, si sblocca solo quando il server lo dice`() {
        val prossimo = t0 + ore
        val m = MemoriaBlocco().risposta(programmato(prossimo, lavastoviglie), t0)
        val dopo = m.risposta(libero, prossimo + 10 * min)
        assertFalse(dopo.attivoAdesso(ora(prossimo + 10 * min)))
    }

    @Test
    fun `fatte prima dell'ora, il blocco non parte - il server ha parlato dopo`() {
        val prossimo = t0 + ore
        val m = MemoriaBlocco().risposta(programmato(prossimo, lavastoviglie), t0).risposta(libero, t0 + 30 * min)
        assertFalse(m.attivoAdesso(ora(prossimo + min)))
        assertNull(m.prossimaPartenza(ora(t0 + 30 * min)))
    }

    @Test
    fun `una risposta in ritardo di pochi secondi non sblocca e riblocca`() {
        val prossimo = t0 + ore
        val m = MemoriaBlocco().risposta(programmato(prossimo, lavastoviglie), t0)
        assertTrue(m.attivoAdesso(ora(prossimo + 1000)))
        // La risposta del server, partita un attimo prima delle 16 sul suo orologio: "non ancora".
        val dopo = m.conServer(programmato(prossimo, lavastoviglie), ora(prossimo + 1000), ora(prossimo + 2000), dataServer = prossimo - 1000)
        assertTrue(dopo.attivoAdesso(ora(prossimo + 2000)))
        val confermato = dopo.risposta(attivo(prossimo, lavastoviglie), prossimo + 3 * min)
        assertTrue(confermato.attivoAdesso(ora(prossimo + 3 * min)))
        assertEquals(dopo.episodioAdesso(ora(prossimo + min)), confermato.episodioAdesso(ora(prossimo + 3 * min)))
    }

    @Test
    fun `un telefono libero col blocco lontano resta libero`() {
        val m = MemoriaBlocco().risposta(programmato(t0 + 3 * ore, lavastoviglie), t0)
        assertFalse(m.attivoAdesso(ora(t0 + min)))
    }

    @Test
    fun `il prossimo blocco lontano sblocca anche un telefono bloccato`() {
        val dopo = MemoriaBlocco().risposta(attivo(t0, lavastoviglie), t0).risposta(programmato(t0 + 24 * ore, cane), t0 + min)
        assertFalse(dopo.attivoAdesso(ora(t0 + min)))
        assertEquals(t0 + 24 * ore, dopo.prossimaPartenza(ora(t0 + min)))
    }

    @Test
    fun `l'avviso del blocco una volta sola per blocco`() {
        val m = MemoriaBlocco().risposta(attivo(t0, lavastoviglie, cane), t0)
        val ep = m.daAnnunciare(ora(t0))
        assertEquals(t0, ep)
        val annunciato = m.conAnnuncio(ep!!)
        assertNull(annunciato.daAnnunciare(ora(t0 + min)))
        // Fatta la più vecchia: il blocco continua (cambia il "dal"), niente avviso nuovo.
        val continua = annunciato.risposta(attivo(t0 + 5 * min, cane), t0 + 2 * min)
        assertTrue(continua.attivoAdesso(ora(t0 + 2 * min)))
        assertNull(continua.daAnnunciare(ora(t0 + 2 * min)))
        // Sbloccato e poi bocciata: è un blocco nuovo, si avvisa di nuovo.
        val nuovo = continua.risposta(libero, t0 + 3 * min).risposta(attivo(t0 + 10 * min, cane), t0 + 10 * min)
        assertEquals(t0 + 10 * min, nuovo.daAnnunciare(ora(t0 + 10 * min)))
    }

    @Test
    fun `il blocco partito da solo si annuncia all'ora giusta`() {
        val prossimo = t0 + ore
        val m = MemoriaBlocco().risposta(programmato(prossimo, lavastoviglie), t0)
        assertNull(m.daAnnunciare(ora(prossimo - 1)))
        assertEquals(prossimo, m.daAnnunciare(ora(prossimo)))
    }

    @Test
    fun `bloccato in un istante passato, per la forza arresto`() {
        val m = MemoriaBlocco().risposta(programmato(t0 + ore, lavastoviglie), t0)
        assertFalse(m.attivoAlMuro(t0 + 30 * min))
        assertTrue(m.attivoAlMuro(t0 + 2 * ore))
        assertTrue(MemoriaBlocco().risposta(attivo(t0, lavastoviglie), t0).attivoAlMuro(t0))
    }

    @Test
    fun `l'elenco più vecchio di quello che c'è non entra`() {
        val f = FaccendaLocale(1, "Svuota la lavastoviglie", stato = StatiFaccenda.DA_FARE)
        val m = MemoriaBlocco().conElenco(listOf(f), ora(t0 + min), ora(t0 + min))
        assertEquals(m, m.conElenco(emptyList(), ora(t0), ora(t0 + 2 * min)))
        assertTrue(m.haFaccende)
        assertFalse(MemoriaBlocco().haFaccende)
    }

    @Test
    fun `cosa è cambiato fra le faccende da fare`() {
        assertFalse(NovitaFaccende.cambiate(listOf(lavastoviglie), listOf(lavastoviglie)))
        assertTrue(NovitaFaccende.cambiate(listOf(lavastoviglie), listOf(lavastoviglie, cane)))
        assertTrue(NovitaFaccende.cambiate(listOf(lavastoviglie, cane), listOf(lavastoviglie)))
        assertTrue(NovitaFaccende.cambiate(listOf(lavastoviglie), listOf(lavastoviglie.copy(bocciature = 1))))
    }

    @Test
    fun `il blocco si salva e si rilegge uguale - sopravvive al riavvio`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val m = MemoriaBlocco()
            .risposta(attivo(t0, lavastoviglie.copy(ultimaBocciatura = Bocciatura(t0, "anche le pentole", "Mamma"))), t0)
            .conAnnuncio(t0)
        val riletta = json.decodeFromString(MemoriaBlocco.serializer(), json.encodeToString(MemoriaBlocco.serializer(), m))
        assertEquals(m, riletta)
        assertTrue(riletta.attivoAdesso(ora(t0 + ore)))
    }

    private companion object {
        const val ACCESO = 3_600_000L
    }
}
