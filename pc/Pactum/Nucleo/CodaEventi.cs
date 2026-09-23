using System.Text.Json.Nodes;

namespace Pactum.Nucleo;

public enum EsitoCoda
{
    /// <summary>Tutto consegnato.</summary>
    Vuota,

    /// <summary>Il server non ha risposto o ha detto "non ora": si riprova al giro dopo.</summary>
    Interrotta,
}

/// <summary>
/// La coda degli eventi da mandare, su disco (<c>coda.json</c>): sopravvive ai
/// riavvii e si svuota solo per id, quando il server ha risposto. Rimandare lo
/// stesso evento non fa danni: il server riconosce l'id (idempotenza del contratto).
/// I ritentativi li fa questa coda, al giro dopo; il client HTTP non ritenta mai.
/// </summary>
public sealed class CodaEventi
{
    public const int Massimo = 5000;
    public const int Lotto = 100;

    private readonly string percorso;
    private readonly object blocco = new();
    private List<Evento> eventi;

    public CodaEventi(string percorso)
    {
        this.percorso = percorso;
        eventi = Archivio.LeggiJson<List<Evento>>(percorso) ?? new List<Evento>();
    }

    public int Conta
    {
        get
        {
            lock (blocco) return eventi.Count;
        }
    }

    public void Accoda(Evento e)
    {
        lock (blocco)
        {
            eventi.Add(e);
            Taglia();
            Salva();
        }
    }

    /// <summary>
    /// Una fotografia nuova SOSTITUISCE quella dello stesso tipo e giorno ancora in
    /// coda: sono cumulative, al server basta l'ultima.
    /// </summary>
    public void SostituisciFotografia(Evento e)
    {
        lock (blocco)
        {
            var giorno = Json.Testo(e.Dettagli["giorno"]);
            eventi.RemoveAll(x => x.Tipo == e.Tipo && Json.Testo(x.Dettagli["giorno"]) == giorno);
            eventi.Add(e);
            Taglia();
            Salva();
        }
    }

    public IReadOnlyList<Evento> Prossimi(int quanti)
    {
        lock (blocco) return eventi.Take(quanti).ToList();
    }

    /// <summary>Toglie per id, non per posizione: nel frattempo una fotografia può aver cambiato la coda.</summary>
    public void Rimuovi(IEnumerable<string> ids)
    {
        var insieme = ids.ToHashSet(StringComparer.Ordinal);
        if (insieme.Count == 0) return;
        lock (blocco)
        {
            if (eventi.RemoveAll(x => insieme.Contains(x.Id)) > 0) Salva();
        }
    }

    /// <summary>
    /// Manda la coda a lotti con <paramref name="invia"/> (che restituisce lo stato HTTP, 0 = rete).
    /// Un lotto rifiutato per un evento storto (400/413/422) si rimanda un evento alla volta,
    /// così un evento guasto non blocca per sempre tutti gli altri; quello guasto si scarta.
    /// </summary>
    public async Task<EsitoCoda> InviaAsync(Func<IReadOnlyList<Evento>, Task<int>> invia, Action<Evento, int>? scartato = null)
    {
        while (true)
        {
            var lotto = Prossimi(Lotto);
            if (lotto.Count == 0) return EsitoCoda.Vuota;
            int stato = await invia(lotto).ConfigureAwait(false);
            if (Riuscito(stato))
            {
                Rimuovi(lotto.Select(e => e.Id));
                continue;
            }
            if (!RifiutoDefinitivo(stato)) return EsitoCoda.Interrotta;

            foreach (var e in lotto)
            {
                int s = await invia(new[] { e }).ConfigureAwait(false);
                if (Riuscito(s))
                {
                    Rimuovi(new[] { e.Id });
                }
                else if (RifiutoDefinitivo(s))
                {
                    scartato?.Invoke(e, s);
                    Rimuovi(new[] { e.Id });
                }
                else
                {
                    return EsitoCoda.Interrotta;
                }
            }
        }
    }

    public static bool Riuscito(int stato) => stato is >= 200 and < 300;

    /// <summary>Il server dice che l'evento stesso è sbagliato: rimandarlo non servirà mai.</summary>
    public static bool RifiutoDefinitivo(int stato) => stato is 400 or 413 or 422;

    private void Taglia()
    {
        // Settimane senza rete: si tengono i più recenti.
        if (eventi.Count > Massimo) eventi.RemoveRange(0, eventi.Count - Massimo);
    }

    private void Salva() => Archivio.ScriviJson(percorso, eventi);

    /// <summary>Una copia dei dettagli per chi vuole leggere la coda (test, diagnostica).</summary>
    public IReadOnlyList<JsonObject> Dettagli()
    {
        lock (blocco) return eventi.Select(e => (JsonObject)e.Dettagli.DeepClone()).ToList();
    }
}
