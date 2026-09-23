using System.Globalization;
using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Motore;

/// <summary>Il giro di rete ogni 5 minuti: battito, fotografie e coda, patto, notifiche.</summary>
public sealed partial class Motore
{
    /// <summary>Il giro di rete: ogni 5 minuti (contratto). Più corto solo nelle prove.</summary>
    public TimeSpan IntervalloRete { get; set; } = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan IntervalloVersione = TimeSpan.FromHours(12);

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
            while (!annulla.IsCancellationRequested)
            {
                await SincronizzaAsync("periodico").ConfigureAwait(false);
                await Task.Delay(IntervalloRete, annulla).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
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

    /// <summary>Rilegge il patto: regole del computer, bonus di oggi, striscia. Poi rivaluta le regole.</summary>
    public async Task<bool> AggiornaPattoAsync(TimeSpan? tempoMassimo = null)
    {
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return false;
        long inizio = Tempo.AdessoUtcMs();
        var r = await postino.InviaAsync("GET", server, "api/patto", tok, null, tempoMassimo).ConfigureAwait(false);
        Annota(r);
        if (!r.Ok || Json.Analizza(r.Corpo) is not JsonObject dati) return false;

        var nuovo = new PattoLocale(dati, Tempo.AdessoUtcMs());
        patto = nuovo;
        try
        {
            Archivio.ScriviJson(percorsi.Patto, new PattoSalvato { AggiornatoUtcMs = nuovo.AggiornatoUtcMs, Patto = dati });
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("patto.json non scritto", e);
        }

        lock (stato)
        {
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
        // Il patto riletto contiene già i bonus concessi prima della richiesta.
        lock (misura) bonusLocali.RemoveAll(b => b.UtcMs < inizio);
        ValutaRegole(Tempo.AdessoUtcMs());
        return true;
    }

    /// <summary>Le notifiche nuove diventano fumetti. Non si marcano lette: sul server varrebbe per tutti i dispositivi.</summary>
    private async Task<bool> LeggiNotificheAsync(TimeSpan? tempoMassimo)
    {
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return false;
        var r = await postino.InviaAsync("GET", server, "api/notifiche", tok, null, tempoMassimo).ConfigureAwait(false);
        Annota(r);
        if (!r.Ok || Json.Analizza(r.Corpo)?["notifiche"] is not JsonArray lista) return false;

        var voci = lista
            .Select(n => (Id: Json.Intero(n?["id"]), Tipo: Json.Testo(n?["tipo"]) ?? "", Messaggio: Json.Testo(n?["messaggio"]) ?? ""))
            .Where(n => n.Id != null)
            .OrderBy(n => n.Id)
            .ToList();
        long massimo = voci.Count > 0 ? voci.Max(n => n.Id!.Value) : 0;

        List<(long? Id, string Tipo, string Messaggio)> nuove;
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
        foreach (var n in nuove.TakeLast(5)) Fumetto?.Invoke(TitoloNotifica(n.Tipo), n.Messaggio);
        if (nuove.Count > 5) Fumetto?.Invoke("Novità dal patto", $"Ci sono altre {nuove.Count - 5} novità: aprile in Pactum.");
        return true;
    }

    public static string TitoloNotifica(string tipo) => tipo switch
    {
        "nuova_proposta" => "Nuova proposta del genitore",
        "verdetto" => "Esito della tua dichiarazione",
        "segno" => "Un segno dal genitore",
        _ => "Novità dal patto",
    };

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
