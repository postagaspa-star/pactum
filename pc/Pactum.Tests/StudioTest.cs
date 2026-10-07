using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>
/// (0.18, contratto v4.0) La Sessione Studio, logica pura: lettura della configurazione e delle partenze,
/// "sono in Studio adesso?" (parola del server, partenza passata senza rete, mezzanotte), e la copertura
/// dello schermo (programmi della lista, firme, browser e siti).
/// </summary>
public class StudioTest
{
    private static readonly TimeZoneInfo Roma = Fuso.Roma;

    private static string Iso(string localeRoma)
    {
        long ms = Fuso.Ms(localeRoma);
        return DateTimeOffset.FromUnixTimeMilliseconds(ms).ToString("yyyy-MM-dd'T'HH:mm:ss'+00:00'");
    }

    private static JsonObject PartenzaJson(string giorno, string inizioRoma, string? chiudibileRoma, int minuti) =>
        new()
        {
            ["giorno"] = giorno,
            ["inizio_ts"] = Iso(inizioRoma),
            ["chiudibile_dal"] = chiudibileRoma == null ? null : Iso(chiudibileRoma),
            ["minuti_minimi"] = minuti,
        };

    private static JsonObject StudioPatto(JsonObject? config = null, JsonObject? inCorso = null, params JsonObject[] partenze) =>
        new()
        {
            ["config"] = config,
            ["in_corso"] = inCorso,
            ["prossime_partenze"] = new JsonArray(partenze.Cast<JsonNode>().ToArray()),
        };

    private static JsonObject ConfigJson(string[] programmi, (string, string)[]? firme = null, (string, string)[]? nomi = null)
    {
        var p = new JsonArray(programmi.Select(k => (JsonNode)k!).ToArray());
        var computer = new JsonObject { ["programmi"] = p };
        if (firme != null)
        {
            var f = new JsonObject();
            foreach (var (k, v) in firme) f[k] = v;
            computer["firme"] = f;
        }
        if (nomi != null)
        {
            var n = new JsonObject();
            foreach (var (k, v) in nomi) n[k] = v;
            computer["nomi"] = n;
        }
        return new JsonObject { ["approvata"] = new JsonObject { ["computer"] = computer } };
    }

    /// <summary>Un file nella cartella di Windows di questo PC (dove il figlio non scrive).</summary>
    private static string DiWindows(string exe) => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), exe);

    // ---------- Lettura ----------

    [Fact]
    public void Legge_config_partenze_e_in_corso()
    {
        var studio = Studio.DaPatto(
            StudioPatto(
                ConfigJson(new[] { "exe:winword.exe", "sito:classeviva.it" }, firme: new[] { ("exe:winword.exe", "Microsoft Corporation") }),
                partenze: PartenzaJson("2026-10-08", "2026-10-08T15:00:00", "2026-10-08T16:00:00", 60)),
            Fuso.Ms("2026-10-08T10:00:00"));

        Assert.NotNull(studio.Config);
        Assert.Equal(new[] { "exe:winword.exe" }, studio.Config!.Esercizi());
        Assert.Equal(new[] { "classeviva.it" }, studio.Config.Siti());
        Assert.Equal("Microsoft Corporation", studio.Config.Firme["exe:winword.exe"]);
        var p = Assert.Single(studio.Partenze);
        Assert.Equal("2026-10-08", p.Giorno);
        Assert.Equal(Fuso.Ms("2026-10-08T15:00:00"), p.InizioMs);
        Assert.Equal(Fuso.Ms("2026-10-08T16:00:00"), p.ChiudibileDalMs);
        Assert.Equal(60, p.MinutiMinimi);
    }

    [Fact]
    public void Un_server_vecchio_senza_studio_da_stato_vuoto()
    {
        var studio = Studio.DaPatto(null, Fuso.Ms("2026-10-08T15:30:00"));
        Assert.Null(studio.Config);
        Assert.Empty(studio.Partenze);
        Assert.Null(studio.InCorsoA(Fuso.Ms("2026-10-08T15:30:00"), Roma));
    }

    [Fact]
    public void Niente_di_approvato_niente_config()
    {
        var studio = Studio.DaPatto(StudioPatto(config: new JsonObject { ["approvata"] = null, ["stato"] = "in_attesa" }), Fuso.Ms("2026-10-08T10:00:00"));
        Assert.Null(studio.Config);
    }

    // ---------- Sono in Studio adesso? ----------

    [Fact]
    public void Il_server_dice_in_corso_si_e_in_studio()
    {
        var inCorso = new JsonObject { ["id"] = 41, ["inizio_ts"] = Iso("2026-10-08T15:00:00") };
        var studio = Studio.DaPatto(StudioPatto(inCorso: inCorso), Fuso.Ms("2026-10-08T15:05:00"));
        var s = studio.InCorsoA(Fuso.Ms("2026-10-08T15:30:00"), Roma);
        Assert.NotNull(s);
        Assert.Equal(41, s!.Id);
        Assert.Equal(Fuso.Ms("2026-10-08T15:00:00"), s.InizioMs);
    }

    [Fact]
    public void A_mezzanotte_lo_studio_si_chiude_da_solo()
    {
        var inCorso = new JsonObject { ["id"] = 41, ["inizio_ts"] = Iso("2026-10-08T15:00:00") };
        var studio = Studio.DaPatto(StudioPatto(inCorso: inCorso), Fuso.Ms("2026-10-08T15:05:00"));
        // Un attimo prima di mezzanotte: ancora in Studio. Dopo: chiuso.
        Assert.NotNull(studio.InCorsoA(Fuso.Ms("2026-10-08T23:59:59"), Roma));
        Assert.Null(studio.InCorsoA(Fuso.Ms("2026-10-09T00:00:00"), Roma));
        Assert.Null(studio.InCorsoA(Fuso.Ms("2026-10-09T08:00:00"), Roma));
    }

    [Fact]
    public void Senza_rete_una_partenza_passata_dopo_l_ultima_risposta_fa_partire_lo_studio()
    {
        // Ultima risposta alle 14:00: la partenza delle 15:00 il server non la conosceva ancora.
        var studio = Studio.DaPatto(
            StudioPatto(partenze: PartenzaJson("2026-10-08", "2026-10-08T15:00:00", "2026-10-08T16:00:00", 60)),
            Fuso.Ms("2026-10-08T14:00:00"));
        Assert.Null(studio.InCorsoA(Fuso.Ms("2026-10-08T14:59:00"), Roma));
        var s = studio.InCorsoA(Fuso.Ms("2026-10-08T15:30:00"), Roma);
        Assert.NotNull(s);
        Assert.Null(s!.Id); // partito in locale, senza id del server
        Assert.Equal(Fuso.Ms("2026-10-08T15:00:00"), s.InizioMs);
        Assert.Equal(Fuso.Ms("2026-10-08T16:00:00"), s.Condizioni.ChiudibileDalMs);
    }

    [Fact]
    public void Una_partenza_che_il_server_conosceva_gia_e_ha_dato_chiusa_non_riparte()
    {
        // Ultima risposta alle 18:00 (dopo la partenza delle 15:00) e NON in_corso: lo Studio è finito, non riparte.
        var studio = Studio.DaPatto(
            StudioPatto(partenze: PartenzaJson("2026-10-08", "2026-10-08T15:00:00", "2026-10-08T16:00:00", 60)),
            Fuso.Ms("2026-10-08T18:00:00"));
        Assert.Null(studio.InCorsoA(Fuso.Ms("2026-10-08T18:05:00"), Roma));
    }

    [Fact]
    public void La_prossima_partenza_e_la_piu_vicina_nel_futuro()
    {
        var studio = Studio.DaPatto(
            StudioPatto(null, null,
                PartenzaJson("2026-10-09", "2026-10-09T15:00:00", "2026-10-09T16:00:00", 60),
                PartenzaJson("2026-10-08", "2026-10-08T15:00:00", "2026-10-08T16:00:00", 60)),
            Fuso.Ms("2026-10-08T10:00:00"));
        var p = studio.ProssimaPartenza(Fuso.Ms("2026-10-08T10:00:00"));
        Assert.NotNull(p);
        Assert.Equal("2026-10-08", p!.Giorno);
        // Dopo la partenza di oggi, la prossima è domani.
        Assert.Equal("2026-10-09", studio.ProssimaPartenza(Fuso.Ms("2026-10-08T16:00:00"))!.Giorno);
    }

    [Fact]
    public void Lo_studio_del_blocco_dice_in_corso_e_quando_finisce()
    {
        var s = Studio.LeggiBlocco(new JsonObject { ["in_corso"] = true, ["id"] = 41, ["inizio_ts"] = Iso("2026-10-08T15:00:00") });
        Assert.NotNull(s);
        Assert.Equal(41, s!.Id);
        Assert.Null(Studio.LeggiBlocco(new JsonObject { ["in_corso"] = false, ["id"] = null, ["inizio_ts"] = null }));
        Assert.Null(Studio.LeggiBlocco(null));
    }

    [Fact]
    public void Lo_stato_si_salva_e_si_rilegge_uguale()
    {
        using var c = new CartellaTemporanea();
        var studio = Studio.DaPatto(
            StudioPatto(
                ConfigJson(new[] { "exe:winword.exe", "sito:classeviva.it" }, firme: new[] { ("exe:winword.exe", "Microsoft Corporation") }),
                partenze: PartenzaJson("2026-10-08", "2026-10-08T15:00:00", "2026-10-08T16:00:00", 60)),
            Fuso.Ms("2026-10-08T14:00:00"));
        Archivio.ScriviJson(c.File("studio.json"), studio);
        var riletto = Archivio.LeggiJson<StatoStudio>(c.File("studio.json"))!;
        Assert.Equal(new[] { "exe:winword.exe" }, riletto.Config!.Esercizi());
        Assert.Equal("Microsoft Corporation", riletto.Config.Firme["exe:winword.exe"]);
        var s = riletto.InCorsoA(Fuso.Ms("2026-10-08T15:30:00"), Roma);
        Assert.NotNull(s);
    }

    // ---------- La copertura dello schermo ----------

    private static ConfigStudio Config(string[] programmi, (string, string)[]? firme = null)
    {
        var c = new ConfigStudio { Programmi = programmi.ToList() };
        if (firme != null) foreach (var (k, v) in firme) c.Firme[k] = v;
        return c;
    }

    [Fact]
    public void Un_programma_nella_lista_non_copre_uno_fuori_lista_copre()
    {
        var config = Config(new[] { "exe:winword.exe" });
        Assert.False(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:winword.exe", "winword.exe", null), config));
        Assert.True(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:gioco.exe", "gioco.exe", null), config));
    }

    [Fact]
    public void Un_programma_con_firma_vuole_la_firma_giusta()
    {
        var config = Config(new[] { "exe:winword.exe" }, firme: new[] { ("exe:winword.exe", "Microsoft Corporation") });
        // Il file giusto: firmato Microsoft.
        Assert.False(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:winword.exe", "winword.exe", "Microsoft Corporation"), config));
        // Un gioco rinominato winword.exe senza quella firma: copre.
        Assert.True(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:winword.exe", "winword.exe", "Pinco Pallo"), config));
        Assert.True(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:winword.exe", "winword.exe", null), config));
    }

    [Fact]
    public void Un_programma_senza_firma_si_riconosce_dal_nome()
    {
        var config = Config(new[] { "exe:gioco-non-firmato.exe" });
        Assert.False(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:gioco-non-firmato.exe", "gioco-non-firmato.exe", null), config));
    }

    [Fact]
    public void Sempre_usabili_non_coprono_ma_gestione_attivita_copre()
    {
        var config = Config(Array.Empty<string>());
        foreach (var exe in new[] { "explorer.exe", "systemsettings.exe", "lockapp.exe" })
            Assert.False(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:" + exe, exe, null, Percorso: DiWindows(exe)), config));
        // Gestione attività è coperta.
        Assert.True(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:taskmgr.exe", "taskmgr.exe", null), config));
    }

    [Fact]
    public void Il_browser_vale_solo_sui_siti_della_lista()
    {
        var config = Config(new[] { "sito:classeviva.it" });
        // Sito nella lista: non copre.
        Assert.False(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:chrome.exe", "chrome.exe", null, Browser: true, LetturaRiuscita: true, Dominio: "classeviva.it"), config));
        // Sito fuori lista: copre.
        Assert.True(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:chrome.exe", "chrome.exe", null, Browser: true, LetturaRiuscita: true, Dominio: "youtube.com"), config));
    }

    [Fact]
    public void Una_scheda_nuova_del_browser_e_usabile_finche_non_si_naviga()
    {
        var config = Config(new[] { "sito:classeviva.it" });
        // Pagina nuova/interna: letta, nessun sito → usabile.
        Assert.False(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:chrome.exe", "chrome.exe", null, Browser: true, LetturaRiuscita: true, Dominio: null), config));
    }

    [Fact]
    public void Una_barra_che_non_si_legge_copre()
    {
        var config = Config(new[] { "sito:classeviva.it" });
        // Schermo intero/F11, cursore nella barra, lettura fallita: copre, anche se prima c'era un sito in lista.
        Assert.True(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:chrome.exe", "chrome.exe", null, Browser: true, LetturaRiuscita: false, Dominio: null), config));
    }

    [Fact]
    public void Un_browser_che_non_si_sa_leggere_e_sempre_coperto()
    {
        // Opera, Vivaldi, Tor... non sono browser leggibili (Browser: false) e il loro exe non è in lista: copre.
        var config = Config(new[] { "sito:classeviva.it", "exe:winword.exe" });
        Assert.True(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:opera.exe", "opera.exe", null), config));
    }

    [Fact]
    public void Uno_schermo_si_copre_se_una_sola_finestra_e_fuori_lista()
    {
        var config = Config(new[] { "exe:winword.exe" });
        // Schermo con solo Word e Esplora file: libero.
        Assert.False(CoperturaStudio.Copre(new[]
        {
            new FinestraStudio("exe:winword.exe", "winword.exe", null),
            new FinestraStudio("exe:explorer.exe", "explorer.exe", null, Percorso: DiWindows("explorer.exe")),
        }, config));
        // Aggiungi un gioco visibile: tutto lo schermo si copre.
        Assert.True(CoperturaStudio.Copre(new[]
        {
            new FinestraStudio("exe:winword.exe", "winword.exe", null),
            new FinestraStudio("exe:gioco.exe", "gioco.exe", null),
        }, config));
    }

    [Fact]
    public void Uno_schermo_senza_finestre_resta_libero()
    {
        Assert.False(CoperturaStudio.Copre(Array.Empty<FinestraStudio>(), Config(Array.Empty<string>())));
    }

    // ---------- (correzione 0.18) l'elenco dei browser è quello del server ----------

    /// <summary>L'elenco BROWSER del server (server/app/studio.py): copiato qui, perché i test del pc non leggono il server.</summary>
    private static readonly string[] BrowserDelServer =
    {
        "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "opera_gx.exe",
        "vivaldi.exe", "arc.exe", "chromium.exe", "iexplore.exe", "waterfox.exe", "librewolf.exe",
        "tor.exe", "yandex.exe", "browser.exe", "seamonkey.exe", "palemoon.exe", "floorp.exe",
        "thorium.exe", "zen.exe", "maxthon.exe", "duckduckgo.exe",
    };

    [Fact]
    public void L_elenco_dei_browser_e_quello_del_server()
    {
        Assert.Equal(BrowserDelServer.OrderBy(b => b), CoperturaStudio.Browser.OrderBy(b => b));
        foreach (var b in BrowserDelServer) Assert.True(CoperturaStudio.ÈBrowser(b), b);
        Assert.False(CoperturaStudio.ÈBrowser("winword.exe"));
    }

    [Theory]
    [InlineData("yandex.exe")]
    [InlineData("browser.exe")]
    [InlineData("floorp.exe")]
    [InlineData("zen.exe")]
    [InlineData("duckduckgo.exe")]
    public void Un_browser_dell_elenco_largo_e_coperto_anche_se_in_lista(string exe)
    {
        // Pactum non ne sa leggere la barra: coperto anche se qualcuno l'avesse messo in lista come exe:.
        var config = Config(new[] { "sito:classeviva.it", "exe:" + exe });
        Assert.True(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:" + exe, exe, null), config));
        // E rinominato (nome originale del file = quel browser) resta coperto.
        var rinominato = Config(new[] { "exe:winword.exe" });
        Assert.True(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:winword.exe", "winword.exe", null, NomeOriginale: exe), rinominato));
    }

    [Fact]
    public void La_finestra_non_propone_i_browser_del_server_e_dice_browser_nella_lista()
    {
        // L'interfaccia viaggia dentro l'exe (Pactum.csproj): è lo stesso app.js della cartella ui.
        using var flusso = typeof(CoperturaStudio).Assembly.GetManifestResourceStream("ui/app.js");
        Assert.NotNull(flusso);
        using var lettore = new StreamReader(flusso!);
        var app = lettore.ReadToEnd();
        int da = app.IndexOf("const BROWSER_STUDIO = new Set([", StringComparison.Ordinal);
        Assert.True(da >= 0);
        int a = app.IndexOf("]);", da, StringComparison.Ordinal);
        var elenco = app[da..a];
        var nellaFinestra = System.Text.RegularExpressions.Regex.Matches(elenco, "'exe:([a-z0-9_]+\\.exe)'")
            .Select(m => m.Groups[1].Value).OrderBy(b => b).ToList();
        Assert.Equal(BrowserDelServer.OrderBy(b => b), nellaFinestra);
        // Il 422 browser_nella_lista ha la sua frase; quella sui siti scritti resta per gli altri 422.
        Assert.Contains("e.errore === 'browser_nella_lista'", app);
        Assert.Contains("'Nella lista c\\'è un browser: toglilo e scrivi i siti che ti servono.'", app);
        Assert.Contains("'Il server non ha accettato la lista: controlla i siti scritti.'", app);
    }
}
