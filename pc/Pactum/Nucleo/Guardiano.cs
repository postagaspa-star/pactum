using System.Text.Json.Nodes;

namespace Pactum.Nucleo;

/// <summary>Lo stato dell'attività pianificata «Pactum» (il guardiano) nell'Utilità di pianificazione dell'utente.</summary>
public enum StatoGuardiano
{
    /// <summary>C'è, è accesa e lancia il percorso giusto: niente da fare.</summary>
    Giusta,

    /// <summary>C'è e accesa, ma lancia un percorso diverso (il programma è stato spostato): si riscrive, senza accusare.</summary>
    DaRiparare,

    /// <summary>Non c'è (il figlio l'ha tolta).</summary>
    Mancante,

    /// <summary>C'è ma è disattivata.</summary>
    Disattivata,
}

/// <summary>Un errore nel creare o leggere l'attività pianificata, col codice da mettere nella manomissione.</summary>
public sealed class ErrorePianificatore : Exception
{
    public ErrorePianificatore(string codice, string messaggio) : base(messaggio) => Codice = codice;
    public string Codice { get; }
}

/// <summary>
/// (0.18, contratto v4.0) L'Utilità di pianificazione di Windows, dietro un'interfaccia: così la logica
/// del guardiano si prova con un finto, senza toccare le attività vere del PC.
/// </summary>
public interface IPianificatore
{
    /// <summary>Lo stato dell'attività «Pactum» adesso.</summary>
    StatoGuardiano Stato();

    /// <summary>Crea (o riscrive) l'attività «Pactum»: all'accesso e ogni minuto, senza limite di durata, senza amministratore. Lancia <see cref="ErrorePianificatore"/> se non ci riesce.</summary>
    void Crea();
}

/// <summary>
/// (0.18, contratto v4.0) Il guardiano: all'avvio e ogni 15 minuti controlla l'attività pianificata
/// «Pactum» e, se è stata tolta o disattivata, la ricrea. Logica pura (provata con un finto
/// <see cref="IPianificatore"/>): l'attività vera non si tocca nei test.
///
/// Gli avvisi al genitore (manomissione <c>guardiano_assente</c>):
/// <list type="bullet">
/// <item>trovata tolta o disattivata e ricreata: <c>{ sotto_tipo, stato: "mancante"|"disattivata" }</c>,
/// <b>una volta ogni volta che la trova tolta</b> (fronte: non si ripete finché resta tolta tra due controlli);</item>
/// <item>non riesce a ricrearla: <c>{ sotto_tipo, stato: "non_creata", errore }</c>, <b>al massimo una volta al giorno</b>;</item>
/// <item>c'è ma lancia un percorso vecchio (programma spostato): si riscrive <b>senza</b> alcun avviso.</item>
/// </list>
/// </summary>
public sealed class Guardiano
{
    public const string SottoTipo = "guardiano_assente";
    private const long UnGiornoMs = 24L * 60 * 60 * 1000;

    private readonly IPianificatore pianificatore;
    private readonly Func<long> adessoMs;
    private readonly MemoriaGuardiano memoria;
    private readonly Action<MemoriaGuardiano>? salva;

    // Fronte dell'avviso «mancante/disattivata»: resta vero solo se l'attività è ancora assente fra due controlli
    // (cioè se ricrearla non è riuscito). Separato da quello di «non_creata», che vive in <see cref="memoria"/>.
    private bool eraAssente;

    /// <param name="memoria">
    /// (correzione 0.18) Quello che il guardiano ricorda su disco (<c>guardiano.json</c>): se l'attività è già stata creata
    /// almeno una volta su questo account, e quando si è mandato l'ultimo <c>non_creata</c>. Senza memoria (prove della
    /// logica) si fa come se l'attività fosse già stata creata: ogni attività che manca è tolta.
    /// </param>
    /// <param name="salva">Scrive la memoria su disco quando cambia (al meglio possibile).</param>
    public Guardiano(IPianificatore pianificatore, Func<long>? adessoMs = null, MemoriaGuardiano? memoria = null, Action<MemoriaGuardiano>? salva = null)
    {
        this.pianificatore = pianificatore;
        this.adessoMs = adessoMs ?? Tempo.AdessoUtcMs;
        this.memoria = memoria ?? new MemoriaGuardiano { GiaCreata = true };
        this.salva = salva;
    }

    /// <summary>
    /// Il controllo (all'avvio e ogni 15 minuti): legge lo stato, ripara/ricrea quando serve, e restituisce
    /// i dettagli della manomissione da mandare al server, oppure <c>null</c> se non c'è niente da dire.
    /// </summary>
    public JsonObject? Controlla()
    {
        StatoGuardiano stato;
        try
        {
            stato = pianificatore.Stato();
        }
        catch (ErrorePianificatore e)
        {
            // Non si riesce nemmeno a leggere: trattalo come "non creata" (al massimo una al giorno).
            return SegnalaNonCreata(e.Codice);
        }

        if (stato == StatoGuardiano.Giusta)
        {
            eraAssente = false;
            SegnaCreata();
            return null;
        }

        if (stato == StatoGuardiano.DaRiparare)
        {
            // Il programma è stato spostato: si riscrive il percorso, senza accusare nessuno.
            try
            {
                pianificatore.Crea();
                eraAssente = false;
                SegnaCreata();
                return null;
            }
            catch (ErrorePianificatore e)
            {
                return SegnalaNonCreata(e.Codice);
            }
        }

        // Mancante o disattivata: la ricreo.
        var etichetta = stato == StatoGuardiano.Disattivata ? "disattivata" : "mancante";
        // (correzione 0.18) La prima creazione su questo account (passaggio dalla 0.14, account nuovo) non è una
        // manomissione: l'attività non è mai esistita. Un'attività disattivata invece c'è stata per forza.
        bool primaVolta = stato == StatoGuardiano.Mancante && !memoria.GiaCreata;
        JsonObject? avviso = null;
        if (!eraAssente && !primaVolta)
        {
            avviso = new JsonObject { ["sotto_tipo"] = SottoTipo, ["stato"] = etichetta };
        }
        try
        {
            pianificatore.Crea();
            // (correzione 0.18) Ricreata: il fronte si chiude. Se il figlio la toglie di nuovo prima del controllo
            // dopo, quel controllo la trova tolta un'altra volta e avvisa di nuovo («una volta ogni volta»).
            eraAssente = false;
            SegnaCreata();
            return avviso;
        }
        catch (ErrorePianificatore e)
        {
            // Non si riesce a ricrearla: resta assente fino al controllo dopo (lì niente secondo «mancante»). In più
            // c'è "non_creata" (al massimo una al giorno): si manda quello più informativo.
            eraAssente = true;
            return SegnalaNonCreata(e.Codice) ?? avviso;
        }
    }

    private void SegnaCreata()
    {
        if (memoria.GiaCreata) return;
        memoria.GiaCreata = true;
        Salva();
    }

    private JsonObject? SegnalaNonCreata(string codice)
    {
        long ora = adessoMs();
        // (correzione 0.18) L'ultima volta sta su disco: un Pactum riaperto più volte nello stesso giorno non ripete.
        if (memoria.UltimaNonCreataMs is long u && ora - u < UnGiornoMs && ora >= u) return null;
        memoria.UltimaNonCreataMs = ora;
        Salva();
        return new JsonObject { ["sotto_tipo"] = SottoTipo, ["stato"] = "non_creata", ["errore"] = codice };
    }

    private void Salva()
    {
        try
        {
            salva?.Invoke(memoria);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Al meglio possibile: al massimo si ripete un avviso, mai se ne inventa uno.
        }
    }
}

/// <summary>
/// (correzione 0.18) La memoria del guardiano su disco (<c>guardiano.json</c>, accanto a <c>vivo.json</c>). Si cancella
/// insieme ai dati: al massimo si perde un avviso, non se ne inventa uno.
/// </summary>
public sealed class MemoriaGuardiano
{
    /// <summary>L'attività «Pactum» è già stata creata (o trovata) almeno una volta su questo account.</summary>
    [System.Text.Json.Serialization.JsonPropertyName("gia_creata")] public bool GiaCreata { get; set; }

    /// <summary>Quando si è mandato l'ultimo <c>non_creata</c> (ms UTC): al massimo uno al giorno.</summary>
    [System.Text.Json.Serialization.JsonPropertyName("ultima_non_creata_ms")] public long? UltimaNonCreataMs { get; set; }
}

/// <summary>
/// (0.18, contratto v4.0) Il segno di un avviso già mandato in questa accensione di Windows
/// (<c>istanza-occupata.json</c>): <c>istanza_occupata</c> parte <b>una volta sola per accensione</b>, non a
/// ogni minuto, anche se il Pactum partito dal guardiano viene chiuso e il guardiano riparte.
/// </summary>
public sealed class SegnoAccensione
{
    /// <summary>Il contatore degli avvii di Windows (registro), se si legge.</summary>
    [System.Text.Json.Serialization.JsonPropertyName("boot_id")] public long? BootId { get; set; }

    /// <summary>L'ora dell'avvio di Windows (ms UTC: adesso meno il cronometro dall'avvio).</summary>
    [System.Text.Json.Serialization.JsonPropertyName("avvio_ms")] public long AvvioMs { get; set; }

    /// <summary>Due ore d'avvio a meno di 2 minuti l'una dall'altra sono la stessa accensione (l'orologio si sincronizza).</summary>
    private const long Tolleranza = 2 * 60_000;

    /// <summary>
    /// Il segno salvato è di questa stessa accensione? Col contatore degli avvii si confronta quello; senza, l'ora
    /// d'avvio (con 2 minuti di tolleranza). Logica pura.
    /// </summary>
    public static bool StessaAccensione(SegnoAccensione? salvato, long? bootId, long avvioMs)
    {
        if (salvato == null) return false;
        if (salvato.BootId is long prima && bootId is long adesso) return prima == adesso;
        return Math.Abs(salvato.AvvioMs - avvioMs) < Tolleranza;
    }
}
