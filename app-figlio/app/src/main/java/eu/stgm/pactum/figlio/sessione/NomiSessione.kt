package eu.stgm.pactum.figlio.sessione

import android.content.Context
import eu.stgm.pactum.design.TemaSessione
import eu.stgm.pactum.figlio.R

/** (0.12) "📚 Studio": il nome di una sessione in testa a una riga (l'elenco delle sessioni). */
fun nomeSessioneConEmoji(context: Context, nome: String): String =
    NomiSessione.inTesta(nome, context.getString(R.string.sessione_con_emoji))

/**
 * (0.12) "📚 «Studio»": il nome di una sessione dentro una frase, da passare
 * alle frasi delle sessioni (strings.xml lo riceve già così). [conEmoji] =
 * false per «Studio» e basta (seconda volta nella stessa schermata).
 */
fun nomeSessioneTraVirgolette(context: Context, nome: String, conEmoji: Boolean = true): String =
    NomiSessione.traVirgolette(
        nome = nome,
        conEmoji = conEmoji,
        virgolette = context.getString(R.string.sessione_nome_tra_virgolette),
        formatoEmoji = context.getString(R.string.sessione_con_emoji),
    )

/**
 * (0.12) Il nome di una sessione con l'emoji del suo tema, con la stessa
 * regola dell'app del genitore (logica pura; i formati arrivano da
 * strings.xml): la prima emoji del tema scelto dal nome (TemaSessione,
 * core-design) accanto al nome. In testa a una riga "📚 Studio"; dentro una
 * frase il nome esatto fra «», con l'emoji fuori: "📚 «Studio»". Se il nome
 * comincia già con quell'emoji ("📚 Ripasso") non la si ripete.
 */
object NomiSessione {

    /** I formati di sempre: "%1$s %2$s" (emoji, nome) e "«%1$s»". */
    const val CON_EMOJI = "%1\$s %2\$s"
    const val TRA_VIRGOLETTE = "«%1\$s»"

    /** L'emoji da mettere accanto a [nome]; null se il nome comincia già con lei. */
    fun emoji(nome: String?): String? {
        val emoji = TemaSessione.daNome(nome.orEmpty()).emojiPrincipale
        // Il selettore di variante (U+FE0F) non conta: con o senza, ⚽ resta la stessa emoji.
        return emoji.takeUnless { senzaVariante(nome.orEmpty().trim()).startsWith(senzaVariante(it)) }
    }

    /** In testa a una riga: "📚 Studio". */
    fun inTesta(nome: String, conEmoji: String = CON_EMOJI): String = conLaSuaEmoji(nome, nome.trim(), conEmoji)

    /**
     * Dentro una frase: "📚 «Studio»". [conEmoji] = false per una seconda
     * volta nella stessa schermata, o dove l'emoji del tema c'è già grande
     * (la pagina animata): solo «Studio».
     */
    fun traVirgolette(
        nome: String,
        conEmoji: Boolean = true,
        virgolette: String = TRA_VIRGOLETTE,
        formatoEmoji: String = CON_EMOJI,
    ): String {
        val scritto = virgolette.format(nome.trim())
        return if (conEmoji) conLaSuaEmoji(nome, scritto, formatoEmoji) else scritto
    }

    private fun conLaSuaEmoji(nome: String, scritto: String, formato: String): String =
        emoji(nome)?.let { formato.format(it, scritto) } ?: scritto

    private fun senzaVariante(testo: String): String = testo.replace("️", "")
}
