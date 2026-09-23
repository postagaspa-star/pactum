using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Pactum.Nucleo;

/// <summary>La copia locale dell'ultimo <c>GET /api/patto</c> (<c>patto.json</c>): serve anche senza rete.</summary>
public sealed class PattoSalvato
{
    [JsonPropertyName("aggiornato_utc_ms")] public long AggiornatoUtcMs { get; set; }
    [JsonPropertyName("patto")] public JsonObject Patto { get; set; } = new();
}

/// <summary>Il patto letto dal server, con le sole domande che servono al motore.</summary>
public sealed class PattoLocale
{
    public PattoLocale(JsonObject patto, long aggiornatoUtcMs)
    {
        Dati = patto;
        AggiornatoUtcMs = aggiornatoUtcMs;
        Regole = (patto["regole"] as JsonArray)?.Select(Regola.DaJson).Where(r => r != null).Select(r => r!).ToList()
                 ?? new List<Regola>();
    }

    public JsonObject Dati { get; }
    public long AggiornatoUtcMs { get; }
    public IReadOnlyList<Regola> Regole { get; }

    public string Fuso => Json.Testo(Dati["fuso"]) ?? "Europe/Rome";

    /// <summary>
    /// Le regole che il computer valuta: limite_tempo e fascia_oraria attive di QUESTO
    /// dispositivo (il patto v3 porta anche la vita reale del figlio, che si dichiara a mano).
    /// </summary>
    public IEnumerable<Regola> RegoleDelComputer(long? dispositivoId) => Regole.Where(r =>
        r.Attiva
        && (r.Tipo == TipiRegola.LimiteTempo || r.Tipo == TipiRegola.FasciaOraria)
        && (r.DispositivoId == null || dispositivoId == null || r.DispositivoId == dispositivoId));

    /// <summary>
    /// I bonus concessi oggi per regola. Valgono solo se la copia è di oggi nel fuso
    /// del patto: una copia di ieri non allunga i limiti di oggi.
    /// </summary>
    public Dictionary<string, int> BonusOggi(long adessoUtcMs)
    {
        var risultato = new Dictionary<string, int>(StringComparer.Ordinal);
        var zona = Zona(Fuso);
        if (Tempo.GiornoDi(AggiornatoUtcMs, zona) != Tempo.GiornoDi(adessoUtcMs, zona)) return risultato;
        if (Dati["bonus_oggi_per_regola"] is not JsonObject bonus) return risultato;
        foreach (var (chiave, valore) in bonus)
        {
            if (Json.Intero(valore) is long minuti && minuti > 0) risultato[chiave] = (int)minuti;
        }
        return risultato;
    }

    public List<GiornoPatto> Striscia => Serie.DaStriscia(Dati["striscia"]);

    public static TimeZoneInfo Zona(string id)
    {
        try
        {
            return TimeZoneInfo.FindSystemTimeZoneById(id);
        }
        catch (TimeZoneNotFoundException)
        {
            return TimeZoneInfo.Local;
        }
        catch (InvalidTimeZoneException)
        {
            return TimeZoneInfo.Local;
        }
    }
}
