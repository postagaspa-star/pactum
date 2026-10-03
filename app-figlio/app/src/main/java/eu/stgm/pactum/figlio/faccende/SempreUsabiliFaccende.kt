package eu.stgm.pactum.figlio.faccende

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import eu.stgm.pactum.figlio.sessione.SempreUsabili

/**
 * (0.13) Le app sempre usabili durante il blocco delle faccende (contratto
 * v3.6, decisione di Andrea): quelle delle Sessioni (Pactum, la Home, la
 * tastiera, il sistema, le Impostazioni, le finestre dei permessi, la scelta
 * di file e foto, la fotocamera di sistema, l'installazione degli
 * aggiornamenti: SempreUsabili), con il **Telefono e la sua schermata di
 * chiamata solo se sono app di sistema**, e in più:
 *  - i **Contatti**;
 *  - **solo l'app degli SMS predefinita**, e solo se è di sistema: WhatsApp,
 *    Telegram, Signal, Messenger e le altre app di messaggi restano coperte
 *    sempre ([MESSAGGISTICA]);
 *  - **Google Wallet**, **eWeLink**, **Tinaba**, **Google Foto** (e i
 *    Contatti di Google): per nome, ma solo se sono di sistema o installate
 *    dal Play Store (un'app installata a mano con lo stesso nome si copre);
 *  - la **galleria** del telefono.
 *
 * Due strade, perché funzioni su telefoni diversi: i nomi dei pacchetti
 * verificati (Play Store e negozi delle marche: Google/Pixel, Motorola,
 * Samsung, Xiaomi) e i ruoli che il sistema dichiara a runtime (l'app degli
 * SMS, il Telefono, le app di sistema che aprono i contatti e le foto). I
 * nomi delle app di sistema delle marche valgono solo se l'app è davvero di
 * sistema.
 */
object SempreUsabiliFaccende {

    /** Il Play Store: chi installa le app "per nome" che valgono. */
    const val PLAY_STORE = "com.android.vending"

    /** Per nome, se sono di sistema o installate dal Play Store: le app scelte da Andrea. */
    val PER_NOME: Set<String> = setOf(
        // Google Wallet
        "com.google.android.apps.walletnfcrel",
        // eWeLink (CoolKit Technology)
        "com.coolkit",
        // Tinaba
        "it.tinaba.app",
        // Google Foto
        "com.google.android.apps.photos",
        // Contatti di Google (Pixel, Motorola, Android One)
        "com.google.android.contacts",
    )

    /** Per nome, ma solo se sono app di sistema: le gallerie, i Contatti e le fotocamere delle marche. */
    val DI_SISTEMA_PER_NOME: Set<String> = setOf(
        // Gallerie: Samsung, Xiaomi, quella di Android, Galleria Go di Google.
        "com.sec.android.gallery3d",
        "com.miui.gallery",
        "com.android.gallery3d",
        "com.google.android.apps.photosgo",
        // Contatti: Samsung, Xiaomi e Android (su Xiaomi è anche il Telefono).
        "com.samsung.android.app.contacts",
        "com.samsung.android.contacts",
        "com.android.contacts",
        // Fotocamere: Pixel, Motorola, Samsung, Xiaomi e Android.
        "com.google.android.GoogleCamera",
        "com.motorola.camera3",
        "com.sec.android.app.camera",
        "com.android.camera",
        "com.android.camera2",
    )

    /**
     * Il Telefono e le chiamate fra quelle delle Sessioni (SempreUsabili.FISSE):
     * durante il blocco valgono solo se sono app di sistema.
     */
    val TELEFONO_PER_NOME: Set<String> = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.stk",
        "com.android.stk2",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.samsung.android.emergency",
        "com.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module",
        "com.google.android.cellbroadcastreceiver",
    )

    /**
     * Le app di messaggi: mai usabili durante il blocco, qualunque ruolo
     * dichiarino. Restano coperte anche se una di loro diventasse l'app degli
     * SMS predefinita (le vecchie versioni di Signal potevano esserlo).
     */
    val MESSAGGISTICA: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.facebook.mlite",
        "com.facebook.katana",
        "com.instagram.android",
        "com.instagram.barcelona",
        "com.snapchat.android",
        "com.discord",
        "com.viber.voip",
        "jp.naver.line.android",
        "com.skype.raider",
        "com.microsoft.teams",
        "kik.android",
        "com.tencent.mm",
        "com.kakao.talk",
        "im.vector.app",
        "ch.threema.app",
        "ch.threema.app.libre",
        "com.wire",
        "network.loki.messenger",
        "org.briarproject.briar.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
    )

    /**
     * L'insieme finale (logica pura): quelle delle Sessioni ([base], già
     * passate da [filtraTelefono]), quelle per nome già controllate
     * ([perNome], [diSistemaPerNome]), quelle trovate dai ruoli ([ruoli]) e
     * l'app degli SMS predefinita ([smsPredefinita], già controllata); mai le
     * app di messaggi. [pactum] c'è sempre: "Apri Pactum" deve portare in un
     * posto libero.
     */
    fun unisci(
        base: Set<String>,
        ruoli: Set<String>,
        smsPredefinita: String?,
        pactum: String,
        diSistemaPerNome: Set<String> = emptySet(),
        perNome: Set<String> = PER_NOME,
    ): Set<String> {
        val tutte = HashSet<String>(base)
        tutte += perNome
        tutte += diSistemaPerNome
        tutte += ruoli
        smsPredefinita?.trim()?.takeIf { it.isNotEmpty() }?.let { tutte += it }
        tutte -= MESSAGGISTICA
        tutte -= ""
        tutte += pactum
        return tutte
    }

    /**
     * Il Telefono, le sue schermate di chiamata e il Telefono predefinito
     * ([telefono]) restano solo se sono di sistema ([diSistema] vero); il
     * resto delle Sessioni resta com'è (logica pura).
     */
    fun filtraTelefono(base: Set<String>, telefono: Set<String>, diSistema: (String) -> Boolean?): Set<String> =
        base.filterTo(HashSet()) { it !in telefono || diSistema(it) == true }

    /** Le app per nome che valgono: di sistema, o installate dal Play Store (logica pura). */
    fun perNomeValide(diSistema: (String) -> Boolean?, installatore: (String) -> String?): Set<String> =
        PER_NOME.filterTo(HashSet()) { diSistema(it) == true || (diSistema(it) == false && installatore(it) == PLAY_STORE) }

    /** L'app degli SMS predefinita vale solo se è di sistema (logica pura). */
    fun smsValida(sms: String?, diSistema: (String) -> Boolean?): String? =
        sms?.trim()?.takeIf { it.isNotEmpty() && diSistema(it) == true }

    /** Le domande al sistema: ognuna a parte, se una non risponde le altre restano. */
    fun leggi(context: Context): Set<String> {
        val app = context.applicationContext
        val pm = app.packageManager
        val sistema = GiudiceFaccende.classificatoreSistema(app)
        val base = try {
            SempreUsabili.leggi(app)
        } catch (e: Exception) {
            SempreUsabili.FISSE
        }
        // Il Telefono: quello predefinito e quello di sistema valgono solo se di
        // sistema. Ogni app che compone numeri, se non è di sistema, non vale.
        val dialer = HashSet(TELEFONO_PER_NOME)
        prova {
            app.getSystemService(TelecomManager::class.java)?.let { telecom ->
                telecom.defaultDialerPackage?.let { dialer += it }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) telecom.systemDialerPackage?.let { dialer += it }
            }
        }
        val telefono = HashSet(dialer)
        prova { telefono += pacchettiPer(pm, Intent(Intent.ACTION_DIAL)) }
        val ruoli = HashSet<String>()
        ruoli += dialer.filter { sistema(it) == true }
        // I Contatti: l'app che apre l'elenco dei contatti, e quella della categoria Contatti.
        prova { ruoli += diSistema(sistema, pacchettiPer(pm, Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))) }
        prova { ruoli += diSistema(sistema, pacchettiPer(pm, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS))) }
        // La galleria: l'app di sistema della categoria Galleria.
        prova { ruoli += diSistema(sistema, pacchettiPer(pm, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))) }
        // La fotocamera di sistema anche per i video e dal tasto rapido.
        for (azione in listOf(MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.ACTION_VIDEO_CAPTURE)) {
            prova { ruoli += diSistema(sistema, pacchettiPer(pm, Intent(azione))) }
        }
        val sms = try {
            smsValida(Telephony.Sms.getDefaultSmsPackage(app), sistema)
        } catch (e: Exception) {
            null
        }
        val perNomeSistema = DI_SISTEMA_PER_NOME.filterTo(HashSet()) { sistema(it) == true }
        val perNome = perNomeValide(sistema) { installatore(pm, it) }
        return unisci(filtraTelefono(base, telefono, sistema), ruoli, sms, app.packageName, perNomeSistema, perNome)
    }

    private inline fun prova(azione: () -> Unit) {
        try {
            azione()
        } catch (e: Exception) {
            // quella domanda non ha risposto: restano le altre
        }
    }

    private fun diSistema(sistema: (String) -> Boolean?, pacchetti: Collection<String>): Set<String> =
        pacchetti.filterTo(HashSet()) { it.isNotBlank() && it != "android" && sistema(it) == true }

    /** Chi ha installato l'app (il Play Store è com.android.vending); null se non si sa. */
    private fun installatore(pm: PackageManager, pacchetto: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pacchetto).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pacchetto)
        }
    } catch (e: Exception) {
        null
    }

    internal fun pacchettiPer(pm: PackageManager, intent: Intent): List<String> {
        val trovati = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return trovati.mapNotNull { it.activityInfo?.packageName }
    }
}

/**
 * (0.13) "Scatta la foto": Pactum apre direttamente la fotocamera DI SISTEMA
 * (anche su Android 8-10, dove qualsiasi app poteva rispondere), e solo lei
 * resta usabile mentre si scatta, al massimo [DURATA_MS]; finisce appena la
 * foto torna a Pactum.
 */
object ScattoInCorso {

    private const val DURATA_MS = 10L * 60 * 1000

    @Volatile
    private var pacchetti: Set<String> = emptySet()

    @Volatile
    private var fino: Long = 0L

    /**
     * La fotocamera di sistema che scatta per un'altra app: quella
     * predefinita, se è di sistema, altrimenti la prima di sistema. Null se
     * non ce n'è nessuna.
     */
    fun fotocameraDiSistema(context: Context): String? = try {
        val app = context.applicationContext
        val pm = app.packageManager
        val sistema = GiudiceFaccende.classificatoreSistema(app)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val predefinita = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }?.activityInfo?.packageName
        scegliFotocamera(predefinita, SempreUsabiliFaccende.pacchettiPer(pm, intent), sistema)
    } catch (e: Exception) {
        null
    }

    /** La scelta (logica pura): la predefinita se è di sistema, altrimenti la prima di sistema fra le [candidate]. */
    fun scegliFotocamera(predefinita: String?, candidate: List<String>, diSistema: (String) -> Boolean?): String? {
        if (predefinita != null && predefinita != "android" && diSistema(predefinita) == true) return predefinita
        return candidate.firstOrNull { it != "android" && it !in SempreUsabiliFaccende.MESSAGGISTICA && diSistema(it) == true }
    }

    fun inizia(pacchetto: String, adesso: Long = System.currentTimeMillis()) {
        pacchetti = setOf(pacchetto) - SempreUsabiliFaccende.MESSAGGISTICA
        fino = adesso + DURATA_MS
    }

    fun finisce() {
        pacchetti = emptySet()
        fino = 0L
    }

    fun attuali(adesso: Long = System.currentTimeMillis()): Set<String> = if (adesso < fino) pacchetti else emptySet()
}
