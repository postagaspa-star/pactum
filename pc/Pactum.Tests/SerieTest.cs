using System.Text.Json.Nodes;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>Serie e record, stessi casi del telefono (SerieTest.kt, GiorniPattoTest.kt).</summary>
public class SerieTest
{
    private const Segnale M = Segnale.Mantenuta;
    private const Segnale F = Segnale.FuoriRegola;
    private const Segnale N = Segnale.NessunDato;

    private static List<GiornoPatto> Striscia(string oggi, params Segnale[] segnali)
    {
        var fine = DateOnly.ParseExact(oggi, "yyyy-MM-dd");
        return segnali.Select((s, i) => new GiornoPatto(Tempo.Testo(fine.AddDays(-(segnali.Length - 1 - i))), s)).ToList();
    }

    [Fact]
    public void Verde_e_rosso_sono_gli_unici_stati_con_un_significato()
    {
        Assert.Equal(M, Serie.SegnaleDaStato("verde"));
        Assert.Equal(F, Serie.SegnaleDaStato("rosso"));
        Assert.Equal(N, Serie.SegnaleDaStato("grigio"));
        Assert.Equal(N, Serie.SegnaleDaStato("giallo"));
        Assert.Equal(N, Serie.SegnaleDaStato(null));
    }

    [Fact]
    public void La_striscia_si_legge_dal_patto()
    {
        var striscia = new JsonArray(
            new JsonObject { ["data"] = "2026-09-22", ["stato"] = "verde" },
            new JsonObject { ["data"] = "2026-09-23", ["stato"] = "grigio" });
        Assert.Equal(new[] { new GiornoPatto("2026-09-22", M), new GiornoPatto("2026-09-23", N) }, Serie.DaStriscia(striscia));
        Assert.Empty(Serie.DaStriscia(null));
    }

    [Fact]
    public void La_serie_conta_all_indietro_da_oggi_e_si_ferma_al_primo_fuori_regola()
    {
        Assert.Equal(3, Serie.SerieDiGiorni(Striscia("2026-09-19", M, M, F, M, M, M)));
        Assert.Equal(0, Serie.SerieDiGiorni(Striscia("2026-09-19", M, M, F)));
        Assert.Equal(2, Serie.SerieDiGiorni(Striscia("2026-09-19", F, M, M, N)));
        Assert.Equal(0, Serie.SerieDiGiorni(Striscia("2026-09-19", M, N, M, F)));
        Assert.Equal(1, Serie.SerieDiGiorni(Striscia("2026-09-19", M, N, M)));
        Assert.Equal(8, Serie.SerieDiGiorni(Striscia("2026-09-19", M, M, M, M, M, M, M, M)));
        Assert.Equal(0, Serie.SerieDiGiorni(new List<GiornoPatto>()));
    }

    [Fact]
    public void Il_record_non_scende_mai()
    {
        Assert.Equal(12, Serie.Record(12, 0));
        Assert.Equal(13, Serie.Record(12, 13));
        Assert.Equal(0, Serie.Record(0, -4));
    }

    [Fact]
    public void Senza_memoria_la_serie_e_quella_della_striscia()
    {
        Assert.Equal(new SerieSalvata("2026-09-19", 7), Serie.Calcola(Striscia("2026-09-19", F, M, M, M, M, M, M, M), null));
    }

    [Fact]
    public void Una_serie_a_zero_cancella_la_memoria()
    {
        Assert.Null(Serie.Calcola(Striscia("2026-09-19", M, M, M, M, M, M, M, F), new SerieSalvata("2026-09-18", 20)));
    }

    [Fact]
    public void Oggi_senza_dati_non_rompe_la_serie_e_non_la_allunga()
    {
        Assert.Equal(new SerieSalvata("2026-09-18", 6), Serie.Calcola(Striscia("2026-09-19", F, M, M, M, M, M, M, N), null));
    }

    [Fact]
    public void La_memoria_allunga_la_serie_oltre_gli_8_giorni()
    {
        var giorni = Striscia("2026-09-19", M, M, M, M, M, M, M, M);
        Assert.Equal(new SerieSalvata("2026-09-19", 13), Serie.Calcola(giorni, new SerieSalvata("2026-09-18", 12)));
        Assert.Equal(new SerieSalvata("2026-09-19", 13), Serie.Calcola(giorni, new SerieSalvata("2026-09-19", 13)));
        Assert.Equal(new SerieSalvata("2026-09-19", 13), Serie.Calcola(giorni, new SerieSalvata("2026-09-11", 5)));
    }

    [Fact]
    public void Un_buco_di_giorni_mai_visti_non_si_conta()
    {
        Assert.Equal(new SerieSalvata("2026-09-19", 8),
            Serie.Calcola(Striscia("2026-09-19", M, M, M, M, M, M, M, M), new SerieSalvata("2026-09-09", 30)));
    }

    [Fact]
    public void Una_rottura_dentro_la_finestra_vince_sulla_memoria()
    {
        Assert.Equal(new SerieSalvata("2026-09-19", 4),
            Serie.Calcola(Striscia("2026-09-19", M, M, M, F, M, M, M, M), new SerieSalvata("2026-09-18", 40)));
    }

    [Fact]
    public void Una_striscia_piu_vecchia_della_memoria_non_accorcia_la_serie()
    {
        var salvata = new SerieSalvata("2026-09-19", 12);
        var vecchia = Striscia("2026-09-17", M, M, M, M, M, M, M, M);
        Assert.Equal(salvata, Serie.Calcola(vecchia, salvata));
        Assert.Equal(salvata, Serie.Memoria(vecchia, salvata));
    }

    [Fact]
    public void Ieri_grigio_poi_verde_non_perde_la_serie_lunga()
    {
        var salvata = new SerieSalvata("2026-09-17", 20);
        var prima = Striscia("2026-09-19", M, M, M, M, M, M, N, N);
        Assert.Null(Serie.Calcola(prima, salvata));
        var tenuta = Serie.Memoria(prima, salvata);
        Assert.Equal(salvata, tenuta);
        var seconda = Striscia("2026-09-19", M, M, M, M, M, M, M, N);
        Assert.Equal(new SerieSalvata("2026-09-18", 21), Serie.Calcola(seconda, tenuta));
        Assert.Equal(new SerieSalvata("2026-09-18", 21), Serie.Memoria(seconda, tenuta));
    }

    [Fact]
    public void Ieri_rosso_invece_cancella_la_memoria()
    {
        Assert.Null(Serie.Memoria(Striscia("2026-09-19", M, M, M, M, M, M, F, N), new SerieSalvata("2026-09-17", 20)));
    }

    [Fact]
    public void Dopo_un_grigio_si_mostra_la_serie_nuova_ma_si_ricorda_quella_lunga()
    {
        var salvata = new SerieSalvata("2026-09-15", 20);
        var giorni = Striscia("2026-09-19", M, M, M, M, N, M, M, M);
        Assert.Equal(new SerieSalvata("2026-09-19", 3), Serie.Calcola(giorni, salvata));
        Assert.Equal(salvata, Serie.Memoria(giorni, salvata));
        Assert.Equal(new SerieSalvata("2026-09-19", 24), Serie.Calcola(Striscia("2026-09-19", M, M, M, M, M, M, M, M), salvata));
    }

    [Fact]
    public void Un_grigio_che_resta_grigio_esce_dalla_finestra()
    {
        var salvata = new SerieSalvata("2026-09-15", 20);
        var giorni = Striscia("2026-09-24", M, M, M, M, M, M, M, M);
        Assert.Equal(new SerieSalvata("2026-09-24", 8), Serie.Calcola(giorni, salvata));
        Assert.Equal(new SerieSalvata("2026-09-24", 8), Serie.Memoria(giorni, salvata));
    }
}
