using Microsoft.Win32;

namespace Pactum.Sistema;

/// <summary>
/// Installazione per utente, senza amministratore, e senza copiare niente: il
/// programma gira dalla cartella dove il figlio ha scompattato lo zip e vi resta.
/// L'unica cosa che scrive è la voce di avvio al login in <c>HKCU\...\Run</c>, che
/// punta proprio a quella cartella. Niente di nascosto: il nome nella chiave è "Pactum".
/// </summary>
public static class Installazione
{
    public const string NomeAvvio = "Pactum";
    private const string ChiaveRun = @"Software\Microsoft\Windows\CurrentVersion\Run";

    /// <summary>
    /// true quando gira dal pacchetto pubblicato (self-contained): accanto all'exe c'è il
    /// runtime .NET (<c>coreclr.dll</c>). Una build di sviluppo e i test non ce l'hanno, e non
    /// devono registrare l'avvio al login né estrarre l'interfaccia.
    /// </summary>
    public static bool ÈPacchetto =>
        File.Exists(Path.Combine(AppContext.BaseDirectory, "coreclr.dll"));

    /// <summary>Se accanto all'exe manca l'interfaccia (un file cancellato per sbaglio), la tira fuori da quella incorporata.</summary>
    public static void AssicuraUi(string cartellaExe)
    {
        var ui = Path.Combine(cartellaExe, "ui");
        if (!File.Exists(Path.Combine(ui, "index.html"))) EstraiUi(ui);
    }

    private static void EstraiUi(string destinazione)
    {
        var assembly = typeof(Installazione).Assembly;
        foreach (var nome in assembly.GetManifestResourceNames().Where(n => n.StartsWith("ui/", StringComparison.Ordinal)))
        {
            var relativo = nome["ui/".Length..].Replace('\\', Path.DirectorySeparatorChar).Replace('/', Path.DirectorySeparatorChar);
            if (relativo.Contains("..", StringComparison.Ordinal)) continue;
            var file = Path.Combine(destinazione, relativo);
            Directory.CreateDirectory(Path.GetDirectoryName(file)!);
            using var sorgente = assembly.GetManifestResourceStream(nome)!;
            using var uscita = File.Create(file);
            sorgente.CopyTo(uscita);
        }
    }

    /// <summary>
    /// Scrive (o corregge) la voce di avvio al login: punta all'exe dov'è adesso, così il programma
    /// riparte dalla cartella dove il figlio l'ha messo. Solo HKCU: nessun permesso speciale.
    /// </summary>
    public static void RegistraAvvio(string eseguibile)
    {
        var valore = $"\"{eseguibile}\" --avvio";
        using var chiave = Registry.CurrentUser.CreateSubKey(ChiaveRun);
        if (!string.Equals(chiave.GetValue(NomeAvvio) as string, valore, StringComparison.OrdinalIgnoreCase))
        {
            chiave.SetValue(NomeAvvio, valore, RegistryValueKind.String);
        }
    }

    public static string? VoceAvvio()
    {
        using var chiave = Registry.CurrentUser.OpenSubKey(ChiaveRun);
        return chiave?.GetValue(NomeAvvio) as string;
    }
}
