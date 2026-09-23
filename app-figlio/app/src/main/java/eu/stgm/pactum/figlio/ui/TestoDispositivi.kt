package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.figlio.dati.CambioDispositivo
import eu.stgm.pactum.figlio.dati.ContestoDispositivi
import eu.stgm.pactum.figlio.dati.Dispositivo
import eu.stgm.pactum.figlio.dati.GiornoStriscia
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiDispositivo
import eu.stgm.pactum.figlio.dati.inGiorniPatto
import java.util.Locale

// Logica pura della v3 (più dispositivi per figlio): come si chiamano le chiavi
// del computer, su quale dispositivo sta una regola, le righe "Computer: 5 su
// 7" sotto la striscia del figlio, "Collegato come". Niente Android: le parole
// arrivano da strings.xml come parametri, si prova con JUnit semplice.

/**
 * Le chiavi `app_o_categoria` del computer (contratto v3, Regole):
 * `exe:<nome del file>` per un programma, `sito:<dominio>` per il tempo su un
 * sito nel browser. Le `categoria:*` sono le stesse del telefono.
 */
object ChiaviComputer {

    const val PREFISSO_PROGRAMMA = "exe:"
    const val PREFISSO_SITO = "sito:"

    fun eProgramma(chiave: String): Boolean = chiave.startsWith(PREFISSO_PROGRAMMA, ignoreCase = true)

    fun eSito(chiave: String): Boolean = chiave.startsWith(PREFISSO_SITO, ignoreCase = true)

    /**
     * "exe:minecraft.exe" → "Minecraft": il nome leggibile se il server lo
     * manda, altrimenti il nome del file senza ".exe" con l'iniziale maiuscola.
     * Un nome del server uguale alla chiave (il ripiego del server quando non
     * sa il nome) non è un nome.
     */
    fun nomeProgramma(chiave: String, nomeServer: String? = null): String {
        nomeServer?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(chiave.trim(), ignoreCase = true) }
            ?.let { return it }
        val file = chiave.trim().drop(PREFISSO_PROGRAMMA.length).trim()
        val senzaEstensione = if (file.endsWith(".exe", ignoreCase = true)) file.dropLast(4).trim() else file
        if (senzaEstensione.isEmpty()) return chiave
        return senzaEstensione.replaceFirstChar { it.titlecase(Locale.ITALIAN) }
    }

    /** "sito:youtube.com" → "youtube.com". */
    fun dominio(chiave: String): String = chiave.trim().drop(PREFISSO_SITO.length).trim()

    /**
     * L'etichetta di una chiave del computer: "Minecraft", "youtube.com (sito)"
     * ([formatoSito] = "%1$s (sito)"). null se la chiave non è del computer
     * (pacchetto Android o categoria: le traduce il telefono).
     */
    fun etichetta(chiave: String, nomeServer: String?, formatoSito: String): String? = when {
        eProgramma(chiave) -> nomeProgramma(chiave, nomeServer)
        eSito(chiave) -> dominio(chiave).takeIf { it.isNotEmpty() }?.let { formatoSito.format(it) } ?: chiave
        else -> null
    }
}

/** Le parole per dire su quale dispositivo sta una regola, da strings.xml. */
data class ParoleDispositivo(
    /** "sul computer" */
    val sulComputer: String,
    /** "sul computer “%1$s”" */
    val sulComputerNome: String,
    /** "sul telefono “%1$s”" */
    val sulTelefonoNome: String,
    /** "sull'altro telefono" */
    val sullAltroTelefono: String,
    /** "su “%1$s”" */
    val suNome: String,
    /** "su un altro dispositivo" */
    val suAltro: String,
)

object TestoDispositivi {

    /**
     * Vero se la regola è di un ALTRO dispositivo del figlio. Vita reale
     * (nessun dispositivo) e server vecchi: no. Se il server non dice quale
     * dispositivo è questo, si sa solo che un computer non è questo telefono.
     */
    fun diUnAltro(regola: Regola, contesto: ContestoDispositivi): Boolean {
        val id = regola.idDispositivo ?: return false
        val questo = contesto.questo
        if (questo != null) return id != questo
        return tipoDi(regola, contesto) == TipiDispositivo.COMPUTER
    }

    /** Il tipo del dispositivo della regola: dalla regola, o dall'elenco dei dispositivi. */
    fun tipoDi(regola: Regola, contesto: ContestoDispositivi): String? =
        regola.dispositivo?.tipo?.takeIf { it.isNotBlank() }
            ?: contesto.dispositivi.firstOrNull { it.id == regola.idDispositivo }?.tipo?.takeIf { it.isNotBlank() }

    private fun nomeDi(regola: Regola, contesto: ContestoDispositivi): String =
        (
            regola.dispositivo?.nome?.takeIf { it.isNotBlank() }
                ?: contesto.dispositivi.firstOrNull { it.id == regola.idDispositivo }?.nome
            )?.trim().orEmpty()

    /**
     * Su quale dispositivo sta la regola, in minuscolo per stare dentro una
     * frase: "sul computer", "sul computer “Portatile”" (se i computer sono
     * più d'uno), "sul telefono “Vecchio”". Un altro TELEFONO porta sempre il
     * nome: "sul telefono" lo leggerebbe come questo. null se la regola è di
     * questo telefono o del figlio (vita reale): allora non si dice niente.
     */
    fun etichetta(regola: Regola, contesto: ContestoDispositivi, parole: ParoleDispositivo): String? {
        if (!diUnAltro(regola, contesto)) return null
        val tipo = tipoDi(regola, contesto)
        val nome = nomeDi(regola, contesto)
        return when (tipo) {
            TipiDispositivo.COMPUTER -> {
                val computer = contesto.dispositivi.count { !it.revocato && it.tipo == TipiDispositivo.COMPUTER }
                if (nome.isNotEmpty() && computer > 1) parole.sulComputerNome.format(nome) else parole.sulComputer
            }
            TipiDispositivo.TELEFONO ->
                if (nome.isNotEmpty()) parole.sulTelefonoNome.format(nome) else parole.sullAltroTelefono
            else -> if (nome.isNotEmpty()) parole.suNome.format(nome) else parole.suAltro
        }
    }

    /** L'iniziale maiuscola, per quando l'etichetta apre la frase: "Sul computer: …". */
    fun maiuscola(testo: String): String = testo.replaceFirstChar { it.titlecase(Locale.ITALIAN) }
}

/** Una riga sotto la striscia del figlio: un dispositivo e i suoi giorni dentro le regole. */
data class RigaDispositivo(
    val id: Long,
    val nome: String,
    /** È questo telefono: la riga dice "Questo telefono" invece del nome. */
    val questo: Boolean,
    val mantenuti: Int,
    val conDati: Int,
    /** Scollegato dal genitore: resta finché i suoi giorni stanno negli 8 della striscia. */
    val revocato: Boolean = false,
)

object RigheDispositivi {

    /**
     * Le righe sotto la striscia del figlio, SOLO se il figlio ha altri
     * dispositivi oltre a questo (con un telefono solo la striscia del figlio è
     * già la sua). Prima questo telefono, poi gli altri nell'ordine del server.
     * I numeri vengono dalla striscia di ciascun dispositivo, contati come la
     * frase grande ("6 su 7": i giorni senza dati escono dal conto). Per questo
     * telefono vale [strisciaQuesto] (`striscia_dispositivo`) se c'è, identica
     * a quella del genitore.
     *
     * Un dispositivo scollegato dal genitore resta finché ha giorni con dati
     * negli 8 della striscia: sono giorni che contano nella striscia del
     * figlio, e il genitore li vede. Poi esce: non ha più niente da dire.
     */
    fun calcola(
        dispositivi: List<Dispositivo>,
        questo: Long?,
        strisciaQuesto: List<GiornoStriscia> = emptyList(),
    ): List<RigaDispositivo> {
        val righe = dispositivi
            .distinctBy { it.id }
            .map { d ->
                val suo = d.id == questo
                riga(d, questo = suo, striscia = if (suo) strisciaQuesto.ifEmpty { d.striscia } else d.striscia)
            }
            .filter { !it.revocato || it.conDati > 0 }
        val altri = righe.filter { !it.questo }
        // Server che non dice chi è questo telefono: si vedono tutti, se sono almeno due.
        if (altri.isEmpty() || (questo == null && righe.size < 2)) return emptyList()
        return righe.filter { it.questo } + altri
    }

    private fun riga(d: Dispositivo, questo: Boolean, striscia: List<GiornoStriscia>): RigaDispositivo {
        val (mantenuti, conDati) = contaGiorni(striscia.inGiorniPatto())
        return RigaDispositivo(d.id, d.nome.trim(), questo, mantenuti, conDati, revocato = d.revocato)
    }
}

/** Le frasi dell'avviso "collegato a un dispositivo diverso da prima", da strings.xml. */
data class ParoleCambioDispositivo(
    /** "Questo telefono ora è collegato come «%1$s», un dispositivo nuovo: le regole e la storia di «%2$s» restano lì." */
    val conNomi: String,
    /** La stessa frase senza nomi ("… del dispositivo di prima …"). */
    val senzaNomi: String,
    /** "Se era lo stesso telefono, chiedi a tuo padre un «Nuovo codice» sulla riga di «%1$s»." */
    val consiglio: String,
    /** Lo stesso consiglio senza il nome ("… sulla riga del dispositivo di prima."). */
    val consiglioSenzaNome: String,
)

/**
 * L'avviso al posto di "Collegamento riuscito" quando il telefono è passato a
 * un dispositivo diverso da prima: cosa è successo e, se quello di prima c'è
 * ancora, come tornarci ("Nuovo codice" sulla sua riga). A un dispositivo
 * scollegato il genitore non può più dare un codice: allora il consiglio non
 * si dà. Se manca uno dei due nomi, la frase senza nomi: niente «» vuote.
 */
fun testoCambioDispositivo(cambio: CambioDispositivo, parole: ParoleCambioDispositivo): String {
    val nuovo = cambio.nomeNuovo.trim()
    val prima = cambio.nomePrima.trim()
    val conNomi = nuovo.isNotEmpty() && prima.isNotEmpty()
    val fatto = if (conNomi) parole.conNomi.format(nuovo, prima) else parole.senzaNomi
    if (cambio.primaScollegato) return fatto
    val consiglio = if (conNomi) parole.consiglio.format(prima) else parole.consiglioSenzaNome
    return "$fatto $consiglio"
}

/**
 * "Telefono di Andrea": il nome del dispositivo e quello del figlio
 * ([formato] = "%1$s di %2$s"). Se il nome del dispositivo dice già il figlio
 * ("Telefono di Andrea"), non lo si ripete. null se non si sa niente.
 */
fun collegatoCome(nomeDispositivo: String?, nomeFiglio: String?, formato: String): String? {
    val dispositivo = nomeDispositivo?.trim().orEmpty()
    val figlio = nomeFiglio?.trim().orEmpty()
    return when {
        dispositivo.isEmpty() && figlio.isEmpty() -> null
        dispositivo.isEmpty() -> figlio
        figlio.isEmpty() || dispositivo.contains(figlio, ignoreCase = true) -> dispositivo
        else -> formato.format(dispositivo, figlio)
    }
}
