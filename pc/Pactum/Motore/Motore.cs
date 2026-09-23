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

    private readonly object orologioBlocco = new();
    private readonly SentinellaOrologio orologio;

    private Thread? filoMisura;
    private Task? cicloRete;
    private long giri;

    private volatile bool bloccato;
    private volatile bool sospeso;
    private volatile bool schermoAcceso = true;
    private volatile string? chiusuraInCorso;
    private long chiusuraInCorsoDalTick;

    public Motore(Percorsi percorsi)
    {
        this.percorsi = percorsi;
        Log.Inizia(percorsi.Log);
        config = Archivio.LeggiJson<Configurazione>(percorsi.Config) ?? new Configurazione();
        token = Dpapi.Svela(config.TokenProtetto);
        if (config.TokenProtetto != null && token == null) Log.Avviso("token presente ma non decifrabile con l'utente attuale");
        coda = new CodaEventi(percorsi.Coda);
        registro = Archivio.LeggiJson<RegistroSforamenti>(percorsi.Sforamenti) ?? new RegistroSforamenti();
        notifiche = Archivio.LeggiJson<StatoNotifiche>(percorsi.Notifiche) ?? new StatoNotifiche();
        memoriaSerie = Archivio.LeggiJson<MemoriaSerie>(percorsi.Serie) ?? new MemoriaSerie();
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

    /// <summary>Un fumetto per l'icona: titolo e testo. Arriva da un filo qualsiasi.</summary>
    public event Action<string, string>? Fumetto;

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
        ValutaAvvio();
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
        if (Abbinato) coda.Accoda(Eventi.Sospensione("sospensione", adesso));
        lock (misura) SalvaGiorno(contatore.Oggi);
        ScriviVivo(null);
        Log.Info("sospensione");
        _ = InviaCodaAsync(TimeSpan.FromSeconds(2));
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

    /// <summary>L'utente esce o Windows si spegne (SessionEnding): si scrive la chiusura pulita subito.</summary>
    public void FineSessione(bool spegnimento)
    {
        var motivo = spegnimento ? Chiusure.Spegnimento : Chiusure.Disconnessione;
        ImpostaChiusura(motivo);
        long adesso = Tempo.AdessoUtcMs();
        if (Abbinato) coda.Accoda(Eventi.Sospensione(motivo, adesso));
        AccodaFotografie(adesso);
        lock (misura) SalvaGiorno(contatore.Oggi);
        ScriviVivo(motivo);
        Log.Info($"fine sessione ({motivo})");
        InviaCodaAsync(TimeSpan.FromSeconds(2)).Wait(TimeSpan.FromSeconds(2.5));
    }

    public void SchermoBloccato(bool bloccato) => this.bloccato = bloccato;

    public void SchermoAcceso(bool acceso) => schermoAcceso = acceso;

    private void ValutaAvvio()
    {
        var precedente = Archivio.LeggiJson<StatoVivo>(percorsi.Vivo);
        long adesso = Tempo.AdessoUtcMs();
        var esito = Vivo.ValutaAvvio(precedente, adesso, Environment.TickCount64, IdAvvioWindows());
        if (esito.ProgrammaChiuso != null)
        {
            Log.Avviso(Json.Booleano(esito.ProgrammaChiuso["avvio_ritardato"]) == true
                ? "avvio in ritardo: il computer era acceso senza Pactum"
                : "il programma era stato chiuso mentre Windows era acceso");
        }
        if (esito.CambioOra != null) Log.Avviso("l'orologio è stato spostato mentre il programma era chiuso");
        if (!Abbinato) return;
        if (esito.ProgrammaChiuso != null) coda.Accoda(Eventi.Manomissione(esito.ProgrammaChiuso, adesso));
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

        if (giri % GiriTraSalvataggi == 0 || esito.GiorniChiusi.Count > 0)
        {
            lock (misura) SalvaGiorno(contatore.Oggi);
            ScriviVivo(ChiusuraDaScrivere());
        }
        if (giri % GiriTraValutazioni == 0 || esito.GiorniChiusi.Count > 0) ValutaRegole(utc);
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

    private Osservazione Osserva()
    {
        if (sospeso || bloccato || !Sessione.Disponibile()) return Osservazione.Assente;
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
        var dominio = lettura.Dominio;
        if (dominio != null && SitiSolo != null && !SitiSolo.Contains(dominio)) dominio = null;
        return new Osservazione(true, finestra.Chiave, finestra.Nome, true, dominio, lettura.Fallita);
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

    private void ValutaRegole(long adesso)
    {
        var p = patto;
        if (p == null || !Abbinato) return;
        var zona = TimeZoneInfo.Local;
        var regole = p.RegoleDelComputer(IdDispositivo).ToList();
        if (regole.Count == 0) return;
        var bonus = BonusOggi(adesso);

        List<Sforamento> nuovi;
        Giornata oggi;
        lock (misura)
        {
            oggi = contatore.Oggi;
            var tutti = Valutatore.Valuta(regole, bonus, oggi.MinutiDi, oggi.MinutiNellIntervallo, adesso, zona);
            nuovi = registro.Nuovi(tutti, oggi.Giorno);
            if (nuovi.Count == 0) return;
            foreach (var s in nuovi) coda.Accoda(Eventi.Sforamento(s, oggi.Giorno, adesso));
            registro.Pota(Tempo.DataDi(adesso, zona).AddDays(-14));
            Archivio.ScriviJson(percorsi.Sforamenti, registro);
        }
        foreach (var s in nuovi)
        {
            Log.Info($"sforamento della regola {s.RegolaId}");
            var regola = regole.FirstOrDefault(r => r.Id == s.RegolaId);
            if (s.Tipo == TipiRegola.FasciaOraria)
            {
                Fumetto?.Invoke("Hai usato il computer in una fascia che ti sei imposto",
                    $"Hai usato il computer {s.MinutiOltre} min in una fascia che ti sei imposto ({regola?.Stringa("dalle") ?? "?"}–{regola?.Stringa("alle") ?? "?"}). Nessun blocco: è il tuo patto.");
            }
            else
            {
                var bersaglio = NomeBersaglio(regola?.Stringa("app_o_categoria"), oggi);
                Fumetto?.Invoke("Oggi sei andato oltre",
                    $"{bersaglio}: oggi sei andato {s.MinutiOltre} min oltre il limite che ti sei dato ({s.LimiteEfficace} min). Nessun blocco: è il tuo patto.");
            }
        }
    }

    private static string NomeBersaglio(string? chiave, Giornata oggi)
    {
        if (chiave == null) return "?";
        var k = chiave.Trim().ToLowerInvariant();
        if (k.StartsWith(Categorie.Prefisso, StringComparison.Ordinal))
        {
            var c = k[Categorie.Prefisso.Length..];
            return c.Length > 0 ? char.ToUpper(c[0], CultureInfo.InvariantCulture) + c[1..] : c;
        }
        if (k.StartsWith(Programma.PrefissoSito, StringComparison.Ordinal)) return k[Programma.PrefissoSito.Length..];
        if (oggi.Programmi.TryGetValue(k, out var voce)) return voce.Nome;
        return Programma.NomeDiRipiego(k);
    }

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

    public void Dispose()
    {
        fermaMisura.Set();
        fermaRete.Cancel();
        postino.Dispose();
    }

    private sealed record BonusLocale(long RegolaId, int Minuti, string GiornoPatto, long UtcMs);
}
