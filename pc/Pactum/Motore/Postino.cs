using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;

namespace Pactum.Motore;

/// <summary>Una risposta del server. <see cref="Stato"/> 0 = il server non ha risposto (rete, tempo scaduto).</summary>
public readonly record struct Risposta(int Stato, string Corpo)
{
    public bool Ok => Stato is >= 200 and < 300;
    public bool Rete => Stato == 0;
    public static Risposta SenzaRete => new(0, "");
}

/// <summary>
/// Il client HTTP verso il server di Pactum. Le letture riusano le connessioni;
/// le modifiche (POST, PATCH, DELETE) partono sempre su una connessione nuova e
/// chiusa subito, così il client non può ripeterle da solo: un bonus non deve
/// partire due volte. I ritentativi li decide la coda degli eventi, che è idempotente.
/// </summary>
public sealed class Postino : IDisposable
{
    private static readonly TimeSpan TempoMassimo = TimeSpan.FromSeconds(20);

    private readonly HttpClient letture;
    private readonly HttpClient modifiche;

    public Postino()
    {
        letture = new HttpClient(new SocketsHttpHandler
        {
            ConnectTimeout = TimeSpan.FromSeconds(10),
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            // Il server (uvicorn) chiude le connessioni ferme dopo 5 secondi: si lasciano prima.
            PooledConnectionIdleTimeout = TimeSpan.FromSeconds(2),
        })
        { Timeout = Timeout.InfiniteTimeSpan };
        modifiche = new HttpClient(new SocketsHttpHandler
        {
            ConnectTimeout = TimeSpan.FromSeconds(10),
            // Una connessione nuova per ogni modifica: mai una connessione rimasta ferma (il server la
            // chiude dopo 5 secondi) e niente ripetizione automatica di .NET, che scatta solo su quelle riusate.
            PooledConnectionLifetime = TimeSpan.Zero,
        })
        { Timeout = Timeout.InfiniteTimeSpan };
        foreach (var c in new[] { letture, modifiche })
        {
            c.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("Pactum-Computer", Versione.Nome));
        }
    }

    /// <summary>
    /// Una richiesta al server: <paramref name="percorso"/> relativo alla base (es. <c>api/patto?x=1</c>).
    /// Mai eccezioni: un problema di rete è <see cref="Risposta.SenzaRete"/>.
    /// </summary>
    public async Task<Risposta> InviaAsync(string metodo, string server, string percorso, string? token, string? corpoJson,
        TimeSpan? tempoMassimo = null, CancellationToken annulla = default)
    {
        if (!Uri.TryCreate(server.TrimEnd('/') + "/" + percorso.TrimStart('/'), UriKind.Absolute, out var uri)) return Risposta.SenzaRete;
        var metodoHttp = new HttpMethod(metodo.ToUpperInvariant());
        using var richiesta = new HttpRequestMessage(metodoHttp, uri);
        bool modifica = metodoHttp != HttpMethod.Get && metodoHttp != HttpMethod.Head;
        if (modifica) richiesta.Headers.ConnectionClose = true;
        if (token != null) richiesta.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        richiesta.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (corpoJson != null) richiesta.Content = new StringContent(corpoJson, Encoding.UTF8, "application/json");

        using var scadenza = CancellationTokenSource.CreateLinkedTokenSource(annulla);
        scadenza.CancelAfter(tempoMassimo ?? TempoMassimo);
        try
        {
            using var risposta = await (modifica ? modifiche : letture).SendAsync(richiesta, HttpCompletionOption.ResponseContentRead, scadenza.Token).ConfigureAwait(false);
            var corpo = await risposta.Content.ReadAsStringAsync(scadenza.Token).ConfigureAwait(false);
            return new Risposta((int)risposta.StatusCode, corpo);
        }
        catch (HttpRequestException)
        {
            return Risposta.SenzaRete;
        }
        catch (OperationCanceledException)
        {
            return Risposta.SenzaRete;
        }
        catch (IOException)
        {
            return Risposta.SenzaRete;
        }
    }

    public void Dispose()
    {
        letture.Dispose();
        modifiche.Dispose();
    }
}
