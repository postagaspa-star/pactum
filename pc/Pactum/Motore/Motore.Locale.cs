using System.Globalization;
using System.Text.Json.Nodes;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Motore;

/// <summary>Le risposte per l'interfaccia (<c>/locale/*</c> e <c>/server/*</c>, docs/pc-programma.md).</summary>
public sealed partial class Motore
{
    private static readonly HashSet<string> MetodiServer = new(StringComparer.Ordinal) { "GET", "POST", "PATCH", "DELETE" };

    /// <summary>L'interfaccia aspetta al massimo 8 secondi le chiamate /locale/*: si resta sotto.</summary>
    private static readonly TimeSpan TempoInterattivo = TimeSpan.FromSeconds(6);

    /// <summary>GET /locale/stato</summary>
    public JsonObject Stato()
    {
        lock (stato)
        {
            var zona = TimeZoneInfo.Local;
            return new JsonObject
            {
                ["abbinato"] = token != null && config.Server != null && !tokenRifiutato,
                ["server"] = config.Server,
                ["figlio"] = config.Figlio == null ? null : new JsonObject
                {
                    ["id"] = Json.Intero(config.Figlio["id"]),
                    ["nome"] = Json.Testo(config.Figlio["nome"]),
                },
                ["dispositivo"] = config.Dispositivo == null ? null : new JsonObject
                {
                    ["id"] = Json.Intero(config.Dispositivo["id"]),
                    ["nome"] = Json.Testo(config.Dispositivo["nome"]),
                    ["tipo"] = Json.Testo(config.Dispositivo["tipo"]),
                },
                ["versione"] = Versione.Nome,
                ["ultimo_invio_ok"] = ultimoInvioOkMs is long u ? Tempo.IsoLocale(u, zona) : null,
                ["rete_ok"] = reteOk,
                ["patto_aggiornato"] = patto is { } p ? Tempo.IsoLocale(p.AggiornatoUtcMs, zona) : null,
            };
        }
    }

    /// <summary>GET /locale/oggi</summary>
    public JsonObject Oggi()
    {
        long adesso = Tempo.AdessoUtcMs();
        var zona = TimeZoneInfo.Local;
        var p = patto;
        var regole = p?.RegoleDelComputer(IdDispositivo).ToList() ?? new List<Regola>();
        var bonus = BonusOggi(adesso);
        lock (misura)
        {
            return Risposte.Oggi(contatore.Oggi, regole, bonus, adesso, zona);
        }
    }

    /// <summary>GET /locale/visti: programmi e siti degli ultimi 30 giorni, i più usati prima.</summary>
    public JsonObject Visti()
    {
        var giorni = new List<Giornata>();
        string oggi;
        lock (misura)
        {
            oggi = contatore.Oggi.Giorno;
            giorni.Add(contatore.Oggi.Copia());
        }
        var limite = Tempo.DataDi(Tempo.AdessoUtcMs(), TimeZoneInfo.Local).AddDays(-30);
        foreach (var file in Directory.EnumerateFiles(percorsi.Giorni, "*.json"))
        {
            var nome = Path.GetFileNameWithoutExtension(file);
            if (nome == oggi || !Tempo.ProvaGiorno(nome, out var data) || data < limite) continue;
            var g = Archivio.LeggiJson<Giornata>(file);
            if (g != null) giorni.Add(g);
        }
        return Risposte.Visti(giorni);
    }

    /// <summary>GET /locale/serie: calcolata qui dalla striscia del figlio, mai mandata al server.</summary>
    public JsonObject SerieERecord()
    {
        var giorni = patto?.Striscia ?? new List<GiornoPatto>();
        lock (stato)
        {
            var salvata = memoriaSerie.Fine != null ? new SerieSalvata(memoriaSerie.Fine, memoriaSerie.Lunghezza) : null;
            int serie = Nucleo.Serie.Calcola(giorni, salvata)?.Lunghezza ?? 0;
            var daTenere = Nucleo.Serie.Memoria(giorni, salvata);
            int record = Nucleo.Serie.Record(memoriaSerie.Record, serie);
            var nuova = new MemoriaSerie { Fine = daTenere?.Fine, Lunghezza = daTenere?.Lunghezza ?? 0, Record = record };
            if (nuova.Fine != memoriaSerie.Fine || nuova.Lunghezza != memoriaSerie.Lunghezza || nuova.Record != memoriaSerie.Record)
            {
                memoriaSerie = nuova;
                Archivio.ScriviJson(percorsi.Serie, memoriaSerie);
            }
            return new JsonObject { ["serie"] = serie, ["record"] = record };
        }
    }

    /// <summary>POST /locale/abbina: il codice di 6 cifre dato dal genitore.</summary>
    public async Task<JsonObject> AbbinaAsync(string? server, string? codice)
    {
        var baseUrl = NormalizzaServer(server);
        if (baseUrl == null) return Errore("indirizzo_non_valido");
        var c = (codice ?? "").Trim();
        if (c.Length != 6 || !c.All(char.IsAsciiDigit)) return Errore("codice_non_valido");

        // "tipo": "computer" (contratto v3.1): il server rifiuta un codice fatto per un telefono.
        var corpo = new JsonObject { ["codice"] = c, ["tipo"] = "computer", ["versione_app"] = Versione.Nome };
        var r = await postino.InviaAsync("POST", baseUrl, "api/abbina", null, corpo.ToJsonString(), TempoInterattivo).ConfigureAwait(false);
        lock (stato) reteOk = !r.Rete;
        if (r.Rete) return Errore("rete");
        var errore = ErroreAbbinamento(r);
        if (errore != null) return errore;

        var risposta = Json.Analizza(r.Corpo);
        var nuovoToken = Json.Testo(risposta?["token"]);
        if (string.IsNullOrEmpty(nuovoToken) || risposta?["dispositivo"] is not JsonObject dispositivo || risposta["figlio"] is not JsonObject figlio)
        {
            Log.Avviso("risposta di abbinamento senza token, dispositivo o figlio");
            return Errore("rete");
        }

        var figlioPulito = new JsonObject { ["id"] = Json.Intero(figlio["id"]), ["nome"] = Json.Testo(figlio["nome"]) };
        var dispositivoPulito = new JsonObject
        {
            ["id"] = Json.Intero(dispositivo["id"]),
            ["nome"] = Json.Testo(dispositivo["nome"]),
            ["tipo"] = Json.Testo(dispositivo["tipo"]),
        };
        lock (stato)
        {
            config = new Configurazione
            {
                Server = baseUrl,
                TokenProtetto = Dpapi.Proteggi(nuovoToken),
                Dispositivo = dispositivoPulito,
                Figlio = figlioPulito,
                AbbinatoIl = Tempo.IsoLocale(Tempo.AdessoUtcMs(), TimeZoneInfo.Local),
            };
            token = nuovoToken;
            tokenRifiutato = false;
            notifiche = new StatoNotifiche();
            Archivio.ScriviJson(percorsi.Notifiche, notifiche);
            SalvaConfig();
        }
        patto = null;
        // (0.13) Un computer appena abbinato (magari di un altro figlio) riparte senza blocco: si azzera lo
        // stato e blocco.json, e la copertura si toglie. BloccoVisto è già false nella config nuova.
        AdottaBlocco(StatoBlocco.Vuoto, Environment.TickCount64);
        Log.Info("computer abbinato");
        _ = Task.Run(() => SincronizzaAsync("abbinamento"));
        return new JsonObject
        {
            ["ok"] = true,
            ["figlio"] = figlioPulito.DeepClone(),
            ["dispositivo"] = dispositivoPulito.DeepClone(),
        };
    }

    /// <summary>
    /// La risposta di POST /api/abbina tradotta per l'interfaccia, se non è un successo:
    /// 409 codice sbagliato (o <c>tipo_non_corrispondente</c> = codice di un telefono), 422 codice
    /// sbagliato, 429 troppi tentativi (con <c>riprova_tra_secondi</c> del server), il resto è rete.
    /// </summary>
    public static JsonObject? ErroreAbbinamento(Risposta r)
    {
        if (r.Ok) return null;
        if (r.Rete) return Errore("rete");
        if (r.Stato == 409)
        {
            // Il server distingue il codice sbagliato dal codice fatto per un dispositivo di un altro tipo.
            var codice = Json.Testo(DettaglioErrore(r.Corpo)["errore"]);
            return Errore(codice == "tipo_non_corrispondente" ? "tipo_non_corrispondente" : "codice_non_valido");
        }
        if (r.Stato == 422) return Errore("codice_non_valido");
        if (r.Stato == 429)
        {
            var e = Errore("troppi_tentativi");
            if (Json.Intero(DettaglioErrore(r.Corpo)["riprova_tra_secondi"]) is long secondi) e["riprova_tra_secondi"] = secondi;
            return e;
        }
        return Errore("rete");
    }

    /// <summary>POST /locale/bonus: al server senza ripetizioni automatiche, poi limiti locali aggiornati subito.</summary>
    public async Task<JsonObject> BonusAsync(long? regolaId, long? minuti, string? motivo)
    {
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return Errore("rete", new JsonObject());
        if (regolaId == null || minuti == null) return Errore("regola_non_valida", new JsonObject());

        var corpo = new JsonObject { ["minuti"] = minuti.Value, ["regola_id"] = regolaId.Value };
        if (!string.IsNullOrWhiteSpace(motivo)) corpo["motivo"] = motivo;
        var r = await postino.InviaAsync("POST", server, "api/bonus", tok, corpo.ToJsonString(), TempoInterattivo).ConfigureAwait(false);
        Annota(r);
        if (r.Ok)
        {
            long adesso = Tempo.AdessoUtcMs();
            var p = patto;
            lock (misura)
            {
                bonusLocali.Add(new BonusLocale(regolaId.Value, (int)minuti.Value,
                    Tempo.GiornoDi(adesso, PattoLocale.Zona(p?.Fuso ?? "Europe/Rome")), adesso));
            }
            Log.Info($"bonus di {minuti} min sulla regola {regolaId}");
            // I limiti locali contano già il bonus: il patto si rilegge senza far aspettare la finestra.
            _ = Task.Run(() => AggiornaPattoAsync());
            return new JsonObject { ["ok"] = true, ["bonus"] = Json.Analizza(r.Corpo) ?? new JsonObject() };
        }
        if (r.Rete) return Errore("rete", new JsonObject());

        var dettagli = DettaglioErrore(r.Corpo);
        var codice = Json.Testo(dettagli["errore"]);
        if (r.Stato == 409 && (codice == "tetto_superato" || codice == "regola_non_valida")) return Errore(codice, dettagli);
        if (r.Stato is 409 or 422) return Errore("regola_non_valida", dettagli);
        dettagli["stato"] = r.Stato;
        return Errore("rete", dettagli);
    }

    /// <summary>
    /// (0.10) POST /locale/proponi: il figlio propone al genitore un cambio su una sua regola, o di
    /// eliminarla (contratto v3.4, POST /api/proposte col token del dispositivo). Se il genitore
    /// accetta, vale subito. Come il bonus: una richiesta sola, senza ripetizioni automatiche, e gli
    /// esiti del server tradotti in codici che l'interfaccia sa dire (<see cref="ErroreProposta"/>).
    /// </summary>
    public async Task<JsonObject> ProponiAsync(long? regolaId, JsonObject? parametri, string? motivazione)
    {
        var (corpo, errore) = CorpoProposta(regolaId, parametri, motivazione);
        if (corpo == null) return Errore(errore!);
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return Errore("non_abbinato");

        var r = await postino.InviaAsync("POST", server, "api/proposte", tok, corpo.ToJsonString(), TempoInterattivo).ConfigureAwait(false);
        Annota(r);
        var e = ErroreProposta(r);
        if (e != null)
        {
            Log.Info($"proposta sulla regola {regolaId} non accettata dal server: {Json.Testo(e["errore"])}");
            return e;
        }
        // Nel diario mai il perché: è del figlio e va solo al genitore.
        Log.Info($"proposta al genitore sulla regola {regolaId}");
        return new JsonObject { ["ok"] = true, ["proposta"] = PropostaDallaRisposta(r.Corpo) };
    }

    /// <summary>
    /// (0.10) POST /locale/ritira: il figlio ritira una sua proposta ancora in attesa (contratto v3.4,
    /// POST /api/proposte/{id}/ritira, nessun corpo). La regola resta com'è.
    /// </summary>
    public async Task<JsonObject> RitiraAsync(long? propostaId)
    {
        if (propostaId is not > 0) return Errore("non_riuscita");
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return Errore("non_abbinato");

        var percorso = "api/proposte/" + propostaId.Value.ToString(CultureInfo.InvariantCulture) + "/ritira";
        var r = await postino.InviaAsync("POST", server, percorso, tok, null, TempoInterattivo).ConfigureAwait(false);
        Annota(r);
        var e = ErroreRitiro(r);
        if (e != null)
        {
            Log.Info($"proposta {propostaId} non ritirata: {Json.Testo(e["errore"])}");
            return e;
        }
        Log.Info($"proposta {propostaId} ritirata");
        return new JsonObject { ["ok"] = true, ["proposta"] = PropostaDallaRisposta(r.Corpo) };
    }

    /// <summary>
    /// (0.10) Il corpo di POST /api/proposte dal computer: la regola, i parametri proposti (oppure il
    /// marcatore <c>{"azione": "elimina"}</c>) e il perché, solo se c'è. Mai <c>figlio_id</c>: il figlio
    /// è quello del token. Senza regola o senza parametri non si chiama il server: c'è l'errore.
    /// </summary>
    public static (JsonObject? Corpo, string? Errore) CorpoProposta(long? regolaId, JsonObject? parametri, string? motivazione)
    {
        if (regolaId is not > 0) return (null, "regola_non_valida");
        if (parametri == null || parametri.Count == 0) return (null, "parametri_non_validi");
        var corpo = new JsonObject
        {
            ["regola_id"] = regolaId.Value,
            ["parametri_proposti"] = parametri.DeepClone(),
        };
        var perche = motivazione?.Trim();
        if (!string.IsNullOrEmpty(perche)) corpo["motivazione"] = perche;
        return (corpo, null);
    }

    /// <summary>
    /// (0.10) La risposta di POST /api/proposte tradotta per l'interfaccia, se non è un successo
    /// (contratto v3.4). 409 coi codici del contratto (<c>proposta_gia_pendente</c>,
    /// <c>regola_non_valida</c>, <c>dispositivo_revocato</c>), 422 parametri che non vanno bene. Un
    /// server di prima della v3.4 accetta le proposte solo dal genitore e risponde 403 (ruolo
    /// sbagliato): allora va aggiornato il server, non è un errore del figlio.
    /// </summary>
    public static JsonObject? ErroreProposta(Risposta r)
    {
        if (r.Ok) return null;
        if (r.Rete || r.Stato is 502 or 503 or 504) return Errore("rete");
        if (r.Stato == 401) return Errore("non_abbinato");
        if (r.Stato is 403 or 404 or 405) return Errore("server_da_aggiornare");
        if (r.Stato == 422) return Errore("parametri_non_validi");
        var codice = Json.Testo(DettaglioErrore(r.Corpo)["errore"]);
        if (r.Stato == 409 && codice is "proposta_gia_pendente" or "regola_non_valida" or "dispositivo_revocato") return Errore(codice);
        return Errore("non_riuscita", new JsonObject { ["stato"] = r.Stato });
    }

    /// <summary>
    /// (0.10) La risposta di POST /api/proposte/{id}/ritira tradotta per l'interfaccia, se non è un
    /// successo: 409 <c>proposta_non_pendente</c> (il genitore ha già risposto), 403 la proposta non è
    /// del figlio, 404 <c>{"detail": "proposta non trovata"}</c> il server v3.4 non la trova più. Un
    /// server di prima della v3.4 non conosce il ritiro: 404 senza quel dettaglio (<c>Not Found</c>,
    /// nessuna strada) o 405, server da aggiornare.
    /// </summary>
    public static JsonObject? ErroreRitiro(Risposta r)
    {
        if (r.Ok) return null;
        if (r.Rete || r.Stato is 502 or 503 or 504) return Errore("rete");
        if (r.Stato == 401) return Errore("non_abbinato");
        if (r.Stato == 403) return Errore("non_tua");
        if (r.Stato == 404 && string.Equals(Json.Testo((Json.Analizza(r.Corpo) as JsonObject)?["detail"])?.Trim(), "proposta non trovata", StringComparison.OrdinalIgnoreCase))
        {
            return Errore("proposta_non_trovata");
        }
        if (r.Stato is 404 or 405) return Errore("server_da_aggiornare");
        if (r.Stato == 409 && Json.Testo(DettaglioErrore(r.Corpo)["errore"]) == "proposta_non_pendente") return Errore("proposta_non_pendente");
        return Errore("non_riuscita", new JsonObject { ["stato"] = r.Stato });
    }

    /// <summary>
    /// La proposta nella risposta del server: da sola (POST /api/proposte) oppure dentro
    /// <c>"proposta"</c> (come la risposta a una proposta). Null se non c'è.
    /// </summary>
    public static JsonObject? PropostaDallaRisposta(string corpo)
    {
        if (Json.Analizza(corpo) is not JsonObject o) return null;
        if (o["proposta"] is JsonObject dentro) return (JsonObject)dentro.DeepClone();
        return o["id"] != null ? o : null;
    }

    /// <summary>POST /locale/aggiorna</summary>
    public async Task<JsonObject> AggiornaAsync() =>
        new() { ["ok"] = await SincronizzaAsync("interfaccia", TempoInterattivo).ConfigureAwait(false) };

    /// <summary>
    /// /server/&lt;percorso&gt;: la richiesta va al server col token del dispositivo e torna
    /// com'è (stato e JSON). Solo sotto <c>api/</c>. Dopo ogni modifica riuscita si rilegge il patto.
    /// (0.10) Il patto che la finestra legge ogni minuto (<c>GET api/patto</c>) diventa anche quello
    /// del motore: una proposta appena accettata dal genitore vale subito anche per i limiti locali.
    /// </summary>
    public async Task<(int Stato, string Json)> InoltraAsync(string metodo, string percorso, string query, string? corpo)
    {
        if (!MetodiServer.Contains(metodo)) return (405, new JsonObject { ["errore"] = "metodo_non_permesso" }.ToJsonString());
        if (!PercorsoPermesso(percorso)) return (404, new JsonObject { ["errore"] = "percorso_non_permesso" }.ToJsonString());
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return (401, new JsonObject { ["errore"] = "non_abbinato" }.ToJsonString());

        long chiestoAlle = Tempo.AdessoUtcMs();
        long chiestoTick = Environment.TickCount64;
        var r = await postino.InviaAsync(metodo, server, percorso + query, tok, metodo == "GET" ? null : (string.IsNullOrWhiteSpace(corpo) ? null : corpo)).ConfigureAwait(false);
        Annota(r);
        if (r.Rete) return (502, new JsonObject { ["errore"] = "rete" }.ToJsonString());
        if (metodo == "GET" && r.Ok && percorso == "api/patto" && Json.Analizza(r.Corpo) is JsonObject letto)
        {
            // I limiti si rivalutano col giro di ogni 15 secondi: la finestra non aspetta.
            AdottaPatto(letto, chiestoAlle, chiestoTick);
        }
        if (metodo != "GET" && r.Ok) await AggiornaPattoAsync(TimeSpan.FromSeconds(8)).ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(r.Corpo)) return (r.Stato, "{}");
        return Json.Analizza(r.Corpo) != null
            ? (r.Stato, r.Corpo)
            : (r.Stato, new JsonObject { ["errore"] = "risposta_non_valida" }.ToJsonString());
    }

    /// <summary>Solo percorsi dell'API, senza trucchi per uscirne.</summary>
    public static bool PercorsoPermesso(string percorso) =>
        percorso.StartsWith("api/", StringComparison.Ordinal)
        && !percorso.Contains("..", StringComparison.Ordinal)
        && !percorso.Contains("//", StringComparison.Ordinal)
        && !percorso.Contains('\\')
        && !percorso.Contains('%');

    /// <summary>L'indirizzo scritto dal figlio: con o senza https://, senza parametri. Null se non va.</summary>
    public static string? NormalizzaServer(string? testo)
    {
        if (string.IsNullOrWhiteSpace(testo)) return null;
        var t = testo.Trim();
        if (!t.Contains("://", StringComparison.Ordinal)) t = "https://" + t;
        if (!Uri.TryCreate(t, UriKind.Absolute, out var uri)) return null;
        if (uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp) return null;
        if (string.IsNullOrEmpty(uri.Host) || !string.IsNullOrEmpty(uri.UserInfo)) return null;
        if (!string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment)) return null;
        return uri.GetLeftPart(UriPartial.Path).TrimEnd('/');
    }

    /// <summary>Il corpo di un errore del server: <c>{"errore": …}</c> oppure, alla FastAPI, <c>{"detail": {"errore": …}}</c>.</summary>
    public static JsonObject DettaglioErrore(string corpo)
    {
        var nodo = Json.Analizza(corpo);
        if (nodo is JsonObject o && o["detail"] is JsonObject d) return (JsonObject)d.DeepClone();
        if (nodo is JsonObject radice) return (JsonObject)radice.DeepClone();
        return new JsonObject();
    }

    private static JsonObject Errore(string codice, JsonObject? dettagli = null)
    {
        var o = new JsonObject { ["ok"] = false, ["errore"] = codice };
        if (dettagli != null) o["dettagli"] = dettagli;
        return o;
    }
}

/// <summary>Le forme JSON per l'interfaccia, logica pura (provata nei test).</summary>
public static class Risposte
{
    public static JsonObject Oggi(Giornata g, IReadOnlyList<Regola> regole, IReadOnlyDictionary<string, int> bonus, long adesso, TimeZoneInfo zona)
    {
        var programmi = new JsonArray();
        foreach (var (chiave, voce) in g.Programmi.OrderByDescending(p => p.Value.Ms).ThenBy(p => p.Key, StringComparer.Ordinal))
        {
            long minuti = Giornata.Minuti(voce.Ms);
            if (minuti < 1) continue;
            programmi.Add(new JsonObject
            {
                ["chiave"] = chiave,
                ["nome"] = voce.Nome,
                ["categoria"] = Categorie.DiProgramma(chiave),
                ["minuti"] = minuti,
            });
        }

        var siti = new JsonArray();
        foreach (var (dominio, voce) in g.Siti.OrderByDescending(s => s.Value.Ms).ThenByDescending(s => s.Value.Visite).ThenBy(s => s.Key, StringComparer.Ordinal))
        {
            siti.Add(new JsonObject { ["dominio"] = dominio, ["minuti"] = Giornata.Minuti(voce.Ms), ["visite"] = voce.Visite });
        }

        var perRegola = new JsonObject();
        var fasce = new JsonObject();
        foreach (var r in regole)
        {
            var id = r.Id.ToString(CultureInfo.InvariantCulture);
            if (r.Tipo == TipiRegola.LimiteTempo)
            {
                var limite = Valutatore.LimiteEfficace(r, bonus);
                var chiave = r.Stringa("app_o_categoria");
                if (limite == null || chiave == null) continue;
                long minuti = g.MinutiDi(chiave);
                perRegola[id] = new JsonObject
                {
                    ["minuti"] = minuti,
                    ["limite_efficace"] = limite.Value,
                    ["oltre"] = Math.Max(0, minuti - limite.Value),
                };
            }
            else if (r.Tipo == TipiRegola.FasciaOraria && Valutatore.Stato(r, adesso, zona) is { } s)
            {
                fasce[id] = new JsonObject { ["attiva_ora"] = s.AttivaOra, ["prossimo_inizio"] = s.ProssimoInizio, ["fine"] = s.Fine };
            }
        }

        return new JsonObject
        {
            ["giorno"] = g.Giorno,
            ["totale_minuti"] = g.MinutiTotali,
            ["programmi"] = programmi,
            ["siti"] = siti,
            ["regole"] = perRegola,
            ["fasce"] = fasce,
            ["siti_non_leggibili"] = g.SitiNonLeggibili,
        };
    }

    public static JsonObject Visti(IEnumerable<Giornata> giorni)
    {
        var programmi = new Dictionary<string, (string Nome, long Ms)>(StringComparer.Ordinal);
        var siti = new Dictionary<string, long>(StringComparer.Ordinal);
        foreach (var g in giorni.OrderBy(g => g.Giorno, StringComparer.Ordinal))
        {
            foreach (var (chiave, voce) in g.Programmi)
            {
                var prima = programmi.GetValueOrDefault(chiave);
                programmi[chiave] = (voce.Nome, prima.Ms + voce.Ms); // il nome più recente vince
            }
            foreach (var (dominio, voce) in g.Siti) siti[dominio] = siti.GetValueOrDefault(dominio) + voce.Ms;
        }
        return new JsonObject
        {
            ["programmi"] = new JsonArray(programmi
                .OrderByDescending(p => p.Value.Ms).ThenBy(p => p.Key, StringComparer.Ordinal)
                .Select(p => (JsonNode)new JsonObject { ["chiave"] = p.Key, ["nome"] = p.Value.Nome })
                .ToArray()),
            ["siti"] = new JsonArray(siti
                .OrderByDescending(s => s.Value).ThenBy(s => s.Key, StringComparer.Ordinal)
                .Select(s => (JsonNode)JsonValue.Create(s.Key)!)
                .ToArray()),
        };
    }
}
