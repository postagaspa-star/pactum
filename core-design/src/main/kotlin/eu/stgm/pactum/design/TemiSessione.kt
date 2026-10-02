package eu.stgm.pactum.design

import androidx.compose.ui.graphics.Color
import java.text.Normalizer
import java.util.Locale

// (0.12) Il tema di una Sessione, scelto DA SOLO dal suo nome (decisione di
// Andrea, 02/10): le emoji delle pagine animate di inizio e fine e il colore
// di fondo. Un posto solo per le due app, come il resto di core-design: niente
// risorse qui dentro, solo logica pura e colori. Test in
// app-figlio/app/src/test/.../design/.

/**
 * Un tema: le sue emoji (la prima è quella grande, al centro) e il colore di
 * fondo della pagina, chiaro e scuro. I fondi sono pastello della stessa
 * famiglia della palette di Pactum: sopra ci vanno una scheda chiara e gli
 * adesivi col bordo bianco, che si leggono su tutti e due. Pactum oggi va
 * sempre su fondo chiaro (scelta di Andrea): lo scuro è pronto se un giorno serve.
 */
enum class TemaSessione(
    val emoji: List<String>,
    val sfondoChiaro: Color,
    val sfondoScuro: Color,
    private val parole: List<String>,
) {
    STUDIO(
        // 📚 ✏️ 📐 🧠 🎓 📝
        emoji = listOf("📚", "✏️", "📐", "🧠", "🎓", "📝"),
        sfondoChiaro = Color(0xFFCFE9E1),
        sfondoScuro = Color(0xFF123B32),
        parole = listOf(
            "studio", "studiare", "compiti", "compito", "scuola", "ripasso", "ripassare", "verifica",
            "interrogazione", "esame", "lezione", "matematica", "latino", "inglese", "storia",
            // Le altre materie di scuola: un nome così è studio anche lui.
            "italiano", "francese", "spagnolo", "tedesco", "greco", "geografia", "fisica", "chimica",
            "scienze", "biologia", "filosofia", "economia", "diritto",
        ),
    ),
    LETTURA(
        // 📖 🔖 📚 ☕
        emoji = listOf("📖", "🔖", "📚", "☕"),
        sfondoChiaro = Color(0xFFF3E3C4),
        sfondoScuro = Color(0xFF3B2F14),
        parole = listOf("lettura", "leggere", "libro"),
    ),
    SPORT(
        // ⚽ 🏀 🏃 💪 🥇 👟
        emoji = listOf("⚽", "🏀", "🏃", "💪", "🥇", "👟"),
        sfondoChiaro = Color(0xFFDDECC8),
        sfondoScuro = Color(0xFF26361A),
        parole = listOf(
            "sport", "allenamento", "allenarsi", "palestra", "calcio", "basket", "pallavolo", "corsa",
            "correre", "nuoto", "nuotare", "bici",
        ),
    ),
    MUSICA(
        // 🎵 🎸 🎹 🎧 🎤
        emoji = listOf("🎵", "🎸", "🎹", "🎧", "🎤"),
        sfondoChiaro = Color(0xFFD7E5F2),
        sfondoScuro = Color(0xFF1A3047),
        parole = listOf("musica", "chitarra", "piano", "pianoforte", "canto", "cantare", "batteria", "basso"),
    ),
    ARTE(
        // 🎨 🖌️ ✏️ 🖼️
        emoji = listOf("🎨", "🖌️", "✏️", "🖼️"),
        sfondoChiaro = Color(0xFFF7E0CF),
        sfondoScuro = Color(0xFF45291C),
        parole = listOf("arte", "disegno", "disegnare", "pittura", "dipingere"),
    ),
    RELAX(
        // 🧘 🌿 ☁️ 😌
        emoji = listOf("🧘", "🌿", "☁️", "😌"),
        sfondoChiaro = Color(0xFFDCEEEA),
        sfondoScuro = Color(0xFF183A36),
        parole = listOf("relax", "meditazione", "meditare", "riposo", "riposare"),
    ),
    PROGRAMMAZIONE(
        // 💻 💡 🚀
        emoji = listOf("💻", "💡", "🚀"),
        sfondoChiaro = Color(0xFFDDE3EA),
        sfondoScuro = Color(0xFF1E2A36),
        parole = listOf("programmazione", "programmare", "coding", "progetto"),
    ),

    /** Tutto il resto: le stelline. */
    STELLINE(
        // ✨ ⭐ 🌟 💫
        emoji = listOf("✨", "⭐", "🌟", "💫"),
        sfondoChiaro = Color(0xFFF0E8D2),
        sfondoScuro = Color(0xFF2E2A1C),
        parole = emptyList(),
    ),
    ;

    /**
     * Le parole che lo fanno scegliere, con i loro plurali ("verifiche",
     * "esami", "libri"). Pigre: le voci di un enum nascono prima di tutto il
     * resto della classe.
     */
    private val forme: Set<String> by lazy { parole.flatMap { listOf(it, ParoleSessione.plurale(it)) }.toSet() }

    /** L'emoji grande, nella scheda al centro. */
    val emojiPrincipale: String get() = emoji.first()

    companion object {

        /**
         * Il tema dal nome della sessione: maiuscole, accenti e punteggiatura
         * non contano ("Matemàtica!" = matematica). Decide la prima parola
         * che si riconosce ("Studio di musica" è studio); nessuna = le stelline.
         */
        fun daNome(nome: String): TemaSessione {
            for (parola in paroleDi(nome)) {
                for (tema in values()) {
                    if (parola in tema.forme) return tema
                }
            }
            return STELLINE
        }

        /** Il nome in parole: minuscole, senza accenti, divise da tutto ciò che non è una lettera o una cifra. */
        fun paroleDi(nome: String): List<String> = ParoleSessione.parole(nome)
    }
}

/** Le parole di un nome di sessione (logica pura, a parte: nasce quando serve, non con l'enum). */
private object ParoleSessione {

    private val SEGNI = Regex("\\p{Mn}+")
    private val NON_PAROLA = Regex("[^\\p{L}\\p{N}]+")
    private val VERBO = Regex(".*(are|ere|ire|arsi)")

    fun parole(nome: String): List<String> {
        val senzaAccenti = SEGNI.replace(Normalizer.normalize(nome, Normalizer.Form.NFD), "")
        return NON_PAROLA.split(senzaAccenti.lowercase(Locale.ROOT)).filter { it.isNotEmpty() }
    }

    /**
     * Il plurale italiano più semplice: -ca → -che, -ga → -ghe, -o/-e → -i,
     * -a → -e. I verbi (-are, -ere, -ire) e le parole straniere restano come sono.
     */
    fun plurale(parola: String): String = when {
        parola.length < 4 || VERBO.matches(parola) -> parola
        parola.endsWith("ca") || parola.endsWith("ga") -> parola.dropLast(1) + "he"
        parola.endsWith("o") || parola.endsWith("e") -> parola.dropLast(1) + "i"
        parola.endsWith("a") -> parola.dropLast(1) + "e"
        else -> parola
    }
}
