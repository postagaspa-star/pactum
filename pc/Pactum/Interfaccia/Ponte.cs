using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Interfaccia;

/// <summary>
/// L'accordo fra motore e interfaccia (docs/pc-programma.md): le richieste della
/// pagina a <c>https://pactum.locale/locale/*</c> e <c>/server/*</c> arrivano qui,
/// niente porte aperte sul computer. Tutte le risposte sono JSON.
/// </summary>
public sealed class Ponte
{
    public const string Host = "pactum.locale";
    public const string Origine = "https://" + Host;

    private readonly Motore.Motore motore;

    public Ponte(Motore.Motore motore) => this.motore = motore;

    /// <summary>true per gli indirizzi dell'interfaccia; tutto il resto si apre nel browser di sistema.</summary>
    public static bool ÈInterno(string? indirizzo)
    {
        if (string.IsNullOrEmpty(indirizzo)) return false;
        if (indirizzo == "about:blank") return true;
        return Uri.TryCreate(indirizzo, UriKind.Absolute, out var u)
               && u.Scheme == Uri.UriSchemeHttps
               && string.Equals(u.Host, Host, StringComparison.OrdinalIgnoreCase)
               && u.IsDefaultPort;
    }

    /// <summary>Solo pagine web e posta si aprono fuori: niente file o programmi da un link.</summary>
    public static bool ApribileFuori(string? indirizzo) =>
        Uri.TryCreate(indirizzo, UriKind.Absolute, out var u)
        && (u.Scheme == Uri.UriSchemeHttps || u.Scheme == Uri.UriSchemeHttp || u.Scheme == Uri.UriSchemeMailto);

    /// <summary>Le chiamate dell'accordo: /locale/* e /server/*. Il resto sono file dell'interfaccia.</summary>
    public static bool ÈApi(Uri uri) =>
        uri.AbsolutePath.StartsWith("/locale/", StringComparison.Ordinal)
        || uri.AbsolutePath.StartsWith("/server/", StringComparison.Ordinal);

    /// <summary>
    /// Il file della cartella ui per un percorso della pagina ("/" = index.html), oppure null
    /// se il percorso prova a uscire dalla cartella o non è un nome di file pulito.
    /// </summary>
    public static string? FileDellInterfaccia(string cartellaUi, string percorsoUrl)
    {
        string relativo;
        try
        {
            relativo = Uri.UnescapeDataString(percorsoUrl).TrimStart('/');
        }
        catch (UriFormatException)
        {
            return null;
        }
        if (relativo.Length == 0) relativo = "index.html";
        if (relativo.Contains('\\') || relativo.Contains(':') || relativo.Contains('\0')) return null;
        if (relativo.Split('/').Any(p => p is "" or "." or "..")) return null;
        var radice = Path.GetFullPath(cartellaUi).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var completo = Path.GetFullPath(Path.Combine(radice, relativo.Replace('/', Path.DirectorySeparatorChar)));
        return completo.StartsWith(radice, StringComparison.OrdinalIgnoreCase) ? completo : null;
    }

    public static string TipoContenuto(string file) => Path.GetExtension(file).ToLowerInvariant() switch
    {
        ".html" or ".htm" => "text/html; charset=utf-8",
        ".js" or ".mjs" => "text/javascript; charset=utf-8",
        ".css" => "text/css; charset=utf-8",
        ".json" => "application/json; charset=utf-8",
        ".svg" => "image/svg+xml",
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        ".ico" => "image/x-icon",
        ".woff2" => "font/woff2",
        ".woff" => "font/woff",
        ".ttf" => "font/ttf",
        ".txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    };

    public async Task<(int Stato, string Json)> GestisciAsync(string metodo, Uri uri, string? corpo)
    {
        metodo = metodo.ToUpperInvariant();
        var percorso = uri.AbsolutePath;
        if (percorso.StartsWith("/locale/", StringComparison.Ordinal))
        {
            var nome = percorso["/locale/".Length..].TrimEnd('/');
            return await LocaleAsync(metodo, nome, corpo).ConfigureAwait(false);
        }
        if (percorso.StartsWith("/server/", StringComparison.Ordinal))
        {
            return await motore.InoltraAsync(metodo, percorso["/server/".Length..], uri.Query, corpo).ConfigureAwait(false);
        }
        return Json(404, new JsonObject { ["errore"] = "sconosciuto" });
    }

    private async Task<(int, string)> LocaleAsync(string metodo, string nome, string? corpo)
    {
        (string Metodo, Func<Task<JsonObject>> Azione)? rotta = nome switch
        {
            "stato" => ("GET", () => Task.FromResult(motore.Stato())),
            "oggi" => ("GET", () => Task.Run(motore.Oggi)),
            "visti" => ("GET", () => Task.Run(motore.Visti)),
            "serie" => ("GET", () => Task.Run(motore.SerieERecord)),
            "abbina" => ("POST", () =>
            {
                var o = Corpo(corpo);
                return motore.AbbinaAsync(Nucleo.Json.Testo(o?["server"]), Testo(o?["codice"]));
            }),
            "bonus" => ("POST", () =>
            {
                var o = Corpo(corpo);
                return motore.BonusAsync(Nucleo.Json.Intero(o?["regola_id"]), Nucleo.Json.Intero(o?["minuti"]), Nucleo.Json.Testo(o?["motivo"]));
            }),
            "aggiorna" => ("POST", motore.AggiornaAsync),
            _ => null,
        };
        if (rotta == null) return Json(404, new JsonObject { ["errore"] = "sconosciuto" });
        if (rotta.Value.Metodo != metodo) return Json(405, new JsonObject { ["errore"] = "metodo_non_permesso" });
        return Json(200, await rotta.Value.Azione().ConfigureAwait(false));
    }

    private static JsonObject? Corpo(string? corpo) => Nucleo.Json.Analizza(corpo) as JsonObject;

    /// <summary>Il codice può arrivare come testo ("012345") o, per sbaglio, come numero.</summary>
    private static string? Testo(JsonNode? n) =>
        Nucleo.Json.Testo(n) ?? (Nucleo.Json.Intero(n) is long l ? l.ToString("000000", System.Globalization.CultureInfo.InvariantCulture) : null);

    private static (int, string) Json(int stato, JsonObject o) => (stato, o.ToJsonString(Nucleo.Json.Opzioni));
}
