using System.Text.Json.Nodes;
using Pactum.Motore;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// (0.14, contratto v3.7) Lo spegnimento del computer: la <c>sospensione</c> va su disco prima di tutto e parte
/// da sola, per prima, prima che la rete se ne vada; se non ce la fa, parte alla riaccensione col suo
/// <c>ts_device</c> (il server v3.7 lo usa per le sospensioni consegnate in ritardo). Uno spegnimento forzato
/// (solo SessionEnded, niente SessionEnding) la accoda lo stesso, una volta sola.
/// </summary>
public class MotoreSpegnimentoTest
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

    /// <summary>Gli eventi di un POST /api/eventi.</summary>
    private static List<JsonObject> EventiDi(ServerFinto.Richiesta r) =>
        ((Json.Analizza(r.Corpo) as JsonObject)?["eventi"] as JsonArray ?? new JsonArray())
            .OfType<JsonObject>().ToList();

    private static (int, string) Rispondi(ServerFinto.Richiesta r, int statoEventi) => r.Percorso switch
    {
        "/api/eventi" => (statoEventi, statoEventi == 200 ? "{\"ricevuti\": 1, \"nuovi\": 1, \"duplicati\": 0}" : "{\"errore\": \"rete\"}"),
        "/api/patto" => (200, "{\"regole\": []}"),
        "/api/notifiche" => (200, "{\"notifiche\": []}"),
        _ => (200, "{}"),
    };

    [Fact]
    public async Task Allo_spegnimento_la_sospensione_parte_da_sola_e_per_prima()
    {
        await using var server = new ServerFinto(r => Rispondi(r, 200));
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);
        long prima = Tempo.AdessoUtcMs();

        m.FineSessione(spegnimento: true);

        // La prima richiesta in assoluto è un POST /api/eventi con la sola sospensione.
        var primaRichiesta = server.Ricevute[0];
        Assert.Equal("POST", primaRichiesta.Metodo);
        Assert.Equal("/api/eventi", primaRichiesta.Percorso);
        var e = Assert.Single(EventiDi(primaRichiesta));
        Assert.Equal("sospensione", Json.Testo(e["tipo"]));
        Assert.Equal("spegnimento", Json.Testo((e["dettagli"] as JsonObject)?["motivo"]));
        Assert.InRange(Json.Intero(e["ts_device"]) ?? 0, prima, Tempo.AdessoUtcMs());
        // Consegnata: non resta in coda. E la chiusura pulita è scritta.
        Assert.Empty(m.EventiInCoda(TipiEvento.Sospensione));
        Assert.Equal(Chiusure.Spegnimento, Archivio.LeggiJson<StatoVivo>(new Percorsi(c.Percorso).Vivo)!.Chiusura);
    }

    [Fact]
    public async Task Se_la_rete_non_ce_la_fa_la_sospensione_parte_alla_riaccensione_col_suo_ts_device()
    {
        int statoEventi = 503; // il server non accetta: come una rete che se ne va
        await using var server = new ServerFinto(r => Rispondi(r, statoEventi));
        using var c = new CartellaTemporanea();

        string id;
        long ts;
        using (var m = Abbinato(c, server.Indirizzo))
        {
            m.FineSessione(spegnimento: true);
            var s = Assert.Single(m.EventiInCoda(TipiEvento.Sospensione));
            id = s.Id;
            ts = s.TsDevice;
        }

        // Riaccensione: un motore nuovo sulla stessa cartella trova la sospensione su disco, con lo stesso ts_device.
        statoEventi = 200;
        using var dopo = new Motore.Motore(new Percorsi(c.Percorso));
        var ritrovata = Assert.Single(dopo.EventiInCoda(TipiEvento.Sospensione));
        Assert.Equal(id, ritrovata.Id);
        Assert.Equal(ts, ritrovata.TsDevice);

        int giaViste = server.Ricevute.Count;
        Assert.True(await dopo.SincronizzaAsync("riaccensione"));
        var consegnata = server.Ricevute.Skip(giaViste)
            .Where(r => r.Percorso == "/api/eventi")
            .SelectMany(EventiDi)
            .Single(ev => Json.Testo(ev["id"]) == id);
        Assert.Equal(ts, Json.Intero(consegnata["ts_device"])); // l'ora vera dello spegnimento, non quella della consegna
        Assert.Empty(dopo.EventiInCoda(TipiEvento.Sospensione));
    }

    [Fact]
    public async Task Lo_spegnimento_forzato_accoda_la_sospensione_una_volta_sola()
    {
        await using var server = new ServerFinto(r => Rispondi(r, 503));
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);

        // Spegnimento forzato: arriva solo SessionEnded → la sospensione si accoda lo stesso.
        m.FineSessione(spegnimento: true, soloSeMancante: true);
        Assert.Single(m.EventiInCoda(TipiEvento.Sospensione));

        // SessionEnded dopo SessionEnding (spegnimento normale): non si ripete.
        m.FineSessione(spegnimento: true, soloSeMancante: true);
        Assert.Single(m.EventiInCoda(TipiEvento.Sospensione));

        // Un SessionEnding nuovo (uno spegnimento annullato e poi rifatto) sì.
        m.FineSessione(spegnimento: true);
        Assert.Equal(2, m.EventiInCoda(TipiEvento.Sospensione).Count);
    }

    [Fact]
    public async Task Anche_la_sospensione_di_Windows_va_su_disco_subito()
    {
        await using var server = new ServerFinto(r => Rispondi(r, 503));
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);
        long prima = Tempo.AdessoUtcMs();

        m.Sospensione();

        // Su disco prima che si provi a mandarla: un motore nuovo sulla stessa cartella la vede già.
        using var altro = new Motore.Motore(new Percorsi(c.Percorso));
        var s = Assert.Single(altro.EventiInCoda(TipiEvento.Sospensione));
        Assert.Equal("sospensione", Json.Testo(s.Dettagli["motivo"]));
        Assert.InRange(s.TsDevice, prima, Tempo.AdessoUtcMs());
    }
}
