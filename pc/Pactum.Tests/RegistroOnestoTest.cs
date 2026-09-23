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
}
