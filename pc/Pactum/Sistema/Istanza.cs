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
    private readonly EventWaitHandle esci;
    private readonly List<RegisteredWaitHandle> attese = new();
    private bool posseduto;

    public Istanza(string suffisso)
    {
        var nome = @"Local\Pactum.Computer" + suffisso;
        mutex = new Mutex(true, nome, out posseduto);
        apri = new EventWaitHandle(false, EventResetMode.AutoReset, nome + ".Apri");
        esci = new EventWaitHandle(false, EventResetMode.AutoReset, nome + ".Esci");
    }

    public bool Prima => posseduto;

    public event Action? RichiestaApertura;
    public event Action? RichiestaUscita;

    /// <summary>Da chiamare nella prima istanza, quando è pronta a rispondere.</summary>
    public void Ascolta()
    {
        attese.Add(ThreadPool.RegisterWaitForSingleObject(apri, (_, _) => RichiestaApertura?.Invoke(), null, Timeout.Infinite, executeOnlyOnce: false));
        attese.Add(ThreadPool.RegisterWaitForSingleObject(esci, (_, _) => RichiestaUscita?.Invoke(), null, Timeout.Infinite, executeOnlyOnce: false));
    }

    public void ChiediApertura() => apri.Set();

    public void ChiediUscita() => esci.Set();

    /// <summary>Aspetta che la prima istanza si chiuda e prende il suo posto.</summary>
    public bool AspettaIlPosto(TimeSpan tempo)
    {
        if (posseduto) return true;
        try
        {
            posseduto = mutex.WaitOne(tempo);
        }
        catch (AbandonedMutexException)
        {
            posseduto = true;
        }
        return posseduto;
    }

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
        esci.Dispose();
    }
}
