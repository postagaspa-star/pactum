namespace Pactum.Sistema;

/// <summary>
/// (0.18, contratto v4.0) Un'istanza sola per utente: mutex con nome nella sessione (<c>Local\</c>).
/// <list type="bullet">
/// <item>La <b>prima istanza</b> si mette in ascolto <b>subito</b> (<see cref="Ascolta"/>, chiamata come prima
/// cosa in <c>Main</c>): risponde «sono vivo» a chi lo chiede (evento <c>.Chiedi</c> → <c>.Risposta</c>)
/// <b>senza aprire la finestra</b>, e apre la finestra solo su richiesta di un avvio a mano (<c>.Apri</c>).</item>
/// <item>Un <b>avvio a mano</b> (doppio clic) che trova un'istanza viva chiede di mostrarla (<see cref="ChiediApertura"/>) ed esce.</item>
/// <item>Un <b>guardiano</b> che trova il mutex preso chiede «sei vivo?» (<see cref="SonoVivo"/>): la risposta serve
/// solo a non aspettare. Decide se c'è un processo <c>Pactum.exe</c> vivo (v. <c>ArbitroIstanza</c>); se non c'è,
/// parte lo stesso, prova a prendere il <b>mutex di riserva</b> (<see cref="PrendiRiserva"/>) e da lì risponde
/// ai guardiani dei minuti dopo.</item>
/// </list>
/// Il mutex conta come «nostro» solo se lo possediamo davvero (<c>WaitOne(0)</c>), non se l'abbiamo solo creato:
/// un altro processo che tiene aperto un handle senza possederlo non ci ferma.
/// Se un oggetto con quei nomi esiste già ma non si apre (di un altro tipo, o con permessi ostili),
/// l'istanza è <see cref="Ostacolata"/>: niente eccezione, chi chiama lo tratta come un'istanza occupata da un
/// processo che non è Pactum.
/// Niente evento <c>.Esci</c> (0.13): un segnale con nome che chiunque fa scattare chiuderebbe Pactum senza traccia.
/// </summary>
public sealed class Istanza : IDisposable
{
    private readonly string nome;
    private readonly Mutex? mutex;
    private readonly EventWaitHandle? apri;
    private readonly EventWaitHandle? chiedi;
    private readonly EventWaitHandle? risposta;
    private readonly List<RegisteredWaitHandle> attese = new();
    private Mutex? riserva;
    private bool possiedoRiserva;
    private bool posseduto;
    private volatile bool aperturaInSospeso;

    public Istanza(string suffisso) : this(@"Local\Pactum.Computer" + suffisso, nomeCompleto: true)
    {
    }

    /// <summary>Per i test: un nome completo (mai quello vero di Pactum).</summary>
    internal Istanza(string nome, bool nomeCompleto)
    {
        _ = nomeCompleto;
        this.nome = nome;
        mutex = Apri(() => new Mutex(false, nome));
        if (mutex != null) posseduto = Possiedi(mutex);
        apri = Apri(() => new EventWaitHandle(false, EventResetMode.AutoReset, nome + ".Apri"));
        chiedi = Apri(() => new EventWaitHandle(false, EventResetMode.AutoReset, nome + ".Chiedi"));
        risposta = Apri(() => new EventWaitHandle(false, EventResetMode.AutoReset, nome + ".Risposta"));
        Ostacolata = mutex == null || apri == null || chiedi == null || risposta == null;
    }

    /// <summary>Questa è la prima istanza (possiede davvero il mutex principale).</summary>
    public bool Prima => posseduto;

    /// <summary>
    /// Un oggetto con uno di questi nomi esiste già e non si apre (di un altro tipo, o con permessi che non ce lo
    /// lasciano aprire): non può essere di Pactum. Chi chiama lo tratta come un'istanza occupata da un altro processo.
    /// </summary>
    public bool Ostacolata { get; }

    /// <summary>Un avvio a mano ha chiesto di aprire la finestra mentre nessuno ascoltava ancora: la si apre appena si può.</summary>
    public bool AperturaInSospeso => aperturaInSospeso;

    public event Action? RichiestaApertura;

    private static T? Apri<T>(Func<T> crea) where T : class
    {
        try
        {
            return crea();
        }
        catch (Exception e) when (e is WaitHandleCannotBeOpenedException or UnauthorizedAccessException or IOException)
        {
            return null;
        }
    }

    /// <summary>Prende il mutex senza aspettare. Un mutex abbandonato (il processo di prima è morto) è nostro.</summary>
    private static bool Possiedi(Mutex m)
    {
        try
        {
            return m.WaitOne(0);
        }
        catch (AbandonedMutexException)
        {
            return true;
        }
    }

    /// <summary>
    /// Da chiamare nell'istanza viva (la prima, o quella partita lo stesso), come prima cosa: risponde
    /// «sono vivo» a <c>.Chiedi</c> e apre la finestra a <c>.Apri</c>. Risponde su un filo del pool.
    /// Senza gli eventi (istanza ostacolata) non ascolta: i guardiani dopo riconoscono questo Pactum dal processo.
    /// </summary>
    public void Ascolta()
    {
        if (chiedi != null && risposta != null)
        {
            var r = risposta;
            attese.Add(ThreadPool.RegisterWaitForSingleObject(chiedi, (_, _) => r.Set(), null, Timeout.Infinite, executeOnlyOnce: false));
        }
        if (apri != null)
        {
            attese.Add(ThreadPool.RegisterWaitForSingleObject(apri, (_, _) => SuApri(), null, Timeout.Infinite, executeOnlyOnce: false));
        }
    }

    private void SuApri()
    {
        var h = RichiestaApertura;
        if (h != null) h.Invoke();
        else aperturaInSospeso = true;
    }

    /// <summary>Gli avvii a mano arrivati prima che la finestra fosse pronta: adesso che c'è un ascoltatore, si aprono.</summary>
    public void SvuotaAperturaInSospeso()
    {
        if (!aperturaInSospeso) return;
        aperturaInSospeso = false;
        RichiestaApertura?.Invoke();
    }

    /// <summary>Avvio a mano con un'istanza già viva: chiede di mostrare la finestra.</summary>
    public void ChiediApertura() => apri?.Set();

    /// <summary>
    /// Il guardiano chiede all'istanza viva: «sei vivo?». Mette l'evento e aspetta la risposta fino a
    /// <paramref name="attesa"/>. true se qualcuno ha risposto. La risposta da sola non prova che sia Pactum:
    /// serve solo a non aspettare inutilmente.
    /// </summary>
    public bool SonoVivo(TimeSpan attesa)
    {
        if (chiedi == null || risposta == null) return false;
        risposta.Reset();
        chiedi.Set();
        return risposta.WaitOne(attesa);
    }

    /// <summary>
    /// Il guardiano parte lo stesso (nessun Pactum.exe vivo): prova a prendere il mutex di riserva, da cui
    /// risponderà ai guardiani dei minuti dopo. false se non lo possiede (qualcun altro lo tiene, o non si apre):
    /// chi chiama parte lo stesso, perché nessun Pactum è vivo.
    /// </summary>
    public bool PrendiRiserva()
    {
        if (riserva != null) return possiedoRiserva;
        riserva = Apri(() => new Mutex(false, nome + ".Riserva"));
        possiedoRiserva = riserva != null && Possiedi(riserva);
        return possiedoRiserva;
    }

    public void Dispose()
    {
        foreach (var a in attese) a.Unregister(null);
        if (posseduto && mutex != null)
        {
            try { mutex.ReleaseMutex(); } catch (ApplicationException) { }
            posseduto = false;
        }
        if (riserva != null)
        {
            if (possiedoRiserva)
            {
                try { riserva.ReleaseMutex(); } catch (ApplicationException) { }
                possiedoRiserva = false;
            }
            riserva.Dispose();
        }
        mutex?.Dispose();
        apri?.Dispose();
        chiedi?.Dispose();
        risposta?.Dispose();
    }
}

/// <summary>
/// (0.18, contratto v4.0) Gli altri processi <c>Pactum.exe</c> vivi nella stessa sessione di Windows (lo stesso
/// account che ha fatto l'accesso), con la versione del loro file. Servono:
/// <list type="bullet">
/// <item>al guardiano, per non accusare quando il mutex lo tiene Pactum stesso (anche lento a rispondere);</item>
/// <item>all'avvio a mano, per il messaggio «È aperta una versione più vecchia di Pactum». La versione si legge dal
/// file dell'altro processo, mai da HKCU né da un file nei dati (l'account è del figlio: lì potrebbe scriverne una
/// finta). Funziona anche con la 0.13/0.14 già installata, che non scrive niente.</item>
/// </list>
/// <b>Limite noto</b> (contratto): un figlio con pieni diritti sul profilo potrebbe nominare «Pactum.exe» un altro
/// programma. La garanzia vera resta la rete di sicurezza lato server (il battito del computer).
/// </summary>
public static class AltriPactum
{
    /// <summary>C'è un altro <c>Pactum.exe</c> vivo, in questa sessione, oltre a questo processo? Mai un'eccezione.</summary>
    public static bool Vivo() => Versioni().Count > 0;

    /// <summary>Le versioni dei file degli altri <c>Pactum.exe</c> vivi in questa sessione (<c>null</c> se non si legge).</summary>
    public static IReadOnlyList<Version?> Versioni()
    {
        var esito = new List<Version?>();
        try
        {
            int mio = Environment.ProcessId;
            int sessione;
            using (var io = System.Diagnostics.Process.GetCurrentProcess()) sessione = io.SessionId;
            foreach (var p in System.Diagnostics.Process.GetProcessesByName("Pactum"))
            {
                using (p)
                {
                    try
                    {
                        if (p.Id == mio || p.SessionId != sessione) continue;
                        esito.Add(VersioneDi(p));
                    }
                    catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception)
                    {
                        // Uscito nel frattempo: non conta.
                    }
                }
            }
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
        }
        return esito;
    }

    private static Version? VersioneDi(System.Diagnostics.Process p)
    {
        try
        {
            var file = p.MainModule?.FileName;
            if (file == null) return null;
            var v = System.Diagnostics.FileVersionInfo.GetVersionInfo(file);
            return new Version(v.FileMajorPart, v.FileMinorPart, v.FileBuildPart);
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception or FileNotFoundException or ArgumentException)
        {
            return null;
        }
    }

    /// <summary>
    /// Un avvio a mano di questa versione deve dire «è aperta una versione più vecchia»? Sì se almeno un Pactum vivo
    /// ha una versione nota più bassa della mia. Una versione che non si legge non fa dire niente (si chiede solo di
    /// mostrarsi, come prima). Logica pura.
    /// </summary>
    public static bool UnaÈPiùVecchia(IEnumerable<Version?> vive, Version mia) =>
        vive.Any(v => v != null && v < mia);
}
