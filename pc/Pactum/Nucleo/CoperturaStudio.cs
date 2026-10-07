namespace Pactum.Nucleo;

/// <summary>
/// (0.18, contratto v4.0) Una finestra visibile vista su uno schermo durante lo Studio, con quel poco
/// che serve a decidere se va coperta: il programma (chiave <c>exe:</c>), il percorso e la firma del file, e —
/// per un browser che Pactum sa leggere — il sito e se la barra si è letta adesso.
/// </summary>
/// <param name="Chiave">La chiave <c>exe:</c> del programma (es. <c>exe:winword.exe</c>, <c>exe:minecraft-java</c>).</param>
/// <param name="Exe">Il nome del file del programma, minuscolo (<c>chrome.exe</c>): per riconoscere i browser.</param>
/// <param name="FirmaSoggetto">Il soggetto (CN) di una firma Authenticode <b>valida</b> del file, o <c>null</c> se non firmato, firma non valida o non letto.</param>
/// <param name="Browser">È un browser di cui Pactum sa leggere la barra (Chrome, Edge, Firefox, Brave).</param>
/// <param name="LetturaRiuscita">Per un browser leggibile: la barra si è letta adesso (false = schermo intero/F11, cursore nella barra, lettura fallita).</param>
/// <param name="Dominio">Per un browser leggibile e letto: il dominio del sito, o <c>null</c> per una scheda nuova / pagina interna del browser.</param>
/// <param name="Percorso">Il percorso del file del programma, se si legge: i programmi «sempre usabili» valgono solo dalla cartella di Windows.</param>
/// <param name="NomeOriginale">(correzione 0.18) Il nome originale del file (<c>OriginalFilename</c>, coperto dalla firma in un file firmato), o <c>null</c>: un browser rinominato si riconosce da qui.</param>
/// <param name="SenzaSito">(correzione 0.18) Per un browser letto: la barra mostra qualcosa che non è un sito con un nome e non è una scheda nuova (indirizzo IP, <c>file://</c>, pagina interna come <c>edge://surf</c>, estensione).</param>
public sealed record FinestraStudio(
    string? Chiave,
    string Exe,
    string? FirmaSoggetto,
    bool Browser = false,
    bool LetturaRiuscita = false,
    string? Dominio = null,
    string? Percorso = null,
    string? NomeOriginale = null,
    bool SenzaSito = false);

/// <summary>
/// (0.18, contratto v4.0) La copertura dello Studio sul computer, logica pura (provata nei test). Decide,
/// per ogni schermo, se va coperto: uno schermo si copre se ci è visibile <b>almeno una</b> finestra fuori
/// dalla lista dello Studio (un programma non permesso, o un browser su un sito fuori lista o che non si
/// legge). Gli schermi senza finestre fuori lista restano liberi. La copertura prende lo schermo, non la
/// singola finestra (correzione del contratto v4.0: la copertura per finestra è stata scartata).
///
/// Sempre usabili, senza coprire: Pactum stesso (le sue finestre non si guardano nemmeno); il desktop, la
/// barra, Start ed Esplora file (<c>explorer.exe</c>); le Impostazioni; le finestre di sistema (permessi,
/// blocco schermo). Valgono <b>solo se il file è nella cartella di Windows</b>, dove il figlio non può
/// scrivere: un gioco rinominato <c>explorer.exe</c> nella sua cartella non passa. <b>Gestione attività è
/// coperta.</b> I browser che Pactum sa leggere valgono solo sui siti <c>sito:</c> della lista; un browser che
/// non sa leggere, o una barra non letta adesso, coprono, anche se qualcuno l'avesse messo in lista.
/// </summary>
public static class CoperturaStudio
{
    /// <summary>Sempre usabili durante lo Studio: non fanno coprire lo schermo. Gestione attività (<c>taskmgr.exe</c>) NON è qui: è coperta.</summary>
    private static readonly HashSet<string> SempreUsabili = new(StringComparer.OrdinalIgnoreCase)
    {
        "explorer.exe",               // desktop, barra, Start, Esplora file
        "lockapp.exe",                // schermo di blocco
        "systemsettings.exe",         // Impostazioni
        "shellexperiencehost.exe",    // interfaccia di sistema (centro notifiche, ecc.)
        "searchhost.exe",             // la ricerca di Start: i risultati web aprono il browser, e lì valgono le regole dei siti
        "searchapp.exe",              // la stessa ricerca, su Windows 10
        "startmenuexperiencehost.exe",
        "textinputhost.exe",          // tastiera a schermo / IME
        "consent.exe",                // la finestra dei permessi (UAC)
        "credentialuibroker.exe",     // la richiesta di una password di Windows
        "dwm.exe",
    };

    /// <summary>
    /// I browser (contratto v4.0, parte C: l'elenco del server, più largo dei quattro che Pactum sa leggere). Uno
    /// che Pactum non sa leggere è sempre coperto durante lo Studio, anche se fosse in lista come <c>exe:</c>.
    /// (correzione 0.18) Uguale all'elenco <c>BROWSER</c> del server (<c>server/app/studio.py</c>), che ne ha più dei
    /// 13 «almeno» del contratto: se qui ne mancasse uno, la finestra lo proporrebbe fra i programmi e il server
    /// rifiuterebbe la lista. Lo stesso elenco è in <c>ui/app.js</c> (<c>BROWSER_STUDIO</c>).
    /// </summary>
    private static readonly HashSet<string> TuttiIBrowser = new(StringComparer.OrdinalIgnoreCase)
    {
        "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "opera_gx.exe", "vivaldi.exe",
        "arc.exe", "chromium.exe", "iexplore.exe", "waterfox.exe", "librewolf.exe", "tor.exe",
        "yandex.exe", "browser.exe", "seamonkey.exe", "palemoon.exe", "floorp.exe", "thorium.exe", "zen.exe",
        "maxthon.exe", "duckduckgo.exe",
    };

    /// <summary>L'elenco dei browser (nomi dei file, minuscoli): per i test che lo confrontano con l'interfaccia.</summary>
    public static IReadOnlyCollection<string> Browser => TuttiIBrowser;

    /// <summary>
    /// Coperti sempre durante lo Studio, anche se qualcuno li avesse messi in lista: Gestione attività (contratto
    /// v4.0: «Gestione attività è coperta»; da lì si fermerebbe Pactum senza passare dal menu).
    /// </summary>
    private static readonly HashSet<string> SempreCoperti = new(StringComparer.OrdinalIgnoreCase)
    {
        "taskmgr.exe",
    };

    /// <summary>La cartella di Windows (<c>C:\Windows</c>): ci scrive solo un amministratore.</summary>
    public static string CartellaWindows { get; set; } = Environment.GetFolderPath(Environment.SpecialFolder.Windows);

    /// <summary>È un browser (dell'elenco largo del contratto)? Non si può proporre come programma della lista.</summary>
    public static bool ÈBrowser(string exe) => TuttiIBrowser.Contains(exe);

    /// <summary>
    /// (correzione 0.18) Il nome originale del file (<c>OriginalFilename</c>) è quello di un browser dell'elenco largo?
    /// Un browser copiato e rinominato (<c>msedge.exe</c> → <c>winword.exe</c>) lo dice ancora qui.
    /// </summary>
    public static bool ÈBrowserDiNascita(string? nomeOriginale) =>
        nomeOriginale != null && TuttiIBrowser.Contains(NomeExe(nomeOriginale));

    /// <summary>
    /// (correzione 0.18) Il nome originale va d'accordo col nome del programma in lista? Sì se manca (molti file non lo
    /// dicono) o se è lo stesso nome, senza badare a maiuscole ed estensione (<c>WinWord.exe</c> = <c>exe:winword.exe</c>).
    /// </summary>
    public static bool StessoNomeOriginale(string? nomeOriginale, string exe)
    {
        if (string.IsNullOrWhiteSpace(nomeOriginale)) return true;
        return string.Equals(SenzaEstensione(NomeExe(nomeOriginale)), SenzaEstensione(exe), StringComparison.OrdinalIgnoreCase);
    }

    private static string NomeExe(string nome)
    {
        var n = nome.Trim().ToLowerInvariant();
        // Qualche file scrive anche ".mui" in fondo (msedge.exe.mui): conta il nome del programma.
        if (n.EndsWith(".mui", StringComparison.Ordinal)) n = n[..^4];
        return n.EndsWith(".exe", StringComparison.Ordinal) ? n : n + ".exe";
    }

    private static string SenzaEstensione(string exe) =>
        exe.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? exe[..^4] : exe;

    /// <summary>Un programma sempre usabile: nome giusto <b>e</b> file nella cartella di Windows.</summary>
    public static bool ÈSempreUsabile(string exe, string? percorso) =>
        SempreUsabili.Contains(exe) && NellaCartellaDiWindows(percorso);

    private static bool NellaCartellaDiWindows(string? percorso)
    {
        if (string.IsNullOrEmpty(percorso) || string.IsNullOrEmpty(CartellaWindows)) return false;
        var cartella = CartellaWindows.TrimEnd('\\', '/') + "\\";
        return percorso!.StartsWith(cartella, StringComparison.OrdinalIgnoreCase) && !percorso.Contains("..", StringComparison.Ordinal);
    }

    /// <summary>Uno schermo con queste finestre visibili va coperto durante lo Studio?</summary>
    public static bool Copre(IEnumerable<FinestraStudio> finestreVisibili, ConfigStudio config)
    {
        var esercizi = config.Esercizi();
        var siti = config.Siti();
        return finestreVisibili.Any(f => FinestraDaCoprire(f, esercizi, siti, config.Firme));
    }

    /// <summary>Questa singola finestra fa coprire lo schermo?</summary>
    public static bool FinestraDaCoprire(FinestraStudio f, ConfigStudio config) =>
        FinestraDaCoprire(f, config.Esercizi(), config.Siti(), config.Firme);

    private static bool FinestraDaCoprire(FinestraStudio f, HashSet<string> esercizi, HashSet<string> siti, IReadOnlyDictionary<string, string> firme)
    {
        // Gestione attività: coperta sempre, anche se fosse in lista.
        if (SempreCoperti.Contains(f.Exe)) return true;

        // Sempre usabili (sistema, Esplora file, Impostazioni), dalla cartella di Windows: mai coprono.
        if (ÈSempreUsabile(f.Exe, f.Percorso)) return false;

        // I browser che Pactum sa leggere: valgono solo sui siti della lista.
        if (f.Browser)
        {
            // Barra non letta adesso (schermo intero/F11, cursore nella barra, lettura fallita): copre.
            if (!f.LetturaRiuscita) return true;
            // (correzione 0.18) Qualcosa che non è un sito con un nome e non è una scheda nuova (un indirizzo IP, un file
            // aperto dal disco, un gioco del browser come edge://surf, una pagina di un'estensione): ci si è arrivati
            // navigando, quindi copre.
            if (f.Dominio == null && f.SenzaSito) return true;
            // Scheda nuova (nessun sito): usabile finché non parte una navigazione.
            if (f.Dominio == null) return false;
            // Un sito: usabile solo se è nella lista.
            return !siti.Contains(f.Dominio);
        }

        // Un browser che Pactum non sa leggere (Opera, Vivaldi, Tor…): sempre coperto, come un sito mai letto.
        if (ÈBrowser(f.Exe)) return true;
        // (correzione 0.18) Un browser rinominato (il nome originale del file è quello di un browser): Pactum non ne legge
        // la barra, quindi è coperto come un browser che non sa leggere, anche se il nuovo nome è in lista.
        if (ÈBrowserDiNascita(f.NomeOriginale)) return true;

        // Un programma normale: deve essere nella lista; se ha una firma, il file deve avere quella firma.
        var chiave = f.Chiave?.Trim().ToLowerInvariant();
        if (chiave == null || !esercizi.Contains(chiave)) return true;
        if (firme.TryGetValue(chiave, out var soggetto))
        {
            // Voce firmata: conta solo un file con una firma valida di quel soggetto (contro il rinomina-exe), e (correzione
            // 0.18) il cui nome originale, coperto dalla firma, è quello della voce: un altro programma dello stesso
            // produttore rinominato winword.exe non passa.
            return !string.Equals(f.FirmaSoggetto, soggetto, StringComparison.Ordinal)
                   || !StessoNomeOriginale(f.NomeOriginale, chiave[Programma.Prefisso.Length..]);
        }
        // Voce senza firma: si riconosce dal solo nome (limite noto).
        return false;
    }
}

/// <summary>
/// (correzione 0.18) Su quali schermi sta una finestra: tutti quelli con cui il suo rettangolo visibile ha in comune
/// almeno <see cref="SogliaPx"/> pixel per lato. Una finestra a cavallo di due schermi fa coprire tutti e due (il
/// contratto copre «ogni schermo su cui è visibile una finestra fuori lista»), non solo quello dove sta per più di
/// metà. Logica pura.
/// </summary>
public static class DivisioneSchermi
{
    /// <summary>Sotto questa sovrapposizione (in pixel) la finestra non si considera su quello schermo: un bordo che sfora appena.</summary>
    public const int SogliaPx = 16;

    /// <summary>I nomi degli schermi su cui è visibile la finestra.</summary>
    public static List<string> Schermi(System.Drawing.Rectangle finestra, IEnumerable<(string Nome, System.Drawing.Rectangle Area)> schermi)
    {
        var esito = new List<string>();
        foreach (var (nome, area) in schermi)
        {
            var comune = System.Drawing.Rectangle.Intersect(finestra, area);
            if (comune.Width >= SogliaPx && comune.Height >= SogliaPx) esito.Add(nome);
        }
        return esito;
    }

    /// <summary>Le finestre divise per schermo: una finestra a cavallo compare in tutti gli schermi che tocca.</summary>
    public static Dictionary<string, List<T>> Dividi<T>(IEnumerable<T> finestre, Func<T, System.Drawing.Rectangle> rettangolo,
        IReadOnlyList<(string Nome, System.Drawing.Rectangle Area)> schermi)
    {
        var perSchermo = new Dictionary<string, List<T>>(StringComparer.Ordinal);
        foreach (var f in finestre)
        {
            foreach (var nome in Schermi(rettangolo(f), schermi))
            {
                if (!perSchermo.TryGetValue(nome, out var lista)) perSchermo[nome] = lista = new List<T>();
                lista.Add(f);
            }
        }
        return perSchermo;
    }
}

/// <summary>(0.18, contratto v4.0) Come conta il tempo di una finestra in primo piano durante lo Studio.</summary>
public enum UsoStudio
{
    /// <summary>Un programma o un sito della lista dello Studio: il tempo <b>non conta</b> per limiti, categorie, totale e fasce; va in <c>sessioni_minuti</c>.</summary>
    Lista,

    /// <summary>Sempre usabile (Esplora file, Impostazioni, una scheda nuova del browser): il tempo conta come sempre.</summary>
    SempreUsabile,

    /// <summary>Fuori lista (lo schermo si copre): il tempo conta come sempre.</summary>
    FuoriLista,
}

public static class ConteggioStudio
{
    /// <summary>
    /// Come conta il tempo della finestra in primo piano durante lo Studio (contratto v4.0, «Cosa conta durante
    /// lo Studio»): nella lista non conta, fuori conta sempre. Una scheda nuova o una pagina interna del browser
    /// è usabile ma non è nella lista: conta come sempre. Logica pura.
    /// </summary>
    public static UsoStudio Classifica(FinestraStudio f, ConfigStudio config)
    {
        if (CoperturaStudio.FinestraDaCoprire(f, config)) return UsoStudio.FuoriLista;
        if (CoperturaStudio.ÈSempreUsabile(f.Exe, f.Percorso)) return UsoStudio.SempreUsabile;
        if (f.Browser) return f.Dominio != null ? UsoStudio.Lista : UsoStudio.SempreUsabile;
        var chiave = f.Chiave?.Trim().ToLowerInvariant();
        return chiave != null && config.Esercizi().Contains(chiave) ? UsoStudio.Lista : UsoStudio.SempreUsabile;
    }

    /// <summary>
    /// (0.18, contratto v4.0) L'osservazione di un giro di misura durante lo Studio: se la finestra in primo piano è
    /// un programma o un sito della lista, il tempo si segna come Studio (non conta per limiti, categorie, totale e
    /// fasce, va in <c>sessioni_minuti</c>); altrimenti l'osservazione resta com'è e conta come sempre. Logica pura.
    /// </summary>
    public static Osservazione Applica(Osservazione o, FinestraStudio f, ConfigStudio config) =>
        o.Attivo && Classifica(f, config) == UsoStudio.Lista ? o with { Studio = true } : o;
}
