using System.Text.Json.Nodes;
using Pactum.Motore;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// (0.23) Le categorie del figlio sul computer: i nomi, chi si sposta (solo quello che Pactum non riconosce, mai
/// i browser, mai da una categoria con un limite), e nel motore lo spostamento dei minuti di oggi, la categoria
/// nuova nel conto per le regole e nella fotografia, e le categorie con un limite che non si eliminano.
/// </summary>
public class CategorieMieTest
{
    private const string Programma = "exe:compitinodiprova.exe";

    [Theory]
    [InlineData("Scuola", "scuola")]
    [InlineData("  Scuola   e  compiti ", "scuola e compiti")]
    [InlineData("Città", "città")]
    public void Il_nome_si_salva_minuscolo_e_con_gli_spazi_a_posto(string scritto, string atteso)
    {
        Assert.Equal(atteso, RegoleCategorie.Nome(scritto));
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("Giochi")]
    [InlineData("altro")]
    [InlineData("Altre app")]
    [InlineData("totale")]
    [InlineData("a:b")]
    [InlineData("tab\tqui")]
    [InlineData("invisibile​")]
    [InlineData("un nome davvero troppo lungo per una categoria")]
    public void Certi_nomi_non_vanno(string scritto)
    {
        Assert.Null(RegoleCategorie.Nome(scritto));
    }

    [Fact]
    public void L_etichetta_ha_la_prima_lettera_maiuscola()
    {
        Assert.Equal("Scuola e compiti", RegoleCategorie.Etichetta("scuola e compiti"));
    }

    [Fact]
    public void Si_sposta_solo_quello_che_Pactum_non_riconosce()
    {
        var nessunLimite = new HashSet<string>();
        Func<string, bool> browser = CoperturaStudio.ÈBrowser;
        Assert.Equal(RegoleCategorie.Fermo.Riconosciuto, RegoleCategorie.PercheFermo("exe:steam.exe", "giochi", nessunLimite, browser));
        Assert.Equal(RegoleCategorie.Fermo.Riconosciuto, RegoleCategorie.PercheFermo("exe:minecraft-java", "giochi", nessunLimite, browser));
        Assert.Equal(RegoleCategorie.Fermo.Browser, RegoleCategorie.PercheFermo("exe:chrome.exe", "altro", nessunLimite, browser));
        Assert.Equal(RegoleCategorie.Fermo.Nessuno, RegoleCategorie.PercheFermo(Programma, "altro", nessunLimite, browser));
        // Dalla categoria con un limite non si esce: il limite si aggirerebbe.
        Assert.Equal(RegoleCategorie.Fermo.ConLimite,
            RegoleCategorie.PercheFermo(Programma, "scuola", new HashSet<string> { "categoria:scuola" }, browser));
        Assert.Equal(RegoleCategorie.Fermo.ConLimite,
            RegoleCategorie.PercheFermo(Programma, "altro", new HashSet<string> { "categoria:altro" }, browser));
    }

    [Fact]
    public void La_categoria_scelta_vale_solo_per_chi_non_e_in_una_lista()
    {
        var mie = RegoleCategorie.Mappa(new CategorieMie
        {
            Categorie = { "scuola" },
            Programmi = { [Programma] = "scuola", ["exe:steam.exe"] = "scuola" },
        });
        Assert.Equal("scuola", Categorie.DiProgramma(Programma, mie));
        Assert.Equal("giochi", Categorie.DiProgramma("exe:steam.exe", mie));
        Assert.Equal("altro", Categorie.DiProgramma(Programma));
    }

    // --- Nel motore ------------------------------------------------------------------------

    private static Motore.Motore Motore(CartellaTemporanea c, JsonArray? regole = null)
    {
        var percorsi = new Percorsi(c.Percorso);
        Archivio.ScriviJson(percorsi.Config, new Configurazione
        {
            Server = "http://127.0.0.1:9",
            TokenProtetto = Dpapi.Proteggi("token-di-prova"),
            Dispositivo = new JsonObject { ["id"] = 2, ["nome"] = "Computer", ["tipo"] = "computer" },
            Figlio = new JsonObject { ["id"] = 1, ["nome"] = "Luca" },
        });
        if (regole != null)
        {
            Archivio.ScriviJson(percorsi.Patto, new PattoSalvato
            {
                AggiornatoUtcMs = Tempo.AdessoUtcMs(),
                Patto = new JsonObject { ["regole"] = regole, ["fuso"] = "Europe/Rome" },
            });
        }
        var oggi = Tempo.GiornoDi(Tempo.AdessoUtcMs(), TimeZoneInfo.Local);
        Directory.CreateDirectory(percorsi.Giorni);
        var g = new Giornata { Giorno = oggi, MsAttivi = 20 * 60_000 };
        g.Programmi[Programma] = new VoceProgramma { Nome = "Compitino", Ms = 20 * 60_000 };
        g.MsPerCategoria["categoria:altro"] = 20 * 60_000;
        Archivio.ScriviJson(percorsi.FileGiorno(oggi), g);
        return new Motore.Motore(percorsi);
    }

    private static JsonArray LimiteSu(string chiave) => new(new JsonObject
    {
        ["id"] = 7,
        ["tipo"] = "limite_tempo",
        ["attiva"] = true,
        ["dispositivo_id"] = 2,
        ["parametri"] = new JsonObject { ["app_o_categoria"] = chiave, ["minuti_al_giorno"] = 60 },
    });

    private static long Minuti(JsonObject oggi, string chiave) =>
        ((JsonArray)oggi["categorie"]!).OfType<JsonObject>()
            .Where(c => Json.Testo(c["chiave"]) == chiave)
            .Select(c => Json.Intero(c["minuti"]) ?? 0).FirstOrDefault();

    [Fact]
    public void Spostare_un_programma_porta_con_se_i_minuti_di_oggi_e_resta_dopo_un_riavvio()
    {
        using var c = new CartellaTemporanea();
        using (var m = Motore(c))
        {
            Assert.Equal(20, Minuti(m.Oggi(), "categoria:altro"));
            var r = m.SpostaProgramma(Programma, "  Scuola ");
            Assert.True(Json.Booleano(r["ok"]));
            var oggi = m.Oggi();
            Assert.Equal(20, Minuti(oggi, "categoria:scuola"));
            Assert.Equal(0, Minuti(oggi, "categoria:altro"));
            var programma = ((JsonArray)oggi["programmi"]!).OfType<JsonObject>().Single();
            Assert.Equal("scuola", Json.Testo(programma["categoria"]));
            var categorie = m.CategorieDelFiglio();
            var mia = ((JsonArray)categorie["mie"]!).OfType<JsonObject>().Single();
            Assert.Equal("Scuola", Json.Testo(mia["etichetta"]));
            Assert.Equal(1, Json.Intero(mia["programmi"]));
        }
        using (var m = new Motore.Motore(new Percorsi(c.Percorso)))
        {
            var programma = ((JsonArray)m.Oggi()["programmi"]!).OfType<JsonObject>().Single();
            Assert.Equal("scuola", Json.Testo(programma["categoria"]));
        }
    }

    [Fact]
    public void I_programmi_riconosciuti_e_i_browser_non_si_spostano()
    {
        using var c = new CartellaTemporanea();
        using var m = Motore(c);
        Assert.Equal("non_spostabile", Json.Testo(m.SpostaProgramma("exe:steam.exe", "scuola")["errore"]));
        Assert.Equal("non_spostabile", Json.Testo(m.SpostaProgramma("exe:chrome.exe", "scuola")["errore"]));
        Assert.Equal("nome_non_valido", Json.Testo(m.SpostaProgramma(Programma, "giochi:x")["errore"]));
    }

    [Fact]
    public void Si_puo_spostare_in_una_categoria_del_contratto_e_tornare_in_altre_app()
    {
        using var c = new CartellaTemporanea();
        using var m = Motore(c);
        Assert.True(Json.Booleano(m.SpostaProgramma(Programma, "Giochi")["ok"]));
        Assert.Equal(20, Minuti(m.Oggi(), "categoria:giochi"));
        Assert.True(Json.Booleano(m.SpostaProgramma(Programma, "altro")["ok"]));
        Assert.Equal(20, Minuti(m.Oggi(), "categoria:altro"));
    }

    [Fact]
    public void Con_un_limite_la_categoria_non_si_elimina_e_i_suoi_programmi_non_escono()
    {
        using var c = new CartellaTemporanea();
        using var m = Motore(c, LimiteSu("categoria:scuola"));
        Assert.True(Json.Booleano(m.SpostaProgramma(Programma, "scuola")["ok"]));
        Assert.Equal("con_limite", Json.Testo(m.SpostaProgramma(Programma, "altro")["errore"]));
        Assert.Equal("con_limite", Json.Testo(m.EliminaCategoria("Scuola")["errore"]));
        var programma = ((JsonArray)m.CategorieDelFiglio()["programmi"]!).OfType<JsonObject>().Single();
        Assert.False(Json.Booleano(programma["spostabile"]));
        Assert.Equal("con_limite", Json.Testo(programma["fermo"]));
    }

    [Fact]
    public void Senza_limite_eliminare_la_categoria_riporta_i_programmi_in_altre_app()
    {
        using var c = new CartellaTemporanea();
        using var m = Motore(c);
        Assert.True(Json.Booleano(m.SpostaProgramma(Programma, "scuola")["ok"]));
        Assert.True(Json.Booleano(m.EliminaCategoria("Scuola")["ok"]));
        Assert.Equal(20, Minuti(m.Oggi(), "categoria:altro"));
        Assert.Empty((JsonArray)m.CategorieDelFiglio()["mie"]!);
    }

    [Fact]
    public void Un_limite_sulla_categoria_nuova_conta_i_minuti_dei_suoi_programmi()
    {
        using var c = new CartellaTemporanea();
        using var m = Motore(c, LimiteSu("categoria:Scuola"));
        Assert.True(Json.Booleano(m.SpostaProgramma(Programma, "Scuola")["ok"]));
        var perRegola = (JsonObject)m.Oggi()["regole"]!;
        Assert.Equal(20, Json.Intero((perRegola["7"] as JsonObject)?["minuti"]));
    }

    [Fact]
    public void Una_categoria_vuota_si_crea_per_darle_un_limite()
    {
        using var c = new CartellaTemporanea();
        using var m = Motore(c);
        var r = m.NuovaCategoria("Disegno");
        Assert.Equal("disegno", Json.Testo(r["nome"]));
        Assert.Single((JsonArray)m.CategorieDelFiglio()["mie"]!);
        Assert.Equal("nome_non_valido", Json.Testo(m.NuovaCategoria("Social")["errore"]));
    }
}
