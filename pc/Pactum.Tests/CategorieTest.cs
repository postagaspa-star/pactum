using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// (02/10, revisione completa) Le categorie del computer: le liste scritte da noi (Categorie.cs, riportate
/// in docs/categorie.md), stessa regola e stesse scelte del telefono. Chi non è in una lista è "altro";
/// i browser e i messaggi lo sono sempre; nel browser il tempo va nella categoria del sito.
/// </summary>
public class CategorieTest
{
    [Theory]
    [InlineData("exe:discord.exe", "social")]
    [InlineData("Discord.exe", "social")]
    [InlineData("exe:instagram.exe", "social")]
    [InlineData("exe:twitch.exe", "social")] // (02/10) Twitch è social, come YouTube
    [InlineData("exe:steam.exe", "giochi")]
    [InlineData("exe:epicgameslauncher.exe", "giochi")]
    [InlineData("exe:battle.net.exe", "giochi")]
    [InlineData("exe:riot client.exe", "giochi")]
    [InlineData("exe:leagueclient.exe", "giochi")]
    [InlineData("exe:valorant-win64-shipping.exe", "giochi")]
    [InlineData("exe:minecraft.windows.exe", "giochi")]
    [InlineData("exe:minecraft.exe", "giochi")]
    [InlineData("exe:robloxplayerbeta.exe", "giochi")]
    [InlineData("exe:fortniteclient-win64-shipping.exe", "giochi")]
    [InlineData("exe:vlc.exe", "video")]
    [InlineData("exe:netflix.exe", "video")]
    [InlineData("exe:appletv.exe", "video")]
    [InlineData("exe:spotify.exe", "musica")]
    [InlineData("exe:applemusic.exe", "musica")]
    [InlineData("exe:winword.exe", "altro")]
    public void I_programmi_comuni_hanno_la_loro_categoria(string programma, string categoria)
    {
        Assert.Equal(categoria, Categorie.DiProgramma(programma));
    }

    /// <summary>(02/10) I browser non sono in nessuna categoria: il loro tempo va nella categoria del sito.</summary>
    [Theory]
    [InlineData("exe:firefox.exe")]
    [InlineData("exe:chrome.exe")]
    [InlineData("exe:msedge.exe")]
    [InlineData("exe:brave.exe")]
    [InlineData("exe:opera.exe")]
    [InlineData("exe:vivaldi.exe")]
    public void I_browser_non_hanno_una_categoria(string programma)
    {
        Assert.Equal(Categorie.Altro, Categorie.DiProgramma(programma));
    }

    [Theory]
    [InlineData("instagram.com", "social")]
    [InlineData("tiktok.com", "social")]
    [InlineData("youtube.com", "social")] // (30/09) YouTube è social, non video
    [InlineData("twitch.tv", "social")] // (02/10) e così Twitch
    [InlineData("kick.com", "social")]
    [InlineData("dailymotion.com", "social")] // (02/10, Andrea) video fatti dalle persone
    [InlineData("vimeo.com", "video")] // (02/10, Andrea) Vimeo resta video
    [InlineData("reddit.com", "social")]
    [InlineData("x.com", "social")]
    [InlineData("twitter.com", "social")] // diventa x.com
    [InlineData("facebook.com", "social")]
    [InlineData("snapchat.com", "social")]
    [InlineData("pinterest.com", "social")]
    [InlineData("discord.com", "social")]
    [InlineData("threads.net", "social")]
    [InlineData("threads.com", "social")]
    [InlineData("linkedin.com", "social")]
    [InlineData("netflix.com", "video")]
    [InlineData("primevideo.com", "video")]
    [InlineData("disneyplus.com", "video")]
    [InlineData("raiplay.it", "video")]
    [InlineData("dazn.com", "video")]
    [InlineData("nowtv.it", "video")]
    [InlineData("crunchyroll.com", "video")]
    [InlineData("paramountplus.com", "video")]
    [InlineData("spotify.com", "musica")]
    [InlineData("deezer.com", "musica")]
    [InlineData("soundcloud.com", "musica")]
    [InlineData("roblox.com", "giochi")]
    [InlineData("poki.com", "giochi")]
    [InlineData("crazygames.com", "giochi")]
    [InlineData("chess.com", "giochi")]
    [InlineData("lichess.org", "giochi")]
    [InlineData("miniclip.com", "giochi")]
    [InlineData("wikipedia.org", "altro")]
    [InlineData("apple.com", "altro")] // tutto apple.com in musica no: solo music.apple.com
    [InlineData("mediaset.it", "altro")] // tutto mediaset.it in video no: è anche TgCom24
    public void I_siti_comuni_hanno_la_loro_categoria(string dominio, string categoria)
    {
        Assert.Equal(categoria, Categorie.DiSito(dominio));
    }

    /// <summary>
    /// (02/10) I servizi che vivono in un sottodominio di un sito più grande si riconoscono dal nome intero
    /// della pagina: YouTube Music è musica (YouTube è social), Apple Music è musica (apple.com no),
    /// Mediaset Infinity è video (TgCom24, sullo stesso mediaset.it, no).
    /// </summary>
    [Theory]
    [InlineData("music.youtube.com", "musica")]
    [InlineData("music.apple.com", "musica")]
    [InlineData("tv.apple.com", "video")]
    [InlineData("mediasetinfinity.mediaset.it", "video")]
    [InlineData("music.amazon.it", "musica")]
    [InlineData("www.youtube.com", "social")]
    [InlineData("m.youtube.com", "social")]
    [InlineData("studio.youtube.com", "social")]
    [InlineData("www.apple.com", null)]
    [InlineData("tgcom24.mediaset.it", null)]
    [InlineData("www.amazon.it", null)]
    [InlineData("it.wikipedia.org", null)]
    public void I_servizi_nei_sottodomini_si_riconoscono_dal_nome_intero(string nome, string? categoria)
    {
        Assert.Equal(categoria, Categorie.DelSito(nome));
    }

    /// <summary>
    /// (30/09, decisione di Andrea) I messaggi non stanno in nessuna categoria: come ogni programma o sito
    /// non elencato, il loro tempo va in "altro" (mai in social).
    /// </summary>
    [Theory]
    [InlineData("exe:whatsapp.exe")]
    [InlineData("exe:whatsapp.root.exe")]
    [InlineData("exe:telegram.exe")]
    [InlineData("exe:signal.exe")]
    [InlineData("exe:messenger.exe")]
    [InlineData("exe:skype.exe")]
    public void I_programmi_di_messaggi_non_hanno_una_categoria(string programma)
    {
        Assert.Equal(Categorie.Altro, Categorie.DiProgramma(programma));
    }

    [Theory]
    [InlineData("whatsapp.com")]
    [InlineData("web.whatsapp.com")]
    [InlineData("telegram.org")]
    [InlineData("messenger.com")]
    [InlineData("signal.org")]
    public void I_siti_di_messaggi_non_hanno_una_categoria(string sito)
    {
        Assert.Equal(Categorie.Altro, Categorie.DiSito(sito));
        Assert.Null(Categorie.DelSito(sito));
        // Nel browser, il tempo su un sito di messaggi resta nella categoria del browser.
        Assert.Equal(Categorie.Altro, Categorie.DelTempo(Categorie.DiProgramma("exe:chrome.exe"), sito));
    }

    [Fact]
    public void Il_tempo_nel_browser_va_nella_categoria_del_sito_se_ne_ha_una()
    {
        var firefox = Categorie.DiProgramma("exe:firefox.exe");
        var chrome = Categorie.DiProgramma("exe:chrome.exe");
        Assert.Equal("social", Categorie.DelTempo(firefox, "youtube.com"));
        Assert.Equal("social", Categorie.DelTempo(chrome, "youtube.com"));
        Assert.Equal("video", Categorie.DelTempo(chrome, "netflix.com"));
        Assert.Equal("musica", Categorie.DelTempo(chrome, "music.youtube.com"));
        // Un sito sconosciuto: la categoria del browser, cioè "altro".
        Assert.Equal("altro", Categorie.DelTempo(firefox, "sito-sconosciuto.it"));
        Assert.Equal("altro", Categorie.DelTempo(chrome, "wikipedia.org"));
        Assert.Equal("giochi", Categorie.DelTempo("giochi", null));
    }

    /// <summary>
    /// Il lettore della barra degli indirizzi decide la categoria dal nome intero della pagina, poi lo butta:
    /// fuori escono solo il dominio registrabile e una delle categorie del contratto.
    /// </summary>
    [Theory]
    [InlineData("https://music.youtube.com/watch?v=abc&list=xyz", "youtube.com", "musica")]
    [InlineData("https://www.youtube.com/watch?v=abc", "youtube.com", "social")]
    [InlineData("music.apple.com/it/album/qualcosa/123", "apple.com", "musica")]
    [InlineData("https://www.apple.com/it/iphone/", "apple.com", null)]
    [InlineData("https://mediasetinfinity.mediaset.it/video/qualcosa", "mediaset.it", "video")]
    [InlineData("https://tgcom24.mediaset.it/cronaca/articolo.shtml", "mediaset.it", null)]
    [InlineData("https://www.twitch.tv/qualcuno", "twitch.tv", "social")]
    [InlineData("https://www.netflix.com/watch/123", "netflix.com", "video")]
    [InlineData("https://web.whatsapp.com/", "whatsapp.com", null)]
    [InlineData("it.wikipedia.org/wiki/Pagina", "wikipedia.org", null)]
    public void Dalla_barra_degli_indirizzi_escono_il_dominio_e_la_categoria_del_sito(string testo, string dominio, string? categoria)
    {
        var lettura = Lettura.DallaBarra(testo);
        Assert.False(lettura.Fallita);
        Assert.Equal(dominio, lettura.Dominio);
        Assert.Equal(categoria, lettura.CategoriaSito);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("come si cucina la pasta")]
    [InlineData("about:blank")]
    [InlineData("chrome://settings")]
    [InlineData("192.168.1.1/admin")]
    [InlineData("co.uk")]
    public void Senza_un_sito_non_escono_ne_dominio_ne_categoria(string? testo)
    {
        // (correzione 0.18) La lettura dice in più se è una scheda nuova o una pagina senza sito (SenzaSito, per lo
        // Studio): qui conta che non escano né dominio né categoria e che la lettura non risulti fallita.
        var l = Lettura.DallaBarra(testo);
        Assert.Null(l.Dominio);
        Assert.Null(l.CategoriaSito);
        Assert.False(l.Fallita);
        Assert.Equal(new Lettura(null, false, SenzaSito: l.SenzaSito), l);
    }

    [Fact]
    public void Nessuna_voce_sta_in_due_liste()
    {
        static IEnumerable<string> Doppioni(IEnumerable<string> voci) =>
            voci.GroupBy(v => v, StringComparer.OrdinalIgnoreCase).Where(g => g.Count() > 1).Select(g => g.Key);

        Assert.Empty(Doppioni(Categorie.ListeProgrammi.Values.SelectMany(v => v)));
        Assert.Empty(Doppioni(Categorie.ListeSiti.Values.SelectMany(v => v).Concat(Categorie.ListeSottositi.Values.SelectMany(v => v))));
    }

    [Fact]
    public void Le_voci_sono_scritte_come_le_vede_il_programma()
    {
        Assert.All(Categorie.ListeProgrammi.Keys.Concat(Categorie.ListeSiti.Keys).Concat(Categorie.ListeSottositi.Keys),
            c => Assert.Contains(c, Categorie.Tutte));
        // I programmi: il nome del file in minuscolo, come nelle chiavi exe:. L'unica eccezione è
        // (0.13) minecraft-java, che non è un vero .exe ma per il server è una chiave exe: come le altre.
        Assert.All(Categorie.ListeProgrammi.Values.SelectMany(v => v), voce =>
        {
            Assert.Equal(Programma.Chiave(voce), Programma.Prefisso + voce);
            if (voce != Programma.MinecraftJava) Assert.EndsWith(".exe", voce, StringComparison.Ordinal);
        });
        // I siti: esattamente il dominio che esce dalla barra degli indirizzi, altrimenti non combacerebbero mai.
        Assert.All(Categorie.ListeSiti.Values.SelectMany(v => v), sito => Assert.Equal(sito, Domini.DominioDellaPagina(sito)));
        // I sottositi: nomi interi PIÙ lunghi del loro dominio registrabile (un dominio intero va nei siti).
        Assert.All(Categorie.ListeSottositi.Values.SelectMany(v => v), nome =>
        {
            Assert.Equal(nome, Domini.Normalizza(nome));
            Assert.NotEqual(nome, Domini.DominioRegistrabile(nome));
        });
    }

    [Fact]
    public void Le_chiavi_sono_quelle_del_contratto()
    {
        Assert.Equal("categoria:social", Categorie.Chiave(Categorie.Social));
        Assert.Equal("exe:minecraft.exe", Programma.Chiave("Minecraft.EXE"));
        Assert.Equal(new[] { "social", "giochi", "video", "musica", "altro" }, Categorie.Tutte);
    }

    [Fact]
    public void Senza_descrizione_il_nome_e_quello_del_file()
    {
        Assert.Equal("minecraft", Programma.NomeDiRipiego("exe:minecraft.exe"));
        Assert.Equal("Notepad", Programma.NomeDiRipiego("Notepad.exe"));
    }

    // ---------- (0.13) Minecraft Java ----------

    [Theory]
    [InlineData("javaw.exe", "Minecraft 1.20.1", true)]
    [InlineData("java.exe", "Minecraft* 1.21.4", true)]
    [InlineData("javaw.exe", "Minecraft", true)]
    [InlineData("javaw.exe", "  Minecraft 1.20.1", true)] // spazi iniziali tolti
    [InlineData("JAVAW.EXE", "Minecraft 1.20.1", true)] // il nome del processo non guarda le maiuscole
    [InlineData("javaw.exe", "Eclipse IDE", false)]
    [InlineData("javaw.exe", "minecraft 1.20.1", false)] // il titolo sì: "Minecraft" con la M grande
    [InlineData("javaw.exe", "", false)]
    [InlineData("javaw.exe", null, false)]
    [InlineData("chrome.exe", "Minecraft 1.20.1", false)] // solo java
    [InlineData("minecraftlauncher.exe", "Minecraft", false)]
    public void Minecraft_Java_e_javaw_col_titolo_giusto(string exe, string? titolo, bool atteso)
    {
        Assert.Equal(atteso, Programma.ÈMinecraftJava(exe, titolo));
    }

    [Fact]
    public void Minecraft_Java_conta_come_gioco_con_la_sua_chiave_e_il_suo_nome()
    {
        Assert.Equal("minecraft-java", Programma.MinecraftJava);
        Assert.Equal("exe:minecraft-java", Programma.Chiave(Programma.MinecraftJava));
        Assert.Equal("Minecraft (Java)", Programma.NomeMinecraftJava);
        Assert.Equal(Categorie.Giochi, Categorie.DiProgramma("exe:minecraft-java"));
        Assert.Equal(Categorie.Giochi, Categorie.DiProgramma(Programma.Chiave(Programma.MinecraftJava)));
    }
}
