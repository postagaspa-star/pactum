package eu.stgm.pactum.figlio.bonus

import eu.stgm.pactum.figlio.dati.DettaglioErrore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il bonus in sospeso: il vincolo è che non vada MAI perso e non parta MAI due
 * volte. Queste sono le regole pure che lo garantiscono; la parte con disco e
 * rete (ConsegnaBonus) le applica sotto un mutex solo.
 */
class RegoleBonusTest {

    private fun bonus(
        creatoIl: Long = 1_000_000L,
        inScrittura: Boolean = false,
        inviato: Boolean = false,
        base: Int? = null,
        minuti: Int = 15,
        giorno: String = "2026-09-19",
        giornoBase: String? = null,
    ) = BonusInSospeso(
        id = "b1",
        regolaId = 3,
        minuti = minuti,
        giorno = giorno,
        creatoIl = creatoIl,
        inScrittura = inScrittura,
        inviato = inviato,
        base = base,
        giornoBase = giornoBase,
    )

    // --- pronto: quando parte da solo ---

    @Test
    fun `dentro la finestra della snackbar il bonus aspetta`() {
        val b = bonus()
        assertFalse(RegoleBonus.pronto(b, b.creatoIl + RegoleBonus.FINESTRA_MS - 1))
    }

    @Test
    fun `chiusa la finestra il bonus parte da solo`() {
        val b = bonus()
        assertTrue(RegoleBonus.pronto(b, b.creatoIl + RegoleBonus.FINESTRA_MS))
    }

    @Test
    fun `mentre scrive il perche' il timer non lo manda`() {
        val b = bonus(inScrittura = true)
        assertFalse(RegoleBonus.pronto(b, b.creatoIl + RegoleBonus.FINESTRA_MS + 5_000))
    }

    @Test
    fun `un perche' abbandonato non trattiene il bonus per sempre`() {
        val b = bonus(inScrittura = true)
        assertTrue(RegoleBonus.pronto(b, b.creatoIl + RegoleBonus.SCRITTURA_MASSIMA_MS))
    }

    // --- passo: mai due volte ---

    @Test
    fun `un bonus mai partito si manda`() {
        assertEquals(RegoleBonus.Passo.Manda, RegoleBonus.passo(bonus(), "2026-09-19", 0))
    }

    @Test
    fun `un invio con risposta persa che il server conta non si rimanda`() {
        val b = bonus(inviato = true, base = 5)
        assertEquals(RegoleBonus.Passo.GiaArrivato, RegoleBonus.passo(b, "2026-09-19", 20))
    }

    @Test
    fun `un invio con risposta persa che il server non conta si rimanda`() {
        val b = bonus(inviato = true, base = 5)
        assertEquals(RegoleBonus.Passo.Manda, RegoleBonus.passo(b, "2026-09-19", 5))
    }

    @Test
    fun `un bonus di ieri non si manda oggi`() {
        assertEquals(RegoleBonus.Passo.Scarta, RegoleBonus.passo(bonus(), "2026-09-20", 0))
        // Nemmeno se era partito: di ieri è di ieri. Ma era partito, quindi
        // non si dice che "non è partito in tempo".
        val partito = bonus(inviato = true, base = 0)
        assertEquals(RegoleBonus.Passo.ScartaGiaPartito, RegoleBonus.passo(partito, "2026-09-20", 0))
    }

    // --- mezzanotte: il contatore del server riparte da zero ---

    @Test
    fun `se il giorno del server e' cambiato un bonus partito non si rimanda`() {
        // Partito alle 23:59 del 19 (base letta il 19), risposta persa. Il
        // telefono dice ancora 19, il server è già al 20: il suo contatore è a
        // zero anche se il bonus era arrivato. Rimandarlo lo darebbe due volte.
        val b = bonus(inviato = true, base = 0, giornoBase = "2026-09-19")
        assertEquals(
            RegoleBonus.Passo.ScartaGiaPartito,
            RegoleBonus.passo(b, "2026-09-19", minutiSulServer = 0, giornoServer = "2026-09-20"),
        )
    }

    @Test
    fun `nello stesso giorno del server il controllo sul contatore vale`() {
        val b = bonus(inviato = true, base = 0, giornoBase = "2026-09-19")
        assertEquals(
            RegoleBonus.Passo.GiaArrivato,
            RegoleBonus.passo(b, "2026-09-19", minutiSulServer = 15, giornoServer = "2026-09-19"),
        )
        assertEquals(
            RegoleBonus.Passo.Manda,
            RegoleBonus.passo(b, "2026-09-19", minutiSulServer = 0, giornoServer = "2026-09-19"),
        )
    }

    @Test
    fun `un bonus mai partito non guarda il giorno della base`() {
        // Niente base: il giorno del server conta solo per un invio incerto.
        assertEquals(
            RegoleBonus.Passo.Manda,
            RegoleBonus.passo(bonus(), "2026-09-19", minutiSulServer = 0, giornoServer = "2026-09-20"),
        )
    }

    // --- il bonus in sospeso nel limite della sentinella ---

    @Test
    fun `il bonus in sospeso di oggi allarga il limite della sua regola`() {
        val conto = RegoleBonus.bonusConSospeso(
            bonusOggi = mapOf("3" to 5),
            sospeso = bonus(minuti = 15),
            oggi = "2026-09-19",
            residuo = 25,
        )
        assertEquals(mapOf("3" to 20), conto)
    }

    @Test
    fun `il bonus in sospeso fuori dal residuo non conta`() {
        // Il server lo rifiuterà (tetto): lo sforamento va registrato.
        val conto = RegoleBonus.bonusConSospeso(
            bonusOggi = emptyMap(),
            sospeso = bonus(minuti = 30),
            oggi = "2026-09-19",
            residuo = 15,
        )
        assertEquals(emptyMap<String, Int>(), conto)
    }

    @Test
    fun `senza residuo noto il bonus in sospeso conta`() {
        // Server vecchio senza contatori: decide il server, e se dice no il
        // giro dopo lo sforamento c'è.
        val conto = RegoleBonus.bonusConSospeso(emptyMap(), bonus(minuti = 5), "2026-09-19", null)
        assertEquals(mapOf("3" to 5), conto)
    }

    @Test
    fun `il bonus in sospeso di un altro giorno non conta`() {
        val conto = RegoleBonus.bonusConSospeso(emptyMap(), bonus(giorno = "2026-09-18"), "2026-09-19", 30)
        assertEquals(emptyMap<String, Int>(), conto)
    }

    @Test
    fun `un bonus gia' contato dal server non si somma due volte`() {
        // Partito con base 5, il server ne conta già 20: è arrivato.
        val conto = RegoleBonus.bonusConSospeso(
            bonusOggi = mapOf("3" to 20),
            sospeso = bonus(inviato = true, base = 5, minuti = 15),
            oggi = "2026-09-19",
            residuo = 10,
        )
        assertEquals(mapOf("3" to 20), conto)
    }

    @Test
    fun `senza bonus in sospeso restano i bonus del server`() {
        assertEquals(mapOf("3" to 5), RegoleBonus.bonusConSospeso(mapOf("3" to 5), null, "2026-09-19", 25))
    }

    // --- esito della risposta ---

    @Test
    fun `200 e' concesso`() {
        assertEquals(EsitoBonus.Concesso(15), RegoleBonus.esitoRisposta(15, true, 200, null))
    }

    @Test
    fun `il 409 del tetto porta i residui`() {
        val dettaglio = DettaglioErrore(errore = "tetto_superato", residuoGiorno = 10, residuoSettimana = 40)
        assertEquals(
            EsitoBonus.TettoSuperato(15, residuoGiorno = 10, residuoSettimana = 40),
            RegoleBonus.esitoRisposta(15, false, 409, dettaglio),
        )
    }

    @Test
    fun `il 409 della regola non valida`() {
        val dettaglio = DettaglioErrore(errore = "regola_non_valida")
        assertEquals(EsitoBonus.RegolaNonValida(5), RegoleBonus.esitoRisposta(5, false, 409, dettaglio))
    }

    @Test
    fun `un altro 4xx e' un no sicuro`() {
        assertEquals(EsitoBonus.Rifiutato(5), RegoleBonus.esitoRisposta(5, false, 401, null))
    }

    @Test
    fun `rete assente o 5xx lasciano il dubbio e il bonus resta`() {
        assertEquals(EsitoBonus.SenzaRete(30), RegoleBonus.esitoRisposta(30, false, 0, null))
        assertEquals(EsitoBonus.SenzaRete(30), RegoleBonus.esitoRisposta(30, false, 502, null))
    }
}
