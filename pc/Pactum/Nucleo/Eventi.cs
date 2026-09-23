using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Pactum.Nucleo;

public static class TipiEvento
{
    public const string UsoGiornaliero = "uso_giornaliero";
    public const string SitiGiornalieri = "siti_giornalieri";
    public const string Manomissione = "manomissione";
    public const string Sforamento = "sforamento";
    public const string Sospensione = "sospensione";
    public const string Ripresa = "ripresa";
}

/// <summary>Un evento del registro (contratto, POST /api/eventi). L'id è la chiave di idempotenza.</summary>
public sealed class Evento
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("tipo")] public string Tipo { get; set; } = "";
    [JsonPropertyName("ts_device")] public long TsDevice { get; set; }
    [JsonPropertyName("dettagli")] public JsonObject Dettagli { get; set; } = new();

    public static Evento Nuovo(string tipo, long tsDevice, JsonObject dettagli) => new()
    {
        Id = Guid.NewGuid().ToString(),
        Tipo = tipo,
        TsDevice = tsDevice,
        Dettagli = dettagli,
    };

    public JsonObject ComeJson() => new()
    {
        ["id"] = Id,
        ["tipo"] = Tipo,
        ["ts_device"] = TsDevice,
        ["dettagli"] = Dettagli.DeepClone(),
    };
}

/// <summary>Le forme degli eventi del computer (contratto v3, "Computer: cosa cambia nel registro").</summary>
public static class Eventi
{
    public static Evento Manomissione(JsonObject dettagli, long ts) => Evento.Nuovo(TipiEvento.Manomissione, ts, dettagli);

    /// <summary>"Chiudi Pactum" dal menu: detto subito, con il momento della chiusura.</summary>
    public static Evento ChiusuraVolontaria(long ts) => Manomissione(new JsonObject
    {
        ["sotto_tipo"] = "programma_chiuso",
        ["volontario"] = true,
        ["dal"] = ts,
    }, ts);

    /// <summary>Un browser di cui non si riesce a leggere la barra degli indirizzi (una volta al giorno).</summary>
    public static Evento SitiNonLeggibili(string programma, string giorno, long ts) => Manomissione(new JsonObject
    {
        ["sotto_tipo"] = "siti_non_leggibili",
        ["programma"] = programma,
        ["giorno"] = giorno,
    }, ts);

    /// <param name="motivo"><c>spegnimento · sospensione · disconnessione</c></param>
    public static Evento Sospensione(string motivo, long ts) =>
        Evento.Nuovo(TipiEvento.Sospensione, ts, new JsonObject { ["motivo"] = motivo });

    /// <param name="motivo"><c>avvio · riattivazione · accesso</c></param>
    public static Evento Ripresa(string motivo, long avvioSistemaMs, long ts) =>
        Evento.Nuovo(TipiEvento.Ripresa, ts, new JsonObject { ["motivo"] = motivo, ["avvio_sistema_ts"] = avvioSistemaMs });

    public static Evento Sforamento(Nucleo.Sforamento s, string giornoComputer, long ts) =>
        Evento.Nuovo(TipiEvento.Sforamento, ts, Valutatore.DettagliSforamento(s, giornoComputer));

    /// <summary>Il corpo di POST /api/eventi.</summary>
    public static JsonObject Lotto(IEnumerable<Evento> eventi) =>
        new() { ["eventi"] = new JsonArray(eventi.Select(e => (JsonNode)e.ComeJson()).ToArray()) };

    /// <summary>Il corpo di POST /api/battito.</summary>
    public static JsonObject Battito(long ts, long msDallAvvio, int? batteria)
    {
        var b = new JsonObject
        {
            ["ts_device"] = ts,
            ["versione_app"] = Versione.Nome,
            ["elapsed_realtime"] = msDallAvvio,
        };
        if (batteria is >= 0 and <= 100) b["batteria"] = batteria.Value;
        return b;
    }
}

/// <summary>Le fotografie cumulative del giorno (uso_giornaliero e siti_giornalieri).</summary>
public static class Fotografie
{
    public static Evento Uso(Giornata g, long ts) => Evento.Nuovo(TipiEvento.UsoGiornaliero, ts, DettagliUso(g));

    public static Evento Siti(Giornata g, long ts) => Evento.Nuovo(TipiEvento.SitiGiornalieri, ts, DettagliSiti(g));

    /// <summary>
    /// <c>uso_minuti</c> con chiavi <c>exe:</c>, <c>nomi</c> leggibili, <c>totale_minuti</c> = tempo attivo,
    /// <c>uso_categorie</c> col tempo nel browser nella categoria del sito. Solo voci da almeno un minuto.
    /// </summary>
    public static JsonObject DettagliUso(Giornata g)
    {
        var uso = new JsonObject();
        var nomi = new JsonObject();
        foreach (var (chiave, voce) in g.Programmi.OrderByDescending(p => p.Value.Ms).ThenBy(p => p.Key, StringComparer.Ordinal))
        {
            long minuti = Giornata.Minuti(voce.Ms);
            if (minuti < 1) continue;
            uso[chiave] = minuti;
            nomi[chiave] = voce.Nome;
        }
        var categorie = new JsonObject();
        foreach (var (chiave, ms) in g.MsPerCategoria.OrderBy(c => c.Key, StringComparer.Ordinal))
        {
            long minuti = Giornata.Minuti(ms);
            if (minuti >= 1) categorie[chiave] = minuti;
        }
        return new JsonObject
        {
            ["giorno"] = g.Giorno,
            ["uso_minuti"] = uso,
            ["totale_minuti"] = g.MinutiTotali,
            ["nomi"] = nomi,
            ["uso_categorie"] = categorie,
        };
    }

    /// <summary>
    /// <c>domini</c> = visite, <c>minuti</c> = minuti con quel sito in primo piano, al massimo 200 domini
    /// (i più usati), ma <c>totale_domini</c> è il conteggio vero. <c>dns_cifrato</c> = siti non leggibili.
    /// </summary>
    public static JsonObject DettagliSiti(Giornata g)
    {
        var scelti = g.Siti
            .OrderByDescending(s => s.Value.Ms)
            .ThenByDescending(s => s.Value.Visite)
            .ThenBy(s => s.Key, StringComparer.Ordinal)
            .Take(Domini.LimiteDominiFotografia)
            .ToList();
        var domini = new JsonObject();
        var minuti = new JsonObject();
        foreach (var (dominio, voce) in scelti)
        {
            domini[dominio] = voce.Visite;
            minuti[dominio] = Giornata.Minuti(voce.Ms);
        }
        return new JsonObject
        {
            ["giorno"] = g.Giorno,
            ["domini"] = domini,
            ["minuti"] = minuti,
            ["totale_domini"] = g.Siti.Count,
            ["dns_cifrato"] = g.SitiNonLeggibili,
        };
    }
}
