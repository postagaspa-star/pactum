using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pactum.Interfaccia;

/// <summary>
/// La prova del ponte dentro la WebView vera (<c>--autoprova file</c>): la pagina
/// fa le chiamate dell'accordo con fetch e il risultato finisce in un file. Serve
/// al collaudo; con <c>--autoprova-chiamate</c> si aggiungono chiamate proprie.
/// </summary>
public static class Autoprova
{
    private static readonly JsonArray Predefinite = new()
    {
        new JsonArray("GET", "/locale/stato"),
        new JsonArray("GET", "/locale/oggi"),
        new JsonArray("GET", "/locale/visti"),
        new JsonArray("GET", "/locale/serie"),
        new JsonArray("GET", "/server/api/patto"),
        new JsonArray("GET", "/locale/inesistente"),
        new JsonArray("POST", "/locale/stato", "{}"),
        new JsonArray("GET", "/server/fuori-api"),
    };

    public static JsonArray? Aggiuntive { get; set; }

    public static string Script
    {
        get
        {
            var chiamate = new JsonArray();
            foreach (var c in (Aggiuntive ?? Predefinite).Concat(Aggiuntive == null ? Array.Empty<JsonNode?>() : Predefinite))
            {
                chiamate.Add(c?.DeepClone());
            }
            return "(async () => {\n" +
                   "  const chiamate = " + chiamate.ToJsonString(new JsonSerializerOptions()) + ";\n" +
                   "  const esito = { pagina: location.origin + location.pathname, risultati: [] };\n" +
                   "  for (const [metodo, percorso, corpo] of chiamate) {\n" +
                   "    try {\n" +
                   "      const r = await fetch(percorso, { method: metodo, headers: corpo ? { 'Content-Type': 'application/json' } : {}, body: corpo });\n" +
                   "      const testo = await r.text();\n" +
                   "      let json = null; try { json = JSON.parse(testo); } catch (e) {}\n" +
                   "      esito.risultati.push({ metodo, percorso, stato: r.status, tipo: r.headers.get('content-type'), json });\n" +
                   "    } catch (e) { esito.risultati.push({ metodo, percorso, errore: String(e) }); }\n" +
                   "  }\n" +
                   "  chrome.webview.postMessage(JSON.stringify(esito, null, 1));\n" +
                   "})();";
        }
    }
}
