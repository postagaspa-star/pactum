using System.Globalization;
using System.Text.Json.Nodes;

namespace Pactum.Nucleo;

/// <summary>
/// Uno sforamento rilevato sul computer (evento <c>sforamento</c>). <see cref="LimiteEfficace"/>
/// solo per limite_tempo; <see cref="GiornoAncora"/> solo per le fasce: è il giorno in cui
/// l'occorrenza parte, e fa da chiave di deduplica.
/// </summary>
public sealed record Sforamento(long RegolaId, string Tipo, int? LimiteEfficace, int MinutiOltre, string? GiornoAncora = null);

public sealed record IntervalloProibito(string GiornoAncora, long Inizio, long Fine);

/// <summary>Una fascia oraria vista adesso, per <c>GET /locale/oggi</c>.</summary>
public sealed record StatoFascia(bool AttivaOra, string? ProssimoInizio, string Fine);

/// <summary>
/// Il valutatore locale, porta di <c>Valutatore.kt</c> del telefono: logica pura,
/// l'uso arriva da due funzioni (per chiave e per intervallo). Non blocca niente
/// e non deduplica: la deduplica la fa <see cref="RegistroSforamenti"/>.
/// </summary>
public static class Valutatore
{
    private static readonly string[] Giorni = { "lun", "mar", "mer", "gio", "ven", "sab", "dom" };

    /// <summary>Sotto il minuto d'uso in una fascia = rumore di misura, non uno sforamento.</summary>
    public const long TolleranzaFasciaMin = 1;

    public static string EtichettaGiorno(DateOnly data) => Giorni[((int)data.DayOfWeek + 6) % 7];

    public static List<Sforamento> Valuta(
        IEnumerable<Regola> regole,
        IReadOnlyDictionary<string, int> bonusOggiPerRegola,
        Func<string, long> minutiChiave,
        Func<long, long, long> minutiIntervallo,
        long adessoMs,
        TimeZoneInfo zona)
    {
        var risultati = new List<Sforamento>();
        foreach (var regola in regole)
        {
            if (!regola.Attiva) continue;
            switch (regola.Tipo)
            {
                case TipiRegola.LimiteTempo:
                    var s = ValutaLimite(regola, bonusOggiPerRegola, minutiChiave);
                    if (s != null) risultati.Add(s);
                    break;
                case TipiRegola.FasciaOraria:
                    risultati.AddRange(ValutaFascia(regola, minutiIntervallo, adessoMs, zona));
                    break;
            }
        }
        return risultati;
    }

    /// <summary>Il limite di oggi: <c>minuti_al_giorno</c> + i bonus concessi oggi su QUELLA regola.</summary>
    public static int? LimiteEfficace(Regola regola, IReadOnlyDictionary<string, int> bonusOggiPerRegola)
    {
        if (regola.Tipo != TipiRegola.LimiteTempo) return null;
        var limite = regola.Intero("minuti_al_giorno");
        if (limite == null) return null;
        return limite.Value + bonusOggiPerRegola.GetValueOrDefault(regola.Id.ToString(CultureInfo.InvariantCulture));
    }

    /// <summary>Il <c>giorno</c> dello sforamento: quello del computer, o l'ancoraggio della fascia.</summary>
    public static string GiornoDelloSforamento(Sforamento s, string giornoComputer) => s.GiornoAncora ?? giornoComputer;

    /// <summary>I dettagli dell'evento <c>sforamento</c> (contratto, POST /api/eventi, v2.4).</summary>
    public static JsonObject DettagliSforamento(Sforamento s, string giornoComputer)
    {
        var d = new JsonObject { ["regola_id"] = s.RegolaId };
        if (s.LimiteEfficace != null) d["limite_efficace"] = s.LimiteEfficace.Value;
        d["minuti_oltre"] = s.MinutiOltre;
        d["giorno"] = GiornoDelloSforamento(s, giornoComputer);
        return d;
    }

    private static Sforamento? ValutaLimite(Regola regola, IReadOnlyDictionary<string, int> bonus, Func<string, long> minutiChiave)
    {
        var chiave = regola.Stringa("app_o_categoria");
        if (chiave == null) return null;
        var limite = LimiteEfficace(regola, bonus);
        if (limite == null) return null;
        long uso = minutiChiave(chiave);
        if (uso <= limite.Value) return null;
        return new Sforamento(regola.Id, regola.Tipo, limite.Value, (int)(uso - limite.Value));
    }

    /// <summary>
    /// Oggi una fascia può avere due parti (la coda mattutina di quella di ieri e la
    /// testa serale di quella di oggi): sono due occorrenze, raggruppate per giorno di
    /// ancoraggio; ognuna dà al più uno sforamento.
    /// </summary>
    private static IEnumerable<Sforamento> ValutaFascia(Regola regola, Func<long, long, long> minutiIntervallo, long adessoMs, TimeZoneInfo zona)
    {
        var dalle = regola.Ora("dalle");
        var alle = regola.Ora("alle");
        var giorni = regola.Stringhe("giorni");
        if (dalle == null || alle == null || giorni.Count == 0) yield break;

        foreach (var gruppo in IntervalliProibitiOggi(dalle.Value, alle.Value, giorni, adessoMs, zona).GroupBy(i => i.GiornoAncora))
        {
            long uso = gruppo.Sum(i => minutiIntervallo(i.Inizio, i.Fine));
            if (uso < TolleranzaFasciaMin) continue;
            yield return new Sforamento(regola.Id, regola.Tipo, null, (int)uso, gruppo.Key);
        }
    }

    /// <summary>
    /// Gli intervalli proibiti di OGGI già cominciati, tagliati a [inizio di oggi, adesso],
    /// ciascuno col giorno di ancoraggio. La parte serale di una fascia che scavalca la
    /// mezzanotte appartiene al giorno che la fa partire, la mattutina al giorno prima.
    /// </summary>
    public static List<IntervalloProibito> IntervalliProibitiOggi(TimeOnly dalle, TimeOnly alle, IReadOnlyCollection<string> giorni, long adessoMs, TimeZoneInfo zona)
    {
        var oggi = Tempo.DataDi(adessoMs, zona);
        long inizioOggi = Tempo.InizioGiorno(oggi, zona);
        var risultati = new List<IntervalloProibito>();
        foreach (var ancora in new[] { oggi.AddDays(-1), oggi })
        {
            if (!giorni.Contains(EtichettaGiorno(ancora))) continue;
            var (inizioMs, fineMs) = Occorrenza(ancora, dalle, alle, zona);
            long s = Math.Max(inizioMs, inizioOggi);
            long e = Math.Min(fineMs, adessoMs);
            if (e > s) risultati.Add(new IntervalloProibito(Tempo.Testo(ancora), s, e));
        }
        return risultati;
    }

    /// <summary>
    /// Dove sta una fascia adesso: se è in corso, quando riparte e quando finisce.
    /// Stessa geometria di <see cref="IntervalliProibitiOggi"/>. Null se la regola non è una fascia leggibile.
    /// </summary>
    public static StatoFascia? Stato(Regola regola, long adessoMs, TimeZoneInfo zona)
    {
        if (regola.Tipo != TipiRegola.FasciaOraria) return null;
        var dalle = regola.Ora("dalle");
        var alle = regola.Ora("alle");
        if (dalle == null || alle == null) return null;
        var giorni = regola.Stringhe("giorni");
        var oggi = Tempo.DataDi(adessoMs, zona);

        bool attiva = false;
        foreach (var ancora in new[] { oggi.AddDays(-1), oggi })
        {
            if (!giorni.Contains(EtichettaGiorno(ancora))) continue;
            var (inizio, fine) = Occorrenza(ancora, dalle.Value, alle.Value, zona);
            if (adessoMs >= inizio && adessoMs < fine) attiva = true;
        }

        string? prossimo = null;
        for (int d = 0; d <= 7 && prossimo == null; d++)
        {
            var giorno = oggi.AddDays(d);
            if (!giorni.Contains(EtichettaGiorno(giorno))) continue;
            if (Occorrenza(giorno, dalle.Value, alle.Value, zona).Inizio > adessoMs) prossimo = Testo(dalle.Value);
        }
        return new StatoFascia(attiva, prossimo, Testo(alle.Value));
    }

    private static (long Inizio, long Fine) Occorrenza(DateOnly ancora, TimeOnly dalle, TimeOnly alle, TimeZoneInfo zona)
    {
        long inizio = Tempo.UtcMsDi(ancora, dalle, zona);
        long fine = alle > dalle ? Tempo.UtcMsDi(ancora, alle, zona) : Tempo.UtcMsDi(ancora.AddDays(1), alle, zona);
        return (inizio, fine);
    }

    private static string Testo(TimeOnly ora) => ora.ToString("HH:mm", CultureInfo.InvariantCulture);
}

/// <summary>
/// Massimo UNO sforamento per regola per giorno (per le fasce, per giorno di
/// ancoraggio): la memoria di quelli già segnalati. Si salva su disco da chi la usa.
/// </summary>
public sealed class RegistroSforamenti
{
    /// <summary>giorno → id delle regole già segnalate quel giorno.</summary>
    public Dictionary<string, List<long>> Segnalati { get; set; } = new();

    public bool Contiene(long regolaId, string giorno) =>
        Segnalati.TryGetValue(giorno, out var ids) && ids.Contains(regolaId);

    public void Aggiungi(long regolaId, string giorno)
    {
        if (!Segnalati.TryGetValue(giorno, out var ids))
        {
            ids = new List<long>();
            Segnalati[giorno] = ids;
        }
        if (!ids.Contains(regolaId)) ids.Add(regolaId);
    }

    /// <summary>Dimentica i giorni più vecchi di <paramref name="primoDaTenere"/>.</summary>
    public void Pota(DateOnly primoDaTenere)
    {
        foreach (var giorno in Segnalati.Keys.ToList())
        {
            if (!Tempo.ProvaGiorno(giorno, out var d) || d < primoDaTenere) Segnalati.Remove(giorno);
        }
    }

    /// <summary>
    /// Gli sforamenti mai segnalati prima, già registrati come segnalati: il chiamante
    /// li accoda e li annuncia. Stessa regola del telefono (SentinellaPatto).
    /// </summary>
    public List<Sforamento> Nuovi(IEnumerable<Sforamento> sforamenti, string giornoComputer)
    {
        var nuovi = new List<Sforamento>();
        foreach (var s in sforamenti)
        {
            var giorno = Valutatore.GiornoDelloSforamento(s, giornoComputer);
            if (Contiene(s.RegolaId, giorno)) continue;
            Aggiungi(s.RegolaId, giorno);
            nuovi.Add(s);
        }
        return nuovi;
    }
}
