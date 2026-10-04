using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>
/// (0.13, contratto v3.6) Il blocco delle faccende, logica pura: quando copre, come parte da solo all'ora
/// giusta anche senza rete, e come si legge la risposta di GET /api/faccende/blocco.
/// </summary>
public class BloccoTest
{
    private static readonly long Ora = Fuso.Ms("2026-10-03T15:00:00");

    private static Faccenda Faccenda(long id, string titolo, long bloccoDaMs, string? chi = "Mamma", string? nota = null) =>
        new() { Id = id, Titolo = titolo, Nota = nota, BloccoDaMs = bloccoDaMs, DataDa = chi };

    [Fact]
    public void Nessuna_faccenda_niente_blocco()
    {
        var s = StatoBlocco.Vuoto;
        Assert.False(s.AttivoA(Ora));
        Assert.Null(s.Prossimo(Ora));
        Assert.Empty(s.DaMostrare());
    }

    [Fact]
    public void Una_faccenda_col_blocco_da_passato_copre()
    {
        var s = new StatoBlocco { DaFare = { Faccenda(1, "Lavastoviglie", Ora - 60_000) } };
        Assert.True(s.AttivoA(Ora));
        Assert.Null(s.Prossimo(Ora));
    }

    [Fact]
    public void Una_faccenda_programmata_non_copre_ancora_ma_dice_quando_partira()
    {
        // Blocco programmato fra un'ora: non attivo adesso, ma "prossimo" dice l'ora giusta.
        var inizio = Ora + 60 * 60_000;
        var s = new StatoBlocco { DaFare = { Faccenda(1, "Compiti", inizio) } };
        Assert.False(s.AttivoA(Ora));
        Assert.Equal(inizio, s.Prossimo(Ora));
        // Arrivata l'ora, copre da solo (anche senza una risposta nuova dal server).
        Assert.True(s.AttivoA(inizio));
        Assert.True(s.AttivoA(inizio + 1));
        Assert.Null(s.Prossimo(inizio));
    }

    [Fact]
    public void Prossimo_e_il_piu_vicino_nel_futuro()
    {
        var s = new StatoBlocco
        {
            DaFare =
            {
                Faccenda(1, "Tardi", Ora + 3 * 60 * 60_000),
                Faccenda(2, "Presto", Ora + 60 * 60_000),
                Faccenda(3, "Già attiva", Ora - 60_000),
            },
        };
        // C'è già una passata: attivo. Prossimo guarda comunque la più vicina fra le future.
        Assert.True(s.AttivoA(Ora));
        Assert.Equal(Ora + 60 * 60_000, s.Prossimo(Ora));
    }

    [Fact]
    public void Le_faccende_da_mostrare_sono_dalla_piu_vecchia()
    {
        var s = new StatoBlocco
        {
            DaFare =
            {
                Faccenda(5, "Terza", Ora + 20_000),
                Faccenda(3, "Prima", Ora - 20_000),
                Faccenda(4, "Seconda", Ora),
            },
        };
        Assert.Equal(new long[] { 3, 4, 5 }, s.DaMostrare().Select(f => f.Id));
    }

    [Fact]
    public void Legge_la_risposta_del_server_con_blocco_da_in_ms()
    {
        var json = new JsonObject
        {
            ["attivo"] = true,
            ["dal"] = "2026-10-03T14:00:00+00:00",
            ["prossimo"] = null,
            ["da_fare"] = new JsonArray(
                new JsonObject
                {
                    ["id"] = 5,
                    ["titolo"] = "Svuota la lavastoviglie",
                    ["nota"] = "anche le pentole",
                    ["blocco_da"] = "2026-10-03T14:00:00+00:00",
                    ["creata_da"] = new JsonObject { ["id"] = 2, ["nome"] = "Mamma" },
                    ["bocciature"] = 0,
                }),
        };
        var s = Blocco.Leggi(json);
        var f = Assert.Single(s.DaFare);
        Assert.Equal(5, f.Id);
        Assert.Equal("Svuota la lavastoviglie", f.Titolo);
        Assert.Equal("anche le pentole", f.Nota);
        Assert.Equal("Mamma", f.DataDa);
        Assert.Equal(Fuso.Ms("2026-10-03T16:00:00"), f.BloccoDaMs); // 14:00 UTC = 16:00 a Roma (ora legale)
        Assert.True(s.AttivoA(Fuso.Ms("2026-10-03T16:30:00")));
    }

    [Fact]
    public void Una_faccenda_senza_un_blocco_da_leggibile_si_lascia_cadere()
    {
        var json = new JsonObject
        {
            ["da_fare"] = new JsonArray(
                new JsonObject { ["id"] = 1, ["titolo"] = "Buona", ["blocco_da"] = "2026-10-03T14:00:00+00:00" },
                new JsonObject { ["id"] = 2, ["titolo"] = "Senza data", ["blocco_da"] = null },
                new JsonObject { ["id"] = 3, ["titolo"] = "Data sbagliata", ["blocco_da"] = "domani" },
                new JsonObject { ["titolo"] = "Senza id", ["blocco_da"] = "2026-10-03T14:00:00+00:00" }),
        };
        var s = Blocco.Leggi(json);
        Assert.Equal(new long[] { 1 }, s.DaFare.Select(f => f.Id));
    }

    [Fact]
    public void Il_corpo_vuoto_o_senza_da_fare_e_blocco_spento()
    {
        Assert.Empty(Blocco.Leggi(null).DaFare);
        Assert.False(Blocco.Leggi(null).AttivoA(Ora));
        Assert.Empty(Blocco.Leggi(new JsonObject()).DaFare);
        Assert.Empty(Blocco.Leggi(new JsonObject { ["attivo"] = false, ["da_fare"] = new JsonArray() }).DaFare);
    }

    [Fact]
    public void Se_il_server_dice_attivo_si_copre_anche_con_l_orologio_del_PC_indietro()
    {
        // Il server dice attivo, ma il blocco_da sembra nel futuro (orologio del PC indietro): si copre lo stesso.
        var json = new JsonObject
        {
            ["attivo"] = true,
            ["da_fare"] = new JsonArray(new JsonObject
            {
                ["id"] = 1,
                ["titolo"] = "Compiti",
                ["blocco_da"] = "2026-10-03T14:00:00+00:00",
            }),
        };
        var s = Blocco.Leggi(json);
        Assert.True(s.AttivoServer);
        // Un istante molto prima del blocco_da: il calcolo locale direbbe "no", ma il server dice "attivo".
        Assert.True(s.AttivoA(Fuso.Ms("2026-10-03T08:00:00")));
    }

    [Fact]
    public void Lo_stato_generico_copre_sempre()
    {
        var s = StatoBlocco.Generico();
        Assert.True(s.AttivoServer);
        Assert.True(s.AttivoA(0));
        Assert.True(s.AttivoA(Ora));
        Assert.Equal("Ci sono lavori di casa da fare", Assert.Single(s.DaMostrare()).Titolo);
    }

    [Fact]
    public void Il_segno_attivo_del_server_si_salva_e_si_rilegge()
    {
        using var c = new CartellaTemporanea();
        var s = Blocco.Leggi(new JsonObject
        {
            ["attivo"] = true,
            ["da_fare"] = new JsonArray(new JsonObject { ["id"] = 1, ["titolo"] = "x", ["blocco_da"] = "2026-10-03T14:00:00+00:00" }),
        });
        Archivio.ScriviJson(c.File("blocco.json"), s);
        var riletto = Archivio.LeggiJson<StatoBlocco>(c.File("blocco.json"))!;
        Assert.True(riletto.AttivoServer);
    }

    [Fact]
    public void Lo_stato_si_salva_e_si_rilegge_uguale()
    {
        using var c = new CartellaTemporanea();
        var s = new StatoBlocco { DaFare = { Faccenda(7, "Spazza", Ora - 1000, "Papà", "il corridoio") } };
        Archivio.ScriviJson(c.File("blocco.json"), s);
        var riletto = Archivio.LeggiJson<StatoBlocco>(c.File("blocco.json"))!;
        var f = Assert.Single(riletto.DaFare);
        Assert.Equal(7, f.Id);
        Assert.Equal("Spazza", f.Titolo);
        Assert.Equal("Papà", f.DataDa);
        Assert.Equal("il corridoio", f.Nota);
        Assert.Equal(Ora - 1000, f.BloccoDaMs);
        Assert.True(riletto.AttivoA(Ora));
    }

    // ---------- (0.14, contratto v3.7) "Lavori di casa" in quello che il figlio legge ----------

    [Fact]
    public void La_copertura_dice_lavori_di_casa_e_mai_faccende()
    {
        Assert.Equal("Prima i lavori di casa", Testi.TitoloBlocco);
        Assert.Equal("Si sblocca da solo quando dal telefono hai mandato la foto di ogni lavoro.", Testi.SottoBlocco);
        var testo = Testi.TestoBlocco(new[] { Faccenda(1, "Riordina la camera", Ora, "Papà", "anche sotto il letto") });
        Assert.Equal(string.Join(Environment.NewLine,
            "Prima i lavori di casa",
            "Riordina la camera — da Papà: anche sotto il letto",
            "Si sblocca da solo quando dal telefono hai mandato la foto di ogni lavoro."), testo);
        Assert.DoesNotContain("faccend", testo, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("faccend", StatoBlocco.Generico().DaMostrare()[0].Titolo, StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData("nuove_faccende", "Mamma ti ha dato 3 lavori di casa", "Nuovi lavori di casa", "Mamma ti ha dato 3 lavori di casa.")]
    [InlineData("nuove_faccende", "", "Nuovi lavori di casa", "Li trovi in Pactum, in Lavori di casa. Le foto si mandano dal telefono.")]
    [InlineData("faccenda_bocciata", "Papà ha bocciato «Riordina la camera»: si vede ancora il pavimento", "Lavoro di casa rimandato", "Papà ha bocciato «Riordina la camera»: si vede ancora il pavimento.")]
    [InlineData("faccenda_bocciata", null, "Lavoro di casa rimandato", "Rifallo e manda una foto nuova dal telefono.")]
    [InlineData("faccenda_annullata", "", "Lavoro di casa annullato", "Non c'è più da farlo.")]
    public void I_fumetti_dei_lavori_di_casa_aprono_la_loro_sezione(string tipo, string? messaggio, string titolo, string testo)
    {
        var (t, x, sezione) = Testi.Notifica(tipo, messaggio, null);
        Assert.Equal(titolo, t);
        Assert.Equal(testo, x);
        Assert.Equal("faccende", sezione);
        Assert.DoesNotContain("faccend", t + x, StringComparison.OrdinalIgnoreCase);
    }
}
