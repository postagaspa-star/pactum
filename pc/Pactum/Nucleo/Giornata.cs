using System.Text.Json.Serialization;

namespace Pactum.Nucleo;

/// <summary>
/// La misura di un giorno locale del computer: un file per giorno
/// (<c>giorni/AAAA-MM-GG.json</c>). Solo numeri, chiavi <c>exe:</c>, domini
/// registrabili e categorie: nessun titolo di finestra, nessun indirizzo.
/// </summary>
public sealed class Giornata
{
    [JsonPropertyName("giorno")] public string Giorno { get; set; } = "";

    /// <summary>Tempo attivo al computer, in millisecondi.</summary>
    [JsonPropertyName("ms_attivi")] public long MsAttivi { get; set; }

    [JsonPropertyName("programmi")] public Dictionary<string, VoceProgramma> Programmi { get; set; } = new();

    [JsonPropertyName("siti")] public Dictionary<string, VoceSito> Siti { get; set; } = new();

    /// <summary>Millisecondi per chiave <c>categoria:*</c>, già con il tempo nel browser nella categoria del sito.</summary>
    [JsonPropertyName("categorie")] public Dictionary<string, long> MsPerCategoria { get; set; } = new();

    /// <summary>Millisecondi attivi per minuto (chiave = minuti dall'epoca UTC): servono alle fasce orarie.</summary>
    [JsonPropertyName("per_minuto")] public Dictionary<long, long> MsPerMinuto { get; set; } = new();

    /// <summary>Per un pezzo del giorno i siti non si sono potuti leggere (nel contratto: <c>dns_cifrato</c>).</summary>
    [JsonPropertyName("siti_non_leggibili")] public bool SitiNonLeggibili { get; set; }

    /// <summary>I browser già dichiarati non leggibili oggi (un evento al giorno per browser).</summary>
    [JsonPropertyName("browser_non_leggibili")] public List<string> BrowserNonLeggibili { get; set; } = new();

    /// <summary>
    /// (0.18, contratto v4.0) Millisecondi passati nei programmi e nei siti della lista della Sessione Studio:
    /// non contano per limiti, categorie, totale e fasce. Vanno nella fotografia come <c>sessioni_minuti</c>.
    /// </summary>
    [JsonPropertyName("ms_studio")] public long MsStudio { get; set; }

    /// <summary>Cresce a ogni cambiamento: se è diversa da <see cref="RevisioneFotografata"/> serve una fotografia nuova.</summary>
    [JsonPropertyName("revisione")] public long Revisione { get; set; }

    [JsonPropertyName("revisione_fotografata")] public long RevisioneFotografata { get; set; }

    public static Giornata Nuova(string giorno) => new() { Giorno = giorno };

    /// <summary>
    /// Una copia che non cambia più (0.10): la lettura dell'uso su cui si decide uno sforamento resta
    /// la stessa mentre si rilegge il patto dal server e il giro di misura va avanti.
    /// </summary>
    public Giornata Copia() => new()
    {
        Giorno = Giorno,
        MsAttivi = MsAttivi,
        MsStudio = MsStudio,
        Programmi = Programmi.ToDictionary(p => p.Key, p => new VoceProgramma { Nome = p.Value.Nome, Ms = p.Value.Ms, Percorso = p.Value.Percorso }),
        Siti = Siti.ToDictionary(s => s.Key, s => new VoceSito { Ms = s.Value.Ms, Visite = s.Value.Visite }),
        MsPerCategoria = new Dictionary<string, long>(MsPerCategoria),
        MsPerMinuto = new Dictionary<long, long>(MsPerMinuto),
        SitiNonLeggibili = SitiNonLeggibili,
        BrowserNonLeggibili = new List<string>(BrowserNonLeggibili),
        Revisione = Revisione,
        RevisioneFotografata = RevisioneFotografata,
    };

    public static long Minuti(long ms) => ms / 60_000;

    [JsonIgnore]
    public long MinutiTotali => Minuti(MsAttivi);

    /// <summary>(0.18) I minuti di Studio del giorno (<c>sessioni_minuti</c>), da 0 a 1440.</summary>
    [JsonIgnore]
    public long MinutiStudio => Math.Clamp(Minuti(MsStudio), 0, 1440);

    /// <summary>
    /// I minuti di oggi su una chiave di regola <c>app_o_categoria</c> (match esatto, contratto v3):
    /// <c>exe:…</c>, <c>sito:…</c>, <c>categoria:…</c> o (v3.3) <c>totale</c>, tutto il computer.
    /// Stesso conto per lo sforamento e per "48 min su 1 h".
    /// </summary>
    public long MinutiDi(string chiave)
    {
        var k = chiave.Trim().ToLowerInvariant();
        // Tutto il computer: lo stesso totale_minuti che parte nella fotografia uso_giornaliero.
        if (k == Bersagli.Totale) return MinutiTotali;
        if (k.StartsWith(Categorie.Prefisso, StringComparison.Ordinal))
            return Minuti(MsPerCategoria.TryGetValue(k, out var c) ? c : 0);
        if (k.StartsWith(Programma.PrefissoSito, StringComparison.Ordinal))
        {
            var dominio = Domini.DominioDellaPagina(k[Programma.PrefissoSito.Length..]) ?? k[Programma.PrefissoSito.Length..];
            return Minuti(Siti.TryGetValue(dominio, out var s) ? s.Ms : 0);
        }
        if (k.StartsWith(Programma.Prefisso, StringComparison.Ordinal))
            return Minuti(Programmi.TryGetValue(k, out var p) ? p.Ms : 0);
        return 0;
    }

    /// <summary>I minuti attivi in [inizio, fine) (millisecondi UTC), a grana di minuto.</summary>
    public long MinutiNellIntervallo(long inizioMs, long fineMs)
    {
        if (fineMs <= inizioMs) return 0;
        long primo = inizioMs / 60_000;
        long ultimo = (fineMs - 1) / 60_000;
        long totale = 0;
        foreach (var (minuto, ms) in MsPerMinuto)
        {
            if (minuto >= primo && minuto <= ultimo) totale += ms;
        }
        return Minuti(totale);
    }
}

public sealed class VoceProgramma
{
    [JsonPropertyName("nome")] public string Nome { get; set; } = "";
    [JsonPropertyName("ms")] public long Ms { get; set; }

    /// <summary>
    /// (0.18) L'ultimo percorso del file visto girare, solo sul computer (mai nelle fotografie): serve a leggere la
    /// firma Authenticode quando si propone la lista dello Studio dai programmi visti negli ultimi 30 giorni.
    /// </summary>
    [JsonPropertyName("percorso")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Percorso { get; set; }
}

public sealed class VoceSito
{
    [JsonPropertyName("ms")] public long Ms { get; set; }
    [JsonPropertyName("visite")] public int Visite { get; set; }
}
