using System.Diagnostics;
using System.Drawing;
using System.Text;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Pactum.Motore;

namespace Pactum.Interfaccia;

/// <summary>
/// La finestra di Pactum: una WebView2 che mostra l'interfaccia HTML da
/// <c>https://pactum.locale/</c> (cartella <c>ui</c>). Non può andare altrove:
/// i link esterni si aprono nel browser di sistema. Chiusa, si libera del tutto
/// (niente processi della WebView accesi in sottofondo); il programma resta nell'icona.
/// </summary>
public sealed class FinestraPactum : Form
{
    private readonly WebView2 vista;
    private readonly Ponte ponte;
    private readonly string cartellaUi;
    private readonly string cartellaDatiWebView;
    private readonly string? fileAutoprova;
    private CoreWebView2Environment? ambiente;
    private bool autoprovaFatta;

    public FinestraPactum(Ponte ponte, string cartellaUi, string cartellaDatiWebView, Icon icona, string? fileAutoprova)
    {
        this.ponte = ponte;
        this.cartellaUi = cartellaUi;
        this.cartellaDatiWebView = cartellaDatiWebView;
        this.fileAutoprova = fileAutoprova;

        Text = "Pactum";
        Icon = icona;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96F, 96F);
        ClientSize = new Size(1100, 760);
        MinimumSize = new Size(720, 520);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.White;

        vista = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Color.White };
        Controls.Add(vista);
        Load += async (_, _) => await InizializzaAsync();
    }

    /// <summary>Terminata l'autoprova (per le prove automatiche).</summary>
    public event Action? AutoprovaFinita;

    /// <summary>Nell'autoprova la finestra si apre ridotta a icona e senza rubare la tastiera a chi usa il PC.</summary>
    protected override bool ShowWithoutActivation => fileAutoprova != null;

    private async Task InizializzaAsync()
    {
        try
        {
            ambiente = await CoreWebView2Environment.CreateAsync(null, cartellaDatiWebView);
            await vista.EnsureCoreWebView2Async(ambiente);
        }
        catch (WebView2RuntimeNotFoundException)
        {
            MostraMessaggio("Per aprire Pactum serve Microsoft Edge WebView2, che su Windows 11 c'è sempre. Il programma intanto continua a misurare.");
            Log.Errore("WebView2 non trovata");
            return;
        }
        catch (Exception e)
        {
            MostraMessaggio("La finestra di Pactum non si è aperta. Il programma intanto continua a misurare.");
            Log.Errore("WebView2 non avviata", e);
            return;
        }

        var core = vista.CoreWebView2;
        await SvuotaCacheSeCambiataAsync(core);
        core.Settings.AreDevToolsEnabled = false;
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreHostObjectsAllowed = false;
        core.Settings.IsGeneralAutofillEnabled = false;
        core.Settings.IsPasswordAutosaveEnabled = false;
        core.Settings.IsWebMessageEnabled = fileAutoprova != null;

        // Niente SetVirtualHostNameToFolderMapping: provato su questo PC (WebView2 153), con la
        // mappatura attiva WebResourceRequested non scatta più e /locale/* non arriverebbe mai al
        // motore. Tutto https://pactum.locale/ passa quindi da qui: /locale e /server al ponte,
        // il resto sono i file della cartella ui. Stessa origine, stessi percorsi relativi.
        core.AddWebResourceRequestedFilter(Ponte.Origine + "/*", CoreWebView2WebResourceContext.All);
        core.WebResourceRequested += SuRichiesta;
        core.NavigationStarting += SuNavigazione;
        core.FrameNavigationStarting += SuNavigazione;
        core.NewWindowRequested += SuNuovaFinestra;
        if (fileAutoprova != null)
        {
            core.NavigationCompleted += SuPaginaPronta;
            core.WebMessageReceived += SuMessaggioAutoprova;
        }
        core.Navigate(Ponte.Origine + "/index.html");
    }

    /// <summary>
    /// Dopo un aggiornamento la WebView potrebbe servire dalla sua cache i file vecchi
    /// di pactum.locale. Si confronta un'impronta dei file installati (versione, nomi,
    /// dimensioni, date) con quella dell'ultima apertura: se è cambiata, si svuota la
    /// cache (non i dati salvati dalla pagina) prima di caricare l'interfaccia.
    /// </summary>
    private async Task SvuotaCacheSeCambiataAsync(CoreWebView2 core)
    {
        var fileImpronta = Path.Combine(cartellaDatiWebView, "impronta-ui.txt");
        var impronta = ImprontaUi(cartellaUi);
        string? precedente = null;
        try
        {
            if (File.Exists(fileImpronta)) precedente = File.ReadAllText(fileImpronta);
        }
        catch (IOException)
        {
        }
        if (precedente == impronta) return;
        try
        {
            await core.Profile.ClearBrowsingDataAsync(
                CoreWebView2BrowsingDataKinds.DiskCache | CoreWebView2BrowsingDataKinds.CacheStorage | CoreWebView2BrowsingDataKinds.ServiceWorkers);
            Nucleo.Archivio.ScriviTesto(fileImpronta, impronta);
            Log.Info("interfaccia cambiata: cache della finestra svuotata");
        }
        catch (Exception e)
        {
            Log.Errore("cache della finestra non svuotata", e);
        }
    }

    /// <summary>Versione del programma + nome, dimensione e data di ogni file della cartella ui.</summary>
    public static string ImprontaUi(string cartella)
    {
        var testo = new StringBuilder(Versione.Nome);
        if (Directory.Exists(cartella))
        {
            foreach (var f in Directory.EnumerateFiles(cartella, "*", SearchOption.AllDirectories).OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
            {
                var info = new FileInfo(f);
                testo.Append('|').Append(Path.GetRelativePath(cartella, f)).Append(':').Append(info.Length).Append(':').Append(info.LastWriteTimeUtc.Ticks);
            }
        }
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(testo.ToString())));
    }

    private async void SuRichiesta(object? mittente, CoreWebView2WebResourceRequestedEventArgs e)
    {
        var rinvio = e.GetDeferral();
        try
        {
            var metodo = e.Request.Method;
            var uri = new Uri(e.Request.Uri);
            if (!Ponte.ÈApi(uri))
            {
                e.Response = FileInterfaccia(metodo, uri);
                return;
            }
            string? corpo = null;
            if (e.Request.Content != null)
            {
                using var lettore = new StreamReader(e.Request.Content, Encoding.UTF8);
                corpo = lettore.ReadToEnd();
            }
            var (stato, json) = await ponte.GestisciAsync(metodo, uri, corpo);
            e.Response = Risposta(stato, json);
        }
        catch (Exception ex)
        {
            Log.Errore("richiesta dell'interfaccia", ex);
            e.Response = Risposta(500, "{\"errore\":\"interno\"}");
        }
        finally
        {
            rinvio.Complete();
        }
    }

    /// <summary>Un file della cartella ui (solo GET): mai fuori dalla cartella, mai in cache.</summary>
    private CoreWebView2WebResourceResponse FileInterfaccia(string metodo, Uri uri)
    {
        var file = metodo is "GET" or "HEAD" ? Ponte.FileDellInterfaccia(cartellaUi, uri.AbsolutePath) : null;
        if (file == null || !File.Exists(file))
        {
            return ambiente!.CreateWebResourceResponse(new MemoryStream(Encoding.UTF8.GetBytes("non trovato")), 404, "Not Found",
                "Content-Type: text/plain; charset=utf-8\r\nCache-Control: no-store");
        }
        // Letto tutto in memoria: i file sono piccoli e così nessun file resta aperto.
        return ambiente!.CreateWebResourceResponse(new MemoryStream(File.ReadAllBytes(file)), 200, "OK",
            $"Content-Type: {Ponte.TipoContenuto(file)}\r\nCache-Control: no-cache\r\nX-Content-Type-Options: nosniff");
    }

    private CoreWebView2WebResourceResponse Risposta(int stato, string json) =>
        ambiente!.CreateWebResourceResponse(
            new MemoryStream(Encoding.UTF8.GetBytes(json)),
            stato,
            Frase(stato),
            "Content-Type: application/json; charset=utf-8\r\nCache-Control: no-store");

    private static string Frase(int stato) => stato switch
    {
        200 => "OK",
        201 => "Created",
        204 => "No Content",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        422 => "Unprocessable Entity",
        429 => "Too Many Requests",
        502 => "Bad Gateway",
        _ => stato < 400 ? "OK" : "Error",
    };

    private void SuNavigazione(object? mittente, CoreWebView2NavigationStartingEventArgs e)
    {
        if (Ponte.ÈInterno(e.Uri)) return;
        e.Cancel = true;
        ApriFuori(e.Uri);
    }

    private void SuNuovaFinestra(object? mittente, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled = true;
        ApriFuori(e.Uri);
    }

    private static void ApriFuori(string indirizzo)
    {
        if (!Ponte.ApribileFuori(indirizzo)) return;
        try
        {
            Process.Start(new ProcessStartInfo(new Uri(indirizzo).AbsoluteUri) { UseShellExecute = true })?.Dispose();
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Log.Errore("link esterno non aperto", e);
        }
    }

    private void MostraMessaggio(string testo)
    {
        Controls.Clear();
        Controls.Add(new Label
        {
            Text = testo,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Segoe UI", 11F),
            Padding = new Padding(40),
        });
    }

    // ---------- Autoprova: il ponte provato dentro la WebView vera ----------

    private async void SuPaginaPronta(object? mittente, CoreWebView2NavigationCompletedEventArgs e)
    {
        if (autoprovaFatta) return;
        autoprovaFatta = true;
        await vista.CoreWebView2.ExecuteScriptAsync(Autoprova.Script);
    }

    private void SuMessaggioAutoprova(object? mittente, CoreWebView2WebMessageReceivedEventArgs e)
    {
        try
        {
            Nucleo.Archivio.ScriviTesto(fileAutoprova!, e.TryGetWebMessageAsString() ?? e.WebMessageAsJson);
            Log.Info("autoprova scritta");
        }
        catch (Exception ex)
        {
            Log.Errore("autoprova non scritta", ex);
        }
        AutoprovaFinita?.Invoke();
    }
}
