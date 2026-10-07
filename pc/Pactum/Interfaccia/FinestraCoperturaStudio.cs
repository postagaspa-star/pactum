using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Interfaccia;

/// <summary>
/// (0.18, contratto v4.0) La finestra che copre uno schermo durante lo Studio quando vi è visibile una
/// finestra fuori lista. Copre l'<b>area di lavoro</b> (la barra delle applicazioni resta libera: da lì il
/// figlio riduce a icona o chiude la finestra fuori lista), sta sempre in primo piano, non si chiude a mano
/// e dice «Sei in Studio» con la lista. Non chiude e non tocca le altre app. In WinForms, come la copertura
/// del blocco: si disegna anche senza rete e senza WebView2.
/// </summary>
public sealed class FinestraCoperturaStudio : Form, ICopertura
{
    private readonly Rectangle area;
    private readonly IReadOnlyList<string> voci;
    private readonly bool contrasto = SystemInformation.HighContrast;
    private Aspetto? aspetto;
    private bool consentiChiusura;

    public FinestraCoperturaStudio(Rectangle area, ConfigStudio config, long inizioMs)
    {
        this.area = area;
        voci = Testi.VociStudio(config.Programmi, config.Nomi);
        Text = "Pactum";
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        Bounds = area;
        AutoScaleMode = AutoScaleMode.None;
        TopMost = true;
        ShowInTaskbar = false;
        ControlBox = false;
        MinimizeBox = false;
        MaximizeBox = false;
        DoubleBuffered = true;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
    }

    protected override bool ShowWithoutActivation => false;

    public void ConsentiChiusura() => consentiChiusura = true;

    public void RiportaInCima()
    {
        if (IsDisposed) return;
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        if (IsHandleCreated)
        {
            Win32.SetWindowPos(Handle, Win32.HWND_TOPMOST, 0, 0, 0, 0, Win32.SWP_NOACTIVATE | Win32.SWP_NOMOVE | Win32.SWP_NOSIZE);
        }
    }

    /// <summary>
    /// (correzione 0.18) Prende il primo piano, come la copertura del blocco: serve quando sullo schermo coperto c'è in
    /// primo piano un'app a schermo intero (un gioco a schermo intero esclusivo terrebbe tastiera e mouse). Best effort.
    /// </summary>
    public void Attiva()
    {
        if (IsDisposed) return;
        RiportaInCima();
        try
        {
            Activate();
        }
        catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
        {
        }
    }

    protected override void WndProc(ref Message m)
    {
        // (correzione 0.18) Un WM_QUERYENDSESSION/WM_ENDSESSION finto (Windows non si sta chiudendo) non arriva a
        // WinForms, che per WM_ENDSESSION chiuderebbe la copertura senza passare da OnFormClosing.
        if (RegoleCopertura.MessaggioDaIgnorare(m.Msg, m.Msg is Win32.WM_QUERYENDSESSION or Win32.WM_ENDSESSION && Win32.SessioneSiChiude()))
        {
            if (m.Msg == Win32.WM_QUERYENDSESSION) m.Result = (IntPtr)1;
            Pactum.Motore.Log.Avviso("messaggio di fine sessione ignorato: Windows non si sta chiudendo");
            return;
        }
        if (m.Msg == Win32.WM_WINDOWPOSCHANGING && m.LParam != IntPtr.Zero)
        {
            var pos = Marshal.PtrToStructure<Win32.WINDOWPOS>(m.LParam);
            pos.x = area.X; pos.y = area.Y; pos.cx = area.Width; pos.cy = area.Height;
            pos.flags &= ~(Win32.SWP_NOMOVE | Win32.SWP_NOSIZE);
            Marshal.StructureToPtr(pos, m.LParam, false);
        }
        base.WndProc(ref m);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // (contratto v4.0, «La copertura non si chiude da fuori») Né a mano né da un altro programma: la chiude solo
        // Pactum (fine dello Studio) o lo spegnimento di Windows. Se sparisce lo stesso, GestoreStudio la rifà subito.
        if (!RegoleCopertura.ChiusuraPermessa(consentiChiusura, e.CloseReason)) { e.Cancel = true; return; }
        base.OnFormClosing(e);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        float scala = DeviceDpi / 96f;
        if (aspetto == null || Math.Abs(aspetto.Scala - scala) > 0.001f)
        {
            aspetto?.Dispose();
            aspetto = new Aspetto(scala, contrasto);
        }
        var a = aspetto;
        var g = e.Graphics;
        g.Clear(a.Sfondo);
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

        int larghezza = Math.Min(a.Px(540), ClientSize.Width - 2 * a.Px(40));
        larghezza = Math.Max(a.Px(200), larghezza);
        var righe = new List<(string Testo, Font Font, Color Colore)>
        {
            ("Pactum", a.CarattereMarchio, a.Marchio),
            (Testi.TitoloStudio, a.CarattereTitolo, a.Testo),
        };
        if (voci.Count == 0) righe.Add((Testi.StudioListaVuota, a.CarattereFrase, a.TestoSecondario));
        else foreach (var v in voci) righe.Add(("• " + v, a.CarattereNome, a.Testo));
        righe.Add((Testi.SottoStudio, a.CarattereFrase, a.TestoSecondario));

        const TextFormatFlags Flags = TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix | TextFormatFlags.HorizontalCenter;
        int altezza = 0;
        var misure = new List<int>();
        foreach (var (testo, font, _) in righe)
        {
            int h = TextRenderer.MeasureText(g, testo, font, new Size(larghezza, int.MaxValue), Flags).Height;
            misure.Add(h);
            altezza += h + a.Px(10);
        }
        int x = (ClientSize.Width - larghezza) / 2;
        int y = Math.Max(a.Px(40), (ClientSize.Height - altezza) / 2);
        for (int i = 0; i < righe.Count; i++)
        {
            var (testo, font, colore) = righe[i];
            TextRenderer.DrawText(g, testo, font, new Rectangle(x, y, larghezza, misure[i]), colore, Flags);
            y += misure[i] + a.Px(10);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { aspetto?.Dispose(); aspetto = null; }
        base.Dispose(disposing);
    }
}
