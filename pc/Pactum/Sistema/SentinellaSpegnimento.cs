using System.Windows.Forms;

namespace Pactum.Sistema;

/// <summary>
/// (0.18, contratto v4.0) Una finestra invisibile che riceve <c>WM_ENDSESSION</c> da Windows: dice se lo
/// spegnimento sta davvero avvenendo (<c>fEndSession = TRUE</c>, <c>wParam != 0</c>) o se è stato
/// <b>annullato</b> (<c>fEndSession = FALSE</c>). È la prova vera dello spegnimento annullato; il timer di
/// 60 s del contesto resta solo come riserva.
/// </summary>
public sealed class SentinellaSpegnimento : NativeWindow, IDisposable
{
    private const int WM_QUERYENDSESSION = 0x0011;
    private const int WM_ENDSESSION = 0x0016;

    private readonly Action<bool> suFineSessione;

    /// <param name="suFineSessione">Chiamato a WM_ENDSESSION: <c>true</c> = lo spegnimento avviene, <c>false</c> = annullato.</param>
    public SentinellaSpegnimento(Action<bool> suFineSessione)
    {
        this.suFineSessione = suFineSessione;
        CreateHandle(new CreateParams());
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WM_QUERYENDSESSION)
        {
            // Non blocchiamo mai lo spegnimento: lo lasciamo proseguire.
            base.WndProc(ref m);
            m.Result = (IntPtr)1;
            return;
        }
        if (m.Msg == WM_ENDSESSION)
        {
            bool avviene = m.WParam != IntPtr.Zero;
            try
            {
                suFineSessione(avviene);
            }
            catch (Exception e)
            {
                Pactum.Motore.Log.Errore("WM_ENDSESSION non gestito", e);
            }
        }
        base.WndProc(ref m);
    }

    public void Dispose() => DestroyHandle();
}
