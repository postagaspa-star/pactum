using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// (0.18, contratto v4.0, «Il mutex occupato da un altro processo») La decisione del guardiano quando il mutex è
/// già preso. Il repo è pubblico: una risposta da sola non prova niente. Conta solo se è vivo un altro Pactum.exe.
/// </summary>
public class ArbitroIstanzaTest
{
    [Fact]
    public void Pactum_vivo_esce_in_silenzio()
    {
        // Pactum c'è (magari lento o impallato e non ha risposto): niente accusa, due Pactum insieme mai.
        Assert.Equal(EsitoGuardiano.EsciInSilenzio, ArbitroIstanza.Decidi(altroPactumVivo: true));
    }

    [Fact]
    public void Nessun_Pactum_vivo_parte_e_accusa_anche_se_qualcuno_ha_risposto()
    {
        // Un finto risponditore (uno script che tiene il mutex e imita l'evento): il guardiano parte e manda istanza_occupata.
        Assert.Equal(EsitoGuardiano.PartiEAccusa, ArbitroIstanza.Decidi(altroPactumVivo: false));
    }

    [Fact]
    public void Versione_piu_vecchia_viva_si_riconosce_solo_se_nota_e_piu_bassa()
    {
        var mia = new Version(0, 18, 0);
        Assert.True(AltriPactum.UnaÈPiùVecchia(new Version?[] { new Version(0, 14, 0) }, mia));
        Assert.True(AltriPactum.UnaÈPiùVecchia(new Version?[] { null, new Version(0, 13, 0) }, mia));
        Assert.False(AltriPactum.UnaÈPiùVecchia(new Version?[] { new Version(0, 18, 0) }, mia));
        Assert.False(AltriPactum.UnaÈPiùVecchia(new Version?[] { new Version(0, 19, 0) }, mia));
        // Una versione che non si legge non fa dire niente: si chiede solo di mostrarsi, come prima.
        Assert.False(AltriPactum.UnaÈPiùVecchia(new Version?[] { null }, mia));
        Assert.False(AltriPactum.UnaÈPiùVecchia(Array.Empty<Version?>(), mia));
    }

    [Theory]
    // --avvio (Windows al login) con il mutex preso: sempre in silenzio, Pactum vivo o no.
    [InlineData(true, false, true, AzioneAvvio.EsciInSilenzio)]
    [InlineData(true, false, false, AzioneAvvio.EsciInSilenzio)]
    // --guardiano (che ha anche --avvio): in silenzio se Pactum è vivo, altrimenti parte lo stesso.
    [InlineData(true, true, true, AzioneAvvio.EsciInSilenzio)]
    [InlineData(true, true, false, AzioneAvvio.PartiLoStesso)]
    // A mano: mostra quello vivo; se il mutex lo tiene un estraneo, parte lo stesso.
    [InlineData(false, false, true, AzioneAvvio.MostraQuellaViva)]
    [InlineData(false, false, false, AzioneAvvio.PartiLoStesso)]
    public void Cosa_fa_un_avvio_col_mutex_preso(bool avvio, bool guardiano, bool pactumVivo, AzioneAvvio atteso)
    {
        Assert.Equal(atteso, ArbitroIstanza.ConMutexPreso(avvio, guardiano, pactumVivo));
    }

    [Fact]
    public void Testi_del_guardiano()
    {
        Assert.Contains("si riapre da solo entro un minuto", Testi.DomandaChiudiPactum);
        Assert.StartsWith("È aperta una versione più vecchia di Pactum", Testi.VersionePiùVecchiaAperta);
    }
}
