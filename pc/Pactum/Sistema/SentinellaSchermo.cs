using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Pactum.Sistema;

/// <summary>
/// Una finestra invisibile che riceve da Windows l'accensione e lo spegnimento
/// del monitor: con lo schermo spento un gioco in pausa a schermo intero non conta.
/// </summary>
public sealed class SentinellaSchermo : NativeWindow, IDisposable
{
    private readonly Action<bool> suCambio;
    private IntPtr registrazione;

    public SentinellaSchermo(Action<bool> suCambio)
    {
        this.suCambio = suCambio;
        CreateHandle(new CreateParams());
        var guid = Win32.GuidStatoSchermo;
        registrazione = Win32.RegisterPowerSettingNotification(Handle, ref guid, Win32.DEVICE_NOTIFY_WINDOW_HANDLE);
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == Win32.WM_POWERBROADCAST && (int)m.WParam == Win32.PBT_POWERSETTINGCHANGE && m.LParam != IntPtr.Zero)
        {
            var guid = Marshal.PtrToStructure<Guid>(m.LParam);
            if (guid == Win32.GuidStatoSchermo)
            {
                // POWERBROADCAST_SETTING: Guid (16 byte), DataLength (4 byte), poi i dati.
                int stato = Marshal.ReadInt32(m.LParam, 20);
                suCambio(stato != 0);
            }
        }
        base.WndProc(ref m);
    }

    public void Dispose()
    {
        if (registrazione != IntPtr.Zero)
        {
            Win32.UnregisterPowerSettingNotification(registrazione);
            registrazione = IntPtr.Zero;
        }
        DestroyHandle();
    }
}
