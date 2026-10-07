using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Xml.Linq;
using Pactum.Nucleo;

namespace Pactum.Sistema;

/// <summary>
/// (0.18, contratto v4.0) L'attività pianificata «Pactum» (il guardiano) nell'Utilità di pianificazione
/// dell'account del figlio, tramite <c>schtasks.exe</c>. Attività <b>di utente</b>, senza amministratore:
/// all'accesso di quell'utente e ogni minuto, senza limite di durata, <c>"&lt;percorso&gt;" --guardiano</c>,
/// visibile, nella cartella principale, con descrizione «Riapre Pactum se si chiude». Niente di nascosto,
/// niente copie dell'eseguibile, niente nomi finti (il repo è pubblico e Bitdefender gira).
///
/// Non si usa mai nei test: la logica del guardiano (<see cref="Guardiano"/>) si prova con un finto, e qui
/// si provano solo le parti pure (lo XML e la sua lettura).
/// </summary>
public sealed class PianificatoreSchtasks : IPianificatore
{
    public const string NomeAttivita = "Pactum";
    public const string Descrizione = "Riapre Pactum se si chiude";
    public const string Opzione = "--guardiano";

    private readonly string eseguibile;
    private readonly string utente;

    /// <param name="eseguibile">Il percorso di Pactum.exe scritto nell'attività (quello di questo avvio).</param>
    /// <param name="utente">L'account per cui si crea l'attività (<c>PC\nome</c>): un account standard può crearne solo per sé.</param>
    public PianificatoreSchtasks(string eseguibile, string utente)
    {
        this.eseguibile = eseguibile;
        this.utente = utente;
    }

    /// <summary>L'account Windows di questo processo, come lo vuole l'Utilità di pianificazione (<c>PC\nome</c>).</summary>
    public static string UtenteCorrente()
    {
        using var io = System.Security.Principal.WindowsIdentity.GetCurrent();
        return io.Name;
    }

    public StatoGuardiano Stato()
    {
        var (codice, uscita) = Schtasks("/Query", "/TN", NomeAttivita, "/XML", "ONE");
        if (codice != 0)
        {
            // schtasks dà 1 anche quando l'attività non c'è: lì è "mancante", non un errore. Il testo dipende
            // dalla lingua di Windows (in italiano «Impossibile trovare il file specificato»).
            if (ÈNonTrovata(uscita)) return StatoGuardiano.Mancante;
            throw new ErrorePianificatore("query_" + codice.ToString(CultureInfo.InvariantCulture), uscita.Trim());
        }
        return LeggiStato(uscita, eseguibile, utente);
    }

    /// <summary>Il messaggio di schtasks dice che l'attività non esiste? (inglese e italiano). Logica pura.</summary>
    internal static bool ÈNonTrovata(string uscita) =>
        new[] { "cannot find", "does not exist", "impossibile trovare", "non esiste", "non trovat" }
            .Any(f => uscita.Contains(f, StringComparison.OrdinalIgnoreCase));

    /// <summary>
    /// Lo stato dallo XML dell'attività, confrontato campo per campo con quello che scrive <see cref="Xml"/>. Logica pura.
    /// <list type="bullet">
    /// <item><see cref="StatoGuardiano.Disattivata"/> (si ripara e si avvisa): l'attività o uno dei suoi due trigger spenti,
    /// oppure un cambio che la spegne di fatto: parte solo a corrente o si ferma a batteria, solo col computer inattivo,
    /// solo con la rete, un trigger che comincia nel futuro o è già finito (data di fine passata, durata della ripetizione
    /// scaduta), un modo di accesso diverso da «solo con l'utente collegato»;</item>
    /// <item><see cref="StatoGuardiano.DaRiparare"/> (si riscrive senza accusare): comando diverso (programma spostato),
    /// argomenti senza <c>--guardiano</c>, un limite di durata, un trigger tolto, ritardi, una data di fine futura, una
    /// ripetizione diversa, un'altra regola per le istanze, una priorità fuori dalla classe normale (4-6), più di un'azione;</item>
    /// <item>altrimenti <see cref="StatoGuardiano.Giusta"/>.</item>
    /// </list>
    /// Un campo che manca vale come il valore di base di Windows (senza <c>DisallowStartIfOnBatteries</c> Windows non parte
    /// a batteria, senza <c>Priority</c> la priorità è 7). Uno XML che non si legge vale come mancante.
    /// </summary>
    internal static StatoGuardiano LeggiStato(string xml, string eseguibileAtteso, string? utenteAtteso = null, DateTimeOffset? adesso = null)
    {
        XDocument doc;
        try
        {
            doc = XDocument.Parse(xml);
        }
        catch (System.Xml.XmlException)
        {
            return StatoGuardiano.Mancante;
        }
        var ora = adesso ?? DateTimeOffset.Now;
        static XElement? El(XElement? p, string nome) => p?.Elements().FirstOrDefault(e => e.Name.LocalName == nome);
        static IEnumerable<XElement> Tutti(XElement? p, string nome) =>
            p?.Elements().Where(e => e.Name.LocalName == nome) ?? Enumerable.Empty<XElement>();
        static string? Val(XElement? p, string nome) => El(p, nome)?.Value.Trim();
        // Un vero/falso, col valore di base di Windows se manca.
        static bool Bool(XElement? p, string nome, bool valoreDiBase) =>
            Val(p, nome) is string v ? string.Equals(v, "true", StringComparison.OrdinalIgnoreCase) || v == "1" : valoreDiBase;
        static bool Spento(XElement? e) => !Bool(e, "Enabled", true);
        static bool Zero(string? durata) => string.IsNullOrEmpty(durata) || Durata(durata) == TimeSpan.Zero;

        var task = doc.Root;
        var impostazioni = El(task, "Settings");
        var trigger = El(task, "Triggers");
        var accessi = Tutti(trigger, "LogonTrigger").ToList();
        var minuti = Tutti(trigger, "TimeTrigger").ToList();

        // ---- Spenta, anche solo di fatto: si ripara e si avvisa «disattivata». ----
        if (Spento(impostazioni)) return StatoGuardiano.Disattivata;
        if (accessi.Any(Spento) || minuti.Any(Spento)) return StatoGuardiano.Disattivata;
        if (Bool(impostazioni, "DisallowStartIfOnBatteries", true) || Bool(impostazioni, "StopIfGoingOnBatteries", true)) return StatoGuardiano.Disattivata;
        if (Bool(impostazioni, "RunOnlyIfIdle", false) || Bool(impostazioni, "RunOnlyIfNetworkAvailable", false)) return StatoGuardiano.Disattivata;
        var tipoAccesso = Val(El(El(task, "Principals"), "Principal"), "LogonType");
        if (tipoAccesso != null && !string.Equals(tipoAccesso, "InteractiveToken", StringComparison.OrdinalIgnoreCase)) return StatoGuardiano.Disattivata;
        foreach (var t in accessi.Concat(minuti))
        {
            if (Data(Val(t, "StartBoundary")) is DateTimeOffset inizio && inizio > ora) return StatoGuardiano.Disattivata;
            if (Data(Val(t, "EndBoundary")) is DateTimeOffset fine && fine <= ora) return StatoGuardiano.Disattivata;
        }
        foreach (var t in minuti)
        {
            var durata = Val(El(t, "Repetition"), "Duration");
            if (!string.IsNullOrEmpty(durata) && Data(Val(t, "StartBoundary")) is DateTimeOffset inizio
                && Durata(durata) is TimeSpan d && (d == TimeSpan.MaxValue ? DateTimeOffset.MaxValue : inizio + d) <= ora) return StatoGuardiano.Disattivata;
        }

        // ---- Cambiata: si riscrive senza accusare. ----
        var azioni = El(task, "Actions")?.Elements().ToList() ?? new List<XElement>();
        if (azioni.Count != 1 || azioni[0].Name.LocalName != "Exec") return StatoGuardiano.DaRiparare;
        var exec = azioni[0];
        var comando = Val(exec, "Command")?.Trim('"');
        var argomenti = Val(exec, "Arguments") ?? "";
        if (comando == null || !string.Equals(comando, eseguibileAtteso, StringComparison.OrdinalIgnoreCase)) return StatoGuardiano.DaRiparare;
        if (!argomenti.Split(' ', StringSplitOptions.RemoveEmptyEntries).Contains(Opzione, StringComparer.Ordinal)) return StatoGuardiano.DaRiparare;

        // Il limite di durata: PT0S = nessuno. Se manca, Windows mette il suo (72 ore): da riparare.
        if (Val(impostazioni, "ExecutionTimeLimit") != "PT0S") return StatoGuardiano.DaRiparare;
        // «Se è già in esecuzione non avviarne un'altra» (IgnoreNew è anche il valore di base).
        var istanze = Val(impostazioni, "MultipleInstancesPolicy") ?? "IgnoreNew";
        if (!string.Equals(istanze, "IgnoreNew", StringComparison.OrdinalIgnoreCase)) return StatoGuardiano.DaRiparare;
        // Priorità normale: per Windows 4-6 (7, il valore di base, è «sotto il normale»).
        int priorita = int.TryParse(Val(impostazioni, "Priority") ?? "7", NumberStyles.Integer, CultureInfo.InvariantCulture, out var pr) ? pr : 7;
        if (priorita is < 4 or > 6) return StatoGuardiano.DaRiparare;

        if (accessi.Count == 0 || minuti.Count == 0) return StatoGuardiano.DaRiparare;
        foreach (var t in accessi.Concat(minuti))
        {
            if (Val(t, "EndBoundary") != null) return StatoGuardiano.DaRiparare;
            if (!Zero(Val(t, "Delay")) || !Zero(Val(t, "RandomDelay"))) return StatoGuardiano.DaRiparare;
            if (Val(t, "ExecutionTimeLimit") is string limite && limite != "PT0S") return StatoGuardiano.DaRiparare;
        }
        foreach (var t in accessi)
        {
            // L'utente del trigger. Scritto come SID non si confronta: può essere solo il modo di Windows di esportarlo.
            var chi = Val(t, "UserId");
            if (chi != null && utenteAtteso != null && !chi.StartsWith("S-1-", StringComparison.OrdinalIgnoreCase) && !StessoUtente(chi, utenteAtteso))
                return StatoGuardiano.DaRiparare;
        }
        foreach (var t in minuti)
        {
            var ripetizione = El(t, "Repetition");
            if (Val(ripetizione, "Interval") != "PT1M") return StatoGuardiano.DaRiparare;
            if (!string.IsNullOrEmpty(Val(ripetizione, "Duration"))) return StatoGuardiano.DaRiparare;
            if (Bool(ripetizione, "StopAtDurationEnd", false)) return StatoGuardiano.DaRiparare;
        }
        return StatoGuardiano.Giusta;
    }

    private static bool StessoUtente(string a, string b)
    {
        if (string.Equals(a, b, StringComparison.OrdinalIgnoreCase)) return true;
        static string Nome(string s) => s.Contains('\\') ? s[(s.LastIndexOf('\\') + 1)..] : s;
        return string.Equals(Nome(a), Nome(b), StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Una data dello XML (<c>2020-01-01T00:00:00</c>: senza fuso è l'ora locale), o null.</summary>
    private static DateTimeOffset? Data(string? testo)
    {
        if (string.IsNullOrEmpty(testo)) return null;
        return DateTimeOffset.TryParse(testo, CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out var d) ? d : null;
    }

    /// <summary>Una durata dello XML (<c>PT1M</c>, <c>P1D</c>), o null se non si legge.</summary>
    private static TimeSpan? Durata(string testo)
    {
        try
        {
            return System.Xml.XmlConvert.ToTimeSpan(testo);
        }
        catch (FormatException)
        {
            return null;
        }
        catch (OverflowException)
        {
            return TimeSpan.MaxValue;
        }
    }

    public void Crea()
    {
        var xml = Xml(eseguibile, utente);
        var file = Path.Combine(Path.GetTempPath(), "pactum-guardiano-" + Guid.NewGuid().ToString("N") + ".xml");
        try
        {
            // schtasks /Create /XML vuole un file UTF-16 con la BOM.
            File.WriteAllText(file, xml, new UnicodeEncoding(bigEndian: false, byteOrderMark: true));
            var (codice, uscita) = Schtasks("/Create", "/TN", NomeAttivita, "/XML", file, "/F");
            if (codice != 0) throw new ErrorePianificatore("create_" + codice.ToString(CultureInfo.InvariantCulture), uscita.Trim());
        }
        catch (IOException e)
        {
            throw new ErrorePianificatore("file", e.Message);
        }
        catch (UnauthorizedAccessException e)
        {
            throw new ErrorePianificatore("file", e.Message);
        }
        finally
        {
            try { File.Delete(file); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }

    /// <summary>
    /// Lo XML dell'attività: all'accesso di <b>questo</b> utente (un account standard non può creare un trigger
    /// «all'accesso di chiunque») e ogni minuto senza fine (TimeTrigger con ripetizione <c>PT1M</c> e senza
    /// <c>Duration</c>), senza limite di durata (<c>ExecutionTimeLimit PT0S</c>: di base Windows ferma dopo 72
    /// ore), anche a batteria, solo con l'utente collegato (<c>InteractiveToken</c>), senza privilegi elevati,
    /// «se è già in esecuzione non avviarne un'altra» (<c>IgnoreNew</c>), priorità normale (5: per Windows
    /// 4-6 è la classe normale, 7 è «sotto il normale»), visibile.
    /// Logica pura: testabile senza toccare l'Utilità di pianificazione.
    /// </summary>
    internal static string Xml(string eseguibile, string utente)
    {
        var exe = System.Security.SecurityElement.Escape(eseguibile);
        var chi = System.Security.SecurityElement.Escape(utente);
        return $@"<?xml version=""1.0"" encoding=""UTF-16""?>
<Task version=""1.2"" xmlns=""http://schemas.microsoft.com/windows/2004/02/mit/task"">
  <RegistrationInfo>
    <Description>{Descrizione}</Description>
    <URI>\{NomeAttivita}</URI>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <UserId>{chi}</UserId>
    </LogonTrigger>
    <TimeTrigger>
      <StartBoundary>2020-01-01T00:00:00</StartBoundary>
      <Enabled>true</Enabled>
      <Repetition>
        <Interval>PT1M</Interval>
        <StopAtDurationEnd>false</StopAtDurationEnd>
      </Repetition>
    </TimeTrigger>
  </Triggers>
  <Principals>
    <Principal id=""Autore"">
      <UserId>{chi}</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>5</Priority>
  </Settings>
  <Actions Context=""Autore"">
    <Exec>
      <Command>""{exe}""</Command>
      <Arguments>{Opzione}</Arguments>
    </Exec>
  </Actions>
</Task>";
    }

    /// <summary>
    /// Lancia schtasks.exe e ne legge l'uscita nella codifica della console (OEM: 850 su un Windows italiano),
    /// così un percorso con lettere accentate si confronta giusto.
    /// </summary>
    private static (int Codice, string Uscita) Schtasks(params string[] argomenti)
    {
        Encoding codifica;
        try
        {
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
            codifica = Encoding.GetEncoding(CultureInfo.CurrentCulture.TextInfo.OEMCodePage);
        }
        catch (Exception e) when (e is ArgumentException or NotSupportedException)
        {
            codifica = Encoding.UTF8;
        }
        var avvio = new ProcessStartInfo("schtasks.exe")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = codifica,
            StandardErrorEncoding = codifica,
        };
        foreach (var a in argomenti) avvio.ArgumentList.Add(a);
        try
        {
            using var p = Process.Start(avvio) ?? throw new ErrorePianificatore("avvio", "schtasks non avviato");
            // L'errore si legge in parallelo: due letture una dopo l'altra possono bloccarsi a vicenda.
            var errore = p.StandardError.ReadToEndAsync();
            var uscita = p.StandardOutput.ReadToEnd();
            if (!p.WaitForExit(15_000))
            {
                try { p.Kill(); } catch (InvalidOperationException) { }
                throw new ErrorePianificatore("tempo", "schtasks non ha risposto");
            }
            return (p.ExitCode, uscita + errore.GetAwaiter().GetResult());
        }
        catch (System.ComponentModel.Win32Exception e)
        {
            throw new ErrorePianificatore("avvio", e.Message);
        }
    }
}
