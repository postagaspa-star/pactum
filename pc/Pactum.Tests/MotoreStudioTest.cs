using System.Globalization;
using System.Text.Json.Nodes;
using Pactum.Motore;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// (0.18, contratto v4.0) La Sessione Studio e la parte B nel motore: lo Studio che parte da solo anche senza rete,
/// il blocco dei lavori che aspetta lo Studio e parte alla sua fine, le liste congelate dello Studio in corso, il
/// tempo nella lista che non conta (<c>sessioni_minuti</c>), «prima il server, poi la copia» all'avvio, lo
/// spegnimento annullato. Tutto con server finti e cartelle temporanee: niente schermi, niente attività vere.
/// </summary>
public class MotoreStudioTest
{
    private static Motore.Motore Abbinato(CartellaTemporanea c, string server)
    {
        var percorsi = new Percorsi(c.Percorso);
        Archivio.ScriviJson(percorsi.Config, new Configurazione
        {
            Server = server,
            TokenProtetto = Dpapi.Proteggi("token-di-prova"),
            Dispositivo = new JsonObject { ["id"] = 2, ["nome"] = "Computer", ["tipo"] = "computer" },
            Figlio = new JsonObject { ["id"] = 1, ["nome"] = "Luca" },
        });
        return new Motore.Motore(percorsi);
    }

    private static string Iso(long ms) =>
        DateTimeOffset.FromUnixTimeMilliseconds(ms).ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture);

    private static JsonObject Computer(params string[] programmi) =>
        new() { ["programmi"] = new JsonArray(programmi.Select(p => (JsonNode)p!).ToArray()) };

    private static JsonObject Config(params string[] programmi) =>
        new() { ["stato"] = "approvata", ["approvata"] = new JsonObject { ["computer"] = Computer(programmi) } };

    private static JsonObject InCorso(long id, long inizioMs, params string[] liste)
    {
        var o = new JsonObject { ["id"] = id, ["inizio_ts"] = Iso(inizioMs) };
        if (liste.Length > 0) o["liste"] = new JsonObject { ["computer"] = Computer(liste) };
        return o;
    }

    private static JsonObject Partenza(long inizioMs) => new()
    {
        ["giorno"] = Tempo.GiornoDi(inizioMs, Fuso.Roma),
        ["inizio_ts"] = Iso(inizioMs),
        ["chiudibile_dal"] = Iso(inizioMs + 3_600_000),
        ["minuti_minimi"] = 60,
    };

    private static JsonObject StudioPatto(JsonObject? config, JsonObject? inCorso, params JsonObject[] partenze) => new()
    {
        ["config"] = config,
        ["in_corso"] = inCorso,
        ["prossime_partenze"] = new JsonArray(partenze.Cast<JsonNode>().ToArray()),
    };

    private static JsonObject BloccoAttivo() => new()
    {
        ["attivo"] = true,
        ["da_fare"] = new JsonArray(new JsonObject
        {
            ["id"] = 5,
            ["titolo"] = "Svuota la lavastoviglie",
            ["blocco_da"] = Iso(Tempo.AdessoUtcMs() - 10 * 60_000),
            ["creata_da"] = new JsonObject { ["id"] = 2, ["nome"] = "Mamma" },
            ["stato"] = "fatta",
            ["foto_ts"] = Iso(Tempo.AdessoUtcMs() - 5 * 60_000),
        }),
    };

    // ---------- Lo Studio e il blocco dei lavori ----------

    [Fact]
    public void Durante_lo_studio_il_blocco_aspetta_e_parte_a_fine_studio()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        VistaStudio? ultimaStudio = null;
        m.CambioStudio += v => ultimaStudio = v;

        // Il server dice: Studio in corso dalle (adesso - 10 min).
        m.AdottaStudioDaPatto(StudioPatto(Config("exe:winword.exe"), InCorso(41, adesso - 1_000)), adesso);
        Assert.True(m.InStudio);
        Assert.True(ultimaStudio!.InCorso);

        // Arriva il blocco dei lavori (dovuto): durante lo Studio aspetta, non copre.
        m.AdottaBlocco(Blocco.Leggi(BloccoAttivo()));
        Assert.False(m.Coperto);

        // Il server dà lo Studio chiuso: il blocco parte subito.
        m.AdottaStudioDaBlocco(new JsonObject { ["in_corso"] = false, ["id"] = null, ["inizio_ts"] = null }, Tempo.AdessoUtcMs());
        Assert.False(m.InStudio);
        Assert.False(ultimaStudio!.InCorso);
        Assert.True(m.Coperto);
    }

    [Fact]
    public void Lo_studio_automatico_toglie_la_copertura_del_blocco_e_la_rimette_dopo()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.AdottaBlocco(Blocco.Leggi(BloccoAttivo()));
        Assert.True(m.Coperto);

        // Parte lo Studio automatico (il server lo dà in corso): il blocco aspetta.
        long adesso = Tempo.AdessoUtcMs();
        m.AdottaStudioDaPatto(StudioPatto(Config(), InCorso(7, adesso - 1_000)), adesso);
        Assert.True(m.InStudio);
        Assert.False(m.Coperto);
    }

    [Fact]
    public void Lo_studio_parte_da_solo_alla_partenza_anche_senza_rete()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        long inizio = (adesso + 3_600_000) / 1000 * 1000; // fra un'ora (le ore del server hanno i secondi interi)
        m.AdottaStudioDaPatto(StudioPatto(Config("sito:classeviva.it"), null, Partenza(inizio)), adesso);

        m.ValutaStudio(inizio - 1_000);
        Assert.False(m.InStudio);
        m.ValutaStudio(inizio + 1_000);
        Assert.True(m.InStudio);
        Assert.Equal(inizio, m.VistaStudioCorrente.InizioMs);
        Assert.Contains("sito:classeviva.it", m.VistaStudioCorrente.Config!.Programmi);
    }

    [Fact]
    public void Cinque_minuti_prima_della_partenza_un_fumetto_avvisa_una_volta_sola()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        var avvisi = new List<string>();
        m.AvvisoStudioInArrivo += (_, testo) => avvisi.Add(testo);
        long adesso = Tempo.AdessoUtcMs();
        long inizio = (adesso + 3_600_000) / 1000 * 1000;
        m.AdottaStudioDaPatto(StudioPatto(Config(), null, Partenza(inizio)), adesso);

        m.ValutaStudio(inizio - 6 * 60_000);
        Assert.Empty(avvisi);
        m.ValutaStudio(inizio - 5 * 60_000);
        m.ValutaStudio(inizio - 4 * 60_000);
        Assert.Equal(new[] { "Tra 5 minuti parte lo Studio." }, avvisi);
        // Alla partenza si entra in Studio, senza altri fumetti.
        m.ValutaStudio(inizio + 1_000);
        Assert.True(m.InStudio);
        Assert.Single(avvisi);
    }

    [Fact]
    public void Senza_partenze_il_computer_non_parte_da_solo()
    {
        // prossime_partenze [] (nessun telefono 0.18): né il telefono né il computer partono da soli.
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        m.AdottaStudioDaPatto(StudioPatto(Config("exe:winword.exe"), null), adesso);
        m.ValutaStudio(adesso + 24 * 3_600_000);
        Assert.False(m.InStudio);
    }

    [Fact]
    public void Un_patto_senza_studio_spegne_lo_studio()
    {
        // Un server più vecchio della v4.0 non manda "studio": lo Studio si spegne.
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        m.AdottaStudioDaPatto(StudioPatto(Config(), InCorso(3, adesso - 1_000)), adesso);
        Assert.True(m.InStudio);
        m.AdottaStudioDaPatto(null, Tempo.AdessoUtcMs());
        Assert.False(m.InStudio);
    }

    [Fact]
    public void Lo_studio_in_corso_tiene_le_sue_liste_congelate()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        // Approvata adesso: Word e GeoGebra. Ma lo Studio in corso è partito con la lista di prima: solo Word.
        m.AdottaStudioDaPatto(
            StudioPatto(Config("exe:winword.exe", "exe:geogebra.exe"), InCorso(41, adesso - 1_000, "exe:winword.exe")),
            adesso);
        Assert.Equal(new[] { "exe:winword.exe" }, m.VistaStudioCorrente.Config!.Programmi);

        // Il blocco ripete lo stesso Studio (senza liste): le liste congelate restano.
        m.AdottaStudioDaBlocco(new JsonObject { ["in_corso"] = true, ["id"] = 41, ["inizio_ts"] = Iso(adesso - 1_000) }, Tempo.AdessoUtcMs());
        Assert.Equal(new[] { "exe:winword.exe" }, m.VistaStudioCorrente.Config!.Programmi);

        // Uno Studio diverso: le liste congelate non valgono più, vale l'approvata finché il patto non dice le sue.
        m.AdottaStudioDaBlocco(new JsonObject { ["in_corso"] = true, ["id"] = 42, ["inizio_ts"] = Iso(adesso - 60_000) }, Tempo.AdessoUtcMs());
        Assert.Equal(new[] { "exe:winword.exe", "exe:geogebra.exe" }, m.VistaStudioCorrente.Config!.Programmi);
    }

    [Fact]
    public void A_mezzanotte_del_patto_lo_studio_finisce_da_solo()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        m.AdottaStudioDaPatto(StudioPatto(Config(), InCorso(41, adesso - 1_000)), adesso);
        Assert.True(m.InStudio);
        long mezzanotte = Tempo.InizioGiorno(Tempo.DataDi((adesso - 1_000) / 1000 * 1000, Fuso.Roma).AddDays(1), Fuso.Roma);
        m.ValutaStudio(mezzanotte - 1_000);
        Assert.True(m.InStudio);
        m.ValutaStudio(mezzanotte);
        Assert.False(m.InStudio);
    }

    [Fact]
    public async Task Un_401_del_blocco_spegne_lo_studio()
    {
        await using var server = new ServerFinto(r => r.Percorso == "/api/faccende/blocco" ? (401, "{\"detail\": \"token non valido\"}") : (200, "{}"));
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);
        long adesso = Tempo.AdessoUtcMs();
        m.AdottaStudioDaPatto(StudioPatto(Config(), InCorso(41, adesso - 1_000)), adesso);
        Assert.True(m.InStudio);

        await m.AggiornaBloccoAsync();
        Assert.False(m.InStudio);
    }

    [Fact]
    public async Task Il_blocco_del_server_dice_quando_lo_studio_finisce()
    {
        long inizio = Tempo.AdessoUtcMs() - 600_000;
        bool aperto = true;
        await using var server = new ServerFinto(r => r.Percorso == "/api/faccende/blocco"
            ? (200, new JsonObject
            {
                ["attivo"] = false,
                ["da_fare"] = new JsonArray(),
                ["rimandato"] = false,
                ["studio"] = aperto
                    ? new JsonObject { ["in_corso"] = true, ["id"] = 41, ["inizio_ts"] = Iso(inizio) }
                    : new JsonObject { ["in_corso"] = false, ["id"] = null, ["inizio_ts"] = null },
            }.ToJsonString())
            : (200, "{}"));
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);

        await m.AggiornaBloccoAsync();
        Assert.True(m.InStudio);
        aperto = false; // il genitore l'ha chiuso (o il figlio dal telefono)
        await m.AggiornaBloccoAsync();
        Assert.False(m.InStudio);
    }

    [Fact]
    public void In_studio_il_blocco_si_chiede_ogni_30_secondi()
    {
        Assert.Equal(TimeSpan.FromSeconds(30), Motore.Motore.IntervalloDelBlocco(coperto: false, inStudio: true));
        Assert.Equal(TimeSpan.FromSeconds(30), Motore.Motore.IntervalloDelBlocco(coperto: true, inStudio: false));
        Assert.Equal(TimeSpan.FromSeconds(60), Motore.Motore.IntervalloDelBlocco(coperto: false, inStudio: false));
    }

    [Fact]
    public void Chiuso_durante_lo_studio_il_segno_resta_in_vivo()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        m.AdottaStudioDaPatto(StudioPatto(Config(), InCorso(41, adesso - 1_000)), adesso);
        Assert.True(Archivio.LeggiJson<StatoVivo>(new Percorsi(c.Percorso).Vivo)!.StudioInCorso);
    }

    [Fact]
    public void La_finestra_sa_dello_studio_anche_partito_senza_rete()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        m.AdottaStudioDaPatto(StudioPatto(Config(), null, Partenza(adesso - 60_000)), adesso - 120_000);
        Assert.True(m.InStudio);
        var s = m.Stato()["studio"] as JsonObject;
        Assert.True(Json.Booleano(s!["in_corso"]));
        Assert.Equal(60, Json.Intero(s["minuti_minimi"]));
        Assert.False(Json.Booleano(s["dal_server"]));
    }

    // ---------- (parte B) Prima il server, poi la copia ----------

    [Fact]
    public async Task All_avvio_si_aspetta_il_server_prima_di_coprire_con_la_copia()
    {
        using var c = new CartellaTemporanea();
        using (var prima = Abbinato(c, "http://127.0.0.1:9"))
        {
            prima.AdottaBlocco(Blocco.Leggi(BloccoAttivo()));
            Assert.True(prima.Coperto);
        }

        // Riavvio senza rete: nei primi secondi non copre (aspetta il server), ma il segno in vivo.json resta.
        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.IniziaAttesaServer(new StatoVivo { BloccatoFaccende = true, StudioInCorso = false });
        Assert.True(m.InAttesaDelServer);
        m.ValutaBlocco(Tempo.AdessoUtcMs());
        Assert.False(m.Coperto);

        // Il server non risponde (niente rete): passato il tempo vale la copia, bloccato resta bloccato.
        await m.SentiIlServerAllAvvioAsync(TimeSpan.FromMilliseconds(500));
        Assert.False(m.InAttesaDelServer);
        Assert.True(m.Coperto);
    }

    [Fact]
    public async Task All_avvio_vale_la_risposta_del_server_se_arriva()
    {
        await using var server = new ServerFinto(r => r.Percorso == "/api/faccende/blocco"
            ? (200, "{\"attivo\": false, \"dal\": null, \"prossimo\": null, \"rimandato\": false, \"da_fare\": [], \"studio\": {\"in_corso\": false, \"id\": null, \"inizio_ts\": null}}")
            : (200, "{}"));
        using var c = new CartellaTemporanea();
        using (var prima = Abbinato(c, server.Indirizzo))
        {
            prima.AdottaBlocco(Blocco.Leggi(BloccoAttivo()));
            Assert.True(prima.Coperto);
        }

        // Riavvio: il server dice che un genitore ha approvato nel frattempo. La copia vecchia non copre mai.
        using var m = Abbinato(c, server.Indirizzo);
        bool copertoUnaVolta = false;
        m.CambioBlocco += v => copertoUnaVolta |= v.Coperto;
        m.IniziaAttesaServer(new StatoVivo { BloccatoFaccende = true });
        await m.SentiIlServerAllAvvioAsync(TimeSpan.FromSeconds(5));
        Assert.False(m.Coperto);
        Assert.False(copertoUnaVolta);
    }

    [Fact]
    public async Task All_avvio_un_5xx_vale_come_niente_rete()
    {
        await using var server = new ServerFinto(_ => (503, "{\"errore\": \"occupato\"}"));
        using var c = new CartellaTemporanea();
        using (var prima = Abbinato(c, server.Indirizzo))
        {
            prima.AdottaBlocco(Blocco.Leggi(BloccoAttivo()));
        }
        using var m = Abbinato(c, server.Indirizzo);
        m.IniziaAttesaServer(new StatoVivo { BloccatoFaccende = true });
        await m.SentiIlServerAllAvvioAsync(TimeSpan.FromSeconds(2));
        Assert.True(m.Coperto);
    }

    [Fact]
    public async Task Durante_l_attesa_lo_studio_salvato_non_copre_poi_vale_la_copia()
    {
        using var c = new CartellaTemporanea();
        long adesso = Tempo.AdessoUtcMs();
        using (var prima = Abbinato(c, "http://127.0.0.1:9"))
        {
            prima.AdottaStudioDaPatto(StudioPatto(Config(), InCorso(41, adesso - 1_000)), adesso);
            Assert.True(prima.InStudio);
        }
        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.CaricaStudio();
        m.IniziaAttesaServer(new StatoVivo { StudioInCorso = true });
        m.ValutaStudio(Tempo.AdessoUtcMs());
        Assert.False(m.InStudio);
        // Senza vivo.json che dica il contrario, il segno di prima resta (chiuso durante lo Studio si riconosce).
        Assert.True(Archivio.LeggiJson<StatoVivo>(new Percorsi(c.Percorso).Vivo)!.StudioInCorso);

        // Il server non risponde: passato il tempo vale la copia, in Studio resta in Studio.
        await m.SentiIlServerAllAvvioAsync(TimeSpan.FromMilliseconds(500));
        Assert.True(m.InStudio);
    }

    // ---------- (parte B) Lo spegnimento annullato ----------

    [Fact]
    public async Task Spegnimento_annullato_manda_la_ripresa_e_lo_spegnimento_vero_rimanda_la_sospensione()
    {
        await using var server = new ServerFinto(r => r.Percorso == "/api/eventi" ? (503, "{\"errore\": \"rete\"}") : (200, "{}"));
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);

        m.FineSessione(spegnimento: true);
        Assert.Single(m.EventiInCoda(TipiEvento.Sospensione));
        Assert.Equal(Chiusure.Spegnimento, Archivio.LeggiJson<StatoVivo>(new Percorsi(c.Percorso).Vivo)!.Chiusura);

        // WM_ENDSESSION con fEndSession = FALSE: lo spegnimento è stato annullato.
        m.SpegnimentoAnnullato();
        var ripresa = Assert.Single(m.EventiInCoda(TipiEvento.Ripresa), e => Json.Testo(e.Dettagli["motivo"]) == "spegnimento_annullato");
        Assert.NotNull(Json.Intero(ripresa.Dettagli["avvio_sistema_ts"]));
        // La chiusura pulita non resta scritta: Pactum è vivo.
        Assert.Null(Archivio.LeggiJson<StatoVivo>(new Percorsi(c.Percorso).Vivo)!.Chiusura);

        // Lo spegnimento poi avviene davvero (solo SessionEnded, entro un minuto): la sospensione si rimanda.
        m.FineSessione(spegnimento: true, soloSeMancante: true);
        Assert.Equal(2, m.EventiInCoda(TipiEvento.Sospensione).Count);
    }

    // ---------- (parte B) Il guardiano nel motore, con un pianificatore finto ----------

    [Fact]
    public void Il_motore_ricrea_l_attivita_tolta_e_lo_dice_al_genitore_una_volta()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        // (correzione 0.18) L'attività c'era già (la prima creazione su un account non accusa nessuno): poi il figlio la toglie.
        var finto = new GuardianoTest.PianificatoreFinto { StatoDaDare = StatoGuardiano.Giusta };
        m.Pianificatore = finto;
        m.ControllaGuardiano();
        finto.StatoDaDare = StatoGuardiano.Mancante;

        m.ControllaGuardiano();
        Assert.Equal(1, finto.Creazioni);
        var e = Assert.Single(m.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["sotto_tipo"]) == "guardiano_assente");
        Assert.Equal("mancante", Json.Testo(e.Dettagli["stato"]));

        // Al controllo dopo (15 minuti) è al suo posto: niente di nuovo.
        m.ControllaGuardiano();
        Assert.Single(m.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["sotto_tipo"]) == "guardiano_assente");

        // Tolta di nuovo: si ricrea e si dice di nuovo.
        finto.StatoDaDare = StatoGuardiano.Disattivata;
        m.ControllaGuardiano();
        Assert.Equal(2, finto.Creazioni);
        Assert.Contains(m.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["stato"]) == "disattivata");
    }

    [Fact]
    public void Senza_pianificatore_il_motore_non_tocca_le_attivita()
    {
        // Nelle prove e nelle build di sviluppo il pianificatore non c'è: nessuna attività, nessun avviso.
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.ControllaGuardiano();
        Assert.DoesNotContain(m.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["sotto_tipo"]) == "guardiano_assente");
    }

    [Fact]
    public void Istanza_occupata_si_dice_una_volta_per_accensione()
    {
        using var c = new CartellaTemporanea();
        using (var m = Abbinato(c, "http://127.0.0.1:9"))
        {
            m.SegnalaIstanzaOccupata();
            m.SegnalaIstanzaOccupata();
            Assert.Single(m.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["sotto_tipo"]) == "istanza_occupata");
        }
        // Il guardiano riparte (stessa accensione di Windows): il segno su disco dice che è già stato detto.
        using var dopo = Abbinato(c, "http://127.0.0.1:9");
        int prima = dopo.EventiInCoda(TipiEvento.Manomissione).Count(x => Json.Testo(x.Dettagli["sotto_tipo"]) == "istanza_occupata");
        dopo.SegnalaIstanzaOccupata();
        Assert.Equal(prima, dopo.EventiInCoda(TipiEvento.Manomissione).Count(x => Json.Testo(x.Dettagli["sotto_tipo"]) == "istanza_occupata"));
    }

    // ---------- Il tempo nella lista dello Studio non conta ----------

    [Fact]
    public void Il_tempo_nella_lista_dello_studio_va_in_sessioni_minuti_e_non_nei_limiti()
    {
        var g = Giornata.Nuova("2026-10-08");
        var contatore = new Contatore(g, giorno => Giornata.Nuova(giorno));
        long t = Fuso.Ms("2026-10-08T15:10:00");
        var word = new Osservazione(true, "exe:winword.exe", "Word", Studio: true);
        var gioco = new Osservazione(true, "exe:minecraft.exe", "Minecraft");
        for (int i = 0; i < 120; i++) contatore.Registra(t + i * 1000L, 1000, word, Fuso.Roma);    // 2 minuti in lista
        for (int i = 0; i < 60; i++) contatore.Registra(t + 120_000 + i * 1000L, 1000, gioco, Fuso.Roma); // 1 minuto fuori

        Assert.Equal(2, g.MinutiStudio);
        Assert.Equal(1, g.MinutiTotali);           // il totale conta solo il fuori lista
        Assert.Equal(0, g.MinutiDi("exe:winword.exe"));
        Assert.Equal(1, g.MinutiDi("exe:minecraft.exe"));
        Assert.Equal(1, g.MinutiNellIntervallo(t, t + 600_000)); // le fasce non vedono lo Studio

        var d = Fotografie.DettagliUso(g);
        Assert.Equal(2, Json.Intero(d["sessioni_minuti"]));
        Assert.Equal(1, Json.Intero(d["totale_minuti"]));
        Assert.False(((JsonObject)d["uso_minuti"]!).ContainsKey("exe:winword.exe"));
    }

    [Fact]
    public void Classifica_l_osservazione_in_studio()
    {
        var config = new ConfigStudio { Programmi = { "exe:winword.exe", "sito:classeviva.it" }, Firme = { ["exe:winword.exe"] = "Microsoft Corporation" } };
        var attiva = new Osservazione(true, "exe:winword.exe", "Word");

        // Word firmato giusto: in lista, non conta.
        Assert.True(ConteggioStudio.Applica(attiva, new FinestraStudio("exe:winword.exe", "winword.exe", "Microsoft Corporation"), config).Studio);
        // Un gioco rinominato winword.exe (senza la firma giusta): fuori lista, conta.
        Assert.False(ConteggioStudio.Applica(attiva, new FinestraStudio("exe:winword.exe", "winword.exe", null), config).Studio);
        // Il browser su un sito della lista: non conta. Su un altro sito: conta.
        var chrome = new Osservazione(true, "exe:chrome.exe", "Chrome", Browser: true, Dominio: "classeviva.it");
        Assert.True(ConteggioStudio.Applica(chrome, new FinestraStudio("exe:chrome.exe", "chrome.exe", null, true, true, "classeviva.it"), config).Studio);
        var youtube = chrome with { Dominio = "youtube.com" };
        Assert.False(ConteggioStudio.Applica(youtube, new FinestraStudio("exe:chrome.exe", "chrome.exe", null, true, true, "youtube.com"), config).Studio);
        // Un'osservazione senza utente resta assente.
        Assert.False(ConteggioStudio.Applica(Osservazione.Assente, new FinestraStudio("exe:winword.exe", "winword.exe", "Microsoft Corporation"), config).Studio);
    }

    [Fact]
    public void Gestione_attivita_e_coperta_anche_se_fosse_in_lista()
    {
        var config = new ConfigStudio { Programmi = { "exe:taskmgr.exe" } };
        var taskmgr = new FinestraStudio("exe:taskmgr.exe", "taskmgr.exe", null,
            Percorso: Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "System32", "Taskmgr.exe"));
        Assert.True(CoperturaStudio.FinestraDaCoprire(taskmgr, config));
    }

    [Theory]
    [InlineData(System.Windows.Forms.CloseReason.UserClosing, false)]
    [InlineData(System.Windows.Forms.CloseReason.TaskManagerClosing, false)]
    [InlineData(System.Windows.Forms.CloseReason.None, false)]
    [InlineData(System.Windows.Forms.CloseReason.ApplicationExitCall, false)]
    [InlineData(System.Windows.Forms.CloseReason.WindowsShutDown, true)]
    public void La_copertura_non_si_chiude_da_fuori_tranne_lo_spegnimento(System.Windows.Forms.CloseReason motivo, bool permessa)
    {
        // (contratto v4.0, «La copertura non si chiude da fuori») Né Alt+F4, né Gestione attività, né un messaggio di
        // chiusura da un altro programma: solo lo spegnimento di Windows, o Pactum stesso.
        Assert.Equal(permessa, Pactum.Interfaccia.RegoleCopertura.ChiusuraPermessa(false, motivo));
        Assert.True(Pactum.Interfaccia.RegoleCopertura.ChiusuraPermessa(true, motivo));
    }

    [Fact]
    public void I_percorsi_dei_programmi_restano_sul_computer_e_non_vanno_nelle_fotografie()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        var g = Giornata.Nuova(Tempo.GiornoDi(Tempo.AdessoUtcMs(), TimeZoneInfo.Local));
        var contatore = new Contatore(g, giorno => Giornata.Nuova(giorno));
        var percorso = @"C:\Program Files\Microsoft Office\root\Office16\WINWORD.EXE";
        contatore.Registra(Tempo.AdessoUtcMs(), 1000, new Osservazione(true, "exe:winword.exe", "Word", Percorso: percorso), TimeZoneInfo.Local);
        Assert.Equal(percorso, g.Programmi["exe:winword.exe"].Percorso);
        Assert.Equal(percorso, g.Copia().Programmi["exe:winword.exe"].Percorso);
        // La fotografia non porta nessun percorso.
        Assert.DoesNotContain("Program Files", Fotografie.DettagliUso(g).ToJsonString());

        // Un giorno salvato su disco: i percorsi dei programmi visti si ritrovano (per le firme della lista).
        var percorsi = new Percorsi(c.Percorso);
        var ieri = Giornata.Nuova(Tempo.DataDi(Tempo.AdessoUtcMs(), TimeZoneInfo.Local).AddDays(-3).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
        ieri.Programmi["exe:geogebra.exe"] = new VoceProgramma { Nome = "GeoGebra", Ms = 60_000, Percorso = @"C:\Users\Luca\AppData\Local\GeoGebra\GeoGebra.exe" };
        Archivio.ScriviJson(percorsi.FileGiorno(ieri.Giorno), ieri);
        var visti = m.PercorsiVisti(30);
        Assert.Equal(@"C:\Users\Luca\AppData\Local\GeoGebra\GeoGebra.exe", visti["exe:geogebra.exe"]);
    }
}
