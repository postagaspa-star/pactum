using Pactum.Nucleo;
using Pactum.Sistema;

namespace Pactum.Tests;

/// <summary>
/// (0.18, contratto v4.0) Il guardiano: all'avvio e ogni 15 minuti controlla l'attività pianificata
/// «Pactum» e la ricrea se è stata tolta o disattivata. Si prova con un <see cref="IPianificatore"/>
/// finto: l'Utilità di pianificazione vera non si tocca mai.
/// </summary>
public class GuardianoTest
{
    /// <summary>Un pianificatore finto: lo stato lo decide il test, e tiene nota di quante volte è stata (ri)creata.</summary>
    internal sealed class PianificatoreFinto : IPianificatore
    {
        public StatoGuardiano StatoDaDare = StatoGuardiano.Giusta;
        public int Creazioni;
        public bool CreazioneFallisce;
        public string CodiceErrore = "create_1";
        public bool LetturaFallisce;

        public StatoGuardiano Stato()
        {
            if (LetturaFallisce) throw new ErrorePianificatore("query_1", "non leggo");
            return StatoDaDare;
        }

        public void Crea()
        {
            if (CreazioneFallisce) throw new ErrorePianificatore(CodiceErrore, "non creo");
            Creazioni++;
            StatoDaDare = StatoGuardiano.Giusta; // dopo averla creata, c'è ed è giusta
        }
    }

    [Fact]
    public void Attivita_giusta_niente_da_fare()
    {
        var p = new PianificatoreFinto { StatoDaDare = StatoGuardiano.Giusta };
        var g = new Guardiano(p);
        Assert.Null(g.Controlla());
        Assert.Equal(0, p.Creazioni);
    }

    [Fact]
    public void Da_riparare_si_riscrive_senza_avvisare()
    {
        var p = new PianificatoreFinto { StatoDaDare = StatoGuardiano.DaRiparare };
        var g = new Guardiano(p);
        Assert.Null(g.Controlla()); // niente manomissione: solo riparazione del percorso
        Assert.Equal(1, p.Creazioni);
    }

    [Fact]
    public void Mancante_la_ricrea_e_avvisa_una_volta_sola_finche_resta_tolta()
    {
        var p = new PianificatoreFinto { StatoDaDare = StatoGuardiano.Mancante };
        var g = new Guardiano(p);

        var avviso = g.Controlla();
        Assert.NotNull(avviso);
        Assert.Equal("guardiano_assente", (string?)avviso!["sotto_tipo"]);
        Assert.Equal("mancante", (string?)avviso["stato"]);
        Assert.Equal(1, p.Creazioni);

        // Dopo averla ricreata, al controllo dopo è giusta: niente avviso.
        Assert.Null(g.Controlla());

        // Se la toglie di nuovo, nuovo fronte, nuovo avviso.
        p.StatoDaDare = StatoGuardiano.Mancante;
        Assert.NotNull(g.Controlla());
    }

    [Fact]
    public void Disattivata_avvisa_con_lo_stato_disattivata()
    {
        var p = new PianificatoreFinto { StatoDaDare = StatoGuardiano.Disattivata };
        var g = new Guardiano(p);
        var avviso = g.Controlla();
        Assert.Equal("disattivata", (string?)avviso!["stato"]);
        Assert.Equal(1, p.Creazioni);
    }

    [Fact]
    public void Non_riesce_a_crearla_avvisa_non_creata_al_massimo_una_al_giorno()
    {
        long ora = Fuso.Ms("2026-10-07T15:00:00");
        var p = new PianificatoreFinto { StatoDaDare = StatoGuardiano.Mancante, CreazioneFallisce = true, CodiceErrore = "create_1" };
        var g = new Guardiano(p, () => ora);

        var primo = g.Controlla();
        Assert.Equal("non_creata", (string?)primo!["stato"]);
        Assert.Equal("create_1", (string?)primo["errore"]);

        // Pochi minuti dopo, ancora mancante e ancora non si crea: niente secondo avviso (una al giorno).
        ora += 15 * 60_000;
        Assert.Null(g.Controlla());

        // Il giorno dopo, di nuovo.
        ora += UnGiorno;
        Assert.Equal("non_creata", (string?)g.Controlla()!["stato"]);
    }

    private const long UnGiorno = 24L * 60 * 60 * 1000;

    [Fact]
    public void Se_non_si_legge_nemmeno_lo_stato_e_non_creata()
    {
        var p = new PianificatoreFinto { LetturaFallisce = true };
        var g = new Guardiano(p, () => 0L);
        var avviso = g.Controlla();
        Assert.Equal("non_creata", (string?)avviso!["stato"]);
        Assert.Equal("query_1", (string?)avviso["errore"]);
    }

    // ---------- Lo XML e la lettura dello stato (logica pura, nessuna attività vera) ----------

    private const string Exe = @"C:\Users\Luca\AppData\Local\Pactum\Pactum.exe";
    private const string Utente = @"PC-LUCA\Luca";

    [Fact]
    public void Lo_xml_ha_i_trigger_giusti_e_nessun_limite_di_durata()
    {
        var xml = PianificatoreSchtasks.Xml(Exe, Utente);
        Assert.Contains("<LogonTrigger>", xml);
        Assert.Contains("<Interval>PT1M</Interval>", xml);
        Assert.DoesNotContain("<Duration>", xml);                                // ogni minuto, senza fine
        Assert.Contains("<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>", xml); // senza limite di 72 ore
        Assert.Contains("<RunLevel>LeastPrivilege</RunLevel>", xml);            // niente amministratore
        Assert.Contains("<LogonType>InteractiveToken</LogonType>", xml);        // solo con l'utente collegato
        Assert.Contains("<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>", xml);
        Assert.Contains("<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>", xml);
        Assert.Contains("<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>", xml);
        Assert.Contains("<Priority>5</Priority>", xml);                          // priorità normale (per Windows 4-6; 7 è sotto il normale)
        Assert.Contains("<Arguments>--guardiano</Arguments>", xml);
        Assert.Contains("<Description>Riapre Pactum se si chiude</Description>", xml);
        Assert.Contains(@"<URI>\Pactum</URI>", xml);                            // nella cartella principale, nome vero
        Assert.Contains(Exe, xml);
        Assert.Contains("<Hidden>false</Hidden>", xml);                          // visibile, niente di nascosto
        // L'attività è dell'utente (all'accesso di chiunque servirebbe l'amministratore).
        Assert.Equal(2, System.Text.RegularExpressions.Regex.Matches(xml, System.Text.RegularExpressions.Regex.Escape(@"<UserId>PC-LUCA\Luca</UserId>")).Count);
        // È XML valido.
        System.Xml.Linq.XDocument.Parse(xml);
    }

    [Fact]
    public void Lo_xml_scappa_i_caratteri_speciali_del_percorso()
    {
        var xml = PianificatoreSchtasks.Xml(@"C:\Users\Luca & Sara\Pactum\Pactum.exe", @"PC\Luca");
        System.Xml.Linq.XDocument.Parse(xml);
        Assert.Equal(StatoGuardiano.Giusta, PianificatoreSchtasks.LeggiStato(xml, @"C:\Users\Luca & Sara\Pactum\Pactum.exe"));
    }

    [Fact]
    public void Legge_lo_stato_dallo_xml()
    {
        // L'attività giusta: stesso percorso (anche con maiuscole diverse), accesa.
        Assert.Equal(StatoGuardiano.Giusta, PianificatoreSchtasks.LeggiStato(PianificatoreSchtasks.Xml(Exe, Utente), Exe));
        Assert.Equal(StatoGuardiano.Giusta, PianificatoreSchtasks.LeggiStato(PianificatoreSchtasks.Xml(Exe.ToUpperInvariant(), Utente), Exe));
        // Percorso diverso (programma spostato): da riparare.
        Assert.Equal(StatoGuardiano.DaRiparare, PianificatoreSchtasks.LeggiStato(PianificatoreSchtasks.Xml(@"C:\Vecchio\Pactum.exe", Utente), Exe));
        // Disattivata (Settings/Enabled a false).
        var disattivata = PianificatoreSchtasks.Xml(Exe, Utente).Replace("<Enabled>true</Enabled>\n    <Hidden>", "<Enabled>false</Enabled>\n    <Hidden>").Replace("<Enabled>true</Enabled>\r\n    <Hidden>", "<Enabled>false</Enabled>\r\n    <Hidden>");
        Assert.Equal(StatoGuardiano.Disattivata, PianificatoreSchtasks.LeggiStato(disattivata, Exe));
        // XML rotto: trattato come mancante.
        Assert.Equal(StatoGuardiano.Mancante, PianificatoreSchtasks.LeggiStato("non è xml", Exe));
    }

    [Fact]
    public void Un_attivita_cambiata_si_ripara_senza_accusare()
    {
        var giusta = PianificatoreSchtasks.Xml(Exe, Utente);
        // Tolto --guardiano: ogni minuto aprirebbe la finestra davanti al figlio.
        Assert.Equal(StatoGuardiano.DaRiparare, PianificatoreSchtasks.LeggiStato(giusta.Replace("<Arguments>--guardiano</Arguments>", "<Arguments>--apri</Arguments>"), Exe));
        // Il limite di 72 ore rimesso.
        Assert.Equal(StatoGuardiano.DaRiparare, PianificatoreSchtasks.LeggiStato(giusta.Replace("<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>", "<ExecutionTimeLimit>PT72H</ExecutionTimeLimit>"), Exe));
        // La ripetizione di ogni minuto cambiata in ogni ora.
        Assert.Equal(StatoGuardiano.DaRiparare, PianificatoreSchtasks.LeggiStato(giusta.Replace("<Interval>PT1M</Interval>", "<Interval>PT1H</Interval>"), Exe));
        // Il trigger all'accesso tolto.
        var senzaAccesso = System.Text.RegularExpressions.Regex.Replace(giusta, @"<LogonTrigger>[\s\S]*?</LogonTrigger>", "");
        Assert.Equal(StatoGuardiano.DaRiparare, PianificatoreSchtasks.LeggiStato(senzaAccesso, Exe));
    }

    [Theory]
    [InlineData("ERRORE: Impossibile trovare il file specificato.")]
    [InlineData("ERROR: The system cannot find the file specified.")]
    [InlineData(@"ERROR: The specified task name ""Pactum"" does not exist in the system.")]
    public void Riconosce_l_attivita_che_non_c_e_anche_in_italiano(string uscita)
    {
        Assert.True(PianificatoreSchtasks.ÈNonTrovata(uscita));
    }

    [Fact]
    public void Un_altro_errore_di_schtasks_non_e_mancante()
    {
        Assert.False(PianificatoreSchtasks.ÈNonTrovata("ERRORE: Accesso negato."));
    }
}
