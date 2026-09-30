using System.Globalization;

namespace Pactum.Nucleo;

/// <summary>
/// Le parole degli avvisi del motore (il fumetto vicino all'orologio e, dalla 0.9, l'avviso a
/// tutto schermo), in un posto solo. Sono quelle dell'interfaccia (<c>pc/ui/testi.js</c>: durata,
/// "Tutto il computer", "48 min oltre") e dell'avviso del telefono: stessi fatti, stesse parole.
/// </summary>
public static class Testi
{
    /// <summary>La frase con cui ogni avviso ricorda che Pactum non blocca niente.</summary>
    public const string NessunBlocco = "Nessun blocco: è il tuo patto.";

    public const string TitoloOltre = "Oggi sei andato oltre";
    public const string TitoloFascia = "Hai usato il computer in una fascia che ti sei imposto";

    /// <summary>(v3.3) Il nome della regola sul totale (<c>app_o_categoria = "totale"</c>) di un computer.</summary>
    public const string TuttoIlComputer = "Tutto il computer";

    public const string HoCapito = "Ho capito";
    public const string ApriPactum = "Apri Pactum";

    /// <summary>
    /// Sotto i pulsanti dell'avviso: Windows non dà la tastiera a una finestra che si apre mentre si usa
    /// un altro programma (un gioco, per esempio) finché non ci si fa clic sopra.
    /// </summary>
    public const string TastiNonRispondono = "Se i tasti non rispondono, fai clic qui.";

    /// <summary>Il pezzo di <see cref="TastiNonRispondono"/> che si mostra come un collegamento.</summary>
    public const string FaiClicQui = "fai clic qui";

    /// <summary>"48 min", "1 h", "1 h 15 min": come <c>durata()</c> di testi.js.</summary>
    public static string Durata(long minuti)
    {
        long m = Math.Max(0, minuti);
        if (m < 60) return m.ToString(CultureInfo.InvariantCulture) + " min";
        var ore = (m / 60).ToString(CultureInfo.InvariantCulture);
        if (m % 60 == 0) return ore + " h";
        return ore + " h " + (m % 60).ToString(CultureInfo.InvariantCulture) + " min";
    }

    /// <summary>
    /// Il bersaglio di un limite detto per nome: "Tutto il computer", "Social", "youtube.com",
    /// "Minecraft" (il nome visto oggi per quel programma, altrimenti il file senza ".exe").
    /// </summary>
    public static string NomeBersaglio(string? chiave, Giornata? oggi)
    {
        if (string.IsNullOrWhiteSpace(chiave)) return "?";
        var k = chiave.Trim().ToLowerInvariant();
        if (k == Bersagli.Totale) return TuttoIlComputer;
        if (k.StartsWith(Categorie.Prefisso, StringComparison.Ordinal))
        {
            var c = k[Categorie.Prefisso.Length..];
            return c.Length > 0 ? char.ToUpper(c[0], CultureInfo.InvariantCulture) + c[1..] : c;
        }
        if (k.StartsWith(Programma.PrefissoSito, StringComparison.Ordinal)) return k[Programma.PrefissoSito.Length..];
        if (oggi != null && oggi.Programmi.TryGetValue(k, out var voce) && !string.IsNullOrWhiteSpace(voce.Nome)) return voce.Nome;
        return Programma.NomeDiRipiego(k);
    }

    // ---------- Il fumetto vicino all'orologio (le frasi di sempre) ----------

    public static (string Titolo, string Testo) Fumetto(Avviso a) => a.Fascia
        ? (TitoloFascia, $"Hai usato il computer {a.MinutiOltre} min in una fascia che ti sei imposto ({a.Dalle}–{a.Alle}). {NessunBlocco}")
        : (TitoloOltre, $"{a.Nome}: oggi sei andato {a.MinutiOltre} min oltre il limite che ti sei dato ({a.LimiteEfficace} min). {NessunBlocco}");

    // ---------- L'avviso a tutto schermo (le frasi dell'avviso del telefono) ----------

    /// <summary>Il titolo: quello dei fumetti. Se sono tutte fasce, quello delle fasce.</summary>
    public static string TitoloAvviso(IReadOnlyCollection<Avviso> avvisi) =>
        avvisi.Count > 0 && avvisi.All(a => a.Fascia) ? TitoloFascia : TitoloOltre;

    /// <summary>"2 h 10 min su 2 h": quanto hai usato oggi sul limite di oggi, come nella riga di Oggi.</summary>
    public static string MinutiSuLimite(long usati, long limite) => Durata(usati) + " su " + Durata(limite);

    /// <summary>"10 min oltre", come l'etichetta della riga di Oggi.</summary>
    public static string Oltre(long minuti) => Durata(minuti) + " oltre";

    /// <summary>Il limite che ti sei dato (<c>minuti_al_giorno</c>), con i bonus di oggi se ci sono.</summary>
    public static string LimiteDato(long limite, long bonus) => bonus > 0
        ? $"Il limite che ti sei dato: {Durata(limite)} al giorno, più {Durata(bonus)} di bonus oggi."
        : $"Il limite che ti sei dato: {Durata(limite)} al giorno.";

    /// <summary>Il nome di una fascia oraria del computer: "Niente computer dalle 22:00 alle 07:00".</summary>
    public static string FasciaRegola(string dalle, string alle) => $"Niente computer dalle {dalle} alle {alle}";

    public static string FasciaUso(long minuti) => $"Oggi {Durata(minuti)} di computer dentro questa fascia.";

    /// <summary>
    /// Tutto l'avviso in righe di testo semplice: per chi usa un lettore di schermo e per le prove
    /// (<c>--prova-avvisi</c>). Stesse parole della finestra, nello stesso ordine.
    /// </summary>
    public static string TestoAvviso(IReadOnlyCollection<Avviso> avvisi)
    {
        var righe = new List<string> { TitoloAvviso(avvisi) };
        foreach (var a in avvisi)
        {
            if (a.Fascia)
            {
                righe.Add($"{a.Nome}. {FasciaUso(a.MinutiUsati)}");
                continue;
            }
            var riga = $"{a.Nome}: {MinutiSuLimite(a.MinutiUsati, a.LimiteEfficace ?? 0)}, {Oltre(a.MinutiOltre)}.";
            if (a.Limite is int limite) riga += " " + LimiteDato(limite, a.Bonus);
            righe.Add(riga);
        }
        righe.Add(NessunBlocco);
        return string.Join(Environment.NewLine, righe);
    }
}
