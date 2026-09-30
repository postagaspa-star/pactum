using System.Text.Json;
using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>
/// (0.9) La decisione "sforamento nuovo → avviso a tutto schermo sì/no": lo stesso dedup del
/// fumetto (una volta per regola per giorno), e quello che l'avviso racconta.
/// </summary>
public class SegnalazioniTest
{
    private const string Oggi = "2026-09-30";

    private static Regola Limite(long id, string chiave, int minuti) =>
        new(id, TipiRegola.LimiteTempo, new JsonObject { ["app_o_categoria"] = chiave, ["minuti_al_giorno"] = minuti }, true, 2);

    private static Regola Fascia(long id, string dalle, string alle) =>
        new(id, TipiRegola.FasciaOraria, new JsonObject
        {
            ["dalle"] = dalle,
            ["alle"] = alle,
            ["giorni"] = new JsonArray("lun", "mar", "mer", "gio", "ven", "sab", "dom"),
        }, true, 2);

    private static Sforamento Oltre(long id, int limite, int oltre) => new(id, TipiRegola.LimiteTempo, limite, oltre);

    private static Segnalazioni.Decisione Decidi(RegistroSforamenti registro, string giorno, IReadOnlyCollection<Regola> regole, params Sforamento[] trovati) =>
        Segnalazioni.Decidi(trovati, giorno, registro, regole, null);

    [Fact]
    public void Uno_sforamento_nuovo_va_a_tutto_schermo()
    {
        var registro = new RegistroSforamenti();
        var d = Decidi(registro, Oggi, new[] { Limite(7, "totale", 120) }, Oltre(7, 120, 10));
        Assert.Equal(7, Assert.Single(d.Nuovi).RegolaId);
        var avviso = Assert.Single(d.ATuttoSchermo);
        Assert.Equal(7, avviso.RegolaId);
        Assert.Equal("Tutto il computer", avviso.Nome);
        Assert.Equal(130, avviso.MinutiUsati);
        Assert.Equal(120, avviso.LimiteEfficace);
        Assert.Equal(120, avviso.Limite);
        Assert.Equal(10, avviso.MinutiOltre);
        Assert.True(registro.Contiene(7, Oggi));
    }

    [Fact]
    public void Gia_segnalato_oggi_non_torna_ne_nel_fumetto_ne_a_tutto_schermo()
    {
        var registro = new RegistroSforamenti();
        var regole = new[] { Limite(7, "totale", 120) };
        Assert.Single(Decidi(registro, Oggi, regole, Oltre(7, 120, 1)).ATuttoSchermo);
        // Un quarto d'ora dopo si è ancora più oltre: niente di nuovo.
        var dopo = Decidi(registro, Oggi, regole, Oltre(7, 120, 16));
        Assert.Empty(dopo.Nuovi);
        Assert.Empty(dopo.ATuttoSchermo);
    }

    [Fact]
    public void Nemmeno_se_un_bonus_alza_il_limite_e_si_va_oltre_di_nuovo()
    {
        var registro = new RegistroSforamenti();
        var regole = new[] { Limite(7, "totale", 120) };
        Assert.Single(Decidi(registro, Oggi, regole, Oltre(7, 120, 3)).ATuttoSchermo);
        Assert.Empty(Decidi(registro, Oggi, regole, Oltre(7, 135, 2)).ATuttoSchermo);
    }

    [Fact]
    public void Il_giorno_dopo_la_stessa_regola_torna_a_tutto_schermo()
    {
        var registro = new RegistroSforamenti();
        var regole = new[] { Limite(7, "totale", 120) };
        Assert.Single(Decidi(registro, Oggi, regole, Oltre(7, 120, 3)).ATuttoSchermo);
        Assert.Single(Decidi(registro, "2026-10-01", regole, Oltre(7, 120, 3)).ATuttoSchermo);
    }

    [Fact]
    public void Dopo_un_riavvio_il_registro_su_disco_ricorda_gli_avvisi_gia_dati()
    {
        var registro = new RegistroSforamenti();
        var regole = new[] { Limite(7, "totale", 120) };
        Assert.Single(Decidi(registro, Oggi, regole, Oltre(7, 120, 3)).ATuttoSchermo);
        // Come fa il motore: sforamenti.json scritto e riletto all'avvio.
        var riletto = JsonSerializer.Deserialize<RegistroSforamenti>(JsonSerializer.Serialize(registro, Json.OpzioniFile), Json.OpzioniFile)!;
        Assert.Empty(Decidi(riletto, Oggi, regole, Oltre(7, 120, 9)).ATuttoSchermo);
    }

    [Fact]
    public void Solo_le_regole_nuove_vanno_a_tutto_schermo_anche_se_le_trova_insieme()
    {
        var registro = new RegistroSforamenti();
        var regole = new[] { Limite(7, "totale", 120), Limite(8, "exe:minecraft.exe", 60), Limite(9, "sito:youtube.com", 30) };
        Assert.Single(Decidi(registro, Oggi, regole, Oltre(7, 120, 3)).ATuttoSchermo);
        var d = Decidi(registro, Oggi, regole, Oltre(7, 120, 8), Oltre(8, 60, 2), Oltre(9, 30, 1));
        Assert.Equal(new long[] { 8, 9 }, d.Nuovi.Select(s => s.RegolaId));
        Assert.Equal(new long[] { 8, 9 }, d.ATuttoSchermo.Select(a => a.RegolaId));
        Assert.Equal(new[] { "minecraft", "youtube.com" }, d.ATuttoSchermo.Select(a => a.Nome));
    }

    [Fact]
    public void Niente_sforamenti_niente_avviso()
    {
        var d = Decidi(new RegistroSforamenti(), Oggi, new[] { Limite(7, "totale", 120) });
        Assert.Empty(d.Nuovi);
        Assert.Empty(d.ATuttoSchermo);
    }

    [Fact]
    public void La_sera_e_la_mattina_della_stessa_fascia_sono_un_avviso_solo()
    {
        var registro = new RegistroSforamenti();
        var regole = new[] { Fascia(3, "23:00", "07:00") };
        var sera = new Sforamento(3, TipiRegola.FasciaOraria, null, 20, "2026-09-30");
        var avviso = Assert.Single(Decidi(registro, "2026-09-30", regole, sera).ATuttoSchermo);
        Assert.True(avviso.Fascia);
        Assert.Equal("Niente computer dalle 23:00 alle 07:00", avviso.Nome);
        Assert.Equal(20, avviso.MinutiUsati);
        // Dopo mezzanotte la stessa occorrenza (ancorata al 30) non riapre l'avviso.
        var mattina = new Sforamento(3, TipiRegola.FasciaOraria, null, 45, "2026-09-30");
        Assert.Empty(Decidi(registro, "2026-10-01", regole, mattina).ATuttoSchermo);
        // La notte dopo è un'occorrenza nuova.
        var notteDopo = new Sforamento(3, TipiRegola.FasciaOraria, null, 5, "2026-10-01");
        Assert.Single(Decidi(registro, "2026-10-01", regole, notteDopo).ATuttoSchermo);
    }

    [Fact]
    public void Una_regola_sparita_dal_patto_ha_comunque_un_avviso_leggibile()
    {
        var d = Decidi(new RegistroSforamenti(), Oggi, Array.Empty<Regola>(), Oltre(5, 60, 4));
        var a = Assert.Single(d.ATuttoSchermo);
        Assert.Equal("?", a.Nome);
        Assert.Null(a.Limite);
        Assert.Equal(64, a.MinutiUsati);
    }

    // ---------- Cosa racconta l'avviso ----------

    [Fact]
    public void I_nomi_dei_bersagli_sono_quelli_dell_interfaccia()
    {
        var oggi = Giornata.Nuova(Oggi);
        oggi.Programmi["exe:minecraft.exe"] = new VoceProgramma { Nome = "Minecraft", Ms = 1 };
        Assert.Equal("Tutto il computer", Testi.NomeBersaglio("totale", oggi));
        Assert.Equal("Tutto il computer", Testi.NomeBersaglio(" TOTALE ", null));
        Assert.Equal("Minecraft", Testi.NomeBersaglio("exe:minecraft.exe", oggi));
        Assert.Equal("roblox", Testi.NomeBersaglio("exe:roblox.exe", oggi));
        Assert.Equal("youtube.com", Testi.NomeBersaglio("sito:youtube.com", oggi));
        Assert.Equal("Social", Testi.NomeBersaglio("categoria:social", oggi));
        Assert.Equal("?", Testi.NomeBersaglio(null, oggi));
    }

    [Theory]
    [InlineData(0, "0 min")]
    [InlineData(48, "48 min")]
    [InlineData(60, "1 h")]
    [InlineData(75, "1 h 15 min")]
    [InlineData(180, "3 h")]
    [InlineData(-5, "0 min")]
    public void Le_durate_si_dicono_come_nell_interfaccia(long minuti, string testo)
    {
        Assert.Equal(testo, Testi.Durata(minuti));
    }

    [Fact]
    public void L_avviso_dice_la_regola_quanto_hai_usato_e_il_limite_che_ti_sei_dato()
    {
        var regola = Limite(7, "totale", 120);
        var a = Avviso.Da(new Sforamento(7, TipiRegola.LimiteTempo, 135, 10), regola, null);
        Assert.Equal(15, a.Bonus);
        Assert.Equal(
            "Oggi sei andato oltre" + Environment.NewLine +
            "Tutto il computer: 2 h 25 min su 2 h 15 min, 10 min oltre. Il limite che ti sei dato: 2 h al giorno, più 15 min di bonus oggi." + Environment.NewLine +
            "Nessun blocco: è il tuo patto.",
            Testi.TestoAvviso(new[] { a }));
        var senzaBonus = Avviso.Da(new Sforamento(7, TipiRegola.LimiteTempo, 120, 1), regola, null);
        Assert.Equal("Il limite che ti sei dato: 2 h al giorno.", Testi.LimiteDato(senzaBonus.Limite!.Value, senzaBonus.Bonus));
    }

    [Fact]
    public void Con_sole_fasce_il_titolo_e_quello_delle_fasce()
    {
        var fascia = Avviso.Da(new Sforamento(3, TipiRegola.FasciaOraria, null, 20, Oggi), Fascia(3, "22:00", "07:00"), null);
        var limite = Avviso.Da(Oltre(7, 120, 10), Limite(7, "totale", 120), null);
        Assert.Equal("Hai usato il computer in una fascia che ti sei imposto", Testi.TitoloAvviso(new[] { fascia }));
        Assert.Equal("Oggi sei andato oltre", Testi.TitoloAvviso(new[] { fascia, limite }));
        Assert.Contains("Niente computer dalle 22:00 alle 07:00. Oggi 20 min di computer dentro questa fascia.", Testi.TestoAvviso(new[] { fascia }));
    }

    [Fact]
    public void Il_fumetto_resta_con_le_parole_di_sempre()
    {
        var limite = Avviso.Da(Oltre(7, 120, 10), Limite(7, "totale", 120), null);
        Assert.Equal(("Oggi sei andato oltre", "Tutto il computer: oggi sei andato 10 min oltre il limite che ti sei dato (120 min). Nessun blocco: è il tuo patto."),
            Testi.Fumetto(limite));
        var fascia = Avviso.Da(new Sforamento(3, TipiRegola.FasciaOraria, null, 20, Oggi), Fascia(3, "22:00", "07:00"), null);
        Assert.Equal(("Hai usato il computer in una fascia che ti sei imposto", "Hai usato il computer 20 min in una fascia che ti sei imposto (22:00–07:00). Nessun blocco: è il tuo patto."),
            Testi.Fumetto(fascia));
    }

    [Fact]
    public void Le_parole_sono_le_stesse_dell_interfaccia()
    {
        // L'interfaccia viaggia dentro l'exe (Pactum.csproj): è lo stesso testi.js della cartella ui.
        using var flusso = typeof(Testi).Assembly.GetManifestResourceStream("ui/testi.js");
        Assert.NotNull(flusso);
        using var lettore = new StreamReader(flusso!);
        var testi = lettore.ReadToEnd();
        Assert.Contains("'" + Testi.TuttoIlComputer + "'", testi);
        Assert.Contains("const TOTALE = '" + Bersagli.Totale + "'", testi);
        // La durata: "1 h", "1 h 15 min" come Testi.Durata.
        Assert.Contains("if (m % 60 === 0) return m / 60 + ' h';", testi);
        Assert.Contains("return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';", testi);
    }
}
