global using System;
global using System.Collections.Generic;
global using System.IO;
global using System.Linq;
global using System.Threading;
global using System.Threading.Tasks;
global using Xunit;

using System.Globalization;
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
