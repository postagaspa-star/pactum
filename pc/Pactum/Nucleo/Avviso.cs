namespace Pactum.Nucleo;

/// <summary>
/// (0.9) Quello che gli avvisi raccontano di uno sforamento appena segnalato: il nome già in
/// chiaro, il resto in numeri (le parole le mette <see cref="Testi"/>). Come <c>Avviso</c> del telefono.
/// </summary>
/// <param name="Nome">Limite di tempo: "Tutto il computer", "Minecraft", "youtube.com", "Social". Fascia: "Niente computer dalle 22:00 alle 07:00".</param>
/// <param name="MinutiUsati">Limite: i minuti di oggi. Fascia: i minuti di computer dentro la fascia.</param>
/// <param name="LimiteEfficace">Limite: il limite di oggi, bonus compresi. Null per le fasce.</param>
/// <param name="Limite">Limite: quello che ti sei dato (<c>minuti_al_giorno</c>), senza bonus. Null per le fasce.</param>
/// <param name="MinutiOltre">Limite: i minuti oltre il limite di oggi. Fascia: i minuti dentro la fascia.</param>
public sealed record Avviso(
    long RegolaId,
    string Tipo,
    string Nome,
    int MinutiUsati,
    int? LimiteEfficace,
    int? Limite,
    int MinutiOltre,
    string? Dalle = null,
    string? Alle = null)
{
    public bool Fascia => Tipo == TipiRegola.FasciaOraria;

    /// <summary>I minuti di bonus di oggi su questa regola.</summary>
    public int Bonus => LimiteEfficace is int e && Limite is int l ? Math.Max(0, e - l) : 0;

    /// <summary>L'avviso di uno sforamento. <paramref name="oggi"/> dà i nomi dei programmi visti oggi.</summary>
    public static Avviso Da(Sforamento s, Regola? regola, Giornata? oggi)
    {
        if (s.Tipo == TipiRegola.FasciaOraria)
        {
            var dalle = regola?.Stringa("dalle") ?? "?";
            var alle = regola?.Stringa("alle") ?? "?";
            return new Avviso(s.RegolaId, s.Tipo, Testi.FasciaRegola(dalle, alle), s.MinutiOltre, null, null, s.MinutiOltre, dalle, alle);
        }
        // Il valutatore dà i minuti OLTRE il limite di oggi: l'uso è la somma.
        int? efficace = s.LimiteEfficace;
        return new Avviso(
            s.RegolaId,
            s.Tipo,
            Testi.NomeBersaglio(regola?.Stringa("app_o_categoria"), oggi),
            (efficace ?? 0) + s.MinutiOltre,
            efficace,
            regola?.Intero("minuti_al_giorno"),
            s.MinutiOltre);
    }
}

/// <summary>
/// (0.9) Cosa fare degli sforamenti visti adesso, logica pura (la applica il motore col suo blocco).
/// Il dedup è quello di sempre (contratto: al massimo UNO sforamento per regola per giorno, col
/// giorno del computer per i limiti e il giorno di ancoraggio per le fasce): quello che è nuovo va
/// nel registro, nel fumetto e nell'avviso a tutto schermo; quello già segnalato non torna, né nel
/// fumetto né a tutto schermo. L'avviso a tutto schermo non ha regole sue: segue il fumetto.
/// </summary>
public static class Segnalazioni
{
    /// <param name="Nuovi">Gli sforamenti da mettere in coda per il server.</param>
    /// <param name="ATuttoSchermo">Gli avvisi da mostrare (fumetto e finestra a tutto schermo), uno per sforamento nuovo.</param>
    public sealed record Decisione(IReadOnlyList<Sforamento> Nuovi, IReadOnlyList<Avviso> ATuttoSchermo);

    /// <summary>
    /// Nuovo = mai segnalato per quella regola quel giorno (<see cref="RegistroSforamenti"/>), che qui
    /// viene aggiornato. Una volta segnalato, per quel giorno non torna: nemmeno se un bonus alza il
    /// limite e poi si va oltre anche quello, nemmeno dopo un riavvio (il registro sta su disco).
    /// </summary>
    public static Decisione Decidi(
        IEnumerable<Sforamento> trovati,
        string giornoComputer,
        RegistroSforamenti registro,
        IReadOnlyCollection<Regola> regole,
        Giornata? oggi)
    {
        var nuovi = registro.Nuovi(trovati, giornoComputer);
        var avvisi = nuovi.Select(s => Avviso.Da(s, regole.FirstOrDefault(r => r.Id == s.RegolaId), oggi)).ToList();
        return new Decisione(nuovi, avvisi);
    }
}
