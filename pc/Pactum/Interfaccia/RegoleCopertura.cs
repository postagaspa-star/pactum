using System.Windows.Forms;
using Pactum.Sistema;

namespace Pactum.Interfaccia;

/// <summary>
/// (0.18, contratto v4.0, «La copertura non si chiude da fuori») Le regole comuni alle coperture del blocco e
/// dello Studio: nessuna richiesta di chiusura le chiude (Alt+F4, un messaggio di chiusura da un altro programma,
/// il Task Manager che chiede di chiudere la finestra), tranne lo spegnimento o l'uscita da Windows. Le chiude
/// solo Pactum stesso, quando il blocco o lo Studio finiscono (o quando esce).
/// </summary>
public static class RegoleCopertura
{
    /// <summary>Una copertura può chiudersi? Solo se l'ha permesso Pactum o se Windows si spegne. Logica pura.</summary>
    public static bool ChiusuraPermessa(bool consentitaDaPactum, CloseReason motivo) =>
        consentitaDaPactum || motivo == CloseReason.WindowsShutDown;

    /// <summary>
    /// (correzione 0.18) Un messaggio di fine sessione (<c>WM_QUERYENDSESSION</c>, <c>WM_ENDSESSION</c>) arrivato a una
    /// copertura va ignorato? Sì quando Windows non sta chiudendo la sessione: l'ha mandato un altro programma (per
    /// WM_ENDSESSION WinForms chiuderebbe la finestra senza chiedere a <see cref="ChiusuraPermessa"/>). Con lo
    /// spegnimento vero passa come sempre. Logica pura.
    /// </summary>
    public static bool MessaggioDaIgnorare(int messaggio, bool sessioneSiChiude) =>
        (messaggio == Win32.WM_QUERYENDSESSION || messaggio == Win32.WM_ENDSESSION) && !sessioneSiChiude;

    /// <summary>
    /// (correzione 0.18) La fine della sessione annunciata da Windows (SessionEnding/SessionEnded, WM_ENDSESSION che
    /// conferma) vale solo se la sessione si sta chiudendo davvero: un messaggio finto, o un installatore che chiude le
    /// app, non deve far mandare una sospensione (il server crederebbe il computer spento). Logica pura.
    /// </summary>
    public static bool FineSessioneVera(bool sessioneSiChiude) => sessioneSiChiude;

    /// <summary>
    /// (correzione 0.18) Una copertura viva va rifatta da capo? Logica pura.
    /// <list type="bullet">
    /// <item>è <b>nascosta da Windows</b> (DWM «cloaked»): è rimasta su un altro desktop virtuale (Win+Ctrl+D,
    /// Visualizzazione attività) e su quello attuale non si vede, anche se per Windows è «visibile». Una finestra nuova
    /// nasce sul desktop attuale;</item>
    /// <item>qualcuno le ha messo uno stile che la rende trasparente o lascia passare i clic (<c>WS_EX_LAYERED</c>,
    /// <c>WS_EX_TRANSPARENT</c>: le coperture di Pactum non li usano mai), o le ha ritagliato una forma (una regione).</item>
    /// </list>
    /// </summary>
    public static bool DaRifare(bool nascostaDaWindows, long stileEsteso, bool haRegione) =>
        nascostaDaWindows
        || (stileEsteso & (Win32.WS_EX_LAYERED | Win32.WS_EX_TRANSPARENT)) != 0
        || haRegione;

    /// <summary>
    /// Le coperture vive dopo un giro di controllo: quelle chiuse (distrutte) escono dalla lista, così la
    /// copertura che manca si rifà; quelle nascoste da fuori si rimostrano (senza rubare la tastiera).
    /// (correzione 0.18) Quelle da rifare (<see cref="DaRifare"/>: su un altro desktop virtuale, rese trasparenti) si
    /// chiudono ed escono dalla lista: chi chiama le rifà subito. Restituisce quante ne restano.
    /// </summary>
    public static int Ripulisci<T>(List<T> coperture) where T : Form, ICopertura =>
        Ripulisci(coperture, h => Win32.Nascosta(h), h => (long)Win32.GetWindowLongPtr(h, Win32.GWL_EXSTYLE), h => Win32.HaRegione(h));

    /// <summary>Come <see cref="Ripulisci{T}(List{T})"/>, con le letture di Windows passate da fuori (per i test).</summary>
    internal static int Ripulisci<T>(List<T> coperture, Func<IntPtr, bool> nascosta, Func<IntPtr, long> stile, Func<IntPtr, bool> regione)
        where T : Form, ICopertura
    {
        coperture.RemoveAll(f => f.IsDisposed || !f.IsHandleCreated);
        foreach (var f in coperture.ToList())
        {
            try
            {
                var h = f.Handle;
                if (DaRifare(nascosta(h), stile(h), regione(h)))
                {
                    Pactum.Motore.Log.Avviso("copertura nascosta o resa trasparente: la rifaccio");
                    coperture.Remove(f);
                    f.ConsentiChiusura();
                    f.Close();
                    f.Dispose();
                    continue;
                }
                if (!Win32.IsWindowVisible(h)) Win32.ShowWindow(h, Win32.SW_SHOWNOACTIVATE);
            }
            catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
            {
            }
        }
        return coperture.Count;
    }
}

/// <summary>(correzione 0.18) Una finestra di copertura (del blocco o dello Studio): la chiude solo Pactum.</summary>
public interface ICopertura
{
    /// <summary>Permette la chiusura programmata.</summary>
    void ConsentiChiusura();
}
