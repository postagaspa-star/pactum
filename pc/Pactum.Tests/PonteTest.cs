using System.Text.Json.Nodes;
using Pactum.Interfaccia;
using Pactum.Nucleo;
using MotorePactum = Pactum.Motore.Motore;

namespace Pactum.Tests;

/// <summary>Il ponte tra la WebView e il motore: dove si può navigare, cosa passa verso il server.</summary>
public class PonteTest
{
    [Theory]
    [InlineData("https://pactum.locale/index.html", true)]
    [InlineData("https://pactum.locale/", true)]
    [InlineData("https://PACTUM.LOCALE/app.js", true)]
    [InlineData("about:blank", true)]
    [InlineData("http://pactum.locale/", false)]
    [InlineData("https://pactum.locale:8443/", false)]
    [InlineData("https://pactum.locale.esempio.it/", false)]
    [InlineData("https://example.com/", false)]
    [InlineData("file:///C:/Windows/win.ini", false)]
    [InlineData(null, false)]
    public void La_finestra_resta_su_pactum_locale(string? indirizzo, bool interno)
    {
        Assert.Equal(interno, Ponte.ÈInterno(indirizzo));
    }

    [Theory]
    [InlineData("https://example.com/pagina", true)]
    [InlineData("http://example.com", true)]
    [InlineData("mailto:padre@example.com", true)]
    [InlineData("file:///C:/Windows/System32/calc.exe", false)]
    [InlineData("javascript:alert(1)", false)]
    [InlineData("ms-settings:privacy", false)]
    public void Fuori_si_aprono_solo_pagine_web_e_posta(string indirizzo, bool apribile)
    {
        Assert.Equal(apribile, Ponte.ApribileFuori(indirizzo));
    }

    [Theory]
    [InlineData("api/patto", true)]
    [InlineData("api/regole/12", true)]
    [InlineData("api/proposte/4/risposta", true)]
    [InlineData("altro", false)]
    [InlineData("api/../../segreto", false)]
    [InlineData("api//regole", false)]
    [InlineData("api/%2e%2e/segreto", false)]
    [InlineData("api\\regole", false)]
    public void Verso_il_server_passano_solo_i_percorsi_dell_api(string percorso, bool permesso)
    {
        Assert.Equal(permesso, MotorePactum.PercorsoPermesso(percorso));
    }

    [Theory]
    [InlineData("pactum.taildbae63.ts.net", "https://pactum.taildbae63.ts.net")]
    [InlineData("https://pactum.taildbae63.ts.net/", "https://pactum.taildbae63.ts.net")]
    [InlineData("  https://Pactum.Esempio.it  ", "https://pactum.esempio.it")]
    [InlineData("http://127.0.0.1:8765", "http://127.0.0.1:8765")]
    [InlineData("https://nas.esempio.it/pactum/", "https://nas.esempio.it/pactum")]
    public void L_indirizzo_del_server_si_scrive_come_viene(string scritto, string atteso)
    {
        Assert.Equal(atteso, MotorePactum.NormalizzaServer(scritto));
    }

    [Theory]
    [InlineData("ftp://server.it")]
    [InlineData("")]
    [InlineData("https://utente:pw@server.it")]
    [InlineData("https://server.it/#x")]
    public void Un_indirizzo_sbagliato_non_passa(string scritto)
    {
        Assert.Null(MotorePactum.NormalizzaServer(scritto));
    }

    [Fact]
    public void L_errore_del_server_si_legge_con_o_senza_detail()
    {
        var conDetail = MotorePactum.DettaglioErrore("{\"detail\": {\"errore\": \"tetto_superato\", \"residuo_giorno\": 0}}");
        Assert.Equal("tetto_superato", Json.Testo(conDetail["errore"]));
        Assert.Equal(0, Json.Intero(conDetail["residuo_giorno"]));
        Assert.Equal("regola_non_valida", Json.Testo(MotorePactum.DettaglioErrore("{\"errore\": \"regola_non_valida\"}")["errore"]));
        Assert.Empty(MotorePactum.DettaglioErrore("<html>502</html>"));
    }

    [Theory]
    [InlineData("/", "index.html")]
    [InlineData("/index.html", "index.html")]
    [InlineData("/app.js", "app.js")]
    [InlineData("/immagini/logo%20nuovo.png", "immagini\\logo nuovo.png")]
    public void I_file_dell_interfaccia_vengono_dalla_cartella_ui(string percorso, string relativo)
    {
        using var cartella = new CartellaTemporanea();
        Assert.Equal(Path.Combine(cartella.Percorso, relativo), Ponte.FileDellInterfaccia(cartella.Percorso, percorso));
    }

    [Fact]
    public void Le_barre_iniziali_in_piu_restano_dentro_la_cartella()
    {
        using var cartella = new CartellaTemporanea();
        Assert.Equal(Path.Combine(cartella.Percorso, "server", "condivisa"), Ponte.FileDellInterfaccia(cartella.Percorso, "//server/condivisa"));
    }

    [Theory]
    [InlineData("/../segreto.txt")]
    [InlineData("/%2e%2e/segreto.txt")]
    [InlineData("/cartella/../../segreto.txt")]
    [InlineData("/..%5csegreto.txt")]
    [InlineData("/C:/Windows/win.ini")]
    [InlineData("/cartella//doppia.js")]
    [InlineData("/a/./b.js")]
    public void Nessun_percorso_esce_dalla_cartella_ui(string percorso)
    {
        using var cartella = new CartellaTemporanea();
        Assert.Null(Ponte.FileDellInterfaccia(cartella.Percorso, percorso));
    }

    [Fact]
    public void Le_chiamate_dell_accordo_si_riconoscono_dal_percorso()
    {
        Assert.True(Ponte.ÈApi(new Uri("https://pactum.locale/locale/stato")));
        Assert.True(Ponte.ÈApi(new Uri("https://pactum.locale/server/api/patto?x=1")));
        Assert.False(Ponte.ÈApi(new Uri("https://pactum.locale/app.js")));
        Assert.False(Ponte.ÈApi(new Uri("https://pactum.locale/localeX/stato")));
        Assert.Equal("text/javascript; charset=utf-8", Ponte.TipoContenuto("app.js"));
        Assert.Equal("text/html; charset=utf-8", Ponte.TipoContenuto("INDEX.HTML"));
        Assert.Equal("text/css; charset=utf-8", Ponte.TipoContenuto("stile.css"));
        Assert.Equal("application/octet-stream", Ponte.TipoContenuto("misterioso.bin"));
    }

    [Fact]
    public void Troppi_tentativi_porta_all_interfaccia_anche_quando_riprovare()
    {
        var e = MotorePactum.ErroreAbbinamento(new Pactum.Motore.Risposta(429, "{\"detail\": {\"errore\": \"troppi_tentativi\", \"riprova_tra_secondi\": 412}}"));
        Assert.NotNull(e);
        Assert.Equal(new[] { "errore", "ok", "riprova_tra_secondi" }, e!.Select(p => p.Key).OrderBy(k => k, StringComparer.Ordinal));
        Assert.Equal("troppi_tentativi", Json.Testo(e["errore"]));
        Assert.Equal(412, Json.Intero(e["riprova_tra_secondi"]));
        // Senza il campo nel corpo non si inventa niente.
        Assert.Null(MotorePactum.ErroreAbbinamento(new Pactum.Motore.Risposta(429, "{}"))!["riprova_tra_secondi"]);
    }

    [Theory]
    [InlineData(409, "codice_non_valido")]
    [InlineData(422, "codice_non_valido")]
    [InlineData(0, "rete")]
    [InlineData(500, "rete")]
    [InlineData(404, "rete")]
    public void Gli_altri_esiti_dell_abbinamento(int stato, string errore)
    {
        var e = MotorePactum.ErroreAbbinamento(new Pactum.Motore.Risposta(stato, "{\"detail\": {\"errore\": \"codice_non_valido\"}}"));
        Assert.Equal(errore, Json.Testo(e!["errore"]));
        Assert.False(Json.Booleano(e["ok"]));
        Assert.Null(MotorePactum.ErroreAbbinamento(new Pactum.Motore.Risposta(200, "{}")));
    }

    [Fact]
    public void Un_codice_fatto_per_un_altro_tipo_di_dispositivo_ha_il_suo_errore()
    {
        // 409 con "tipo_non_corrispondente" (contratto v3.1): il codice era di un telefono.
        var e = MotorePactum.ErroreAbbinamento(new Pactum.Motore.Risposta(409, "{\"detail\": {\"errore\": \"tipo_non_corrispondente\", \"tipo_atteso\": \"telefono\"}}"));
        Assert.Equal("tipo_non_corrispondente", Json.Testo(e!["errore"]));
        Assert.False(Json.Booleano(e["ok"]));
        // Un 409 con qualunque altro codice resta "codice non valido".
        Assert.Equal("codice_non_valido", Json.Testo(MotorePactum.ErroreAbbinamento(new Pactum.Motore.Risposta(409, "{\"detail\": {\"errore\": \"codice_non_valido\"}}"))!["errore"]));
    }

    [Fact]
    public void L_impronta_dell_interfaccia_cambia_quando_cambia_un_file()
    {
        using var cartella = new CartellaTemporanea();
        File.WriteAllText(cartella.File("index.html"), "<p>uno</p>");
        var prima = FinestraPactum.ImprontaUi(cartella.Percorso);
        Assert.Equal(prima, FinestraPactum.ImprontaUi(cartella.Percorso));
        File.WriteAllText(cartella.File("app.js"), "// nuovo");
        var dopo = FinestraPactum.ImprontaUi(cartella.Percorso);
        Assert.NotEqual(prima, dopo);
        File.WriteAllText(cartella.File("index.html"), "<p>due, più lungo</p>");
        Assert.NotEqual(dopo, FinestraPactum.ImprontaUi(cartella.Percorso));
    }

    [Fact]
    public async Task Le_chiamate_locali_sconosciute_o_col_metodo_sbagliato_hanno_la_loro_risposta()
    {
        using var cartella = new CartellaTemporanea();
        using var motore = new MotorePactum(new Pactum.Motore.Percorsi(cartella.Percorso));
        var ponte = new Ponte(motore);
        var (s1, j1) = await ponte.GestisciAsync("GET", new Uri("https://pactum.locale/locale/inesistente"), null);
        Assert.Equal(404, s1);
        Assert.Equal("sconosciuto", Json.Testo(JsonNode.Parse(j1)!["errore"]));
        var (s2, j2) = await ponte.GestisciAsync("POST", new Uri("https://pactum.locale/locale/stato"), "{}");
        Assert.Equal(405, s2);
        Assert.Equal("metodo_non_permesso", Json.Testo(JsonNode.Parse(j2)!["errore"]));
        var (s3, j3) = await ponte.GestisciAsync("GET", new Uri("https://pactum.locale/locale/stato"), null);
        Assert.Equal(200, s3);
        Assert.False(Json.Booleano(JsonNode.Parse(j3)!["abbinato"]));
        var (s4, j4) = await ponte.GestisciAsync("POST", new Uri("https://pactum.locale/locale/abbina"), "{\"server\": \"https://x.it\", \"codice\": \"12\"}");
        Assert.Equal(200, s4);
        Assert.Equal("codice_non_valido", Json.Testo(JsonNode.Parse(j4)!["errore"]));
        var (s5, _) = await ponte.GestisciAsync("GET", new Uri("https://pactum.locale/altro"), null);
        Assert.Equal(404, s5);
    }
}
