using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// (0.18, contratto v4.0, «Il mutex occupato da un altro processo») L'istanza unica con le domande «sei vivo?»,
/// l'apertura della finestra e il mutex di riserva. Sempre con <b>nomi di prova</b> unici: mai quelli veri di
/// Pactum (<c>Local\Pactum.Computer…</c>), così il Pactum che gira su questo PC non vede niente.
/// </summary>
public class IstanzaTest
{
    private static string NomeDiProva() => @"Local\PactumProvaTest." + Guid.NewGuid().ToString("N");

    /// <summary>
    /// Un'altra istanza «come da un altro processo»: un mutex di Windows è rientrante per filo, quindi le istanze che
    /// non devono possederlo si creano (e provano a prenderlo) su un filo loro, mai su quello del test.
    /// </summary>
    private static T SuAltroFilo<T>(Func<T> lavoro)
    {
        T esito = default!;
        Exception? errore = null;
        var filo = new Thread(() =>
        {
            try { esito = lavoro(); }
            catch (Exception e) { errore = e; }
        });
        filo.Start();
        filo.Join();
        if (errore != null) throw errore;
        return esito;
    }

    [Fact]
    public void La_prima_possiede_il_mutex_la_seconda_no()
    {
        var nome = NomeDiProva();
        using var prima = new Istanza(nome, nomeCompleto: true);
        using var seconda = SuAltroFilo(() => new Istanza(nome, nomeCompleto: true));
        Assert.True(prima.Prima);
        Assert.False(prima.Ostacolata);
        Assert.False(seconda.Prima);
    }

    [Fact]
    public void Chi_ascolta_risponde_sono_vivo_senza_aprire_la_finestra()
    {
        var nome = NomeDiProva();
        using var prima = new Istanza(nome, nomeCompleto: true);
        int aperture = 0;
        prima.RichiestaApertura += () => Interlocked.Increment(ref aperture);
        using var guardiano = SuAltroFilo(() => new Istanza(nome, nomeCompleto: true));

        // Prima di ascoltare non risponde nessuno.
        Assert.False(guardiano.SonoVivo(TimeSpan.FromMilliseconds(200)));
        prima.Ascolta();
        Assert.True(guardiano.SonoVivo(TimeSpan.FromSeconds(2)));
        Assert.True(guardiano.SonoVivo(TimeSpan.FromSeconds(2))); // e risponde ogni volta
        Thread.Sleep(100);
        Assert.Equal(0, aperture); // rispondere non apre la finestra
    }

    [Fact]
    public void Un_avvio_a_mano_chiede_di_aprire_la_finestra()
    {
        var nome = NomeDiProva();
        using var prima = new Istanza(nome, nomeCompleto: true);
        using var aperta = new ManualResetEventSlim();
        prima.RichiestaApertura += () => aperta.Set();
        prima.Ascolta();
        using var aMano = SuAltroFilo(() => new Istanza(nome, nomeCompleto: true));
        aMano.ChiediApertura();
        Assert.True(aperta.Wait(TimeSpan.FromSeconds(2)));
    }

    [Fact]
    public void Un_apertura_chiesta_prima_che_la_finestra_ci_sia_non_si_perde()
    {
        var nome = NomeDiProva();
        using var prima = new Istanza(nome, nomeCompleto: true);
        prima.Ascolta(); // ascolta subito, in Main, quando l'interfaccia non c'è ancora
        using var aMano = SuAltroFilo(() => new Istanza(nome, nomeCompleto: true));
        aMano.ChiediApertura();
        var limite = DateTime.UtcNow.AddSeconds(2);
        while (!prima.AperturaInSospeso && DateTime.UtcNow < limite) Thread.Sleep(20);
        Assert.True(prima.AperturaInSospeso);

        int aperture = 0;
        prima.RichiestaApertura += () => aperture++;
        prima.SvuotaAperturaInSospeso();
        Assert.Equal(1, aperture);
        Assert.False(prima.AperturaInSospeso);
    }

    [Fact]
    public void Il_mutex_di_riserva_lo_prende_uno_solo()
    {
        var nome = NomeDiProva();
        using var estraneo = new Istanza(nome, nomeCompleto: true); // tiene il mutex principale (filo del test, vivo)
        Assert.True(estraneo.Prima);
        // Due «processi» che partono lo stesso, ciascuno su un filo che resta vivo (un mutex di un filo finito è abbandonato).
        using var filoA = new FiloVivo();
        using var filoB = new FiloVivo();
        var partitoLoStesso = filoA.Fai(() => new Istanza(nome, nomeCompleto: true));
        var altro = filoB.Fai(() => new Istanza(nome, nomeCompleto: true));
        try
        {
            Assert.False(partitoLoStesso.Prima);
            Assert.True(filoA.Fai(() => partitoLoStesso.PrendiRiserva()));
            Assert.True(filoA.Fai(() => partitoLoStesso.PrendiRiserva())); // chiederlo di nuovo non cambia
            Assert.False(filoB.Fai(() => altro.PrendiRiserva()));
        }
        finally
        {
            filoB.Fai(() => { altro.Dispose(); return 0; });
            filoA.Fai(() => { partitoLoStesso.Dispose(); return 0; });
        }
    }

    /// <summary>Un filo che resta vivo e fa i lavori che gli si danno, uno alla volta (come un altro processo).</summary>
    private sealed class FiloVivo : IDisposable
    {
        private readonly System.Collections.Concurrent.BlockingCollection<Action> lavori = new();
        private readonly Thread filo;

        public FiloVivo()
        {
            filo = new Thread(() =>
            {
                foreach (var lavoro in lavori.GetConsumingEnumerable()) lavoro();
            }) { IsBackground = true };
            filo.Start();
        }

        public T Fai<T>(Func<T> lavoro)
        {
            T esito = default!;
            Exception? errore = null;
            using var fatto = new ManualResetEventSlim();
            lavori.Add(() =>
            {
                try { esito = lavoro(); }
                catch (Exception e) { errore = e; }
                finally { fatto.Set(); }
            });
            fatto.Wait();
            if (errore != null) throw errore;
            return esito;
        }

        public void Dispose()
        {
            lavori.CompleteAdding();
            filo.Join(2_000);
            lavori.Dispose();
        }
    }

    [Fact]
    public void Un_oggetto_con_quel_nome_di_un_altro_tipo_ostacola_senza_eccezioni()
    {
        var nome = NomeDiProva();
        // Un evento col nome del mutex: non si apre come mutex (lo farebbe uno script ostile).
        using var ostacolo = new EventWaitHandle(false, EventResetMode.ManualReset, nome);
        using var istanza = new Istanza(nome, nomeCompleto: true);
        Assert.True(istanza.Ostacolata);
        Assert.False(istanza.Prima);
    }

    [Fact]
    public void Chiusa_la_prima_il_mutex_torna_libero()
    {
        var nome = NomeDiProva();
        using (var prima = new Istanza(nome, nomeCompleto: true))
        {
            Assert.True(prima.Prima);
            Assert.False(SuAltroFilo(() => { using var intanto = new Istanza(nome, nomeCompleto: true); return intanto.Prima; }));
        }
        // Da un altro filo (come un altro processo) adesso si prende.
        Assert.True(SuAltroFilo(() => { using var dopo = new Istanza(nome, nomeCompleto: true); return dopo.Prima; }));
    }
}
