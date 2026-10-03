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
    private readonly GestoreBlocco gestoreBlocco = new();
    private readonly Queue<(string Titolo, string Testo, string? Url, string? Sezione)> fumetti = new();
    private readonly System.Windows.Forms.Timer timerFumetti;
    private readonly ToolStripMenuItem chiudiMenu;
    private readonly ToolStripSeparator separatoreChiudi;
    private FinestraPactum? finestra;
    private FinestraAvviso? finestraAvviso;
    private string? urlFumettoInMostra;
    private string? sezioneFumettoInMostra;
    private bool uscito;

    // Una domanda del programma è aperta ("Chiudi Pactum?"): l'avviso a tutto schermo non le va sopra, aspetta.
    private bool dialogoAperto;
    private readonly List<Avviso> avvisiInAttesa = new();

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
        // (0.13) Durante un blocco delle faccende "Chiudi Pactum" non c'è: non si offre un modo pulito di
        // chiudere il programma proprio mentre deve coprire. Il Task Manager resta (e lascia traccia).
        separatoreChiudi = new ToolStripSeparator();
        chiudiMenu = new ToolStripMenuItem("Chiudi Pactum", null, async (_, _) => await ChiudiAsync());
        menu.Items.Add(separatoreChiudi);
        menu.Items.Add(chiudiMenu);

        icona = new NotifyIcon { Icon = immagine, Text = "Pactum", ContextMenuStrip = menu, Visible = true };
        icona.DoubleClick += (_, _) => Apri();
        icona.BalloonTipClicked += (_, _) => SuClicFumetto();

        timerFumetti = new System.Windows.Forms.Timer { Interval = (int)IntervalloFumetti.TotalMilliseconds };
        timerFumetti.Tick += (_, _) => ProssimoFumetto();

        motore.Fumetto += (titolo, testo, sezione) => SulFiloGrafico(() => AccodaFumetto(titolo, testo, null, sezione));
        motore.AvvisoTuttoSchermo += avvisi => SulFiloGrafico(() => MostraAvviso(avvisi));
        motore.AvvisoAggiornamento += (titolo, testo, url) => SulFiloGrafico(() => AccodaFumetto(titolo, testo, url));
        // (0.13) Il blocco delle faccende: copre o scopre gli schermi. Mai durante le prove su file.
        motore.CambioBlocco += vista => SulFiloGrafico(() => AggiornaBlocco(vista));
        schermo = new SentinellaSchermo(acceso => motore.SchermoAcceso(acceso));

        SystemEvents.PowerModeChanged += SuEnergia;
        SystemEvents.SessionEnding += SuFineSessione;
        SystemEvents.SessionEnded += SuSessioneFinita;
        SystemEvents.SessionSwitch += SuCambioSessione;
        SystemEvents.TimeChanged += SuCambioOra;
        NetworkChange.NetworkAvailabilityChanged += SuRete;

        istanza.RichiestaApertura += () => SulFiloGrafico(Apri);
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
                ChiudiAvviso();
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

    private void Apri() => Apri(null);

    /// <summary>
    /// Apre (o riporta davanti) la finestra di Pactum. (0.10) Con <paramref name="sezione"/> la porta su
    /// quella sezione: il clic sul fumetto di una proposta apre Proposte, come sul telefono.
    /// </summary>
    private void Apri(string? sezione)
    {
        if (uscito) return;
        // Chi apre Pactum (menu, doppio clic, fumetto, secondo avvio) ha visto l'avviso: si chiude,
        // come fa il suo pulsante "Apri Pactum", e la finestra del programma non gli finisce sotto.
        ChiudiAvviso();
        if (finestra != null && !finestra.IsDisposed && sezione != null) finestra.MostraSezione(sezione);
        if (finestra == null || finestra.IsDisposed)
        {
            var cartellaUi = Path.Combine(AppContext.BaseDirectory, "ui");
            finestra = new FinestraPactum(ponte, cartellaUi, Path.Combine(opzioni.CartellaDati, "WebView2"), CaricaIconaGrande(), opzioni.FileAutoprova, sezione);
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
        // (0.13) Durante un blocco delle faccende non si chiude da qui (la voce è nascosta): e comunque la
        // domanda di conferma non deve mai finire sotto la copertura, dove sembrerebbe un blocco.
        if (motore.Coperto) return;
        // Nessuna domanda sotto l'avviso a tutto schermo (che resta sopra tutto, e che la domanda
        // disabiliterebbe: sembrerebbe un blocco). L'avviso si chiude prima, e finché la domanda è
        // aperta quelli nuovi aspettano: se Pactum resta aperto, compaiono dopo.
        ChiudiAvviso();
        DialogResult scelta;
        dialogoAperto = true;
        try
        {
            scelta = MessageBox.Show(
                "Se chiudi, tuo padre vedrà un'interruzione nella registrazione.\n\nChiudere Pactum?",
                "Chiudi Pactum",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning,
                MessageBoxDefaultButton.Button2);
        }
        finally
        {
            dialogoAperto = false;
        }
        if (scelta != DialogResult.Yes)
        {
            Log.Info("chiusura annullata dal figlio");
            MostraAvvisiInAttesa();
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
        ChiudiAvviso();
        // Lo spegnimento non si blocca mai: le finestre del blocco si lasciano chiudere.
        gestoreBlocco.Dispose();
        icona.Visible = false;
        icona.Dispose();
        schermo.Dispose();
        motore.Dispose();
        ExitThread();
    }

    /// <summary>
    /// (0.9) Uno sforamento nuovo: oltre al fumetto si apre l'avviso a tutto schermo, sullo schermo
    /// della finestra in primo piano e sopra tutto. Se è già aperto, le regole nuove si aggiungono lì
    /// e l'avviso torna in cima. Mai sopra una domanda del programma. Si chiude sempre.
    /// </summary>
    private void MostraAvviso(IReadOnlyList<Avviso> avvisi)
    {
        if (uscito || avvisi.Count == 0) return;
        if (dialogoAperto)
        {
            // Una domanda del programma è aperta: l'avviso non le va sopra, aspetta che si chiuda.
            avvisiInAttesa.AddRange(avvisi);
            return;
        }
        if (opzioni.CartellaProvaAvvisi is string cartella)
        {
            SalvaAvvisoDiProva(cartella, avvisi);
            return;
        }
        Icon? iconaNuova = null;
        try
        {
            if (finestraAvviso == null || finestraAvviso.IsDisposed)
            {
                iconaNuova = CaricaIconaGrande();
                var nuova = new FinestraAvviso(avvisi, iconaNuova);
                iconaNuova = null; // ora è della finestra
                finestraAvviso = nuova;
                nuova.RichiestaApertura += () => SulFiloGrafico(Apri);
                nuova.FormClosed += (_, _) =>
                {
                    if (ReferenceEquals(finestraAvviso, nuova)) finestraAvviso = null;
                };
                // Windows può non darle la tastiera (un programma in sottofondo non ruba il primo piano):
                // resta comunque sopra tutto, e sotto i pulsanti c'è "Se i tasti non rispondono, fai clic qui".
                nuova.Show();
            }
            else
            {
                // Già aperto, magari coperto o ridotto a icona: le regole nuove si aggiungono e torna in cima.
                finestraAvviso.Aggiungi(avvisi);
            }
            Log.Info($"avviso a tutto schermo: {avvisi.Count} {(avvisi.Count == 1 ? "regola" : "regole")}");
        }
        catch (Exception e)
        {
            // Il fumetto è già partito e lo sforamento è in coda: manca solo la finestra.
            Log.Errore("avviso a tutto schermo non aperto", e);
            // Niente finestra a metà: una finestra rotta (magari invisibile) non deve ricevere gli avvisi dopo.
            var rotta = finestraAvviso;
            finestraAvviso = null;
            try
            {
                rotta?.Dispose();
                iconaNuova?.Dispose();
            }
            catch (Exception ex)
            {
                Log.Errore("avviso a tutto schermo non liberato", ex);
            }
        }
    }

    /// <summary>
    /// (0.13, contratto v3.6) Il blocco delle faccende: copre tutti gli schermi con l'elenco, o toglie la
    /// copertura quando il server dice che è finito. Nelle prove su file (<c>--prova-avvisi</c>) non copre
    /// lo schermo di chi sta usando il PC: lo scrive nel diario e basta.
    /// </summary>
    private void AggiornaBlocco(VistaBlocco _ignorato)
    {
        if (uscito) return;
        // (0.13) Non ci si fida dell'ordine degli eventi fra i fili: si rilegge lo stato attuale del motore.
        var vista = motore.VistaBloccoCorrente;
        // Durante un blocco "Chiudi Pactum" sparisce dal menu; torna quando il blocco finisce.
        chiudiMenu.Visible = !vista.Coperto;
        separatoreChiudi.Visible = !vista.Coperto;
        if (opzioni.CartellaProvaAvvisi != null)
        {
            Log.Info(vista.Coperto ? $"blocco delle faccende (prova): coprirebbe gli schermi, {vista.Faccende.Count} da fare" : "blocco delle faccende (prova): toglierebbe la copertura");
            return;
        }
        try
        {
            if (vista.Coperto) gestoreBlocco.Mostra(vista.Faccende);
            else gestoreBlocco.Nascondi();
        }
        catch (Exception e)
        {
            Log.Errore("blocco delle faccende non aggiornato", e);
        }
    }

    /// <summary>Chiude l'avviso a tutto schermo, se c'è (prima di aprire la finestra del programma o una domanda).</summary>
    private void ChiudiAvviso()
    {
        var aperta = finestraAvviso;
        finestraAvviso = null;
        if (aperta == null || aperta.IsDisposed) return;
        try
        {
            aperta.Close();
        }
        catch (Exception e)
        {
            Log.Errore("avviso a tutto schermo non chiuso", e);
            aperta.Dispose();
        }
    }

    /// <summary>Gli avvisi arrivati mentre una domanda era aperta: adesso che è chiusa, si mostrano.</summary>
    private void MostraAvvisiInAttesa()
    {
        if (avvisiInAttesa.Count == 0) return;
        var attesa = avvisiInAttesa.ToList();
        avvisiInAttesa.Clear();
        MostraAvviso(attesa);
    }

    /// <summary>Le prove (<c>--prova-avvisi</c>): l'avviso diventa un'immagine e un testo nella cartella, lo schermo resta libero.</summary>
    private static void SalvaAvvisoDiProva(string cartella, IReadOnlyList<Avviso> avvisi)
    {
        try
        {
            Directory.CreateDirectory(cartella);
            var nome = $"avviso-{DateTime.Now:HHmmss-fff}";
            Archivio.ScriviTesto(Path.Combine(cartella, nome + ".txt"), Testi.TestoAvviso(avvisi));
            FinestraAvviso.DisegnaImmagine(avvisi, Path.Combine(cartella, nome + ".png"));
            Log.Info($"avviso a tutto schermo (prova, su file): {avvisi.Count} {(avvisi.Count == 1 ? "regola" : "regole")}");
        }
        catch (Exception e)
        {
            Log.Errore("avviso di prova non salvato", e);
        }
    }

    private void AccodaFumetto(string titolo, string testo, string? url = null, string? sezione = null)
    {
        if (opzioni.CartellaProvaAvvisi is string cartella)
        {
            // Le prove non coprono lo schermo di chi usa il PC: il fumetto va in un file.
            try
            {
                Directory.CreateDirectory(cartella);
                var clic = sezione != null ? "[clic: " + sezione + "]" + Environment.NewLine : "";
                File.AppendAllText(Path.Combine(cartella, "fumetti.txt"), titolo + Environment.NewLine + testo + Environment.NewLine + clic + Environment.NewLine);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                Log.Errore("fumetto di prova non salvato", e);
            }
            Log.Info($"fumetto (prova, su file): {titolo}");
            return;
        }
        fumetti.Enqueue((Taglia(titolo, 63), Taglia(testo, 255), url, sezione));
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
            sezioneFumettoInMostra = null;
            return;
        }
        var (titolo, testo, url, sezione) = fumetti.Dequeue();
        urlFumettoInMostra = url;
        sezioneFumettoInMostra = sezione;
        // Nel diario solo il titolo: il testo può nominare un sito o un programma.
        Log.Info($"fumetto: {titolo}");
        icona.ShowBalloonTip((int)IntervalloFumetti.TotalMilliseconds, titolo, testo, ToolTipIcon.Info);
    }

    /// <summary>
    /// Clic sul fumetto: l'avviso di una versione nuova apre la pagina di download, gli altri aprono
    /// Pactum; (0.10) quelli delle proposte su Proposte, quello di un verdetto sul Diario.
    /// </summary>
    private void SuClicFumetto()
    {
        var url = urlFumettoInMostra;
        if (url != null) ApriPagina(url);
        else Apri(sezioneFumettoInMostra);
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
