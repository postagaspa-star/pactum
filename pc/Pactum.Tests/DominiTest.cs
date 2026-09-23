using System.Text.RegularExpressions;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>Il dominio registrabile: stesse regole del telefono, più la barra degli indirizzi del computer.</summary>
public class DominiTest
{
    [Theory]
    [InlineData("m.youtube.com", "youtube.com")]
    [InlineData("www.instagram.com", "instagram.com")]
    [InlineData("scontent.cdninstagram.com", "instagram.com")]
    [InlineData("i.ytimg.com", "youtube.com")]
    [InlineData("youtu.be", "youtube.com")]
    [InlineData("twitter.com", "x.com")]
    public void Aggrega_i_sottodomini_e_i_marchi_come_il_telefono(string nome, string atteso)
    {
        Assert.Equal(atteso, Domini.DominioOsservabile(nome));
    }

    [Theory]
    [InlineData("news.bbc.co.uk", "bbc.co.uk")]
    [InlineData("www.agenziaentrate.gov.it", "agenziaentrate.gov.it")]
    [InlineData("shop.example.com.au", "example.com.au")]
    [InlineData("example.com", "example.com")]
    public void I_suffissi_a_due_livelli_tengono_tre_etichette(string host, string atteso)
    {
        Assert.Equal(atteso, Domini.DominioRegistrabile(host));
    }

    [Fact]
    public void Un_suffisso_doppio_da_solo_non_e_un_sito()
    {
        Assert.Null(Domini.DominioRegistrabile("co.uk"));
    }

    [Theory]
    [InlineData("nas")]
    [InlineData("192.168.1.10")]
    [InlineData("router.lan")]
    [InlineData("stampante.local")]
    [InlineData("a..b.com")]
    [InlineData("")]
    public void Quello_che_non_e_un_sito_pubblico_resta_fuori(string nome)
    {
        Assert.Null(Domini.Normalizza(nome));
    }

    [Fact]
    public void Il_rumore_di_rete_resta_fuori_sul_telefono()
    {
        Assert.Null(Domini.DominioOsservabile("www.googletagmanager.com"));
        Assert.Null(Domini.DominioOsservabile("mtalk.google.com"));
    }

    [Theory]
    [InlineData("https://en.wikipedia.org/wiki/Pagina_segreta?q=cerca#titolo", "wikipedia.org")]
    [InlineData("example.com", "example.com")]
    [InlineData("www.example.com/percorso/lungo", "example.com")]
    [InlineData("m.youtube.com/watch?v=abc123", "youtube.com")]
    [InlineData("youtu.be/abc123", "youtube.com")]
    [InlineData("HTTPS://WWW.Example.COM:443/A", "example.com")]
    [InlineData("https://utente:segreto@example.com/", "example.com")]
    [InlineData("http://example.com:8080/x", "example.com")]
    [InlineData("example.com:8080/x", "example.com")]
    [InlineData("https://münchen.de/", "xn--mnchen-3ya.de")]
    [InlineData("www.bbc.co.uk/news", "bbc.co.uk")]
    [InlineData("https://www.primevideo.com/detail/xyz", "primevideo.com")]
    [InlineData("  example.org  ", "example.org")]
    public void Dalla_barra_degli_indirizzi_esce_solo_il_dominio(string testo, string atteso)
    {
        Assert.Equal(atteso, Domini.DaBarraIndirizzi(testo));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("come si cucina la pasta")]
    [InlineData("chrome://settings")]
    [InlineData("edge://newtab/")]
    [InlineData("about:blank")]
    [InlineData("file:///C:/Users/andre/segreto.txt")]
    [InlineData("view-source:https://example.com")]
    [InlineData("192.168.1.1/admin")]
    [InlineData("localhost:3000")]
    [InlineData("https://[::1]/")]
    [InlineData("mailto:qualcuno@example.com")]
    [InlineData("javascript:alert(1)")]
    [InlineData("ftp://example.com/file")]
    [InlineData("intranet/pagina")]
    [InlineData("nas.local/foto")]
    public void Senza_un_sito_pubblico_non_esce_niente(string? testo)
    {
        Assert.Null(Domini.DaBarraIndirizzi(testo));
    }

    [Theory]
    [InlineData("https://www.youtube.com/watch?v=segreto&list=privata")]
    [InlineData("https://accounts.google.com/o/oauth2/auth?client_id=x&redirect_uri=https://altro.it/cb")]
    [InlineData("reddit.com/r/qualcosa/comments/abc/titolo_del_post")]
    [InlineData("https://example.com/percorso#frammento")]
    public void Quello_che_esce_e_sempre_e_solo_un_nome_di_dominio(string testo)
    {
        var dominio = Domini.DaBarraIndirizzi(testo);
        Assert.NotNull(dominio);
        Assert.Matches(new Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$"), dominio!);
        Assert.DoesNotContain("/", dominio);
        Assert.DoesNotContain("?", dominio);
    }
}
