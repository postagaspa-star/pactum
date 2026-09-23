using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>Le regole valutate sul computer: limiti con bonus, fasce oltre la mezzanotte, deduplica.</summary>
public class ValutatoreTest
{
    private static readonly string[] TuttiIGiorni = { "lun", "mar", "mer", "gio", "ven", "sab", "dom" };

    private static Regola Limite(long id, string chiave, int minuti) =>
        new(id, TipiRegola.LimiteTempo, new JsonObject { ["app_o_categoria"] = chiave, ["minuti_al_giorno"] = minuti }, true, 2);

    private static Regola Fascia(long id, string dalle, string alle, params string[] giorni) =>
        new(id, TipiRegola.FasciaOraria, new JsonObject
        {
            ["dalle"] = dalle,
            ["alle"] = alle,
            ["giorni"] = new JsonArray((giorni.Length == 0 ? TuttiIGiorni : giorni).Select(g => (JsonNode)g).ToArray()),
        }, true, 2);

    private static List<Sforamento> Valuta(IEnumerable<Regola> regole, Func<string, long> minuti, Func<long, long, long> intervallo, string adesso,
        Dictionary<string, int>? bonus = null) =>
        Valutatore.Valuta(regole, bonus ?? new Dictionary<string, int>(), minuti, intervallo, Fuso.Ms(adesso), Fuso.Roma);

    [Fact]
    public void Lo_sforamento_di_un_limite_porta_il_giorno_del_computer()
    {
        var s = Assert.Single(Valuta(new[] { Limite(1, "exe:minecraft.exe", 60) }, _ => 100, (_, _) => 0, "2026-09-19T22:10:00"));
        var d = Valutatore.DettagliSforamento(s, "2026-09-19");
        Assert.Equal(1, Json.Intero(d["regola_id"]));
        Assert.Equal("2026-09-19", Json.Testo(d["giorno"]));
        Assert.Equal(60, Json.Intero(d["limite_efficace"]));
        Assert.Equal(40, Json.Intero(d["minuti_oltre"]));
    }

    [Fact]
    public void Arrivare_al_limite_non_e_andare_oltre()
    {
        Assert.Empty(Valuta(new[] { Limite(1, "exe:minecraft.exe", 60) }, _ => 60, (_, _) => 0, "2026-09-19T22:10:00"));
    }

    [Fact]
    public void Il_bonus_di_oggi_allunga_solo_la_sua_regola()
    {
        var regola = Limite(1, "sito:youtube.com", 60);
        var bonus = new Dictionary<string, int> { ["1"] = 15, ["2"] = 30 };
        Assert.Equal(75, Valutatore.LimiteEfficace(regola, bonus));
        Assert.Empty(Valuta(new[] { regola }, _ => 70, (_, _) => 0, "2026-09-19T20:00:00", bonus));
        var s = Assert.Single(Valuta(new[] { regola }, _ => 80, (_, _) => 0, "2026-09-19T20:00:00", bonus));
        Assert.Equal(5, s.MinutiOltre);
        Assert.Equal(75, s.LimiteEfficace);
        Assert.Null(Valutatore.LimiteEfficace(Fascia(3, "23:00", "07:00"), bonus));
    }

    [Fact]
    public void Programmi_siti_e_categorie_si_leggono_dalla_misura_di_oggi()
    {
        var oggi = Giornata.Nuova("2026-09-19");
        oggi.Programmi["exe:minecraft.exe"] = new VoceProgramma { Nome = "Minecraft", Ms = 65 * 60_000 };
        oggi.Siti["youtube.com"] = new VoceSito { Ms = 50 * 60_000, Visite = 3 };
        oggi.MsPerCategoria["categoria:social"] = 31 * 60_000;
        var regole = new[] { Limite(1, "exe:minecraft.exe", 60), Limite(2, "sito:youtube.com", 60), Limite(3, "categoria:social", 30) };
        var sforamenti = Valuta(regole, oggi.MinutiDi, oggi.MinutiNellIntervallo, "2026-09-19T21:00:00");
        Assert.Equal(new long[] { 1, 3 }, sforamenti.Select(s => s.RegolaId).OrderBy(x => x));
    }

    [Fact]
    public void Una_regola_non_attiva_non_si_valuta()
    {
        var spenta = Limite(1, "exe:minecraft.exe", 10) with { Attiva = false };
        Assert.Empty(Valuta(new[] { spenta }, _ => 100, (_, _) => 0, "2026-09-19T21:00:00"));
    }

    [Fact]
    public void La_coda_mattutina_di_una_fascia_notturna_cade_nel_giorno_in_cui_e_partita()
    {
        var s = Assert.Single(Valuta(new[] { Fascia(2, "23:00", "07:00") }, _ => 0, (_, _) => 30, "2026-09-20T01:00:00"));
        Assert.Equal("2026-09-19", s.GiornoAncora);
        Assert.Equal("2026-09-19", Json.Testo(Valutatore.DettagliSforamento(s, "2026-09-20")["giorno"]));
        Assert.Null(Valutatore.DettagliSforamento(s, "2026-09-20")["limite_efficace"]);
    }

    [Fact]
    public void Gli_intervalli_di_oggi_sono_la_coda_di_ieri_e_la_testa_di_oggi()
    {
        var intervalli = Valutatore.IntervalliProibitiOggi(new TimeOnly(22, 0), new TimeOnly(7, 0), TuttiIGiorni, Fuso.Ms("2026-09-20T23:30:00"), Fuso.Roma);
        Assert.Equal(2, intervalli.Count);
        Assert.Equal("2026-09-19", intervalli[0].GiornoAncora);
        Assert.Equal(Fuso.Ms("2026-09-20T00:00:00"), intervalli[0].Inizio);
        Assert.Equal(Fuso.Ms("2026-09-20T07:00:00"), intervalli[0].Fine);
        Assert.Equal("2026-09-20", intervalli[1].GiornoAncora);
        Assert.Equal(Fuso.Ms("2026-09-20T22:00:00"), intervalli[1].Inizio);
        Assert.Equal(Fuso.Ms("2026-09-20T23:30:00"), intervalli[1].Fine);
    }

    [Fact]
    public void Due_occorrenze_nello_stesso_giorno_sono_due_sforamenti()
    {
        var sforamenti = Valuta(new[] { Fascia(2, "22:00", "07:00") }, _ => 0, (_, _) => 5, "2026-09-20T23:30:00");
        Assert.Equal(new[] { "2026-09-19", "2026-09-20" }, sforamenti.Select(s => s.GiornoAncora));
    }

    [Fact]
    public void Sotto_il_minuto_nella_fascia_non_e_uno_sforamento()
    {
        Assert.Empty(Valuta(new[] { Fascia(2, "22:00", "07:00") }, _ => 0, (_, _) => 0, "2026-09-20T23:30:00"));
    }

    [Fact]
    public void Una_fascia_che_oggi_non_vale_non_conta()
    {
        // Il 19/09/2026 è sabato.
        Assert.Empty(Valuta(new[] { Fascia(2, "14:00", "16:00", "lun") }, _ => 0, (_, _) => 50, "2026-09-19T15:00:00"));
        Assert.Single(Valuta(new[] { Fascia(2, "14:00", "16:00", "sab") }, _ => 0, (_, _) => 50, "2026-09-19T15:00:00"));
    }

    [Fact]
    public void Un_solo_sforamento_al_giorno_per_regola()
    {
        var registro = new RegistroSforamenti();
        var s = new Sforamento(1, TipiRegola.LimiteTempo, 60, 5);
        Assert.Single(registro.Nuovi(new[] { s }, "2026-09-19"));
        Assert.Empty(registro.Nuovi(new[] { s with { MinutiOltre = 30 } }, "2026-09-19"));
        Assert.Single(registro.Nuovi(new[] { s }, "2026-09-20"));
    }

    [Fact]
    public void La_sera_e_la_mattina_della_stessa_notte_sono_uno_sforamento_solo()
    {
        var registro = new RegistroSforamenti();
        var fascia = Fascia(2, "23:00", "07:00");
        // Computer usato solo dalle 23 del 19 in poi.
        long daQuando = Fuso.Ms("2026-09-19T23:00:00");
        Func<long, long, long> uso = (inizio, _) => inizio >= daQuando ? 20 : 0;
        var sera = Valuta(new[] { fascia }, _ => 0, uso, "2026-09-19T23:40:00");
        Assert.Equal("2026-09-19", Assert.Single(registro.Nuovi(sera, "2026-09-19")).GiornoAncora);
        var mattina = Valuta(new[] { fascia }, _ => 0, uso, "2026-09-20T01:00:00");
        Assert.Empty(registro.Nuovi(mattina, "2026-09-20"));
        // La notte dopo è un'occorrenza nuova.
        var notteDopo = Valuta(new[] { fascia }, _ => 0, uso, "2026-09-20T23:30:00");
        Assert.Equal("2026-09-20", Assert.Single(registro.Nuovi(notteDopo, "2026-09-20")).GiornoAncora);
    }

    [Fact]
    public void Il_registro_dimentica_i_giorni_vecchi()
    {
        var registro = new RegistroSforamenti();
        registro.Aggiungi(1, "2026-09-01");
        registro.Aggiungi(1, "2026-09-19");
        registro.Pota(new DateOnly(2026, 9, 10));
        Assert.False(registro.Contiene(1, "2026-09-01"));
        Assert.True(registro.Contiene(1, "2026-09-19"));
    }

    [Fact]
    public void Prima_della_fascia_si_sa_quando_parte()
    {
        var stato = Valutatore.Stato(Fascia(2, "23:00", "07:00"), Fuso.Ms("2026-09-19T19:47:00"), Fuso.Roma);
        Assert.Equal(new StatoFascia(false, "23:00", "07:00"), stato);
    }

    [Fact]
    public void Dentro_una_fascia_notturna_dopo_mezzanotte_e_attiva()
    {
        var stato = Valutatore.Stato(Fascia(2, "23:00", "07:00"), Fuso.Ms("2026-09-20T01:00:00"), Fuso.Roma);
        Assert.Equal(new StatoFascia(true, "23:00", "07:00"), stato);
    }

    [Fact]
    public void Una_fascia_senza_giorni_futuri_non_ha_prossimo_inizio()
    {
        var stato = Valutatore.Stato(Fascia(2, "23:00", "07:00", "xyz"), Fuso.Ms("2026-09-20T01:00:00"), Fuso.Roma);
        Assert.Equal(new StatoFascia(false, null, "07:00"), stato);
    }

    [Fact]
    public void Il_patto_da_valutare_e_quello_di_questo_computer()
    {
        var patto = new JsonObject
        {
            ["regole"] = new JsonArray(
                new JsonObject { ["id"] = 1, ["tipo"] = "limite_tempo", ["parametri"] = new JsonObject { ["app_o_categoria"] = "exe:a.exe", ["minuti_al_giorno"] = 5 }, ["attiva"] = true, ["dispositivo_id"] = 2 },
                new JsonObject { ["id"] = 2, ["tipo"] = "limite_tempo", ["parametri"] = new JsonObject { ["app_o_categoria"] = "com.app", ["minuti_al_giorno"] = 5 }, ["attiva"] = true, ["dispositivo_id"] = 1 },
                new JsonObject { ["id"] = 3, ["tipo"] = "vita_reale", ["parametri"] = new JsonObject(), ["attiva"] = true, ["dispositivo_id"] = null },
                new JsonObject { ["id"] = 4, ["tipo"] = "fascia_oraria", ["parametri"] = new JsonObject(), ["attiva"] = true, ["dispositivo"] = new JsonObject { ["id"] = 2 } }),
        };
        var locale = new PattoLocale(patto, 0);
        Assert.Equal(new long[] { 1, 4 }, locale.RegoleDelComputer(2).Select(r => r.Id));
    }

    [Fact]
    public void I_bonus_di_una_copia_di_ieri_non_valgono_oggi()
    {
        var patto = new JsonObject { ["fuso"] = "Europe/Rome", ["bonus_oggi_per_regola"] = new JsonObject { ["1"] = 15 } };
        var diOggi = new PattoLocale(patto, Fuso.Ms("2026-09-19T08:00:00"));
        Assert.Equal(15, diOggi.BonusOggi(Fuso.Ms("2026-09-19T21:00:00"))["1"]);
        var diIeri = new PattoLocale((JsonObject)patto.DeepClone(), Fuso.Ms("2026-09-18T23:00:00"));
        Assert.Empty(diIeri.BonusOggi(Fuso.Ms("2026-09-19T08:00:00")));
    }
}
