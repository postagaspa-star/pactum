using System.Globalization;
using System.Text;
using System.Text.Json.Serialization;

namespace Pactum.Nucleo;

/// <summary>
/// (0.23) Le categorie del figlio sul computer (richiesta di Andrea, 10/10: «attualmente viene messo tutto in
/// "Altro", e così non si capisce nulla»). Il figlio sposta i programmi che stanno in «Altre app» in una delle
/// categorie del contratto o in una sua, che crea dando un nome. Decisioni di Andrea:
/// <list type="number">
/// <item>si spostano solo i programmi che Pactum non riconosce già (quelli delle nostre liste restano dove sono,
/// così un limite sui Giochi non si aggira), e mai i browser (il loro tempo va nella categoria del sito);</item>
/// <item>i genitori vedono le categorie col nome scelto (sono chiavi <c>categoria:&lt;nome&gt;</c> in
/// <c>uso_categorie</c>, come le altre);</item>
/// <item>sulle categorie del figlio si possono mettere limiti, come sulle altre.</item>
/// </list>
/// In più, perché nessun limite si aggiri: un programma che sta in una categoria con un limite non si sposta, e
/// una categoria con un limite non si elimina. Il file sta solo sul computer (<c>categorie.json</c>).
/// </summary>
public sealed class CategorieMie
{
    /// <summary>I nomi delle categorie create, in minuscolo, nell'ordine di creazione.</summary>
    [JsonPropertyName("categorie")] public List<string> Categorie { get; set; } = new();

    /// <summary>La categoria scelta per un programma: chiave <c>exe:…</c> → nome (una del contratto o una creata).</summary>
    [JsonPropertyName("programmi")] public Dictionary<string, string> Programmi { get; set; } = new();
}

/// <summary>(0.23) Le regole delle categorie del figlio, logica pura (provata in CategorieMieTest).</summary>
public static class RegoleCategorie
{
    /// <summary>Quanto può essere lungo il nome di una categoria (caratteri visibili).</summary>
    public const int LunghezzaMassima = 30;

    /// <summary>Quante categorie si possono creare.</summary>
    public const int CategorieMassime = 30;

    /// <summary>I nomi che non si possono usare: le categorie del contratto, i loro nomi a schermo, il totale.</summary>
    private static readonly HashSet<string> Riservati = new(StringComparer.Ordinal)
    {
        Nucleo.Categorie.Social, Nucleo.Categorie.Giochi, Nucleo.Categorie.Video, Nucleo.Categorie.Musica,
        Nucleo.Categorie.Altro, "altre app", Bersagli.Totale,
    };

    /// <summary>Perché un programma non si può spostare.</summary>
    public enum Fermo
    {
        /// <summary>Si può spostare.</summary>
        Nessuno,

        /// <summary>Pactum lo riconosce già: sta nella categoria delle nostre liste.</summary>
        Riconosciuto,

        /// <summary>È un browser: il suo tempo va nella categoria del sito che si guarda.</summary>
        Browser,

        /// <summary>La sua categoria di adesso ha un limite: spostarlo aggirerebbe il limite.</summary>
        ConLimite,
    }

    /// <summary>
    /// Il nome di una categoria come si salva (minuscolo, spazi singoli, forma NFC), o null se non va bene:
    /// vuoto, più lungo di <see cref="LunghezzaMassima"/>, con caratteri invisibili o di controllo, con i due
    /// punti (separano la chiave), o uno dei nomi riservati.
    /// </summary>
    public static string? Nome(string? scritto)
    {
        if (scritto == null) return null;
        var t = scritto.Normalize(NormalizationForm.FormC).Trim();
        if (t.Length == 0) return null;
        var sb = new StringBuilder();
        bool spazio = false;
        foreach (var r in t.EnumerateRunes())
        {
            var cat = Rune.GetUnicodeCategory(r);
            if (cat is UnicodeCategory.Control or UnicodeCategory.Format or UnicodeCategory.LineSeparator
                or UnicodeCategory.ParagraphSeparator or UnicodeCategory.Surrogate or UnicodeCategory.PrivateUse
                or UnicodeCategory.OtherNotAssigned)
            {
                return null;
            }
            if (Rune.IsWhiteSpace(r))
            {
                spazio = true;
                continue;
            }
            if (r.Value == ':') return null;
            if (spazio && sb.Length > 0) sb.Append(' ');
            spazio = false;
            sb.Append(Rune.ToLowerInvariant(r).ToString());
        }
        var nome = sb.ToString();
        if (nome.Length == 0) return null;
        if (nome.EnumerateRunes().Count() > LunghezzaMassima) return null;
        if (Riservati.Contains(nome)) return null;
        return nome;
    }

    /// <summary>Il nome a schermo: la prima lettera maiuscola ("scuola e compiti" → "Scuola e compiti").</summary>
    public static string Etichetta(string nome) =>
        nome.Length == 0 ? nome : char.ToUpperInvariant(nome[0]) + nome[1..];

    /// <summary>
    /// Si può spostare il programma <paramref name="chiaveProgramma"/> (<c>exe:…</c>), che adesso sta in
    /// <paramref name="categoriaAttuale"/>? <paramref name="chiaviConLimite"/> = le chiavi <c>categoria:…</c>
    /// (minuscole) che hanno un limite su questo computer.
    /// </summary>
    public static Fermo PercheFermo(string chiaveProgramma, string categoriaAttuale, ISet<string> chiaviConLimite, Func<string, bool> èBrowser)
    {
        var exe = SenzaPrefisso(chiaveProgramma);
        if (èBrowser(exe)) return Fermo.Browser;
        if (Nucleo.Categorie.ÈInUnaLista(exe)) return Fermo.Riconosciuto;
        if (chiaviConLimite.Contains(Nucleo.Categorie.Chiave(categoriaAttuale).ToLowerInvariant())) return Fermo.ConLimite;
        return Fermo.Nessuno;
    }

    /// <summary>La mappa da dare a <see cref="Nucleo.Categorie.DiProgramma"/>: nome del file → categoria.</summary>
    public static Dictionary<string, string> Mappa(CategorieMie mie)
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var (programma, categoria) in mie.Programmi)
        {
            var exe = SenzaPrefisso(programma);
            if (exe.Length == 0 || Nucleo.Categorie.ÈInUnaLista(exe)) continue;
            d[exe] = categoria;
        }
        return d;
    }

    private static string SenzaPrefisso(string chiave)
    {
        var t = chiave.Trim().ToLowerInvariant();
        return t.StartsWith(Programma.Prefisso, StringComparison.Ordinal) ? t[Programma.Prefisso.Length..] : t;
    }
}
