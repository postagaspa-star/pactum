using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>La coda su disco: sopravvive ai riavvii, si svuota solo per id, rimandare non duplica.</summary>
public class CodaEventiTest
{
    private static Evento Manomissione(string sottoTipo) =>
        Evento.Nuovo(TipiEvento.Manomissione, 1000, new JsonObject { ["sotto_tipo"] = sottoTipo });

    private static Evento Uso(string giorno, int totale) =>
        Evento.Nuovo(TipiEvento.UsoGiornaliero, 1000, new JsonObject { ["giorno"] = giorno, ["totale_minuti"] = totale });

    /// <summary>Un finto server idempotente: tiene gli id visti, come il vero.</summary>
    private sealed class FintoServer
    {
        public HashSet<string> Visti { get; } = new();
        public int Chiamate { get; private set; }
        public int Duplicati { get; private set; }
        public Func<IReadOnlyList<Evento>, int>? Risposta { get; set; }

        public Task<int> Ricevi(IReadOnlyList<Evento> lotto)
        {
            Chiamate++;
            var stato = Risposta?.Invoke(lotto) ?? 200;
            if (stato is >= 200 and < 300 or -1)
            {
                foreach (var e in lotto)
                {
                    if (!Visti.Add(e.Id)) Duplicati++;
                }
            }
            return Task.FromResult(stato == -1 ? 0 : stato);
        }
    }

    [Fact]
    public void La_coda_sopravvive_al_riavvio()
    {
        using var cartella = new CartellaTemporanea();
        var prima = new CodaEventi(cartella.File("coda.json"));
        var eventi = new[] { Manomissione("a"), Manomissione("b"), Manomissione("c") };
        foreach (var e in eventi) prima.Accoda(e);

        var dopo = new CodaEventi(cartella.File("coda.json"));
        Assert.Equal(eventi.Select(e => e.Id), dopo.Prossimi(10).Select(e => e.Id));
        Assert.Equal("b", Json.Testo(dopo.Prossimi(10)[1].Dettagli["sotto_tipo"]));
    }

    [Fact]
    public void Una_fotografia_nuova_sostituisce_quella_dello_stesso_giorno()
    {
        using var cartella = new CartellaTemporanea();
        var coda = new CodaEventi(cartella.File("coda.json"));
        coda.SostituisciFotografia(Uso("2026-09-22", 100));
        coda.SostituisciFotografia(Uso("2026-09-23", 10));
        coda.Accoda(Manomissione("x"));
        var ultima = Uso("2026-09-23", 20);
        coda.SostituisciFotografia(ultima);
        coda.SostituisciFotografia(Evento.Nuovo(TipiEvento.SitiGiornalieri, 1, new JsonObject { ["giorno"] = "2026-09-23" }));

        var tutti = coda.Prossimi(10);
        Assert.Equal(4, tutti.Count);
        Assert.Single(tutti, e => e.Tipo == TipiEvento.UsoGiornaliero && Json.Testo(e.Dettagli["giorno"]) == "2026-09-23");
        Assert.Contains(tutti, e => e.Id == ultima.Id);
        Assert.Single(tutti, e => e.Tipo == TipiEvento.SitiGiornalieri);
    }

    [Fact]
    public void Si_toglie_per_id_anche_se_la_coda_e_cambiata_nel_frattempo()
    {
        using var cartella = new CartellaTemporanea();
        var coda = new CodaEventi(cartella.File("coda.json"));
        coda.SostituisciFotografia(Uso("2026-09-23", 10));
        coda.Accoda(Manomissione("a"));
        var mandati = coda.Prossimi(10);
        // Mentre il lotto viaggia arriva una fotografia nuova dello stesso giorno.
        var nuova = Uso("2026-09-23", 30);
        coda.SostituisciFotografia(nuova);
        coda.Rimuovi(mandati.Select(e => e.Id));
        Assert.Equal(new[] { nuova.Id }, coda.Prossimi(10).Select(e => e.Id));
    }

    [Fact]
    public async Task Una_risposta_persa_si_rimanda_e_il_server_non_duplica()
    {
        using var cartella = new CartellaTemporanea();
        var coda = new CodaEventi(cartella.File("coda.json"));
        for (int i = 0; i < 5; i++) coda.Accoda(Manomissione("e" + i));
        var server = new FintoServer { Risposta = _ => -1 }; // il server salva, la risposta si perde

        Assert.Equal(EsitoCoda.Interrotta, await coda.InviaAsync(server.Ricevi));
        Assert.Equal(5, coda.Conta);

        server.Risposta = null;
        // Il programma riparte: la coda è ancora su disco.
        var riaperta = new CodaEventi(cartella.File("coda.json"));
        Assert.Equal(EsitoCoda.Vuota, await riaperta.InviaAsync(server.Ricevi));
        Assert.Equal(0, riaperta.Conta);
        Assert.Equal(5, server.Visti.Count);
        Assert.Equal(5, server.Duplicati);
    }

    [Fact]
    public async Task Senza_rete_la_coda_resta_com_era()
    {
        using var cartella = new CartellaTemporanea();
        var coda = new CodaEventi(cartella.File("coda.json"));
        coda.Accoda(Manomissione("a"));
        var server = new FintoServer { Risposta = _ => 0 };
        Assert.Equal(EsitoCoda.Interrotta, await coda.InviaAsync(server.Ricevi));
        Assert.Equal(1, new CodaEventi(cartella.File("coda.json")).Conta);
    }

    [Fact]
    public async Task Un_evento_guasto_non_blocca_gli_altri()
    {
        using var cartella = new CartellaTemporanea();
        var coda = new CodaEventi(cartella.File("coda.json"));
        coda.Accoda(Manomissione("buono1"));
        var guasto = Manomissione("guasto");
        coda.Accoda(guasto);
        coda.Accoda(Manomissione("buono2"));
        var server = new FintoServer
        {
            Risposta = lotto => lotto.Any(e => Json.Testo(e.Dettagli["sotto_tipo"]) == "guasto") ? 422 : 200,
        };
        var scartati = new List<string>();

        Assert.Equal(EsitoCoda.Vuota, await coda.InviaAsync(server.Ricevi, (e, _) => scartati.Add(e.Id)));
        Assert.Equal(new[] { guasto.Id }, scartati);
        Assert.Equal(2, server.Visti.Count);
        Assert.Equal(0, coda.Conta);
    }

    [Fact]
    public async Task Un_server_che_dice_non_ora_non_fa_scartare_niente()
    {
        using var cartella = new CartellaTemporanea();
        var coda = new CodaEventi(cartella.File("coda.json"));
        coda.Accoda(Manomissione("a"));
        foreach (var stato in new[] { 401, 403, 404, 429, 500, 503 })
        {
            var server = new FintoServer { Risposta = _ => stato };
            Assert.Equal(EsitoCoda.Interrotta, await coda.InviaAsync(server.Ricevi));
            Assert.Equal(1, coda.Conta);
        }
    }

    [Fact]
    public async Task Tanti_eventi_partono_a_lotti()
    {
        using var cartella = new CartellaTemporanea();
        var coda = new CodaEventi(cartella.File("coda.json"));
        for (int i = 0; i < 250; i++) coda.Accoda(Manomissione("e" + i));
        var dimensioni = new List<int>();
        var server = new FintoServer { Risposta = l => { dimensioni.Add(l.Count); return 200; } };
        Assert.Equal(EsitoCoda.Vuota, await coda.InviaAsync(server.Ricevi));
        Assert.Equal(new[] { 100, 100, 50 }, dimensioni);
    }

    [Fact]
    public void Un_file_rovinato_non_ferma_il_programma()
    {
        using var cartella = new CartellaTemporanea();
        File.WriteAllText(cartella.File("coda.json"), "[{\"id\": tronc");
        var coda = new CodaEventi(cartella.File("coda.json"));
        Assert.Equal(0, coda.Conta);
        Assert.True(File.Exists(cartella.File("coda.json.guasto")));
        coda.Accoda(Manomissione("a"));
        Assert.Equal(1, new CodaEventi(cartella.File("coda.json")).Conta);
    }
}
