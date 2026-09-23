using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Pactum.Nucleo;

/// <summary>Cosa dice un giorno della striscia. Solo "verde" e "rosso" hanno un significato.</summary>
public enum Segnale { Mantenuta, FuoriRegola, NessunDato }

public sealed record GiornoPatto(string Data, Segnale Segnale);

/// <summary>La serie ricordata sul computer: quanti giorni di fila e l'ultimo giorno contato.</summary>
public sealed record SerieSalvata(string Fine, int Lunghezza);

/// <summary>Il file <c>serie.json</c>: la serie ricordata e il record, che non scende mai.</summary>
public sealed class MemoriaSerie
{
    [JsonPropertyName("fine")] public string? Fine { get; set; }
    [JsonPropertyName("lunghezza")] public int Lunghezza { get; set; }
    [JsonPropertyName("record")] public int Record { get; set; }
}

/// <summary>
/// Serie e record del figlio, porta di <c>Serie.kt</c> e di <c>serieDiGiorni</c>
/// (core-design, Segnale.kt). Si calcolano solo dalla striscia del server e non
/// viaggiano mai: nessun campo nel contratto, il genitore non li vede.
/// </summary>
public static class Serie
{
    public static Segnale SegnaleDaStato(string? stato) => stato switch
    {
        "verde" => Segnale.Mantenuta,
        "rosso" => Segnale.FuoriRegola,
        _ => Segnale.NessunDato,
    };

    /// <summary>La <c>striscia</c> di GET /api/patto (dal più vecchio a oggi).</summary>
    public static List<GiornoPatto> DaStriscia(JsonNode? striscia)
    {
        var giorni = new List<GiornoPatto>();
        if (striscia is not JsonArray a) return giorni;
        foreach (var voce in a)
        {
            var data = Json.Testo(voce?["data"]);
            if (data == null) continue;
            giorni.Add(new GiornoPatto(data, SegnaleDaStato(Json.Testo(voce?["stato"]))));
        }
        return giorni;
    }

    /// <summary>
    /// Giorni mantenuti di fila contati all'indietro dal più recente; se oggi non
    /// ha ancora dati si parte da ieri.
    /// </summary>
    public static int SerieDiGiorni(IReadOnlyList<GiornoPatto> giorni)
    {
        var contati = GiorniContati(giorni);
        int n = 0;
        for (int i = contati.Count - 1; i >= 0 && contati[i].Segnale == Segnale.Mantenuta; i--) n++;
        return n;
    }

    /// <summary>La serie da mostrare: la striscia allungata dalla memoria quando ci si attacca.</summary>
    public static SerieSalvata? Calcola(IReadOnlyList<GiornoPatto> giorni, SerieSalvata? salvata)
    {
        var contati = GiorniContati(giorni);
        var ultimo = contati.Count > 0 ? Data(contati[^1].Data) : null;
        var fineRicordata = salvata != null ? Data(salvata.Fine) : null;
        if (salvata != null && ultimo != null && fineRicordata != null && fineRicordata > ultimo) return salvata;

        int corta = SerieDiGiorni(giorni);
        if (corta == 0) return null;
        var daSola = new SerieSalvata(contati[^1].Data, corta);
        if (salvata == null || ultimo == null || fineRicordata == null || corta < contati.Count) return daSola;

        var primo = Data(contati[0].Data);
        if (primo == null) return daSola;
        if (fineRicordata < primo.Value.AddDays(-1)) return daSola;
        int giorniNuovi = ultimo.Value.DayNumber - fineRicordata.Value.DayNumber;
        return new SerieSalvata(daSola.Fine, Math.Max(corta, salvata.Lunghezza + giorniNuovi));
    }

    /// <summary>
    /// La serie da ricordare dopo aver letto la striscia: un grigio dopo la fine
    /// ricordata non cancella la memoria (le fotografie possono ancora arrivare), un rosso sì.
    /// </summary>
    public static SerieSalvata? Memoria(IReadOnlyList<GiornoPatto> giorni, SerieSalvata? salvata)
    {
        var corrente = Calcola(giorni, salvata);
        if (salvata == null || corrente == salvata) return corrente;
        var contati = GiorniContati(giorni);
        var fineRicordata = Data(salvata.Fine);
        if (fineRicordata == null) return corrente;
        if (contati.Count == 0) return salvata;
        var primo = Data(contati[0].Data);
        if (primo == null) return salvata;
        if (fineRicordata < primo.Value.AddDays(-1)) return corrente;
        var dopo = contati.Where(g => Data(g.Data) is DateOnly d && d > fineRicordata).ToList();
        if (dopo.Any(g => g.Segnale == Segnale.FuoriRegola)) return corrente;
        if (dopo.Any(g => g.Segnale == Segnale.NessunDato)) return salvata;
        return corrente;
    }

    /// <summary>Il record non scende MAI.</summary>
    public static int Record(int precedente, int serie) => Math.Max(Math.Max(precedente, serie), 0);

    private static List<GiornoPatto> GiorniContati(IReadOnlyList<GiornoPatto> giorni) =>
        giorni.Count > 0 && giorni[^1].Segnale == Segnale.NessunDato ? giorni.Take(giorni.Count - 1).ToList() : giorni.ToList();

    private static DateOnly? Data(string testo) => Tempo.ProvaGiorno(testo, out var d) ? d : null;
}
