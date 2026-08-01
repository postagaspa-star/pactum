package eu.stgm.pactum.figlio.siti

/**
 * Dal nome chiesto al DNS al **dominio che una persona riconosce** — il filtro
 * e l'aggregazione previsti dal contratto (docs/contratto-api.md, sezione
 * "Siti visitati"):
 *
 *  - aggregazione sul **dominio registrabile**: `scontent.cdninstagram.com` →
 *    `instagram.com`, `m.youtube.com` → `youtube.com`. Un sottodominio non
 *    diventa mai una riga a sé: elencare i sottodomini direbbe cose sul
 *    CONTENUTO, e il contenuto è fuori dal patto;
 *  - i **domini tecnici non entrano**: CDN, analytics, telemetria, pubblicità,
 *    controlli di connettività, certificati. Nel registro finisce ciò che una
 *    persona riconoscerebbe come "un sito che ho visitato", non il rumore di
 *    rete.
 *
 * Il filtro vive QUI, nell'app del figlio, in chiaro: il figlio può leggerlo e
 * discuterlo (contratto: "il filtro vive nell'app del figlio, quindi il figlio
 * può vederlo e discuterlo").
 *
 * Non è la Public Suffix List completa (megabyte di dati aggiornati di
 * continuo): è la lista dei suffissi a due livelli che si incontrano davvero,
 * più la regola generale "ultime due etichette". Un errore qui accorpa o
 * separa un dominio, non rompe niente.
 */
object Domini {

    /** Tetto di domini per fotografia (contratto: i 200 più richiesti del giorno). */
    const val LIMITE_DOMINI_FOTOGRAFIA = 200

    /**
     * Suffissi a due livelli: sotto questi il dominio registrabile ha TRE
     * etichette (`bbc.co.uk`, non `co.uk`). Lista corta e concreta.
     */
    private val SUFFISSI_DOPPI = setOf(
        // Regno Unito
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "sch.uk", "ltd.uk", "plc.uk",
        // Australia / Nuova Zelanda
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au",
        "co.nz", "net.nz", "org.nz", "ac.nz", "govt.nz",
        // America latina
        "com.br", "net.br", "org.br", "gov.br", "edu.br",
        "com.ar", "com.mx", "com.co", "com.pe", "com.uy", "com.ve", "com.ec",
        // Asia
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
        "co.kr", "or.kr", "ne.kr",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "com.tw", "com.hk", "com.sg", "com.my", "com.ph", "com.vn",
        "co.in", "net.in", "org.in", "co.id", "or.id",
        // Europa / altro
        "co.il", "com.tr", "com.ua", "com.ru", "com.pl", "com.es", "com.pt",
        "co.za", "com.sa", "com.eg", "gov.it", "edu.it",
    )

    /**
     * CDN e domini "di servizio" che appartengono a un sito riconoscibile: si
     * ACCORPANO al marchio invece di sparire, altrimenti l'uso vero (l'app di
     * Instagram parla quasi solo con `cdninstagram.com`) resterebbe invisibile.
     * Il contratto lo chiede alla lettera: `scontent.cdninstagram.com` →
     * `instagram.com`.
     */
    private val ALIAS = mapOf(
        "cdninstagram.com" to "instagram.com",
        "fbcdn.net" to "facebook.com",
        "facebook.net" to "facebook.com",
        "fbsbx.com" to "facebook.com",
        "whatsapp.net" to "whatsapp.com",
        "ytimg.com" to "youtube.com",
        "googlevideo.com" to "youtube.com",
        "youtu.be" to "youtube.com",
        "youtube-nocookie.com" to "youtube.com",
        "tiktokcdn.com" to "tiktok.com",
        "tiktokcdn-us.com" to "tiktok.com",
        "tiktokv.com" to "tiktok.com",
        "byteoversea.com" to "tiktok.com",
        "ibytedtos.com" to "tiktok.com",
        "twimg.com" to "x.com",
        "t.co" to "x.com",
        "twitter.com" to "x.com",
        "redd.it" to "reddit.com",
        "redditmedia.com" to "reddit.com",
        "redditstatic.com" to "reddit.com",
        "discordapp.com" to "discord.com",
        "discordapp.net" to "discord.com",
        "discord.media" to "discord.com",
        "discord.gg" to "discord.com",
        "scdn.co" to "spotify.com",
        "spotifycdn.com" to "spotify.com",
        "licdn.com" to "linkedin.com",
        "pinimg.com" to "pinterest.com",
        "sc-cdn.net" to "snapchat.com",
        "snapchat.net" to "snapchat.com",
        "snap.com" to "snapchat.com",
        "ttvnw.net" to "twitch.tv",
        "jtvnw.net" to "twitch.tv",
        "twitchcdn.net" to "twitch.tv",
        "nflxvideo.net" to "netflix.com",
        "nflxso.net" to "netflix.com",
        "nflximg.net" to "netflix.com",
        "media-amazon.com" to "amazon.com",
        "ssl-images-amazon.com" to "amazon.com",
        "primevideo.com" to "amazon.com",
        "rbxcdn.com" to "roblox.com",
        "steamstatic.com" to "steampowered.com",
        "steamcontent.com" to "steampowered.com",
        "steamcommunity.com" to "steampowered.com",
        "epicgames.dev" to "epicgames.com",
        "cdn-telegram.org" to "telegram.org",
        "t.me" to "telegram.org",
        "telegram.me" to "telegram.org",
        "wp.com" to "wordpress.com",
        "wikimedia.org" to "wikipedia.org",
        "githubusercontent.com" to "github.com",
        "githubassets.com" to "github.com",
        "openai.com" to "chatgpt.com",
        "oaistatic.com" to "chatgpt.com",
        "shopifycdn.com" to "shopify.com",
        "pstatic.net" to "naver.com",
    )

    /**
     * Domini registrabili che NON sono "un sito visitato": CDN generiche,
     * analytics, telemetria, pubblicità, servizi di sistema di Google/Apple,
     * infrastruttura dei certificati. Sono il rumore che renderebbe la lista
     * del genitore illeggibile.
     */
    private val RUMORE = setOf(
        // Google / Android di sistema
        "googleapis.com", "gstatic.com", "ggpht.com", "googleusercontent.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "googlesyndication.com", "googleadservices.com", "doubleclick.net",
        "googlezip.net", "gvt1.com", "gvt2.com", "gvt3.com", "android.com",
        "pki.goog", "goog", "google-play.com", "dl.google.com", "crashlytics.com",
        "firebaseio.com", "firebase.com", "firebaseinstallations.com",
        "app-measurement.com", "googlehosted.com", "1e100.net", "gmail.com",
        // Apple / Microsoft di sistema
        "mzstatic.com", "aaplimg.com", "cdn-apple.com", "push.apple.com",
        "msftconnecttest.com", "msftncsi.com", "windowsupdate.com",
        "msedge.net", "msecnd.net", "azureedge.net", "azurefd.net",
        "trafficmanager.net", "windows.net", "office.net", "appcenter.ms",
        // CDN generiche
        "akamai.net", "akamaiedge.net", "akamaized.net", "akadns.net", "akamaihd.net",
        "edgekey.net", "edgesuite.net", "cloudfront.net", "fastly.net", "fastlylb.net",
        "cloudflare.com", "cloudflare.net", "cloudflare-dns.com", "cdn77.org",
        "llnwd.net", "stackpathdns.com", "cachefly.net", "jsdelivr.net", "unpkg.com",
        "bootstrapcdn.com", "cdnjs.com", "gcdn.co", "hwcdn.net", "cdn.net",
        "wpengine.com", "incapdns.net", "impervadns.net",
        // Analytics / telemetria / crash
        "segment.io", "segment.com", "amplitude.com", "mixpanel.com", "branch.io",
        "appsflyer.com", "adjust.com", "adjust.io", "kochava.com", "sentry.io",
        "bugsnag.com", "newrelic.com", "nr-data.net", "datadoghq.com",
        "scorecardresearch.com", "quantserve.com", "chartbeat.net", "hotjar.com",
        "optimizely.com", "onesignal.com", "urbanairship.com", "airship.com",
        "braze.com", "appboy.com", "flurry.com", "clarity.ms", "fullstory.com",
        "launchdarkly.com", "split.io", "statsigapi.net", "mparticle.com",
        // Pubblicità
        "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "amazon-adsystem.com", "casalemedia.com", "openx.net", "smaato.net",
        "mopub.com", "moatads.com", "yieldmo.com", "teads.tv", "smartadserver.com",
        "33across.com", "id5-sync.com", "sharethrough.com", "indexww.com",
        "applovin.com", "adcolony.com", "chartboost.com", "vungle.com",
        "supersonicads.com", "ironsrc.com", "inmobi.com", "unity3d.com",
        "bidswitch.net", "everesttech.net", "demdex.net", "omtrdc.net",
        "adsafeprotected.com", "doubleverify.com", "serving-sys.com",
        // Certificati, tempo, rete
        "digicert.com", "letsencrypt.org", "sectigo.com", "comodoca.com",
        "usertrust.com", "globalsign.com", "verisign.com", "entrust.net",
        "amazontrust.com", "godaddy.com", "ntp.org", "pool.ntp.org",
        "root-servers.net", "in-addr.arpa", "gtld-servers.net",
        // Resolver DNS cifrati (la loro presenza si tratta a parte, v. [eResolverCifrato])
        "nextdns.io", "adguard-dns.com", "quad9.net", "opendns.com", "dns.sb",
    )

    /**
     * Parole che, dentro il dominio registrabile, dicono da sole "sono
     * infrastruttura": euristica di rincalzo per ciò che la lista non copre.
     */
    private val PAROLE_RUMORE = listOf(
        "analytics", "telemetry", "telemetria", "tracker", "tracking",
        "crashlytics", "adservice", "adserver", "adsystem", "metrics",
    )

    /**
     * Sottodomini di SERVIZIO su domini per il resto veri (`google.com` è un
     * sito vero, `mtalk.google.com` è la messaggistica di sistema di Android).
     * Si filtrano PRIMA dell'aggregazione, altrimenti il rumore di sistema
     * gonfierebbe un dominio legittimo.
     */
    private val PREFISSI_TECNICI = listOf(
        "mtalk.", "alt1-mtalk.", "alt2-mtalk.", "alt3-mtalk.", "alt4-mtalk.",
        "alt5-mtalk.", "alt6-mtalk.", "alt7-mtalk.", "alt8-mtalk.",
        "clients1.", "clients2.", "clients3.", "clients4.", "clients5.", "clients6.",
        "connectivitycheck.", "safebrowsing.", "captive.", "detectportal.",
        "ocsp.", "crl.", "pki.", "ntp.", "time.", "csi.", "pagead.", "googleads.",
        "ads.", "adservice.", "beacon.", "telemetry.", "metrics.", "analytics.",
        "crashlytics.", "sentry.", "logs.", "collector.", "incoming.",
    )

    /**
     * Nomi che, se qualcuno li chiede, dicono che sta per partire il **DNS
     * cifrato** (DoH: il browser risolve il nome del resolver e poi passa a
     * HTTPS, dove non vediamo più niente). Non è un dominio da registrare: è
     * il segnale che dobbiamo dichiararci ciechi (`dns_cifrato: true`).
     */
    private val RESOLVER_CIFRATI = setOf(
        "dns.google", "dns64.dns.google", "cloudflare-dns.com",
        "mozilla.cloudflare-dns.com", "chrome.cloudflare-dns.com", "one.one.one.one",
        "dns.quad9.net", "dns11.quad9.net", "dns9.quad9.net",
        "doh.opendns.com", "dns.nextdns.io", "dns.adguard.com",
        "dns.adguard-dns.com", "doh.dns.sb", "dns.alidns.com", "doh.cleanbrowsing.org",
        "security.cloudflare-dns.com", "family.cloudflare-dns.com",
    )

    /** true se il nome chiesto è il bootstrap di un resolver DNS cifrato. */
    fun eResolverCifrato(nome: String): Boolean {
        val h = normalizza(nome) ?: return false
        return h in RESOLVER_CIFRATI
    }

    /**
     * Il dominio da mettere nel registro per il nome chiesto al DNS, oppure
     * **null** se è rumore (o non è un nome sensato). È l'unica porta: tutto
     * ciò che il genitore vedrà è passato di qui.
     */
    fun dominioOsservabile(nome: String): String? {
        val h = normalizza(nome) ?: return null
        if (PREFISSI_TECNICI.any { h.startsWith(it) }) return null
        val registrabile = dominioRegistrabile(h) ?: return null
        val finale = ALIAS[registrabile] ?: registrabile
        if (finale in RUMORE) return null
        if (PAROLE_RUMORE.any { it in finale }) return null
        return finale
    }

    /**
     * Il nome ripulito (minuscolo, senza punto finale) se è un nome di dominio
     * pubblico plausibile; null per indirizzi IP, nomi di rete locale, nomi
     * malformati. Un nome senza punto (`nas`, `router`) è roba di casa: fuori.
     */
    fun normalizza(nome: String): String? {
        val h = nome.trim().lowercase().trimEnd('.')
        if (h.isEmpty() || h.length > 253) return null
        if (!h.contains('.')) return null
        if (h.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' && it != '_' }) return null
        if (h.contains("..")) return null
        // Indirizzo IPv4 scritto per esteso: non è un sito.
        if (h.all { it in '0'..'9' || it == '.' }) return null
        val fuoriInternet = listOf(
            ".local", ".lan", ".home", ".internal", ".localdomain", ".arpa",
            ".onion", ".test", ".invalid", ".example", ".localhost",
        )
        if (fuoriInternet.any { h.endsWith(it) }) return null
        return h
    }

    /**
     * Il dominio registrabile: ultime due etichette, tre se il suffisso è a
     * due livelli (`bbc.co.uk`). null se non restano abbastanza etichette.
     */
    fun dominioRegistrabile(host: String): String? {
        val parti = host.split('.').filter { it.isNotEmpty() }
        if (parti.size < 2) return null
        val ultimeDue = parti.takeLast(2).joinToString(".")
        if (ultimeDue in SUFFISSI_DOPPI) {
            if (parti.size < 3) return null
            return parti.takeLast(3).joinToString(".")
        }
        return ultimeDue
    }
}
