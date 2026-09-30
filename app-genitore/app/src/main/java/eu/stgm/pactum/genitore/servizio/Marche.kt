package eu.stgm.pactum.genitore.servizio

import java.util.Locale

/**
 * (0.9) Le marche che, oltre ad Android, hanno un risparmio batteria loro: può
 * fermare Pactum anche con l'esenzione data. Per ciascuna le Impostazioni
 * mostrano un passo in parole semplici (strings.xml, `marca_*`).
 */
enum class MarcaConRisparmio { XIAOMI, HUAWEI, OPPO_ONEPLUS, VIVO, SAMSUNG }

/**
 * La marca del telefono da Build.MANUFACTURER ("Xiaomi", "HUAWEI", "OnePlus"…),
 * null = una marca senza un risparmio batteria suo noto (Pixel, Motorola, …):
 * allora basta l'esenzione di Android. Logica pura, provata in MarcheTest.
 */
fun marcaConRisparmio(produttore: String?): MarcaConRisparmio? {
    val nome = produttore?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when {
        nome.isEmpty() -> null
        listOf("xiaomi", "redmi", "poco").any { it in nome } -> MarcaConRisparmio.XIAOMI
        listOf("huawei", "honor").any { it in nome } -> MarcaConRisparmio.HUAWEI
        listOf("oppo", "realme", "oneplus").any { it in nome } -> MarcaConRisparmio.OPPO_ONEPLUS
        listOf("vivo", "iqoo").any { it in nome } -> MarcaConRisparmio.VIVO
        "samsung" in nome -> MarcaConRisparmio.SAMSUNG
        else -> null
    }
}
