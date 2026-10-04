using System.Globalization;
using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Motore;

/// <summary>
/// Il giro di rete ogni 5 minuti: battito, fotografie e coda, patto, notifiche. (0.10) Mentre una
/// proposta del figlio aspetta il genitore, in più un giro veloce ogni minuto: patto e notifiche.
/// </summary>
public sealed partial class Motore
{
    /// <summary>Il giro di rete: ogni 5 minuti (contratto). Più corto solo nelle prove.</summary>
    public TimeSpan IntervalloRete { get; set; } = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan IntervalloVersione = TimeSpan.FromHours(12);

    /// <summary>
    /// (0.10) Il giro veloce, mentre una proposta del figlio aspetta il genitore (<c>proposte_inviate</c>
    /// non vuota): se il genitore accetta, la regola nuova vale sul computer entro un minuto, e il fumetto
    /// della risposta arriva con lei.
    /// </summary>
    private static readonly TimeSpan IntervalloVeloce = TimeSpan.FromSeconds(60);

    /// <summary>(0.13, contratto v3.6) GET /api/faccende/blocco: ogni 30 secondi da bloccato, ogni minuto altrimenti.</summary>
    private static readonly TimeSpan IntervalloBloccoCoperto = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan IntervalloBlocco = TimeSpan.FromSeconds(60);

    // Protegge config, token e lo stato della rete.
    private readonly object stato = new();
    private readonly SemaphoreSlim giroRete = new(1, 1);
    private Configurazione config;
    private string? token;
    private volatile PattoLocale? patto;
    private StatoNotifiche notifiche;
    private MemoriaSerie memoriaSerie;
    private bool tokenRifiutato;
    private bool reteOk;
    private long? ultimoInvioOkMs;
    private long prossimaVersioneTick;
    private int? versioneGiaSegnalata;

    // (0.10) Quando è stato chiesto il patto che il motore sta usando (orologio monotono): uno chiesto
    // prima, che arriva dopo, non lo sostituisce. Protetto da "stato".
    private long pattoChiestoTick = long.MinValue;
    private readonly object scritturaPatto = new();

    /// <summary>C'è una versione nuova del programma: titolo, testo e l'indirizzo della pagina da cui scaricarla.</summary>
    public event Action<string, string, string>? AvvisoAggiornamento;

    private long? IdDispositivo
    {
        get
        {
            lock (stato) return Json.Intero(config.Dispositivo?["id"]);
        }
    }

    private (string? Server, string? Token) Credenziali()
    {
        lock (stato) return (config.Server, token);
    }

    private async Task CicloReteAsync(CancellationToken annulla)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(3), annulla).ConfigureAwait(false);
            long prossimoGiro = long.MinValue;
            long prossimoBlocco = long.MinValue;
            while (!annulla.IsCancellationRequested)
            {
                if (Environment.TickCount64 >= prossimoGiro)
                {
                    await SincronizzaAsync("periodico").ConfigureAwait(false);
                    prossimoGiro = Environment.TickCount64 + (long)IntervalloRete.TotalMilliseconds;
                }
                else if (patto?.HaProposteInviate == true)
                {
                    // (0.10) Una proposta del figlio aspetta il genitore: se accetta, vale subito anche qui.
                    await GiroVeloceAsync().ConfigureAwait(false);
                }
                if (Environment.TickCount64 >= prossimoBlocco)
                {
                    // (0.13) La risposta piccola del blocco: più spesso quando si è bloccati.
                    await AggiornaBloccoAsync().ConfigureAwait(false);
                    prossimoBlocco = Environment.TickCount64 + (long)(coperto ? IntervalloBloccoCoperto : IntervalloBlocco).TotalMilliseconds;
                }
                long prossimo = Math.Min(prossimoGiro, prossimoBlocco);
                long resta = prossimo - Environment.TickCount64;
                await Task.Delay(TimeSpan.FromMilliseconds(Math.Clamp(resta, 1_000, (long)IntervalloVeloce.TotalMilliseconds)), annulla).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    /// <summary>
    /// (0.10) Il giro veloce: solo patto e notifiche. Se un giro completo è in corso, questo salta
    /// (il completo li legge già).
    /// </summary>
    internal async Task GiroVeloceAsync()
    {
        if (!await giroRete.WaitAsync(0).ConfigureAwait(false)) return;
        try
        {
            bool ok = await AggiornaPattoAsync().ConfigureAwait(false);
            ok &= await LeggiNotificheAsync(null).ConfigureAwait(false);
            if (!ok) Log.Info("giro veloce: incompleto");
        }
        catch (Exception e)
        {
            Log.Errore("giro veloce", e);
        }
        finally
        {
            giroRete.Release();
        }
    }

    /// <summary>
    /// Un giro completo. Due giri non si sovrappongono mai. true se è andato tutto a buon fine.
    /// Con <paramref name="tempoMassimo"/> (chi aspetta davanti allo schermo) ogni chiamata ha quel
    /// tempo e, se il giro in corso non finisce presto, non si aspetta.
    /// </summary>
    public async Task<bool> SincronizzaAsync(string motivo, TimeSpan? tempoMassimo = null)
    {
        if (tempoMassimo == null) await giroRete.WaitAsync().ConfigureAwait(false);
        else if (!await giroRete.WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false)) return false;
        try
        {
            long adesso = Tempo.AdessoUtcMs();
            AccodaFotografie(adesso);
            var (server, tok) = Credenziali();
            if (server == null || tok == null) return false;
            bool ok = true;

            var battito = await postino.InviaAsync("POST", server, "api/battito", tok,
                Eventi.Battito(adesso, Environment.TickCount64, Batteria()).ToJsonString(), tempoMassimo).ConfigureAwait(false);
            Annota(battito);
            if (battito.Rete)
            {
                Log.Info($"giro di rete ({motivo}): server non raggiungibile, in coda {coda.Conta}");
                return false;
            }
            if (battito.Ok)
            {
                // Consegnato: da qui fa fede l'orologio del server, lo scarto riparte da zero.
                lock (orologioBlocco) orologio.Riancora(Tempo.AdessoUtcMs(), Environment.TickCount64);
            }
            else
            {
                ok = false;
            }

            ok &= await InviaCodaAsync(tempoMassimo).ConfigureAwait(false);
            ok &= await AggiornaPattoAsync(tempoMassimo).ConfigureAwait(false);
            ok &= await LeggiNotificheAsync(tempoMassimo).ConfigureAwait(false);
            if (Environment.TickCount64 >= prossimaVersioneTick) await ControllaVersioneAsync().ConfigureAwait(false);

            Log.Info($"giro di rete ({motivo}): {(ok ? "ok" : "incompleto")}, in coda {coda.Conta}");
            return ok;
        }
        catch (Exception e)
        {
            Log.Errore("giro di rete", e);
            return false;
        }
        finally
        {
            giroRete.Release();
        }
    }

    /// <summary>
    /// (0.13, contratto v3.6) <c>GET /api/faccende/blocco</c>: la risposta piccola che i dispositivi
    /// chiedono spesso. Le risposte, con la lettura più prudente possibile:
    /// <list type="bullet">
    /// <item>niente rete o un <c>5xx</c> → non si cambia niente (un blocco resta finché il server non dice il contrario);</item>
    /// <item><c>200</c> → si adotta, e si segna che questo server conosce il blocco;</item>
    /// <item><c>401</c> (dispositivo revocato) → si toglie la copertura;</item>
    /// <item><c>403</c> → non si cambia niente (non è un "niente faccende");</item>
    /// <item><c>404</c>/<c>405</c> → si toglie la copertura SOLO se il corpo è quello di FastAPI
    /// (<c>{"detail":"Not Found"}</c> / <c>"Method Not Allowed"</c>) E questo server non ha mai mandato il
    /// blocco: così un errore di un proxy su un server che le conosce non sblocca per sbaglio.</item>
    /// </list>
    /// Mai un'eccezione che fermi il ciclo di rete: come il giro veloce.
    /// </summary>
    internal async Task AggiornaBloccoAsync(TimeSpan? tempoMassimo = null)
    {
        try
        {
            var (server, tok) = Credenziali();
            if (server == null || tok == null) return;
            long chiestoTick = Environment.TickCount64;
            var r = await postino.InviaAsync("GET", server, "api/faccende/blocco", tok, null, tempoMassimo).ConfigureAwait(false);
            Annota(r);
            if (r.Rete || r.Stato is 500 or 502 or 503 or 504) return; // senza rete: resta com'era
            if (r.Ok && Json.Analizza(r.Corpo) is JsonObject o)
            {
                SegnaBloccoVisto();
                AdottaBlocco(Blocco.Leggi(o), chiestoTick);
            }
            else if (r.Stato == 401)
            {
                AdottaBlocco(StatoBlocco.Vuoto, chiestoTick); // dispositivo revocato
            }
            else if (r.Stato is 404 or 405 && !BloccoVisto && ÈNonTrovatoDiFastApi(r))
            {
                AdottaBlocco(StatoBlocco.Vuoto, chiestoTick); // server che non conosce le faccende
            }
            // 403, o un 404/405 strano (proxy), o un server che il blocco l'aveva già mandato: non si cambia niente.
        }
        catch (Exception e)
        {
            Log.Errore("giro del blocco", e);
        }
    }

    /// <summary>Il corpo è il "Not Found"/"Method Not Allowed" di FastAPI (un endpoint che non esiste), non un errore nostro con un <c>errore</c>.</summary>
    private static bool ÈNonTrovatoDiFastApi(Risposta r)
    {
        var detail = Json.Testo((Json.Analizza(r.Corpo) as JsonObject)?["detail"])?.Trim();
        return detail is "Not Found" or "Method Not Allowed";
    }

    /// <summary>(0.13) Questo server conosce il blocco: lo si ricorda (legato al server, azzerato al nuovo abbinamento).</summary>
    private void SegnaBloccoVisto()
    {
        lock (stato)
        {
            if (config.BloccoVisto) return;
            config.BloccoVisto = true;
            SalvaConfig();
        }
    }

    private bool BloccoVisto
    {
        get
        {
            lock (stato) return config.BloccoVisto;
        }
    }

    /// <summary>
    /// (0.14) Un evento da solo, subito, prima del resto della coda: la <c>sospensione</c> allo spegnimento, che
    /// deve arrivare prima che la rete se ne vada. L'evento è già in coda (su disco): se arriva, lo si toglie; se
    /// no, resta lì e parte al giro dopo (alla riaccensione) col suo <c>ts_device</c>. Lo stesso id mandato due
    /// volte non fa danni (idempotenza del contratto). Mai un'eccezione.
    /// </summary>
    internal async Task<bool> InviaSubitoAsync(Evento evento, TimeSpan tempoMassimo)
    {
        try
        {
            var (server, tok) = Credenziali();
            if (server == null || tok == null) return false;
            var r = await postino.InviaAsync("POST", server, "api/eventi", tok, Eventi.Lotto(new[] { evento }).ToJsonString(), tempoMassimo).ConfigureAwait(false);
            Annota(r);
            if (!CodaEventi.Riuscito(r.Stato)) return false;
            coda.Rimuovi(new[] { evento.Id });
            return true;
        }
        catch (Exception e)
        {
            Log.Errore("invio subito di un evento", e);
            return false;
        }
    }

    /// <summary>Manda la coda. Con <paramref name="tempoMassimo"/> ogni chiamata ha quel tempo (chiusura, sospensione).</summary>
    private async Task<bool> InviaCodaAsync(TimeSpan? tempoMassimo)
    {
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return false;
        var esito = await coda.InviaAsync(async lotto =>
        {
            var r = await postino.InviaAsync("POST", server, "api/eventi", tok, Eventi.Lotto(lotto).ToJsonString(), tempoMassimo).ConfigureAwait(false);
            Annota(r);
            return r.Stato;
        }, (evento, statoHttp) => Log.Avviso($"evento {evento.Tipo} rifiutato dal server ({statoHttp}), scartato")).ConfigureAwait(false);
        if (esito == EsitoCoda.Vuota)
        {
            lock (stato) ultimoInvioOkMs = Tempo.AdessoUtcMs();
            return true;
        }
        return false;
    }

    /// <summary>
    /// Rilegge il patto: regole del computer, bonus di oggi, striscia. Poi, con <paramref name="valuta"/>,
    /// rivaluta le regole (senza, lo fa chi chiama: v. <c>ValutaConPattoFrescoAsync</c>).
    /// </summary>
    public async Task<bool> AggiornaPattoAsync(TimeSpan? tempoMassimo = null, bool valuta = true)
    {
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return false;
        long inizio = Tempo.AdessoUtcMs();
        long inizioTick = Environment.TickCount64;
        var r = await postino.InviaAsync("GET", server, "api/patto", tok, null, tempoMassimo).ConfigureAwait(false);
        Annota(r);
        if (!r.Ok || Json.Analizza(r.Corpo) is not JsonObject dati) return false;

        AdottaPatto(dati, inizio, inizioTick);
        if (valuta) ValutaRegole(Tempo.AdessoUtcMs());
        return true;
    }

    /// <summary>
    /// Il patto appena arrivato dal server diventa quello del motore (regole, bonus, striscia) e si
    /// salva per l'avvio senza rete. Arriva dal giro di rete o (0.10) dalla finestra, che lo legge ogni
    /// minuto attraverso il ponte. Uno chiesto prima di quello in uso, e arrivato dopo, non lo
    /// sostituisce: false. <paramref name="chiestoAlle"/> = l'ora della richiesta (UTC), per i bonus
    /// appena dati; <paramref name="chiestoTick"/> = la stessa, sull'orologio monotono, per l'ordine.
    /// </summary>
    internal bool AdottaPatto(JsonObject dati, long chiestoAlle, long chiestoTick)
    {
        var nuovo = new PattoLocale(dati, Tempo.AdessoUtcMs());
        lock (stato)
        {
            if (chiestoTick < pattoChiestoTick) return false;
            pattoChiestoTick = chiestoTick;
            patto = nuovo;

            // Il genitore può rinominare figlio e dispositivo: si tiene il nome nuovo.
            bool cambiato = false;
            if (dati["figlio"] is JsonObject f && Json.Intero(f["id"]) != null)
            {
                config.Figlio = new JsonObject { ["id"] = Json.Intero(f["id"]), ["nome"] = Json.Testo(f["nome"]) };
                cambiato = true;
            }
            if (dati["dispositivo"] is JsonObject d && Json.Intero(d["id"]) != null)
            {
                config.Dispositivo = new JsonObject { ["id"] = Json.Intero(d["id"]), ["nome"] = Json.Testo(d["nome"]), ["tipo"] = Json.Testo(d["tipo"]) };
                cambiato = true;
            }
            if (cambiato) SalvaConfig();
        }
        lock (scritturaPatto)
        {
            // Se intanto ne è arrivato uno più nuovo, su disco va quello (lo scrive lui).
            if (ReferenceEquals(patto, nuovo))
            {
                try
                {
                    Archivio.ScriviJson(percorsi.Patto, new PattoSalvato { AggiornatoUtcMs = nuovo.AggiornatoUtcMs, Patto = dati });
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    Log.Errore("patto.json non scritto", e);
                }
            }
        }
        // Il patto riletto contiene già i bonus concessi prima della richiesta.
        lock (misura) bonusLocali.RemoveAll(b => b.UtcMs < chiestoAlle);
        // (0.13, contratto v3.6) Il patto porta anche il blocco delle faccende: lo si adotta qui, così è
        // fresco anche al giro della finestra (ogni minuto). Un patto di un server vecchio non ha il campo:
        // lì il blocco lo tiene aggiornato il giro dedicato (AggiornaBloccoAsync). Si passa il tick della
        // richiesta del patto: un patto vecchio arrivato dopo non scavalca una risposta più fresca di /blocco.
        if (dati["blocco"] is JsonObject bloccoJson)
        {
            SegnaBloccoVisto();
            AdottaBlocco(Blocco.Leggi(bloccoJson), chiestoTick);
        }
        return true;
    }

    /// <summary>
    /// Le notifiche nuove diventano fumetti. Il computer non le marca lette (dalla v3.1 varrebbe solo per
    /// lui): tiene il segno dell'ultima vista (<c>notifiche.json</c>) e (0.10) chiede solo quelle dopo
    /// (<c>?dopo_id</c>, contratto v3.3). Un server più vecchio ignora il parametro e le manda tutte: il
    /// filtro sull'ultima vista tiene comunque solo le nuove.
    /// </summary>
    private async Task<bool> LeggiNotificheAsync(TimeSpan? tempoMassimo)
    {
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return false;
        long? giaViste;
        lock (stato) giaViste = notifiche.UltimoId;
        var percorso = giaViste is long ultimaVista
            ? "api/notifiche?dopo_id=" + Math.Max(0, ultimaVista).ToString(CultureInfo.InvariantCulture)
            : "api/notifiche";
        var r = await postino.InviaAsync("GET", server, percorso, tok, null, tempoMassimo).ConfigureAwait(false);
        Annota(r);
        if (!r.Ok || (Json.Analizza(r.Corpo) as JsonObject)?["notifiche"] is not JsonArray lista) return false;

        var voci = lista
            .Select(n => (Id: Json.Intero(n?["id"]), Tipo: Json.Testo(n?["tipo"]) ?? "", Messaggio: Json.Testo(n?["messaggio"]) ?? "",
                // (0.10) Il payload dice l'esito di una proposta del figlio e chi ne è l'autore (contratto v3.4).
                Payload: n?["payload"] as JsonObject))
            .Where(n => n.Id != null)
            .OrderBy(n => n.Id)
            .ToList();
        long massimo = voci.Count > 0 ? voci.Max(n => n.Id!.Value) : 0;

        List<(long? Id, string Tipo, string Messaggio, JsonObject? Payload)> nuove;
        lock (stato)
        {
            if (notifiche.UltimoId == null)
            {
                // Primo giro dopo l'abbinamento: le notifiche vecchie non si ripetono.
                notifiche.UltimoId = massimo;
                Archivio.ScriviJson(percorsi.Notifiche, notifiche);
                return true;
            }
            var ultimo = notifiche.UltimoId.Value;
            nuove = voci.Where(n => n.Id > ultimo).ToList();
            if (nuove.Count == 0) return true;
            notifiche.UltimoId = Math.Max(ultimo, massimo);
            Archivio.ScriviJson(percorsi.Notifiche, notifiche);
        }
        foreach (var n in nuove.TakeLast(5))
        {
            // (0.10) Col clic, il fumetto di una proposta apre Proposte (quello di un verdetto, il Diario).
            var (titolo, testo, sezione) = Testi.Notifica(n.Tipo, n.Messaggio, n.Payload);
            Fumetto?.Invoke(titolo, testo, sezione);
        }
        if (nuove.Count > 5) Fumetto?.Invoke(Testi.TitoloNovita, $"Ci sono altre {nuove.Count - 5} novità: aprile in Pactum.", null);
        return true;
    }

    private async Task ControllaVersioneAsync()
    {
        var (server, _) = Credenziali();
        if (server == null) return;
        var r = await postino.InviaAsync("GET", server, "api/versione", null, null).ConfigureAwait(false);
        if (!r.Ok) return;
        prossimaVersioneTick = Environment.TickCount64 + (long)IntervalloVersione.TotalMilliseconds;
        var computer = Json.Analizza(r.Corpo)?["computer"];
        var codice = Json.Intero(computer?["versione_code"]);
        if (codice == null || codice <= Versione.Codice || versioneGiaSegnalata == (int)codice) return;
        versioneGiaSegnalata = (int)codice;
        var nome = Json.Testo(computer?["versione_nome"]) ?? codice.Value.ToString(CultureInfo.InvariantCulture);
        Log.Info($"versione nuova disponibile: {nome}");
        // Niente download né sostituzione automatica: il programma avvisa e basta. Il clic sull'avviso
        // apre la pagina /scarica del server nel browser; da lì il figlio scarica e installa come la prima volta.
        var pagina = server.TrimEnd('/') + "/scarica";
        AvvisoAggiornamento?.Invoke("C'è una versione nuova di Pactum",
            $"È uscita la {nome}. Fai clic qui per aprire la pagina da cui scaricarla.", pagina);
    }

    /// <summary>Tiene nota dello stato della rete e del token dopo ogni risposta.</summary>
    private void Annota(Risposta r)
    {
        lock (stato)
        {
            reteOk = !r.Rete;
            if (r.Stato == 401)
            {
                if (!tokenRifiutato) Log.Avviso("il server non riconosce più il token di questo computer");
                tokenRifiutato = true;
            }
            else if (r.Ok)
            {
                tokenRifiutato = false;
            }
        }
    }

    private void SalvaConfig()
    {
        try
        {
            Archivio.ScriviJson(percorsi.Config, config);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("config.json non scritto", e);
        }
    }

    private static int? Batteria()
    {
        var s = System.Windows.Forms.SystemInformation.PowerStatus;
        if (s.BatteryChargeStatus.HasFlag(System.Windows.Forms.BatteryChargeStatus.NoSystemBattery)) return null;
        if (s.BatteryChargeStatus.HasFlag(System.Windows.Forms.BatteryChargeStatus.Unknown)) return null;
        var percento = (int)Math.Round(s.BatteryLifePercent * 100);
        return percento is >= 0 and <= 100 ? percento : null;
    }
}
