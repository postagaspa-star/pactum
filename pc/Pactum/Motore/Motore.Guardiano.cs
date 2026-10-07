using Pactum.Nucleo;

namespace Pactum.Motore;

/// <summary>
/// (0.18, contratto v4.0) Il guardiano lato motore: crea/ripara l'attività pianificata «Pactum» all'avvio
/// e ogni 15 minuti, e manda le manomissioni <c>guardiano_assente</c>. L'attività vera si tocca solo col
/// pacchetto vero: <see cref="Pianificatore"/> resta <c>null</c> nelle prove (nessuna attività reale).
/// </summary>
public sealed partial class Motore
{
    private Guardiano? guardiano;

    /// <summary>(0.18) L'Utilità di pianificazione dietro interfaccia; lo imposta <c>Program</c> solo dal pacchetto vero.</summary>
    public IPianificatore? Pianificatore { get; set; }

    /// <summary>
    /// (0.18) Il guardiano è partito perché il mutex era tenuto da un processo che non è Pactum: lo si dice
    /// <b>una volta sola per accensione</b> di Windows (il segno resta su disco: se questo Pactum viene chiuso e il
    /// guardiano riparte lo stesso, non si ripete).
    /// </summary>
    public void SegnalaIstanzaOccupata()
    {
        Log.Avviso("istanza_occupata: il guardiano è partito perché il mutex era tenuto da un processo che non è Pactum");
        if (!Abbinato) return;
        long adesso = Tempo.AdessoUtcMs();
        long? boot = IdAvvioWindows();
        long avvio = adesso - Environment.TickCount64;
        if (SegnoAccensione.StessaAccensione(Archivio.LeggiJson<SegnoAccensione>(percorsi.IstanzaOccupata), boot, avvio)) return;
        coda.Accoda(Eventi.IstanzaOccupata(adesso));
        try
        {
            Archivio.ScriviJson(percorsi.IstanzaOccupata, new SegnoAccensione { BootId = boot, AvvioMs = avvio });
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("istanza-occupata.json non scritto", e);
        }
    }

    private void AvviaGuardiano()
    {
        if (Pianificatore == null) return;
        guardiano ??= NuovoGuardiano(Pianificatore);
        // schtasks può metterci qualche secondo: mai sul filo della finestra.
        _ = Task.Run(ControllaGuardiano);
    }

    private readonly object guardianoLock = new();

    /// <summary>(correzione 0.18) Il guardiano con la sua memoria su disco (<c>guardiano.json</c>).</summary>
    private Guardiano NuovoGuardiano(IPianificatore p)
    {
        var memoria = Archivio.LeggiJson<MemoriaGuardiano>(percorsi.Guardiano) ?? new MemoriaGuardiano();
        return new Guardiano(p, null, memoria, m => Archivio.ScriviJson(percorsi.Guardiano, m));
    }

    /// <summary>Un controllo del guardiano (all'avvio e ogni 15 minuti): ripara/ricrea l'attività e, se serve, avvisa.</summary>
    internal void ControllaGuardiano()
    {
        if (guardiano == null && Pianificatore != null) guardiano = NuovoGuardiano(Pianificatore);
        if (guardiano == null) return;
        try
        {
            System.Text.Json.Nodes.JsonObject? dettagli;
            lock (guardianoLock) dettagli = guardiano.Controlla();
            if (dettagli == null) return;
            Log.Avviso($"attività pianificata: {Json.Testo(dettagli["stato"])}");
            if (Abbinato) coda.Accoda(Eventi.Manomissione(dettagli, Tempo.AdessoUtcMs()));
        }
        catch (Exception e)
        {
            Log.Errore("controllo del guardiano", e);
        }
    }
}
