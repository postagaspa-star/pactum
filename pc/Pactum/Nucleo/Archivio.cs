using System.Text;
using System.Text.Json;

namespace Pactum.Nucleo;

/// <summary>
/// Scritture atomiche su disco: file temporaneo scritto fino in fondo (flush su
/// disco), poi rinomina sopra quello vecchio. Un file a metà non esiste mai.
/// </summary>
public static class Archivio
{
    public static void ScriviTesto(string percorso, string testo) => ScriviByte(percorso, new UTF8Encoding(false).GetBytes(testo));

    public static void ScriviByte(string percorso, byte[] dati)
    {
        var cartella = Path.GetDirectoryName(percorso);
        if (!string.IsNullOrEmpty(cartella)) Directory.CreateDirectory(cartella);
        var temporaneo = percorso + ".tmp";
        using (var fs = new FileStream(temporaneo, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            fs.Write(dati, 0, dati.Length);
            fs.Flush(flushToDisk: true);
        }
        // L'antivirus a volte tiene aperto il file appena scritto per un attimo: si riprova.
        for (int tentativo = 1; ; tentativo++)
        {
            try
            {
                File.Move(temporaneo, percorso, overwrite: true);
                return;
            }
            catch (IOException) when (tentativo < 5)
            {
                Thread.Sleep(40 * tentativo);
            }
            catch (UnauthorizedAccessException) when (tentativo < 5)
            {
                Thread.Sleep(40 * tentativo);
            }
        }
    }

    public static void ScriviJson<T>(string percorso, T valore) =>
        ScriviTesto(percorso, JsonSerializer.Serialize(valore, Json.OpzioniFile));

    /// <summary>
    /// Legge un file JSON; se manca restituisce null. Se è rovinato lo mette da
    /// parte (<c>.guasto</c>) e restituisce null: meglio ripartire che fermarsi.
    /// </summary>
    public static T? LeggiJson<T>(string percorso) where T : class
    {
        if (!File.Exists(percorso)) return null;
        try
        {
            var testo = File.ReadAllText(percorso, Encoding.UTF8);
            if (string.IsNullOrWhiteSpace(testo)) throw new JsonException("file vuoto");
            return JsonSerializer.Deserialize<T>(testo, Json.OpzioniFile);
        }
        catch (JsonException)
        {
            MettiDaParte(percorso);
            return null;
        }
        catch (NotSupportedException)
        {
            MettiDaParte(percorso);
            return null;
        }
    }

    private static void MettiDaParte(string percorso)
    {
        try
        {
            File.Move(percorso, percorso + ".guasto", overwrite: true);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
