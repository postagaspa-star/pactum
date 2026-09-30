package eu.stgm.pactum.genitore.servizio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Quale passo per il risparmio batteria della marca mostrare (0.9). Se sbaglia,
 * al padre con un Xiaomi manca il pezzo che fa arrivare gli avvisi — o a chi ha
 * un Pixel si mostra un passo che non esiste.
 */
class MarcheTest {

    @Test
    fun `le marche col risparmio batteria loro si riconoscono dal produttore`() {
        assertEquals(MarcaConRisparmio.XIAOMI, marcaConRisparmio("Xiaomi"))
        assertEquals(MarcaConRisparmio.XIAOMI, marcaConRisparmio("Redmi"))
        assertEquals(MarcaConRisparmio.XIAOMI, marcaConRisparmio("POCO"))
        assertEquals(MarcaConRisparmio.HUAWEI, marcaConRisparmio("HUAWEI"))
        assertEquals(MarcaConRisparmio.HUAWEI, marcaConRisparmio("HONOR"))
        assertEquals(MarcaConRisparmio.OPPO_ONEPLUS, marcaConRisparmio("OPPO"))
        assertEquals(MarcaConRisparmio.OPPO_ONEPLUS, marcaConRisparmio("realme"))
        assertEquals(MarcaConRisparmio.OPPO_ONEPLUS, marcaConRisparmio("OnePlus"))
        assertEquals(MarcaConRisparmio.VIVO, marcaConRisparmio("vivo"))
        assertEquals(MarcaConRisparmio.VIVO, marcaConRisparmio("iQOO"))
        assertEquals(MarcaConRisparmio.SAMSUNG, marcaConRisparmio("samsung"))
        assertEquals(MarcaConRisparmio.SAMSUNG, marcaConRisparmio(" Samsung "))
    }

    @Test
    fun `le altre marche non hanno un passo in piu`() {
        assertNull(marcaConRisparmio("Google"))
        assertNull(marcaConRisparmio("motorola"))
        assertNull(marcaConRisparmio("Fairphone"))
        assertNull(marcaConRisparmio(""))
        assertNull(marcaConRisparmio(null))
    }
}
