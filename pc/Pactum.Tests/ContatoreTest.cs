using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>Il conto del tempo attivo, le visite dei siti, il cambio del giorno a mezzanotte.</summary>
public class ContatoreTest
{
    private static readonly Osservazione Word = new(true, "exe:winword.exe", "Microsoft Word");

    private static Osservazione Chrome(string? dominio, bool fallita = false) =>
        new(true, "exe:chrome.exe", "Google Chrome", Browser: true, Dominio: dominio, LetturaFallita: fallita);

    private static Contatore Nuovo(string giorno) => new(Giornata.Nuova(giorno), Giornata.Nuova);

    /// <summary>Un giro al secondo a partire da <paramref name="inizio"/>.</summary>
    private static long Giri(Contatore c, long inizio, int quanti, Osservazione o, Action<EsitoGiro>? esito = null)
    {
        long t = inizio;
        for (int i = 0; i < quanti; i++)
        {
            t += 1000;
            var e = c.Registra(t, 1000, o, Fuso.Roma);
            esito?.Invoke(e);
        }
        return t;
    }

    [Fact]
    public void Il_tempo_si_somma_solo_quando_l_utente_c_e()
    {
        var c = Nuovo("2026-09-23");
        long t = Giri(c, Fuso.Ms("2026-09-23T10:00:00"), 10, Word);
        Giri(c, t, 10, Osservazione.Assente);
        Assert.Equal(10_000, c.Oggi.MsAttivi);
        Assert.Equal(10_000, c.Oggi.Programmi["exe:winword.exe"].Ms);
        Assert.Equal("Microsoft Word", c.Oggi.Programmi["exe:winword.exe"].Nome);
    }

    [Fact]
    public void Un_giro_lungo_dopo_una_sospensione_non_inventa_tempo()
    {
        var c = Nuovo("2026-09-23");
        c.Registra(Fuso.Ms("2026-09-23T10:00:00"), 3_600_000, Word, Fuso.Roma);
        Assert.Equal(Contatore.MsMassimiPerGiro, c.Oggi.MsAttivi);
    }

    [Fact]
    public void A_mezzanotte_il_giro_si_divide_tra_i_due_giorni()
    {
        var c = Nuovo("2026-09-23");
        var esito = c.Registra(Fuso.Ms("2026-09-24T00:00:01"), 2000, Word, Fuso.Roma);
        var ieri = Assert.Single(esito.GiorniChiusi);
        Assert.Equal("2026-09-23", ieri.Giorno);
        Assert.Equal(1000, ieri.MsAttivi);
        Assert.Equal("2026-09-24", c.Oggi.Giorno);
        Assert.Equal(1000, c.Oggi.MsAttivi);
        // Il secondo prima di mezzanotte sta nel minuto 23:59 del giorno vecchio.
        long minuto2359 = Fuso.Ms("2026-09-23T23:59:00") / 60_000;
        Assert.Equal(1000, ieri.MsPerMinuto[minuto2359]);
    }

    [Fact]
    public void Il_giorno_cambia_anche_se_nessuno_usa_il_computer()
    {
        var c = Nuovo("2026-09-23");
        var esito = c.Registra(Fuso.Ms("2026-09-24T03:00:00"), 1000, Osservazione.Assente, Fuso.Roma);
        Assert.Single(esito.GiorniChiusi);
        Assert.Equal("2026-09-24", c.Oggi.Giorno);
        Assert.Equal(0, c.Oggi.MsAttivi);
    }

    [Fact]
    public void Il_giorno_nuovo_riprende_da_quello_salvato()
    {
        var salvato = Giornata.Nuova("2026-09-24");
        salvato.MsAttivi = 5000;
        var c = new Contatore(Giornata.Nuova("2026-09-23"), g => g == "2026-09-24" ? salvato : Giornata.Nuova(g));
        c.Registra(Fuso.Ms("2026-09-24T09:00:00"), 1000, Word, Fuso.Roma);
        Assert.Same(salvato, c.Oggi);
        Assert.Equal(6000, c.Oggi.MsAttivi);
    }

    [Fact]
    public void Una_visita_e_il_sito_che_diventa_quello_in_primo_piano()
    {
        var c = Nuovo("2026-09-23");
        long t = Giri(c, Fuso.Ms("2026-09-23T15:00:00"), 3, Chrome("youtube.com"));
        t = Giri(c, t, 2, Chrome("wikipedia.org"));
        Giri(c, t, 1, Chrome("youtube.com"));
        Assert.Equal(2, c.Oggi.Siti["youtube.com"].Visite);
        Assert.Equal(4000, c.Oggi.Siti["youtube.com"].Ms);
        Assert.Equal(1, c.Oggi.Siti["wikipedia.org"].Visite);
        Assert.Equal(2000, c.Oggi.Siti["wikipedia.org"].Ms);
    }

    [Fact]
    public void Tornare_al_sito_da_un_altro_programma_e_una_visita_nuova()
    {
        var c = Nuovo("2026-09-23");
        long t = Giri(c, Fuso.Ms("2026-09-23T15:00:00"), 2, Chrome("youtube.com"));
        t = Giri(c, t, 2, Word);
        Giri(c, t, 2, Chrome("youtube.com"));
        Assert.Equal(2, c.Oggi.Siti["youtube.com"].Visite);
    }

    [Fact]
    public void Una_lettura_mancata_o_una_pausa_non_sono_una_visita()
    {
        var c = Nuovo("2026-09-23");
        long t = Giri(c, Fuso.Ms("2026-09-23T15:00:00"), 2, Chrome("youtube.com"));
        t = Giri(c, t, 3, Chrome(null, fallita: true));
        t = Giri(c, t, 5, Osservazione.Assente);
        Giri(c, t, 2, Chrome("youtube.com"));
        Assert.Equal(1, c.Oggi.Siti["youtube.com"].Visite);
        Assert.Equal(4000, c.Oggi.Siti["youtube.com"].Ms);
        // Il tempo nel browser c'è comunque: solo il sito non si sa.
        Assert.Equal(7000, c.Oggi.Programmi["exe:chrome.exe"].Ms);
    }

    [Fact]
    public void Dopo_mezzanotte_il_sito_in_primo_piano_e_una_visita_del_giorno_nuovo()
    {
        var c = Nuovo("2026-09-23");
        Giri(c, Fuso.Ms("2026-09-23T23:59:57"), 6, Chrome("youtube.com"));
        Assert.Equal("2026-09-24", c.Oggi.Giorno);
        Assert.Equal(1, c.Oggi.Siti["youtube.com"].Visite);
    }

    [Fact]
    public void Oltre_un_minuto_di_letture_fallite_il_giorno_dichiara_i_siti_non_leggibili()
    {
        var c = Nuovo("2026-09-23");
        var segnalati = new List<string>();
        long t = Giri(c, Fuso.Ms("2026-09-23T15:00:00"), 60, Chrome(null, fallita: true), e => segnalati.AddRange(e.BrowserNonLeggibili));
        Assert.False(c.Oggi.SitiNonLeggibili);
        Giri(c, t, 30, Chrome(null, fallita: true), e => segnalati.AddRange(e.BrowserNonLeggibili));
        Assert.True(c.Oggi.SitiNonLeggibili);
        Assert.Equal(new[] { "exe:chrome.exe" }, segnalati);
    }

    [Fact]
    public void Una_lettura_riuscita_azzera_il_conto_delle_fallite()
    {
        var c = Nuovo("2026-09-23");
        long t = Giri(c, Fuso.Ms("2026-09-23T15:00:00"), 50, Chrome(null, fallita: true));
        t = Giri(c, t, 1, Chrome("example.com"));
        Giri(c, t, 50, Chrome(null, fallita: true));
        Assert.False(c.Oggi.SitiNonLeggibili);
    }

    [Fact]
    public void Il_tempo_nel_browser_va_nella_categoria_del_sito()
    {
        var c = Nuovo("2026-09-23");
        long t = Giri(c, Fuso.Ms("2026-09-23T15:00:00"), 120, Chrome("youtube.com"));
        t = Giri(c, t, 60, Chrome("wikipedia.org"));
        Giri(c, t, 60, new Osservazione(true, "exe:steam.exe", "Steam"));
        Assert.Equal(2, c.Oggi.MinutiDi("categoria:video"));
        Assert.Equal(1, c.Oggi.MinutiDi("categoria:altro"));
        Assert.Equal(1, c.Oggi.MinutiDi("categoria:giochi"));
        Assert.Equal(3, c.Oggi.MinutiDi("exe:chrome.exe"));
        Assert.Equal(2, c.Oggi.MinutiDi("sito:youtube.com"));
        Assert.Equal(2, c.Oggi.MinutiDi("sito:m.youtube.com"));
        Assert.Equal(4, c.Oggi.MinutiTotali);
    }

    [Fact]
    public void I_minuti_di_un_intervallo_vengono_dal_conto_per_minuto()
    {
        var c = Nuovo("2026-09-23");
        Giri(c, Fuso.Ms("2026-09-23T22:10:00"), 180, Word);
        Assert.Equal(3, c.Oggi.MinutiNellIntervallo(Fuso.Ms("2026-09-23T22:00:00"), Fuso.Ms("2026-09-23T23:00:00")));
        Assert.Equal(1, c.Oggi.MinutiNellIntervallo(Fuso.Ms("2026-09-23T22:12:00"), Fuso.Ms("2026-09-23T22:13:00")));
        Assert.Equal(0, c.Oggi.MinutiNellIntervallo(Fuso.Ms("2026-09-23T21:00:00"), Fuso.Ms("2026-09-23T22:00:00")));
    }

    [Theory]
    [InlineData(true, false, true, 10_000, false, true)]
    [InlineData(true, false, true, 179_000, false, true)]
    [InlineData(true, false, true, 180_000, false, false)]
    [InlineData(true, false, true, 3_600_000, true, true)]
    [InlineData(true, false, false, 3_600_000, true, false)]
    [InlineData(false, false, true, 1_000, false, false)]
    [InlineData(true, true, true, 3_600_000, true, false)]
    public void L_utente_c_e_se_ha_toccato_qualcosa_da_poco_o_guarda_uno_schermo_intero(
        bool sessione, bool salvaschermo, bool schermoAcceso, long msSenzaInput, bool schermoIntero, bool atteso)
    {
        Assert.Equal(atteso, Presenza.Attivo(sessione, salvaschermo, schermoAcceso, msSenzaInput, schermoIntero));
    }
}
