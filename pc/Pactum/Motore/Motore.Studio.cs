using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Motore;

/// <summary>
/// (0.18, contratto v4.0) La Sessione Studio nel motore: tiene lo stato (config, partenze, in corso
/// secondo il server), decide a ogni giro se si è in Studio adesso (anche senza rete, con le partenze
/// salvate) e lo dice all'interfaccia, che copre gli schermi fuori lista. La <b>chiusura la sa solo dal
/// server</b>: finché il server dà lo Studio aperto si resta in Studio; alla mezzanotte del patto si
/// chiude da solo. Durante lo Studio il blocco dei lavori <b>aspetta</b> (non copre).
/// </summary>
public sealed partial class Motore
{
    private readonly object studioLock = new();
    private volatile StatoStudio statoStudio = StatoStudio.Vuoto;
    private volatile bool inStudio;
    private long studioInizioMs;
    private ConfigStudio? configStudioMostrata;
    // Il fumetto dei 5 minuti prima di una partenza: una volta sola per partenza.
    private long fumettoPartenzaFattoMs = long.MinValue;

    /// <summary>(0.18) In Studio adesso? Lo legge l'interfaccia per coprire gli schermi fuori lista.</summary>
    public bool InStudio => inStudio;

    /// <summary>(0.18) Lo stato dello Studio adesso: in corso sì/no e la configurazione (lista e firme) da usare per la copertura.</summary>
    public VistaStudio VistaStudioCorrente
    {
        get
        {
            lock (studioLock) return new VistaStudio(inStudio, studioInizioMs, configStudioMostrata);
        }
    }

    /// <summary>(0.18) Lo Studio è cambiato (in corso sì/no, o la lista): l'interfaccia copre o scopre. Da un filo qualsiasi.</summary>
    public event Action<VistaStudio>? CambioStudio;

    /// <summary>(0.18) Il fumetto «Tra 5 minuti parte lo Studio»: titolo e testo, da un filo qualsiasi.</summary>
    public event Action<string, string>? AvvisoStudioInArrivo;

    /// <summary>Il fuso del patto (per la mezzanotte dello Studio).</summary>
    private TimeZoneInfo FusoPatto() => PattoLocale.Zona(patto?.Fuso ?? "Europe/Rome");

    // (0.18) L'ora del server agganciata al cronometro di Windows (da ogni risposta, v. Annota).
    private readonly OrologioServer orologioServer = new();

    // Quando è stata chiesta (cronometro) l'ultima risposta che ha detto se lo Studio è in corso: una risposta
    // chiesta prima (un patto lento) che arriva dopo una più fresca di /faccende/blocco non la scavalca.
    private long studioInCorsoTick = long.MinValue;

    /// <summary>(0.18, contratto v4.0) L'ora del server adesso (agganciata), o l'orologio del computer se nessuna risposta l'ha ancora agganciata.</summary>
    internal long OraServer() => orologioServer.Adesso(Environment.TickCount64, Tempo.AdessoUtcMs());

    /// <summary>
    /// (0.18) Lo <c>studio</c> di <c>GET /api/patto</c> (config, in_corso, prossime_partenze): config e partenze
    /// si adottano sempre (il patto è la loro sola fonte; un patto più vecchio non arriva fin qui, v.
    /// <c>AdottaPatto</c>); la parte «in corso» solo se non c'è già una risposta più fresca del blocco.
    /// <paramref name="chiestoAlle"/> è l'ora del server della richiesta.
    /// </summary>
    internal void AdottaStudioDaPatto(JsonObject? studioJson, long chiestoAlle, long? chiestoAlTick = null)
    {
        long chiestoTick = chiestoAlTick ?? Environment.TickCount64;
        var nuovo = Studio.DaPatto(studioJson, chiestoAlle);
        lock (studioLock)
        {
            if (chiestoTick < studioInCorsoTick)
            {
                // Il blocco ha già detto qualcosa di più fresco sullo Studio in corso: si tiene quello.
                var vecchio = statoStudio;
                var listeNuove = nuovo.InCorsoServer && nuovo.IdServer == vecchio.IdServer && nuovo.InizioServerMs == vecchio.InizioServerMs
                    ? nuovo.ListeInCorso
                    : null;
                nuovo.ListeInCorso = vecchio.InCorsoServer ? listeNuove ?? vecchio.ListeInCorso : null;
                nuovo.InCorsoServer = vecchio.InCorsoServer;
                nuovo.IdServer = vecchio.IdServer;
                nuovo.InizioServerMs = vecchio.InizioServerMs;
                nuovo.UltimaRispostaMs = Math.Max(vecchio.UltimaRispostaMs, nuovo.UltimaRispostaMs);
            }
            else
            {
                studioInCorsoTick = chiestoTick;
            }
            statoStudio = nuovo;
            SalvaStudio();
        }
        ValutaStudio(OraServer());
    }

    /// <summary>
    /// (0.18) Lo <c>studio</c> di <c>GET /api/faccende/blocco</c> (solo in_corso/id/inizio, più fresco):
    /// aggiorna la parte «in corso secondo il server», tenendo config e partenze. È il segnale di chiusura.
    /// </summary>
    internal void AdottaStudioDaBlocco(JsonObject? studioBloccoJson, long chiestoAlle, long? chiestoAlTick = null)
    {
        long chiestoTick = chiestoAlTick ?? Environment.TickCount64;
        var inCorso = Studio.LeggiBlocco(studioBloccoJson);
        lock (studioLock)
        {
            if (chiestoTick < studioInCorsoTick) return;
            studioInCorsoTick = chiestoTick;
            statoStudio.AggiornaInCorso(inCorso, chiestoAlle);
            SalvaStudio();
        }
        ValutaStudio(OraServer());
    }

    /// <summary>
    /// (correzione 0.18) Un <c>401</c> (computer revocato): lo Studio si svuota del tutto (configurazione, liste, in
    /// corso e partenze) e si salva, come il blocco (<c>StatoBlocco.Vuoto</c>). Niente Studio e niente fumetto finché il
    /// server non manda di nuovo il suo stato.
    /// </summary>
    internal void SvuotaStudio(long? chiestoAlTick = null)
    {
        long chiestoTick = chiestoAlTick ?? Environment.TickCount64;
        lock (studioLock)
        {
            if (chiestoTick < studioInCorsoTick) return;
            studioInCorsoTick = chiestoTick;
            statoStudio = StatoStudio.Vuoto;
            SalvaStudio();
        }
        ValutaStudio(OraServer());
    }

    /// <summary>
    /// (0.18) Decide se si è in Studio adesso (dalla parola del server, o da una partenza passata senza
    /// rete, fino alla mezzanotte del patto) e, se cambia, lo dice all'interfaccia. Chiamato a ogni giro di
    /// misura, così lo Studio parte all'ora giusta anche senza rete e si chiude a mezzanotte.
    /// </summary>
    internal void ValutaStudio(long adesso)
    {
        var stato = statoStudio;
        var sessione = stato.InCorsoA(adesso, FusoPatto());
        // (0.18) Nei primi secondi dell'avvio si aspetta il server prima di coprire con la copia (v. SentiIlServerAllAvvioAsync).
        bool nuovoInStudio = Abbinato && !attesaServerAvvio && sessione != null;
        // Le liste congelate dello Studio in corso (dal server), o quelle approvate note se è partito senza rete.
        var config = sessione != null ? stato.ListePer(sessione) : stato.Config;

        bool cambia;
        lock (studioLock)
        {
            cambia = nuovoInStudio != inStudio
                     || (nuovoInStudio && !StessaConfig(configStudioMostrata, config));
            inStudio = nuovoInStudio;
            studioInizioMs = sessione?.InizioMs ?? 0;
            configStudioMostrata = nuovoInStudio ? config : null;
        }

        FumettoSeStudioInArrivo(adesso, stato);

        if (!cambia) return;
        // Lo Studio comincia o finisce: il blocco dei lavori si ricalcola subito (aspetta durante lo Studio, parte
        // alla sua fine se restano lavori aperti che bloccano). ValutaBlocco non richiama ValutaStudio.
        ValutaBlocco(Tempo.AdessoUtcMs());
        // (0.18) Il segno in vivo.json segue lo Studio subito: un programma chiuso di colpo durante lo Studio
        // si riconosce al riavvio (chiuso_durante_studio).
        ScriviVivo(ChiusuraDaScrivere());
        Log.Info(nuovoInStudio ? "Sessione Studio in corso: copro gli schermi fuori lista" : "Sessione Studio finita");
        try
        {
            CambioStudio?.Invoke(new VistaStudio(nuovoInStudio, studioInizioMs, configStudioMostrata));
        }
        catch (Exception e)
        {
            Log.Errore("cambio dello Studio non consegnato", e);
        }
    }

    /// <summary>(0.18) 5 minuti prima di una partenza, un fumetto avvisa. Una volta sola per partenza.</summary>
    private void FumettoSeStudioInArrivo(long adesso, StatoStudio stato)
    {
        if (!Abbinato || inStudio) return;
        var p = stato.ProssimaPartenza(adesso);
        if (p == null) return;
        long cinqueMinutiPrima = p.InizioMs - 5 * 60_000;
        if (adesso < cinqueMinutiPrima || adesso >= p.InizioMs) return;
        if (fumettoPartenzaFattoMs == p.InizioMs) return;
        fumettoPartenzaFattoMs = p.InizioMs;
        try
        {
            AvvisoStudioInArrivo?.Invoke("Sessione Studio", "Tra 5 minuti parte lo Studio.");
        }
        catch (Exception e)
        {
            Log.Errore("fumetto dello Studio in arrivo non partito", e);
        }
    }

    /// <summary>
    /// (0.18, contratto v4.0) Le firme Authenticode dei programmi visti, per chiave <c>exe:</c>: quelli che girano
    /// adesso e quelli visti negli ultimi 30 giorni (dal percorso del file salvato nei giorni, solo sul computer).
    /// Serve a proporre la lista dello Studio col soggetto della firma già riempito (il figlio sceglie un programma
    /// dai programmi visti, il computer legge la firma del file che ha visto girare). Solo i programmi con una firma
    /// valida compaiono; un file che non c'è più o non si legge si salta. Mai un'eccezione.
    /// </summary>
    public JsonObject FirmeCorrenti()
    {
        var percorsiVisti = new Dictionary<string, string>(StringComparer.Ordinal);
        try
        {
            foreach (var v in Sistema.PrimoPiano.Visibili())
            {
                if (v.Percorso != null) percorsiVisti.TryAdd(v.Chiave, v.Percorso);
            }
        }
        catch (Exception e)
        {
            Log.Errore("lettura delle finestre per le firme", e);
        }
        foreach (var (chiave, percorso) in PercorsiVisti(30)) percorsiVisti.TryAdd(chiave, percorso);

        var firme = new JsonObject();
        foreach (var (chiave, percorso) in percorsiVisti.OrderBy(p => p.Key, StringComparer.Ordinal))
        {
            try
            {
                // (correzione 0.18) La firma si propone solo se il nome originale del file va d'accordo col nome del
                // programma: nello Studio una voce firmata vuole anche quello, e un programma col nome cambiato (o un
                // browser rinominato) sarebbe sempre coperto. Senza firma resta riconoscibile dal nome (limite noto).
                var exe = chiave.StartsWith(Programma.Prefisso, StringComparison.Ordinal) ? chiave[Programma.Prefisso.Length..] : chiave;
                if (Sistema.Firma.Leggi(percorso) is { Soggetto: string soggetto } info
                    && CoperturaStudio.StessoNomeOriginale(info.NomeOriginale, exe)
                    && !CoperturaStudio.ÈBrowserDiNascita(info.NomeOriginale))
                    firme[chiave] = soggetto;
            }
            catch (Exception e)
            {
                Log.Errore("firma non letta", e);
            }
        }
        return firme;
    }

    /// <summary>
    /// (0.18) Il percorso più recente di ogni programma visto negli ultimi <paramref name="giorni"/> giorni (oggi
    /// compreso), dai file dei giorni. Solo locale: i percorsi non escono mai dal computer.
    /// </summary>
    internal Dictionary<string, string> PercorsiVisti(int giorni)
    {
        var esito = new Dictionary<string, string>(StringComparer.Ordinal);
        var elenco = new List<Giornata>();
        string oggi;
        lock (misura)
        {
            oggi = contatore.Oggi.Giorno;
            elenco.Add(contatore.Oggi.Copia());
        }
        try
        {
            var limite = Tempo.DataDi(Tempo.AdessoUtcMs(), TimeZoneInfo.Local).AddDays(-giorni);
            foreach (var file in Directory.EnumerateFiles(percorsi.Giorni, "*.json"))
            {
                var nome = Path.GetFileNameWithoutExtension(file);
                if (nome == oggi || !Tempo.ProvaGiorno(nome, out var data) || data < limite) continue;
                var g = Archivio.LeggiJson<Giornata>(file);
                if (g != null && g.Giorno == nome) elenco.Add(g);
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("giorni non letti per i percorsi dei programmi", e);
        }
        // Dal giorno più recente: vince l'ultimo percorso visto.
        foreach (var g in elenco.OrderByDescending(g => g.Giorno, StringComparer.Ordinal))
        {
            foreach (var (chiave, voce) in g.Programmi)
            {
                if (!string.IsNullOrEmpty(voce.Percorso)) esito.TryAdd(chiave, voce.Percorso!);
            }
        }
        return esito;
    }

    /// <summary>
    /// (0.18) Lo Studio come lo sa il computer adesso, per la finestra (<c>GET /locale/stato</c> → <c>studio</c>): in
    /// corso sì/no, da quando, da quando si potrebbe chiudere e i minuti minimi (dalla partenza), e se il server
    /// l'ha già confermato. Serve quando lo Studio è partito senza rete e il patto non ha ancora <c>in_corso</c>.
    /// </summary>
    internal JsonObject StudioPerLaFinestra()
    {
        var zona = TimeZoneInfo.Local;
        var stato = statoStudio;
        var sessione = inStudio ? stato.InCorsoA(OraServer(), FusoPatto()) : null;
        if (sessione == null) return new JsonObject { ["in_corso"] = false };
        return new JsonObject
        {
            ["in_corso"] = true,
            ["inizio"] = Tempo.IsoLocale(sessione.InizioMs, zona),
            ["chiudibile_dal"] = sessione.Condizioni.ChiudibileDalMs is long c ? Tempo.IsoLocale(c, zona) : null,
            ["minuti_minimi"] = sessione.Condizioni.MinutiMinimi > 0 ? sessione.Condizioni.MinutiMinimi : null,
            ["dal_server"] = sessione.Id != null,
        };
    }

    private void SalvaStudio()
    {
        try
        {
            Archivio.ScriviJson(percorsi.Studio, statoStudio);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("studio.json non scritto", e);
        }
        // (correzione 0.18) vivo.json ricorda la prossima partenza di questo stesso file: si riscrive insieme, così un
        // programma chiuso subito dopo non fa sembrare cambiato un file che ha cambiato il server.
        ScriviVivo(ChiusuraDaScrivere());
    }

    /// <summary>
    /// (0.18) Rilegge lo Studio salvato (<c>studio.json</c>): all'avvio, prima di decidere la copertura.
    /// (correzione 0.18) Come per il blocco (<c>stato_blocco_perso</c>): se il file manca, non si legge o non ha più la
    /// prossima partenza che l'ultimo «sono vivo» ricordava, oppure l'ultimo «sono vivo» diceva Studio in corso e il
    /// file non lo sa più, si manda una <c>manomissione</c> <c>stato_studio_perso</c> e si rimette la partenza ricordata
    /// (senza lista: finché il server non risponde, nello Studio vale la lista vuota). Il server poi riscrive tutto.
    /// </summary>
    internal void CaricaStudio(StatoVivo? precedenteVivo = null)
    {
        var salvato = Archivio.LeggiJson<StatoStudio>(percorsi.Studio);
        if (salvato != null) statoStudio = salvato;
        if (precedenteVivo == null) return;
        long adesso = OraServer();
        var stato = statoStudio;
        bool perso = false;
        if (precedenteVivo.ProssimaPartenzaMs is long prossima && prossima > adesso
            && !stato.Partenze.Any(p => p.InizioMs == prossima))
        {
            perso = true;
            stato.Partenze.Add(new PartenzaStudio { Giorno = Tempo.GiornoDi(prossima, FusoPatto()), InizioMs = prossima });
        }
        if (precedenteVivo.StudioInCorso && precedenteVivo.StudioInizioMs is long inizio && inizio <= adesso
            && adesso < StatoStudio.MezzanotteDopo(inizio, FusoPatto()) && stato.InCorsoA(adesso, FusoPatto()) == null)
        {
            // Lo Studio era in corso (e non è ancora passata la sua mezzanotte) ma il file non lo sa più: si resta in
            // Studio finché il server non dice il contrario, come uno Studio partito senza rete. Senza l'inizio
            // ricordato non si può giudicare: niente accusa.
            perso = true;
            stato.InCorsoServer = true;
            stato.IdServer = null;
            stato.InizioServerMs = inizio;
            stato.ListeInCorso = null;
        }
        if (!perso) return;
        statoStudio = stato;
        SalvaStudio();
        Log.Avviso("stato dello Studio perso o cambiato: rimetto quello che ricordavo finché il server non risponde");
        if (Abbinato) coda.Accoda(Eventi.Manomissione(new JsonObject { ["sotto_tipo"] = "stato_studio_perso" }, Tempo.AdessoUtcMs()));
    }

    /// <summary>(correzione 0.18) La prossima partenza nota adesso, da ricordare in <c>vivo.json</c>.</summary>
    private long? ProssimaPartenzaNota() => statoStudio.ProssimaPartenza(OraServer())?.InizioMs;

    /// <summary>(correzione 0.18) L'inizio dello Studio in corso, da ricordare in <c>vivo.json</c>.</summary>
    private long? InizioStudioNoto() => inStudio ? studioInizioMs : null;

    private static bool StessaConfig(ConfigStudio? a, ConfigStudio? b)
    {
        if (a == null || b == null) return a == null && b == null;
        return a.Programmi.SequenceEqual(b.Programmi)
               && a.Firme.Count == b.Firme.Count
               && a.Firme.All(e => b.Firme.TryGetValue(e.Key, out var v) && v == e.Value);
    }
}

/// <summary>(0.18) Quello che l'interfaccia deve fare per lo Studio: coprire sì/no, da quando, e con quale lista.</summary>
public sealed record VistaStudio(bool InCorso, long InizioMs, ConfigStudio? Config);
