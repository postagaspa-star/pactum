global using System;
global using System.Collections.Generic;
global using System.IO;
global using System.Linq;
global using System.Threading;
global using System.Threading.Tasks;
global using Xunit;

using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>Il fuso dei test: quello di casa (Roma), con l'ora legale vera.</summary>
internal static class Fuso
{
    public static readonly TimeZoneInfo Roma = TimeZoneInfo.FindSystemTimeZoneById("W. Europe Standard Time");

    /// <summary>"2026-09-19T22:10:00" (ora di Roma) in millisecondi UTC.</summary>
    public static long Ms(string localeRoma)
    {
        var d = DateTime.ParseExact(localeRoma, "yyyy-MM-dd'T'HH:mm:ss", CultureInfo.InvariantCulture);
        return Tempo.UtcMsDi(DateOnly.FromDateTime(d), TimeOnly.FromDateTime(d), Roma);
    }
}

/// <summary>Una cartella temporanea che sparisce a fine test.</summary>
internal sealed class CartellaTemporanea : IDisposable
{
    public CartellaTemporanea()
    {
        Percorso = Path.Combine(Path.GetTempPath(), "pactum-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Percorso);
    }

    public string Percorso { get; }

    public string File(string nome) => Path.Combine(Percorso, nome);

    public void Dispose()
    {
        try
        {
            Directory.Delete(Percorso, recursive: true);
        }
        catch (IOException)
        {
        }
    }
}

/// <summary>
/// (0.10) Un server finto su 127.0.0.1 (porta a caso) per provare le richieste del motore senza
/// rete: risponde con <see cref="Risponde"/> e tiene le richieste ricevute, in ordine. Una
/// richiesta per connessione ("Connection: close"), niente altro di HTTP.
/// </summary>
internal sealed class ServerFinto : IAsyncDisposable
{
    private readonly TcpListener ascolto = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource fine = new();
    private readonly ConcurrentQueue<Richiesta> ricevute = new();
    private readonly Task servizio;

    public ServerFinto(Func<Richiesta, (int Stato, string Corpo)> risponde)
    {
        Risponde = risponde;
        ascolto.Start();
        servizio = Task.Run(AccettaAsync);
    }

    /// <param name="Percorso">Con la query, se c'è: <c>/api/proposte/21/ritira</c>.</param>
    /// <param name="Autorizzazione">L'intestazione <c>Authorization</c>, se c'è.</param>
    public sealed record Richiesta(string Metodo, string Percorso, string? Autorizzazione, string Corpo);

    public Func<Richiesta, (int Stato, string Corpo)> Risponde { get; set; }

    public string Indirizzo => $"http://127.0.0.1:{((IPEndPoint)ascolto.LocalEndpoint).Port}";

    public IReadOnlyList<Richiesta> Ricevute => ricevute.ToList();

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
            _ = Task.Run(() => ServiAsync(c));
        }
    }

    private async Task ServiAsync(TcpClient c)
    {
        using var _ = c;
        try
        {
            var flusso = c.GetStream();
            var letti = new List<byte>();
            var pezzo = new byte[4096];
            int fineIntestazione;
            while ((fineIntestazione = Cerca(letti)) < 0)
            {
                int n = await flusso.ReadAsync(pezzo, fine.Token);
                if (n == 0) return;
                letti.AddRange(pezzo.AsSpan(0, n).ToArray());
            }
            var righe = Encoding.ASCII.GetString(letti.GetRange(0, fineIntestazione).ToArray()).Split("\r\n");
            var primaRiga = righe[0].Split(' ');
            int lunghezza = 0;
            string? autorizzazione = null;
            foreach (var riga in righe.Skip(1))
            {
                var duePunti = riga.IndexOf(':');
                if (duePunti < 0) continue;
                var nome = riga[..duePunti].Trim();
                var valore = riga[(duePunti + 1)..].Trim();
                if (nome.Equals("Content-Length", StringComparison.OrdinalIgnoreCase)) lunghezza = int.Parse(valore, CultureInfo.InvariantCulture);
                if (nome.Equals("Authorization", StringComparison.OrdinalIgnoreCase)) autorizzazione = valore;
            }
            while (letti.Count < fineIntestazione + 4 + lunghezza)
            {
                int n = await flusso.ReadAsync(pezzo, fine.Token);
                if (n == 0) return;
                letti.AddRange(pezzo.AsSpan(0, n).ToArray());
            }
            var corpo = Encoding.UTF8.GetString(letti.GetRange(fineIntestazione + 4, lunghezza).ToArray());
            var richiesta = new Richiesta(primaRiga[0], primaRiga[1], autorizzazione, corpo);
            ricevute.Enqueue(richiesta);

            var (stato, risposta) = Risponde(richiesta);
            var byteCorpo = Encoding.UTF8.GetBytes(risposta);
            var testa = $"HTTP/1.1 {stato} Pactum\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {byteCorpo.Length}\r\nConnection: close\r\n\r\n";
            await flusso.WriteAsync(Encoding.ASCII.GetBytes(testa), fine.Token);
            await flusso.WriteAsync(byteCorpo, fine.Token);
        }
        catch (Exception) when (fine.IsCancellationRequested)
        {
        }
        catch (IOException)
        {
        }
    }

    private static int Cerca(List<byte> b)
    {
        for (int i = 0; i + 3 < b.Count; i++)
        {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') return i;
        }
        return -1;
    }

    public async ValueTask DisposeAsync()
    {
        fine.Cancel();
        ascolto.Stop();
        await Task.WhenAny(servizio, Task.Delay(2000));
    }
}
