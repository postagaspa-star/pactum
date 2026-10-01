using System.Text.Json.Nodes;
using Pactum.Interfaccia;
using Pactum.Motore;
using Pactum.Nucleo;
using Pactum.Sistema;
using MotorePactum = Pactum.Motore.Motore;

namespace Pactum.Tests;

/// <summary>
/// (0.10) Le proposte del figlio al genitore dal computer (contratto v3.4): il corpo che parte, la
/// richiesta vera verso il server, gli esiti tradotti per l'interfaccia (anche col server di prima
/// della v3.4, che va aggiornato) e i fumetti delle notifiche nuove.
/// </summary>
public class ProposteTest
{
    private const string Token = "token-del-computer";

    private static JsonObject Minecraft(int minuti) =>
        new() { ["app_o_categoria"] = "exe:minecraft.exe", ["minuti_al_giorno"] = minuti };

    private static IEnumerable<string> Chiavi(JsonNode? n) => ((JsonObject)n!).Select(p => p.Key).OrderBy(k => k, StringComparer.Ordinal);

    private static string? Errore(JsonObject esito)
    {
        Assert.False(Json.Booleano(esito["ok"]));
        return Json.Testo(esito["errore"]);
    }

    /// <summary>Un computer collegato al server finto, come dopo l'abbinamento.</summary>
    private static MotorePactum Collegato(CartellaTemporanea c, string server)
    {
        var percorsi = new Percorsi(c.Percorso);
        Archivio.ScriviJson(percorsi.Config, new Configurazione
        {
            Server = server,
            TokenProtetto = Dpapi.Proteggi(Token),
            Dispositivo = new JsonObject { ["id"] = 2, ["nome"] = "Computer", ["tipo"] = "computer" },
            Figlio = new JsonObject { ["id"] = 1, ["nome"] = "Andrea" },
        });
        return new MotorePactum(percorsi);
    }

    /// <summary>La proposta come la crea il server v3.4 (contratto, "La proposta nelle risposte").</summary>
    private static string PropostaCreata(long id, long regolaId, string stato = "pendente") => new JsonObject
    {
        ["id"] = id,
        ["regola_id"] = regolaId,
        ["parametri_proposti"] = Minecraft(90),
        ["motivazione"] = "Sabato c'è il torneo",
        ["confronto"] = "+30 min al giorno rispetto ad ora",
        ["direzione"] = "allenta",
        ["stato"] = stato,
        ["usata"] = false,
        ["ts_server"] = "2026-10-01T15:00:00+00:00",
        ["risposta"] = null,
        ["autore"] = "figlio",
    }.ToJsonString();

    // ---------- il corpo di POST /api/proposte ----------

    [Fact]
    public void Il_corpo_ha_regola_parametri_e_il_perche_solo_se_c_e()
    {
        var parametri = Minecraft(90);
        var (corpo, errore) = MotorePactum.CorpoProposta(12, parametri, "  Sabato c'è il torneo  ");
        Assert.Null(errore);
        Assert.Equal(new[] { "motivazione", "parametri_proposti", "regola_id" }, Chiavi(corpo));
        Assert.Equal(12, Json.Intero(corpo!["regola_id"]));
        Assert.Equal(90, Json.Intero(corpo["parametri_proposti"]!["minuti_al_giorno"]));
        Assert.Equal("Sabato c'è il torneo", Json.Testo(corpo["motivazione"]));
        // Una copia: i parametri di chi chiama restano suoi.
        Assert.NotSame(parametri, corpo["parametri_proposti"]);

        var (senzaPerche, _) = MotorePactum.CorpoProposta(12, Minecraft(90), "   ");
        Assert.Equal(new[] { "parametri_proposti", "regola_id" }, Chiavi(senzaPerche));
        Assert.Equal(new[] { "parametri_proposti", "regola_id" }, Chiavi(MotorePactum.CorpoProposta(12, Minecraft(90), null).Corpo));
    }

    [Fact]
    public void Proporre_di_eliminare_manda_il_marcatore_del_contratto()
    {
        var (corpo, _) = MotorePactum.CorpoProposta(15, new JsonObject { ["azione"] = "elimina" }, null);
        Assert.Equal("{\"azione\":\"elimina\"}", corpo!["parametri_proposti"]!.ToJsonString());
        // Mai figlio_id: il figlio è quello del token.
        Assert.Null(corpo["figlio_id"]);
    }

    [Theory]
    [InlineData(null, "regola_non_valida")]
    [InlineData(0L, "regola_non_valida")]
    [InlineData(-3L, "regola_non_valida")]
    public void Senza_una_regola_non_c_e_niente_da_proporre(long? regolaId, string errore)
    {
        var (corpo, e) = MotorePactum.CorpoProposta(regolaId, Minecraft(90), null);
        Assert.Null(corpo);
        Assert.Equal(errore, e);
    }

    [Fact]
    public void Senza_parametri_non_c_e_niente_da_proporre()
    {
        Assert.Equal("parametri_non_validi", MotorePactum.CorpoProposta(12, null, null).Errore);
        Assert.Equal("parametri_non_validi", MotorePactum.CorpoProposta(12, new JsonObject(), "perché").Errore);
    }

    // ---------- gli esiti del server tradotti ----------

    [Theory]
    [InlineData(409, "{\"detail\": {\"errore\": \"proposta_gia_pendente\"}}", "proposta_gia_pendente")]
    [InlineData(409, "{\"detail\": {\"errore\": \"regola_non_valida\"}}", "regola_non_valida")]
    [InlineData(409, "{\"errore\": \"regola_non_valida\"}", "regola_non_valida")]
    [InlineData(409, "{\"detail\": {\"errore\": \"dispositivo_revocato\"}}", "dispositivo_revocato")]
    [InlineData(409, "{\"detail\": {\"errore\": \"qualcosa_di_nuovo\"}}", "non_riuscita")]
    [InlineData(422, "{\"detail\": [{\"loc\": [\"body\", \"parametri_proposti\"], \"msg\": \"x\"}]}", "parametri_non_validi")]
    // Il server di prima della v3.4: le proposte solo col token del genitore.
    [InlineData(403, "{\"detail\": \"serve il token del genitore\"}", "server_da_aggiornare")]
    [InlineData(404, "{\"detail\": \"Not Found\"}", "server_da_aggiornare")]
    [InlineData(405, "{\"detail\": \"Method Not Allowed\"}", "server_da_aggiornare")]
    [InlineData(401, "{\"detail\": \"token sconosciuto\"}", "non_abbinato")]
    [InlineData(0, "", "rete")]
    [InlineData(502, "<html>Bad Gateway</html>", "rete")]
    [InlineData(500, "{\"detail\": \"errore interno\"}", "non_riuscita")]
    public void Gli_esiti_della_proposta_per_l_interfaccia(int stato, string corpo, string errore)
    {
        var e = MotorePactum.ErroreProposta(new Risposta(stato, corpo));
        Assert.NotNull(e);
        Assert.Equal(errore, Errore(e!));
    }

    [Fact]
    public void Una_proposta_arrivata_non_e_un_errore_e_quella_non_riuscita_dice_lo_stato()
    {
        Assert.Null(MotorePactum.ErroreProposta(new Risposta(200, PropostaCreata(31, 12))));
        Assert.Null(MotorePactum.ErroreRitiro(new Risposta(200, PropostaCreata(31, 12, "ritirata"))));
        var e = MotorePactum.ErroreProposta(new Risposta(500, "{}"))!;
        Assert.Equal(500, Json.Intero(e["dettagli"]!["stato"]));
    }

    [Theory]
    [InlineData(409, "{\"detail\": {\"errore\": \"proposta_non_pendente\"}}", "proposta_non_pendente")]
    [InlineData(403, "{\"detail\": \"la proposta è del genitore\"}", "non_tua")]
    // La v3.4 non trova più la proposta: lo dice col suo dettaglio.
    [InlineData(404, "{\"detail\": \"proposta non trovata\"}", "proposta_non_trovata")]
    // Il server di prima della v3.4 non conosce il ritiro: nessuna strada, o il metodo che non va.
    [InlineData(404, "{\"detail\": \"Not Found\"}", "server_da_aggiornare")]
    [InlineData(404, "<html>404</html>", "server_da_aggiornare")]
    [InlineData(404, "[]", "server_da_aggiornare")]
    [InlineData(405, "{\"detail\": \"Method Not Allowed\"}", "server_da_aggiornare")]
    [InlineData(401, "{\"detail\": \"dispositivo revocato\"}", "non_abbinato")]
    [InlineData(0, "", "rete")]
    [InlineData(504, "", "rete")]
    [InlineData(409, "{\"detail\": {\"errore\": \"altro\"}}", "non_riuscita")]
    [InlineData(500, "{}", "non_riuscita")]
    public void Gli_esiti_del_ritiro_per_l_interfaccia(int stato, string corpo, string errore)
    {
        Assert.Equal(errore, Errore(MotorePactum.ErroreRitiro(new Risposta(stato, corpo))!));
    }

    [Fact]
    public void Per_ogni_esito_del_motore_l_interfaccia_ha_la_sua_frase()
    {
        // I codici sono l'accordo fra motore e interfaccia: ognuno ha la sua frase in testi.js (che
        // viaggia dentro l'exe, Pactum.csproj); "non_riuscita" è la frase di riserva.
        string Corpo(string codice) => "{\"detail\": {\"errore\": \"" + codice + "\"}}";
        var risposte = new[]
        {
            new Risposta(0, ""), new Risposta(401, "{}"), new Risposta(403, "{}"), new Risposta(404, "{}"), new Risposta(422, "{}"),
            new Risposta(409, Corpo("proposta_gia_pendente")), new Risposta(409, Corpo("regola_non_valida")),
            new Risposta(409, Corpo("dispositivo_revocato")), new Risposta(409, Corpo("proposta_non_pendente")),
            new Risposta(404, "{\"detail\": \"proposta non trovata\"}"),
        };
        var codici = risposte
            .SelectMany(r => new[] { MotorePactum.ErroreProposta(r), MotorePactum.ErroreRitiro(r) })
            .Select(e => Json.Testo(e!["errore"])!)
            .Where(c => c != "non_riuscita")
            .Distinct()
            .ToList();
        Assert.Equal(10, codici.Count);

        using var flusso = typeof(Testi).Assembly.GetManifestResourceStream("ui/testi.js");
        Assert.NotNull(flusso);
        using var lettore = new StreamReader(flusso!);
        var testi = lettore.ReadToEnd();
        foreach (var codice in codici) Assert.Contains("case '" + codice + "':", testi);
        // Le frasi decise: il server da aggiornare non è mai un errore generico.
        Assert.Contains("'Per mandare proposte serve aggiornare il server di Pactum.'", testi);
        Assert.Contains("'Per ritirare una proposta serve aggiornare il server di Pactum.'", testi);
        Assert.Contains("'C\\'è già una proposta in attesa su questa regola.'", testi);
    }

    [Fact]
    public void La_proposta_si_legge_da_sola_o_dentro_proposta()
    {
        Assert.Equal(31, Json.Intero(MotorePactum.PropostaDallaRisposta(PropostaCreata(31, 12))!["id"]));
        var dentro = "{\"proposta\": " + PropostaCreata(32, 12, "ritirata") + ", \"regola\": null}";
        Assert.Equal("ritirata", Json.Testo(MotorePactum.PropostaDallaRisposta(dentro)!["stato"]));
        Assert.Null(MotorePactum.PropostaDallaRisposta("<html>"));
        Assert.Null(MotorePactum.PropostaDallaRisposta("{}"));
    }

    // ---------- le richieste vere, verso un server finto ----------

    [Fact]
    public async Task La_proposta_parte_col_token_del_computer_e_torna_all_interfaccia()
    {
        await using var server = new ServerFinto(_ => (200, PropostaCreata(31, 12)));
        using var c = new CartellaTemporanea();
        using var motore = Collegato(c, server.Indirizzo);

        var esito = await motore.ProponiAsync(12, Minecraft(90), "Sabato c'è il torneo");

        Assert.Equal(new[] { "ok", "proposta" }, Chiavi(esito));
        Assert.True(Json.Booleano(esito["ok"]));
        Assert.Equal(31, Json.Intero(esito["proposta"]!["id"]));
        Assert.Equal("figlio", Json.Testo(esito["proposta"]!["autore"]));
        var r = Assert.Single(server.Ricevute);
        Assert.Equal("POST", r.Metodo);
        Assert.Equal("/api/proposte", r.Percorso);
        Assert.Equal("Bearer " + Token, r.Autorizzazione);
        var corpo = JsonNode.Parse(r.Corpo)!;
        Assert.Equal(new[] { "motivazione", "parametri_proposti", "regola_id" }, Chiavi(corpo));
        Assert.Equal("Sabato c'è il torneo", Json.Testo(corpo["motivazione"]));
        Assert.Equal("exe:minecraft.exe", Json.Testo(corpo["parametri_proposti"]!["app_o_categoria"]));
    }

    [Fact]
    public async Task Il_ritiro_parte_senza_corpo_sulla_proposta_giusta()
    {
        await using var server = new ServerFinto(_ => (200, PropostaCreata(31, 12, "ritirata")));
        using var c = new CartellaTemporanea();
        using var motore = Collegato(c, server.Indirizzo);

        var esito = await motore.RitiraAsync(31);

        Assert.True(Json.Booleano(esito["ok"]));
        Assert.Equal("ritirata", Json.Testo(esito["proposta"]!["stato"]));
        var r = Assert.Single(server.Ricevute);
        Assert.Equal("POST", r.Metodo);
        Assert.Equal("/api/proposte/31/ritira", r.Percorso);
        Assert.Equal("Bearer " + Token, r.Autorizzazione);
        Assert.Equal("", r.Corpo);
    }

    [Fact]
    public async Task Con_un_server_di_prima_della_v34_serve_aggiornare_il_server()
    {
        await using var server = new ServerFinto(r => r.Percorso == "/api/proposte"
            ? (403, "{\"detail\": \"serve il token del genitore\"}")
            : (404, "{\"detail\": \"Not Found\"}"));
        using var c = new CartellaTemporanea();
        using var motore = Collegato(c, server.Indirizzo);

        Assert.Equal("server_da_aggiornare", Errore(await motore.ProponiAsync(12, Minecraft(90), null)));
        Assert.Equal("server_da_aggiornare", Errore(await motore.RitiraAsync(31)));
        Assert.Equal(2, server.Ricevute.Count);
        // Il resto continua come prima: il computer resta collegato.
        Assert.True(Json.Booleano(motore.Stato()["abbinato"]));
    }

    [Fact]
    public async Task Una_proposta_gia_in_attesa_sulla_regola_ha_il_suo_errore()
    {
        await using var server = new ServerFinto(_ => (409, "{\"detail\": {\"errore\": \"proposta_gia_pendente\"}}"));
        using var c = new CartellaTemporanea();
        using var motore = Collegato(c, server.Indirizzo);
        Assert.Equal("proposta_gia_pendente", Errore(await motore.ProponiAsync(12, Minecraft(90), null)));
    }

    [Fact]
    public async Task Senza_collegamento_o_senza_regola_non_parte_niente()
    {
        using var c = new CartellaTemporanea();
        using var motore = new MotorePactum(new Percorsi(c.Percorso));
        Assert.Equal("non_abbinato", Errore(await motore.ProponiAsync(12, Minecraft(90), null)));
        Assert.Equal("non_abbinato", Errore(await motore.RitiraAsync(31)));
        Assert.Equal("regola_non_valida", Errore(await motore.ProponiAsync(null, Minecraft(90), null)));
        Assert.Equal("parametri_non_validi", Errore(await motore.ProponiAsync(12, null, null)));
        Assert.Equal("non_riuscita", Errore(await motore.RitiraAsync(null)));
    }

    [Fact]
    public async Task Il_ponte_porta_proposte_e_ritiri_al_motore()
    {
        await using var server = new ServerFinto(r => (200, r.Percorso.EndsWith("/ritira", StringComparison.Ordinal) ? PropostaCreata(31, 15, "ritirata") : PropostaCreata(31, 15)));
        using var c = new CartellaTemporanea();
        using var motore = Collegato(c, server.Indirizzo);
        var ponte = new Ponte(motore);

        var (s1, j1) = await ponte.GestisciAsync("POST", new Uri("https://pactum.locale/locale/proponi"),
            "{\"regola_id\": 15, \"parametri_proposti\": {\"azione\": \"elimina\"}, \"motivazione\": \"\"}");
        Assert.Equal(200, s1);
        Assert.True(Json.Booleano(JsonNode.Parse(j1)!["ok"]));
        var proposta = JsonNode.Parse(server.Ricevute[0].Corpo)!;
        Assert.Equal(new[] { "parametri_proposti", "regola_id" }, Chiavi(proposta));
        Assert.Equal("elimina", Json.Testo(proposta["parametri_proposti"]!["azione"]));

        var (s2, j2) = await ponte.GestisciAsync("POST", new Uri("https://pactum.locale/locale/ritira"), "{\"proposta_id\": 31}");
        Assert.Equal(200, s2);
        Assert.Equal("ritirata", Json.Testo(JsonNode.Parse(j2)!["proposta"]!["stato"]));
        Assert.Equal("/api/proposte/31/ritira", server.Ricevute[1].Percorso);

        // Solo POST, come le altre azioni locali; un corpo sbagliato non arriva al server.
        Assert.Equal(405, (await ponte.GestisciAsync("GET", new Uri("https://pactum.locale/locale/proponi"), null)).Stato);
        Assert.Equal(405, (await ponte.GestisciAsync("GET", new Uri("https://pactum.locale/locale/ritira"), null)).Stato);
        var (s3, j3) = await ponte.GestisciAsync("POST", new Uri("https://pactum.locale/locale/proponi"), "non è json");
        Assert.Equal(200, s3);
        Assert.Equal("regola_non_valida", Json.Testo(JsonNode.Parse(j3)!["errore"]));
        Assert.Equal(2, server.Ricevute.Count);
    }

    // ---------- le notifiche nuove (fumetti) ----------

    [Theory]
    [InlineData("accetta", "Il genitore ha accettato la tua proposta", "+30 min al giorno rispetto ad ora. Vale già da adesso.")]
    [InlineData("rifiuta", "Il genitore ha rifiutato la tua proposta", "+30 min al giorno rispetto ad ora. La regola resta com'è.")]
    public void La_risposta_del_genitore_ha_il_suo_titolo_e_il_testo_non_lo_ripete(string esito, string titolo, string testo)
    {
        var payload = new JsonObject { ["proposta_id"] = 31, ["regola_id"] = 12, ["esito"] = esito, ["autore"] = "figlio" };
        var messaggio = titolo + ": +30 min al giorno rispetto ad ora";
        Assert.Equal((titolo, testo, "proposte"), Testi.Notifica("proposta_risposta", messaggio, payload));
    }

    [Fact]
    public void Coi_nomi_nel_confronto_la_frase_comincia_maiuscola()
    {
        // Contratto v3.4, "I nomi nel confronto": il bersaglio cambiato si legge coi nomi.
        var payload = new JsonObject { ["esito"] = "accetta", ["autore"] = "figlio" };
        Assert.Equal("Da Minecraft (60 min) a Tutto il computer (120 min) al giorno. Vale già da adesso.",
            Testi.Notifica("proposta_risposta", "Il genitore ha accettato la tua proposta: da Minecraft (60 min) a Tutto il computer (120 min) al giorno", payload).Testo);
    }

    [Fact]
    public void Una_proposta_di_eliminare_dice_cosa_e_successo_alla_regola()
    {
        // Il confronto di un'eliminazione ("propone di eliminare la regola") è scritto per il genitore.
        var accettata = new JsonObject { ["esito"] = "accetta", ["autore"] = "figlio" };
        Assert.Equal((Testi.PropostaAccettata, "La regola è uscita dal patto.", "proposte"),
            Testi.Notifica("proposta_risposta", "Il genitore ha accettato la tua proposta: propone di eliminare la regola", accettata));
        var rifiutata = new JsonObject { ["esito"] = "rifiuta", ["autore"] = "figlio" };
        Assert.Equal("La regola resta nel patto.",
            Testi.Notifica("proposta_risposta", "Il genitore ha rifiutato la tua proposta: propone di eliminare la regola", rifiutata).Testo);
    }

    [Fact]
    public void Senza_messaggio_il_fumetto_dice_cosa_cambia()
    {
        var accettata = new JsonObject { ["esito"] = "accetta", ["autore"] = "figlio" };
        Assert.Equal((Testi.PropostaAccettata, "Vale già da adesso.", "proposte"), Testi.Notifica("proposta_risposta", "", accettata));
        var rifiutata = new JsonObject { ["esito"] = "rifiuta" };
        Assert.Equal((Testi.PropostaRifiutata, "La regola resta com'è.", "proposte"), Testi.Notifica("proposta_risposta", null, rifiutata));
        Assert.Equal((Testi.PropostaAccettata, "Vale già da adesso.", "proposte"), Testi.Notifica("proposta_risposta", "Il genitore ha accettato la tua proposta.", accettata));
        Assert.Equal(Testi.PropostaRisposta, Testi.Notifica("proposta_risposta", "Risposta arrivata", null).Titolo);
    }

    [Fact]
    public void Il_genitore_che_ritira_la_sua_proposta()
    {
        // Il messaggio del contratto è uguale al titolo: il testo dice cosa vuol dire.
        var payload = new JsonObject { ["proposta_id"] = 21, ["regola_id"] = 12, ["autore"] = "genitore" };
        Assert.Equal(("Il genitore ha ritirato la sua proposta", "Non c'è più niente da decidere: la regola resta com'è.", "proposte"),
            Testi.Notifica("proposta_ritirata", "Il genitore ha ritirato la sua proposta", payload));
    }

    [Fact]
    public void Le_altre_notifiche_tengono_titolo_e_messaggio_senza_ripeterlo()
    {
        Assert.Equal(("Nuova proposta del genitore", "−30 min al giorno rispetto ad ora.", "proposte"),
            Testi.Notifica("nuova_proposta", "Nuova proposta del genitore: −30 min al giorno rispetto ad ora", new JsonObject { ["proposta_id"] = 4 }));
        Assert.Equal(("Esito della tua dichiarazione", "Confermato.", "diario"), Testi.Notifica("verdetto", "Confermato", null));
        Assert.Equal(("Un segno dal genitore", "Ho visto la settimana. Bene così.", null), Testi.Notifica("segno", "Ho visto la settimana. Bene così.", new JsonObject()));
        Assert.Equal(("Novità dal patto", "Regola modificata.", null), Testi.Notifica("modifica_regola", "Regola modificata", null));
        // Un messaggio che comincia come il titolo ma va avanti senza i due punti resta intero.
        Assert.StartsWith("Il genitore ha accettato la tua proposta di ieri.",
            Testi.Notifica("proposta_risposta", "Il genitore ha accettato la tua proposta di ieri", new JsonObject { ["esito"] = "accetta" }).Testo);
    }

    [Fact]
    public void Un_autore_che_al_figlio_non_arriva_resta_una_novita()
    {
        // Notifiche che il contratto manda al genitore: se mai arrivassero qui, non si travestono.
        Assert.Equal(Testi.TitoloNovita, Testi.Notifica("proposta_risposta", "Il figlio ha risposto", new JsonObject { ["autore"] = "genitore", ["esito"] = "accetta" }).Titolo);
        Assert.Equal(Testi.TitoloNovita, Testi.Notifica("proposta_ritirata", "Andrea ha ritirato la sua proposta", new JsonObject { ["autore"] = "figlio" }).Titolo);
        Assert.Equal(Testi.TitoloNovita, Testi.Notifica("nuova_proposta", "Andrea propone: +30 min", new JsonObject { ["autore"] = "figlio" }).Titolo);
        // Riguardano comunque una proposta: il clic apre Proposte.
        Assert.Equal("proposte", Testi.Notifica("proposta_ritirata", "Andrea ha ritirato la sua proposta", new JsonObject { ["autore"] = "figlio" }).Sezione);
    }

    [Theory]
    [InlineData("nuova_proposta")]
    [InlineData("proposta_risposta")]
    [InlineData("proposta_ritirata")]
    [InlineData("verdetto")]
    [InlineData("segno")]
    [InlineData("tipo_mai_visto")]
    public void Il_testo_del_fumetto_non_e_mai_vuoto(string tipo)
    {
        // Il fumetto di Windows non accetta un testo vuoto.
        Assert.False(string.IsNullOrWhiteSpace(Testi.Notifica(tipo, "  ", null).Testo));
    }

    [Fact]
    public async Task Nel_giro_di_rete_le_notifiche_nuove_diventano_fumetti()
    {
        var notifiche = new JsonArray();
        await using var server = new ServerFinto(r => Strada(r) switch
        {
            // Come la v3.3: con ?dopo_id solo quelle arrivate dopo.
            "/api/notifiche" => (200, new JsonObject
            {
                ["notifiche"] = new JsonArray(notifiche.Where(n => Json.Intero(n!["id"]) > (DopoId(r) ?? -1)).Select(n => n!.DeepClone()).ToArray()),
            }.ToJsonString()),
            "/api/patto" => (200, "{\"regole\": [], \"fuso\": \"Europe/Rome\"}"),
            _ => (200, "{}"),
        });
        using var c = new CartellaTemporanea();
        using var motore = Collegato(c, server.Indirizzo);
        var fumetti = new List<(string Titolo, string Testo, string? Sezione)>();
        motore.Fumetto += (titolo, testo, sezione) => fumetti.Add((titolo, testo, sezione));

        // Il primo giro dopo l'abbinamento non ripete le notifiche vecchie.
        notifiche.Add(new JsonObject { ["id"] = 5, ["tipo"] = "segno", ["messaggio"] = "Ho visto la settimana. Bene così.", ["payload"] = new JsonObject() });
        Assert.True(await motore.SincronizzaAsync("prova"));
        Assert.Empty(fumetti);

        notifiche.Add(new JsonObject
        {
            ["id"] = 7,
            ["tipo"] = "proposta_risposta",
            ["messaggio"] = "Il genitore ha accettato la tua proposta: +30 min al giorno rispetto ad ora",
            ["payload"] = new JsonObject { ["proposta_id"] = 31, ["regola_id"] = 12, ["esito"] = "accetta", ["autore"] = "figlio" },
            ["ts_server"] = "2026-10-01T15:10:00+00:00",
        });
        notifiche.Add(new JsonObject
        {
            ["id"] = 8,
            ["tipo"] = "proposta_ritirata",
            ["messaggio"] = "Il genitore ha ritirato la sua proposta",
            ["payload"] = new JsonObject { ["proposta_id"] = 21, ["regola_id"] = 13, ["autore"] = "genitore" },
            ["ts_server"] = "2026-10-01T15:11:00+00:00",
        });
        Assert.True(await motore.SincronizzaAsync("prova"));

        Assert.Equal(new[] { "Il genitore ha accettato la tua proposta", "Il genitore ha ritirato la sua proposta" }, fumetti.Select(f => f.Titolo));
        Assert.Equal("+30 min al giorno rispetto ad ora. Vale già da adesso.", fumetti[0].Testo);
        // Col clic, i fumetti delle proposte aprono Proposte.
        Assert.All(fumetti, f => Assert.Equal("proposte", f.Sezione));
        // Il primo giro chiede tutte le non lette; dopo, solo quelle dopo l'ultima vista (contratto v3.3).
        var lette = server.Ricevute.Where(r => Strada(r) == "/api/notifiche").Select(r => r.Percorso).ToList();
        Assert.Equal(new[] { "/api/notifiche", "/api/notifiche?dopo_id=5" }, lette);
        // Il computer non le marca lette: tiene il segno dell'ultima vista.
        Assert.DoesNotContain(server.Ricevute, r => r.Percorso.Contains("/letta", StringComparison.Ordinal));

        // Un terzo giro senza novità chiede dopo l'8 e non mostra niente.
        Assert.True(await motore.SincronizzaAsync("prova"));
        Assert.Equal(2, fumetti.Count);
        Assert.Equal("/api/notifiche?dopo_id=8", server.Ricevute.Last(r => Strada(r) == "/api/notifiche").Percorso);
    }

    [Fact]
    public async Task Con_un_server_che_ignora_dopo_id_le_notifiche_gia_viste_non_tornano()
    {
        // Un server di prima della v3.3 ignora ?dopo_id e manda tutte le non lette: il filtro le scarta.
        var tutte = new JsonArray(
            new JsonObject { ["id"] = 5, ["tipo"] = "segno", ["messaggio"] = "Ho visto la settimana. Bene così." },
            new JsonObject { ["id"] = 9, ["tipo"] = "nuova_proposta", ["messaggio"] = "Nuova proposta del genitore: −15 min al giorno rispetto ad ora" });
        int giro = 0;
        await using var server = new ServerFinto(r => Strada(r) switch
        {
            "/api/notifiche" => (200, new JsonObject { ["notifiche"] = new JsonArray(tutte.Take(giro == 0 ? 1 : 2).Select(n => n!.DeepClone()).ToArray()) }.ToJsonString()),
            "/api/patto" => (200, "{\"regole\": []}"),
            _ => (200, "{}"),
        });
        using var c = new CartellaTemporanea();
        using var motore = Collegato(c, server.Indirizzo);
        var titoli = new List<string>();
        motore.Fumetto += (titolo, _, _) => titoli.Add(titolo);

        await motore.SincronizzaAsync("prova");
        giro = 1;
        await motore.SincronizzaAsync("prova");
        await motore.SincronizzaAsync("prova");
        Assert.Equal(new[] { "Nuova proposta del genitore" }, titoli);
    }

    // ---------- (0.10) il patto fresco: una proposta accettata vale subito, mai uno sforamento falso ----------

    /// <summary>
    /// Un computer collegato, con il patto salvato (Tutto il computer: al massimo <paramref name="limiteCopia"/>
    /// minuti, regola 7) e <paramref name="minutiOggi"/> minuti attivi oggi.
    /// </summary>
    private static MotorePactum ConPatto(CartellaTemporanea c, string server, int limiteCopia, int minutiOggi)
    {
        var percorsi = new Percorsi(c.Percorso);
        Archivio.ScriviJson(percorsi.Config, new Configurazione
        {
            Server = server,
            TokenProtetto = Dpapi.Proteggi(Token),
            Dispositivo = new JsonObject { ["id"] = 2, ["nome"] = "Computer", ["tipo"] = "computer" },
            Figlio = new JsonObject { ["id"] = 1, ["nome"] = "Andrea" },
        });
        Archivio.ScriviJson(percorsi.Patto, new PattoSalvato { AggiornatoUtcMs = Tempo.AdessoUtcMs(), Patto = PattoTotale(limiteCopia) });
        var oggi = Tempo.GiornoDi(Tempo.AdessoUtcMs(), TimeZoneInfo.Local);
        var g = Giornata.Nuova(oggi);
        g.MsAttivi = minutiOggi * 60_000L;
        Archivio.ScriviJson(percorsi.FileGiorno(oggi), g);
        return new MotorePactum(percorsi);
    }

    private static JsonObject PattoTotale(int minuti, bool conProposta = false)
    {
        var patto = new JsonObject
        {
            ["regole"] = new JsonArray(new JsonObject
            {
                ["id"] = 7,
                ["tipo"] = TipiRegola.LimiteTempo,
                ["attiva"] = true,
                ["dispositivo_id"] = 2,
                ["parametri"] = new JsonObject { ["app_o_categoria"] = "totale", ["minuti_al_giorno"] = minuti },
            }),
            ["fuso"] = "Europe/Rome",
            ["proposte_pendenti"] = new JsonArray(),
            ["proposte_inviate"] = new JsonArray(),
        };
        if (conProposta) ((JsonArray)patto["proposte_inviate"]!).Add(new JsonObject { ["id"] = 31, ["regola_id"] = 7, ["stato"] = "pendente", ["autore"] = "figlio" });
        return patto;
    }

    private static long? LimiteDiOggi(MotorePactum motore) => Json.Intero(motore.Oggi()["regole"]?["7"]?["limite_efficace"]);

    [Fact]
    public async Task Prima_di_registrare_uno_sforamento_si_rilegge_il_patto_e_una_proposta_accettata_vale_subito()
    {
        // La copia dice 120 minuti, il server (il genitore ha appena accettato la proposta) 180: 150 non sono oltre.
        await using var server = new ServerFinto(r => Strada(r) == "/api/patto" ? (200, PattoTotale(180).ToJsonString()) : (200, "{}"));
        using var c = new CartellaTemporanea();
        using var motore = ConPatto(c, server.Indirizzo, limiteCopia: 120, minutiOggi: 150);
        int mostrati = 0;
        motore.Fumetto += (_, _, _) => mostrati++;
        motore.AvvisoTuttoSchermo += _ => mostrati++;

        await motore.ValutaConPattoFrescoAsync(Tempo.AdessoUtcMs());

        Assert.Empty(motore.EventiInCoda(TipiEvento.Sforamento));
        Assert.Equal(0, mostrati);
        Assert.Single(server.Ricevute, r => Strada(r) == "/api/patto");
        // Il patto fresco è quello del motore, anche su disco.
        Assert.Equal(180, LimiteDiOggi(motore));
        Assert.Equal(180, Json.Intero(Archivio.LeggiJson<PattoSalvato>(c.File("patto.json"))!.Patto["regole"]![0]!["parametri"]!["minuti_al_giorno"]));
    }

    [Fact]
    public async Task Se_il_patto_fresco_conferma_lo_sforamento_si_registra_una_volta_sola()
    {
        await using var server = new ServerFinto(r => Strada(r) == "/api/patto" ? (200, PattoTotale(120).ToJsonString()) : (200, "{}"));
        using var c = new CartellaTemporanea();
        using var motore = ConPatto(c, server.Indirizzo, limiteCopia: 120, minutiOggi: 150);
        var fumetti = new List<string>();
        motore.Fumetto += (_, testo, _) => fumetti.Add(testo);

        long adesso = Tempo.AdessoUtcMs();
        await motore.ValutaConPattoFrescoAsync(adesso);
        var sforamento = Assert.Single(motore.EventiInCoda(TipiEvento.Sforamento));
        Assert.Equal(30, Json.Intero(sforamento.Dettagli["minuti_oltre"]));
        Assert.Single(fumetti);

        // Già segnalato oggi: niente di nuovo, e il server non si richiama.
        await motore.ValutaConPattoFrescoAsync(adesso + 15_000);
        Assert.Single(motore.EventiInCoda(TipiEvento.Sforamento));
        Assert.Single(fumetti);
        Assert.Single(server.Ricevute, r => Strada(r) == "/api/patto");
    }

    [Fact]
    public async Task Senza_rete_si_decide_sulla_copia_come_prima()
    {
        using var c = new CartellaTemporanea();
        using var motore = ConPatto(c, "http://127.0.0.1:9", limiteCopia: 120, minutiOggi: 150);
        int fumetti = 0;
        motore.Fumetto += (_, _, _) => fumetti++;

        await motore.ValutaConPattoFrescoAsync(Tempo.AdessoUtcMs());

        Assert.Single(motore.EventiInCoda(TipiEvento.Sforamento));
        Assert.Equal(1, fumetti);
    }

    [Fact]
    public async Task Senza_sforamenti_in_vista_il_server_non_si_richiama()
    {
        await using var server = new ServerFinto(_ => (200, PattoTotale(120).ToJsonString()));
        using var c = new CartellaTemporanea();
        using var motore = ConPatto(c, server.Indirizzo, limiteCopia: 120, minutiOggi: 90);

        await motore.ValutaConPattoFrescoAsync(Tempo.AdessoUtcMs());

        Assert.Empty(server.Ricevute);
        Assert.Empty(motore.EventiInCoda(TipiEvento.Sforamento));
    }

    [Fact]
    public async Task Il_patto_che_la_finestra_legge_diventa_quello_del_motore()
    {
        await using var server = new ServerFinto(r => Strada(r) == "/api/patto" ? (200, PattoTotale(180).ToJsonString()) : (500, "{}"));
        using var c = new CartellaTemporanea();
        using var motore = ConPatto(c, server.Indirizzo, limiteCopia: 120, minutiOggi: 150);
        Assert.Equal(120, LimiteDiOggi(motore));

        // La finestra legge il patto ogni minuto attraverso il ponte: il motore lo adotta.
        var (stato, json) = await new Ponte(motore).GestisciAsync("GET", new Uri("https://pactum.locale/server/api/patto"), null);

        Assert.Equal(200, stato);
        Assert.Equal(180, Json.Intero(JsonNode.Parse(json)!["regole"]![0]!["parametri"]!["minuti_al_giorno"]));
        Assert.Equal(180, LimiteDiOggi(motore));
        Assert.Equal(180, Json.Intero(Archivio.LeggiJson<PattoSalvato>(c.File("patto.json"))!.Patto["regole"]![0]!["parametri"]!["minuti_al_giorno"]));
        // Una risposta non riuscita, o un'altra lettura, non lo tocca.
        await motore.InoltraAsync("GET", "api/proposte", "?autori=tutti", null);
        Assert.Equal(180, LimiteDiOggi(motore));
    }

    [Fact]
    public void Un_patto_chiesto_prima_e_arrivato_dopo_non_sostituisce_quello_piu_nuovo()
    {
        using var c = new CartellaTemporanea();
        using var motore = ConPatto(c, "http://127.0.0.1:9", limiteCopia: 120, minutiOggi: 10);
        long adesso = Tempo.AdessoUtcMs();
        Assert.True(motore.AdottaPatto(PattoTotale(180), adesso, chiestoTick: 2_000));
        Assert.False(motore.AdottaPatto(PattoTotale(60), adesso - 5_000, chiestoTick: 1_000));
        Assert.Equal(180, LimiteDiOggi(motore));
        Assert.Equal(180, Json.Intero(Archivio.LeggiJson<PattoSalvato>(c.File("patto.json"))!.Patto["regole"]![0]!["parametri"]!["minuti_al_giorno"]));
    }

    [Fact]
    public async Task Mentre_una_proposta_aspetta_il_genitore_il_giro_veloce_legge_patto_e_notifiche()
    {
        await using var server = new ServerFinto(r => Strada(r) switch
        {
            "/api/patto" => (200, PattoTotale(180, conProposta: true).ToJsonString()),
            "/api/notifiche" => (200, "{\"notifiche\": []}"),
            _ => (200, "{}"),
        });
        using var c = new CartellaTemporanea();
        using var motore = ConPatto(c, server.Indirizzo, limiteCopia: 120, minutiOggi: 10);

        await motore.GiroVeloceAsync();

        // Solo patto e notifiche: niente battito né eventi (quelli restano al giro di ogni 5 minuti).
        Assert.Equal(new[] { "/api/patto", "/api/notifiche" }, server.Ricevute.Select(Strada).ToArray());
        Assert.Equal(180, LimiteDiOggi(motore));
    }

    [Fact]
    public void Il_giro_veloce_serve_solo_con_una_proposta_del_figlio_in_attesa()
    {
        Assert.True(new PattoLocale(PattoTotale(60, conProposta: true), 0).HaProposteInviate);
        Assert.False(new PattoLocale(PattoTotale(60), 0).HaProposteInviate);
        // Un server di prima della v3.4 il campo non lo manda.
        Assert.False(new PattoLocale(new JsonObject { ["regole"] = new JsonArray() }, 0).HaProposteInviate);
    }

    /// <summary>Il percorso della richiesta senza la query.</summary>
    private static string Strada(ServerFinto.Richiesta r) => r.Percorso.Split('?')[0];

    private static long? DopoId(ServerFinto.Richiesta r)
    {
        var q = r.Percorso.Contains('?') ? r.Percorso[(r.Percorso.IndexOf('?') + 1)..] : "";
        foreach (var parte in q.Split('&'))
        {
            if (parte.StartsWith("dopo_id=", StringComparison.Ordinal) && long.TryParse(parte["dopo_id=".Length..], out var n)) return n;
        }
        return null;
    }
}
