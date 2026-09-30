using System.Drawing;
using System.Runtime.ExceptionServices;
using System.Text.Json.Nodes;
using Pactum.Interfaccia;
using Pactum.Nucleo;

namespace Pactum.Tests;

/// <summary>
/// (0.9) La finestra a tutto schermo: i pulsanti per chiuderla ci sono sempre, dentro lo schermo,
/// qualunque sia la scala e quante che siano le regole; e si disegna davvero.
/// </summary>
public class FinestraAvvisoTest
{
    private static readonly Avviso Totale = Avviso.Da(new Sforamento(7, TipiRegola.LimiteTempo, 135, 10),
        new Regola(7, TipiRegola.LimiteTempo, new JsonObject { ["app_o_categoria"] = "totale", ["minuti_al_giorno"] = 120 }, true, 2), null);

    [Theory]
    [InlineData(1920, 1080, 1.0f)]
    [InlineData(1920, 1080, 1.5f)]
    [InlineData(1366, 768, 1.0f)]
    [InlineData(1280, 800, 2.0f)]
    [InlineData(800, 600, 1.5f)]
    [InlineData(3840, 2160, 2.5f)]
    public void I_pulsanti_per_chiudere_sono_sempre_dentro_lo_schermo(int larghezza, int altezza, float scala)
    {
        var schermo = new Rectangle(0, 0, larghezza, altezza);
        int riga = (int)(18 * scala);
        foreach (int contenuto in new[] { 300, 900, 5_000, 50_000 })
        {
            var d = Disposizione.Calcola(new Size(larghezza, altezza), scala, _ => (int)(contenuto * scala), 17, riga);
            Assert.True(schermo.Contains(d.HoCapito), $"Ho capito fuori dallo schermo con {contenuto} px di contenuto");
            Assert.True(schermo.Contains(d.Apri), $"Apri Pactum fuori dallo schermo con {contenuto} px di contenuto");
            Assert.True(schermo.Contains(d.Suggerimento), $"\"fai clic qui\" fuori dallo schermo con {contenuto} px di contenuto");
            Assert.True(schermo.Contains(d.Vista));
            Assert.False(d.HoCapito.IntersectsWith(d.Apri));
            Assert.False(d.Vista.IntersectsWith(d.HoCapito));
            Assert.False(d.Suggerimento.IntersectsWith(d.HoCapito) || d.Suggerimento.IntersectsWith(d.Apri));
            Assert.True(d.Suggerimento.Top > d.HoCapito.Top);
            Assert.True(d.HoCapito.Width > 0 && d.HoCapito.Height > 0);
            // Troppo contenuto: scorre il contenuto, non i pulsanti.
            Assert.Equal(d.Foglio.Height > d.Vista.Height, d.Scorre);
        }
    }

    [Fact]
    public void Con_poco_contenuto_l_avviso_sta_al_centro_e_non_scorre()
    {
        var d = Disposizione.Calcola(new Size(1920, 1080), 1f, _ => 300, 17, 18);
        Assert.False(d.Scorre);
        Assert.Equal(540, d.Vista.Width);
        Assert.Equal((1920 - 540) / 2, d.Vista.X);
        int sopra = d.Vista.Top;
        int sotto = 1080 - d.Suggerimento.Bottom;
        Assert.InRange(Math.Abs(sopra - sotto), 0, 2);
    }

    [Fact]
    public void Una_regola_gia_nell_avviso_prende_i_numeri_nuovi_senza_raddoppiare()
    {
        var lista = FinestraAvviso.Unisci(new List<Avviso>(), new[] { Totale });
        var piuTardi = Totale with { MinutiUsati = 150, MinutiOltre = 15 };
        var fascia = new Avviso(3, TipiRegola.FasciaOraria, "Niente computer dalle 22:00 alle 07:00", 5, null, null, 5, "22:00", "07:00");
        FinestraAvviso.Unisci(lista, new[] { piuTardi, fascia });
        Assert.Equal(new long[] { 7, 3 }, lista.Select(a => a.RegolaId));
        Assert.Equal(150, lista[0].MinutiUsati);
    }

    [Fact]
    public void Si_disegna_con_i_colori_dell_interfaccia_e_i_due_pulsanti()
    {
        if (System.Windows.Forms.SystemInformation.HighContrast) return; // col contrasto elevato valgono i colori di sistema
        SuFiloSta(() =>
        {
            var lavoro = FinestraAvviso.SchermoDiLavoro();
            using var finestra = FinestraAvviso.PerImmagine(new[] { Totale });
            using var immagine = finestra.Disegna();
            // Grande come lo schermo dove si sta guardando (quello della finestra in primo piano).
            Assert.Equal(lavoro.Size, immagine.Size);
            var schermo = new Rectangle(Point.Empty, immagine.Size);
            Assert.True(schermo.Contains(finestra.RigaSuggerimento));
            // Lo sfondo è --superficie.
            Assert.Equal(Color.FromArgb(0xFB, 0xFD, 0xFC).ToArgb(), immagine.GetPixel(3, 3).ToArgb());
            // "Ho capito" è una pillola piena di --primario (a sinistra del testo, dopo l'arrotondamento).
            var hoCapito = finestra.PulsanteHoCapito;
            Assert.True(schermo.Contains(hoCapito));
            int yMezzo = hoCapito.Top + hoCapito.Height / 2;
            Assert.Equal(Color.FromArgb(0x1F, 0x6E, 0x5C).ToArgb(), immagine.GetPixel(hoCapito.Left + hoCapito.Height, yMezzo).ToArgb());
            // "Apri Pactum" è a contorno: dentro la pillola, lo sfondo della pagina.
            var apri = finestra.PulsanteApri;
            Assert.True(schermo.Contains(apri));
            Assert.Equal(Color.FromArgb(0xFB, 0xFD, 0xFC).ToArgb(), immagine.GetPixel(apri.Left + apri.Height, apri.Top + apri.Height / 2).ToArgb());
            // Sopra i pulsanti c'è la scheda bianca della regola.
            bool scheda = false;
            for (int y = 0; y < hoCapito.Top && !scheda; y += 2)
            {
                if (immagine.GetPixel(schermo.Width / 2, y).ToArgb() == Color.White.ToArgb()) scheda = true;
            }
            Assert.True(scheda, "la scheda della regola non si vede");
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
