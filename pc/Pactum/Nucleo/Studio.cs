using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Pactum.Nucleo;

/// <summary>
/// (0.18, contratto v4.0) Una partenza automatica della Sessione Studio, come la manda il server in
/// <c>prossime_partenze</c>: il giorno locale, l'istante d'inizio (ora del server), da quando è
/// chiudibile e i minuti minimi. Il computer parte da solo a <see cref="InizioMs"/>, anche senza rete.
/// </summary>
public sealed class PartenzaStudio
{
    [JsonPropertyName("giorno")] public string Giorno { get; set; } = "";
    [JsonPropertyName("inizio_ms")] public long InizioMs { get; set; }
    [JsonPropertyName("chiudibile_dal_ms")] public long? ChiudibileDalMs { get; set; }
    [JsonPropertyName("minuti_minimi")] public int MinutiMinimi { get; set; }
}

/// <summary>
/// (0.18, contratto v4.0) La configurazione approvata della Sessione Studio, con la sola parte che serve
/// al computer: la lista dei programmi e dei siti permessi e le loro firme. Gli orari li calcola già il
/// server (<see cref="PartenzaStudio"/>): il computer non li ricalcola.
/// </summary>
public sealed class ConfigStudio
{
    /// <summary>Le chiavi permesse: <c>exe:winword.exe</c>, <c>sito:classeviva.it</c>.</summary>
    [JsonPropertyName("programmi")] public List<string> Programmi { get; set; } = new();

    /// <summary>Per una voce <c>exe:</c>, il soggetto (CN) della firma Authenticode con cui deve essere firmata.</summary>
    [JsonPropertyName("firme")] public Dictionary<string, string> Firme { get; set; } = new();

    /// <summary>I nomi leggibili delle chiavi (per la finestra), facoltativi.</summary>
    [JsonPropertyName("nomi")] public Dictionary<string, string> Nomi { get; set; } = new();

    /// <summary>I programmi permessi (chiavi <c>exe:</c>), in minuscolo.</summary>
    public HashSet<string> Esercizi() =>
        Programmi.Where(k => k.StartsWith(Programma.Prefisso, StringComparison.Ordinal))
            .Select(k => k.Trim().ToLowerInvariant()).ToHashSet(StringComparer.Ordinal);

    /// <summary>I domini permessi (dalle chiavi <c>sito:</c>), già come dominio registrabile.</summary>
    public HashSet<string> Siti() =>
        Programmi.Where(k => k.StartsWith(Programma.PrefissoSito, StringComparison.Ordinal))
            .Select(k => Domini.DominioDellaPagina(k[Programma.PrefissoSito.Length..]) ?? k[Programma.PrefissoSito.Length..])
            .Where(d => d.Length > 0).ToHashSet(StringComparer.Ordinal);
}

/// <summary>
/// (0.18, contratto v4.0) Lo stato della Sessione Studio salvato sul computer (<c>studio.json</c>): la
/// configurazione approvata, lo Studio in corso secondo il server (id e inizio) e le prossime partenze.
/// Da solo sa dire se adesso si è in Studio, anche senza rete (una partenza passata dopo l'ultima
/// risposta del server), e quando parte la prossima.
///
/// La <b>chiusura</b> la sa solo dal server (contratto: «la chiusura la sa solo dal server»): finché il
/// server dice <c>in_corso</c> con quell'inizio, si resta in Studio; quando il server lo dà chiuso (o
/// passa la mezzanotte, o arriva un <c>401</c>) si esce. Senza rete lo Studio continua fino a mezzanotte.
/// </summary>
public sealed class StatoStudio
{
    /// <summary>La configurazione approvata (<c>null</c> se non c'è niente di approvato).</summary>
    [JsonPropertyName("config")] public ConfigStudio? Config { get; set; }

    /// <summary>Lo Studio in corso secondo l'ultima risposta del server: <c>true</c> se il server lo dà aperto.</summary>
    [JsonPropertyName("in_corso_server")] public bool InCorsoServer { get; set; }

    /// <summary>L'id dello Studio in corso secondo il server (<c>null</c> se non c'è o se è partito solo in locale).</summary>
    [JsonPropertyName("id_server")] public long? IdServer { get; set; }

    /// <summary>L'inizio dello Studio in corso secondo il server (ms UTC).</summary>
    [JsonPropertyName("inizio_server_ms")] public long InizioServerMs { get; set; }

    /// <summary>
    /// Le liste <b>congelate</b> dello Studio in corso secondo il server (<c>in_corso.liste.computer</c>): lo Studio
    /// aperto tiene le liste con cui è partito, anche se intanto un genitore ne approva di nuove (quelle valgono
    /// dalla partenza dopo). <c>null</c> se il server non le ha dette: allora vale la configurazione approvata.
    /// </summary>
    [JsonPropertyName("liste_in_corso")] public ConfigStudio? ListeInCorso { get; set; }

    /// <summary>Le prossime partenze automatiche, dalla più vicina; <c>[]</c> se non c'è un telefono 0.18.</summary>
    [JsonPropertyName("partenze")] public List<PartenzaStudio> Partenze { get; set; } = new();

    /// <summary>Quando è arrivata l'ultima risposta del server con queste informazioni (ms UTC): per sapere quali partenze il server non conosceva ancora.</summary>
    [JsonPropertyName("ultima_risposta_ms")] public long UltimaRispostaMs { get; set; }

    public static StatoStudio Vuoto => new();

    /// <summary>
    /// (correzione 0.18) Lo scarto fra l'ora agganciata del computer e quella del server che si tollera alla partenza (3 s):
    /// una risposta arrivata entro 3 secondi dopo una partenza può essere stata decisa dal server prima di quella partenza.
    /// </summary>
    public const long MargineServerMs = 3_000;

    /// <summary>
    /// Aggiorna solo la parte «Studio in corso secondo il server» (da <c>GET /api/faccende/blocco</c>, più
    /// fresca), tenendo config e partenze. <paramref name="rispostaMs"/> è quando è arrivata questa risposta.
    /// </summary>
    public void AggiornaInCorso(SessioneStudio? dalServer, long rispostaMs)
    {
        // Le liste congelate restano solo se è sempre lo stesso Studio (stesso id e stesso inizio).
        if (dalServer == null || !InCorsoServer || dalServer.Id != IdServer || dalServer.InizioMs != InizioServerMs) ListeInCorso = null;
        InCorsoServer = dalServer != null;
        IdServer = dalServer?.Id;
        InizioServerMs = dalServer?.InizioMs ?? 0;
        UltimaRispostaMs = rispostaMs;
    }

    /// <summary>
    /// Lo Studio in corso adesso, o <c>null</c>. Prima vale la parola del server (se dice <c>in_corso</c> e
    /// non è ancora passata la mezzanotte del suo giorno); altrimenti, senza rete, una partenza passata
    /// <b>dopo</b> l'ultima risposta del server (che quindi il server non poteva ancora conoscere) e non
    /// oltre la sua mezzanotte. La mezzanotte del fuso del patto chiude lo Studio da sola (<c>non_chiuso</c>).
    /// </summary>
    public SessioneStudio? InCorsoA(long adessoMs, TimeZoneInfo fuso)
    {
        if (InCorsoServer && adessoMs < MezzanotteDopo(InizioServerMs, fuso))
        {
            return new SessioneStudio(IdServer, InizioServerMs, CondizioniDi(InizioServerMs, fuso));
        }

        PartenzaStudio? scelta = null;
        foreach (var p in Partenze)
        {
            // (correzione 0.18) Una partenza è «già nota al server» solo se la sua risposta è arrivata almeno
            // MargineServerMs dopo: l'ora agganciata ha i secondi interi e può essere avanti di un attimo, e un «niente
            // Studio» chiesto alle 14:59:59.6 (per il server) non deve spegnere lo Studio delle 15:00 fino al giro dopo.
            if (p.InizioMs <= adessoMs && p.InizioMs + MargineServerMs > UltimaRispostaMs && adessoMs < MezzanotteDopo(p.InizioMs, fuso))
            {
                if (scelta == null || p.InizioMs > scelta.InizioMs) scelta = p;
            }
        }
        return scelta == null ? null : new SessioneStudio(null, scelta.InizioMs, (scelta.ChiudibileDalMs, scelta.MinutiMinimi));
    }

    /// <summary>
    /// Le liste con cui coprire durante lo Studio <paramref name="sessione"/>: quelle congelate dal server se è lo
    /// Studio che il server dà in corso, altrimenti (partito senza rete) la configurazione approvata nota.
    /// </summary>
    public ConfigStudio? ListePer(SessioneStudio sessione) =>
        InCorsoServer && ListeInCorso != null && sessione.Id != null && sessione.Id == IdServer && sessione.InizioMs == InizioServerMs
            ? ListeInCorso
            : Config;

    /// <summary>La partenza futura più vicina (inizio &gt; adesso), per il fumetto dei 5 minuti e per partire da soli.</summary>
    public PartenzaStudio? ProssimaPartenza(long adessoMs)
    {
        PartenzaStudio? min = null;
        foreach (var p in Partenze)
        {
            if (p.InizioMs > adessoMs && (min == null || p.InizioMs < min.InizioMs)) min = p;
        }
        return min;
    }

    /// <summary>Le condizioni di chiusura note al computer per lo Studio che parte a <paramref name="inizioMs"/> (dalla partenza che gli corrisponde).</summary>
    private (long? ChiudibileDalMs, int MinutiMinimi) CondizioniDi(long inizioMs, TimeZoneInfo fuso)
    {
        var giorno = Tempo.GiornoDi(inizioMs, fuso);
        var p = Partenze.FirstOrDefault(x => x.Giorno == giorno) ?? Partenze.FirstOrDefault(x => Math.Abs(x.InizioMs - inizioMs) < 60_000);
        return p == null ? (null, 0) : (p.ChiudibileDalMs, p.MinutiMinimi);
    }

    /// <summary>La mezzanotte (fuso del patto) del giorno dopo quello locale di <paramref name="istanteMs"/>.</summary>
    internal static long MezzanotteDopo(long istanteMs, TimeZoneInfo fuso) =>
        Tempo.InizioGiorno(Tempo.DataDi(istanteMs, fuso).AddDays(1), fuso);
}

/// <summary>Lo Studio in corso adesso: l'inizio e le condizioni di chiusura (che però il computer non applica, le mostra).</summary>
public sealed record SessioneStudio(long? Id, long InizioMs, (long? ChiudibileDalMs, int MinutiMinimi) Condizioni);

/// <summary>
/// (0.18, contratto v4.0) Legge lo stato della Sessione Studio dal JSON del server (il campo <c>studio</c>
/// di <c>GET /api/patto</c>: <c>{ config, in_corso, prossime_partenze }</c>) e dal campo <c>studio</c> di
/// <c>GET /api/faccende/blocco</c> (<c>{ in_corso, id, inizio_ts }</c>, più leggero, chiesto spesso per
/// sapere quando lo Studio finisce). Tollerante: quello che non si legge si lascia cadere.
/// </summary>
public static class Studio
{
    /// <summary>
    /// Dallo <c>studio</c> di <c>GET /api/patto</c>. <paramref name="adessoMs"/> segna l'ultima risposta del
    /// server (serve a sapere quali partenze non conosceva ancora). Un patto senza il campo (server vecchio)
    /// dà uno stato vuoto: niente Studio.
    /// </summary>
    public static StatoStudio DaPatto(JsonObject? studioJson, long adessoMs)
    {
        var s = new StatoStudio { UltimaRispostaMs = adessoMs };
        if (studioJson == null) return s;

        s.Config = LeggiConfig(studioJson["config"] as JsonObject);

        if (studioJson["in_corso"] is JsonObject inCorso && Tempo.ProvaIsoMs(Json.Testo(inCorso["inizio_ts"]), out var inizio))
        {
            s.InCorsoServer = true;
            s.IdServer = Json.Intero(inCorso["id"]);
            s.InizioServerMs = inizio;
            // Le liste congelate dello Studio in corso (quelle con cui è partito).
            s.ListeInCorso = LeggiComputer((inCorso["liste"] as JsonObject)?["computer"] as JsonObject);
        }

        if (studioJson["prossime_partenze"] is JsonArray partenze)
        {
            foreach (var nodo in partenze)
            {
                var p = LeggiPartenza(nodo as JsonObject);
                if (p != null) s.Partenze.Add(p);
            }
        }
        return s;
    }

    /// <summary>La configurazione approvata (il contenuto in vigore). <c>null</c> se non c'è niente di approvato.</summary>
    public static ConfigStudio? LeggiConfig(JsonObject? configJson)
    {
        var approvata = configJson?["approvata"] as JsonObject;
        return LeggiComputer(approvata?["computer"] as JsonObject);
    }

    /// <summary>La parte <c>computer</c> di una configurazione o delle liste di uno Studio: programmi, firme, nomi. Null se manca.</summary>
    public static ConfigStudio? LeggiComputer(JsonObject? computer)
    {
        if (computer == null) return null;
        var c = new ConfigStudio();
        if (computer["programmi"] is JsonArray programmi)
        {
            foreach (var k in programmi)
            {
                var chiave = Json.Testo(k)?.Trim().ToLowerInvariant();
                if (!string.IsNullOrEmpty(chiave)) c.Programmi.Add(chiave);
            }
        }
        if (computer["firme"] is JsonObject firme)
        {
            foreach (var (chiave, valore) in firme)
            {
                var soggetto = Json.Testo(valore);
                if (!string.IsNullOrEmpty(soggetto)) c.Firme[chiave.Trim().ToLowerInvariant()] = soggetto!;
            }
        }
        if (computer["nomi"] is JsonObject nomi)
        {
            foreach (var (chiave, valore) in nomi)
            {
                var nome = Json.Testo(valore);
                if (!string.IsNullOrEmpty(nome)) c.Nomi[chiave.Trim().ToLowerInvariant()] = nome!;
            }
        }
        return c;
    }

    /// <summary>Una <c>prossime_partenze</c> del server: <c>inizio_ts</c> e <c>chiudibile_dal</c> in ms UTC. Null se non ha un inizio leggibile.</summary>
    public static PartenzaStudio? LeggiPartenza(JsonObject? o)
    {
        if (o == null || !Tempo.ProvaIsoMs(Json.Testo(o["inizio_ts"]), out var inizio)) return null;
        long? chiudibile = Tempo.ProvaIsoMs(Json.Testo(o["chiudibile_dal"]), out var cd) ? cd : null;
        return new PartenzaStudio
        {
            Giorno = Json.Testo(o["giorno"]) ?? Tempo.GiornoDi(inizio, TimeZoneInfo.Utc),
            InizioMs = inizio,
            ChiudibileDalMs = chiudibile,
            MinutiMinimi = (int)Math.Clamp(Json.Intero(o["minuti_minimi"]) ?? 0, 0, 600),
        };
    }

    /// <summary>
    /// Lo <c>studio</c> di <c>GET /api/faccende/blocco</c> (più leggero: <c>in_corso</c>, <c>id</c>,
    /// <c>inizio_ts</c>), per sapere subito quando lo Studio finisce. Null se il server non manda il campo.
    /// </summary>
    public static SessioneStudio? LeggiBlocco(JsonObject? studioJson)
    {
        if (studioJson == null) return null;
        if (Json.Booleano(studioJson["in_corso"]) != true) return null;
        if (!Tempo.ProvaIsoMs(Json.Testo(studioJson["inizio_ts"]), out var inizio)) return null;
        return new SessioneStudio(Json.Intero(studioJson["id"]), inizio, (null, 0));
    }
}

/// <summary>
/// (0.18, contratto v4.0) L'ora del server agganciata all'orologio che non si sposta (il cronometro di Windows,
/// <c>Environment.TickCount64</c>), come <c>MemoriaBlocco.oraServer</c> sul telefono: le partenze e la mezzanotte
/// dello Studio si decidono su quest'ora, non sull'orologio del computer. Vale per questa accensione del
/// programma; finché nessuna risposta l'ha agganciata (avvio senza rete) vale l'orologio del computer.
/// Logica pura, provata nei test.
/// </summary>
public sealed class OrologioServer
{
    private readonly object blocco = new();
    private long? ancoraServer;
    private long ancoraTick;

    /// <summary>C'è un'ora del server agganciata.</summary>
    public bool Agganciato
    {
        get
        {
            lock (blocco) return ancoraServer != null;
        }
    }

    /// <summary>Una risposta del server: alle <paramref name="tick"/> del cronometro, il server diceva <paramref name="serverMs"/>.</summary>
    public void Aggancia(long serverMs, long tick)
    {
        lock (blocco)
        {
            ancoraServer = serverMs;
            ancoraTick = tick;
        }
    }

    /// <summary>L'ora del server adesso (al cronometro <paramref name="tick"/>), o <paramref name="orologioLocaleMs"/> se non è agganciata.</summary>
    public long Adesso(long tick, long orologioLocaleMs)
    {
        lock (blocco) return ancoraServer is long s ? s + (tick - ancoraTick) : orologioLocaleMs;
    }
}
