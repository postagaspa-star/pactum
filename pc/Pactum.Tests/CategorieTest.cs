using Pactum.Nucleo;

namespace Pactum.Tests;

public class CategorieTest
{
    [Theory]
    [InlineData("exe:discord.exe", "social")]
    [InlineData("Discord.exe", "social")]
    [InlineData("exe:instagram.exe", "social")]
    [InlineData("exe:steam.exe", "giochi")]
    [InlineData("exe:minecraft.windows.exe", "giochi")]
    [InlineData("exe:robloxplayerbeta.exe", "giochi")]
    [InlineData("exe:vlc.exe", "video")]
    [InlineData("exe:spotify.exe", "musica")]
    [InlineData("exe:winword.exe", "altro")]
    [InlineData("exe:chrome.exe", "altro")]
    public void I_programmi_comuni_hanno_la_loro_categoria(string programma, string categoria)
    {
        Assert.Equal(categoria, Categorie.DiProgramma(programma));
    }

    [Theory]
    [InlineData("instagram.com", "social")]
    [InlineData("tiktok.com", "social")]
    [InlineData("discord.com", "social")]
    [InlineData("youtube.com", "social")] // (30/09) YouTube è social, non video
    [InlineData("roblox.com", "giochi")]
    [InlineData("netflix.com", "video")]
    [InlineData("twitch.tv", "video")]
    [InlineData("spotify.com", "musica")]
    [InlineData("wikipedia.org", "altro")]
    public void I_siti_comuni_hanno_la_loro_categoria(string dominio, string categoria)
    {
        Assert.Equal(categoria, Categorie.DiSito(dominio));
    }

    /// <summary>
    /// (30/09, decisione di Andrea) I messaggi non stanno in nessuna categoria della tabella: come ogni
    /// programma o sito non elencato, il loro tempo va in "altro" (mai in social).
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
    [InlineData("telegram.org")]
    [InlineData("messenger.com")]
    [InlineData("signal.org")]
    public void I_siti_di_messaggi_non_hanno_una_categoria(string dominio)
    {
        Assert.Equal(Categorie.Altro, Categorie.DiSito(dominio));
        // Nel browser, il tempo su un sito di messaggi resta nella categoria del browser.
        Assert.Equal(Categorie.Altro, Categorie.DelTempo(Categorie.DiProgramma("exe:chrome.exe"), dominio));
    }

    [Fact]
    public void Il_tempo_nel_browser_va_nella_categoria_del_sito_se_ne_ha_una()
    {
        Assert.Equal("social", Categorie.DelTempo("altro", "youtube.com"));
        Assert.Equal("video", Categorie.DelTempo("altro", "netflix.com"));
        Assert.Equal("altro", Categorie.DelTempo("altro", "wikipedia.org"));
        Assert.Equal("giochi", Categorie.DelTempo("giochi", null));
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
}
