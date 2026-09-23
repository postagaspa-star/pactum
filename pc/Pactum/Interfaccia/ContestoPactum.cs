using System.Drawing;
using System.Net.NetworkInformation;
using System.Windows.Forms;
using Microsoft.Win32;
using Pactum.Nucleo;
using Pactum.Sistema;
using MotorePactum = Pactum.Motore.Motore;
using Log = Pactum.Motore.Log;

namespace Pactum.Interfaccia;

/// <summary>
/// Il programma quando gira: l'icona vicino all'orologio (sempre visibile, niente
/// di nascosto), il menu, i fumetti, la finestra quando serve e gli eventi di
/// Windows per il registro onesto.
/// </summary>
public sealed class ContestoPactum : ApplicationContext
{
    private static readonly TimeSpan IntervalloFumetti = TimeSpan.FromSeconds(7);

    private readonly MotorePactum motore;
    private readonly Ponte ponte;
    private readonly Opzioni opzioni;
    private readonly Istanza istanza;
    private readonly Icon immagine;
    private readonly NotifyIcon icona;
    private readonly Control invocatore;
    private readonly SentinellaSchermo schermo;
    private readonly Queue<(string Titolo, string Testo, string? Url)> fumetti = new();
    private readonly System.Windows.Forms.Timer timerFumetti;
    private FinestraPactum? finestra;
    private string? urlFumettoInMostra;
    private bool uscito;

    public ContestoPactum(MotorePactum motore, Opzioni opzioni, Istanza istanza)
    {
        this.motore = motore;
        this.opzioni = opzioni;
        this.istanza = istanza;
        ponte = new Ponte(motore);

        invocatore = new Control();
        invocatore.CreateControl();
        immagine = CaricaIcona();

        var menu = new ContextMenuStrip();
        var apri = new ToolStripMenuItem("Apri Pactum", null, (_, _) => Apri()) { Font = new Font(SystemFonts.MenuFont ?? Control.DefaultFont, FontStyle.Bold) };
        menu.Items.Add(apri);
        menu.Items.Add(new ToolStripMenuItem("Aggiorna adesso", null, async (_, _) => await AggiornaAdessoAsync()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Chiudi Pactum", null, async (_, _) => await ChiudiAsync()));

        icona = new NotifyIcon { Icon = immagine, Text = "Pactum", ContextMenuStrip = menu, Visible = true };
        icona.DoubleClick += (_, _) => Apri();
        icona.BalloonTipClicked += (_, _) => SuClicFumetto();

        timerFumetti = new System.Windows.Forms.Timer { Interval = (int)IntervalloFumetti.TotalMilliseconds };
        timerFumetti.Tick += (_, _) => ProssimoFumetto();

        motore.Fumetto += (titolo, testo) => SulFiloGrafico(() => AccodaFumetto(titolo, testo));
        motore.AvvisoAggiornamento += (titolo, testo, url) => SulFiloGrafico(() => AccodaFumetto(titolo, testo, url));
        schermo = new SentinellaSchermo(acceso => motore.SchermoAcceso(acceso));

        SystemEvents.PowerModeChanged += SuEnergia;
        SystemEvents.SessionEnding += SuFineSessione;
        SystemEvents.SessionEnded += SuSessioneFinita;
        SystemEvents.SessionSwitch += SuCambioSessione;
        SystemEvents.TimeChanged += SuCambioOra;
        NetworkChange.NetworkAvailabilityChanged += SuRete;

        istanza.RichiestaApertura += () => SulFiloGrafico(Apri);
        istanza.RichiestaUscita += () => SulFiloGrafico(() => Esci(Chiusure.Aggiornamento));
        istanza.Ascolta();

        motore.Avvia();

        if (!motore.Abbinato || !opzioni.Avvio || opzioni.Apri) Apri();
        if (opzioni.ProvaChiudiDopoSecondi is int dopo)
        {
            // Il collaudo senza clic: si esegue quello che fa il "Sì" della conferma di "Chiudi Pactum".
            var t = new System.Windows.Forms.Timer { Interval = dopo * 1000 };
            t.Tick += async (_, _) =>
            {
                t.Stop();
                Log.Info("chiusura confermata dal figlio (prova)");
                await Task.Run(motore.ChiudiVolontariamenteAsync);
                Esci(null);
            };
            t.Start();
        }
        if (opzioni.EsciDopoSecondi is int secondi)
        {
            var t = new System.Windows.Forms.Timer { Interval = secondi * 1000 };
            t.Tick += (_, _) =>
            {
                t.Stop();
                Esci("prova");
            };
            t.Start();
        }
    }

    public static Icon CaricaIcona()
    {
        using var flusso = typeof(ContestoPactum).Assembly.GetManifestResourceStream("Pactum.Risorse.pactum.ico");
        return flusso != null ? new Icon(flusso, SystemInformation.SmallIconSize) : SystemIcons.Application;
    }

    private void SulFiloGrafico(Action azione)
    {
        if (uscito || invocatore.IsDisposed) return;
        try
        {
            invocatore.BeginInvoke(azione);
        }
        catch (InvalidOperationException)
        {
        }
    }

    private void Apri()
    {
        if (uscito) return;
        if (finestra == null || finestra.IsDisposed)
        {
            var cartellaUi = Path.Combine(AppContext.BaseDirectory, "ui");
            finestra = new FinestraPactum(ponte, cartellaUi, Path.Combine(opzioni.CartellaDati, "WebView2"), CaricaIconaGrande(), opzioni.FileAutoprova);
            if (opzioni.FileAutoprova != null && opzioni.EsciDopoAutoprova)
            {
                finestra.AutoprovaFinita += () => SulFiloGrafico(() => Esci("prova"));
            }
            finestra.FormClosed += (_, _) => finestra = null;
            if (opzioni.FileAutoprova != null) finestra.WindowState = FormWindowState.Minimized;
            finestra.Show();
            if (opzioni.FileAutoprova != null) return;
        }
        if (finestra.WindowState == FormWindowState.Minimized) finestra.WindowState = FormWindowState.Normal;
        finestra.Activate();
    }

    private static Icon CaricaIconaGrande()
    {
        using var flusso = typeof(ContestoPactum).Assembly.GetManifestResourceStream("Pactum.Risorse.pactum.ico");
        return flusso != null ? new Icon(flusso) : SystemIcons.Application;
    }

    private async Task AggiornaAdessoAsync()
    {
        bool ok = await Task.Run(() => motore.SincronizzaAsync("menu"));
        AccodaFumetto("Pactum", ok ? "Aggiornato adesso." : "Il server non ha risposto: riprovo più tardi da solo.");
    }

    private async Task ChiudiAsync()
    {
        var scelta = MessageBox.Show(
            "Se chiudi, tuo padre vedrà un'interruzione nella registrazione.\n\nChiudere Pactum?",
            "Chiudi Pactum",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning,
            MessageBoxDefaultButton.Button2);
        if (scelta != DialogResult.Yes)
        {
            Log.Info("chiusura annullata dal figlio");
            return;
        }
        Log.Info("chiusura confermata dal figlio");
        icona.Text = "Pactum si sta chiudendo…";
        await Task.Run(motore.ChiudiVolontariamenteAsync);
        Esci(null);
    }

    /// <summary>Uscita. Con <paramref name="chiusura"/> il motore viene fermato qui; null se l'ha già fatto chi chiama.</summary>
    private void Esci(string? chiusura)
    {
        if (uscito) return;
        uscito = true;
        if (chiusura != null) motore.Ferma(chiusura);
        SystemEvents.PowerModeChanged -= SuEnergia;
        SystemEvents.SessionEnding -= SuFineSessione;
        SystemEvents.SessionEnded -= SuSessioneFinita;
        SystemEvents.SessionSwitch -= SuCambioSessione;
        SystemEvents.TimeChanged -= SuCambioOra;
        NetworkChange.NetworkAvailabilityChanged -= SuRete;
        timerFumetti.Stop();
        finestra?.Close();
        icona.Visible = false;
        icona.Dispose();
        schermo.Dispose();
        motore.Dispose();
        ExitThread();
    }

    private void AccodaFumetto(string titolo, string testo, string? url = null)
    {
        fumetti.Enqueue((Taglia(titolo, 63), Taglia(testo, 255), url));
        if (!timerFumetti.Enabled)
        {
            ProssimoFumetto();
            timerFumetti.Start();
        }
    }

    private void ProssimoFumetto()
    {
        if (fumetti.Count == 0)
        {
            timerFumetti.Stop();
            urlFumettoInMostra = null;
            return;
        }
        var (titolo, testo, url) = fumetti.Dequeue();
        urlFumettoInMostra = url;
        // Nel diario solo il titolo: il testo può nominare un sito o un programma.
        Log.Info($"fumetto: {titolo}");
        icona.ShowBalloonTip((int)IntervalloFumetti.TotalMilliseconds, titolo, testo, ToolTipIcon.Info);
    }

    /// <summary>Clic sul fumetto: l'avviso di una versione nuova apre la pagina di download, gli altri aprono Pactum.</summary>
    private void SuClicFumetto()
    {
        var url = urlFumettoInMostra;
        if (url != null) ApriPagina(url);
        else Apri();
    }

    private static void ApriPagina(string url)
    {
        if (!Ponte.ApribileFuori(url)) return;
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true })?.Dispose();
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException or ObjectDisposedException)
        {
            Log.Errore("pagina di download non aperta", e);
        }
    }

    private static string Taglia(string s, int massimo) => s.Length <= massimo ? s : s[..(massimo - 1)] + "…";

    // ---------- Eventi di Windows ----------

    private void SuEnergia(object? mittente, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Suspend) motore.Sospensione();
        else if (e.Mode == PowerModes.Resume) motore.Ripresa();
    }

    private void SuFineSessione(object? mittente, SessionEndingEventArgs e)
    {
        motore.FineSessione(spegnimento: e.Reason == SessionEndReasons.SystemShutdown);
    }

    private void SuSessioneFinita(object? mittente, SessionEndedEventArgs e)
    {
        Log.Info("sessione finita");
        Esci(e.Reason == SessionEndReasons.SystemShutdown ? Chiusure.Spegnimento : Chiusure.Disconnessione);
    }

    private void SuCambioSessione(object? mittente, SessionSwitchEventArgs e)
    {
        switch (e.Reason)
        {
            case SessionSwitchReason.SessionLock:
            case SessionSwitchReason.ConsoleDisconnect:
            case SessionSwitchReason.RemoteDisconnect:
                motore.SchermoBloccato(true);
                break;
            case SessionSwitchReason.SessionUnlock:
            case SessionSwitchReason.SessionLogon:
                motore.SchermoBloccato(false);
                break;
        }
    }

    private void SuCambioOra(object? mittente, EventArgs e) => TimeZoneInfo.ClearCachedData();

    private void SuRete(object? mittente, NetworkAvailabilityEventArgs e)
    {
        if (e.IsAvailable) _ = Task.Run(() => motore.SincronizzaAsync("rete tornata"));
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            timerFumetti.Dispose();
            invocatore.Dispose();
            immagine.Dispose();
        }
        base.Dispose(disposing);
    }
}
