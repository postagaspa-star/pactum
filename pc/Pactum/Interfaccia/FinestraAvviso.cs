using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Windows.Forms;
using Microsoft.Win32;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Interfaccia;

/// <summary>
/// (0.9) L'avviso a tutto schermo quando si va oltre una regola: si apre sullo schermo della finestra
/// in primo piano (se non c'è, sul principale), sopra tutto, e dice quale regola, quanto hai usato e
/// il limite che ti sei dato. NON è un blocco:
/// "Ho capito" (o Esc, o Alt+F4) chiude e si torna dov'eri; "Apri Pactum" chiude e apre la finestra
/// del programma. Come l'avviso del telefono: stesse parole, una scheda per regola.
///
/// È fatto in WinForms e non con una pagina nella WebView2, perché è la via più robusta per una
/// finestra che copre tutto lo schermo: compare subito (nessun motore del browser da avviare), non
/// dipende da WebView2, e i pulsanti ci sono sempre, anche se qualcosa va storto. Una pagina che
/// non carica lascerebbe uno schermo vuoto e senza pulsanti: sembrerebbe un blocco, cioè proprio
/// quello che Pactum non fa mai. L'aspetto è quello dell'interfaccia (<see cref="Aspetto"/>).
/// </summary>
public sealed class FinestraAvviso : Form
{
    private readonly List<Avviso> avvisi;
    private readonly bool contrasto = SystemInformation.HighContrast;
    private readonly Panel vista;
    private readonly FoglioAvviso foglio;
    private readonly BottonePactum hoCapito;
    private readonly BottonePactum apri;
    private readonly LinkLabel suggerimento;
    private readonly Icon? iconaPropria;
    private readonly bool perImmagine;
    private Aspetto? aspetto;
    private long prontaDalTick;

    /// <summary>
    /// Appena compare, per un attimo i pulsanti non rispondono: uno Spazio o un Invio premuto mentre
    /// si giocava o si scriveva non deve chiuderla prima che la si veda. Poi si chiude sempre.
    /// </summary>
    internal const int AttimoMs = 700;

    /// <param name="icona">L'icona della finestra: la finestra la tiene e la libera quando si chiude.</param>
    public FinestraAvviso(IEnumerable<Avviso> avvisi, Icon? icona) : this(avvisi, icona, perImmagine: false)
    {
    }

    private FinestraAvviso(IEnumerable<Avviso> avvisi, Icon? icona, bool perImmagine)
    {
        this.avvisi = Unisci(new List<Avviso>(), avvisi);
        this.perImmagine = perImmagine;
        SuspendLayout();
        Text = "Pactum";
        iconaPropria = icona;
        if (icona != null) Icon = icona;
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        Bounds = SchermoDiLavoro();
        AutoScaleMode = AutoScaleMode.None;
        // Sopra tutto, ma nell'elenco delle finestre come le altre: niente di nascosto.
        TopMost = !perImmagine;
        ShowInTaskbar = !perImmagine;
        KeyPreview = true;
        DoubleBuffered = true;

        foglio = new FoglioAvviso();
        vista = new Panel { AutoScroll = true, TabStop = false };
        vista.Controls.Add(foglio);
        hoCapito = new BottonePactum(Testi.HoCapito, primario: true) { TabIndex = 0 };
        apri = new BottonePactum(Testi.ApriPactum, primario: false) { TabIndex = 1 };
        hoCapito.Click += (_, _) =>
        {
            if (Pronta) Close();
        };
        apri.Click += (_, _) =>
        {
            if (!Pronta) return;
            Close();
            RichiestaApertura?.Invoke();
        };
        // Windows non dà la tastiera a una finestra che si apre mentre si usa un altro programma: un clic sì.
        suggerimento = new LinkLabel
        {
            Text = Testi.TastiNonRispondono,
            LinkArea = new LinkArea(Testi.TastiNonRispondono.IndexOf(Testi.FaiClicQui, StringComparison.Ordinal), Testi.FaiClicQui.Length),
            LinkBehavior = LinkBehavior.AlwaysUnderline,
            TextAlign = ContentAlignment.TopCenter,
            AutoSize = false,
            TabStop = false,
            UseMnemonic = false,
        };
        suggerimento.LinkClicked += (_, _) => PrendiLaTastiera();
        suggerimento.Click += (_, _) => PrendiLaTastiera();
        Controls.Add(vista);
        Controls.Add(hoCapito);
        Controls.Add(apri);
        Controls.Add(suggerimento);
        // Invio ed Esc chiudono, come "Ho capito".
        AcceptButton = hoCapito;
        CancelButton = hoCapito;
        ActiveControl = hoCapito;
        ResumeLayout(false);
        Aggiorna();
        // Un monitor staccato, una risoluzione cambiata: posizione e misure si rifanno.
        if (!perImmagine) SystemEvents.DisplaySettingsChanged += SuSchermiCambiati;
    }

    /// <summary>"Apri Pactum": l'avviso si è chiuso, adesso va aperta la finestra del programma.</summary>
    public event Action? RichiestaApertura;

    internal IReadOnlyList<Avviso> Avvisi => avvisi;

    private bool Pronta => Environment.TickCount64 >= prontaDalTick;

    /// <summary>
    /// Sforamenti nuovi mentre l'avviso è ancora aperto: si aggiungono qui (una scheda per regola), e
    /// l'avviso torna in vista se nel frattempo era stato coperto o ridotto a icona.
    /// </summary>
    public void Aggiungi(IEnumerable<Avviso> nuovi)
    {
        Unisci(avvisi, nuovi);
        prontaDalTick = Environment.TickCount64 + AttimoMs;
        Aggiorna();
        RiportaInCima();
    }

    /// <summary>Di nuovo com'era e sopra tutto, senza rubare la tastiera a chi sta usando un altro programma.</summary>
    public void RiportaInCima()
    {
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        if (IsHandleCreated)
        {
            Win32.SetWindowPos(Handle, Win32.HWND_TOPMOST, 0, 0, 0, 0, Win32.SWP_NOACTIVATE | Win32.SWP_NOMOVE | Win32.SWP_NOSIZE);
        }
    }

    /// <summary>"Fai clic qui": il clic ha già portato la finestra in primo piano, adesso Invio ed Esc arrivano a lei.</summary>
    private void PrendiLaTastiera()
    {
        Activate();
        hoCapito.Focus();
    }

    /// <summary>Una scheda per regola: una regola già presente prende i numeri nuovi.</summary>
    internal static List<Avviso> Unisci(List<Avviso> lista, IEnumerable<Avviso> nuovi)
    {
        foreach (var a in nuovi)
        {
            int i = lista.FindIndex(x => x.RegolaId == a.RegolaId);
            if (i >= 0) lista[i] = a;
            else lista.Add(a);
        }
        return lista;
    }

    /// <summary>
    /// Lo schermo dove si sta guardando: quello della finestra in primo piano. Se non c'è una
    /// finestra in primo piano, il monitor principale.
    /// </summary>
    internal static Rectangle SchermoDiLavoro()
    {
        var primoPiano = Win32.GetForegroundWindow();
        var schermo = primoPiano != IntPtr.Zero ? Screen.FromHandle(primoPiano) : Screen.PrimaryScreen;
        return (schermo ?? Screen.PrimaryScreen)?.Bounds ?? new Rectangle(0, 0, 1280, 800);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Solo adesso si sa la scala vera del monitor.
        Aggiorna();
    }

    protected override void OnDpiChanged(DpiChangedEventArgs e)
    {
        // Resta a tutto schermo sullo schermo dove si guarda: si rifanno caratteri e misure.
        e.Cancel = true;
        base.OnDpiChanged(e);
        Bounds = SchermoDiLavoro();
        Aggiorna();
    }

    private void SuSchermiCambiati(object? mittente, EventArgs e)
    {
        if (IsDisposed || !IsHandleCreated) return;
        if (InvokeRequired)
        {
            BeginInvoke(new Action(Riposiziona));
            return;
        }
        Riposiziona();
    }

    private void Riposiziona()
    {
        if (IsDisposed) return;
        Bounds = SchermoDiLavoro();
        Aggiorna();
    }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        Disponi();
    }

    protected override void OnShown(EventArgs e)
    {
        prontaDalTick = Environment.TickCount64 + AttimoMs;
        base.OnShown(e);
        hoCapito.Focus();
    }

    private void Aggiorna()
    {
        float scala = DeviceDpi / 96f;
        var vecchio = aspetto;
        if (vecchio == null || Math.Abs(vecchio.Scala - scala) > 0.001f) aspetto = new Aspetto(scala, contrasto);
        var a = aspetto!;
        BackColor = a.Sfondo;
        vista.BackColor = a.Sfondo;
        Text = "Pactum – " + Testi.TitoloAvviso(avvisi);
        foglio.Imposta(avvisi, a);
        hoCapito.Imposta(a);
        apri.Imposta(a);
        suggerimento.Font = a.CarattereAiuto;
        suggerimento.BackColor = a.Sfondo;
        suggerimento.ForeColor = a.TestoSecondario;
        suggerimento.LinkColor = a.Collegamento;
        suggerimento.ActiveLinkColor = a.Contrasto ? a.Collegamento : a.PrimarioSopra;
        suggerimento.VisitedLinkColor = a.Collegamento;
        // Il lettore di schermo legge l'avviso intero quando il fuoco arriva su "Ho capito".
        hoCapito.AccessibleDescription = Testi.TestoAvviso(avvisi);
        if (!ReferenceEquals(vecchio, a)) vecchio?.Dispose();
        Disponi();
        Invalidate(true);
    }

    private void Disponi()
    {
        if (aspetto == null) return;
        int altezzaSuggerimento = TextRenderer.MeasureText(Testi.TastiNonRispondono, aspetto.CarattereAiuto).Height;
        var d = Disposizione.Calcola(ClientSize, aspetto.Scala, foglio.AltezzaPer, SystemInformation.VerticalScrollBarWidth, altezzaSuggerimento);
        vista.Bounds = d.Vista;
        foglio.Bounds = d.Foglio;
        hoCapito.Bounds = d.HoCapito;
        apri.Bounds = d.Apri;
        suggerimento.Bounds = d.Suggerimento;
    }

    /// <summary>
    /// Solo per le prove (<c>--prova-avvisi</c>) e per i test: l'avviso disegnato in un'immagine
    /// grande come il monitor principale, senza aprirlo sullo schermo di chi sta usando il PC.
    /// </summary>
    public static void DisegnaImmagine(IReadOnlyList<Avviso> avvisi, string file)
    {
        using var f = PerImmagine(avvisi);
        using var immagine = f.Disegna();
        immagine.Save(file, ImageFormat.Png);
    }

    /// <summary>La finestra pronta ma mai mostrata: né sopra le altre, né nella barra delle applicazioni.</summary>
    internal static FinestraAvviso PerImmagine(IReadOnlyList<Avviso> avvisi)
    {
        var f = new FinestraAvviso(avvisi, null, perImmagine: true);
        CreaHandle(f);
        f.Aggiorna();
        return f;
    }

    internal Bitmap Disegna()
    {
        var immagine = new Bitmap(Width, Height);
        DrawToBitmap(immagine, new Rectangle(Point.Empty, Size));
        return immagine;
    }

    internal Rectangle PulsanteHoCapito => hoCapito.Bounds;
    internal Rectangle PulsanteApri => apri.Bounds;
    internal Rectangle RigaSuggerimento => suggerimento.Bounds;

    private static void CreaHandle(Control c)
    {
        _ = c.Handle;
        foreach (Control figlio in c.Controls) CreaHandle(figlio);
    }

    protected override void Dispose(bool disposing)
    {
        // Prima di tutto: SystemEvents terrebbe viva questa finestra per sempre.
        if (disposing && !perImmagine) SystemEvents.DisplaySettingsChanged -= SuSchermiCambiati;
        base.Dispose(disposing);
        if (disposing)
        {
            // Dopo la finestra: caratteri e icona non servono più a nessuno.
            aspetto?.Dispose();
            aspetto = null;
            iconaPropria?.Dispose();
        }
    }
}

/// <summary>
/// Dove va ogni pezzo dell'avviso, logica pura (provata nei test): una colonna larga come la
/// schermata "Collega" dell'interfaccia (540 px), al centro dello schermo; sotto, i due pulsanti
/// della stessa larghezza (<c>.azioni-pari</c>) e la riga "Se i tasti non rispondono, fai clic qui".
/// Se le schede non ci stanno, scorre solo il contenuto: pulsanti e riga restano sempre in vista,
/// perché l'avviso si deve poter chiudere sempre.
/// </summary>
internal readonly record struct Disposizione(Rectangle Vista, Rectangle Foglio, Rectangle HoCapito, Rectangle Apri, Rectangle Suggerimento, bool Scorre)
{
    public static Disposizione Calcola(Size area, float scala, Func<int, int> altezzaFoglio, int barraScorrimento, int altezzaSuggerimento)
    {
        int Px(float px) => (int)Math.Round(px * scala);
        int margine = Px(32);
        int larghezza = Math.Max(Math.Min(Px(200), area.Width), Math.Min(Px(540), area.Width - 2 * margine));
        int x = Math.Max(0, (area.Width - larghezza) / 2);
        int hBottoni = Px(40);
        int sopraBottoni = Px(20); // 16 fra i blocchi (.collega) + 4 sopra le azioni (.azioni)
        int sottoBottoni = Px(12);
        int hFondo = sopraBottoni + hBottoni + sottoBottoni + altezzaSuggerimento;
        int disponibile = Math.Max(0, area.Height - 2 * margine);
        int hFoglio = altezzaFoglio(larghezza);

        Rectangle vista;
        int yBottoni;
        bool scorre = hFoglio + hFondo > disponibile;
        if (!scorre)
        {
            int y = margine + (disponibile - (hFoglio + hFondo)) / 2;
            vista = new Rectangle(x, y, larghezza, hFoglio);
            yBottoni = y + hFoglio + sopraBottoni;
        }
        else
        {
            // La barra di scorrimento sta fuori dalla colonna, a destra: il testo resta allineato ai pulsanti.
            int hVista = Math.Max(0, disponibile - hFondo);
            vista = new Rectangle(x, margine, Math.Min(larghezza + barraScorrimento, area.Width - x), hVista);
            yBottoni = margine + hVista + sopraBottoni;
        }

        // Fra le due pillole c'è il posto per il segno del fuoco di tutte e due (come .azioni, poco più di 8 px).
        int m = Px(BottonePactum.Margine);
        int spazio = 2 * m;
        int w1 = (larghezza - spazio) / 2;
        var hoCapito = Rectangle.Inflate(new Rectangle(x, yBottoni, w1, hBottoni), m, m);
        var apri = Rectangle.Inflate(new Rectangle(x + w1 + spazio, yBottoni, larghezza - w1 - spazio, hBottoni), m, m);
        var suggerimento = new Rectangle(x, yBottoni + hBottoni + sottoBottoni, larghezza, altezzaSuggerimento);
        return new Disposizione(vista, new Rectangle(0, 0, larghezza, hFoglio), hoCapito, apri, suggerimento, scorre);
    }
}

/// <summary>
/// Il contenuto dell'avviso, disegnato come l'interfaccia: il marchio, il titolo (.eroe), una
/// scheda per regola come la riga di Oggi (nome, "2 h 10 min su 2 h", la barra piena e mai rossa,
/// "10 min oltre", il limite che ti sei dato), e la frase del patto. Una sola funzione misura e
/// disegna, così misure e disegno non si separano mai.
/// </summary>
internal sealed class FoglioAvviso : Control
{
    private const TextFormatFlags Paragrafo =
        TextFormatFlags.WordBreak | TextFormatFlags.TextBoxControl | TextFormatFlags.NoPrefix | TextFormatFlags.NoPadding;

    private const TextFormatFlags Riga = TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix | TextFormatFlags.NoPadding;

    private IReadOnlyList<Avviso> avvisi = Array.Empty<Avviso>();
    private Aspetto? aspetto;

    public FoglioAvviso()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        SetStyle(ControlStyles.Selectable, false);
        TabStop = false;
        AccessibleRole = AccessibleRole.StaticText;
    }

    public void Imposta(IReadOnlyList<Avviso> nuovi, Aspetto nuovo)
    {
        avvisi = nuovi;
        aspetto = nuovo;
        BackColor = nuovo.Sfondo;
        // Per chi usa un lettore di schermo: tutto l'avviso, con le stesse parole.
        AccessibleName = Testi.TestoAvviso(nuovi);
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
        y += Testo(g, Testi.TitoloAvviso(avvisi), a.CarattereTitolo, a.Testo, 0, y, larghezza);
        y += a.Px(16);
        for (int i = 0; i < avvisi.Count; i++)
        {
            if (i > 0) y += a.Px(12);
            y += Scheda(g, avvisi[i], y, larghezza);
        }
        y += a.Px(16);
        y += Testo(g, Testi.NessunBlocco, a.CarattereFrase, a.Testo, 0, y, larghezza);
        return y;
    }

    /// <summary>.card: bianca, bordo sottile, angoli da 14 px, 18 × 20 px di margine interno.</summary>
    private int Scheda(Graphics? g, Avviso avviso, int y, int larghezza)
    {
        var a = aspetto!;
        int dentroX = a.Px(20);
        int dentroY = a.Px(18);
        int larghezzaDentro = Math.Max(1, larghezza - 2 * dentroX);
        int altezza = ContenutoScheda(null, avviso, 0, 0, larghezzaDentro) + 2 * dentroY;
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
            ContenutoScheda(g, avviso, dentroX, y + dentroY, larghezzaDentro);
        }
        return altezza;
    }

    private int ContenutoScheda(Graphics? g, Avviso avviso, int x, int y, int larghezza)
    {
        var a = aspetto!;
        int inizio = y;
        int spazio = a.Px(8);
        if (avviso.Fascia)
        {
            y += Testo(g, avviso.Nome, a.CarattereNome, a.Testo, x, y, larghezza);
            y += spazio;
            y += Testo(g, Testi.FasciaUso(avviso.MinutiUsati), a.CarattereSecondario, a.TestoSecondario, x, y, larghezza);
            return y - inizio;
        }

        // Come la riga di Oggi: il nome a sinistra, "2 h 10 min su 2 h" a destra.
        var numero = Testi.MinutiSuLimite(avviso.MinutiUsati, avviso.LimiteEfficace ?? 0);
        var misuraNumero = TextRenderer.MeasureText(numero, a.CarattereNumero, Size.Empty, Riga);
        int larghezzaNome = Math.Max(a.Px(80), larghezza - misuraNumero.Width - a.Px(12));
        int altezzaNome = Testo(g, avviso.Nome, a.CarattereNome, a.Testo, x, y, larghezzaNome);
        if (g != null)
        {
            // In basso sulla prima riga del nome, come l'allineamento sulla linea di base del CSS.
            int primaRiga = TextRenderer.MeasureText("Ag", a.CarattereNome, Size.Empty, Riga).Height;
            var punto = new Point(x + larghezza - misuraNumero.Width, y + Math.Max(0, primaRiga - misuraNumero.Height));
            TextRenderer.DrawText(g, numero, a.CarattereNumero, punto, a.Testo, Riga);
        }
        y += Math.Max(altezzaNome, misuraNumero.Height) + spazio;

        int altezzaBarra = a.Px(6);
        if (g != null) Barra(g, new Rectangle(x, y, larghezza, altezzaBarra), avviso.MinutiUsati, avviso.LimiteEfficace ?? 0);
        y += altezzaBarra + spazio;

        y += Etichetta(g, Testi.Oltre(avviso.MinutiOltre), x, y);
        if (avviso.Limite is int limite)
        {
            y += spazio;
            y += Testo(g, Testi.LimiteDato(limite, avviso.Bonus), a.CarattereSecondario, a.TestoSecondario, x, y, larghezza);
        }
        return y - inizio;
    }

    /// <summary>.barra: il limite di oggi. Oltre il limite resta piena e verde, mai rossa.</summary>
    private void Barra(Graphics g, Rectangle r, int usati, int limite)
    {
        var a = aspetto!;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        float raggio = r.Height / 2f;
        using (var binario = Aspetto.Arrotondato(r, raggio))
        using (var fondo = new SolidBrush(a.BarraFondo))
        {
            g.FillPath(fondo, binario);
        }
        double frazione = Math.Clamp(usati / (double)Math.Max(1, limite), 0, 1);
        float pieno = (float)(r.Width * frazione);
        if (pieno > 0)
        {
            using var percorso = Aspetto.Arrotondato(new RectangleF(r.X, r.Y, pieno, r.Height), Math.Min(raggio, pieno / 2));
            using var colore = new SolidBrush(a.BarraPieno);
            g.FillPath(colore, percorso);
        }
        if (a.Contrasto)
        {
            using var binario = Aspetto.Arrotondato(r, raggio);
            using var bordo = new Pen(a.Testo, a.Linea);
            g.DrawPath(bordo, binario);
        }
    }

    /// <summary>.chip.chip-secondario: "10 min oltre".</summary>
    private int Etichetta(Graphics? g, string testo, int x, int y)
    {
        var a = aspetto!;
        var misura = TextRenderer.MeasureText(testo, a.CarattereEtichetta, Size.Empty, Riga);
        int larghezza = misura.Width + 2 * a.Px(10);
        int altezza = Math.Max(misura.Height, a.Px(18)) + 2 * a.Px(2);
        if (g != null)
        {
            var r = new Rectangle(x, y, larghezza, altezza);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var percorso = Aspetto.Arrotondato(r, altezza / 2f))
            using (var fondo = new SolidBrush(a.EtichettaFondo))
            {
                g.FillPath(fondo, percorso);
                if (a.Contrasto)
                {
                    using var bordo = new Pen(a.Testo, a.Linea);
                    g.DrawPath(bordo, percorso);
                }
            }
            TextRenderer.DrawText(g, testo, a.CarattereEtichetta, r, a.EtichettaTesto,
                Riga | TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }
        return altezza;
    }

    /// <summary>Un paragrafo che va a capo nella larghezza data. Restituisce l'altezza.</summary>
    private static int Testo(Graphics? g, string testo, Font carattere, Color colore, int x, int y, int larghezza)
    {
        var misura = TextRenderer.MeasureText(testo, carattere, new Size(Math.Max(1, larghezza), int.MaxValue), Paragrafo);
        if (g != null) TextRenderer.DrawText(g, testo, carattere, new Rectangle(x, y, larghezza, misura.Height), colore, Paragrafo);
        return misura.Height;
    }
}
