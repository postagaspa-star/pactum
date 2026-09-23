using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Pactum.Motore;

/// <summary>Dove stanno i dati: <c>%LOCALAPPDATA%\Pactum\</c> (o la cartella di <c>--dati</c> per le prove).</summary>
public sealed class Percorsi
{
    public Percorsi(string radice)
    {
        Radice = radice;
        Directory.CreateDirectory(radice);
        Directory.CreateDirectory(Giorni);
    }

    public static string Predefinita =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pactum");

    public string Radice { get; }
    public string Config => Path.Combine(Radice, "config.json");
    public string Giorni => Path.Combine(Radice, "giorni");
    public string Coda => Path.Combine(Radice, "coda.json");
    public string Vivo => Path.Combine(Radice, "vivo.json");
    public string Patto => Path.Combine(Radice, "patto.json");
    public string Sforamenti => Path.Combine(Radice, "sforamenti.json");
    public string Notifiche => Path.Combine(Radice, "notifiche.json");
    public string Serie => Path.Combine(Radice, "serie.json");
    public string Log => Path.Combine(Radice, "log");
    public string WebView => Path.Combine(Radice, "WebView2");

    public string FileGiorno(string giorno) => Path.Combine(Giorni, giorno + ".json");
}

/// <summary><c>config.json</c>: server, token protetto con DPAPI, dispositivo e figlio. Mai il token in chiaro.</summary>
public sealed class Configurazione
{
    [JsonPropertyName("server")] public string? Server { get; set; }
    [JsonPropertyName("token_protetto")] public string? TokenProtetto { get; set; }
    [JsonPropertyName("dispositivo")] public JsonObject? Dispositivo { get; set; }
    [JsonPropertyName("figlio")] public JsonObject? Figlio { get; set; }
    [JsonPropertyName("abbinato_il")] public string? AbbinatoIl { get; set; }
}

/// <summary><c>notifiche.json</c>: fin dove sono già stati mostrati gli avvisi del server.</summary>
public sealed class StatoNotifiche
{
    [JsonPropertyName("ultimo_id")] public long? UltimoId { get; set; }
}
