using System.Globalization;
using System.Windows.Forms;
using Pactum.Interfaccia;
using Pactum.Motore;
using Pactum.Sistema;

namespace Pactum;

/// <summary>Le opzioni della riga di comando. Il figlio non ne usa nessuna: servono all'avvio automatico e alle prove.</summary>
public sealed class Opzioni
{
    /// <summary>Avviato da Windows al login: la finestra non si apre da sola.</summary>
    public bool Avvio { get; private set; }

    /// <summary>Apre comunque la finestra.</summary>
    public bool Apri { get; private set; }

    /// <summary>Niente voce di avvio al login (prove e sviluppo). Il programma non si copia mai: gira dov'è.</summary>
    public bool NonInstallare { get; private set; }

    public string CartellaDati { get; private set; } = Percorsi.Predefinita;
    public bool CartellaDatiPersonale { get; private set; }
    public string? AbbinaServer { get; private set; }
    public string? AbbinaCodice { get; private set; }
    public string? FileAutoprova { get; private set; }
    public string? FileChiamateAutoprova { get; private set; }
    public bool EsciDopoAutoprova { get; private set; }
    public int? EsciDopoSecondi { get; private set; }
    public int? IntervalloReteSecondi { get; private set; }

    /// <summary>
    /// Solo per le prove sul PC di qualcuno: si registrano solo questi domini, gli altri
    /// valgono come "nessun sito" (la navigazione vera di chi usa il PC non finisce nei dati di prova).
    /// </summary>
    public IReadOnlySet<string>? SitiSolo { get; private set; }

    /// <summary>Solo per le prove: il processo la cui finestra vale come quella in primo piano.</summary>
    public int? ProvaPidFinestra { get; private set; }

    /// <summary>Apre da solo, dopo N secondi, la stessa conferma di "Chiudi Pactum" (collaudo del menu).</summary>
    public int? ProvaChiudiDopoSecondi { get; private set; }

    public static Opzioni Da(string[] args)
    {
        var o = new Opzioni();
        for (int i = 0; i < args.Length; i++)
        {
            string Prossimo() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"manca il valore di {args[i]}");
            switch (args[i])
            {
                case "--avvio": o.Avvio = true; break;
                case "--apri": o.Apri = true; break;
                case "--no-installa": o.NonInstallare = true; break;
                case "--dati":
                    o.CartellaDati = Path.GetFullPath(Prossimo());
                    o.CartellaDatiPersonale = true;
                    break;
                case "--abbina":
                    o.AbbinaServer = Prossimo();
                    o.AbbinaCodice = Prossimo();
                    break;
                case "--autoprova": o.FileAutoprova = Path.GetFullPath(Prossimo()); break;
                case "--autoprova-chiamate": o.FileChiamateAutoprova = Path.GetFullPath(Prossimo()); break;
                case "--esci-dopo-autoprova": o.EsciDopoAutoprova = true; break;
                case "--esci-dopo": o.EsciDopoSecondi = int.Parse(Prossimo(), CultureInfo.InvariantCulture); break;
                case "--siti-solo":
                    o.SitiSolo = Prossimo().Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToHashSet(StringComparer.Ordinal);
                    break;
                case "--prova-finestra": o.ProvaPidFinestra = int.Parse(Prossimo(), CultureInfo.InvariantCulture); break;
                case "--intervallo-rete": o.IntervalloReteSecondi = int.Parse(Prossimo(), CultureInfo.InvariantCulture); break;
                case "--prova-chiudi-dopo": o.ProvaChiudiDopoSecondi = int.Parse(Prossimo(), CultureInfo.InvariantCulture); break;
            }
        }
        return o;
    }

    /// <summary>Con una cartella dati diversa (prove) serve un'istanza diversa.</summary>
    public string SuffissoIstanza =>
        CartellaDatiPersonale ? "." + Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(CartellaDati.ToLowerInvariant())))[..12] : "";
}

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        Opzioni opzioni;
        try
        {
            opzioni = Opzioni.Da(args);
        }
        catch (Exception e) when (e is ArgumentException or FormatException)
        {
            MessageBox.Show(e.Message, "Pactum", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 2;
        }

        ApplicationConfiguration.Initialize();
        // Un errore imprevisto non deve fermare la misura: si scrive nel diario e si va avanti.
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => Log.Errore("eccezione non gestita nella finestra", e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Errore("eccezione non gestita", e.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            Log.Errore("eccezione in un compito", e.Exception);
            e.SetObserved();
        };
        var eseguibile = Environment.ProcessPath!;

        using var istanza = new Istanza(opzioni.SuffissoIstanza);

        if (!istanza.Prima)
        {
            istanza.ChiediApertura();
            return 0;
        }

        var cartellaExe = AppContext.BaseDirectory;
        Installazione.AssicuraUi(cartellaExe);
        if (!opzioni.NonInstallare && Installazione.ÈPacchetto)
        {
            try
            {
                Installazione.RegistraAvvio(eseguibile);
            }
            catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException or IOException)
            {
                Log.Errore("avvio al login non registrato", e);
            }
        }

        if (opzioni.FileChiamateAutoprova != null)
        {
            Autoprova.Aggiuntive = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(opzioni.FileChiamateAutoprova)) as System.Text.Json.Nodes.JsonArray;
        }

        using var motore = new Motore.Motore(new Percorsi(opzioni.CartellaDati));
        if (opzioni.IntervalloReteSecondi is int secondi) motore.IntervalloRete = TimeSpan.FromSeconds(Math.Max(10, secondi));
        motore.SitiSolo = opzioni.SitiSolo;
        motore.ProvaPidFinestra = opzioni.ProvaPidFinestra;
        if (opzioni.AbbinaServer != null)
        {
            var esito = motore.AbbinaAsync(opzioni.AbbinaServer, opzioni.AbbinaCodice).GetAwaiter().GetResult();
            Log.Info($"abbinamento da riga di comando: {Nucleo.Json.Testo(esito["errore"]) ?? "ok"}");
        }

        Application.Run(new ContestoPactum(motore, opzioni, istanza));
        return 0;
    }
}
