using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;

namespace Pactum.Interfaccia;

/// <summary>
/// L'aspetto dell'interfaccia (<c>pc/ui/stile.css</c>) per le finestre fatte in WinForms: gli
/// stessi colori della palette verde del figlio, gli stessi caratteri (Segoe UI Variable, sennò
/// Segoe UI) e le stesse misure in px, moltiplicate per la scala del monitor. Niente rosso. Con
/// il contrasto elevato di Windows valgono i colori di sistema (come <c>forced-colors</c> nel CSS).
/// </summary>
internal sealed class Aspetto : IDisposable
{
    public Aspetto(float scala, bool contrasto)
    {
        Scala = scala;
        Contrasto = contrasto;
        if (contrasto)
        {
            Sfondo = SystemColors.Window;
            Testo = SystemColors.WindowText;
            TestoSecondario = SystemColors.WindowText;
            Primario = SystemColors.Highlight;
            PrimarioSopra = SystemColors.Highlight;
            SuPrimario = SystemColors.HighlightText;
            Scheda = SystemColors.Window;
            BordoScheda = SystemColors.WindowText;
            BarraFondo = SystemColors.Window;
            BarraPieno = SystemColors.Highlight;
            EtichettaFondo = SystemColors.Window;
            EtichettaTesto = SystemColors.WindowText;
            Contorno = SystemColors.WindowText;
            Tocco = SystemColors.Window;
            Marchio = SystemColors.WindowText;
            Collegamento = SystemColors.HotTrack;
        }
        else
        {
            Sfondo = Hex(0xFBFDFC);          // --superficie
            Testo = Hex(0x181D1B);           // --su-superficie
            TestoSecondario = Hex(0x414944); // --su-superficie-variante
            Primario = Hex(0x1F6E5C);        // --primario
            PrimarioSopra = Hex(0x185A4B);   // --primario-premuto
            SuPrimario = Color.White;        // --su-primario
            Scheda = Color.White;            // --contenitore-minimo
            BordoScheda = Hex(0xC1C9C4);     // --contorno-variante
            BarraFondo = Hex(0xE1E7E4);      // --superficie-variante
            BarraPieno = Primario;
            EtichettaFondo = Hex(0xDCE9E4);  // --contenitore-secondario
            EtichettaTesto = Hex(0x17332B);  // --su-contenitore-secondario
            Contorno = Hex(0x717973);        // --contorno
            Tocco = Color.FromArgb(20, 0x1F, 0x6E, 0x5C); // --tocco: rgba(31, 110, 92, 0.08)
            Marchio = Primario;
            Collegamento = Primario;         // a { color: var(--primario) }
        }

        CarattereMarchio = Carattere(20, semigrassetto: true, titoli: true);
        CarattereTitolo = Carattere(36, semigrassetto: true, titoli: true);
        CarattereNome = Carattere(16, semigrassetto: true, titoli: false);
        CarattereNumero = Carattere(14, semigrassetto: false, titoli: false);
        CarattereEtichetta = Carattere(12, semigrassetto: true, titoli: false);
        CarattereSecondario = Carattere(14, semigrassetto: false, titoli: false);
        CarattereFrase = Carattere(16, semigrassetto: false, titoli: false);
        CarattereBottone = Carattere(14, semigrassetto: true, titoli: false);
        CarattereAiuto = Carattere(13, semigrassetto: false, titoli: false);
    }

    public float Scala { get; }
    public bool Contrasto { get; }

    public Color Sfondo { get; }
    public Color Testo { get; }
    public Color TestoSecondario { get; }
    public Color Primario { get; }
    public Color PrimarioSopra { get; }
    public Color SuPrimario { get; }
    public Color Scheda { get; }
    public Color BordoScheda { get; }
    public Color BarraFondo { get; }
    public Color BarraPieno { get; }
    public Color EtichettaFondo { get; }
    public Color EtichettaTesto { get; }
    public Color Contorno { get; }
    public Color Tocco { get; }
    public Color Marchio { get; }
    public Color Collegamento { get; }

    /// <summary>.marchio-nome: 20 px, semigrassetto.</summary>
    public Font CarattereMarchio { get; }

    /// <summary>.eroe: 36 px, semigrassetto.</summary>
    public Font CarattereTitolo { get; }

    /// <summary>Il nome della regola nella scheda: 16 px, semigrassetto.</summary>
    public Font CarattereNome { get; }

    /// <summary>.riga-numero: 14 px.</summary>
    public Font CarattereNumero { get; }

    /// <summary>.chip: 12 px, semigrassetto.</summary>
    public Font CarattereEtichetta { get; }

    /// <summary>.secondario: 14 px.</summary>
    public Font CarattereSecondario { get; }

    /// <summary>.eroe-sotto: 16 px.</summary>
    public Font CarattereFrase { get; }

    /// <summary>.bottone: 14 px, semigrassetto.</summary>
    public Font CarattereBottone { get; }

    /// <summary>.aiuto: 13 px.</summary>
    public Font CarattereAiuto { get; }

    /// <summary>Una misura in px del CSS, sul monitor di adesso.</summary>
    public int Px(float px) => (int)Math.Round(px * Scala);

    /// <summary>Lo spessore delle linee da 1 px del CSS: sempre un numero intero di pixel, così resta netto.</summary>
    public float Linea => Math.Max(1, (int)Scala);

    private static Color Hex(int rgb) => Color.FromArgb(255, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);

    private static readonly Lazy<HashSet<string>> Installati = new(() =>
    {
        using var raccolta = new InstalledFontCollection();
        return new HashSet<string>(raccolta.Families.Select(f => f.Name), StringComparer.OrdinalIgnoreCase);
    });

    /// <summary>
    /// Come <c>--carattere</c> e <c>--carattere-titoli</c> del CSS: Segoe UI Variable (Windows 11),
    /// altrimenti Segoe UI. Per GDI il semigrassetto (600) è una famiglia a sé; i nomi lunghi sono
    /// tagliati a 31 lettere ("Segoe UI Variable Display Semib").
    /// </summary>
    private Font Carattere(float px, bool semigrassetto, bool titoli)
    {
        string[] famiglie = (semigrassetto, titoli) switch
        {
            (true, true) => new[] { "Segoe UI Variable Display Semib", "Segoe UI Variable Display Semibold", "Segoe UI Semibold" },
            (true, false) => new[] { "Segoe UI Variable Text Semibold", "Segoe UI Semibold" },
            (false, true) => new[] { "Segoe UI Variable Display", "Segoe UI" },
            _ => new[] { "Segoe UI Variable Text", "Segoe UI" },
        };
        foreach (var famiglia in famiglie)
        {
            if (Installati.Value.Contains(famiglia)) return new Font(famiglia, px * Scala, FontStyle.Regular, GraphicsUnit.Pixel);
        }
        return new Font(FontFamily.GenericSansSerif, px * Scala, semigrassetto ? FontStyle.Bold : FontStyle.Regular, GraphicsUnit.Pixel);
    }

    /// <summary>Un rettangolo con gli angoli arrotondati (<c>border-radius</c>).</summary>
    public static GraphicsPath Arrotondato(RectangleF r, float raggio)
    {
        var p = new GraphicsPath();
        float d = Math.Min(raggio * 2, Math.Min(r.Width, r.Height));
        if (d <= 0.5f)
        {
            p.AddRectangle(r);
            return p;
        }
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    public void Dispose()
    {
        CarattereMarchio.Dispose();
        CarattereTitolo.Dispose();
        CarattereNome.Dispose();
        CarattereNumero.Dispose();
        CarattereEtichetta.Dispose();
        CarattereSecondario.Dispose();
        CarattereFrase.Dispose();
        CarattereBottone.Dispose();
        CarattereAiuto.Dispose();
    }
}

/// <summary>
/// Un pulsante come <c>.bottone</c> dell'interfaccia: a pillola, "primario" pieno oppure "contorno".
/// Resta un Button vero (tastiera, Invio/Spazio, lettori di schermo): cambia solo il disegno. Attorno
/// lascia lo spazio del segno del fuoco (3 px, 2 px più in là, come <c>:focus-visible</c>).
/// </summary>
internal sealed class BottonePactum : Button
{
    private readonly bool primario;
    private Aspetto? aspetto;
    private bool sopra;

    public BottonePactum(string testo, bool primario)
    {
        this.primario = primario;
        Text = testo;
        UseMnemonic = false;
        UseVisualStyleBackColor = false;
        Cursor = Cursors.Hand;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
    }

    /// <summary>Lo spazio attorno alla pillola per il segno del fuoco.</summary>
    public static float Margine => 5;

    public void Imposta(Aspetto nuovo)
    {
        aspetto = nuovo;
        Font = nuovo.CarattereBottone;
        BackColor = nuovo.Sfondo;
        Invalidate();
    }

    protected override void OnMouseEnter(EventArgs e)
    {
        sopra = true;
        Invalidate();
        base.OnMouseEnter(e);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        sopra = false;
        Invalidate();
        base.OnMouseLeave(e);
    }

    protected override void OnGotFocus(EventArgs e)
    {
        Invalidate();
        base.OnGotFocus(e);
    }

    protected override void OnLostFocus(EventArgs e)
    {
        Invalidate();
        base.OnLostFocus(e);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var a = aspetto;
        if (a == null)
        {
            base.OnPaint(e);
            return;
        }
        var g = e.Graphics;
        g.Clear(a.Sfondo);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        int margine = a.Px(Margine);
        var pillola = Rectangle.Inflate(ClientRectangle, -margine, -margine);
        if (pillola.Width <= 0 || pillola.Height <= 0) return;

        var forma = new RectangleF(pillola.X + 0.5f, pillola.Y + 0.5f, pillola.Width - 1, pillola.Height - 1);
        using (var percorso = Aspetto.Arrotondato(forma, forma.Height / 2))
        {
            if (primario)
            {
                using var pieno = new SolidBrush(sopra ? a.PrimarioSopra : a.Primario);
                g.FillPath(pieno, percorso);
            }
            else
            {
                if (sopra)
                {
                    using var tocco = new SolidBrush(a.Tocco);
                    g.FillPath(tocco, percorso);
                }
                using var bordo = new Pen(a.Contorno, a.Linea);
                g.DrawPath(bordo, percorso);
            }
            if (a.Contrasto && primario)
            {
                using var bordo = new Pen(a.Testo, a.Linea);
                g.DrawPath(bordo, percorso);
            }
        }

        var colore = primario ? a.SuPrimario : (a.Contrasto ? a.Testo : a.Primario);
        TextRenderer.DrawText(g, Text, Font, pillola, colore,
            TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix | TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);

        if (Focused && ShowFocusCues)
        {
            float spessore = a.Px(3);
            var fuori = RectangleF.Inflate(pillola, a.Px(2) + spessore / 2, a.Px(2) + spessore / 2);
            using var percorso = Aspetto.Arrotondato(fuori, fuori.Height / 2);
            using var penna = new Pen(a.Contrasto ? a.Testo : a.Primario, spessore);
            g.DrawPath(penna, percorso);
        }
    }
}
