using System.Windows.Forms;
using Microsoft.Win32;
using Pactum.Nucleo;
using Pactum.Sistema;
using Log = Pactum.Motore.Log;

namespace Pactum.Interfaccia;

/// <summary>
/// (0.18, contratto v4.0) La copertura della Sessione Studio sul computer: copre, <b>per schermo</b>, quelli
/// su cui è visibile almeno una finestra fuori dalla lista dello Studio (un programma non permesso, o un
/// browser su un sito fuori lista o che non si legge). La copertura prende l'area di lavoro, lasciando
/// <b>libera la barra delle applicazioni</b>, sta sempre in primo piano e dice «Sei in Studio» con la lista.
/// Gli schermi senza finestre fuori lista restano liberi. Non chiude e non tocca le altre app.
///
/// Guardare le finestre (leggere le barre dei browser, le firme dei file) può prendere fino a un secondo: si fa
/// su un filo a parte, mai su quello della finestra; il filo della finestra applica solo il risultato. Un giro
/// ogni 300 ms: una copertura che manca (chiusa o nascosta da fuori) si rifà entro un secondo.
///
/// Diversa dal blocco (<see cref="GestoreBlocco"/>), che copre sempre tutti gli schermi, barra compresa.
/// </summary>
public sealed class GestoreStudio : IDisposable
{
    private readonly System.Windows.Forms.Timer timer;
    private readonly LettoreIndirizzi lettore = new();
    private readonly Dictionary<string, FinestraCoperturaStudio> coperture = new(StringComparer.Ordinal);
    private ConfigStudio? config;
    private long inizioMs;
    private Task<HashSet<string>?>? analisi;
    // L'ultimo esito dell'analisi (gli schermi da coprire): si riapplica a ogni giro.
    private HashSet<string>? ultimoEsito;

    public GestoreStudio()
    {
        timer = new System.Windows.Forms.Timer { Interval = 300 };
        timer.Tick += (_, _) => Giro();
        SystemEvents.DisplaySettingsChanged += SuSchermiCambiati;
    }

    public bool InStudio { get; private set; }

    /// <summary>Entra in Studio con questa lista (o aggiorna la lista): da qui copre gli schermi fuori lista.</summary>
    public void Studio(ConfigStudio? config, long inizioMs)
    {
        bool cambiata = !ReferenceEquals(this.config, config);
        this.config = config ?? new ConfigStudio();
        this.inizioMs = inizioMs;
        InStudio = true;
        // Una lista nuova: le coperture aperte si rifanno col testo nuovo al prossimo giro.
        if (cambiata)
        {
            ChiudiTutte();
            ultimoEsito = null;
        }
        timer.Start();
        Giro();
    }

    /// <summary>Esce dallo Studio: toglie ogni copertura.</summary>
    public void Esci()
    {
        InStudio = false;
        timer.Stop();
        ultimoEsito = null;
        ChiudiTutte();
    }

    /// <summary>
    /// Sul filo della finestra: applica l'ultima analisi finita (quali schermi coprire), rimette in cima le
    /// coperture e fa partire l'analisi dopo. Le coperture chiuse o nascoste da fuori si rifanno qui.
    /// </summary>
    private void Giro()
    {
        if (!InStudio || config == null) return;
        try
        {
            if (analisi != null && analisi.IsCompleted)
            {
                var daCoprire = analisi.Status == TaskStatus.RanToCompletion ? analisi.Result : null;
                analisi = null;
                if (daCoprire != null) ultimoEsito = daCoprire;
            }
            // Una nascosta da fuori si rimostra; (correzione 0.18) una rimasta su un altro desktop virtuale o resa
            // trasparente si chiude (Ripulisci) e si rifà qui sotto. Una chiusa (distrutta) esce dall'elenco.
            RegoleCopertura.Ripulisci(coperture.Values.ToList());
            foreach (var (nome, f) in coperture.ToList())
            {
                if (f.IsDisposed || !f.IsHandleCreated) coperture.Remove(nome);
            }
            // L'ultimo esito si applica a ogni giro (300 ms): una copertura che manca si rifà subito, senza
            // aspettare l'analisi dopo; quelle che ci sono tornano in cima.
            if (ultimoEsito != null)
            {
                Applica(ultimoEsito);
                PrimoPianoASchermoIntero(ultimoEsito);
            }

            if (analisi == null)
            {
                var lista = config;
                analisi = Task.Run(() => Analizza(lista));
            }
        }
        catch (Exception e)
        {
            Log.Errore("copertura dello Studio non aggiornata", e);
        }
    }

    /// <summary>Su un filo a parte: gli schermi (nome del dispositivo) su cui è visibile una finestra fuori lista.</summary>
    private HashSet<string>? Analizza(ConfigStudio lista)
    {
        try
        {
            // (correzione 0.18) Una finestra a cavallo di due schermi conta su tutti e due, non solo dove sta per più di metà.
            var schermi = Screen.AllScreens.Select(s => (s.DeviceName, s.Bounds)).ToList();
            var viste = PrimoPiano.Visibili().Select(v => (Vista: v, Studio: Osserva(v))).ToList();
            var perSchermo = DivisioneSchermi.Dividi(viste, x => x.Vista.Rettangolo, schermi)
                .ToDictionary(e => e.Key, e => e.Value.Select(x => x.Studio).ToList(), StringComparer.Ordinal);
            var esito = new HashSet<string>(StringComparer.Ordinal);
            foreach (var (schermo, finestre) in perSchermo)
            {
                if (CoperturaStudio.Copre(finestre, lista)) esito.Add(schermo);
            }
            return esito;
        }
        catch (Exception e)
        {
            Log.Errore("finestre dello Studio non guardate", e);
            return null;
        }
    }

    /// <summary>Una finestra vista → l'osservazione pura per la policy: browser col sito (lettura rigorosa), programma con la firma.</summary>
    private FinestraStudio Osserva(FinestraVista v)
    {
        if (LettoreIndirizzi.ÈBrowser(v.Exe))
        {
            // Nello Studio una barra che non si legge adesso (F11, cursore nella barra, lettura fallita) copre.
            var l = lettore.Leggi(v.Hwnd, v.Exe, v.SchermoIntero, rigoroso: true);
            return new FinestraStudio(v.Chiave, v.Exe, null, Browser: true, LetturaRiuscita: !l.Fallita, Dominio: l.Dominio, Percorso: v.Percorso,
                SenzaSito: l.SenzaSito);
        }
        // Un programma: firma e (correzione 0.18) nome originale del file, che riconosce un browser rinominato. Si
        // leggono per ogni programma fuori dalla cartella di Windows e restano in memoria per poco (v. Firma).
        if (CoperturaStudio.ÈSempreUsabile(v.Exe, v.Percorso)) return new FinestraStudio(v.Chiave, v.Exe, null, Percorso: v.Percorso);
        var info = Firma.Leggi(v.Percorso);
        return new FinestraStudio(v.Chiave, v.Exe, info?.Soggetto, Percorso: v.Percorso, NomeOriginale: info?.NomeOriginale);
    }

    /// <summary>
    /// (correzione 0.18) Come il blocco: se in primo piano c'è un'app a schermo intero su uno schermo da coprire, la
    /// copertura di quello schermo prende il primo piano (un gioco a schermo intero esclusivo terrebbe tastiera e mouse).
    /// Senza combattere Start o la barra, che non sono a schermo intero.
    /// </summary>
    private void PrimoPianoASchermoIntero(HashSet<string> daCoprire)
    {
        var primoPiano = Win32.GetForegroundWindow();
        if (primoPiano == IntPtr.Zero) return;
        if (coperture.Values.Any(f => !f.IsDisposed && f.IsHandleCreated && f.Handle == primoPiano)) return;
        if (!PrimoPiano.ÈaSchermoIntero(primoPiano)) return;
        var schermo = Screen.FromHandle(primoPiano).DeviceName;
        if (!daCoprire.Contains(schermo)) return;
        if (coperture.TryGetValue(schermo, out var copertura) && !copertura.IsDisposed) copertura.Attiva();
    }

    /// <summary>Sul filo della finestra: copre gli schermi dell'elenco, libera gli altri.</summary>
    private void Applica(HashSet<string> daCoprire)
    {
        foreach (var schermo in Screen.AllScreens)
        {
            if (daCoprire.Contains(schermo.DeviceName)) MostraCopertura(schermo);
            else ChiudiCopertura(schermo.DeviceName);
        }
        // Schermi spariti (cambio monitor): togli le loro coperture.
        foreach (var nome in coperture.Keys.ToList())
        {
            if (Screen.AllScreens.All(s => s.DeviceName != nome)) ChiudiCopertura(nome);
        }
    }

    private void MostraCopertura(Screen schermo)
    {
        if (coperture.TryGetValue(schermo.DeviceName, out var esistente) && !esistente.IsDisposed)
        {
            esistente.RiportaInCima();
            return;
        }
        try
        {
            // Area di lavoro, non i bordi dello schermo: la barra delle applicazioni resta libera.
            var f = new FinestraCoperturaStudio(schermo.WorkingArea, config!, inizioMs);
            coperture[schermo.DeviceName] = f;
            f.Show();
            f.RiportaInCima();
        }
        catch (Exception e)
        {
            Log.Errore("copertura dello Studio non aperta", e);
        }
    }

    private void ChiudiCopertura(string schermo)
    {
        if (!coperture.TryGetValue(schermo, out var f)) return;
        coperture.Remove(schermo);
        try { f.ConsentiChiusura(); f.Close(); f.Dispose(); }
        catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException) { }
    }

    private void ChiudiTutte()
    {
        foreach (var nome in coperture.Keys.ToList()) ChiudiCopertura(nome);
    }

    private void SuSchermiCambiati(object? mittente, EventArgs e)
    {
        // Gli schermi sono cambiati: le coperture si rifanno sulle aree nuove al prossimo giro.
        if (InStudio)
        {
            ChiudiTutte();
            ultimoEsito = null;
        }
    }

    public void Dispose()
    {
        SystemEvents.DisplaySettingsChanged -= SuSchermiCambiati;
        timer.Stop();
        timer.Dispose();
        ChiudiTutte();
    }
}
