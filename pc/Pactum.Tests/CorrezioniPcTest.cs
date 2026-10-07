using System.Drawing;
using System.Globalization;
using System.Text.Json.Nodes;
using System.Windows.Forms;
using Pactum.Interfaccia;
using Pactum.Motore;
using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// (correzione 0.18) I problemi trovati dai revisori sul programma del computer, uno per uno: guardiano, attività
/// pianificata, spegnimento, chiusura durante blocco e Studio, Studio dopo un 401, studio.json perso, coperture
/// (desktop virtuali, schermi a cavallo, stili cambiati), browser rinominati, pagine senza sito, firme.
/// Solo dati finti, niente attività vere, niente schermi.
/// </summary>
public class CorrezioniPcTest
{
    private const string Exe = @"C:\Users\Luca\AppData\Local\Pactum\Pactum.exe";
    private const string Utente = @"PC-LUCA\Luca";

    // ---------- #16 #10 #22 Il guardiano ----------

    [Fact]
    public void Tolta_di_nuovo_dopo_la_ricreazione_avvisa_di_nuovo()
    {
        var p = new GuardianoTest.PianificatoreFinto { StatoDaDare = StatoGuardiano.Mancante };
        var g = new Guardiano(p);
        Assert.Equal("mancante", (string?)g.Controlla()!["stato"]);
        // Ricreata (il finto la dà giusta), ma il figlio la ritoglie prima del controllo dopo.
        p.StatoDaDare = StatoGuardiano.Mancante;
        var secondo = g.Controlla();
        Assert.NotNull(secondo);
        Assert.Equal("mancante", (string?)secondo!["stato"]);
        Assert.Equal(2, p.Creazioni);
    }

    [Fact]
    public void Se_ricrearla_non_riesce_il_mancante_non_si_ripete_fra_due_controlli()
    {
        long ora = Fuso.Ms("2026-10-07T15:00:00");
        var p = new GuardianoTest.PianificatoreFinto { StatoDaDare = StatoGuardiano.Mancante, CreazioneFallisce = true };
        var g = new Guardiano(p, () => ora);
        Assert.Equal("non_creata", (string?)g.Controlla()!["stato"]);
        ora += 15 * 60_000;
        Assert.Null(g.Controlla()); // resta assente: niente «mancante» né un secondo «non_creata» lo stesso giorno
    }

    [Fact]
    public void La_prima_creazione_su_un_account_non_accusa_poi_si()
    {
        var memoria = new MemoriaGuardiano();
        MemoriaGuardiano? salvata = null;
        var p = new GuardianoTest.PianificatoreFinto { StatoDaDare = StatoGuardiano.Mancante };
        var g = new Guardiano(p, null, memoria, m => salvata = m);

        Assert.Null(g.Controlla()); // passaggio dalla 0.14: l'attività non è mai esistita
        Assert.Equal(1, p.Creazioni);
        Assert.True(salvata!.GiaCreata);

        // Un Pactum riaperto (stessa memoria su disco): adesso un'attività che manca è tolta.
        var g2 = new Guardiano(p, null, salvata, _ => { });
        p.StatoDaDare = StatoGuardiano.Mancante;
        Assert.Equal("mancante", (string?)g2.Controlla()!["stato"]);
    }

    [Fact]
    public void Una_disattivata_avvisa_anche_alla_prima_volta()
    {
        // Disattivata vuol dire che c'era: non è la prima creazione.
        var p = new GuardianoTest.PianificatoreFinto { StatoDaDare = StatoGuardiano.Disattivata };
        var g = new Guardiano(p, null, new MemoriaGuardiano(), _ => { });
        Assert.Equal("disattivata", (string?)g.Controlla()!["stato"]);
    }

    [Fact]
    public void Non_creata_si_ricorda_su_disco_fra_un_avvio_e_l_altro()
    {
        long ora = Fuso.Ms("2026-10-07T15:00:00");
        var memoria = new MemoriaGuardiano { GiaCreata = true };
        var p = new GuardianoTest.PianificatoreFinto { LetturaFallisce = true };
        Assert.Equal("non_creata", (string?)new Guardiano(p, () => ora, memoria, _ => { }).Controlla()!["stato"]);

        // Pactum riparte un'ora dopo (uscita e rientro dall'account): niente secondo non_creata lo stesso giorno.
        ora += 3_600_000;
        Assert.Null(new Guardiano(p, () => ora, memoria, _ => { }).Controlla());

        // La lettura torna a funzionare e l'attività manca: il «mancante» parte (il fronte di non_creata è un altro).
        p.LetturaFallisce = false;
        p.StatoDaDare = StatoGuardiano.Mancante;
        Assert.Equal("mancante", (string?)new Guardiano(p, () => ora, memoria, _ => { }).Controlla()!["stato"]);
    }

    [Fact]
    public void Il_motore_non_accusa_alla_prima_creazione_e_lo_ricorda_su_disco()
    {
        using var c = new CartellaTemporanea();
        using (var m = Abbinato(c, "http://127.0.0.1:9"))
        {
            var finto = new GuardianoTest.PianificatoreFinto { StatoDaDare = StatoGuardiano.Mancante };
            m.Pianificatore = finto;
            m.ControllaGuardiano();
            Assert.Equal(1, finto.Creazioni);
            Assert.DoesNotContain(m.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["sotto_tipo"]) == "guardiano_assente");
        }
        Assert.True(Archivio.LeggiJson<MemoriaGuardiano>(new Percorsi(c.Percorso).Guardiano)!.GiaCreata);
        using var dopo = Abbinato(c, "http://127.0.0.1:9");
        dopo.Pianificatore = new GuardianoTest.PianificatoreFinto { StatoDaDare = StatoGuardiano.Mancante };
        dopo.ControllaGuardiano();
        Assert.Contains(dopo.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["stato"]) == "mancante");
    }

    // ---------- #8 #17 #18 L'attività confrontata campo per campo ----------

    private static readonly DateTimeOffset Adesso = new(2026, 10, 7, 15, 0, 0, TimeSpan.FromHours(2));

    private static StatoGuardiano Stato(string xml) => PianificatoreSchtasks.LeggiStato(xml, Exe, Utente, Adesso);

    private static string Giusta => PianificatoreSchtasks.Xml(Exe, Utente);

    [Fact]
    public void L_attivita_scritta_da_pactum_e_giusta_e_ha_priorita_normale()
    {
        Assert.Equal(StatoGuardiano.Giusta, Stato(Giusta));
        Assert.Contains("<Priority>5</Priority>", Giusta);
        // 7 (sotto il normale) o la priorità tolta (Windows mette 7): da riparare.
        Assert.Equal(StatoGuardiano.DaRiparare, Stato(Giusta.Replace("<Priority>5</Priority>", "<Priority>7</Priority>")));
        Assert.Equal(StatoGuardiano.DaRiparare, Stato(Giusta.Replace("<Priority>5</Priority>", "")));
        Assert.Equal(StatoGuardiano.Giusta, Stato(Giusta.Replace("<Priority>5</Priority>", "<Priority>4</Priority>")));
    }

    [Theory]
    [InlineData("<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>", "<DisallowStartIfOnBatteries>true</DisallowStartIfOnBatteries>")]
    [InlineData("<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>", "")] // tolto: Windows non parte a batteria
    [InlineData("<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>", "<StopIfGoingOnBatteries>true</StopIfGoingOnBatteries>")]
    [InlineData("<RunOnlyIfIdle>false</RunOnlyIfIdle>", "<RunOnlyIfIdle>true</RunOnlyIfIdle>")]
    [InlineData("<RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>", "<RunOnlyIfNetworkAvailable>true</RunOnlyIfNetworkAvailable>")]
    [InlineData("<StartBoundary>2020-01-01T00:00:00</StartBoundary>", "<StartBoundary>2099-01-01T00:00:00</StartBoundary>")]
    [InlineData("<StartBoundary>2020-01-01T00:00:00</StartBoundary>", "<StartBoundary>2020-01-01T00:00:00</StartBoundary><EndBoundary>2021-01-01T00:00:00</EndBoundary>")]
    [InlineData("<Interval>PT1M</Interval>", "<Interval>PT1M</Interval><Duration>P1D</Duration>")] // dal 2020 + 1 giorno: già finita
    [InlineData("<LogonType>InteractiveToken</LogonType>", "<LogonType>Password</LogonType>")]
    public void Un_cambio_che_spegne_il_guardiano_e_disattivata(string prima, string dopo)
    {
        Assert.Contains(prima, Giusta);
        Assert.Equal(StatoGuardiano.Disattivata, Stato(Giusta.Replace(prima, dopo)));
    }

    [Fact]
    public void Un_trigger_spento_e_disattivata()
    {
        var accessoSpento = Giusta.Replace("<LogonTrigger>\n      <Enabled>true</Enabled>", "<LogonTrigger>\n      <Enabled>false</Enabled>")
            .Replace("<LogonTrigger>\r\n      <Enabled>true</Enabled>", "<LogonTrigger>\r\n      <Enabled>false</Enabled>");
        Assert.NotEqual(Giusta, accessoSpento);
        Assert.Equal(StatoGuardiano.Disattivata, Stato(accessoSpento));
        var minutoSpento = System.Text.RegularExpressions.Regex.Replace(Giusta,
            @"(<StartBoundary>2020-01-01T00:00:00</StartBoundary>\s*)<Enabled>true</Enabled>", "$1<Enabled>false</Enabled>");
        Assert.NotEqual(Giusta, minutoSpento);
        Assert.Equal(StatoGuardiano.Disattivata, Stato(minutoSpento));
    }

    [Theory]
    [InlineData("<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>", "<MultipleInstancesPolicy>Queue</MultipleInstancesPolicy>")]
    [InlineData("<StartBoundary>2020-01-01T00:00:00</StartBoundary>", "<StartBoundary>2020-01-01T00:00:00</StartBoundary><EndBoundary>2099-01-01T00:00:00</EndBoundary>")]
    [InlineData("<UserId>PC-LUCA\\Luca</UserId>\n    </LogonTrigger>", "<UserId>PC-LUCA\\Luca</UserId>\n      <Delay>P1D</Delay>\n    </LogonTrigger>")]
    [InlineData("<Interval>PT1M</Interval>", "<Interval>PT1M</Interval><Duration>P99999D</Duration>")]
    [InlineData("<Arguments>--guardiano</Arguments>\n    </Exec>", "<Arguments>--guardiano</Arguments>\n    </Exec>\n    <Exec><Command>notepad.exe</Command></Exec>")]
    public void Un_altro_cambio_si_ripara_senza_accusare(string prima, string dopo)
    {
        var xml = Giusta.Replace("\r\n", "\n");
        Assert.Contains(prima, xml);
        Assert.Equal(StatoGuardiano.DaRiparare, Stato(xml.Replace(prima, dopo)));
    }

    [Fact]
    public void L_utente_del_trigger_si_confronta_ma_un_sid_no()
    {
        var xml = Giusta.Replace("\r\n", "\n");
        var accessoAltro = xml.Replace("<UserId>PC-LUCA\\Luca</UserId>\n    </LogonTrigger>", "<UserId>PC-LUCA\\Sara</UserId>\n    </LogonTrigger>");
        Assert.Equal(StatoGuardiano.DaRiparare, Stato(accessoAltro));
        var sid = xml.Replace("<UserId>PC-LUCA\\Luca</UserId>\n    </LogonTrigger>", "<UserId>S-1-5-21-1-2-3-1001</UserId>\n    </LogonTrigger>");
        Assert.Equal(StatoGuardiano.Giusta, Stato(sid));
    }

    // ---------- #19 Spegnimento con lo stesso avvio ma ShutdownTime dopo ----------

    private const long Minuto = 60_000;

    [Theory]
    [InlineData("spegnimento")]
    [InlineData("disconnessione")]
    public void Spento_davvero_dopo_l_ultimo_sono_vivo_non_e_annullato(string chiusura)
    {
        long adesso = Fuso.Ms("2026-10-08T07:30:00");
        var prima = new StatoVivo { UtcMs = adesso - 600 * Minuto, TickMs = 30 * Minuto, BootId = 121, Chiusura = chiusura, BloccatoFaccende = true, StudioInCorso = true };
        // Avvio rapido: stesso contatore degli avvii, cronometro andato avanti; ma ShutdownTime è dopo l'ultimo «sono vivo».
        var e = Vivo.ValutaAvvio(prima, adesso, 40 * Minuto, 121, spegnimentoPulitoMs: adesso - 590 * Minuto);
        Assert.Equal("avvio", e.MotivoRipresa);
        Assert.Null(e.ProgrammaChiuso);
        Assert.False(e.ChiusoDuranteBlocco);
        Assert.False(e.ChiusoDuranteStudio);
    }

    [Fact]
    public void Senza_uno_spegnimento_dopo_resta_spegnimento_annullato()
    {
        long adesso = Fuso.Ms("2026-10-08T07:30:00");
        var prima = new StatoVivo { UtcMs = adesso - 20 * Minuto, TickMs = 10 * Minuto, BootId = 121, Chiusura = Chiusure.Spegnimento, StudioInCorso = true };
        var e = Vivo.ValutaAvvio(prima, adesso, 30 * Minuto, 121, spegnimentoPulitoMs: adesso - 3 * 24 * 60 * Minuto);
        Assert.Equal("spegnimento_annullato", e.MotivoRipresa);
        Assert.Equal("spegnimento_annullato", (string?)e.ProgrammaChiuso!["causa"]);
    }

    // ---------- #11 #20 Il 401 svuota lo Studio ----------

    [Fact]
    public async Task Dopo_un_401_una_partenza_salvata_non_fa_partire_lo_studio_ne_il_fumetto()
    {
        await using var server = new ServerFinto(r => r.Percorso == "/api/faccende/blocco" ? (401, "{\"detail\": \"token non valido\"}") : (200, "{}"));
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, server.Indirizzo);
        var fumetti = new List<string>();
        m.AvvisoStudioInArrivo += (_, t) => fumetti.Add(t);
        long adesso = Tempo.AdessoUtcMs();
        long domani = (adesso + 24 * 3_600_000) / 1000 * 1000;
        m.AdottaStudioDaPatto(StudioPatto(Config("exe:winword.exe"), null, Partenza(domani)), adesso);

        await m.AggiornaBloccoAsync();

        m.ValutaStudio(domani - 4 * Minuto);
        m.ValutaStudio(domani + 1_000);
        Assert.False(m.InStudio);
        Assert.Empty(fumetti);
        Assert.Empty(Archivio.LeggiJson<StatoStudio>(new Percorsi(c.Percorso).Studio)!.Partenze);
    }

    // ---------- #13 Lo scarto dell'ora del server alla partenza ----------

    [Fact]
    public void Una_risposta_di_un_attimo_dopo_la_partenza_non_spegne_lo_studio()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        long inizio = (adesso + 3_600_000) / 1000 * 1000;
        m.AdottaStudioDaPatto(StudioPatto(Config(), null, Partenza(inizio)), adesso);
        m.ValutaStudio(inizio + 200);
        Assert.True(m.InStudio);

        // Il server, per cui erano ancora le 14:59:59.6, risponde «niente Studio»: la risposta è di 1,5 s dopo la partenza.
        m.AdottaStudioDaBlocco(new JsonObject { ["in_corso"] = false }, inizio + 1_500);
        m.ValutaStudio(inizio + 2_000);
        Assert.True(m.InStudio);

        // Una risposta di parecchi secondi dopo vale davvero (per esempio un genitore l'ha chiuso).
        m.AdottaStudioDaBlocco(new JsonObject { ["in_corso"] = false }, inizio + 10_000);
        m.ValutaStudio(inizio + 11_000);
        Assert.False(m.InStudio);
    }

    // ---------- #15 studio.json cancellato o cambiato ----------

    [Fact]
    public void Studio_json_cancellato_si_dice_e_la_partenza_ricordata_resta()
    {
        using var c = new CartellaTemporanea();
        var percorsi = new Percorsi(c.Percorso);
        long adesso = Tempo.AdessoUtcMs();
        long inizio = (adesso + 3_600_000) / 1000 * 1000;
        using (var prima = Abbinato(c, "http://127.0.0.1:9"))
        {
            prima.AdottaStudioDaPatto(StudioPatto(Config("exe:winword.exe"), null, Partenza(inizio)), adesso);
        }
        var vivo = Archivio.LeggiJson<StatoVivo>(percorsi.Vivo)!;
        Assert.Equal(inizio, vivo.ProssimaPartenzaMs);
        File.Delete(percorsi.Studio);

        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.CaricaStudio(vivo);
        Assert.Single(m.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["sotto_tipo"]) == "stato_studio_perso");
        m.ValutaStudio(inizio + 1_000);
        Assert.True(m.InStudio);
    }

    [Fact]
    public void Studio_in_corso_perso_resta_in_studio()
    {
        using var c = new CartellaTemporanea();
        var percorsi = new Percorsi(c.Percorso);
        long adesso = Tempo.AdessoUtcMs();
        using (var prima = Abbinato(c, "http://127.0.0.1:9"))
        {
            prima.AdottaStudioDaPatto(StudioPatto(Config(), InCorso(41, adesso - 1_000)), adesso);
            Assert.True(prima.InStudio);
        }
        var vivo = Archivio.LeggiJson<StatoVivo>(percorsi.Vivo)!;
        Archivio.ScriviJson(percorsi.Studio, StatoStudio.Vuoto); // cambiato: niente più Studio in corso

        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.CaricaStudio(vivo);
        Assert.Contains(m.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["sotto_tipo"]) == "stato_studio_perso");
        m.ValutaStudio(Tempo.AdessoUtcMs());
        Assert.True(m.InStudio);
    }

    [Fact]
    public void Senza_niente_da_ricordare_un_file_mancante_non_accusa()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        m.CaricaStudio(new StatoVivo { UtcMs = Tempo.AdessoUtcMs() });
        Assert.DoesNotContain(m.EventiInCoda(TipiEvento.Manomissione), x => Json.Testo(x.Dettagli["sotto_tipo"]) == "stato_studio_perso");
    }

    // ---------- #4 «Chiudi Pactum» durante blocco e Studio ----------

    [Fact]
    public void Chiudi_pactum_e_nascosto_mentre_si_aspetta_il_server_con_un_blocco_ricordato()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        Assert.False(m.ChiusuraDaNascondere);
        m.IniziaAttesaServer(new StatoVivo { BloccatoFaccende = true });
        Assert.False(m.Coperto); // nei 5 secondi non copre...
        Assert.True(m.ChiusuraDaNascondere); // ...ma «Chiudi Pactum» non c'è
    }

    [Fact]
    public void Chiusa_durante_lo_studio_non_si_scrive_volontaria()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        long adesso = Tempo.AdessoUtcMs();
        m.AdottaStudioDaPatto(StudioPatto(Config(), InCorso(41, adesso - 1_000)), adesso);
        Assert.True(m.ChiusuraDaNascondere);

        await_(m.ChiudiVolontariamenteAsync());
        var vivo = Archivio.LeggiJson<StatoVivo>(new Percorsi(c.Percorso).Vivo)!;
        Assert.Null(vivo.Chiusura); // al riavvio: programma_chiuso e chiuso_durante_studio
        Assert.True(vivo.StudioInCorso);
    }

    [Fact]
    public void Chiusa_fuori_da_blocco_e_studio_resta_volontaria()
    {
        using var c = new CartellaTemporanea();
        using var m = Abbinato(c, "http://127.0.0.1:9");
        await_(m.ChiudiVolontariamenteAsync());
        Assert.Equal(Chiusure.Volontaria, Archivio.LeggiJson<StatoVivo>(new Percorsi(c.Percorso).Vivo)!.Chiusura);
    }

    private static void await_(Task t) => t.GetAwaiter().GetResult();

    // ---------- #21 --guardiano non apre la finestra ----------

    [Fact]
    public void Il_guardiano_non_apre_mai_la_finestra()
    {
        Assert.False(ContestoPactum.ApreLaFinestraAllAvvio(Opzioni.Da(new[] { "--guardiano" }), abbinato: false));
        Assert.False(ContestoPactum.ApreLaFinestraAllAvvio(Opzioni.Da(new[] { "--guardiano" }), abbinato: true));
        // Come prima per gli altri avvii.
        Assert.True(ContestoPactum.ApreLaFinestraAllAvvio(Opzioni.Da(Array.Empty<string>()), abbinato: true));
        Assert.True(ContestoPactum.ApreLaFinestraAllAvvio(Opzioni.Da(new[] { "--avvio" }), abbinato: false));
        Assert.False(ContestoPactum.ApreLaFinestraAllAvvio(Opzioni.Da(new[] { "--avvio" }), abbinato: true));
    }

    // ---------- #5 Messaggi finti di fine sessione ----------

    [Fact]
    public void I_messaggi_di_fine_sessione_valgono_solo_se_windows_si_chiude()
    {
        Assert.True(RegoleCopertura.MessaggioDaIgnorare(Win32.WM_ENDSESSION, sessioneSiChiude: false));
        Assert.True(RegoleCopertura.MessaggioDaIgnorare(Win32.WM_QUERYENDSESSION, sessioneSiChiude: false));
        Assert.False(RegoleCopertura.MessaggioDaIgnorare(Win32.WM_ENDSESSION, sessioneSiChiude: true));
        Assert.False(RegoleCopertura.MessaggioDaIgnorare(0x0010 /* WM_CLOSE */, sessioneSiChiude: false));
        Assert.False(RegoleCopertura.FineSessioneVera(sessioneSiChiude: false));
        Assert.True(RegoleCopertura.FineSessioneVera(sessioneSiChiude: true));
        // Adesso, mentre girano i test, Windows non si sta chiudendo.
        Assert.False(Win32.SessioneSiChiude());
    }

    // ---------- #1 #6 Coperture su un altro desktop o rese trasparenti ----------

    private sealed class CoperturaFinta : Form, ICopertura
    {
        public bool Consentita;
        public void ConsentiChiusura() => Consentita = true;
    }

    [Fact]
    public void Una_copertura_su_un_altro_desktop_o_trasparente_va_rifatta()
    {
        Assert.False(RegoleCopertura.DaRifare(nascostaDaWindows: false, stileEsteso: Win32.WS_EX_TOOLWINDOW, haRegione: false));
        Assert.True(RegoleCopertura.DaRifare(nascostaDaWindows: true, stileEsteso: 0, haRegione: false));
        Assert.True(RegoleCopertura.DaRifare(false, Win32.WS_EX_LAYERED, false));
        Assert.True(RegoleCopertura.DaRifare(false, Win32.WS_EX_TRANSPARENT, false));
        Assert.True(RegoleCopertura.DaRifare(false, 0, haRegione: true));
    }

    [Fact]
    public void Ripulisci_chiude_la_copertura_che_windows_dice_nascosta()
    {
        // Una finestra finta mai mostrata: Windows (finto) dice che è nascosta su un altro desktop.
        var f = new CoperturaFinta();
        _ = f.Handle;
        var lista = new List<CoperturaFinta> { f };
        int restano = RegoleCopertura.Ripulisci(lista, _ => true, _ => 0L, _ => false);
        Assert.Equal(0, restano);
        Assert.True(f.Consentita);
        Assert.True(f.IsDisposed);
    }

    [Theory]
    [InlineData(0L, 800, 600, false, true)]                     // finestra normale
    [InlineData(0x80L, 800, 600, false, true)]                  // gioco con WS_EX_TOOLWINDOW messo da fuori: grande, conta
    [InlineData(0x80L, 200, 120, false, false)]                 // tavolozza piccola: non conta
    [InlineData(0x20L, 1920, 1080, false, false)]               // sovrapposizione che lascia passare i clic
    [InlineData(0x20L | 0x80L, 1920, 1080, true, true)]         // ...ma in primo piano conta sempre
    [InlineData(0L, 20, 20, true, false)]                       // finestrella
    public void Quali_finestre_contano_nello_studio(long stile, int larghezza, int altezza, bool primoPiano, bool conta)
    {
        Assert.Equal(conta, PrimoPiano.FinestraCheConta(stile, larghezza, altezza, primoPiano));
    }

    // ---------- #3 Una finestra a cavallo di due schermi ----------

    private static readonly List<(string, Rectangle)> DueSchermi = new()
    {
        ("A", new Rectangle(0, 0, 1920, 1080)),
        ("B", new Rectangle(1920, 0, 1920, 1080)),
    };

    [Fact]
    public void Una_finestra_a_cavallo_copre_tutti_e_due_gli_schermi()
    {
        // 51% su A e 49% su B.
        var gioco = new Rectangle(1920 - 510, 100, 1000, 700);
        Assert.Equal(new[] { "A", "B" }, DivisioneSchermi.Schermi(gioco, DueSchermi));
        // Ingrandita su A, coi bordi invisibili che sforano di 8 px su B: solo A.
        Assert.Equal(new[] { "A" }, DivisioneSchermi.Schermi(new Rectangle(-8, -8, 1936, 1096), DueSchermi));

        var perSchermo = DivisioneSchermi.Dividi(new[] { ("gioco", gioco), ("word", new Rectangle(2000, 100, 800, 600)) }, x => x.Item2, DueSchermi);
        Assert.Equal(new[] { "gioco" }, perSchermo["A"].Select(x => x.Item1));
        Assert.Equal(new[] { "gioco", "word" }, perSchermo["B"].Select(x => x.Item1));
    }

    // ---------- #2 Browser rinominati, voci firmate col nome originale ----------

    private static ConfigStudio ListaWord() => new()
    {
        Programmi = { "exe:winword.exe", "sito:classeviva.it" },
        Firme = { ["exe:winword.exe"] = "Microsoft Corporation" },
    };

    [Fact]
    public void Un_browser_firmato_e_rinominato_winword_si_copre()
    {
        var edgeRinominato = new FinestraStudio("exe:winword.exe", "winword.exe", "Microsoft Corporation",
            Percorso: @"C:\Users\Luca\Edge\winword.exe", NomeOriginale: "msedge.exe");
        Assert.True(CoperturaStudio.FinestraDaCoprire(edgeRinominato, ListaWord()));
        Assert.Equal(UsoStudio.FuoriLista, ConteggioStudio.Classifica(edgeRinominato, ListaWord()));

        // Anche su una voce senza firma.
        var lista = new ConfigStudio { Programmi = { "exe:geogebra.exe" } };
        var chromeRinominato = new FinestraStudio("exe:geogebra.exe", "geogebra.exe", "Google LLC", NomeOriginale: "chrome.exe");
        Assert.True(CoperturaStudio.FinestraDaCoprire(chromeRinominato, lista));
    }

    [Fact]
    public void Una_voce_firmata_vuole_anche_il_nome_originale_giusto()
    {
        // Un altro programma dello stesso produttore, rinominato winword.exe.
        var altro = new FinestraStudio("exe:winword.exe", "winword.exe", "Microsoft Corporation", NomeOriginale: "MinecraftLauncher.exe");
        Assert.True(CoperturaStudio.FinestraDaCoprire(altro, ListaWord()));
        // Word vero (il nome originale ha maiuscole diverse) e un file che il nome originale non lo dice.
        Assert.False(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:winword.exe", "winword.exe", "Microsoft Corporation", NomeOriginale: "WinWord.exe"), ListaWord()));
        Assert.False(CoperturaStudio.FinestraDaCoprire(new FinestraStudio("exe:winword.exe", "winword.exe", "Microsoft Corporation"), ListaWord()));
    }

    // ---------- #7 Pagine senza un nome di sito ----------

    [Theory]
    [InlineData("http://1.2.3.4/proxy")]
    [InlineData("1.2.3.4")]
    [InlineData("file:///C:/Users/Luca/Downloads/gioco.html")]
    [InlineData("edge://surf")]
    [InlineData("chrome://dino")]
    [InlineData("chrome-extension://abcdefghijklmnop/pagina.html")]
    [InlineData("data:text/html,ciao")]
    public void Una_pagina_senza_sito_nello_studio_copre(string barra)
    {
        var l = Lettura.DallaBarra(barra);
        Assert.Null(l.Dominio);
        Assert.False(l.Fallita);
        Assert.True(l.SenzaSito);
        var f = new FinestraStudio("exe:msedge.exe", "msedge.exe", null, Browser: true, LetturaRiuscita: true, Dominio: null, SenzaSito: l.SenzaSito);
        Assert.True(CoperturaStudio.FinestraDaCoprire(f, ListaWord()));
    }

    [Theory]
    [InlineData("")]
    [InlineData("about:blank")]
    [InlineData("chrome://newtab/")]
    [InlineData("edge://newtab")]
    [InlineData("about:newtab")]
    public void La_scheda_nuova_resta_usabile(string barra)
    {
        var l = Lettura.DallaBarra(barra);
        Assert.False(l.SenzaSito);
        var f = new FinestraStudio("exe:msedge.exe", "msedge.exe", null, Browser: true, LetturaRiuscita: true, Dominio: null, SenzaSito: l.SenzaSito);
        Assert.False(CoperturaStudio.FinestraDaCoprire(f, ListaWord()));
    }

    [Fact]
    public void Un_sito_vero_non_e_senza_sito()
    {
        var l = Lettura.DallaBarra("https://web.spaggiari.eu/home");
        Assert.NotNull(l.Dominio);
        Assert.False(l.SenzaSito);
    }

    // ---------- #14 La memoria delle firme ----------

    [Fact]
    public void La_memoria_delle_firme_rilegge_un_file_cambiato_o_dopo_un_minuto()
    {
        long ora = 1_000_000;
        string identita = "vol|1|42|creato|scritto|0|2048";
        int letture = 0;
        var memoria = new MemoriaFirme(_ => identita, _ => { letture++; return new InfoFirma("Microsoft Corporation", "WinWord.exe"); }, () => ora);

        Assert.Equal("Microsoft Corporation", memoria.Leggi(@"C:\Users\Luca\winword.exe")!.Soggetto);
        memoria.Leggi(@"C:\Users\Luca\winword.exe");
        Assert.Equal(1, letture); // stesso file, appena letto: dalla memoria

        // Sostituito con un altro file della stessa grandezza e della stessa data: un altro indice del file.
        identita = "vol|1|43|creato|scritto|0|2048";
        memoria.Leggi(@"C:\Users\Luca\winword.exe");
        Assert.Equal(2, letture);

        // Anche se sembra lo stesso, dopo un minuto si rilegge.
        ora += MemoriaFirme.DurataMs;
        memoria.Leggi(@"C:\Users\Luca\winword.exe");
        Assert.Equal(3, letture);
    }

    [Fact]
    public void Un_file_che_non_si_apre_non_ha_firma()
    {
        var memoria = new MemoriaFirme(_ => null, _ => new InfoFirma("Microsoft Corporation", null), () => 0);
        Assert.Null(memoria.Leggi(@"C:\Users\Luca\sparito.exe"));
        Assert.Null(Firma.Leggi(@"C:\Users\Luca\non-esiste-davvero.exe"));
    }

    // ---------- Aiuti (come in MotoreStudioTest) ----------

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

    private static JsonObject Config(params string[] programmi) => new()
    {
        ["stato"] = "approvata",
        ["approvata"] = new JsonObject { ["computer"] = new JsonObject { ["programmi"] = new JsonArray(programmi.Select(p => (JsonNode)p!).ToArray()) } },
    };

    private static JsonObject InCorso(long id, long inizioMs) => new() { ["id"] = id, ["inizio_ts"] = Iso(inizioMs) };

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
}
