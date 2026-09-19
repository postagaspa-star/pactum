package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.R
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Le parole VERE dell'app per i test JVM: legge res/values/strings.xml e
 * formatta come Resources.getString. Così un test prova la frase che il
 * genitore leggerà davvero, non una stringa finta scritta apposta per il test.
 *
 * I test JVM girano nella cartella del modulo (app/), da cui il percorso.
 */
object ParoleDiProva : Parole {

    private val valori: Map<String, String> by lazy {
        val documento = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val nodi = documento.getElementsByTagName("string")
        (0 until nodi.length)
            .map { nodi.item(it) as Element }
            .associate { it.getAttribute("name") to sbroglia(it.textContent) }
    }

    private val nomiPerId: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    override fun testo(id: Int, vararg argomenti: Any): String {
        val nome = nomiPerId[id] ?: error("id $id non è in R.string")
        val grezzo = valori[nome] ?: error("'$nome' non è in strings.xml")
        return String.format(Locale.ITALY, grezzo, *argomenti)
    }

    // Gli escape di strings.xml che l'app usa davvero: \' \" \n.
    private fun sbroglia(testo: String): String =
        testo.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
}
