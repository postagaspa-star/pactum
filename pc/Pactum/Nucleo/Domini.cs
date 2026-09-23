namespace Pactum.Nucleo;

/// <summary>
/// Dal nome di un sito al dominio che una persona riconosce. Stesse regole
/// dell'app del telefono (app-figlio, siti/Domini.kt): dominio registrabile
/// (<c>m.youtube.com</c> → <c>youtube.com</c>, <c>bbc.co.uk</c> resta a tre
/// etichette), alias dei marchi (<c>youtu.be</c> → <c>youtube.com</c>).
/// Sul computer si aggiunge <see cref="DaBarraIndirizzi"/>: il testo della barra
/// degli indirizzi entra, esce solo il dominio.
/// </summary>
public static class Domini
{
    /// <summary>Tetto di domini per fotografia (contratto: i 200 più richiesti del giorno).</summary>
    public const int LimiteDominiFotografia = 200;

    private static readonly HashSet<string> SuffissiDoppi = new(StringComparer.Ordinal)
    {
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
    };

    /// <summary>CDN e domini di servizio accorpati al marchio (identici al telefono).</summary>
    private static readonly Dictionary<string, string> Alias = new(StringComparer.Ordinal)
    {
        ["cdninstagram.com"] = "instagram.com",
        ["fbcdn.net"] = "facebook.com",
        ["facebook.net"] = "facebook.com",
        ["fbsbx.com"] = "facebook.com",
        ["whatsapp.net"] = "whatsapp.com",
        ["ytimg.com"] = "youtube.com",
        ["googlevideo.com"] = "youtube.com",
        ["youtu.be"] = "youtube.com",
        ["youtube-nocookie.com"] = "youtube.com",
        ["tiktokcdn.com"] = "tiktok.com",
        ["tiktokcdn-us.com"] = "tiktok.com",
        ["tiktokv.com"] = "tiktok.com",
        ["byteoversea.com"] = "tiktok.com",
        ["ibytedtos.com"] = "tiktok.com",
        ["twimg.com"] = "x.com",
        ["t.co"] = "x.com",
        ["twitter.com"] = "x.com",
        ["redd.it"] = "reddit.com",
        ["redditmedia.com"] = "reddit.com",
        ["redditstatic.com"] = "reddit.com",
        ["discordapp.com"] = "discord.com",
        ["discordapp.net"] = "discord.com",
        ["discord.media"] = "discord.com",
        ["discord.gg"] = "discord.com",
        ["scdn.co"] = "spotify.com",
        ["spotifycdn.com"] = "spotify.com",
        ["licdn.com"] = "linkedin.com",
        ["pinimg.com"] = "pinterest.com",
        ["sc-cdn.net"] = "snapchat.com",
        ["snapchat.net"] = "snapchat.com",
        ["snap.com"] = "snapchat.com",
        ["ttvnw.net"] = "twitch.tv",
        ["jtvnw.net"] = "twitch.tv",
        ["twitchcdn.net"] = "twitch.tv",
        ["nflxvideo.net"] = "netflix.com",
        ["nflxso.net"] = "netflix.com",
        ["nflximg.net"] = "netflix.com",
        ["media-amazon.com"] = "amazon.com",
        ["ssl-images-amazon.com"] = "amazon.com",
        ["primevideo.com"] = "amazon.com",
        ["rbxcdn.com"] = "roblox.com",
        ["steamstatic.com"] = "steampowered.com",
        ["steamcontent.com"] = "steampowered.com",
        ["steamcommunity.com"] = "steampowered.com",
        ["epicgames.dev"] = "epicgames.com",
        ["cdn-telegram.org"] = "telegram.org",
        ["t.me"] = "telegram.org",
        ["telegram.me"] = "telegram.org",
        ["wp.com"] = "wordpress.com",
        ["wikimedia.org"] = "wikipedia.org",
        ["githubusercontent.com"] = "github.com",
        ["githubassets.com"] = "github.com",
        ["openai.com"] = "chatgpt.com",
        ["oaistatic.com"] = "chatgpt.com",
        ["shopifycdn.com"] = "shopify.com",
        ["pstatic.net"] = "naver.com",
    };

    /// <summary>
    /// Alias del telefono che sul computer NON si applicano: nella barra degli
    /// indirizzi <c>primevideo.com</c> è il sito stesso (un video), non una CDN di Amazon.
    /// </summary>
    private static readonly HashSet<string> AliasSoloTelefono = new(StringComparer.Ordinal) { "primevideo.com" };

    /// <summary>Rumore di rete del telefono (CDN, analytics, pubblicità…): identico a Domini.kt.</summary>
    private static readonly HashSet<string> Rumore = new(StringComparer.Ordinal)
    {
        "googleapis.com", "gstatic.com", "ggpht.com", "googleusercontent.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "googlesyndication.com", "googleadservices.com", "doubleclick.net",
        "googlezip.net", "gvt1.com", "gvt2.com", "gvt3.com", "android.com",
        "pki.goog", "goog", "google-play.com", "dl.google.com", "crashlytics.com",
        "firebaseio.com", "firebase.com", "firebaseinstallations.com",
        "app-measurement.com", "googlehosted.com", "1e100.net", "gmail.com",
        "mzstatic.com", "aaplimg.com", "cdn-apple.com", "push.apple.com",
        "msftconnecttest.com", "msftncsi.com", "windowsupdate.com",
        "msedge.net", "msecnd.net", "azureedge.net", "azurefd.net",
        "trafficmanager.net", "windows.net", "office.net", "appcenter.ms",
        "akamai.net", "akamaiedge.net", "akamaized.net", "akadns.net", "akamaihd.net",
        "edgekey.net", "edgesuite.net", "cloudfront.net", "fastly.net", "fastlylb.net",
        "cloudflare.com", "cloudflare.net", "cloudflare-dns.com", "cdn77.org",
        "llnwd.net", "stackpathdns.com", "cachefly.net", "jsdelivr.net", "unpkg.com",
        "bootstrapcdn.com", "cdnjs.com", "gcdn.co", "hwcdn.net", "cdn.net",
        "wpengine.com", "incapdns.net", "impervadns.net",
        "segment.io", "segment.com", "amplitude.com", "mixpanel.com", "branch.io",
        "appsflyer.com", "adjust.com", "adjust.io", "kochava.com", "sentry.io",
        "bugsnag.com", "newrelic.com", "nr-data.net", "datadoghq.com",
        "scorecardresearch.com", "quantserve.com", "chartbeat.net", "hotjar.com",
        "optimizely.com", "onesignal.com", "urbanairship.com", "airship.com",
        "braze.com", "appboy.com", "flurry.com", "clarity.ms", "fullstory.com",
        "launchdarkly.com", "split.io", "statsigapi.net", "mparticle.com",
        "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "amazon-adsystem.com", "casalemedia.com", "openx.net", "smaato.net",
        "mopub.com", "moatads.com", "yieldmo.com", "teads.tv", "smartadserver.com",
        "33across.com", "id5-sync.com", "sharethrough.com", "indexww.com",
        "applovin.com", "adcolony.com", "chartboost.com", "vungle.com",
        "supersonicads.com", "ironsrc.com", "inmobi.com", "unity3d.com",
        "bidswitch.net", "everesttech.net", "demdex.net", "omtrdc.net",
        "adsafeprotected.com", "doubleverify.com", "serving-sys.com",
        "digicert.com", "letsencrypt.org", "sectigo.com", "comodoca.com",
        "usertrust.com", "globalsign.com", "verisign.com", "entrust.net",
        "amazontrust.com", "godaddy.com", "ntp.org", "pool.ntp.org",
        "root-servers.net", "in-addr.arpa", "gtld-servers.net",
        "nextdns.io", "adguard-dns.com", "quad9.net", "opendns.com", "dns.sb",
    };

    private static readonly string[] ParoleRumore =
    {
        "analytics", "telemetry", "telemetria", "tracker", "tracking",
        "crashlytics", "adservice", "adserver", "adsystem", "metrics",
    };

    private static readonly string[] PrefissiTecnici =
    {
        "mtalk.", "alt1-mtalk.", "alt2-mtalk.", "alt3-mtalk.", "alt4-mtalk.",
        "alt5-mtalk.", "alt6-mtalk.", "alt7-mtalk.", "alt8-mtalk.",
        "clients1.", "clients2.", "clients3.", "clients4.", "clients5.", "clients6.",
        "connectivitycheck.", "safebrowsing.", "captive.", "detectportal.",
        "ocsp.", "crl.", "pki.", "ntp.", "time.", "csi.", "pagead.", "googleads.",
        "ads.", "adservice.", "beacon.", "telemetry.", "metrics.", "analytics.",
        "crashlytics.", "sentry.", "logs.", "collector.", "incoming.",
    };

    private static readonly string[] FuoriInternet =
    {
        ".local", ".lan", ".home", ".internal", ".localdomain", ".arpa",
        ".onion", ".test", ".invalid", ".example", ".localhost",
    };

    /// <summary>
    /// Il nome ripulito (minuscolo, senza punto finale) se è un nome di dominio
    /// pubblico plausibile; null per indirizzi IP, nomi di rete locale, nomi malformati.
    /// </summary>
    public static string? Normalizza(string nome)
    {
        var h = nome.Trim().ToLowerInvariant().TrimEnd('.');
        if (h.Length == 0 || h.Length > 253) return null;
        if (!h.Contains('.')) return null;
        foreach (var c in h)
        {
            bool ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            if (!ok) return null;
        }
        if (h.Contains("..")) return null;
        if (h.All(c => (c >= '0' && c <= '9') || c == '.')) return null;
        if (FuoriInternet.Any(s => h.EndsWith(s, StringComparison.Ordinal))) return null;
        return h;
    }

    /// <summary>Ultime due etichette, tre se il suffisso è a due livelli. Null se non bastano.</summary>
    public static string? DominioRegistrabile(string host)
    {
        var parti = host.Split('.', StringSplitOptions.RemoveEmptyEntries);
        if (parti.Length < 2) return null;
        var ultimeDue = parti[^2] + "." + parti[^1];
        if (SuffissiDoppi.Contains(ultimeDue))
        {
            if (parti.Length < 3) return null;
            return parti[^3] + "." + ultimeDue;
        }
        return ultimeDue;
    }

    /// <summary>
    /// La regola completa del telefono (con il filtro del rumore di rete). Sul
    /// computer non serve per la barra degli indirizzi — lì c'è solo quello che
    /// la persona ha aperto — ma resta qui identica per i confronti.
    /// </summary>
    public static string? DominioOsservabile(string nome)
    {
        var h = Normalizza(nome);
        if (h == null) return null;
        if (PrefissiTecnici.Any(p => h.StartsWith(p, StringComparison.Ordinal))) return null;
        var registrabile = DominioRegistrabile(h);
        if (registrabile == null) return null;
        var finale = Alias.TryGetValue(registrabile, out var a) ? a : registrabile;
        if (Rumore.Contains(finale)) return null;
        if (ParoleRumore.Any(p => finale.Contains(p, StringComparison.Ordinal))) return null;
        return finale;
    }

    /// <summary>Il dominio di una pagina aperta: registrabile + alias dei marchi, niente filtro del rumore.</summary>
    public static string? DominioDellaPagina(string host)
    {
        var h = Normalizza(host);
        if (h == null) return null;
        var registrabile = DominioRegistrabile(h);
        if (registrabile == null) return null;
        if (!AliasSoloTelefono.Contains(registrabile) && Alias.TryGetValue(registrabile, out var a)) return a;
        return registrabile;
    }

    /// <summary>
    /// Dal testo della barra degli indirizzi al dominio registrabile, e niente
    /// altro. Il testo non si conserva e non si scrive da nessuna parte: questa
    /// funzione lo legge e restituisce solo il dominio. Null quando non c'è un
    /// sito (pagina nuova, pagine interne del browser, file, ricerche scritte,
    /// indirizzi IP o di rete locale).
    /// </summary>
    public static string? DaBarraIndirizzi(string? testo)
    {
        if (string.IsNullOrWhiteSpace(testo)) return null;
        var t = testo.Trim();
        if (t.Length > 4096) return null;
        foreach (var c in t)
        {
            if (char.IsWhiteSpace(c)) return null; // una ricerca, non un indirizzo
        }

        string conSchema;
        int schema = t.IndexOf("://", StringComparison.Ordinal);
        if (schema >= 0)
        {
            var nomeSchema = t[..schema].ToLowerInvariant();
            if (nomeSchema != "http" && nomeSchema != "https") return null;
            conSchema = t;
        }
        else
        {
            // "about:blank", "view-source:…", "mailto:…" non sono siti; "host:porta/…" sì.
            int fineHost = t.IndexOfAny(new[] { '/', '?', '#' });
            var testa = fineHost >= 0 ? t[..fineHost] : t;
            int dueP = testa.IndexOf(':');
            if (dueP >= 0)
            {
                var porta = testa[(dueP + 1)..];
                if (porta.Length == 0 || !porta.All(char.IsAsciiDigit)) return null;
            }
            conSchema = "https://" + t;
        }

        if (!Uri.TryCreate(conSchema, UriKind.Absolute, out var uri)) return null;
        if (uri.HostNameType != UriHostNameType.Dns) return null;
        string host;
        try
        {
            host = uri.IdnHost;
        }
        catch (UriFormatException)
        {
            return null;
        }
        return DominioDellaPagina(host);
    }
}
