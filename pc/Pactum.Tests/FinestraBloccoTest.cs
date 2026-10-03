using System.Drawing;
using System.Runtime.ExceptionServices;
using Pactum.Interfaccia;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>
/// (0.13, contratto v3.6) La finestra del blocco delle faccende: la colonna del contenuto sta sempre
/// dentro lo schermo (a qualunque scala e con quante che siano le faccende), e si disegna davvero coi
/// colori dell'interfaccia. Non si apre mai sullo schermo nei test (solo il disegno in un'immagine).
/// </summary>
public class FinestraBloccoTest
{
    private static readonly IReadOnlyList<Faccenda> Faccende = new[]
    {
        new Faccenda { Id = 1, Titolo = "Svuota la lavastoviglie", Nota = "anche le pentole", BloccoDaMs = 1, DataDa = "Mamma" },
        new Faccenda { Id = 2, Titolo = "Riordina la camera", BloccoDaMs = 2, DataDa = "Papà" },
    };

    [Theory]
    [InlineData(1920, 1080, 1.0f)]
    [InlineData(1366, 768, 1.5f)]
    [InlineData(1280, 800, 2.0f)]
    [InlineData(3840, 2160, 2.5f)]
    public void La_colonna_sta_sempre_dentro_lo_schermo(int larghezza, int altezza, float scala)
    {
        var schermo = new Rectangle(0, 0, larghezza, altezza);
        foreach (int contenuto in new[] { 300, 5_000, 50_000 })
        {
            var d = DisposizioneBlocco.Calcola(new Size(larghezza, altezza), scala, _ => (int)(contenuto * scala));
            Assert.True(schermo.Contains(d.Colonna), $"colonna fuori dallo schermo con {contenuto} px di contenuto");
            Assert.True(d.LarghezzaColonna > 0);
        }
    }

    [Fact]
    public void Con_poco_contenuto_la_colonna_e_al_centro()
    {
        var d = DisposizioneBlocco.Calcola(new Size(1920, 1080), 1f, _ => 300);
        Assert.Equal(540, d.LarghezzaColonna);
        Assert.Equal((1920 - 540) / 2, d.Colonna.X);
    }

    [Fact]
    public void Si_disegna_con_i_colori_dell_interfaccia_e_la_scheda_della_faccenda()
    {
        if (System.Windows.Forms.SystemInformation.HighContrast) return; // col contrasto elevato valgono i colori di sistema
        SuFiloSta(() =>
        {
            var schermo = new Rectangle(0, 0, 1280, 800);
            using var finestra = FinestraBlocco.PerImmagine(schermo, Faccende);
            using var immagine = finestra.Disegna();
            Assert.Equal(schermo.Size, immagine.Size);
            // Lo sfondo è --superficie.
            Assert.Equal(Color.FromArgb(0xFB, 0xFD, 0xFC).ToArgb(), immagine.GetPixel(3, 3).ToArgb());
            // In mezzo allo schermo (dove sta la colonna) c'è la scheda bianca di una faccenda.
            bool scheda = false;
            for (int y = 0; y < immagine.Height && !scheda; y += 3)
            {
                if (immagine.GetPixel(immagine.Width / 2, y).ToArgb() == Color.White.ToArgb()) scheda = true;
            }
            Assert.True(scheda, "la scheda della faccenda non si vede");
        });
    }

    /// <summary>Le finestre di WinForms vogliono un filo STA, come quello del programma.</summary>
    private static void SuFiloSta(Action azione)
    {
        ExceptionDispatchInfo? errore = null;
        var filo = new Thread(() =>
        {
            try
            {
                azione();
            }
            catch (Exception e)
            {
                errore = ExceptionDispatchInfo.Capture(e);
            }
        });
        filo.SetApartmentState(ApartmentState.STA);
        filo.Start();
        filo.Join();
        errore?.Throw();
    }
}
