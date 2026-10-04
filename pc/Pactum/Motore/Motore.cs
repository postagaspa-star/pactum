using System.Globalization;
using System.Text.Json.Nodes;
using Microsoft.Win32;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Motore;

/// <summary>
/// Il motore del programma: misura ogni secondo, tiene il registro onesto,
/// valuta le regole (senza bloccare niente), parla col server ogni 5 minuti e
/// risponde all'interfaccia. Una sola istanza per utente.
/// </summary>
public sealed partial class Motore : IDisposable
{
    private const int MsGiro = 1_000;
    private const int GiriTraSalvataggi = 60;
    private const int GiriTraValutazioni = 15;

    /// <summary>(0.10) Quanto si aspetta il patto fresco prima di registrare uno sforamento nuovo; poi si decide sulla copia.</summary>
    private static readonly TimeSpan TempoRilettura = TimeSpan.FromSeconds(10);

    private readonly Percorsi percorsi;
    private readonly Postino postino = new();
    private readonly LettoreIndirizzi lettore = new();
    private readonly CodaEventi coda;
    private readonly ManualResetEventSlim fermaMisura = new(false);
    private readonly CancellationTokenSource fermaRete = new();

    // Protegge contatore, registro degli sforamenti e bonus locali.
    private readonly object misura = new();
    private readonly Contatore contatore;
    private readonly RegistroSforamenti registro;
    private readonly List<BonusLocale> bonusLocali = new();

    // Il registro in memoria ha qualcosa che sforamenti.json non ha: si riprova a ogni valutazione.
    private bool registroDaSalvare;
    private bool registroNonScritto;

    private readonly object orologioBlocco = new();
    private readonly SentinellaOrologio orologio;

    private Thread? filoMisura;
    private Task? cicloRete;
    private long giri;

    // (0.10) 1 mentre una valutazione rilegge il patto prima di registrare uno sforamento nuovo.
    private int valutazioneInCorso;

    private volatile bool bloccato;
    private volatile bool sospeso;
    private volatile bool schermoAcceso = true;
    private volatile string? chiusuraInCorso;
    private long chiusuraInCorsoDalTick;

    // (0.13) Il blocco delle faccende (contratto v3.6). "coperto" = la finestra deve coprire gli schermi.
    private readonly object bloccoLock = new();
    private volatile StatoBlocco? statoBlocco;
    private volatile bool coperto;
    private IReadOnlyList<Faccenda> faccendeMostrate = Array.Empty<Faccenda>();
    // Quando è stato chiesto lo stato del blocco che sto usando (orologio monotono): uno chiesto prima,
    // che arriva dopo (un patto lento sopra una risposta fresca di /faccende/blocco), non lo sostituisce.
    private long bloccoChiestoTick = long.MinValue;

    public Motore(Percorsi percorsi)
    {
        this.percorsi = percorsi;
        Log.Inizia(percorsi.Log);
        config = Archivio.LeggiJson<Configurazione>(percorsi.Config) ?? new Configurazione();
        token = Dpapi.Svela(config.TokenProtetto);
        if (config.TokenProtetto != null && token == null) Log.Avviso("token presente ma non decifrabile con l'utente attuale");
        coda = new CodaEventi(percorsi.Coda)
        {
            ScritturaFallita = e => Log.Errore("coda.json non scritta: la coda resta in memoria e si riscrive appena si può", e),
        };
        registro = Archivio.LeggiJson<RegistroSforamenti>(percorsi.Sforamenti) ?? new RegistroSforamenti();
        // Gli sforamenti ancora da mandare sono già segnalati, anche se sforamenti.json non li ha.
        if (registro.Ricorda(coda.Prossimi(CodaEventi.Massimo)) > 0) registroDaSalvare = true;
        notifiche = Archivio.LeggiJson<StatoNotifiche>(percorsi.Notifiche) ?? new StatoNotifiche();
        memoriaSerie = Archivio.LeggiJson<MemoriaSerie>(percorsi.Serie) ?? new MemoriaSerie();
        // (0.13) Il blocco salvato: senza rete resta com'era, un blocco programmato parte all'ora giusta.
        statoBlocco = Archivio.LeggiJson<StatoBlocco>(percorsi.Blocco);
        var salvato = Archivio.LeggiJson<PattoSalvato>(percorsi.Patto);
        if (salvato != null) patto = new PattoLocale(salvato.Patto, salvato.AggiornatoUtcMs);

        long adesso = Tempo.AdessoUtcMs();
        contatore = new Contatore(CaricaGiorno(Tempo.GiornoDi(adesso, TimeZoneInfo.Local)), CaricaGiorno);
        orologio = new SentinellaOrologio(adesso, Environment.TickCount64, TimeZoneInfo.Local.Id);
    }

    /// <summary>Prove: se c'è, si registrano solo questi domini (v. Opzioni.SitiSolo).</summary>
    public IReadOnlySet<string>? SitiSolo { get; set; }

    /// <summary>Prove: la finestra di questo processo vale come quella in primo piano (v. PrimoPiano.LeggiFinestraDi).</summary>
    public int? ProvaPidFinestra { get; set; }

    /// <summary>
    /// Un fumetto per l'icona: titolo, testo e (0.10) la sezione della finestra da aprire col clic
    /// (<c>"proposte"</c>, <c>"diario"</c>; null = come sempre). Arriva da un filo qualsiasi.
    /// </summary>
    public event Action<string, string, string?>? Fumetto;

    /// <summary>
    /// (0.9) Sforamenti nuovi da mostrare anche a tutto schermo, oltre al fumetto: stesso dedup
    /// (una volta per regola per giorno). Arriva da un filo qualsiasi.
    /// </summary>
    public event Action<IReadOnlyList<Avviso>>? AvvisoTuttoSchermo;

    /// <summary>
    /// (0.13, contratto v3.6) Il blocco delle faccende è cambiato: coprire gli schermi con l'elenco,
    /// oppure togliere la copertura. Arriva da un filo qualsiasi; chi ascolta marshalla sul filo grafico.
    /// </summary>
    public event Action<VistaBlocco>? CambioBlocco;

    /// <summary>(0.13) Per i test e l'interfaccia: il blocco sta coprendo gli schermi adesso?</summary>
    public bool Coperto => coperto;

    /// <summary>
    /// (0.13) Lo stato attuale del blocco, letto adesso: l'interfaccia lo rilegge quando arriva un
    /// <see cref="CambioBlocco"/>, invece di fidarsi dell'ordine degli eventi fra i fili.
    /// </summary>
    public VistaBlocco VistaBloccoCorrente
    {
        get
        {
            lock (bloccoLock) return new VistaBlocco(coperto, faccendeMostrate);
        }
    }

    public bool Abbinato
    {
        get
        {
            lock (stato) return token != null && config.Server != null;
        }
    }

    public void Avvia()
    {
        Log.Info($"avvio Pactum {Versione.Nome}");
        var precedenteVivo = Archivio.LeggiJson<StatoVivo>(percorsi.Vivo);
        ValutaAvvio(precedenteVivo);
        // (0.13) Il blocco: se blocco.json si è perso ma l'ultimo "sono vivo" diceva bloccato, resta coperto
        // con un elenco generico finché il server non risponde. Poi si valuta la copertura dallo stato salvato.
        PreparaBlocco(precedenteVivo, Tempo.AdessoUtcMs());
        ScriviVivo(null);
        filoMisura = new Thread(CicloMisura) { IsBackground = true, Name = "Pactum misura" };
        filoMisura.SetApartmentState(ApartmentState.MTA);
        filoMisura.Start();
        cicloRete = Task.Run(() => CicloReteAsync(fermaRete.Token));
    }

    /// <summary>Ferma tutto e scrive come ci si è chiusi (<see cref="Chiusure"/>).</summary>
    public void Ferma(string chiusura)
    {
        ImpostaChiusura(chiusura);
        fermaMisura.Set();
        fermaRete.Cancel();
        filoMisura?.Join(3_000);
        lock (misura) SalvaGiorno(contatore.Oggi);
        ScriviVivo(chiusura);
        Log.Info($"fermo ({chiusura})");
    }

    /// <summary>"Chiudi Pactum" dal menu, dopo la conferma: lo si dice subito al server, poi ci si ferma.</summary>
    public async Task ChiudiVolontariamenteAsync()
    {
        long adesso = Tempo.AdessoUtcMs();
        // Gli ultimi secondi contano: uno sforamento appena successo parte con la chiusura (senza avvisi a schermo).
        ValutaRegole(adesso, conAvvisi: false);
        if (Abbinato) coda.Accoda(Eventi.ChiusuraVolontaria(adesso));
        ImpostaChiusura(Chiusure.Volontaria);
        AccodaFotografie(adesso);
        await InviaCodaAsync(TimeSpan.FromSeconds(4)).ConfigureAwait(false);
        Ferma(Chiusure.Volontaria);
    }

    // ---------- Il registro onesto: sospensione, spegnimento, ripresa ----------

    /// <summary>Windows va in sospensione (PowerModeChanged.Suspend).</summary>
    public void Sospensione()
    {
        sospeso = true;
        long adesso = Tempo.AdessoUtcMs();
        // (0.14) Prima di tutto la sospensione, su disco (coda.json) col suo ts_device: se la rete se ne va
        // prima di consegnarla, parte al risveglio con l'ora giusta.
        var sospensione = Abbinato ? Eventi.Sospensione("sospensione", adesso) : null;
        if (sospensione != null) coda.Accoda(sospensione);
        ScriviVivo(null);
        // Gli sforamenti degli ultimi secondi vanno in coda (lo schermo si spegne: niente avvisi).
        ValutaRegole(adesso, conAvvisi: false);
        lock (misura) SalvaGiorno(contatore.Oggi);
        Log.Info("sospensione");
        // La sospensione parte da sola e per prima, poi il resto: Windows non aspetta molto prima di dormire.
        _ = Task.Run(async () =>
        {
            if (sospensione != null) await InviaSubitoAsync(sospensione, TimeSpan.FromSeconds(2)).ConfigureAwait(false);
            await InviaCodaAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
        });
    }

    /// <summary>Windows si riattiva (PowerModeChanged.Resume).</summary>
    public void Ripresa()
    {
        long adesso = Tempo.AdessoUtcMs();
        long tick = Environment.TickCount64;
        lock (orologioBlocco) orologio.Riancora(adesso, tick);
        sospeso = false;
        if (Abbinato) coda.Accoda(Eventi.Ripresa("riattivazione", adesso - tick, adesso));
        Log.Info("ripresa dalla sospensione");
        // La rete torna qualche secondo dopo il risveglio.
        _ = Task.Run(async () =>
        {
            await Task.Delay(TimeSpan.FromSeconds(15)).ConfigureAwait(false);
            await SincronizzaAsync("ripresa").ConfigureAwait(false);
        });
    }

    /// <summary>(0.14) Quanto si aspetta la rete in tutto allo spegnimento: Windows non va trattenuto.</summary>
    private static readonly TimeSpan TempoSpegnimento = TimeSpan.FromSeconds(2.5);

    /// <summary>(0.14) Quanto, di quel tempo, può prendere la sola sospensione.</summary>
    private static readonly TimeSpan TempoSospensione = TimeSpan.FromSeconds(2);

    // (0.14) L'ultima FineSessione (orologio monotono): in uno spegnimento normale arrivano sia SessionEnding sia
    // SessionEnded, e la seconda non deve ripetere la sospensione.
    private readonly object fineSessioneBlocco = new();
    private long fineSessioneTick = long.MinValue;

    /// <summary>
    /// L'utente esce o Windows si spegne: si scrive la chiusura pulita e si manda la <c>sospensione</c>.
    /// (0.14, contratto v3.7) L'ordine conta, perché la rete se ne va da un momento all'altro:
    /// <list type="number">
    /// <item>la <c>sospensione</c> va subito su disco (<c>coda.json</c>) col suo <c>ts_device</c>, insieme alla chiusura
    /// in <c>vivo.json</c>: se non si riesce a mandarla, parte alla riaccensione con l'ora vera dello spegnimento;</item>
    /// <item>la si manda da sola, per prima (al massimo <see cref="TempoSospensione"/>), così una coda lunga o un evento
    /// rifiutato non la fanno arrivare tardi;</item>
    /// <item>poi gli sforamenti degli ultimi secondi e le fotografie del giorno, in coda, e con il tempo che resta
    /// (in tutto al massimo <see cref="TempoSpegnimento"/>) si manda il resto.</item>
    /// </list>
    /// Con <paramref name="soloSeMancante"/> (da SessionEnded) non fa niente se una FineSessione c'è stata da meno
    /// di un minuto: serve agli spegnimenti forzati, dove SessionEnding non arriva.
    /// </summary>
    public void FineSessione(bool spegnimento, bool soloSeMancante = false)
    {
        lock (fineSessioneBlocco)
        {
            long ora = Environment.TickCount64;
            if (soloSeMancante && fineSessioneTick != long.MinValue && ora - fineSessioneTick < 60_000) return;
            fineSessioneTick = ora;
        }
        var cronometro = System.Diagnostics.Stopwatch.StartNew();
        var motivo = spegnimento ? Chiusure.Spegnimento : Chiusure.Disconnessione;
        ImpostaChiusura(motivo);
        long adesso = Tempo.AdessoUtcMs();

        var sospensione = Abbinato ? Eventi.Sospensione(motivo, adesso) : null;
        if (sospensione != null) coda.Accoda(sospensione);
        ScriviVivo(motivo);
        if (sospensione != null) AspettaAlPiù(InviaSubitoAsync(sospensione, TempoSospensione), TempoSospensione + TimeSpan.FromMilliseconds(200));

        // Gli sforamenti degli ultimi secondi e le fotografie: in coda (niente avvisi a schermo).
        ValutaRegole(adesso, conAvvisi: false);
        AccodaFotografie(adesso);
        lock (misura) SalvaGiorno(contatore.Oggi);
        Log.Info($"fine sessione ({motivo})");
        var resta = TempoSpegnimento - cronometro.Elapsed;
        if (resta > TimeSpan.FromMilliseconds(300)) AspettaAlPiù(InviaCodaAsync(resta), resta);
    }

    /// <summary>Aspetta un invio al massimo per quel tempo; un errore non ferma mai lo spegnimento.</summary>
    private static void AspettaAlPiù(Task compito, TimeSpan tempo)
    {
        try
        {
            compito.Wait(tempo);
        }
        catch (AggregateException e)
        {
            Log.Errore("invio allo spegnimento", e.InnerException ?? e);
        }
    }

    public void SchermoBloccato(bool bloccato) => this.bloccato = bloccato;

    public void SchermoAcceso(bool acceso) => schermoAcceso = acceso;

    private void ValutaAvvio(StatoVivo? precedente)
    {
        long adesso = Tempo.AdessoUtcMs();
        var esito = Vivo.ValutaAvvio(precedente, adesso, Environment.TickCount64, IdAvvioWindows(), IdSpegnimentoPulitoMs());
        if (esito.ProgrammaChiuso != null)
        {
            Log.Avviso(Json.Booleano(esito.ProgrammaChiuso["avvio_ritardato"]) == true
                ? "avvio in ritardo: il computer era acceso senza Pactum"
                : "il programma era stato chiuso mentre Windows era acceso");
        }
        if (esito.ChiusoDuranteBlocco) Log.Avviso("il programma era stato chiuso durante un blocco dei lavori di casa");
        if (esito.CambioOra != null) Log.Avviso("l'orologio è stato spostato mentre il programma era chiuso");
        if (!Abbinato) return;
        if (esito.ProgrammaChiuso != null) coda.Accoda(Eventi.Manomissione(esito.ProgrammaChiuso, adesso));
        // (0.13) Chiuso di colpo durante un blocco delle faccende (per esempio dal Task Manager): lo si dice.
        if (esito.ChiusoDuranteBlocco) coda.Accoda(Eventi.ChiusoDuranteBlocco(adesso));
        if (esito.CambioOra != null) coda.Accoda(Eventi.Manomissione(esito.CambioOra, adesso));
        coda.Accoda(Eventi.Ripresa(esito.MotivoRipresa, esito.AvvioSistemaMs, adesso));
    }

    private void ScriviVivo(string? chiusura)
    {
        try
        {
            Archivio.ScriviJson(percorsi.Vivo, new StatoVivo
            {
                UtcMs = Tempo.AdessoUtcMs(),
                TickMs = Environment.TickCount64,
                BootId = IdAvvioWindows(),
                Chiusura = chiusura,
                BloccatoFaccende = coperto,
            });
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("vivo.json non scritto", e);
        }
    }

    /// <summary>Il contatore degli avvii di Windows: non si sposta con l'orologio.</summary>
    private static long? IdAvvioWindows()
    {
        try
        {
            using var k = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management\PrefetchParameters");
            return k?.GetValue("BootId") is int id ? (uint)id : null;
        }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException)
        {
            return null;
        }
    }

    /// <summary>
    /// (0.13) L'ora dell'ultimo spegnimento PULITO di Windows (<c>HKLM\…\Control\Windows\ShutdownTime</c>),
    /// in millisecondi UTC: un valore <c>FILETIME</c> di 8 byte, leggibile in sola lettura senza amministratore.
    /// Serve a <see cref="Vivo.ValutaAvvio"/> per riconoscere un programma chiuso di colpo durante un blocco e
    /// poi un riavvio/spegnimento pulito. Null se non si legge (uno spegnimento sporco non lo aggiorna: resta vecchio).
    /// </summary>
    internal static long? IdSpegnimentoPulitoMs()
    {
        try
        {
            using var k = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Windows");
            if (k?.GetValue("ShutdownTime") is byte[] b && b.Length == 8)
            {
                return DateTimeOffset.FromFileTime(BitConverter.ToInt64(b, 0)).ToUnixTimeMilliseconds();
            }
            return null;
        }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException or ArgumentException)
        {
            return null;
        }
    }

    /// <summary>
    /// (0.13) Un errore imprevisto sta chiudendo il programma: si scrive una chiusura "crash" in vivo.json,
    /// così al riavvio un crash non diventa una <c>manomissione</c> (né un <c>chiuso_durante_blocco</c>).
    /// </summary>
    public void SegnaCrash() => ScriviVivo(Chiusure.Crash);

    // ---------- La misura: un giro al secondo ----------

    private void CicloMisura()
    {
        long precedente = Environment.TickCount64;
        while (!fermaMisura.IsSet)
        {
            long mono = Environment.TickCount64;
            long trascorsi = mono - precedente;
            precedente = mono;
            try
            {
                Giro(Tempo.AdessoUtcMs(), mono, trascorsi);
            }
            catch (Exception e)
            {
                Log.Errore("giro di misura", e);
            }
            int attesa = (int)Math.Clamp(MsGiro - (Environment.TickCount64 - mono), 50, MsGiro);
            fermaMisura.Wait(attesa);
        }
    }

    private void Giro(long utc, long mono, long trascorsi)
    {
        giri++;
        if (giri % 60 == 0) TimeZoneInfo.ClearCachedData();
        var zona = TimeZoneInfo.Local;

        List<JsonObject> manomissioni;
        lock (orologioBlocco) manomissioni = orologio.Controlla(utc, mono, zona.Id);
        foreach (var m in manomissioni)
        {
            Log.Avviso($"manomissione rilevata: {Json.Testo(m["sotto_tipo"])}");
            if (Abbinato) coda.Accoda(Eventi.Manomissione(m, utc));
        }

        var osservazione = Osserva();
        EsitoGiro esito;
        Giornata oggi;
        lock (misura)
        {
            esito = contatore.Registra(utc, trascorsi, osservazione, zona);
            oggi = contatore.Oggi;
            if (esito.GiorniChiusi.Count > 0 && oggi.Revisione == 0) oggi.Revisione = 1;
        }

        foreach (var chiuso in esito.GiorniChiusi)
        {
            Log.Info($"cambio di giorno: chiuso {chiuso.Giorno}");
            // Il giorno che si chiude si valuta fino all'ultimo secondo, prima di passare al nuovo:
            // uno sforamento negli ultimi secondi prima di mezzanotte resta di quel giorno. Solo l'evento:
            // "oggi sei andato oltre" non sarebbe più vero.
            ValutaRegole(UltimoMsDel(chiuso, zona, utc), chiuso, conAvvisi: false);
            lock (misura)
            {
                SalvaGiorno(chiuso);
                if (Abbinato) FotografaGiorno(chiuso, utc);
            }
        }
        foreach (var browser in esito.BrowserNonLeggibili)
        {
            Log.Avviso($"siti non leggibili con {browser}");
            if (Abbinato) coda.Accoda(Eventi.SitiNonLeggibili(browser, oggi.Giorno, utc));
        }

        // (0.13) Il blocco delle faccende: lo si guarda a ogni giro, così un blocco programmato parte
        // all'ora giusta anche senza rete (la copia non cambia da sola, ma l'ora passa).
        ValutaBlocco(utc);

        if (giri % GiriTraSalvataggi == 0 || esito.GiorniChiusi.Count > 0)
        {
            lock (misura) SalvaGiorno(contatore.Oggi);
            ScriviVivo(ChiusuraDaScrivere());
        }
        // (0.10) Uno sforamento nuovo si registra solo dopo aver riletto il patto (v. ValutaConPattoFrescoAsync):
        // la rete non ferma la misura, che va avanti mentre si aspetta il server.
        if (giri % GiriTraValutazioni == 0 || esito.GiorniChiusi.Count > 0) _ = ValutaConPattoFrescoAsync(utc);
    }

    private void ImpostaChiusura(string chiusura)
    {
        Interlocked.Exchange(ref chiusuraInCorsoDalTick, Environment.TickCount64);
        chiusuraInCorso = chiusura;
    }

    /// <summary>Dopo SessionEnding la chiusura pulita resta scritta; se dopo un minuto siamo ancora vivi, lo spegnimento è stato annullato.</summary>
    private string? ChiusuraDaScrivere()
    {
        var c = chiusuraInCorso;
        if (c == null) return null;
        if (Environment.TickCount64 - Interlocked.Read(ref chiusuraInCorsoDalTick) > 60_000)
        {
            chiusuraInCorso = null;
            return null;
        }
        return c;
    }

    internal Osservazione Osserva()
    {
        // (0.13) Sotto la copertura del blocco non si misura: il tempo non conta (un gioco o un video a
        // schermo intero dietro la copertura non deve diventare uno sforamento falso al genitore).
        if (sospeso || bloccato || coperto || !Sessione.Disponibile()) return Osservazione.Assente;
        var finestra = ProvaPidFinestra is int pid ? PrimoPiano.LeggiFinestraDi(pid) : PrimoPiano.Leggi();
        if (finestra?.Exe == "lockapp.exe") return Osservazione.Assente;
        bool attivo = Presenza.Attivo(
            sessioneDisponibile: true,
            salvaschermo: Sessione.SalvaschermoInFunzione(),
            schermoAcceso: schermoAcceso,
            msDallUltimoInput: Sessione.MsDallUltimoInput(),
            schermoIntero: finestra?.SchermoIntero ?? false);
        if (!attivo) return Osservazione.Assente;
        if (finestra == null) return new Osservazione(true, null, null);
        if (!LettoreIndirizzi.ÈBrowser(finestra.Exe)) return new Osservazione(true, finestra.Chiave, finestra.Nome);
        var lettura = lettore.Leggi(finestra.Hwnd, finestra.Exe, finestra.SchermoIntero);
        if (lettura.Dominio != null && SitiSolo != null && !SitiSolo.Contains(lettura.Dominio))
            lettura = lettura with { Dominio = null, CategoriaSito = null };
        return new Osservazione(true, finestra.Chiave, finestra.Nome, true, lettura.Dominio, lettura.Fallita, lettura.CategoriaSito);
    }

    // ---------- I giorni su disco e le fotografie ----------

    private Giornata CaricaGiorno(string giorno)
    {
        var g = Archivio.LeggiJson<Giornata>(percorsi.FileGiorno(giorno));
        if (g == null || g.Giorno != giorno) g = Giornata.Nuova(giorno);
        return g;
    }

    private void SalvaGiorno(Giornata g)
    {
        try
        {
            Archivio.ScriviJson(percorsi.FileGiorno(g.Giorno), g);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore($"giorno {g.Giorno} non salvato", e);
        }
    }

    /// <summary>Le fotografie cumulative di un giorno in coda (sostituiscono quelle ancora non mandate). Chiamare col blocco "misura".</summary>
    private void FotografaGiorno(Giornata g, long adesso)
    {
        coda.SostituisciFotografia(Fotografie.Uso(g, adesso));
        coda.SostituisciFotografia(Fotografie.Siti(g, adesso));
        g.RevisioneFotografata = g.Revisione;
        SalvaGiorno(g);
    }

    /// <summary>Fotografa oggi e gli ultimi 7 giorni che sono cambiati dall'ultima fotografia.</summary>
    private void AccodaFotografie(long adesso)
    {
        if (!Abbinato) return;
        lock (misura)
        {
            var oggi = contatore.Oggi;
            if (oggi.Revisione == 0) oggi.Revisione = 1; // anche un giorno a zero minuti è un dato
            if (oggi.Revisione != oggi.RevisioneFotografata) FotografaGiorno(oggi, adesso);

            var limite = Tempo.DataDi(adesso, TimeZoneInfo.Local).AddDays(-7);
            foreach (var file in Directory.EnumerateFiles(percorsi.Giorni, "*.json"))
            {
                var nome = Path.GetFileNameWithoutExtension(file);
                if (nome == oggi.Giorno || !Tempo.ProvaGiorno(nome, out var data) || data < limite) continue;
                var g = Archivio.LeggiJson<Giornata>(file);
                if (g != null && g.Giorno == nome && g.Revisione != g.RevisioneFotografata) FotografaGiorno(g, adesso);
            }
        }
    }

    // ---------- Le regole: sforamenti, mai blocchi ----------

    /// <summary>
    /// (0.10) La valutazione di ogni 15 secondi. Se la copia del patto trova uno sforamento NUOVO, prima
    /// di registrarlo si rilegge il patto dal server e si rivaluta sulla stessa lettura dell'uso, come fa
    /// la sentinella del telefono (SentinellaPatto.segnalaNuovi): una copia rimasta indietro (una proposta
    /// appena accettata dal genitore, un bonus dato dal telefono) darebbe uno sforamento falso. Senza rete,
    /// o col server che non risponde in <see cref="TempoRilettura"/>, si decide sulla copia, come prima.
    /// Una alla volta: se la rilettura di prima non è finita, questo giro salta (tra 15 secondi c'è il prossimo).
    /// </summary>
    internal async Task ValutaConPattoFrescoAsync(long adesso)
    {
        if (Interlocked.CompareExchange(ref valutazioneInCorso, 1, 0) != 0) return;
        try
        {
            Giornata lettura;
            lock (misura) lettura = contatore.Oggi.Copia();
            if (CiSonoNuovi(lettura, adesso))
            {
                bool fresco = await AggiornaPattoAsync(TempoRilettura, valuta: false).ConfigureAwait(false);
                Log.Info(fresco
                    ? "sforamento nuovo in vista: patto riletto prima di registrarlo"
                    : "sforamento nuovo in vista: server non raggiungibile, si decide sulla copia del patto");
            }
            bool stessoGiorno;
            lock (misura) stessoGiorno = contatore.Oggi.Giorno == lettura.Giorno;
            // Sulla stessa lettura. Se intanto è passata la mezzanotte, solo l'evento: "oggi" non sarebbe più vero.
            ValutaRegole(adesso, lettura, conAvvisi: stessoGiorno);
        }
        catch (Exception e)
        {
            Log.Errore("valutazione delle regole", e);
        }
        finally
        {
            Volatile.Write(ref valutazioneInCorso, 0);
        }
    }

    /// <summary>(0.10) La copia del patto darebbe, su questa lettura, almeno uno sforamento mai segnalato? Non segna niente.</summary>
    private bool CiSonoNuovi(Giornata lettura, long adesso)
    {
        var p = patto;
        if (p == null || !Abbinato) return false;
        var regole = p.RegoleDelComputer(IdDispositivo).ToList();
        if (regole.Count == 0) return false;
        var bonus = BonusOggi(adesso);
        var trovati = Valutatore.Valuta(regole, bonus, lettura.MinutiDi, lettura.MinutiNellIntervallo, adesso, TimeZoneInfo.Local);
        lock (misura) return trovati.Any(s => !registro.Contiene(s.RegolaId, Valutatore.GiornoDelloSforamento(s, lettura.Giorno)));
    }

    /// <summary>
    /// Valuta le regole del computer su <paramref name="giorno"/> (se manca, oggi) all'istante
    /// <paramref name="adesso"/>. La parte su disco è al meglio possibile e non lancia mai: gli
    /// sforamenti nuovi stanno nella coda in memoria anche se coda.json non si scrive, e il registro,
    /// se non si scrive, si riprova a ogni valutazione finché non ci riesce. Dopo la decisione partono
    /// sempre fumetto e avviso a tutto schermo; con <paramref name="conAvvisi"/> false solo gli eventi
    /// (mezzanotte, sospensione, spegnimento, chiusura: lo schermo non serve più).
    /// </summary>
    internal void ValutaRegole(long adesso, Giornata? giorno = null, bool conAvvisi = true)
    {
        var p = patto;
        var regole = p != null && Abbinato ? p.RegoleDelComputer(IdDispositivo).ToList() : new List<Regola>();
        var bonus = regole.Count > 0 ? BonusOggi(adesso) : new Dictionary<string, int>();
        var zona = TimeZoneInfo.Local;

        IReadOnlyList<Avviso> avvisi = Array.Empty<Avviso>();
        lock (misura)
        {
            if (regole.Count > 0)
            {
                var g = giorno ?? contatore.Oggi;
                var tutti = Valutatore.Valuta(regole, bonus, g.MinutiDi, g.MinutiNellIntervallo, adesso, zona);
                // I nomi dei programmi si leggono qui, col blocco: il giro di misura li sta scrivendo.
                var decisione = Segnalazioni.Decidi(tutti, g.Giorno, registro, regole, g);
                if (decisione.Nuovi.Count > 0)
                {
                    // La coda li tiene in memoria anche se il file non si scrive: tutti, uno per regola.
                    foreach (var s in decisione.Nuovi) coda.Accoda(Eventi.Sforamento(s, g.Giorno, adesso));
                    registro.Pota(Tempo.DataDi(adesso, zona).AddDays(-14));
                    registroDaSalvare = true;
                    avvisi = decisione.ATuttoSchermo;
                }
            }
            if (registroDaSalvare) registroDaSalvare = !SalvaRegistro();
            coda.SalvaSeServe();
        }

        foreach (var a in avvisi) Log.Info($"sforamento della regola {a.RegolaId}{(conAvvisi ? "" : " (solo l'evento)")}");
        if (!conAvvisi || avvisi.Count == 0) return;
        try
        {
            foreach (var a in avvisi)
            {
                var (titolo, testo) = Testi.Fumetto(a);
                Fumetto?.Invoke(titolo, testo, null);
            }
        }
        catch (Exception e)
        {
            Log.Errore("fumetto dello sforamento non partito", e);
        }
        try
        {
            // (0.9) Oltre al fumetto, la finestra a tutto schermo: stessi sforamenti, stesso dedup.
            AvvisoTuttoSchermo?.Invoke(avvisi);
        }
        catch (Exception e)
        {
            Log.Errore("avviso a tutto schermo non partito", e);
        }
    }

    /// <summary>sforamenti.json al meglio possibile. Nel diario il primo errore di una serie e la ripresa, non ogni tentativo.</summary>
    private bool SalvaRegistro()
    {
        try
        {
            Archivio.ScriviJson(percorsi.Sforamenti, registro);
            if (registroNonScritto) Log.Info("sforamenti.json di nuovo scritto");
            registroNonScritto = false;
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            if (!registroNonScritto) Log.Errore("sforamenti.json non scritto: riprovo a ogni valutazione", e);
            registroNonScritto = true;
            return false;
        }
    }

    /// <summary>L'ultimo millisecondo di un giorno locale, per valutarlo fino in fondo.</summary>
    private static long UltimoMsDel(Giornata g, TimeZoneInfo zona, long ripiego) =>
        Tempo.ProvaGiorno(g.Giorno, out var data) ? Tempo.InizioGiorno(data.AddDays(1), zona) - 1 : ripiego;

    /// <summary>Per i test: quanti eventi di quel tipo aspettano di partire (in memoria).</summary>
    internal IReadOnlyList<Evento> EventiInCoda(string tipo) =>
        coda.Prossimi(CodaEventi.Massimo).Where(e => e.Tipo == tipo).ToList();

    /// <summary>I bonus di oggi per regola: quelli del patto più quelli appena concessi e non ancora riletti.</summary>
    private Dictionary<string, int> BonusOggi(long adesso)
    {
        var p = patto;
        var bonus = p?.BonusOggi(adesso) ?? new Dictionary<string, int>();
        var giornoPatto = Tempo.GiornoDi(adesso, PattoLocale.Zona(p?.Fuso ?? "Europe/Rome"));
        lock (misura)
        {
            foreach (var b in bonusLocali.Where(b => b.GiornoPatto == giornoPatto))
            {
                var k = b.RegolaId.ToString(CultureInfo.InvariantCulture);
                bonus[k] = bonus.GetValueOrDefault(k) + b.Minuti;
            }
        }
        return bonus;
    }

    // ---------- Il blocco delle faccende (contratto v3.6) ----------

    /// <summary>
    /// (0.13) Un nuovo stato del blocco (da <c>GET /api/faccende/blocco</c> o dal campo <c>blocco</c> del
    /// patto): si salva su disco e si rivaluta la copertura. Senza rete non si chiama: il blocco resta com'era.
    /// <paramref name="chiestoTick"/> (orologio monotono) è quando la richiesta è partita: uno stato chiesto
    /// prima di quello in uso, e arrivato dopo, non lo sostituisce.
    /// </summary>
    internal void AdottaBlocco(StatoBlocco nuovo, long? chiestoTick = null)
    {
        long tick = chiestoTick ?? Environment.TickCount64;
        lock (bloccoLock)
        {
            if (tick < bloccoChiestoTick) return;
            bloccoChiestoTick = tick;
            statoBlocco = nuovo;
            try
            {
                Archivio.ScriviJson(percorsi.Blocco, nuovo);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                Log.Errore("blocco.json non scritto", e);
            }
        }
        ValutaBlocco(Tempo.AdessoUtcMs());
    }

    /// <summary>
    /// (0.13) All'avvio: se <c>blocco.json</c> si è perso o era rovinato (<c>statoBlocco</c> nullo) ma
    /// l'ultimo "sono vivo" diceva bloccato, si resta coperti con un elenco generico finché il server non
    /// risponde, e si manda una <c>manomissione</c> <c>stato_blocco_perso</c>. Poi si valuta la copertura.
    /// </summary>
    internal void PreparaBlocco(StatoVivo? precedenteVivo, long adesso)
    {
        if (statoBlocco == null && precedenteVivo?.BloccatoFaccende == true)
        {
            lock (bloccoLock) statoBlocco = StatoBlocco.Generico();
            Log.Avviso("stato del blocco perso: resto coperto finché il server non risponde");
            if (Abbinato) coda.Accoda(Eventi.Manomissione(new JsonObject { ["sotto_tipo"] = "stato_blocco_perso" }, adesso));
        }
        ValutaBlocco(adesso);
    }

    /// <summary>
    /// (0.13) Decide se gli schermi vanno coperti adesso: attivo se c'è una faccenda col <c>blocco_da</c>
    /// già passato (contratto v3.6). Se la copertura o l'elenco cambiano, lo dice (<see cref="CambioBlocco"/>).
    /// Non copre mai se il computer non è abbinato. Logica semplice: la macchina degli stati vera è in
    /// <see cref="StatoBlocco"/>, provata nei test; qui si scrive solo il cambiamento.
    /// </summary>
    internal void ValutaBlocco(long adesso)
    {
        var s = statoBlocco;
        bool nuovoCoperto = Abbinato && s != null && s.AttivoA(adesso);
        var faccende = nuovoCoperto ? s!.DaMostrare() : Array.Empty<Faccenda>();

        bool cambiaCopertura;
        bool cambia;
        lock (bloccoLock)
        {
            cambiaCopertura = nuovoCoperto != coperto;
            cambia = cambiaCopertura || (nuovoCoperto && !StessoElenco(faccendeMostrate, faccende));
            coperto = nuovoCoperto;
            faccendeMostrate = faccende;
        }
        if (!cambia) return;
        // (0.13) Il segno in vivo.json segue la copertura subito, non al prossimo salvataggio periodico:
        // così un programma chiuso di colpo durante un blocco si riconosce al riavvio.
        if (cambiaCopertura) ScriviVivo(ChiusuraDaScrivere());
        Log.Info(nuovoCoperto ? $"blocco dei lavori di casa attivo: {faccende.Count} da fare" : "blocco dei lavori di casa tolto");
        try
        {
            CambioBlocco?.Invoke(new VistaBlocco(nuovoCoperto, faccende));
        }
        catch (Exception e)
        {
            Log.Errore("cambio del blocco non consegnato", e);
        }
    }

    private static bool StessoElenco(IReadOnlyList<Faccenda> a, IReadOnlyList<Faccenda> b)
    {
        if (a.Count != b.Count) return false;
        for (int i = 0; i < a.Count; i++)
        {
            if (a[i].Id != b[i].Id || a[i].Titolo != b[i].Titolo || a[i].Nota != b[i].Nota
                || a[i].DataDa != b[i].DataDa || a[i].BloccoDaMs != b[i].BloccoDaMs) return false;
        }
        return true;
    }

    public void Dispose()
    {
        fermaMisura.Set();
        fermaRete.Cancel();
        postino.Dispose();
    }

    private sealed record BonusLocale(long RegolaId, int Minuti, string GiornoPatto, long UtcMs);
}
