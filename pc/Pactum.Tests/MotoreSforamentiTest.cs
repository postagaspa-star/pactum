using System.Text.Json.Nodes;
using Pactum.Motore;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// Gli sforamenti nel motore vero: col disco che non scrive fumetto e avviso partono lo stesso e gli
/// eventi restano in coda; il registro si riprova finché non si scrive; un riavvio lo stesso giorno non
/// ripete niente; a mezzanotte e allo spegnimento si valuta, ma senza avvisi a schermo.
/// Cambiano la scrittura su disco (Archivio.Sposta): girano da soli, come ArchivioTest.
/// </summary>
[Collection("Archivio")]
public class MotoreSforamentiTest : IDisposable
{
    private readonly Action<string, string> spostaVera = Archivio.Sposta;

    public void Dispose()
    {
        Archivio.Sposta = spostaVera;
        Archivio.RinominaVietata = false;
    }

    private static string Oggi => Tempo.GiornoDi(Tempo.AdessoUtcMs(), TimeZoneInfo.Local);

    /// <summary>Il disco "pieno" per i file che finiscono così: la rinomina finale non riesce mai.</summary>
    private void DiscoPienoPer(params string[] file)
    {
        var vera = spostaVera;
        Archivio.Sposta = (da, a) =>
        {
            if (file.Any(f => a.EndsWith(f, StringComparison.OrdinalIgnoreCase))) throw new IOException("disco pieno (prova)");
            vera(da, a);
        };
    }

    /// <summary>
    /// Un computer abbinato (a un indirizzo che non risponde), con i limiti dati nel patto salvato e
    /// <paramref name="minutiOggi"/> minuti attivi oggi, di cui 30 di Minecraft.
    /// </summary>
    private static Motore.Motore Prepara(CartellaTemporanea c, int minutiOggi, params (long Id, string Chiave, int Minuti)[] limiti)
    {
        var percorsi = new Percorsi(c.Percorso);
        Archivio.ScriviJson(percorsi.Config, new Configurazione
        {
            Server = "http://127.0.0.1:9",
            TokenProtetto = Dpapi.Proteggi("token-di-prova"),
            Dispositivo = new JsonObject { ["id"] = 2, ["nome"] = "Computer", ["tipo"] = "computer" },
            Figlio = new JsonObject { ["id"] = 1, ["nome"] = "Andrea" },
        });
        var regole = new JsonArray(limiti.Select(l => (JsonNode)new JsonObject
        {
            ["id"] = l.Id,
            ["tipo"] = TipiRegola.LimiteTempo,
            ["attiva"] = true,
            ["dispositivo_id"] = 2,
            ["parametri"] = new JsonObject { ["app_o_categoria"] = l.Chiave, ["minuti_al_giorno"] = l.Minuti },
        }).ToArray());
        Archivio.ScriviJson(percorsi.Patto, new PattoSalvato
        {
            AggiornatoUtcMs = Tempo.AdessoUtcMs(),
            Patto = new JsonObject { ["regole"] = regole, ["fuso"] = "Europe/Rome" },
        });
        var g = Giornata.Nuova(Oggi);
        g.MsAttivi = minutiOggi * 60_000L;
        g.Programmi["exe:minecraft.exe"] = new VoceProgramma { Nome = "Minecraft", Ms = 30 * 60_000L };
        Archivio.ScriviJson(percorsi.FileGiorno(Oggi), g);
        return new Motore.Motore(percorsi);
    }

    private static IEnumerable<long?> Regole(IEnumerable<Evento> sforamenti) =>
        sforamenti.Select(e => Json.Intero(e.Dettagli["regola_id"])).OrderBy(x => x);

    [Fact]
    public void Col_disco_che_non_scrive_fumetto_e_avviso_partono_e_gli_eventi_restano_in_coda()
    {
        using var c = new CartellaTemporanea();
        using var motore = Prepara(c, 150, (7, "totale", 120), (8, "exe:minecraft.exe", 10));
        var fumetti = new List<string>();
        var avvisi = new List<IReadOnlyList<Avviso>>();
        motore.Fumetto += (_, testo) => fumetti.Add(testo);
        motore.AvvisoTuttoSchermo += a => avvisi.Add(a);
        DiscoPienoPer("sforamenti.json", "coda.json");

        long adesso = Tempo.AdessoUtcMs();
        motore.ValutaRegole(adesso);

        // Tutte e due le regole: due fumetti e un avviso con due schede, anche se niente si è scritto.
        Assert.Equal(2, fumetti.Count);
        Assert.Equal(new long[] { 7, 8 }, Assert.Single(avvisi).Select(a => a.RegolaId).OrderBy(x => x));
        Assert.Equal(new long?[] { 7, 8 }, Regole(motore.EventiInCoda(TipiEvento.Sforamento)));
        Assert.False(File.Exists(c.File("sforamenti.json")));

        // La valutazione dopo: niente di nuovo (una volta per regola per giorno); il registro si riprova, ancora invano.
        motore.ValutaRegole(adesso + 15_000);
        Assert.Equal(2, fumetti.Count);
        Assert.Single(avvisi);
        Assert.False(File.Exists(c.File("sforamenti.json")));

        // Il disco torna: alla valutazione dopo si scrivono registro e coda, senza avvisi nuovi.
        Archivio.Sposta = spostaVera;
        motore.ValutaRegole(adesso + 30_000);
        Assert.Equal(2, fumetti.Count);
        var registro = Archivio.LeggiJson<RegistroSforamenti>(c.File("sforamenti.json"))!;
        Assert.True(registro.Contiene(7, Oggi));
        Assert.True(registro.Contiene(8, Oggi));
        var coda = Archivio.LeggiJson<List<Evento>>(c.File("coda.json"))!;
        Assert.Equal(new long?[] { 7, 8 }, Regole(coda.Where(e => e.Tipo == TipiEvento.Sforamento)));

        // Riavvio lo stesso giorno: niente secondo sforamento, niente secondo avviso.
        using var dopo = new Motore.Motore(new Percorsi(c.Percorso));
        int mostrati = 0;
        dopo.Fumetto += (_, _) => mostrati++;
        dopo.AvvisoTuttoSchermo += _ => mostrati++;
        dopo.ValutaRegole(Tempo.AdessoUtcMs());
        Assert.Equal(0, mostrati);
        Assert.Equal(2, dopo.EventiInCoda(TipiEvento.Sforamento).Count);
    }

    [Fact]
    public void Se_il_registro_non_si_era_scritto_al_riavvio_basta_la_coda()
    {
        using var c = new CartellaTemporanea();
        using (var prima = Prepara(c, 150, (7, "totale", 120)))
        {
            DiscoPienoPer("sforamenti.json");
            prima.ValutaRegole(Tempo.AdessoUtcMs());
            Assert.Single(prima.EventiInCoda(TipiEvento.Sforamento));
        }
        // Chiuso prima di riuscire a scrivere il registro: sul disco c'è solo la coda.
        Assert.False(File.Exists(c.File("sforamenti.json")));
        Archivio.Sposta = spostaVera;

        using var dopo = new Motore.Motore(new Percorsi(c.Percorso));
        int mostrati = 0;
        dopo.Fumetto += (_, _) => mostrati++;
        dopo.AvvisoTuttoSchermo += _ => mostrati++;
        dopo.ValutaRegole(Tempo.AdessoUtcMs());
        Assert.Equal(0, mostrati);
        Assert.Single(dopo.EventiInCoda(TipiEvento.Sforamento));
        // E alla prima valutazione il registro ricostruito finisce sul disco.
        Assert.True(Archivio.LeggiJson<RegistroSforamenti>(c.File("sforamenti.json"))!.Contiene(7, Oggi));
    }

    [Fact]
    public void A_mezzanotte_e_allo_spegnimento_si_valuta_ma_senza_avvisi_a_schermo()
    {
        using var c = new CartellaTemporanea();
        using var motore = Prepara(c, 150, (7, "totale", 120));
        int mostrati = 0;
        motore.Fumetto += (_, _) => mostrati++;
        motore.AvvisoTuttoSchermo += _ => mostrati++;

        // Il giorno che si chiude, valutato fino all'ultimo secondo: solo l'evento, e col suo giorno.
        Assert.True(Tempo.ProvaGiorno(Oggi, out var dataOggi));
        var ieri = Giornata.Nuova(Tempo.Testo(dataOggi.AddDays(-1)));
        ieri.MsAttivi = 125 * 60_000L;
        long fineIeri = Tempo.InizioGiorno(dataOggi, TimeZoneInfo.Local) - 1;
        motore.ValutaRegole(fineIeri, ieri, conAvvisi: false);
        var sforamento = Assert.Single(motore.EventiInCoda(TipiEvento.Sforamento));
        Assert.Equal(ieri.Giorno, Json.Testo(sforamento.Dettagli["giorno"]));
        Assert.Equal(5, Json.Intero(sforamento.Dettagli["minuti_oltre"]));
        Assert.Equal(0, mostrati);

        // Spegnimento: lo sforamento di oggi va in coda prima dell'ultimo invio, senza avvisi a schermo.
        motore.FineSessione(spegnimento: true);
        Assert.Contains(motore.EventiInCoda(TipiEvento.Sforamento), e => Json.Testo(e.Dettagli["giorno"]) == Oggi);
        Assert.Equal(0, mostrati);
    }

    [Fact]
    public void La_coda_tiene_l_evento_anche_se_il_file_non_si_scrive_e_lo_riscrive_dopo()
    {
        using var c = new CartellaTemporanea();
        int errori = 0;
        var coda = new CodaEventi(c.File("coda.json")) { ScritturaFallita = _ => errori++ };
        DiscoPienoPer("coda.json");

        coda.Accoda(Evento.Nuovo(TipiEvento.Manomissione, 1, new JsonObject { ["sotto_tipo"] = "a" }));
        coda.Accoda(Evento.Nuovo(TipiEvento.Manomissione, 2, new JsonObject { ["sotto_tipo"] = "b" }));
        Assert.Equal(2, coda.Conta);
        Assert.True(coda.DaSalvare);
        Assert.Equal(1, errori); // nel diario una volta sola per serie, non a ogni tentativo

        Archivio.Sposta = spostaVera;
        coda.SalvaSeServe();
        Assert.False(coda.DaSalvare);
        Assert.Equal(2, new CodaEventi(c.File("coda.json")).Conta);
    }
}
