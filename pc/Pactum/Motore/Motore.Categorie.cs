using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Motore;

/// <summary>
/// (0.23) Le categorie del figlio per i programmi (v. <see cref="CategorieMie"/>): lette all'avvio, cambiate
/// dall'interfaccia (<c>/locale/categorie</c>, <c>/locale/categorie-sposta</c>, <c>/locale/categorie-nuova</c>,
/// <c>/locale/categorie-elimina</c>). Uno spostamento vale subito anche per il tempo di oggi: i minuti di quel
/// programma passano dalla categoria di prima alla nuova, e la fotografia di oggi riparte col conto giusto.
/// I giorni passati restano come sono stati mandati.
/// </summary>
public sealed partial class Motore
{
    private readonly object categorieLock = new();
    private CategorieMie categorieMie = new();
    private IReadOnlyDictionary<string, string> mappaMie = new Dictionary<string, string>();

    /// <summary>La categoria di un programma per questo computer (con le scelte del figlio).</summary>
    private string CategoriaDi(string programma) => Categorie.DiProgramma(programma, mappaMie);

    /// <summary>Legge <c>categorie.json</c> e le mette in uso. Un file rovinato vale come nessuna categoria.</summary>
    private void CaricaCategorie()
    {
        lock (categorieLock)
        {
            categorieMie = Archivio.LeggiJson<CategorieMie>(percorsi.CategorieMie) ?? new CategorieMie();
            categorieMie.Categorie = categorieMie.Categorie
                .Select(RegoleCategorie.Nome).OfType<string>().Distinct(StringComparer.Ordinal)
                .Take(RegoleCategorie.CategorieMassime).ToList();
            // Solo mete che esistono: una categoria del contratto o una creata.
            categorieMie.Programmi = categorieMie.Programmi
                .Where(p => Categorie.Tutte.Contains(p.Value) || categorieMie.Categorie.Contains(p.Value))
                .GroupBy(p => p.Key.Trim().ToLowerInvariant(), StringComparer.Ordinal)
                .ToDictionary(g => g.Key, g => g.Last().Value, StringComparer.Ordinal);
            mappaMie = RegoleCategorie.Mappa(categorieMie);
        }
        lock (misura) contatore.Mie = mappaMie;
    }

    /// <summary>Le chiavi <c>categoria:…</c> (minuscole) con un limite di tempo su questo computer.</summary>
    private HashSet<string> ChiaviConLimite()
    {
        var chiavi = new HashSet<string>(StringComparer.Ordinal);
        var p = patto;
        if (p == null) return chiavi;
        foreach (var r in p.RegoleDelComputer(IdDispositivo))
        {
            if (!r.Attiva || r.Tipo != TipiRegola.LimiteTempo) continue;
            var chiave = r.Stringa("app_o_categoria")?.Trim().ToLowerInvariant();
            if (chiave != null && chiave.StartsWith(Categorie.Prefisso, StringComparison.Ordinal)) chiavi.Add(chiave);
        }
        return chiavi;
    }

    /// <summary>GET /locale/categorie: le categorie (del contratto e del figlio) e i programmi degli ultimi 30 giorni.</summary>
    public JsonObject CategorieDelFiglio()
    {
        var giorni = GiorniRecenti();
        var conLimite = ChiaviConLimite();
        lock (categorieLock)
        {
            var programmi = new Dictionary<string, (string Nome, long Ms)>(StringComparer.Ordinal);
            foreach (var g in giorni.OrderBy(g => g.Giorno, StringComparer.Ordinal))
            {
                foreach (var (chiave, voce) in g.Programmi)
                {
                    var prima = programmi.GetValueOrDefault(chiave);
                    programmi[chiave] = (voce.Nome, prima.Ms + voce.Ms);
                }
            }
            var conteggi = new Dictionary<string, int>(StringComparer.Ordinal);
            var righe = new JsonArray();
            foreach (var (chiave, (nome, ms)) in programmi.OrderByDescending(p => p.Value.Ms).ThenBy(p => p.Key, StringComparer.Ordinal))
            {
                if (Giornata.Minuti(ms) < 1) continue;
                var categoria = CategoriaDi(chiave);
                conteggi[categoria] = conteggi.GetValueOrDefault(categoria) + 1;
                var fermo = RegoleCategorie.PercheFermo(chiave, categoria, conLimite, CoperturaStudio.ÈBrowser);
                righe.Add(new JsonObject
                {
                    ["chiave"] = chiave,
                    ["nome"] = nome,
                    ["categoria"] = categoria,
                    ["minuti"] = Giornata.Minuti(ms),
                    ["spostabile"] = fermo == RegoleCategorie.Fermo.Nessuno,
                    ["fermo"] = fermo switch
                    {
                        RegoleCategorie.Fermo.Riconosciuto => "riconosciuto",
                        RegoleCategorie.Fermo.Browser => "browser",
                        RegoleCategorie.Fermo.ConLimite => "con_limite",
                        _ => null,
                    },
                });
            }
            var fisse = new JsonArray(Categorie.Tutte.Select(c => (JsonNode)new JsonObject
            {
                ["nome"] = c,
                ["chiave"] = Categorie.Chiave(c),
                ["con_limite"] = conLimite.Contains(Categorie.Chiave(c)),
            }).ToArray());
            var mie = new JsonArray(categorieMie.Categorie.Select(c => (JsonNode)new JsonObject
            {
                ["nome"] = c,
                ["etichetta"] = RegoleCategorie.Etichetta(c),
                ["chiave"] = Categorie.Chiave(c),
                ["programmi"] = conteggi.GetValueOrDefault(c),
                ["con_limite"] = conLimite.Contains(Categorie.Chiave(c)),
            }).ToArray());
            return new JsonObject { ["fisse"] = fisse, ["mie"] = mie, ["programmi"] = righe };
        }
    }

    /// <summary>
    /// POST /locale/categorie-sposta <c>{ "programma": "exe:…", "categoria": "&lt;nome&gt;" }</c>: il programma va
    /// in quella categoria; un nome nuovo la crea. "altro" lo riporta in «Altre app».
    /// </summary>
    public JsonObject SpostaProgramma(string? programma, string? categoria)
    {
        var chiave = programma?.Trim().ToLowerInvariant();
        if (string.IsNullOrEmpty(chiave) || !chiave.StartsWith(Programma.Prefisso, StringComparison.Ordinal) || chiave.Length <= Programma.Prefisso.Length)
        {
            return Errore("programma_non_valido");
        }
        var meta = categoria?.Trim().ToLowerInvariant() is string m && (Categorie.Tutte.Contains(m))
            ? m
            : RegoleCategorie.Nome(categoria);
        if (meta == null) return Errore("nome_non_valido");
        var conLimite = ChiaviConLimite();
        string prima;
        lock (categorieLock)
        {
            prima = CategoriaDi(chiave);
            var fermo = RegoleCategorie.PercheFermo(chiave, prima, conLimite, CoperturaStudio.ÈBrowser);
            if (fermo != RegoleCategorie.Fermo.Nessuno) return Errore(fermo == RegoleCategorie.Fermo.ConLimite ? "con_limite" : "non_spostabile");
            if (!Categorie.Tutte.Contains(meta) && !categorieMie.Categorie.Contains(meta))
            {
                if (categorieMie.Categorie.Count >= RegoleCategorie.CategorieMassime) return Errore("troppe_categorie");
                categorieMie.Categorie.Add(meta);
            }
            if (meta == Categorie.Altro) categorieMie.Programmi.Remove(chiave);
            else categorieMie.Programmi[chiave] = meta;
            SalvaCategorie();
        }
        SpostaTempoDiOggi(chiave, prima, meta);
        Log.Info("categoria di un programma cambiata");
        return new JsonObject { ["ok"] = true, ["categorie"] = CategorieDelFiglio() };
    }

    /// <summary>POST /locale/categorie-nuova <c>{ "nome" }</c>: una categoria vuota (per esempio per darle subito un limite).</summary>
    public JsonObject NuovaCategoria(string? nome)
    {
        var n = RegoleCategorie.Nome(nome);
        if (n == null) return Errore("nome_non_valido");
        lock (categorieLock)
        {
            if (!categorieMie.Categorie.Contains(n))
            {
                if (categorieMie.Categorie.Count >= RegoleCategorie.CategorieMassime) return Errore("troppe_categorie");
                categorieMie.Categorie.Add(n);
                SalvaCategorie();
            }
        }
        return new JsonObject { ["ok"] = true, ["nome"] = n, ["categorie"] = CategorieDelFiglio() };
    }

    /// <summary>
    /// POST /locale/categorie-elimina <c>{ "nome" }</c>: via la categoria; i suoi programmi tornano in «Altre app».
    /// Una categoria con un limite non si elimina (il limite si aggirerebbe).
    /// </summary>
    public JsonObject EliminaCategoria(string? nome)
    {
        var n = RegoleCategorie.Nome(nome);
        if (n == null) return Errore("nome_non_valido");
        if (ChiaviConLimite().Contains(Categorie.Chiave(n))) return Errore("con_limite");
        List<string> tornano;
        lock (categorieLock)
        {
            if (!categorieMie.Categorie.Remove(n)) return Errore("non_trovata");
            tornano = categorieMie.Programmi.Where(p => p.Value == n).Select(p => p.Key).ToList();
            foreach (var p in tornano) categorieMie.Programmi.Remove(p);
            SalvaCategorie();
        }
        foreach (var p in tornano) SpostaTempoDiOggi(p, n, Categorie.Altro);
        return new JsonObject { ["ok"] = true, ["categorie"] = CategorieDelFiglio() };
    }

    private void SalvaCategorie()
    {
        Archivio.ScriviJson(percorsi.CategorieMie, categorieMie);
        mappaMie = RegoleCategorie.Mappa(categorieMie);
        lock (misura) contatore.Mie = mappaMie;
    }

    /// <summary>
    /// I minuti di oggi del programma passano dalla categoria di prima alla nuova. I programmi che si spostano
    /// non sono browser: tutto il loro tempo è andato nella categoria del programma.
    /// </summary>
    private void SpostaTempoDiOggi(string programma, string da, string a)
    {
        if (da == a) return;
        lock (misura)
        {
            var g = contatore.Oggi;
            if (!g.Programmi.TryGetValue(programma, out var voce) || voce.Ms <= 0) return;
            var chiaveDa = Categorie.Chiave(da);
            var chiaveA = Categorie.Chiave(a);
            long ms = Math.Min(voce.Ms, g.MsPerCategoria.GetValueOrDefault(chiaveDa));
            if (ms <= 0) return;
            g.MsPerCategoria[chiaveDa] -= ms;
            if (g.MsPerCategoria[chiaveDa] <= 0) g.MsPerCategoria.Remove(chiaveDa);
            g.MsPerCategoria[chiaveA] = g.MsPerCategoria.GetValueOrDefault(chiaveA) + ms;
            g.Revisione++;
            SalvaGiorno(g);
        }
    }
}
