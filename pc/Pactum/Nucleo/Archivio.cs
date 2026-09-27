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

    /// <summary>
    /// Vero quando il sistema ha vietato a questo programma di rinominare i suoi
    /// file (visto sul PC di casa il 27/09/2026: il <c>.tmp</c> si scrive, la
    /// rinomina riceve "accesso negato" a ogni tentativo, mentre PowerShell nella
    /// stessa cartella rinomina senza problemi: è un filtro sul programma, non sui
    /// permessi). Da lì in poi si scrive direttamente sul file vero: si perde
    /// l'atomicità, ma il dato arriva su disco invece di andare perso.
    /// </summary>
    internal static bool RinominaVietata { get; set; }

    /// <summary>La rinomina sopra il file vecchio; i test la sostituiscono.</summary>
    internal static Action<string, string> Sposta { get; set; } = (da, a) => File.Move(da, a, overwrite: true);

    public static void ScriviByte(string percorso, byte[] dati)
    {
        var cartella = Path.GetDirectoryName(percorso);
        if (!string.IsNullOrEmpty(cartella)) Directory.CreateDirectory(cartella);
        if (!RinominaVietata)
        {
            var temporaneo = percorso + ".tmp";
            ScriviSubito(temporaneo, dati);
            // L'antivirus a volte tiene aperto il file appena scritto per un attimo: si riprova.
            for (int tentativo = 1; ; tentativo++)
            {
                try
                {
                    Sposta(temporaneo, percorso);
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
                catch (UnauthorizedAccessException)
                {
                    RinominaVietata = true;
                    break;
                }
            }
            try
            {
                File.Delete(temporaneo);
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
        ScriviSubito(percorso, dati);
    }

    private static void ScriviSubito(string percorso, byte[] dati)
    {
        using var fs = new FileStream(percorso, FileMode.Create, FileAccess.Write, FileShare.None);
        fs.Write(dati, 0, dati.Length);
        fs.Flush(flushToDisk: true);
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
