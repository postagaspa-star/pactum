using System.Runtime.InteropServices;
using System.Windows.Automation;
using Pactum.Nucleo;

namespace Pactum.Sistema;

/// <summary>Il sito in primo piano: solo il dominio registrabile, oppure "non sono riuscito a leggere".</summary>
public readonly record struct Lettura(string? Dominio, bool Fallita)
{
    public static readonly Lettura NonLeggibile = new(null, true);
}

/// <summary>
/// Legge con UI Automation la barra degli indirizzi del browser in primo piano e
/// ne tiene SOLO il dominio registrabile. Il testo della barra non esce mai da
/// <see cref="LeggiOra"/>: passa da <see cref="Domini.DaBarraIndirizzi"/> e si butta.
/// Niente log, niente file, niente eventi con l'indirizzo. Si cerca esattamente la
/// barra (Chromium: classe <c>OmniboxViewViews</c>; Firefox: <c>urlbar-input</c>):
/// mai un campo di testo qualsiasi, che potrebbe essere dentro una pagina.
/// </summary>
public sealed class LettoreIndirizzi
{
    private static readonly HashSet<string> Browser = new(StringComparer.Ordinal)
    {
        "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe",
    };

    private const int MsMassimiLettura = 800;
    private const long MsPausaDopoRicercaVana = 5_000;

    private readonly Dictionary<IntPtr, AutomationElement> barre = new();
    private readonly Dictionary<IntPtr, string?> ultimoDominio = new();
    private readonly Dictionary<IntPtr, long> prossimaRicerca = new();
    private Task<Lettura>? inCorso;

    public static bool ÈBrowser(string exe) => Browser.Contains(exe);

    /// <summary>
    /// La lettura con un tempo massimo: un browser bloccato non deve fermare la misura.
    /// Se la lettura precedente non è ancora finita, questa vale come non riuscita.
    /// </summary>
    public Lettura Leggi(IntPtr hwnd, string exe, bool schermoIntero)
    {
        if (inCorso != null && !inCorso.IsCompleted) return Lettura.NonLeggibile;
        var compito = Task.Run(() => LeggiOra(hwnd, exe, schermoIntero));
        inCorso = compito;
        try
        {
            return compito.Wait(MsMassimiLettura) ? compito.Result : Lettura.NonLeggibile;
        }
        catch (AggregateException)
        {
            return Lettura.NonLeggibile;
        }
    }

    private Lettura LeggiOra(IntPtr hwnd, string exe, bool schermoIntero)
    {
        try
        {
            if (!barre.TryGetValue(hwnd, out var barra))
            {
                long adesso = Environment.TickCount64;
                if (prossimaRicerca.TryGetValue(hwnd, out var quando) && adesso < quando) return Ripiego(hwnd, schermoIntero);
                if (barre.Count > 64) Dimentica();
                barra = Trova(hwnd, exe);
                if (barra == null)
                {
                    prossimaRicerca[hwnd] = adesso + MsPausaDopoRicercaVana;
                    return Ripiego(hwnd, schermoIntero);
                }
                barre[hwnd] = barra;
                prossimaRicerca.Remove(hwnd);
            }

            // Il cursore è nella barra: la persona sta scrivendo, la pagina è ancora quella di prima.
            if (barra.GetCurrentPropertyValue(AutomationElement.HasKeyboardFocusProperty) is true)
            {
                return new Lettura(ultimoDominio.GetValueOrDefault(hwnd), false);
            }

            var dominio = Domini.DaBarraIndirizzi(barra.GetCurrentPropertyValue(ValuePattern.ValueProperty) as string);
            ultimoDominio[hwnd] = dominio;
            return new Lettura(dominio, false);
        }
        catch (Exception e) when (e is ElementNotAvailableException or COMException or InvalidOperationException or ArgumentException or TimeoutException)
        {
            barre.Remove(hwnd);
            return Ripiego(hwnd, schermoIntero);
        }
    }

    /// <summary>
    /// A schermo intero (un video, F11) la barra sparisce ma la pagina è la stessa:
    /// vale l'ultimo dominio letto in quella finestra. Altrimenti è una lettura mancata.
    /// </summary>
    private Lettura Ripiego(IntPtr hwnd, bool schermoIntero) =>
        schermoIntero && ultimoDominio.TryGetValue(hwnd, out var d) && d != null
            ? new Lettura(d, false)
            : Lettura.NonLeggibile;

    private static AutomationElement? Trova(IntPtr hwnd, string exe)
    {
        var radice = AutomationElement.FromHandle(hwnd);
        if (exe == "firefox.exe")
        {
            var barra = radice.FindFirst(TreeScope.Descendants, new AndCondition(
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit),
                new PropertyCondition(AutomationElement.AutomationIdProperty, "urlbar-input")));
            if (barra != null) return barra;
            var navigazione = radice.FindFirst(TreeScope.Descendants, new AndCondition(
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ToolBar),
                new PropertyCondition(AutomationElement.AutomationIdProperty, "nav-bar")));
            return navigazione?.FindFirst(TreeScope.Descendants, new AndCondition(
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit),
                new PropertyCondition(AutomationElement.IsValuePatternAvailableProperty, true)));
        }
        // Chrome, Edge, Brave (Chromium): la barra ha la classe della vista, uguale in tutte le lingue.
        return radice.FindFirst(TreeScope.Descendants, new AndCondition(
            new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit),
            new PropertyCondition(AutomationElement.ClassNameProperty, "OmniboxViewViews")));
    }

    private void Dimentica()
    {
        barre.Clear();
        ultimoDominio.Clear();
        prossimaRicerca.Clear();
    }
}
