namespace Pactum.Nucleo;

/// <summary>
/// Le categorie del contratto (<c>social · giochi · video · musica · altro</c>)
/// per i programmi e i siti comuni. Tabella interna e in chiaro, come sul
/// telefono: il figlio la può leggere e discutere. Chi non è in tabella è "altro".
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

    private static readonly Dictionary<string, string> Programmi = Tabella(new()
    {
        [Social] = new[]
        {
            "discord.exe", "discordptb.exe", "discordcanary.exe", "telegram.exe", "whatsapp.exe",
            "whatsapp.root.exe", "signal.exe", "messenger.exe", "skype.exe", "instagram.exe",
            "facebook.exe", "tiktok.exe", "snapchat.exe", "threads.exe", "x.exe", "twitter.exe",
            "reddit.exe", "pinterest.exe",
        },
        [Giochi] = new[]
        {
            "steam.exe", "steamwebhelper.exe", "epicgameslauncher.exe", "minecraft.windows.exe",
            "minecraftlauncher.exe", "minecraft.exe", "robloxplayerbeta.exe", "robloxplayerlauncher.exe",
            "windows10universal.exe", "fortniteclient-win64-shipping.exe", "fortnitelauncher.exe",
            "leagueclient.exe", "leagueclientux.exe", "league of legends.exe", "riotclientservices.exe",
            "riotclientux.exe", "valorant.exe", "valorant-win64-shipping.exe", "battle.net.exe",
            "overwatch.exe", "gta5.exe", "gta5_enhanced.exe", "playgtav.exe", "rocketleague.exe",
            "cs2.exe", "csgo.exe", "dota2.exe", "eadesktop.exe", "origin.exe", "ubisoftconnect.exe",
            "upc.exe", "xboxpcapp.exe", "genshinimpact.exe", "zenlesszonezero.exe", "starrail.exe",
            "among us.exe", "brawlhalla.exe", "osu!.exe", "terraria.exe", "stardew valley.exe",
            "fc24.exe", "fc25.exe", "fc26.exe", "r5apex.exe", "r5apex_dx12.exe", "cod.exe",
            "destiny2.exe", "tslgame.exe", "rainbowsix.exe", "eldenring.exe", "geometrydash.exe",
            "fallguys_client_game.exe", "marvel-win64-shipping.exe", "rustclient.exe", "hollow_knight.exe",
            "palworld-win64-shipping.exe", "helldivers2.exe", "fifa23.exe", "nba2k25.exe",
        },
        [Video] = new[]
        {
            "vlc.exe", "netflix.exe", "primevideo.exe", "disneyplus.exe", "video.ui.exe",
            "microsoft.media.player.exe", "wmplayer.exe", "mpc-hc64.exe", "mpc-hc.exe", "mpc-be64.exe",
            "potplayermini64.exe", "potplayermini.exe", "twitch.exe", "crunchyroll.exe", "raiplay.exe",
        },
        [Musica] = new[]
        {
            "spotify.exe", "itunes.exe", "applemusic.exe", "deezer.exe", "tidal.exe",
            "amazon music.exe", "musicbee.exe", "foobar2000.exe", "aimp.exe", "soundcloud.exe",
        },
    });

    private static readonly Dictionary<string, string> Siti = Tabella(new()
    {
        [Social] = new[]
        {
            "instagram.com", "facebook.com", "tiktok.com", "x.com", "reddit.com", "snapchat.com",
            "discord.com", "whatsapp.com", "telegram.org", "threads.net", "threads.com", "pinterest.com",
            "tumblr.com", "bsky.app", "messenger.com", "bereal.com", "linkedin.com", "vk.com", "weibo.com",
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
            "youtube.com", "netflix.com", "twitch.tv", "disneyplus.com", "primevideo.com", "vimeo.com",
            "dailymotion.com", "crunchyroll.com", "raiplay.it", "mediaset.it", "la7.it", "dazn.com",
            "nowtv.it", "paramountplus.com", "plex.tv", "pluto.tv", "kick.com", "rumble.com",
            "bilibili.com", "discoveryplus.com", "max.com", "hbomax.com", "timvision.it", "tubi.tv",
        },
        [Musica] = new[]
        {
            "spotify.com", "soundcloud.com", "deezer.com", "tidal.com", "bandcamp.com", "last.fm",
            "genius.com", "shazam.com", "audiomack.com",
        },
    });

    private static Dictionary<string, string> Tabella(Dictionary<string, string[]> perCategoria)
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

    /// <summary>La categoria di un sito (dominio registrabile), "altro" se non è in tabella.</summary>
    public static string DiSito(string dominio) =>
        Siti.TryGetValue(dominio.Trim(), out var c) ? c : Altro;

    /// <summary>
    /// Dove va il tempo di un secondo passato al computer (contratto v3): nel
    /// browser, nella categoria del sito se il sito ne ha una; altrimenti in quella del programma.
    /// </summary>
    public static string DelTempo(string categoriaProgramma, string? dominio)
    {
        if (dominio != null && Siti.TryGetValue(dominio, out var c)) return c;
        return categoriaProgramma;
    }
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
