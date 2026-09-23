using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using Pactum.Nucleo;

namespace Pactum.Sistema;

/// <summary>Il programma della finestra in primo piano. Nessun titolo: solo processo, classe e rettangolo.</summary>
public sealed record FinestraAttiva(IntPtr Hwnd, int Pid, string Exe, string Chiave, string Nome, bool SchermoIntero);

public static class PrimoPiano
{
    private static readonly HashSet<string> ClassiDesktop = new(StringComparer.Ordinal)
    {
        "Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd",
    };

    private static readonly ConcurrentDictionary<string, string> NomiPerPercorso = new(StringComparer.OrdinalIgnoreCase);

    private static readonly uint MioPid = Win32.GetCurrentProcessId();

    /// <summary>La finestra in primo piano, oppure null (nessuna, oppure è Pactum stesso).</summary>
    public static FinestraAttiva? Leggi() => DaFinestra(Win32.GetForegroundWindow());

    /// <summary>
    /// Solo per le prove (<c>--prova-finestra</c>): la finestra principale di un processo al posto di
    /// quella in primo piano, così un browser di prova può restare ridotto a icona.
    /// </summary>
    public static FinestraAttiva? LeggiFinestraDi(int pid)
    {
        IntPtr trovata = IntPtr.Zero;
        Win32.EnumWindows((h, _) =>
        {
            Win32.GetWindowThreadProcessId(h, out uint p);
            if (p == pid && Win32.IsWindowVisible(h) && Win32.ClasseDi(h) == "Chrome_WidgetWin_1")
            {
                trovata = h;
                return false;
            }
            return true;
        }, IntPtr.Zero);
        return DaFinestra(trovata);
    }

    private static FinestraAttiva? DaFinestra(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return null;
        Win32.GetWindowThreadProcessId(hwnd, out uint pid);
        if (pid == 0) return null;

        var (exe, percorso) = Processo(pid);
        if (exe == null) return null;

        // Le app dello Store vivono dentro ApplicationFrameHost: il processo vero è quello di una finestra figlia.
        if (exe == "applicationframehost.exe")
        {
            uint vero = ProcessoFiglio(hwnd, pid);
            if (vero != 0)
            {
                pid = vero;
                (exe, percorso) = Processo(vero);
                if (exe == null) return null;
            }
        }
        if (pid == MioPid) return null;

        var classe = Win32.ClasseDi(hwnd);
        bool schermoIntero = !ClassiDesktop.Contains(classe) && SchermoIntero(hwnd);
        return new FinestraAttiva(hwnd, (int)pid, exe, Programma.Chiave(exe), NomeLeggibile(percorso, exe), schermoIntero);
    }

    private static (string? Exe, string? Percorso) Processo(uint pid)
    {
        var h = Win32.OpenProcess(Win32.PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h != IntPtr.Zero)
        {
            try
            {
                var sb = new StringBuilder(1024);
                uint lunghezza = (uint)sb.Capacity;
                if (Win32.QueryFullProcessImageName(h, 0, sb, ref lunghezza))
                {
                    var percorso = sb.ToString();
                    return (Path.GetFileName(percorso).ToLowerInvariant(), percorso);
                }
            }
            finally
            {
                Win32.CloseHandle(h);
            }
        }
        // Processi protetti o di un altro utente: basta il nome.
        try
        {
            using var p = Process.GetProcessById((int)pid);
            return (p.ProcessName.ToLowerInvariant() + ".exe", null);
        }
        catch (ArgumentException)
        {
            return (null, null);
        }
        catch (InvalidOperationException)
        {
            return (null, null);
        }
    }

    private static uint ProcessoFiglio(IntPtr cornice, uint pidCornice)
    {
        uint trovato = 0;
        Win32.EnumChildWindows(cornice, (figlia, _) =>
        {
            Win32.GetWindowThreadProcessId(figlia, out uint p);
            if (p != 0 && p != pidCornice)
            {
                trovato = p;
                return false;
            }
            return true;
        }, IntPtr.Zero);
        return trovato;
    }

    private static bool SchermoIntero(IntPtr hwnd)
    {
        if (Win32.IsIconic(hwnd)) return false;
        if (!Win32.GetWindowRect(hwnd, out var r)) return false;
        var monitor = Win32.MonitorFromWindow(hwnd, Win32.MONITOR_DEFAULTTONEAREST);
        if (monitor == IntPtr.Zero) return false;
        var info = new Win32.MONITORINFO { cbSize = System.Runtime.InteropServices.Marshal.SizeOf<Win32.MONITORINFO>() };
        if (!Win32.GetMonitorInfo(monitor, ref info)) return false;
        var m = info.rcMonitor;
        return r.Left <= m.Left && r.Top <= m.Top && r.Right >= m.Right && r.Bottom >= m.Bottom;
    }

    /// <summary>La descrizione del file ("Google Chrome"), altrimenti il nome del file.</summary>
    private static string NomeLeggibile(string? percorso, string exe)
    {
        if (percorso == null) return Programma.NomeDiRipiego(exe);
        return NomiPerPercorso.GetOrAdd(percorso, p =>
        {
            try
            {
                var descrizione = FileVersionInfo.GetVersionInfo(p).FileDescription?.Trim();
                if (!string.IsNullOrEmpty(descrizione)) return descrizione;
            }
            catch (FileNotFoundException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
            catch (IOException)
            {
            }
            return Programma.NomeDiRipiego(Path.GetFileName(p));
        });
    }
}

/// <summary>Lo stato della sessione di Windows per decidere se l'utente "c'è".</summary>
public static class Sessione
{
    private static readonly uint MiaSessione = Win32.ProcessIdToSessionId(Win32.GetCurrentProcessId(), out var s) ? s : uint.MaxValue;

    /// <summary>Millisecondi dall'ultimo tasto o movimento del mouse in questa sessione.</summary>
    public static long MsDallUltimoInput()
    {
        var info = new Win32.LASTINPUTINFO { cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf<Win32.LASTINPUTINFO>() };
        if (!Win32.GetLastInputInfo(ref info)) return long.MaxValue;
        // Entrambi a 32 bit: la sottrazione senza segno regge anche il giro del contatore dopo 49 giorni.
        return unchecked((uint)Environment.TickCount - info.dwTime);
    }

    /// <summary>Sessione sulla console (non un altro utente davanti al PC) o remota, e desktop di input normale (non bloccato).</summary>
    public static bool Disponibile()
    {
        bool console = Win32.WTSGetActiveConsoleSessionId() == MiaSessione || System.Windows.Forms.SystemInformation.TerminalServerSession;
        return console && DesktopDiInputNormale();
    }

    public static bool SalvaschermoInFunzione() =>
        Win32.SystemParametersInfo(Win32.SPI_GETSCREENSAVERRUNNING, 0, out int attivo, 0) && attivo != 0;

    /// <summary>Con lo schermo bloccato (o la richiesta di UAC) il desktop di input non è "Default".</summary>
    private static bool DesktopDiInputNormale()
    {
        var desktop = Win32.OpenInputDesktop(0, false, Win32.DESKTOP_READOBJECTS);
        if (desktop == IntPtr.Zero) return false;
        try
        {
            var sb = new StringBuilder(64);
            return Win32.GetUserObjectInformation(desktop, Win32.UOI_NAME, sb, sb.Capacity * 2, out _)
                   && string.Equals(sb.ToString(), "Default", StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            Win32.CloseDesktop(desktop);
        }
    }
}
