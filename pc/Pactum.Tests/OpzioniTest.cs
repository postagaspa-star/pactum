using Pactum.Motore;

namespace Pactum.Tests;

/// <summary>
/// Le opzioni di prova non possono falsare il programma vero: valgono solo con --dati su una
/// cartella diversa da quella vera (%LOCALAPPDATA%\Pactum). Nessuna cartella viene toccata qui.
/// </summary>
public class OpzioniTest
{
    [Fact]
    public void La_prova_rapida_del_pacchetto_funziona_ancora()
    {
        // La riga stampata da crea-pacchetto.ps1.
        var cartella = Path.Combine(Path.GetTempPath(), "pactum-prova");
        var o = Opzioni.Da(new[] { "--no-installa", "--dati", cartella, "--esci-dopo", "5" });
        Assert.True(o.CartellaDiProva);
        Assert.True(o.NonInstallare);
        Assert.Equal(5, o.EsciDopoSecondi);
        Assert.Empty(o.OpzioniIgnorate);
        Assert.NotEqual("", o.SuffissoIstanza);
    }

    [Fact]
    public void Con_una_cartella_di_prova_valgono_tutte()
    {
        var cartella = Path.Combine(Path.GetTempPath(), "pactum-prova-2");
        var o = Opzioni.Da(new[] { "--dati", cartella, "--siti-solo", "example.com", "--prova-finestra", "42",
            "--prova-avvisi", Path.Combine(cartella, "avvisi"), "--esci-dopo-autoprova" });
        Assert.Equal(new[] { "example.com" }, o.SitiSolo!);
        Assert.Equal(42, o.ProvaPidFinestra);
        Assert.NotNull(o.CartellaProvaAvvisi);
        Assert.True(o.EsciDopoAutoprova);
        Assert.Empty(o.OpzioniIgnorate);
    }

    [Fact]
    public void Senza_una_cartella_di_prova_le_opzioni_di_prova_si_ignorano()
    {
        var o = Opzioni.Da(new[] { "--avvio", "--siti-solo", "example.com", "--prova-finestra", "123", "--esci-dopo", "1",
            "--prova-avvisi", Path.Combine(Path.GetTempPath(), "avvisi"), "--esci-dopo-autoprova" });
        Assert.True(o.Avvio);
        Assert.False(o.CartellaDiProva);
        Assert.Null(o.SitiSolo);
        Assert.Null(o.ProvaPidFinestra);
        Assert.Null(o.EsciDopoSecondi);
        Assert.Null(o.CartellaProvaAvvisi);
        Assert.False(o.EsciDopoAutoprova);
        Assert.Equal(new[] { "--prova-avvisi", "--siti-solo", "--prova-finestra", "--esci-dopo", "--esci-dopo-autoprova" }, o.OpzioniIgnorate);
        Assert.Equal("", o.SuffissoIstanza);
    }

    [Fact]
    public void Il_guardiano_e_come_l_avvio_ma_segnato()
    {
        // (0.18, contratto v4.0) --guardiano: la finestra non si apre da sola (come --avvio), ma fa l'arbitraggio.
        var o = Opzioni.Da(new[] { "--guardiano" });
        Assert.True(o.Guardiano);
        Assert.True(o.Avvio);
        Assert.False(o.Apri);
        // --avvio da solo non è un guardiano.
        var a = Opzioni.Da(new[] { "--avvio" });
        Assert.True(a.Avvio);
        Assert.False(a.Guardiano);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Con_dati_sulla_cartella_vera_e_come_senza(bool barraInFondo)
    {
        // Solo il nome della cartella vera: non la si apre e non la si crea.
        var vera = (Percorsi.Predefinita + (barraInFondo ? "\\" : "")).ToUpperInvariant();
        var o = Opzioni.Da(new[] { "--dati", vera, "--siti-solo", "example.com", "--esci-dopo", "1" });
        Assert.False(o.CartellaDiProva);
        Assert.Null(o.SitiSolo);
        Assert.Null(o.EsciDopoSecondi);
        Assert.Equal(new[] { "--siti-solo", "--esci-dopo" }, o.OpzioniIgnorate);
        // La stessa istanza del programma vero: non se ne apre una seconda sugli stessi dati.
        Assert.Equal("", o.SuffissoIstanza);
    }
}
