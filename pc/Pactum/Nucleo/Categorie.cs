namespace Pactum.Nucleo;

/// <summary>
/// Le categorie del contratto (<c>social · giochi · video · musica · altro</c>) per i programmi e i siti.
/// (02/10, revisione completa) Liste scritte da noi, in chiaro e in un posto solo, con la stessa regola
/// e le stesse scelte del telefono; <c>docs/categorie.md</c> le riporta intere:
/// <list type="number">
/// <item>un programma o un sito in una lista sta nella categoria della sua lista;</item>
/// <item>tutto il resto è "altro": i messaggi (decisione di Andrea del 30/09) e i browser ci stanno sempre;</item>
/// <item>il tempo nel browser va nella categoria del SITO, se il sito è in una lista; altrimenti resta
/// in quella del browser, cioè "altro".</item>
/// </list>
/// </summary>
public static class Categorie
{
    public const string Social = "social";
    public const string Giochi = "giochi";
    public const string Video = "video";
    public const string Musica = "musica";
    public const string Altro = "altro";

    public const string Prefisso = "categoria:";

    public static readonly IReadOnlyList<string> Tutte = new[] { Social, Giochi, Video, Musica, Altro };

    /// <summary>I programmi: il nome del file, minuscolo come nelle chiavi <c>exe:</c>.</summary>
    internal static readonly IReadOnlyDictionary<string, string[]> ListeProgrammi = new Dictionary<string, string[]>
    {
        [Social] = new[]
        {
            "discord.exe", "discordptb.exe", "discordcanary.exe", "instagram.exe",
            "facebook.exe", "tiktok.exe", "snapchat.exe", "threads.exe", "x.exe", "twitter.exe",
            "reddit.exe", "pinterest.exe",
            // (02/10) Twitch è social, come YouTube.
            "twitch.exe",
        },
        [Giochi] = new[]
        {
            // I negozi e i launcher dei giochi.
            "steam.exe", "steamwebhelper.exe", "epicgameslauncher.exe", "battle.net.exe",
            "riotclientservices.exe", "riotclientux.exe", "riot client.exe", "eadesktop.exe", "origin.exe",
            "ubisoftconnect.exe", "upc.exe", "xboxpcapp.exe", "galaxyclient.exe",
            // I giochi.
            "minecraft.windows.exe", "minecraftlauncher.exe", "minecraft.exe",
            "robloxplayerbeta.exe", "robloxplayerlauncher.exe", "windows10universal.exe",
            "fortniteclient-win64-shipping.exe", "fortnitelauncher.exe",
            "leagueclient.exe", "leagueclientux.exe", "league of legends.exe",
            "valorant.exe", "valorant-win64-shipping.exe",
            "overwatch.exe", "gta5.exe", "gta5_enhanced.exe", "playgtav.exe", "rocketleague.exe",
            "cs2.exe", "csgo.exe", "dota2.exe", "genshinimpact.exe", "zenlesszonezero.exe", "starrail.exe",
            "among us.exe", "brawlhalla.exe", "osu!.exe", "terraria.exe", "stardew valley.exe",
            "fc24.exe", "fc25.exe", "fc26.exe", "fifa23.exe", "nba2k25.exe", "r5apex.exe", "r5apex_dx12.exe",
            "cod.exe", "destiny2.exe", "tslgame.exe", "rainbowsix.exe", "eldenring.exe", "geometrydash.exe",
            "fallguys_client_game.exe", "marvel-win64-shipping.exe", "rustclient.exe", "hollow_knight.exe",
            "palworld-win64-shipping.exe", "helldivers2.exe",
        },
        [Video] = new[]
        {
            "netflix.exe", "primevideo.exe", "disneyplus.exe", "appletv.exe", "crunchyroll.exe", "raiplay.exe",
            "plex.exe",
            // I lettori di video.
            "vlc.exe", "video.ui.exe", "microsoft.media.player.exe", "wmplayer.exe", "mpc-hc64.exe",
            "mpc-hc.exe", "mpc-be64.exe", "potplayermini64.exe", "potplayermini.exe",
        },
        [Musica] = new[]
        {
            "spotify.exe", "itunes.exe", "applemusic.exe", "deezer.exe", "tidal.exe", "amazon music.exe",
            "soundcloud.exe", "youtube music.exe", "musicbee.exe", "foobar2000.exe", "aimp.exe",
        },
        // In nessuna categoria, qualunque cosa ci si faccia. I browser: il loro tempo va nella categoria
        // del sito aperto (v. DelTempo). I messaggi: decisione di Andrea del 30/09.
        [Altro] = new[]
        {
            "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe",
            "duckduckgo.exe", "arc.exe", "zen.exe", "librewolf.exe", "waterfox.exe", "iexplore.exe",
            "whatsapp.exe", "whatsapp.root.exe", "telegram.exe", "signal.exe", "messenger.exe", "skype.exe",
        },
    };

    /// <summary>I siti: il dominio registrabile, come lo dà <see cref="Domini.DominioDellaPagina"/>.</summary>
    internal static readonly IReadOnlyDictionary<string, string[]> ListeSiti = new Dictionary<string, string[]>
    {
        [Social] = new[]
        {
            "instagram.com", "tiktok.com", "facebook.com", "x.com", "reddit.com", "snapchat.com",
            "discord.com", "threads.net", "threads.com", "pinterest.com", "pinterest.it",
            "tumblr.com", "bsky.app", "bereal.com", "linkedin.com", "vk.com", "weibo.com",
            // (0.9, decisione di Andrea del 30/09) YouTube è social, non video.
            "youtube.com",
            // (02/10, confermato da Andrea) Come YouTube: le dirette di Twitch e Kick, e i siti di video fatti dalle persone.
            "twitch.tv", "kick.com", "dailymotion.com", "rumble.com", "bilibili.com",
        },
        [Giochi] = new[]
        {
            "roblox.com", "poki.com", "crazygames.com", "miniclip.com", "chess.com", "lichess.org",
            "steampowered.com", "epicgames.com", "y8.com", "friv.com", "coolmathgames.com", "itch.io",
            "kongregate.com", "agar.io", "slither.io", "krunker.io", "geoguessr.com", "minecraft.net",
            "gamejolt.com", "armorgames.com", "addictinggames.com", "gamepix.com", "1001games.com",
        },
        [Video] = new[]
        {
            "netflix.com", "primevideo.com", "disneyplus.com", "raiplay.it", "dazn.com", "nowtv.it",
            "crunchyroll.com", "paramountplus.com", "pluto.tv", "la7.it", "discoveryplus.com", "max.com",
            "hbomax.com", "timvision.it", "plex.tv", "tubi.tv",
            // (02/10, Andrea) Vimeo è video: cortometraggi e lavori, niente scorrimento infinito.
            "vimeo.com",
        },
        [Musica] = new[]
        {
            "spotify.com", "soundcloud.com", "deezer.com", "tidal.com", "bandcamp.com", "last.fm",
            "genius.com", "shazam.com", "audiomack.com",
        },
        // (0.9, decisione di Andrea del 30/09) I siti di messaggi: in nessuna categoria.
        [Altro] = new[] { "whatsapp.com", "telegram.org", "messenger.com", "signal.org" },
    };

    /// <summary>
    /// I servizi che vivono in un sottodominio di un sito più grande: si riconoscono dal nome intero della
    /// pagina, mai dal dominio registrabile. youtube.com è social ma YouTube Music è musica; apple.com non
    /// ha una categoria (e non deve averla tutto in musica); mediaset.it è anche TgCom24, Mediaset Infinity
    /// è video.
    /// </summary>
    internal static readonly IReadOnlyDictionary<string, string[]> ListeSottositi = new Dictionary<string, string[]>
    {
        [Video] = new[] { "mediasetinfinity.mediaset.it", "tv.apple.com" },
        [Musica] = new[] { "music.youtube.com", "music.apple.com", "music.amazon.it", "music.amazon.com" },
    };

    private static readonly Dictionary<string, string> Programmi = Tabella(ListeProgrammi);
    private static readonly Dictionary<string, string> Siti = Tabella(ListeSiti);
    private static readonly Dictionary<string, string> Sottositi = Tabella(ListeSottositi);

    private static Dictionary<string, string> Tabella(IReadOnlyDictionary<string, string[]> perCategoria)
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var (categoria, voci) in perCategoria)
        {
            foreach (var v in voci) d[v] = categoria;
        }
        return d;
    }

    /// <summary>La chiave di regola di una categoria: <c>categoria:social</c>.</summary>
    public static string Chiave(string categoria) => Prefisso + categoria;

    /// <summary>La categoria di un programma, da <c>exe:discord.exe</c> o da <c>discord.exe</c>.</summary>
    public static string DiProgramma(string chiaveOExe)
    {
        var exe = chiaveOExe.StartsWith(Programma.Prefisso, StringComparison.OrdinalIgnoreCase)
            ? chiaveOExe[Programma.Prefisso.Length..]
            : chiaveOExe;
        return Programmi.TryGetValue(exe.Trim(), out var c) ? c : Altro;
    }

    /// <summary>La categoria di un sito, "altro" se non è in una lista.</summary>
    public static string DiSito(string sito) => DelSito(sito) ?? Altro;

    /// <summary>
    /// La categoria di un sito dal suo nome intero (<c>music.youtube.com</c>) o dal dominio registrabile
    /// (<c>youtube.com</c>): prima i servizi nei sottodomini, poi il dominio registrabile. Null se il sito
    /// non ha una categoria (non è in una lista, o è un sito di messaggi).
    /// </summary>
    public static string? DelSito(string sito)
    {
        var nome = sito.Trim().TrimEnd('.').ToLowerInvariant();
        if (nome.Length == 0) return null;
        foreach (var (sottosito, categoria) in Sottositi)
        {
            if (nome == sottosito || nome.EndsWith("." + sottosito, StringComparison.Ordinal)) return categoria;
        }
        var dominio = Domini.DominioDellaPagina(nome) ?? nome;
        return Siti.TryGetValue(dominio, out var c) && c != Altro ? c : null;
    }

    /// <summary>
    /// Dove va il tempo di un secondo passato al computer (contratto v3): nel browser, nella categoria del
    /// sito se il sito ne ha una; altrimenti in quella del programma.
    /// </summary>
    public static string DelTempo(string categoriaProgramma, string? sito) =>
        sito != null && DelSito(sito) is string c ? c : categoriaProgramma;
}

/// <summary>Le chiavi dei programmi: <c>exe:</c> + nome del file in minuscolo (contratto v3).</summary>
public static class Programma
{
    public const string Prefisso = "exe:";
    public const string PrefissoSito = "sito:";

    public static string Chiave(string nomeFile) => Prefisso + nomeFile.Trim().ToLowerInvariant();

    /// <summary>Il nome da mostrare quando il file non ha una descrizione: il nome del file senza ".exe".</summary>
    public static string NomeDiRipiego(string chiaveOFile)
    {
        var nome = chiaveOFile.StartsWith(Prefisso, StringComparison.OrdinalIgnoreCase) ? chiaveOFile[Prefisso.Length..] : chiaveOFile;
        return nome.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? nome[..^4] : nome;
    }
}
