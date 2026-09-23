using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pactum.Nucleo;

public static class TipiRegola
{
    public const string LimiteTempo = "limite_tempo";
    public const string FasciaOraria = "fascia_oraria";
    public const string VitaReale = "vita_reale";
}

/// <summary>Una regola del patto come arriva da <c>GET /api/patto</c> (contratto v3).</summary>
public sealed record Regola(long Id, string Tipo, JsonObject Parametri, bool Attiva, long? DispositivoId)
{
    public static Regola? DaJson(JsonNode? nodo)
    {
        if (nodo is not JsonObject o) return null;
        var id = Json.Intero(o["id"]);
        var tipo = Json.Testo(o["tipo"]);
        if (id == null || tipo == null) return null;
        var parametri = o["parametri"] is JsonObject p ? (JsonObject)p.DeepClone() : new JsonObject();
        bool attiva = o["attiva"] is not JsonValue v || !v.TryGetValue<bool>(out var b) || b;
        long? dispositivo = Json.Intero(o["dispositivo_id"]) ?? Json.Intero((o["dispositivo"] as JsonObject)?["id"]);
        return new Regola(id.Value, tipo, parametri, attiva, dispositivo);
    }

    public string? Stringa(string nome)
    {
        var t = Json.Testo(Parametri[nome]);
        return string.IsNullOrWhiteSpace(t) ? null : t;
    }

    public int? Intero(string nome) => Json.Intero(Parametri[nome]) is long l ? (int)l : null;

    public IReadOnlyList<string> Stringhe(string nome) =>
        Parametri[nome] is JsonArray a
            ? a.Select(Json.Testo).Where(s => s != null).Select(s => s!).ToList()
            : Array.Empty<string>();

    public TimeOnly? Ora(string nome)
    {
        var t = Stringa(nome);
        if (t == null) return null;
        return TimeOnly.TryParseExact(t, new[] { "HH:mm", "H:mm", "HH:mm:ss" }, CultureInfo.InvariantCulture, DateTimeStyles.None, out var ora)
            ? ora
            : null;
    }
}

/// <summary>Letture tolleranti dal JSON del server (tolleranza evolutiva del contratto).</summary>
public static class Json
{
    public static readonly JsonSerializerOptions Opzioni = new()
    {
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static readonly JsonSerializerOptions OpzioniFile = new()
    {
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        WriteIndented = true,
    };

    public static string? Testo(JsonNode? n) =>
        n is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;

    public static long? Intero(JsonNode? n)
    {
        if (n is not JsonValue v) return null;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<int>(out var i)) return i;
        if (v.TryGetValue<double>(out var d) && Math.Abs(d - Math.Round(d)) < 1e-9) return (long)Math.Round(d);
        if (v.TryGetValue<JsonElement>(out var e))
        {
            if (e.ValueKind == JsonValueKind.Number && e.TryGetInt64(out var x)) return x;
            if (e.ValueKind == JsonValueKind.String && long.TryParse(e.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var y)) return y;
        }
        if (v.TryGetValue<string>(out var s) && long.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var z)) return z;
        return null;
    }

    public static bool? Booleano(JsonNode? n)
    {
        if (n is not JsonValue v) return null;
        if (v.TryGetValue<bool>(out var b)) return b;
        if (v.TryGetValue<JsonElement>(out var e) && (e.ValueKind == JsonValueKind.True || e.ValueKind == JsonValueKind.False)) return e.GetBoolean();
        return null;
    }

    public static JsonNode? Analizza(string? testo)
    {
        if (string.IsNullOrWhiteSpace(testo)) return null;
        try
        {
            return JsonNode.Parse(testo);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
