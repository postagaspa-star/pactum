using System.Globalization;

namespace Pactum.Nucleo;

/// <summary>Conversioni tra millisecondi UTC e giorno/ora locali, con il fuso passato da fuori (testabile).</summary>
public static class Tempo
{
    public const string FormatoGiorno = "yyyy-MM-dd";

    public static DateTime LocaleDa(long utcMs, TimeZoneInfo zona) =>
        TimeZoneInfo.ConvertTimeFromUtc(DateTime.UnixEpoch.AddMilliseconds(utcMs), zona);

    public static string GiornoDi(long utcMs, TimeZoneInfo zona) =>
        LocaleDa(utcMs, zona).ToString(FormatoGiorno, CultureInfo.InvariantCulture);

    public static DateOnly DataDi(long utcMs, TimeZoneInfo zona) => DateOnly.FromDateTime(LocaleDa(utcMs, zona));

    public static bool ProvaGiorno(string? testo, out DateOnly data) =>
        DateOnly.TryParseExact(testo, FormatoGiorno, CultureInfo.InvariantCulture, DateTimeStyles.None, out data);

    public static string Testo(DateOnly data) => data.ToString(FormatoGiorno, CultureInfo.InvariantCulture);

    /// <summary>
    /// Un'ora locale in millisecondi UTC. Un'ora che non esiste (il salto dell'ora
    /// legale) si sposta avanti del salto; un'ora doppia prende la prima.
    /// </summary>
    public static long UtcMsDi(DateOnly data, TimeOnly ora, TimeZoneInfo zona)
    {
        var locale = DateTime.SpecifyKind(data.ToDateTime(ora), DateTimeKind.Unspecified);
        if (zona.IsInvalidTime(locale))
        {
            var salto = zona.GetAdjustmentRules()
                .Where(r => r.DateStart <= locale && r.DateEnd >= locale)
                .Select(r => r.DaylightDelta)
                .FirstOrDefault();
            locale = locale + (salto == TimeSpan.Zero ? TimeSpan.FromHours(1) : salto);
        }
        DateTime utc;
        if (zona.IsAmbiguousTime(locale))
        {
            var offset = zona.GetAmbiguousTimeOffsets(locale).Max();
            utc = DateTime.SpecifyKind(locale - offset, DateTimeKind.Utc);
        }
        else
        {
            utc = TimeZoneInfo.ConvertTimeToUtc(locale, zona);
        }
        return (long)(utc - DateTime.UnixEpoch).TotalMilliseconds;
    }

    /// <summary>La mezzanotte locale che apre il giorno, in millisecondi UTC.</summary>
    public static long InizioGiorno(DateOnly data, TimeZoneInfo zona) => UtcMsDi(data, TimeOnly.MinValue, zona);

    public static string IsoLocale(long utcMs, TimeZoneInfo zona)
    {
        var locale = LocaleDa(utcMs, zona);
        var offset = zona.GetUtcOffset(DateTime.SpecifyKind(DateTime.UnixEpoch.AddMilliseconds(utcMs), DateTimeKind.Utc));
        return new DateTimeOffset(DateTime.SpecifyKind(locale, DateTimeKind.Unspecified), offset)
            .ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture);
    }

    public static long AdessoUtcMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
