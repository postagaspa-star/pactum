namespace Pactum.Nucleo;

/// <summary>Cosa fa il guardiano quando trova il mutex già preso (contratto v4.0, «Il mutex occupato da un altro processo»).</summary>
public enum EsitoGuardiano
{
    /// <summary>Un altro processo <c>Pactum.exe</c> è vivo: esci in silenzio (due Pactum insieme non ci sono mai).</summary>
    EsciInSilenzio,

    /// <summary>Nessun processo <c>Pactum.exe</c> è vivo: chi tiene il mutex (o risponde) non è Pactum. Parti lo stesso e manda <c>istanza_occupata</c>.</summary>
    PartiEAccusa,
}

/// <summary>
/// (0.18, contratto v4.0) La decisione del guardiano quando il mutex <c>Local\Pactum.Computer…</c> è già
/// preso (o i suoi oggetti non si aprono), logica pura (provata nei test). Il repo è pubblico: i nomi degli
/// eventi sono noti, quindi <b>una risposta da sola non prova niente</b> (chiunque nello stesso account può
/// imitarla). Conta solo se è vivo un altro processo <c>Pactum.exe</c> («Chi risponde deve essere davvero
/// Pactum»):
/// <list type="bullet">
/// <item>sì: <b>esci in silenzio</b> (Pactum c'è, magari lento a rispondere);</item>
/// <item>no: <b>parti lo stesso</b> e manda <c>istanza_occupata</c> una volta per accensione, anche se qualcuno
/// ha risposto alla domanda «sei vivo?».</item>
/// </list>
/// La risposta serve solo a non aspettare i 10 secondi della seconda prova.
/// </summary>
public static class ArbitroIstanza
{
    public static EsitoGuardiano Decidi(bool altroPactumVivo) =>
        altroPactumVivo ? EsitoGuardiano.EsciInSilenzio : EsitoGuardiano.PartiEAccusa;

    /// <summary>
    /// (0.18, contratto v4.0) Cosa fa un avvio che trova il mutex già preso, logica pura:
    /// <list type="bullet">
    /// <item><c>--avvio</c> (Windows al login): esce in silenzio, sempre (il guardiano riparte entro un minuto);</item>
    /// <item><c>--guardiano</c> (ogni minuto, dopo le due domande): esce in silenzio se un altro Pactum.exe è vivo,
    /// altrimenti parte lo stesso e accusa;</item>
    /// <item>a mano (doppio clic): con un altro Pactum.exe vivo gli chiede di mostrarsi, come prima; senza, il mutex
    /// lo tiene un estraneo e si parte lo stesso, come il guardiano (altrimenti il doppio clic non aprirebbe niente).</item>
    /// </list>
    /// </summary>
    public static AzioneAvvio ConMutexPreso(bool avvioAutomatico, bool guardiano, bool altroPactumVivo)
    {
        if (guardiano || !avvioAutomatico)
        {
            if (altroPactumVivo) return guardiano ? AzioneAvvio.EsciInSilenzio : AzioneAvvio.MostraQuellaViva;
            return AzioneAvvio.PartiLoStesso;
        }
        return AzioneAvvio.EsciInSilenzio;
    }
}

/// <summary>(0.18) Cosa fa un avvio di Pactum che trova il mutex già preso (v. <see cref="ArbitroIstanza.ConMutexPreso"/>).</summary>
public enum AzioneAvvio
{
    /// <summary>Esce senza aprire niente.</summary>
    EsciInSilenzio,

    /// <summary>Chiede al Pactum vivo di mostrare la sua finestra (o spiega che è una versione più vecchia) ed esce.</summary>
    MostraQuellaViva,

    /// <summary>Nessun Pactum.exe vivo: parte lo stesso, prende il mutex di riserva e manda <c>istanza_occupata</c>.</summary>
    PartiLoStesso,
}
