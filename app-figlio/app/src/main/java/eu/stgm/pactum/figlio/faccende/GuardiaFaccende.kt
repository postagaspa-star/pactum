package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.sessione.ClassiAttivita

// (0.13) La barriera delle faccende, logica pura: quando coprire l'app in
// primo piano mentre il telefono è bloccato. Come la barriera delle Sessioni
// (GuardiaSessione), con una lista diversa e più stretta: qui non ci sono "le
// app della sessione", ci sono solo le app fondamentali che restano sempre
// usabili (SempreUsabiliFaccende). Tutto il resto si copre:
//  - anche durante una chiamata (decisione di Andrea): restano libere solo le
//    app dell'elenco, cioè il Telefono e la sua schermata di chiamata, e solo
//    se sono app di sistema. Una chiamata via internet continua in sottofondo
//    e si chiude dalla notifica; WhatsApp, Discord o un gioco che tengono
//    l'audio "in chiamata" si coprono come sempre;
//  - una pagina web aperta in una scheda del browser (Custom Tab) è libera
//    solo se l'ha aperta un'app dell'elenco, appena prima;
//  - un'app senza icona è libera solo se è di sistema (una "calcolatrice
//    cassaforte" o un browser nascosto si coprono).
// Per il resto la stessa prudenza delle Sessioni: se non si sa chi c'è
// davanti, o mancano i permessi, NON si copre.

/** Perché la barriera delle faccende copre, o non copre: lo dicono i test. */
enum class MotivoFaccende {
    NESSUN_BLOCCO,
    SCHERMO_SPENTO,
    BLOCCATO,
    SENZA_MOSTRA_SOPRA,
    SENZA_ACCESSO_USO,
    PRIMO_PIANO_IGNOTO,
    SEMPRE_USABILE,

    /** Una pagina web aperta da un'app dell'elenco, appena prima: fa parte di quell'app. */
    PARTE_DI_UN_APP,

    /** Un pezzo di sistema senza icona (servizi, selettori, finestre dei permessi). */
    NON_CONTA,
    INCERTO,
    PRIMA_LE_FACCENDE,
}

data class DecisioneFaccende(val copri: Boolean, val motivo: MotivoFaccende)

/** Tutto quello che serve per decidere, letto dal telefono un attimo prima. */
data class SituazioneFaccende(
    /** Il blocco c'è adesso (MemoriaBlocco.attivoAdesso). */
    val bloccoAttivo: Boolean,
    /** Il pacchetto in primo piano; null = non si sa. */
    val primoPiano: String?,
    val schermoAcceso: Boolean,
    val sbloccato: Boolean,
    val mostraSopra: Boolean,
    val accessoUso: Boolean,
    /** Le app sempre usabili durante il blocco (SempreUsabiliFaccende). */
    val sempreUsabili: Set<String>,
    /** L'app ha un'icona fra le app (conta nell'uso); null = non si sa. */
    val contaNellUso: (String) -> Boolean?,
    /** L'app è di sistema (installata col telefono); null = non si sa. */
    val diSistema: (String) -> Boolean?,
    /** La schermata (classe dell'activity) in primo piano, se si sa. */
    val classe: String? = null,
    /** L'app che era davanti appena prima di questa (chi ha aperto una pagina web), se si sa. */
    val precedente: String? = null,
    /** Le schermate Home (e le app recenti che stanno lì): da lì una pagina web non si apre "per un'app". */
    val home: Set<String> = emptySet(),
)

object GuardiaFaccende {

    /** Da qui non si apre una pagina "per conto di un'app": il sistema stesso, le finestre di scelta. */
    private val NON_APRONO_PAGINE = setOf("android", "com.android.systemui", "com.android.intentresolver")

    /**
     * Si copre se il telefono è bloccato, lo schermo acceso e sbloccato, ci
     * sono i due permessi, e davanti c'è un'app che si conosce, che non è tra
     * le sempre usabili, che non è una pagina web aperta da una di loro e che
     * non è un pezzo di sistema senza icona. Una chiamata non cambia niente.
     * Qualunque errore = non si copre.
     */
    fun decidi(s: SituazioneFaccende): DecisioneFaccende = try {
        decidiDentro(s)
    } catch (e: Exception) {
        lascia(MotivoFaccende.INCERTO)
    }

    private fun decidiDentro(s: SituazioneFaccende): DecisioneFaccende {
        if (!s.bloccoAttivo) return lascia(MotivoFaccende.NESSUN_BLOCCO)
        if (!s.schermoAcceso) return lascia(MotivoFaccende.SCHERMO_SPENTO)
        if (!s.sbloccato) return lascia(MotivoFaccende.BLOCCATO)
        if (!s.mostraSopra) return lascia(MotivoFaccende.SENZA_MOSTRA_SOPRA)
        if (!s.accessoUso) return lascia(MotivoFaccende.SENZA_ACCESSO_USO)
        val app = s.primoPiano?.trim()?.takeIf { it.isNotEmpty() } ?: return lascia(MotivoFaccende.PRIMO_PIANO_IGNOTO)
        if (app in s.sempreUsabili) return lascia(MotivoFaccende.SEMPRE_USABILE)
        if (ClassiAttivita.aiutoDiUnApp(app, s.classe) && apertaDaUnApp(s, app)) return lascia(MotivoFaccende.PARTE_DI_UN_APP)
        if (s.contaNellUso(app) != true) {
            // Senza icona: libera solo se è di sistema.
            when (s.diSistema(app)) {
                true -> return lascia(MotivoFaccende.NON_CONTA)
                null -> return lascia(MotivoFaccende.INCERTO)
                false -> Unit
            }
        }
        return DecisioneFaccende(copri = true, motivo = MotivoFaccende.PRIMA_LE_FACCENDE)
    }

    /** La pagina web l'ha aperta, appena prima, un'app dell'elenco (non la Home, non le Recenti). */
    private fun apertaDaUnApp(s: SituazioneFaccende, app: String): Boolean {
        val prima = s.precedente?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return prima != app && prima in s.sempreUsabili && prima !in s.home && prima !in NON_APRONO_PAGINE
    }

    private fun lascia(motivo: MotivoFaccende) = DecisioneFaccende(copri = false, motivo = motivo)
}
