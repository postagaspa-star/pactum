using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Pactum.Nucleo;

/// <summary>
/// L'orologio spostato a mano: a ogni giro confronta l'orologio di Windows con
/// un cronometro che non si può spostare (millisecondi dall'avvio di Windows).
/// Lo scarto si misura rispetto a un'ancora, quindi anche tanti piccoli spostamenti
/// si sommano finché l'ancora non viene rinnovata (a ogni battito consegnato, come
/// sul telefono: da lì fa fede l'orologio del server).
/// </summary>
public sealed class SentinellaOrologio
{
    /// <summary>Sotto i 2 minuti è la sincronizzazione automatica, non una mano.</summary>
    public const long SogliaMs = 2 * 60_000;

    private long ancoraUtc;
    private long ancoraMono;
    private string zona;

    public SentinellaOrologio(long utcMs, long monoMs, string zonaId)
    {
        ancoraUtc = utcMs;
        ancoraMono = monoMs;
        zona = zonaId;
    }

    /// <summary>I dettagli delle manomissioni da registrare (vuoto se è tutto a posto).</summary>
    public List<JsonObject> Controlla(long utcMs, long monoMs, string zonaId)
    {
        var eventi = new List<JsonObject>();
        long atteso = ancoraUtc + (monoMs - ancoraMono);
        long scarto = utcMs - atteso;
        if (Math.Abs(scarto) > SogliaMs)
        {
            // Il segno dice la direzione: positivo = orologio spostato avanti.
            eventi.Add(new JsonObject { ["sotto_tipo"] = "cambio_ora", ["drift_secondi"] = scarto / 1000 });
            Riancora(utcMs, monoMs);
        }
        if (!string.Equals(zonaId, zona, StringComparison.Ordinal))
        {
            eventi.Add(new JsonObject { ["sotto_tipo"] = "cambio_fuso", ["fuso"] = zonaId });
            zona = zonaId;
        }
        return eventi;
    }

    public void Riancora(long utcMs, long monoMs)
    {
        ancoraUtc = utcMs;
        ancoraMono = monoMs;
    }
}

/// <summary>Il "sono vivo" scritto ogni minuto in <c>vivo.json</c>.</summary>
public sealed class StatoVivo
{
    [JsonPropertyName("utc_ms")] public long UtcMs { get; set; }

    /// <summary>Millisecondi dall'avvio di Windows in quel momento (Environment.TickCount64).</summary>
    [JsonPropertyName("tick_ms")] public long TickMs { get; set; }

    /// <summary>Contatore degli avvii di Windows (registro), se leggibile.</summary>
    [JsonPropertyName("boot_id")] public long? BootId { get; set; }

    /// <summary>Come si è chiuso il programma: null = mai chiuso bene (ancora acceso o chiuso a forza).</summary>
    [JsonPropertyName("chiusura")] public string? Chiusura { get; set; }

    /// <summary>(0.13) Un blocco delle faccende era attivo all'ultimo "sono vivo": serve a <c>chiuso_durante_blocco</c>.</summary>
    [JsonPropertyName("bloccato_faccende")] public bool BloccatoFaccende { get; set; }
}

public static class Chiusure
{
    public const string Volontaria = "volontaria";
    public const string Spegnimento = "spegnimento";
    public const string Disconnessione = "disconnessione";
    public const string Aggiornamento = "aggiornamento";

    /// <summary>(0.13) Un errore imprevisto: una chiusura scritta nel gestore delle eccezioni, così al riavvio un crash non diventa una manomissione.</summary>
    public const string Crash = "crash";
}

/// <summary>Cosa dire al server quando il programma riparte.</summary>
/// <param name="ChiusoDuranteBlocco">
/// (0.13) Il programma di prima è stato chiuso di colpo (stesso avvio di Windows, chiusura non pulita)
/// mentre un blocco delle faccende era attivo: si manda in più una <c>manomissione chiuso_durante_blocco</c>.
/// Mai dopo uno spegnimento, una disconnessione o una sospensione (lì <see cref="ProgrammaChiuso"/> è già null).
/// </param>
public sealed record EsitoAvvio(bool StessoAvvioDiWindows, JsonObject? ProgrammaChiuso, JsonObject? CambioOra, string MotivoRipresa, long AvvioSistemaMs, bool ChiusoDuranteBlocco = false);

/// <summary>
/// Il programma chiuso a forza: al riavvio si confronta l'ultimo "sono vivo" con
/// l'avvio di Windows. Se Windows era acceso da prima dell'ultimo "sono vivo" e
/// non c'era stata una chiusura pulita, il programma è stato chiuso mentre Windows
/// era acceso: <c>manomissione {sotto_tipo: "programma_chiuso", dal, al}</c>.
/// </summary>
public static class Vivo
{
    /// <summary>Senza boot_id, due avvii di Windows calcolati a meno di 5 minuti sono lo stesso.</summary>
    public const long TolleranzaAvvioMs = 5 * 60_000;

    /// <summary>
    /// Se Windows è acceso da più di così e Pactum parte solo ora (tolto dall'avvio automatico
    /// o aperto tardi a mano), il tempo in cui il computer è stato acceso senza Pactum si registra.
    /// </summary>
    public const long AvvioTardivoMs = 10 * 60_000;

    /// <param name="spegnimentoPulitoMs">
    /// (0.13) L'ora dell'ultimo spegnimento pulito di Windows (<c>HKLM\…\Control\Windows\ShutdownTime</c>),
    /// in millisecondi UTC, o null se non si legge. Serve a riconoscere un programma chiuso di colpo durante
    /// un blocco e poi un riavvio/spegnimento: se Windows si è spento pulito DOPO l'ultimo "sono vivo", Pactum
    /// era già morto. Uno spegnimento non pulito (corrente, schermata blu) non aggiorna quel valore.
    /// </param>
    public static EsitoAvvio ValutaAvvio(StatoVivo? precedente, long utcAdesso, long tickAdesso, long? bootIdAdesso, long? spegnimentoPulitoMs = null)
    {
        long avvioAdesso = utcAdesso - tickAdesso;
        if (precedente == null) return new EsitoAvvio(false, null, null, "avvio", avvioAdesso);

        bool stesso;
        if (precedente.BootId != null && bootIdAdesso != null)
        {
            // Il contatore degli avvii non si sposta con l'orologio: è la prova migliore.
            stesso = precedente.BootId == bootIdAdesso && tickAdesso >= precedente.TickMs;
        }
        else
        {
            long avvioPrima = precedente.UtcMs - precedente.TickMs;
            stesso = tickAdesso >= precedente.TickMs && Math.Abs(avvioAdesso - avvioPrima) <= TolleranzaAvvioMs;
        }
        if (!stesso)
        {
            // Windows è ripartito e Pactum non c'era dall'accensione: se l'ultima chiusura era
            // pulita e il computer è acceso da più di dieci minuti, il tempo senza Pactum in questa
            // sessione (dall'accensione a ora) va nel registro. Così, se il figlio toglie Pactum
            // dall'avvio automatico, non risulta "spento" all'infinito.
            JsonObject? tardivo = null;
            bool chiusoDuranteBloccoCross = false;
            if (precedente.Chiusura != null && tickAdesso > AvvioTardivoMs)
            {
                tardivo = new JsonObject
                {
                    ["sotto_tipo"] = "programma_chiuso",
                    ["dal"] = avvioAdesso,
                    ["al"] = utcAdesso,
                    ["avvio_ritardato"] = true,
                };
            }
            else if (precedente.Chiusura == null && precedente.BloccatoFaccende
                     && spegnimentoPulitoMs is long spento && spento > precedente.UtcMs)
            {
                // (0.13) Chiuso di colpo durante un blocco, poi Windows si è spento PULITO dopo l'ultimo
                // "sono vivo": Pactum era già morto prima dello spegnimento. Il buco e la manomissione.
                // Se lo spegnimento non è stato pulito (corrente, schermata blu) ShutdownTime resta vecchio
                // (<= l'ultimo "sono vivo") e non si dice niente.
                tardivo = new JsonObject
                {
                    ["sotto_tipo"] = "programma_chiuso",
                    ["dal"] = precedente.UtcMs,
                    ["al"] = spento,
                };
                chiusoDuranteBloccoCross = true;
            }
            return new EsitoAvvio(false, tardivo, null, "avvio", avvioAdesso, chiusoDuranteBloccoCross);
        }

        JsonObject? chiuso = null;
        if (precedente.Chiusura == null)
        {
            chiuso = new JsonObject
            {
                ["sotto_tipo"] = "programma_chiuso",
                ["dal"] = precedente.UtcMs,
                ["al"] = utcAdesso,
            };
        }

        // Con Windows sempre acceso, orologio e cronometro devono essere andati avanti insieme.
        JsonObject? cambioOra = null;
        long scarto = utcAdesso - (precedente.UtcMs + (tickAdesso - precedente.TickMs));
        if (Math.Abs(scarto) > SentinellaOrologio.SogliaMs)
        {
            cambioOra = new JsonObject { ["sotto_tipo"] = "cambio_ora", ["drift_secondi"] = scarto / 1000 };
        }

        var motivo = precedente.Chiusura == Chiusure.Disconnessione ? "accesso" : "avvio";
        // (0.13) Chiuso di colpo (chiusura non pulita, stesso avvio di Windows) mentre un blocco era attivo.
        bool chiusoDuranteBlocco = chiuso != null && precedente.BloccatoFaccende;
        return new EsitoAvvio(true, chiuso, cambioOra, motivo, avvioAdesso, chiusoDuranteBlocco);
    }
}
