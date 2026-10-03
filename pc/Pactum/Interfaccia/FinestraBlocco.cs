using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Interfaccia;

/// <summary>
/// (0.13, contratto v3.6) Il blocco delle faccende: quando ci sono faccende da fare, una finestra senza
/// bordi sempre in primo piano copre OGNI schermo. Dice "Prima le faccende", l'elenco con chi le ha date,
/// e che si sblocca da solo quando dal telefono è arrivata la foto di ogni faccenda.
///
/// È la prima cosa di Pactum che blocca, ed è una decisione di Andrea per la sua famiglia. Si tiene onesta:
/// <list type="bullet">
/// <item>non chiude, non minimizza e non tocca le altre app — le copre e basta (niente lavoro perso);</item>
/// <item>non si chiude a mano (Alt+F4 e simili ignorati), non si sposta, torna davanti se qualcosa la
/// scavalca (<see cref="GestoreBlocco"/> la rimette in cima spesso), copre la barra delle applicazioni;</item>
/// <item>si toglie solo quando il server dice che le faccende sono finite, oppure quando Windows si spegne o
/// l'utente esce (lo spegnimento non si blocca mai);</item>
/// <item>niente trucchi da virus: nessun hook di tastiera, niente registro, Task Manager o impostazioni di
/// sicurezza toccate. Chi la vuole chiudere con il Task Manager ci riesce: resta nel registro, come sempre.</item>
/// </list>
/// È fatta in WinForms (come l'avviso): si disegna subito, anche senza rete e senza WebView2.
/// </summary>
public sealed class FinestraBlocco : Form
{
    private readonly IReadOnlyList<Faccenda> faccende;
    private readonly bool contrasto = SystemInformation.HighContrast;
    private readonly bool perImmagine;
    private readonly Rectangle schermoBounds;
    private readonly FoglioBlocco foglio;
    private readonly Panel vista;
    private Aspetto? aspetto;
    private bool consentiChiusura;

    public FinestraBlocco(Rectangle schermo, IReadOnlyList<Faccenda> faccende, Icon? icona)
        : this(schermo, faccende, icona, perImmagine: false)
    {
    }

    private FinestraBlocco(Rectangle schermo, IReadOnlyList<Faccenda> faccende, Icon? icona, bool perImmagine)
    {
        this.faccende = faccende;
        this.perImmagine = perImmagine;
        schermoBounds = schermo;
        SuspendLayout();
        Text = "Pactum";
        if (icona != null) Icon = icona;
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        Bounds = schermo;
        AutoScaleMode = AutoScaleMode.None;
        // Sopra tutto (anche la barra delle applicazioni), ma nell'elenco delle finestre come le altre.
        TopMost = !perImmagine;
        ShowInTaskbar = false;
        KeyPreview = true;
        DoubleBuffered = true;
        ControlBox = false;
        MinimizeBox = false;
        MaximizeBox = false;

        foglio = new FoglioBlocco();
        vista = new Panel { AutoScroll = true, TabStop = false };
        vista.Controls.Add(foglio);
        Controls.Add(vista);
        ResumeLayout(false);
        Aggiorna();
    }

    /// <summary>
    /// (0.13) La copertura prende il primo piano quando compare, così la tastiera va a lei e si vede anche
    /// sopra un gioco a schermo intero esclusivo. Non è più "senza attivazione": era quello il difetto.
    /// </summary>
    protected override bool ShowWithoutActivation => false;

    /// <summary>Permette la chiusura programmata (il blocco è finito, o il programma esce): poi si può chiudere.</summary>
    public void ConsentiChiusura() => consentiChiusura = true;

    /// <summary>
    /// (0.13) Prende il primo piano: quando la copertura compare, e quando in primo piano è tornata a schermo
    /// intero un'altra app su uno schermo coperto. Best effort (Windows limita chi può rubare il primo piano).
    /// </summary>
    public void Attiva()
    {
        if (IsDisposed || perImmagine) return;
        RiportaInCima();
        try
        {
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
        }
        catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
        {
        }
    }

    /// <summary>La finestra resta ferma e grande come il suo schermo: ogni tentativo di spostarla o ridimensionarla si annulla.</summary>
    protected override void WndProc(ref Message m)
    {
        if (!perImmagine && m.Msg == Win32.WM_WINDOWPOSCHANGING && m.LParam != IntPtr.Zero)
        {
            var pos = Marshal.PtrToStructure<Win32.WINDOWPOS>(m.LParam);
            pos.x = schermoBounds.X;
            pos.y = schermoBounds.Y;
            pos.cx = schermoBounds.Width;
            pos.cy = schermoBounds.Height;
            pos.flags &= ~(Win32.SWP_NOMOVE | Win32.SWP_NOSIZE);
            Marshal.StructureToPtr(pos, m.LParam, false);
        }
        base.WndProc(ref m);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Non si chiude a mano (Alt+F4, menu di sistema). Lo spegnimento e il Task Manager non si bloccano mai.
        if (!consentiChiusura && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            return;
        }
        base.OnFormClosing(e);
    }

    /// <summary>Di nuovo sopra tutto, senza rubare la tastiera; se qualcosa l'ha minimizzata, torna com'era.</summary>
    public void RiportaInCima()
    {
        if (IsDisposed || perImmagine) return;
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        if (IsHandleCreated)
        {
            Win32.SetWindowPos(Handle, Win32.HWND_TOPMOST, 0, 0, 0, 0, Win32.SWP_NOACTIVATE | Win32.SWP_NOMOVE | Win32.SWP_NOSIZE);
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Aggiorna();
    }

    protected override void OnDpiChanged(DpiChangedEventArgs e)
    {
        e.Cancel = true;
        base.OnDpiChanged(e);
        Aggiorna();
    }

    private void Aggiorna()
    {
        float scala = DeviceDpi / 96f;
        var vecchio = aspetto;
        if (vecchio == null || Math.Abs(vecchio.Scala - scala) > 0.001f) aspetto = new Aspetto(scala, contrasto);
        var a = aspetto!;
        BackColor = a.Sfondo;
        vista.BackColor = a.Sfondo;
        foglio.Imposta(faccende, a);
        if (!ReferenceEquals(vecchio, a)) vecchio?.Dispose();
        Disponi();
        Invalidate(true);
    }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        Disponi();
    }

    /// <summary>La colonna al centro dello schermo; se le faccende non ci stanno, scorre solo il contenuto.</summary>
    private void Disponi()
    {
        if (aspetto == null) return;
        var d = DisposizioneBlocco.Calcola(ClientSize, aspetto.Scala, foglio.AltezzaPer);
        int contenuto = foglio.AltezzaPer(d.LarghezzaColonna);
        bool scorre = contenuto > d.Colonna.Height;
        int larghezzaVista = d.LarghezzaColonna + (scorre ? SystemInformation.VerticalScrollBarWidth : 0);
        vista.Bounds = new Rectangle(d.Colonna.X, d.Colonna.Y, Math.Min(larghezzaVista, Math.Max(1, ClientSize.Width - d.Colonna.X)), d.Colonna.Height);
        foglio.Bounds = new Rectangle(0, 0, d.LarghezzaColonna, contenuto);
    }

    /// <summary>
    /// Solo per i test e le prove: la finestra disegnata in un'immagine grande come lo schermo dato,
    /// senza mai aprirla sullo schermo di chi usa il PC.
    /// </summary>
    public static void DisegnaImmagine(Rectangle schermo, IReadOnlyList<Faccenda> faccende, string file)
    {
        using var f = PerImmagine(schermo, faccende);
        using var immagine = f.Disegna();
        immagine.Save(file, ImageFormat.Png);
    }

    internal static FinestraBlocco PerImmagine(Rectangle schermo, IReadOnlyList<Faccenda> faccende)
    {
        var f = new FinestraBlocco(schermo, faccende, null, perImmagine: true);
        CreaHandle(f);
        f.Aggiorna();
        return f;
    }

    private static void CreaHandle(Control c)
    {
        _ = c.Handle;
        foreach (Control figlio in c.Controls) CreaHandle(figlio);
    }

    internal Bitmap Disegna()
    {
        var immagine = new Bitmap(Math.Max(1, Width), Math.Max(1, Height));
        DrawToBitmap(immagine, new Rectangle(Point.Empty, Size));
        return immagine;
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing)
        {
            aspetto?.Dispose();
            aspetto = null;
        }
    }
}

/// <summary>
/// (0.13) Tiene una finestra di blocco per ogni schermo (<see cref="Screen.AllScreens"/>), le rimette in
/// cima spesso (~mezzo secondo) e le rifà se gli schermi cambiano. Non tocca le altre app: le copre e basta.
/// </summary>
public sealed class GestoreBlocco : IDisposable
{
    private readonly List<FinestraBlocco> finestre = new();
    private readonly System.Windows.Forms.Timer inCima;
    private IReadOnlyList<Faccenda> faccende = Array.Empty<Faccenda>();

    public GestoreBlocco()
    {
        inCima = new System.Windows.Forms.Timer { Interval = 500 };
        inCima.Tick += (_, _) => InCima();
        SystemEvents.DisplaySettingsChanged += SuSchermiCambiati;
    }

    public bool Coperto { get; private set; }

    /// <summary>Copre tutti gli schermi con l'elenco delle faccende (o aggiorna l'elenco se era già coperto).</summary>
    public void Mostra(IReadOnlyList<Faccenda> nuove)
    {
        faccende = nuove;
        Coperto = true;
        Ricostruisci();
        inCima.Start();
    }

    /// <summary>Toglie la copertura: il blocco è finito (o il programma sta uscendo).</summary>
    public void Nascondi()
    {
        Coperto = false;
        inCima.Stop();
        ChiudiFinestre();
    }

    /// <summary>
    /// Ogni mezzo secondo: le coperture tornano sopra tutto. Se una copertura è sparita (un errore in
    /// creazione), si riprova. E se in primo piano c'è a schermo intero un'altra app su uno schermo coperto,
    /// una copertura prende il primo piano — senza combattere il menu Start o la barra (non sono a schermo intero).
    /// </summary>
    private void InCima()
    {
        if (!Coperto) return;
        // Copertura parziale (un monitor senza finestra): riprova a coprire tutto.
        if (finestre.Count < Screen.AllScreens.Length)
        {
            Ricostruisci();
            return;
        }
        foreach (var f in finestre) f.RiportaInCima();

        var primoPiano = Win32.GetForegroundWindow();
        if (primoPiano == IntPtr.Zero) return;
        if (finestre.Any(f => f.IsHandleCreated && f.Handle == primoPiano)) return; // già una copertura
        if (!PrimoPiano.ÈaSchermoIntero(primoPiano)) return; // Start, barra, finestra normale: lasciali stare
        var schermo = Screen.FromHandle(primoPiano);
        var copertura = finestre.FirstOrDefault(f => f.IsHandleCreated && Screen.FromHandle(f.Handle).DeviceName == schermo.DeviceName)
                        ?? finestre.FirstOrDefault();
        copertura?.Attiva();
    }

    private void Ricostruisci()
    {
        ChiudiFinestre();
        bool tutte = true;
        foreach (var schermo in Screen.AllScreens)
        {
            try
            {
                var f = new FinestraBlocco(schermo.Bounds, faccende, null);
                finestre.Add(f);
                f.Show();
                f.RiportaInCima();
            }
            catch (Exception e)
            {
                // Una copertura non aperta: non si lascia uno schermo scoperto in silenzio. Il giro di
                // InCima riproverà (finestre.Count < schermi) finché non ci riesce.
                tutte = false;
                Pactum.Motore.Log.Errore("copertura di uno schermo non aperta", e);
            }
        }
        // La prima copertura prende il primo piano: la tastiera va a lei, non all'app coperta.
        if (finestre.Count > 0)
        {
            try
            {
                finestre[0].Attiva();
            }
            catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
            {
            }
        }
        if (!tutte) Pactum.Motore.Log.Avviso("copertura del blocco incompleta: riprovo al prossimo giro");
    }

    private void ChiudiFinestre()
    {
        foreach (var f in finestre)
        {
            try
            {
                f.ConsentiChiusura();
                f.Close();
                f.Dispose();
            }
            catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
            {
            }
        }
        finestre.Clear();
    }

    private void SuSchermiCambiati(object? mittente, EventArgs e)
    {
        try
        {
            if (Coperto) Ricostruisci();
        }
        catch (Exception ex)
        {
            Pactum.Motore.Log.Errore("coperture non rifatte dopo il cambio schermi", ex);
        }
    }

    public void Dispose()
    {
        SystemEvents.DisplaySettingsChanged -= SuSchermiCambiati;
        inCima.Stop();
        inCima.Dispose();
        ChiudiFinestre();
    }
}

/// <summary>
/// La disposizione del contenuto del blocco, logica pura (provata nei test): una colonna al centro dello
/// schermo, larga come la schermata "Collega" (540 px), che scorre solo il contenuto se le faccende sono tante.
/// </summary>
internal readonly record struct DisposizioneBlocco(Rectangle Colonna, int LarghezzaColonna)
{
    public static DisposizioneBlocco Calcola(Size area, float scala, Func<int, int> altezzaContenuto)
    {
        int Px(float px) => (int)Math.Round(px * scala);
        int margine = Px(40);
        int larghezza = Math.Max(Math.Min(Px(200), area.Width), Math.Min(Px(540), area.Width - 2 * margine));
        int x = Math.Max(0, (area.Width - larghezza) / 2);
        int disponibile = Math.Max(0, area.Height - 2 * margine);
        int h = altezzaContenuto(larghezza);
        int y = h >= disponibile ? margine : margine + (disponibile - h) / 2;
        int altezzaVista = Math.Min(h, disponibile);
        return new DisposizioneBlocco(new Rectangle(x, y, larghezza, altezzaVista), larghezza);
    }
}

/// <summary>
/// Il contenuto del blocco, disegnato come l'interfaccia (<see cref="Aspetto"/>): il marchio, il titolo
/// "Prima le faccende", una scheda per faccenda (titolo, chi l'ha data, nota), e la frase di come ci si
/// sblocca. Una sola funzione misura e disegna, così misure e disegno non si separano mai.
/// </summary>
internal sealed class FoglioBlocco : Control
{
    private const TextFormatFlags Paragrafo =
        TextFormatFlags.WordBreak | TextFormatFlags.TextBoxControl | TextFormatFlags.NoPrefix | TextFormatFlags.NoPadding;

    private IReadOnlyList<Faccenda> faccende = Array.Empty<Faccenda>();
    private Aspetto? aspetto;

    public FoglioBlocco()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        SetStyle(ControlStyles.Selectable, false);
        TabStop = false;
        AccessibleRole = AccessibleRole.StaticText;
    }

    public void Imposta(IReadOnlyList<Faccenda> nuove, Aspetto nuovo)
    {
        faccende = nuove;
        aspetto = nuovo;
        BackColor = nuovo.Sfondo;
        // Per chi usa un lettore di schermo: tutto il blocco, con le stesse parole.
        AccessibleName = Testi.TestoBlocco(nuove);
        Invalidate();
    }

    public int AltezzaPer(int larghezza) => aspetto == null ? 0 : Componi(null, larghezza);

    protected override void OnPaint(PaintEventArgs e)
    {
        if (aspetto == null) return;
        e.Graphics.Clear(aspetto.Sfondo);
        Componi(e.Graphics, Width);
    }

    private int Componi(Graphics? g, int larghezza)
    {
        var a = aspetto!;
        int y = 0;
        y += Testo(g, "Pactum", a.CarattereMarchio, a.Marchio, 0, y, larghezza);
        y += a.Px(16);
        y += Testo(g, Testi.TitoloBlocco, a.CarattereTitolo, a.Testo, 0, y, larghezza);
        y += a.Px(20);
        for (int i = 0; i < faccende.Count; i++)
        {
            if (i > 0) y += a.Px(12);
            y += Scheda(g, faccende[i], y, larghezza);
        }
        y += a.Px(20);
        y += Testo(g, Testi.SottoBlocco, a.CarattereFrase, a.TestoSecondario, 0, y, larghezza);
        return y;
    }

    private int Scheda(Graphics? g, Faccenda f, int y, int larghezza)
    {
        var a = aspetto!;
        int dentroX = a.Px(20);
        int dentroY = a.Px(18);
        int larghezzaDentro = Math.Max(1, larghezza - 2 * dentroX);
        int altezza = Contenuto(null, f, 0, 0, larghezzaDentro) + 2 * dentroY;
        if (g != null)
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float mezza = a.Linea / 2;
            var forma = new RectangleF(mezza, y + mezza, larghezza - a.Linea, altezza - a.Linea);
            using var percorso = Aspetto.Arrotondato(forma, a.Px(14));
            using var fondo = new SolidBrush(a.Scheda);
            using var bordo = new Pen(a.BordoScheda, a.Linea);
            g.FillPath(fondo, percorso);
            g.DrawPath(bordo, percorso);
            Contenuto(g, f, dentroX, y + dentroY, larghezzaDentro);
        }
        return altezza;
    }

    private int Contenuto(Graphics? g, Faccenda f, int x, int y, int larghezza)
    {
        var a = aspetto!;
        int inizio = y;
        y += Testo(g, f.Titolo, a.CarattereNome, a.Testo, x, y, larghezza);
        var chi = Testi.DaChi(f.DataDa);
        if (chi.Length > 0)
        {
            y += a.Px(6);
            y += Testo(g, chi, a.CarattereSecondario, a.TestoSecondario, x, y, larghezza);
        }
        if (!string.IsNullOrWhiteSpace(f.Nota))
        {
            y += a.Px(6);
            y += Testo(g, f.Nota!.Trim(), a.CarattereSecondario, a.TestoSecondario, x, y, larghezza);
        }
        return y - inizio;
    }

    private static int Testo(Graphics? g, string testo, Font carattere, Color colore, int x, int y, int larghezza)
    {
        var misura = TextRenderer.MeasureText(testo, carattere, new Size(Math.Max(1, larghezza), int.MaxValue), Paragrafo);
        if (g != null) TextRenderer.DrawText(g, testo, carattere, new Rectangle(x, y, larghezza, misura.Height), colore, Paragrafo);
        return misura.Height;
    }
}
