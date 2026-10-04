using System.Globalization;
using System.Text.Json.Nodes;
using Pactum.Motore;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// (0.13, contratto v3.6) Il blocco delle faccende nel motore: adotta lo stato e copre, parte da solo
/// all'ora giusta anche senza rete, e GET /api/faccende/blocco si comporta bene (200, lista vuota, 404
/// di un server vecchio, niente rete). La macchina degli stati è provata a parte in <see cref="BloccoTest"/>.
/// </summary>
public class MotoreBloccoTest
{
    private static Motore.Motore Abbinato(CartellaTemporanea c, string server)
    {
        var percorsi = new Percorsi(c.Percorso);
        Archivio.ScriviJson(percorsi.Config, new Configurazione
        {
            Server = server,
            TokenProtetto = Dpapi.Proteggi("token-di-prova"),
            Dispositivo = new JsonObject { ["id"] = 2, ["nome"] = "Computer", ["tipo"] = "computer" },
            Figlio = new JsonObject { ["id"] = 1, ["nome"] = "Luca" },
        });
        return new Motore.Motore(percorsi);
    }

    private static string Iso(DateTimeOffset t) => t.ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture);

    private static JsonObject BloccoJson(int minuti)
    {
        var t = DateTimeOffset.UtcNow.AddMinutes(minuti);
        return new JsonObject
        {
            ["attivo"] = minuti <= 0,
            ["da_fare"] = new JsonArray(new JsonObject
            {
                ["id"] = 5,
                ["titolo"] = "Svuota la lavastoviglie",
                ["nota"] = "anche le pentole",
                ["blocco_da"] = Iso(t),
                ["creata_da"] = new JsonObject { ["id"] = 2, ["nome"] = "Mamma" },
            }),
        };
    }

    [Fact]
    public void Adotta_un_blocco_attivo_copre_e_lo_dice_una_volta_sola()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        VistaBlocco? ultima = null;
        int conta = 0;
        m.CambioBlocco += v => { ultima = v; conta++; };

        // Lo stesso JSON, costruito una volta: due letture danno lo stesso stato (niente ora che scorre).
        var attivo = BloccoJson(-5);
        m.AdottaBlocco(Blocco.Leggi(attivo));
        Assert.True(m.Coperto);
        Assert.NotNull(ultima);
        Assert.True(ultima!.Coperto);
        var f = Assert.Single(ultima.Faccende);
        Assert.Equal("Svuota la lavastoviglie", f.Titolo);
        Assert.Equal("Mamma", f.DataDa);

        // Lo stesso stato non fa partire un altro cambio.
        m.AdottaBlocco(Blocco.Leggi(attivo));
        Assert.Equal(1, conta);

        // Faccende finite: si scopre.
        m.AdottaBlocco(StatoBlocco.Vuoto);
        Assert.False(m.Coperto);
        Assert.False(ultima!.Coperto);
        Assert.Equal(2, conta);
    }

    [Fact]
    public void Un_blocco_programmato_parte_all_ora_giusta_anche_senza_rete()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        var futura = Blocco.Leggi(BloccoJson(60)); // fra un'ora
        m.AdottaBlocco(futura);
        Assert.False(m.Coperto);

        // All'ora di prossimo, senza una risposta nuova dal server, si copre da solo.
        long inizio = futura.DaFare[0].BloccoDaMs;
        m.ValutaBlocco(inizio - 1000);
        Assert.False(m.Coperto);
        m.ValutaBlocco(inizio + 1000);
        Assert.True(m.Coperto);
    }

    [Fact]
    public void Lo_stato_del_blocco_resta_su_disco_fra_un_avvio_e_l_altro()
    {
        using var c = new CartellaTemporanea();
        using (var m = Abbinato(c, "http://127.0.0.1:9"))
        {
            m.AdottaBlocco(Blocco.Leggi(BloccoJson(-5)));
            Assert.True(m.Coperto);
        }
        // Riaperto senza rete: il blocco salvato resta, e si copre subito.
        using var m2 = Abbinato(c, "http://127.0.0.1:9");
        m2.ValutaBlocco(Tempo.AdessoUtcMs());
        Assert.True(m2.Coperto);
    }

    [Fact]
    public async Task Il_giro_del_blocco_adotta_la_risposta_del_server()
    {
        (int, string) risposta = (200, BloccoJson(-5).ToJsonString());
        await using var server = new ServerFinto(_ => risposta);
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);

        await m.AggiornaBloccoAsync();
        Assert.True(m.Coperto);
        Assert.Contains(server.Ricevute, r => r.Percorso == "/api/faccende/blocco");

        // Niente più faccende da fare: si scopre.
        risposta = (200, new JsonObject { ["attivo"] = false, ["da_fare"] = new JsonArray() }.ToJsonString());
        await m.AggiornaBloccoAsync();
        Assert.False(m.Coperto);
    }

    [Fact]
    public async Task Un_server_vecchio_niente_blocco_e_niente_errori()
    {
        // /api/faccende risponde 404: niente blocco, e non deve esplodere.
        await using var server = new ServerFinto(_ => (404, "{\"detail\": \"Not Found\"}"));
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);

        // Partiamo da un blocco attivo salvato: un server vecchio lo deve togliere (non è più in gioco).
        m.AdottaBlocco(Blocco.Leggi(BloccoJson(-5)));
        Assert.True(m.Coperto);
        await m.AggiornaBloccoAsync();
        Assert.False(m.Coperto);
    }

    [Fact]
    public async Task Senza_rete_il_blocco_resta_com_era()
    {
        using var c = new CartellaTemporanea();
        // Indirizzo che non risponde: la richiesta è "senza rete".
        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.AdottaBlocco(Blocco.Leggi(BloccoJson(-5)));
        Assert.True(m.Coperto);

        await m.AggiornaBloccoAsync(TimeSpan.FromMilliseconds(300));
        // Niente rete: resta bloccato finché il server non dice il contrario.
        Assert.True(m.Coperto);
    }

    [Theory]
    [InlineData(503, "{\"errore\": \"rete\"}")]                 // 5xx: non si cambia niente
    [InlineData(403, "{\"detail\": \"Forbidden\"}")]            // 403: non si cambia niente
    [InlineData(404, "{\"detail\": \"faccenda non trovata\"}")] // 404 non-FastAPI: non si cambia niente
    [InlineData(500, "{\"detail\": \"boom\"}")]                 // 5xx
    public async Task Risposte_che_non_tolgono_il_blocco(int stato, string corpo)
    {
        await using var server = new ServerFinto(_ => (stato, corpo));
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);
        m.AdottaBlocco(Blocco.Leggi(BloccoJson(-5)));
        Assert.True(m.Coperto);

        await m.AggiornaBloccoAsync();
        Assert.True(m.Coperto); // resta com'era
    }

    [Fact]
    public async Task Un_404_di_FastAPI_toglie_il_blocco_solo_se_il_server_non_l_ha_mai_mandato()
    {
        (int, string) risposta = (404, "{\"detail\": \"Not Found\"}");
        await using var server = new ServerFinto(_ => risposta);
        using var c = new CartellaTemporanea();

        // Prima: il server ha già mandato il blocco (200) → è un server che lo conosce.
        using (var m = Abbinato(c, server.Indirizzo))
        {
            risposta = (200, BloccoJson(-5).ToJsonString());
            await m.AggiornaBloccoAsync();
            Assert.True(m.Coperto);
            // Adesso un 404 improvviso (proxy?) NON deve togliere il blocco.
            risposta = (404, "{\"detail\": \"Not Found\"}");
            await m.AggiornaBloccoAsync();
            Assert.True(m.Coperto);
        }

        // Un computer che non ha mai visto il blocco: un 404 di FastAPI lo toglie (server vecchio).
        using var vergine = new CartellaTemporanea();
        using var m2 = Abbinato(vergine, server.Indirizzo);
        m2.AdottaBlocco(Blocco.Leggi(BloccoJson(-5)));
        Assert.True(m2.Coperto);
        await m2.AggiornaBloccoAsync();
        Assert.False(m2.Coperto);
    }

    [Fact]
    public async Task Il_blocco_arriva_anche_dal_patto_e_un_patto_vecchio_non_scavalca_una_risposta_fresca()
    {
        await using var server = new ServerFinto(r =>
            r.Percorso == "/api/faccende/blocco" ? (200, BloccoJson(-5).ToJsonString())
            : (200, "{\"regole\": []}")); // il patto, senza blocco
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);

        // Una risposta fresca di /faccende/blocco copre.
        await m.AggiornaBloccoAsync();
        Assert.True(m.Coperto);

        // Un patto chiesto "prima" (tick più piccolo) che arriva dopo non deve togliere il blocco.
        var pattoVecchio = new JsonObject
        {
            ["regole"] = new JsonArray(),
            ["blocco"] = new JsonObject { ["attivo"] = false, ["da_fare"] = new JsonArray() },
        };
        m.AdottaPatto(pattoVecchio, Tempo.AdessoUtcMs(), long.MinValue);
        Assert.True(m.Coperto);

        // Un patto fresco (tick grande) senza faccende toglie il blocco.
        m.AdottaPatto(pattoVecchio, Tempo.AdessoUtcMs(), long.MaxValue);
        Assert.False(m.Coperto);
    }

    [Fact]
    public void Il_segno_in_vivo_segue_la_copertura_subito()
    {
        using var c = new CartellaTemporanea();
        var percorsi = new Percorsi(c.Percorso);
        using var m = Abbinato(c, "http://127.0.0.1:9");

        m.AdottaBlocco(Blocco.Leggi(BloccoJson(-5)));
        Assert.True(m.Coperto);
        Assert.True(Archivio.LeggiJson<StatoVivo>(percorsi.Vivo)!.BloccatoFaccende);

        m.AdottaBlocco(StatoBlocco.Vuoto);
        Assert.False(m.Coperto);
        Assert.False(Archivio.LeggiJson<StatoVivo>(percorsi.Vivo)!.BloccatoFaccende);
    }

    [Fact]
    public void Sotto_la_copertura_non_si_misura()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.AdottaBlocco(Blocco.Leggi(BloccoJson(-5)));
        Assert.True(m.Coperto);
        // Sotto il blocco l'osservazione è "assente": il tempo non conta.
        Assert.False(m.Osserva().Attivo);
    }

    [Fact]
    public void Blocco_perso_ma_vivo_diceva_bloccato_resta_coperto_e_lo_segnala()
    {
        using var c = new CartellaTemporanea();
        // Nessun blocco.json, ma l'ultimo "sono vivo" diceva bloccato.
        using var m = Abbinato(c, "http://127.0.0.1:9");
        var precedente = new StatoVivo { UtcMs = Tempo.AdessoUtcMs(), TickMs = 1000, BloccatoFaccende = true };

        m.PreparaBlocco(precedente, Tempo.AdessoUtcMs());
        Assert.True(m.Coperto); // resta coperto con l'elenco generico
        var generica = Assert.Single(m.VistaBloccoCorrente.Faccende);
        Assert.Equal("Ci sono lavori di casa da fare", generica.Titolo);
        // E lo dice al genitore.
        Assert.Contains(m.EventiInCoda("manomissione"),
            e => Nucleo.Json.Testo(e.Dettagli["sotto_tipo"]) == "stato_blocco_perso");
    }

    [Fact]
    public void Senza_blocco_salvato_e_senza_segno_non_si_copre()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.PreparaBlocco(new StatoVivo { BloccatoFaccende = false }, Tempo.AdessoUtcMs());
        Assert.False(m.Coperto);
    }
}
