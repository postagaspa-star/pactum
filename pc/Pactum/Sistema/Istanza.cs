namespace Pactum.Sistema;

/// <summary>
/// Un'istanza sola per utente: mutex con nome nella sessione (<c>Local\</c>). Chi
/// arriva secondo chiede alla prima di aprire la finestra (o, per un
/// aggiornamento, di chiudersi) e se ne va.
/// </summary>
public sealed class Istanza : IDisposable
{
    private readonly Mutex mutex;
    private readonly EventWaitHandle apri;
    private readonly List<RegisteredWaitHandle> attese = new();
    private bool posseduto;

    public Istanza(string suffisso)
    {
        var nome = @"Local\Pactum.Computer" + suffisso;
        mutex = new Mutex(true, nome, out posseduto);
        apri = new EventWaitHandle(false, EventResetMode.AutoReset, nome + ".Apri");
        // (0.13) Niente evento ".Esci": era un segnale con nome che chiunque, nello stesso account, poteva far
        // scattare (il codice è pubblico) per far chiudere Pactum come "aggiornamento" pulito, senza traccia.
        // Non lo usava nessuno: tolto. Chi vuole chiudere Pactum usa il menu, o il Task Manager (che lascia traccia).
    }

    public bool Prima => posseduto;

    public event Action? RichiestaApertura;

    /// <summary>Da chiamare nella prima istanza, quando è pronta a rispondere.</summary>
    public void Ascolta()
    {
        attese.Add(ThreadPool.RegisterWaitForSingleObject(apri, (_, _) => RichiestaApertura?.Invoke(), null, Timeout.Infinite, executeOnlyOnce: false));
    }

    public void ChiediApertura() => apri.Set();

    public void Dispose()
    {
        foreach (var a in attese) a.Unregister(null);
        if (posseduto)
        {
            try
            {
                mutex.ReleaseMutex();
            }
            catch (ApplicationException)
            {
            }
            posseduto = false;
        }
        mutex.Dispose();
        apri.Dispose();
    }
}
