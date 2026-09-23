using System.Text.Json;
using System.Text.Json.Nodes;
using Pactum.Motore;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>Le forme JSON degli eventi e delle risposte all'interfaccia, confrontate col contratto v3 e con docs/pc-programma.md.</summary>
public class FormeJsonTest
{
    private static IEnumerable<string> Chiavi(JsonNode? n) => ((JsonObject)n!).Select(p => p.Key).OrderBy(k => k, StringComparer.Ordinal);

    private static void Intero(JsonNode? n)
    {
        var v = Assert.IsAssignableFrom<JsonValue>(n);
        Assert.Equal(JsonValueKind.Number, v.GetValueKind());
        Assert.NotNull(Json.Intero(v));
        Assert.DoesNotContain(".", v.ToJsonString());
    }

    private static Giornata GiornataDiProva()
    {
        var g = Giornata.Nuova("2026-09-23");
        g.MsAttivi = 131 * 60_000 + 30_000;
        g.Programmi["exe:chrome.exe"] = new VoceProgramma { Nome = "Google Chrome", Ms = 80 * 60_000 + 59_000 };
        g.Programmi["exe:minecraft.exe"] = new VoceProgramma { Nome = "Minecraft", Ms = 50 * 60_000 };
        g.Programmi["exe:calc.exe"] = new VoceProgramma { Nome = "Calcolatrice", Ms = 40_000 };
        g.Siti["youtube.com"] = new VoceSito { Ms = 42 * 60_000, Visite = 7 };
        g.Siti["example.com"] = new VoceSito { Ms = 20_000, Visite = 1 };
        g.MsPerCategoria["categoria:video"] = 42 * 60_000;
        g.MsPerCategoria["categoria:giochi"] = 50 * 60_000;
        g.MsPerCategoria["categoria:altro"] = 39 * 60_000;
        return g;
    }

    [Fact]
    public void Uso_giornaliero_del_computer()
    {
        var d = Fotografie.Uso(GiornataDiProva(), 1).Dettagli;
        Assert.Equal(new[] { "giorno", "nomi", "totale_minuti", "uso_categorie", "uso_minuti" }, Chiavi(d));
        Assert.Equal("2026-09-23", Json.Testo(d["giorno"]));
        Assert.Equal(131, Json.Intero(d["totale_minuti"]));
        Intero(d["totale_minuti"]);
        var uso = (JsonObject)d["uso_minuti"]!;
        Assert.Equal(new[] { "exe:chrome.exe", "exe:minecraft.exe" }, uso.Select(p => p.Key));
        Assert.Equal(80, Json.Intero(uso["exe:chrome.exe"]));
        foreach (var (_, v) in uso) Intero(v);
        Assert.Equal("Minecraft", Json.Testo(d["nomi"]!["exe:minecraft.exe"]));
        Assert.Equal(uso.Select(p => p.Key), ((JsonObject)d["nomi"]!).Select(p => p.Key));
        Assert.Equal(42, Json.Intero(d["uso_categorie"]!["categoria:video"]));
    }

    [Fact]
    public void Siti_giornalieri_del_computer_con_i_minuti()
    {
        var d = Fotografie.Siti(GiornataDiProva(), 1).Dettagli;
        Assert.Equal(new[] { "dns_cifrato", "domini", "giorno", "minuti", "totale_domini" }, Chiavi(d));
        Assert.Equal(7, Json.Intero(d["domini"]!["youtube.com"]));
        Assert.Equal(42, Json.Intero(d["minuti"]!["youtube.com"]));
        Assert.Equal(0, Json.Intero(d["minuti"]!["example.com"]));
        Assert.Equal(2, Json.Intero(d["totale_domini"]));
        Assert.False(Json.Booleano(d["dns_cifrato"]));
    }

    [Fact]
    public void Oltre_200_domini_la_lista_si_taglia_ma_il_totale_resta_vero()
    {
        var g = Giornata.Nuova("2026-09-23");
        for (int i = 0; i < 250; i++) g.Siti[$"sito{i:000}.it"] = new VoceSito { Ms = i * 60_000, Visite = 1 };
        g.SitiNonLeggibili = true;
        var d = Fotografie.DettagliSiti(g);
        Assert.Equal(200, ((JsonObject)d["domini"]!).Count);
        Assert.Equal(200, ((JsonObject)d["minuti"]!).Count);
        Assert.Equal(250, Json.Intero(d["totale_domini"]));
        Assert.True(Json.Booleano(d["dns_cifrato"]));
        // Restano i più usati.
        Assert.NotNull(d["minuti"]!["sito249.it"]);
        Assert.Null(d["minuti"]!["sito000.it"]);
    }

    [Fact]
    public void Gli_eventi_del_registro_hanno_id_uuid_e_ts_intero()
    {
        var lotto = Eventi.Lotto(new[]
        {
            Eventi.Sospensione("spegnimento", 1_790_000_000_123),
            Eventi.Ripresa("riattivazione", 1_789_990_000_000, 1_790_000_000_456),
            Eventi.SitiNonLeggibili("exe:firefox.exe", "2026-09-23", 1_790_000_000_789),
        });
        var eventi = Assert.IsType<JsonArray>(lotto["eventi"]);
        Assert.Equal(3, eventi.Count);
        foreach (var e in eventi)
        {
            Assert.Equal(new[] { "dettagli", "id", "tipo", "ts_device" }, Chiavi(e));
            Assert.True(Guid.TryParse(Json.Testo(e!["id"]), out _));
            Intero(e["ts_device"]);
        }
        Assert.Equal("sospensione", Json.Testo(eventi[0]!["tipo"]));
        Assert.Equal("spegnimento", Json.Testo(eventi[0]!["dettagli"]!["motivo"]));
        Assert.Equal("ripresa", Json.Testo(eventi[1]!["tipo"]));
        Assert.Equal(new[] { "avvio_sistema_ts", "motivo" }, Chiavi(eventi[1]!["dettagli"]));
        Assert.Equal("manomissione", Json.Testo(eventi[2]!["tipo"]));
        Assert.Equal("siti_non_leggibili", Json.Testo(eventi[2]!["dettagli"]!["sotto_tipo"]));
    }

    [Fact]
    public void Lo_sforamento_ha_giorno_limite_efficace_e_minuti_oltre()
    {
        var limite = Eventi.Sforamento(new Sforamento(12, TipiRegola.LimiteTempo, 75, 9), "2026-09-23", 5);
        Assert.Equal("sforamento", limite.Tipo);
        Assert.Equal(new[] { "giorno", "limite_efficace", "minuti_oltre", "regola_id" }, Chiavi(limite.Dettagli));
        var fascia = Eventi.Sforamento(new Sforamento(13, TipiRegola.FasciaOraria, null, 20, "2026-09-22"), "2026-09-23", 5);
        Assert.Equal(new[] { "giorno", "minuti_oltre", "regola_id" }, Chiavi(fascia.Dettagli));
        Assert.Equal("2026-09-22", Json.Testo(fascia.Dettagli["giorno"]));
    }

    [Fact]
    public void Il_battito_del_computer()
    {
        var b = Eventi.Battito(1_790_000_000_000, 123_456, null);
        Assert.Equal(new[] { "elapsed_realtime", "ts_device", "versione_app" }, Chiavi(b));
        Assert.Equal("0.8.0", Json.Testo(b["versione_app"]));
        Assert.Equal(87, Json.Intero(Eventi.Battito(1, 1, 87)["batteria"]));
    }

    [Fact]
    public void Un_evento_salvato_nella_coda_torna_uguale()
    {
        var e = Eventi.Sforamento(new Sforamento(12, TipiRegola.LimiteTempo, 75, 9), "2026-09-23", 1_790_000_000_000);
        var testo = JsonSerializer.Serialize(new List<Evento> { e });
        var riletto = Assert.Single(JsonSerializer.Deserialize<List<Evento>>(testo)!);
        Assert.Equal(e.Id, riletto.Id);
        Assert.Equal(e.TsDevice, riletto.TsDevice);
        Assert.Equal(e.Dettagli.ToJsonString(), riletto.Dettagli.ToJsonString());
    }

    // ---------- le risposte all'interfaccia (docs/pc-programma.md) ----------

    [Fact]
    public void Locale_oggi_ha_la_forma_dell_accordo()
    {
        var regole = new[]
        {
            new Regola(12, TipiRegola.LimiteTempo, new JsonObject { ["app_o_categoria"] = "sito:youtube.com", ["minuti_al_giorno"] = 30 }, true, 2),
            new Regola(13, TipiRegola.FasciaOraria, new JsonObject { ["dalle"] = "22:00", ["alle"] = "07:00", ["giorni"] = new JsonArray("lun", "mar", "mer", "gio", "ven", "sab", "dom") }, true, 2),
        };
        var bonus = new Dictionary<string, int> { ["12"] = 15 };
        var o = Risposte.Oggi(GiornataDiProva(), regole, bonus, Fuso.Ms("2026-09-23T15:00:00"), Fuso.Roma);

        Assert.Equal(new[] { "fasce", "giorno", "programmi", "regole", "siti", "siti_non_leggibili", "totale_minuti" }, Chiavi(o));
        Assert.Equal(131, Json.Intero(o["totale_minuti"]));
        var programmi = (JsonArray)o["programmi"]!;
        Assert.Equal(2, programmi.Count); // la calcolatrice ha meno di un minuto
        Assert.Equal(new[] { "categoria", "chiave", "minuti", "nome" }, Chiavi(programmi[0]));
        Assert.Equal("exe:chrome.exe", Json.Testo(programmi[0]!["chiave"]));
        Assert.Equal("Google Chrome", Json.Testo(programmi[0]!["nome"]));
        Assert.Equal("altro", Json.Testo(programmi[0]!["categoria"]));
        Assert.Equal("giochi", Json.Testo(programmi[1]!["categoria"]));
        var siti = (JsonArray)o["siti"]!;
        Assert.Equal(new[] { "dominio", "minuti", "visite" }, Chiavi(siti[0]));
        Assert.Equal("youtube.com", Json.Testo(siti[0]!["dominio"]));
        var r12 = o["regole"]!["12"];
        Assert.Equal(new[] { "limite_efficace", "minuti", "oltre" }, Chiavi(r12));
        Assert.Equal(42, Json.Intero(r12!["minuti"]));
        Assert.Equal(45, Json.Intero(r12["limite_efficace"]));
        Assert.Equal(0, Json.Intero(r12["oltre"]));
        var f13 = o["fasce"]!["13"];
        Assert.Equal(new[] { "attiva_ora", "fine", "prossimo_inizio" }, Chiavi(f13));
        Assert.False(Json.Booleano(f13!["attiva_ora"]));
        Assert.Equal("22:00", Json.Testo(f13["prossimo_inizio"]));
        Assert.Equal("07:00", Json.Testo(f13["fine"]));
        Assert.False(Json.Booleano(o["siti_non_leggibili"]));
    }

    [Fact]
    public void Locale_visti_mette_prima_i_piu_usati_degli_ultimi_giorni()
    {
        var ieri = Giornata.Nuova("2026-09-22");
        ieri.Programmi["exe:minecraft.exe"] = new VoceProgramma { Nome = "Minecraft", Ms = 500 * 60_000 };
        ieri.Siti["roblox.com"] = new VoceSito { Ms = 300 * 60_000, Visite = 2 };
        var o = Risposte.Visti(new[] { GiornataDiProva(), ieri });
        Assert.Equal(new[] { "programmi", "siti" }, Chiavi(o));
        var programmi = (JsonArray)o["programmi"]!;
        Assert.Equal(new[] { "chiave", "nome" }, Chiavi(programmi[0]));
        Assert.Equal("exe:minecraft.exe", Json.Testo(programmi[0]!["chiave"]));
        Assert.Contains(programmi, p => Json.Testo(p!["chiave"]) == "exe:calc.exe");
        Assert.Equal(new[] { "roblox.com", "youtube.com", "example.com" }, ((JsonArray)o["siti"]!).Select(s => Json.Testo(s)));
    }

    [Fact]
    public void Locale_stato_ha_la_forma_dell_accordo_anche_prima_dell_abbinamento()
    {
        using var cartella = new CartellaTemporanea();
        using var motore = new Motore.Motore(new Percorsi(cartella.Percorso));
        var s = motore.Stato();
        Assert.Equal(new[] { "abbinato", "dispositivo", "figlio", "patto_aggiornato", "rete_ok", "server", "ultimo_invio_ok", "versione" }, Chiavi(s));
        Assert.False(Json.Booleano(s["abbinato"]));
        Assert.Null(s["figlio"]);
        Assert.Null(s["dispositivo"]);
        Assert.Equal("0.8.0", Json.Testo(s["versione"]));
    }

    [Theory]
    [InlineData("ftp://server.it", "123456", "indirizzo_non_valido")]
    [InlineData("", "123456", "indirizzo_non_valido")]
    [InlineData("https://server.it/?x=1", "123456", "indirizzo_non_valido")]
    [InlineData("https://server.it", "12345", "codice_non_valido")]
    [InlineData("https://server.it", "12a456", "codice_non_valido")]
    [InlineData("https://server.it", null, "codice_non_valido")]
    public async Task Locale_abbina_rifiuta_prima_di_chiamare_il_server(string server, string? codice, string errore)
    {
        using var cartella = new CartellaTemporanea();
        using var motore = new Motore.Motore(new Percorsi(cartella.Percorso));
        var r = await motore.AbbinaAsync(server, codice);
        Assert.Equal(new[] { "errore", "ok" }, Chiavi(r));
        Assert.False(Json.Booleano(r["ok"]));
        Assert.Equal(errore, Json.Testo(r["errore"]));
    }

    [Fact]
    public async Task Senza_abbinamento_bonus_e_server_lo_dicono()
    {
        using var cartella = new CartellaTemporanea();
        using var motore = new Motore.Motore(new Percorsi(cartella.Percorso));
        var bonus = await motore.BonusAsync(1, 15, null);
        Assert.Equal(new[] { "dettagli", "errore", "ok" }, Chiavi(bonus));
        Assert.Equal("rete", Json.Testo(bonus["errore"]));
        var (stato, json) = await motore.InoltraAsync("GET", "api/patto", "", null);
        Assert.Equal(401, stato);
        Assert.Equal("non_abbinato", Json.Testo(JsonNode.Parse(json)!["errore"]));
    }

    [Fact]
    public async Task Il_ponte_verso_il_server_rifiuta_percorsi_e_metodi_fuori_accordo()
    {
        using var cartella = new CartellaTemporanea();
        using var motore = new Motore.Motore(new Percorsi(cartella.Percorso));
        Assert.Equal(405, (await motore.InoltraAsync("PUT", "api/regole", "", "{}")).Stato);
        Assert.Equal(404, (await motore.InoltraAsync("GET", "altro/patto", "", null)).Stato);
        Assert.Equal(404, (await motore.InoltraAsync("GET", "api/../segreto", "", null)).Stato);
    }
}
