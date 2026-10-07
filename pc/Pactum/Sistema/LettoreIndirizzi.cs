using System.Runtime.InteropServices;
using System.Windows.Automation;
using Pactum.Nucleo;

namespace Pactum.Sistema;

/// <summary>
/// Il sito in primo piano: solo il dominio registrabile e la categoria del sito (null se non ne ha una),
/// oppure "non sono riuscito a leggere".
/// </summary>
/// <param name="SenzaSito">
/// (correzione 0.18) La barra si è letta ma non mostra un sito con un nome, e non è nemmeno una scheda nuova: un
/// indirizzo IP, un file aperto dal disco, una pagina interna come <c>edge://surf</c>, una pagina di un'estensione.
/// Fuori dallo Studio non cambia niente (non è un sito, non si registra); nello Studio copre.
/// </param>
public readonly record struct Lettura(string? Dominio, bool Fallita, string? CategoriaSito = null, bool SenzaSito = false)
{
    public static readonly Lettura NonLeggibile = new(null, true);

    /// <summary>
    /// (02/10) Dal testo della barra degli indirizzi al dominio registrabile e alla categoria del sito,
    /// decisa dal nome intero della pagina: <c>music.youtube.com</c> è musica, anche se il dominio è
    /// youtube.com (social). Il testo e il nome intero non escono da qui: si leggono e si buttano.
    /// </summary>
    public static Lettura DallaBarra(string? testo)
    {
        var nome = Domini.NomeDaBarraIndirizzi(testo);
        var dominio = nome == null ? null : Domini.DominioDellaPagina(nome);
        return dominio == null
            ? new Lettura(null, false, SenzaSito: !Domini.ÈSchedaNuova(testo))
            : new Lettura(dominio, false, Categorie.DelSito(nome!));
    }
}

/// <summary>
/// Legge con UI Automation la barra degli indirizzi del browser in primo piano e
/// ne tiene SOLO il dominio registrabile e la categoria del sito. Il testo della
/// barra non esce mai da <see cref="LeggiOra"/>: passa da <see cref="Lettura.DallaBarra"/>
/// e si butta. Niente log, niente file, niente eventi con l'indirizzo. Si cerca esattamente la
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
    private readonly Dictionary<IntPtr, Lettura> ultimaLettura = new();
    private readonly Dictionary<IntPtr, long> prossimaRicerca = new();
    private Task<Lettura>? inCorso;

    public static bool ÈBrowser(string exe) => Browser.Contains(exe);

    /// <summary>
    /// La lettura con un tempo massimo: un browser bloccato non deve fermare la misura.
    /// Se la lettura precedente non è ancora finita, questa vale come non riuscita.
    /// </summary>
    /// <param name="rigoroso">
    /// (0.18, contratto v4.0) Durante lo Studio: una barra che non si legge <b>adesso</b> è una lettura mancata — schermo
    /// intero/F11, cursore nella barra mentre si scrive, lettura fallita — senza il ripiego sull'ultimo sito letto (fuori
    /// dallo Studio resta la regola della v3: vale l'ultimo sito letto).
    /// </param>
    public Lettura Leggi(IntPtr hwnd, string exe, bool schermoIntero, bool rigoroso = false)
    {
        if (inCorso != null && !inCorso.IsCompleted) return Lettura.NonLeggibile;
        var compito = Task.Run(() => LeggiOra(hwnd, exe, schermoIntero, rigoroso));
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

    private Lettura LeggiOra(IntPtr hwnd, string exe, bool schermoIntero, bool rigoroso)
    {
        // (0.18) Nello Studio una finestra a schermo intero (F11, un video) non mostra la barra: non si legge adesso.
        if (rigoroso && schermoIntero) return Lettura.NonLeggibile;
        try
        {
            if (!barre.TryGetValue(hwnd, out var barra))
            {
                long adesso = Environment.TickCount64;
                if (prossimaRicerca.TryGetValue(hwnd, out var quando) && adesso < quando) return rigoroso ? Lettura.NonLeggibile : Ripiego(hwnd, schermoIntero);
                if (barre.Count > 64) Dimentica();
                barra = Trova(hwnd, exe);
                if (barra == null)
                {
                    prossimaRicerca[hwnd] = adesso + MsPausaDopoRicercaVana;
                    return rigoroso ? Lettura.NonLeggibile : Ripiego(hwnd, schermoIntero);
                }
                barre[hwnd] = barra;
                prossimaRicerca.Remove(hwnd);
            }

            // Il cursore è nella barra: la persona sta scrivendo, la pagina è ancora quella di prima.
            if (barra.GetCurrentPropertyValue(AutomationElement.HasKeyboardFocusProperty) is true)
            {
                // (0.18) Nello Studio il cursore nella barra copre: si potrebbe navigare altrove senza che si legga.
                if (rigoroso) return Lettura.NonLeggibile;
                return ultimaLettura.TryGetValue(hwnd, out var prima) ? prima : new Lettura(null, false);
            }

            var lettura = Lettura.DallaBarra(barra.GetCurrentPropertyValue(ValuePattern.ValueProperty) as string);
            ultimaLettura[hwnd] = lettura;
            return lettura;
        }
        catch (Exception e) when (e is ElementNotAvailableException or COMException or InvalidOperationException or ArgumentException or TimeoutException)
        {
            barre.Remove(hwnd);
            return rigoroso ? Lettura.NonLeggibile : Ripiego(hwnd, schermoIntero);
        }
    }

    /// <summary>
    /// A schermo intero (un video, F11) la barra sparisce ma la pagina è la stessa:
    /// vale l'ultimo dominio letto in quella finestra. Altrimenti è una lettura mancata.
    /// </summary>
    private Lettura Ripiego(IntPtr hwnd, bool schermoIntero) =>
        schermoIntero && ultimaLettura.TryGetValue(hwnd, out var l) && l.Dominio != null
            ? l
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
        ultimaLettura.Clear();
        prossimaRicerca.Clear();
    }
}
