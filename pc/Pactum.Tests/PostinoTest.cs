using System.Net;
using System.Net.Sockets;
using System.Text;
using Pactum.Motore;

namespace Pactum.Tests;

/// <summary>
/// Il server vero (uvicorn) tiene aperte le connessioni ma chiude quelle ferme dopo
/// 5 secondi. Una modifica mandata dopo una pausa deve riuscire al primo colpo e
/// partire una volta sola: nessuna connessione ferma riusata, nessuna ripetizione.
/// </summary>
public sealed class PostinoTest : IAsyncLifetime
{
    private readonly TcpListener ascolto = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource fine = new();
    private int richieste;
    private int connessioni;
    private Task? servizio;

    private string Base => $"http://127.0.0.1:{((IPEndPoint)ascolto.LocalEndpoint).Port}";

    public Task InitializeAsync()
    {
        ascolto.Start();
        servizio = Task.Run(AccettaAsync);
        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        fine.Cancel();
        ascolto.Stop();
        if (servizio != null) await Task.WhenAny(servizio, Task.Delay(2000));
    }

    private async Task AccettaAsync()
    {
        while (!fine.IsCancellationRequested)
        {
            TcpClient c;
            try
            {
                c = await ascolto.AcceptTcpClientAsync(fine.Token);
            }
            catch (Exception)
            {
                return;
            }
            Interlocked.Increment(ref connessioni);
            _ = Task.Run(() => ServiAsync(c));
        }
    }

    /// <summary>HTTP/1.1 con keep-alive; dopo 300 ms senza richieste chiude, come uvicorn dopo 5 s.</summary>
    private async Task ServiAsync(TcpClient c)
    {
        using var _ = c;
        var flusso = c.GetStream();
        var buffer = new List<byte>();
        var pezzo = new byte[4096];
        while (true)
        {
            int fineIntestazione;
            while ((fineIntestazione = Cerca(buffer, "\r\n\r\n")) < 0)
            {
                using var attesa = new CancellationTokenSource(300);
                int n;
                try
                {
                    n = await flusso.ReadAsync(pezzo, attesa.Token);
                }
                catch (Exception)
                {
                    return; // ferma troppo a lungo: si chiude
                }
                if (n == 0) return;
                buffer.AddRange(pezzo.AsSpan(0, n).ToArray());
            }
            var intestazione = Encoding.ASCII.GetString(buffer.GetRange(0, fineIntestazione).ToArray());
            int lunghezza = 0;
            bool chiudi = false;
            foreach (var riga in intestazione.Split("\r\n"))
            {
                if (riga.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase)) lunghezza = int.Parse(riga[15..].Trim());
                if (riga.StartsWith("Connection:", StringComparison.OrdinalIgnoreCase) && riga.Contains("close", StringComparison.OrdinalIgnoreCase)) chiudi = true;
            }
            while (buffer.Count < fineIntestazione + 4 + lunghezza)
            {
                int n = await flusso.ReadAsync(pezzo);
                if (n == 0) return;
                buffer.AddRange(pezzo.AsSpan(0, n).ToArray());
            }
            buffer.RemoveRange(0, fineIntestazione + 4 + lunghezza);
            Interlocked.Increment(ref richieste);
            var corpo = "{\"ricevuto\":true}";
            var risposta = $"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {corpo.Length}\r\nConnection: {(chiudi ? "close" : "keep-alive")}\r\n\r\n{corpo}";
            await flusso.WriteAsync(Encoding.ASCII.GetBytes(risposta));
            if (chiudi) return;
        }
    }

    private static int Cerca(List<byte> b, string s)
    {
        var cercato = Encoding.ASCII.GetBytes(s);
        for (int i = 0; i + cercato.Length <= b.Count; i++)
        {
            bool uguale = true;
            for (int j = 0; j < cercato.Length && uguale; j++) uguale = b[i + j] == cercato[j];
            if (uguale) return i;
        }
        return -1;
    }

    [Fact]
    public async Task Una_modifica_dopo_una_pausa_riesce_al_primo_colpo_e_parte_una_volta()
    {
        using var postino = new Postino();
        var prima = await postino.InviaAsync("POST", Base, "api/bonus", "t", "{\"minuti\":5}");
        await Task.Delay(1000); // il server ha già chiuso le connessioni ferme
        var dopo = await postino.InviaAsync("POST", Base, "api/bonus", "t", "{\"minuti\":5}");
        Assert.Equal(200, prima.Stato);
        Assert.Equal(200, dopo.Stato);
        Assert.Equal(2, Volatile.Read(ref richieste));
        Assert.Equal(2, Volatile.Read(ref connessioni));
    }

    [Fact]
    public async Task Anche_le_letture_dopo_una_pausa_riescono()
    {
        using var postino = new Postino();
        Assert.Equal(200, (await postino.InviaAsync("GET", Base, "api/patto", "t", null)).Stato);
        await Task.Delay(1000);
        Assert.Equal(200, (await postino.InviaAsync("GET", Base, "api/patto", "t", null)).Stato);
        Assert.Equal(2, Volatile.Read(ref richieste));
    }

    [Fact]
    public async Task Senza_server_la_risposta_e_senza_rete_e_nessuna_eccezione()
    {
        using var postino = new Postino();
        var r = await postino.InviaAsync("POST", "http://127.0.0.1:9", "api/eventi", "t", "{}", TimeSpan.FromSeconds(3));
        Assert.True(r.Rete);
        Assert.False(r.Ok);
    }
}
