using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>Il registro onesto: orologio spostato, fuso cambiato, programma chiuso a forza.</summary>
public class RegistroOnestoTest
{
    private const long Minuto = 60_000;

    // ---------- l'orologio ----------

    [Fact]
    public void Orologio_e_cronometro_che_vanno_insieme_non_dicono_niente()
    {
        var s = new SentinellaOrologio(1_000_000, 50_000, "W. Europe Standard Time");
        for (int i = 1; i <= 300; i++)
        {
            Assert.Empty(s.Controlla(1_000_000 + i * 1000, 50_000 + i * 1000, "W. Europe Standard Time"));
        }
    }

    [Fact]
    public void Una_correzione_piccola_non_e_una_mano()
    {
        var s = new SentinellaOrologio(0, 0, "Z");
        Assert.Empty(s.Controlla(1000 + 30_000, 1000, "Z"));
    }

    [Fact]
    public void Un_salto_avanti_di_cinque_minuti_e_un_cambio_ora()
    {
        var s = new SentinellaOrologio(0, 0, "Z");
        var e = Assert.Single(s.Controlla(1000 + 5 * Minuto, 1000, "Z"));
        Assert.Equal("cambio_ora", Json.Testo(e["sotto_tipo"]));
        Assert.Equal(300, Json.Intero(e["drift_secondi"]));
        // Dopo l'evento si riparte da lì: il secondo dopo è tutto a posto.
        Assert.Empty(s.Controlla(2000 + 5 * Minuto, 2000, "Z"));
    }

    [Fact]
    public void Un_salto_indietro_ha_il_segno_meno()
    {
        var s = new SentinellaOrologio(10 * 60 * Minuto, 0, "Z");
        var e = Assert.Single(s.Controlla(10 * 60 * Minuto - 60 * Minuto + 1000, 1000, "Z"));
        Assert.Equal(-3600, Json.Intero(e["drift_secondi"]));
    }

    [Fact]
    public void Tanti_piccoli_spostamenti_si_sommano()
    {
        var s = new SentinellaOrologio(0, 0, "Z");
        Assert.Empty(s.Controlla(1000 + 90_000, 1000, "Z"));
        var e = Assert.Single(s.Controlla(2000 + 180_000, 2000, "Z"));
        Assert.Equal(180, Json.Intero(e["drift_secondi"]));
    }

    [Fact]
    public void Il_battito_consegnato_rinnova_l_ancora()
    {
        var s = new SentinellaOrologio(0, 0, "Z");
        Assert.Empty(s.Controlla(1000 + 90_000, 1000, "Z"));
        s.Riancora(1000 + 90_000, 1000);
        Assert.Empty(s.Controlla(2000 + 180_000, 2000, "Z"));
    }

    [Fact]
    public void Un_fuso_nuovo_e_un_cambio_fuso()
    {
        var s = new SentinellaOrologio(0, 0, "W. Europe Standard Time");
        var e = Assert.Single(s.Controlla(1000, 1000, "GMT Standard Time"));
        Assert.Equal("cambio_fuso", Json.Testo(e["sotto_tipo"]));
        Assert.Equal("GMT Standard Time", Json.Testo(e["fuso"]));
        Assert.Empty(s.Controlla(2000, 2000, "GMT Standard Time"));
    }

    // ---------- il programma chiuso ----------

    private static readonly long Adesso = Fuso.Ms("2026-09-23T18:00:00");

    private static StatoVivo Vivo(long utc, long tick, long? boot = 121, string? chiusura = null) =>
        new() { UtcMs = utc, TickMs = tick, BootId = boot, Chiusura = chiusura };

    [Fact]
    public void La_prima_volta_non_c_e_niente_da_dire()
    {
        var e = Nucleo.Vivo.ValutaAvvio(null, Adesso, 3_600_000, 121);
        Assert.Null(e.ProgrammaChiuso);
        Assert.Null(e.CambioOra);
        Assert.Equal("avvio", e.MotivoRipresa);
        Assert.Equal(Adesso - 3_600_000, e.AvvioSistemaMs);
    }

    [Fact]
    public void Chiuso_a_forza_con_Windows_acceso_e_una_manomissione_col_buco()
    {
        // L'ultimo "sono vivo" alle 17:30, riaperto alle 18:00, Windows mai riavviato.
        var prima = Vivo(Adesso - 30 * Minuto, 10 * Minuto);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.True(e.StessoAvvioDiWindows);
        Assert.NotNull(e.ProgrammaChiuso);
        Assert.Equal("programma_chiuso", Json.Testo(e.ProgrammaChiuso!["sotto_tipo"]));
        Assert.Equal(Adesso - 30 * Minuto, Json.Intero(e.ProgrammaChiuso["dal"]));
        Assert.Equal(Adesso, Json.Intero(e.ProgrammaChiuso["al"]));
        Assert.Null(e.CambioOra);
    }

    [Fact]
    public void Chiuso_dal_menu_e_gia_stato_detto_al_momento()
    {
        var prima = Vivo(Adesso - 30 * Minuto, 10 * Minuto, chiusura: Chiusure.Volontaria);
        Assert.Null(Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121).ProgrammaChiuso);
    }

    [Fact]
    public void Dopo_un_riavvio_di_Windows_non_c_e_manomissione()
    {
        var prima = Vivo(Adesso - 30 * Minuto, 300 * Minuto, boot: 120);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 2 * Minuto, 121);
        Assert.False(e.StessoAvvioDiWindows);
        Assert.Null(e.ProgrammaChiuso);
        Assert.Equal("avvio", e.MotivoRipresa);
    }

    [Fact]
    public void Avviato_tardi_dopo_un_riavvio_con_chiusura_pulita_registra_il_tempo_senza_Pactum()
    {
        // Windows riavviato (boot diverso), acceso da 40 minuti, l'ultima chiusura era pulita
        // (spegnimento): il tempo dall'accensione a ora — in cui Pactum non c'era — va nel registro.
        var prima = Vivo(Adesso - 5 * 60 * Minuto, 300 * Minuto, boot: 120, chiusura: Chiusure.Spegnimento);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.False(e.StessoAvvioDiWindows);
        Assert.NotNull(e.ProgrammaChiuso);
        Assert.Equal("programma_chiuso", Json.Testo(e.ProgrammaChiuso!["sotto_tipo"]));
        Assert.True(Json.Booleano(e.ProgrammaChiuso["avvio_ritardato"]));
        Assert.Equal(Adesso - 40 * Minuto, Json.Intero(e.ProgrammaChiuso["dal"])); // l'accensione di Windows
        Assert.Equal(Adesso, Json.Intero(e.ProgrammaChiuso["al"]));
        Assert.Equal("avvio", e.MotivoRipresa);
    }

    [Fact]
    public void Avviato_subito_dopo_un_riavvio_non_dice_niente()
    {
        // Avvio automatico al login: Windows acceso da 2 minuti. Sotto i 10, niente manomissione.
        var prima = Vivo(Adesso - 5 * 60 * Minuto, 300 * Minuto, boot: 120, chiusura: Chiusure.Spegnimento);
        Assert.Null(Nucleo.Vivo.ValutaAvvio(prima, Adesso, 2 * Minuto, 121).ProgrammaChiuso);
    }

    [Fact]
    public void Avviato_tardi_ma_senza_una_chiusura_pulita_non_inventa_un_avvio_ritardato()
    {
        // Chiusura non pulita prima del riavvio: il buco fra le sessioni non si sa attribuire.
        var prima = Vivo(Adesso - 5 * 60 * Minuto, 300 * Minuto, boot: 120, chiusura: null);
        Assert.Null(Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121).ProgrammaChiuso);
    }

    [Fact]
    public void Senza_contatore_degli_avvii_basta_il_cronometro_tornato_indietro()
    {
        var prima = Vivo(Adesso - 30 * Minuto, 300 * Minuto, boot: null);
        Assert.Null(Nucleo.Vivo.ValutaAvvio(prima, Adesso, 2 * Minuto, null).ProgrammaChiuso);
    }

    [Fact]
    public void Senza_contatore_degli_avvii_un_avvio_diverso_si_vede_dall_ora_di_accensione()
    {
        // Accensione calcolata 3 ore dopo quella di prima: è un altro avvio.
        var prima = Vivo(Adesso - 4 * 60 * Minuto, 10 * Minuto, boot: null);
        Assert.Null(Nucleo.Vivo.ValutaAvvio(prima, Adesso, 60 * Minuto, null).ProgrammaChiuso);
        // Stessa accensione: stesso avvio, e il programma era stato chiuso.
        var stesso = Vivo(Adesso - 30 * Minuto, 10 * Minuto, boot: null);
        Assert.NotNull(Nucleo.Vivo.ValutaAvvio(stesso, Adesso, 40 * Minuto, null).ProgrammaChiuso);
    }

    [Fact]
    public void L_orologio_spostato_a_programma_chiuso_si_vede_lo_stesso()
    {
        // Ultimo "sono vivo" 30 minuti fa per il cronometro, ma l'orologio dice 90.
        var prima = Vivo(Adesso - 90 * Minuto, 10 * Minuto);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.NotNull(e.ProgrammaChiuso);
        Assert.NotNull(e.CambioOra);
        Assert.Equal(3600, Json.Intero(e.CambioOra!["drift_secondi"]));
    }

    [Fact]
    public void Dopo_l_uscita_dall_account_si_riparte_con_un_accesso()
    {
        var prima = Vivo(Adesso - 30 * Minuto, 10 * Minuto, chiusura: Chiusure.Disconnessione);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.Null(e.ProgrammaChiuso);
        Assert.Equal("accesso", e.MotivoRipresa);
    }

    [Fact]
    public void Chi_chiude_dal_menu_lo_dice_subito_con_volontario()
    {
        var e = Eventi.ChiusuraVolontaria(Adesso);
        Assert.Equal(TipiEvento.Manomissione, e.Tipo);
        Assert.Equal("programma_chiuso", Json.Testo(e.Dettagli["sotto_tipo"]));
        Assert.True(Json.Booleano(e.Dettagli["volontario"]));
        Assert.Equal(Adesso, Json.Intero(e.Dettagli["dal"]));
    }

    // ---------- (0.13) chiuso durante un blocco delle faccende ----------

    private static StatoVivo VivoBloccato(long utc, long tick, long? boot = 121, string? chiusura = null, bool bloccato = false) =>
        new() { UtcMs = utc, TickMs = tick, BootId = boot, Chiusura = chiusura, BloccatoFaccende = bloccato };

    [Fact]
    public void Chiuso_a_forza_durante_un_blocco_si_dice_al_riavvio()
    {
        // Ultimo "sono vivo" alle 17:30 con un blocco attivo, Windows mai riavviato, nessuna chiusura pulita.
        var prima = VivoBloccato(Adesso - 30 * Minuto, 10 * Minuto, bloccato: true);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.NotNull(e.ProgrammaChiuso); // il buco di sempre
        Assert.True(e.ChiusoDuranteBlocco); // in più: era un blocco
        var evento = Eventi.ChiusoDuranteBlocco(Adesso);
        Assert.Equal(TipiEvento.Manomissione, evento.Tipo);
        Assert.Equal("chiuso_durante_blocco", Json.Testo(evento.Dettagli["sotto_tipo"]));
    }

    [Fact]
    public void Chiuso_a_forza_senza_blocco_non_dice_chiuso_durante_blocco()
    {
        var prima = VivoBloccato(Adesso - 30 * Minuto, 10 * Minuto, bloccato: false);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.NotNull(e.ProgrammaChiuso);
        Assert.False(e.ChiusoDuranteBlocco);
    }

    [Fact]
    public void Dopo_uno_spegnimento_pulito_niente_chiuso_durante_blocco_anche_se_era_bloccato()
    {
        // Spegnimento vero: avvio di Windows DIVERSO (120 → 121), Pactum ripartito presto (2 min dall'accensione):
        // non è una manomissione, anche se il blocco era attivo.
        var prima = VivoBloccato(Adesso - 30 * Minuto, 300 * Minuto, boot: 120, chiusura: Chiusure.Spegnimento, bloccato: true);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 2 * Minuto, 121);
        Assert.Null(e.ProgrammaChiuso);
        Assert.False(e.ChiusoDuranteBlocco);
    }

    // ---------- (0.18, contratto v4.0) lo spegnimento che non avviene, e lo Studio ----------

    private static StatoVivo VivoCoperto(long utc, long tick, long? boot = 121, string? chiusura = null, bool bloccato = false, bool studio = false) =>
        new() { UtcMs = utc, TickMs = tick, BootId = boot, Chiusura = chiusura, BloccatoFaccende = bloccato, StudioInCorso = studio };

    [Fact]
    public void Chiusura_spegnimento_ma_stesso_avvio_di_Windows_e_uno_spegnimento_annullato()
    {
        // vivo.json diceva "spegnimento", ma l'avvio di Windows è lo stesso (121 = 121): lo spegnimento non è
        // avvenuto (poi Pactum ucciso e riaperto). Ripresa spegnimento_annullato; programma_chiuso perché era in blocco.
        var prima = VivoCoperto(Adesso - 30 * Minuto, 10 * Minuto, chiusura: Chiusure.Spegnimento, bloccato: true);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.Equal("spegnimento_annullato", e.MotivoRipresa);
        Assert.Equal(Adesso - 30 * Minuto, e.RipresaDalMs); // ripresa { motivo: spegnimento_annullato, dal }
        Assert.NotNull(e.ProgrammaChiuso);
        Assert.Equal("programma_chiuso", Json.Testo(e.ProgrammaChiuso!["sotto_tipo"]));
        Assert.Equal("spegnimento_annullato", Json.Testo(e.ProgrammaChiuso["causa"]));
        Assert.True(e.ChiusoDuranteBlocco);
    }

    [Fact]
    public void Spegnimento_annullato_fuori_da_blocco_e_studio_nessuna_manomissione()
    {
        // Stesso avvio, chiusura "spegnimento", ma NON era coperto: ripresa spegnimento_annullato senza manomissione.
        var prima = VivoCoperto(Adesso - 30 * Minuto, 10 * Minuto, chiusura: Chiusure.Spegnimento, bloccato: false, studio: false);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.Equal("spegnimento_annullato", e.MotivoRipresa);
        Assert.Equal(Adesso - 30 * Minuto, e.RipresaDalMs); // ripresa { motivo: spegnimento_annullato, dal }
        Assert.Null(e.ProgrammaChiuso);
        Assert.False(e.ChiusoDuranteBlocco);
        Assert.False(e.ChiusoDuranteStudio);
    }

    [Fact]
    public void Disconnessione_stesso_avvio_in_studio_e_programma_chiuso_con_causa_disconnessione()
    {
        var prima = VivoCoperto(Adesso - 30 * Minuto, 10 * Minuto, chiusura: Chiusure.Disconnessione, studio: true);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.Equal("accesso", e.MotivoRipresa);
        Assert.NotNull(e.ProgrammaChiuso);
        Assert.Equal("disconnessione", Json.Testo(e.ProgrammaChiuso!["causa"]));
        Assert.True(e.ChiusoDuranteStudio);
        Assert.False(e.ChiusoDuranteBlocco);
    }

    [Fact]
    public void Chiuso_a_forza_durante_uno_studio_si_dice_al_riavvio()
    {
        // Chiusura null (ucciso di colpo), stesso avvio, era in Studio: programma_chiuso + chiuso_durante_studio.
        var prima = VivoCoperto(Adesso - 30 * Minuto, 10 * Minuto, bloccato: false, studio: true);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.NotNull(e.ProgrammaChiuso);
        Assert.True(e.ChiusoDuranteStudio);
        var evento = Eventi.ChiusoDuranteStudio(Adesso);
        Assert.Equal("chiuso_durante_studio", Json.Testo(evento.Dettagli["sotto_tipo"]));
    }

    [Fact]
    public void Chiuso_durante_un_blocco_poi_Windows_spento_pulito_si_dice_al_riavvio()
    {
        // Avvio di Windows diverso, nessuna chiusura pulita di Pactum, bloccato; lo spegnimento PULITO di
        // Windows è successivo all'ultimo "sono vivo" → Pactum era già morto prima dello spegnimento.
        var prima = VivoBloccato(Adesso - 2 * 60 * Minuto, 300 * Minuto, boot: 120, bloccato: true);
        long spentoPulito = Adesso - 60 * Minuto; // dopo l'ultimo "sono vivo" (−120 min)
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 5 * Minuto, 121, spentoPulito);
        Assert.True(e.ChiusoDuranteBlocco);
        Assert.NotNull(e.ProgrammaChiuso);
        Assert.Equal("programma_chiuso", Json.Testo(e.ProgrammaChiuso!["sotto_tipo"]));
        Assert.Equal(prima.UtcMs, Json.Intero(e.ProgrammaChiuso["dal"]));
        Assert.Equal(spentoPulito, Json.Intero(e.ProgrammaChiuso["al"]));
    }

    [Fact]
    public void Spegnimento_non_pulito_durante_un_blocco_non_dice_niente()
    {
        // ShutdownTime vecchio (<= l'ultimo "sono vivo"): corrente staccata o schermata blu, non lo aggiornano.
        var prima = VivoBloccato(Adesso - 60 * Minuto, 300 * Minuto, boot: 120, bloccato: true);
        long spentoVecchio = Adesso - 90 * Minuto; // PRIMA dell'ultimo "sono vivo"
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 5 * Minuto, 121, spentoVecchio);
        Assert.False(e.ChiusoDuranteBlocco);
        Assert.Null(e.ProgrammaChiuso);
    }

    [Fact]
    public void Riavvio_di_Windows_senza_sapere_lo_spegnimento_non_dice_niente()
    {
        // ShutdownTime non leggibile (null): non si inventa una manomissione.
        var prima = VivoBloccato(Adesso - 30 * Minuto, 300 * Minuto, boot: 120, bloccato: true);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 2 * Minuto, 121, null);
        Assert.False(e.ChiusoDuranteBlocco);
    }

    [Fact]
    public void Non_bloccato_e_Windows_riavviato_non_e_chiuso_durante_blocco_anche_con_ShutdownTime()
    {
        // Non era bloccato: lo spegnimento pulito successivo non è una manomissione del blocco.
        var prima = VivoBloccato(Adesso - 2 * 60 * Minuto, 300 * Minuto, boot: 120, bloccato: false);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 5 * Minuto, 121, Adesso - 60 * Minuto);
        Assert.False(e.ChiusoDuranteBlocco);
        Assert.Null(e.ProgrammaChiuso);
    }

    [Fact]
    public void Un_crash_non_diventa_una_manomissione()
    {
        // Chiusura "crash" scritta dal gestore delle eccezioni: niente programma_chiuso né chiuso_durante_blocco.
        var prima = VivoBloccato(Adesso - 30 * Minuto, 10 * Minuto, chiusura: Chiusure.Crash, bloccato: true);
        var e = Nucleo.Vivo.ValutaAvvio(prima, Adesso, 40 * Minuto, 121);
        Assert.Null(e.ProgrammaChiuso);
        Assert.False(e.ChiusoDuranteBlocco);
    }

    [Fact]
    public void La_manomissione_stato_blocco_perso_ha_il_suo_sotto_tipo()
    {
        var e = Eventi.Manomissione(new System.Text.Json.Nodes.JsonObject { ["sotto_tipo"] = "stato_blocco_perso" }, Adesso);
        Assert.Equal(TipiEvento.Manomissione, e.Tipo);
        Assert.Equal("stato_blocco_perso", Json.Testo(e.Dettagli["sotto_tipo"]));
    }
}
