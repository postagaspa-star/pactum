using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>Questi test cambiano lo stato statico di Archivio: girano da soli, non in parallelo agli altri.</summary>
[CollectionDefinition("Archivio", DisableParallelization = true)]
public class CollezioneArchivio
{
}

/// <summary>
/// Le scritture su disco anche quando il sistema vieta al programma di rinominare
/// i suoi file (PC di casa, 27/09/2026: "accesso negato" a ogni rinomina del
/// <c>.tmp</c>, e così né il collegamento né le misure venivano salvati).
/// </summary>
[Collection("Archivio")]
public class ArchivioTest : IDisposable
{
    private readonly Action<string, string> spostaVera = Archivio.Sposta;

    public void Dispose()
    {
        Archivio.Sposta = spostaVera;
        Archivio.RinominaVietata = false;
    }

    [Fact]
    public void Senza_divieti_scrive_e_non_lascia_il_tmp()
    {
        using var c = new CartellaTemporanea();
        var file = c.File("vivo.json");

        Archivio.ScriviTesto(file, "uno");
        Archivio.ScriviTesto(file, "due");

        Assert.Equal("due", File.ReadAllText(file));
        Assert.False(File.Exists(file + ".tmp"));
        Assert.False(Archivio.RinominaVietata);
    }

    [Fact]
    public void Con_la_rinomina_vietata_il_dato_arriva_lo_stesso()
    {
        using var c = new CartellaTemporanea();
        var file = c.File("config.json");
        int tentativi = 0;
        Archivio.Sposta = (_, _) =>
        {
            tentativi++;
            throw new UnauthorizedAccessException("Access to the path is denied.");
        };

        Archivio.ScriviTesto(file, "{\"token\":\"primo\"}");

        Assert.Equal("{\"token\":\"primo\"}", File.ReadAllText(file));
        Assert.False(File.Exists(file + ".tmp"));
        Assert.True(Archivio.RinominaVietata);
        Assert.Equal(5, tentativi);

        // Dopo il primo divieto la rinomina non si riprova più: niente attese a ogni scrittura.
        Archivio.ScriviTesto(file, "{\"token\":\"secondo\"}");

        Assert.Equal("{\"token\":\"secondo\"}", File.ReadAllText(file));
        Assert.Equal(5, tentativi);
        Assert.Equal("secondo", Archivio.LeggiJson<Dictionary<string, string>>(file)!["token"]);
    }

    [Fact]
    public void Un_file_occupato_per_un_attimo_si_riprova_e_resta_atomico()
    {
        using var c = new CartellaTemporanea();
        var file = c.File("coda.json");
        int tentativi = 0;
        Archivio.Sposta = (da, a) =>
        {
            if (++tentativi < 3) throw new IOException("occupato dall'antivirus");
            File.Move(da, a, overwrite: true);
        };

        Archivio.ScriviTesto(file, "ok");

        Assert.Equal("ok", File.ReadAllText(file));
        Assert.False(File.Exists(file + ".tmp"));
        Assert.False(Archivio.RinominaVietata);
        Assert.Equal(3, tentativi);
    }
}
