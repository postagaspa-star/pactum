namespace Pactum.Nucleo;

/// <summary>Quello che si vede al computer in un giro di misura (circa un secondo).</summary>
/// <param name="Attivo">L'utente c'è (v. <see cref="Presenza"/>).</param>
/// <param name="Programma">Chiave <c>exe:…</c> del programma in primo piano, null se non si sa.</param>
/// <param name="NomeProgramma">Nome leggibile ("Google Chrome").</param>
/// <param name="Browser">In primo piano c'è un browser di cui si legge la barra degli indirizzi.</param>
/// <param name="Dominio">Il dominio registrabile in primo piano, null se non c'è un sito.</param>
/// <param name="LetturaFallita">Il browser c'è ma la barra non si è potuta leggere.</param>
/// <param name="CategoriaSito">
/// (02/10) La categoria del sito decisa dal nome intero della pagina (<c>music.youtube.com</c> → musica,
/// v. <c>Lettura.DallaBarra</c>); null se il sito non ne ha una o se non la si è data: allora decide il dominio.
/// </param>
/// <param name="Percorso">
/// (0.18) Il percorso del file del programma, se si legge. Resta solo sul computer (mai nelle fotografie): serve a
/// leggere la firma dei programmi visti quando si propone la lista dello Studio.
/// </param>
/// <param name="Studio">
/// (0.18, contratto v4.0) Il programma o il sito in primo piano è nella lista della Sessione Studio in corso:
/// il tempo <b>non conta</b> per limiti, categorie, totale e fasce (come le sessioni del telefono), e va solo
/// nei minuti di Studio del giorno (<c>sessioni_minuti</c> della fotografia).
/// </param>
public sealed record Osservazione(
    bool Attivo,
    string? Programma,
    string? NomeProgramma,
    bool Browser = false,
    string? Dominio = null,
    bool LetturaFallita = false,
    string? CategoriaSito = null,
    bool Studio = false,
    string? Percorso = null)
{
    public static readonly Osservazione Assente = new(false, null, null);
}

public sealed class EsitoGiro
{
    /// <summary>I giorni appena chiusi (la mezzanotte è passata): vanno salvati e fotografati.</summary>
    public List<Giornata> GiorniChiusi { get; } = new();

    /// <summary>I browser dichiarati non leggibili in questo giro (una volta al giorno ciascuno).</summary>
    public List<string> BrowserNonLeggibili { get; } = new();
}

/// <summary>
/// Il conto del tempo attivo, logica pura: riceve un'osservazione al secondo e
/// la somma nel giorno giusto. A mezzanotte divide il giro tra i due giorni e
/// apre il giorno nuovo. Niente I/O: i giorni li carica chi lo usa.
/// </summary>
public sealed class Contatore
{
    /// <summary>(0.23) Le categorie scelte dal figlio (nome del file → categoria): le dà il motore.</summary>
    public IReadOnlyDictionary<string, string> Mie { get; set; } = new Dictionary<string, string>();

    /// <summary>Un giro vale al massimo 2 secondi: dopo una sospensione o un blocco non si inventa tempo.</summary>
    public const long MsMassimiPerGiro = 2_000;

    /// <summary>Oltre un minuto di letture fallite di fila, il giorno dichiara i siti non leggibili.</summary>
    public const long MsCecitaSiti = 60_000;

    private readonly Func<string, Giornata> carica;
    private readonly Dictionary<string, long> msFallitiPerBrowser = new(StringComparer.Ordinal);
    private string? ultimoDominio;

    public Contatore(Giornata oggi, Func<string, Giornata> carica)
    {
        Oggi = oggi;
        this.carica = carica;
    }

    public Giornata Oggi { get; private set; }

    public EsitoGiro Registra(long adessoUtcMs, long msTrascorsi, Osservazione o, TimeZoneInfo zona)
    {
        var esito = new EsitoGiro();
        long ms = Math.Clamp(msTrascorsi, 0, MsMassimiPerGiro);
        var giornoAdesso = Tempo.GiornoDi(adessoUtcMs, zona);

        if (giornoAdesso != Oggi.Giorno)
        {
            long inizio = adessoUtcMs - ms;
            if (o.Attivo && ms > 0
                && Tempo.GiornoDi(inizio, zona) == Oggi.Giorno
                && Tempo.ProvaGiorno(giornoAdesso, out var nuovo))
            {
                // Il pezzo di giro prima della mezzanotte resta al giorno vecchio.
                long mezzanotte = Tempo.InizioGiorno(nuovo, zona);
                long prima = Math.Clamp(mezzanotte - inizio, 0, ms);
                if (prima > 0) Accumula(Oggi, mezzanotte, prima, o, esito);
                ms -= prima;
            }
            esito.GiorniChiusi.Add(Oggi);
            Oggi = carica(giornoAdesso);
            // Giorno nuovo: il sito in primo piano conta come una visita del giorno nuovo.
            ultimoDominio = null;
            msFallitiPerBrowser.Clear();
        }

        if (o.Attivo && ms > 0) Accumula(Oggi, adessoUtcMs, ms, o, esito);
        return esito;
    }

    private void Accumula(Giornata g, long fineMs, long ms, Osservazione o, EsitoGiro esito)
    {
        if (o.Studio)
        {
            // (0.18, contratto v4.0) Nella lista dello Studio il tempo non conta: niente totale, niente minuti
            // delle fasce, niente programmi, siti o categorie. Solo i minuti di Studio del giorno.
            g.MsStudio += ms;
            ultimoDominio = null;
            g.Revisione++;
            return;
        }
        g.MsAttivi += ms;
        long minuto = (fineMs - 1) / 60_000;
        g.MsPerMinuto[minuto] = Math.Min(60_000, g.MsPerMinuto.GetValueOrDefault(minuto) + ms);

        var categoriaProgramma = Categorie.Altro;
        if (o.Programma != null)
        {
            if (!g.Programmi.TryGetValue(o.Programma, out var voce))
            {
                voce = new VoceProgramma { Nome = o.NomeProgramma ?? Programma.NomeDiRipiego(o.Programma) };
                g.Programmi[o.Programma] = voce;
            }
            else if (!string.IsNullOrEmpty(o.NomeProgramma))
            {
                voce.Nome = o.NomeProgramma;
            }
            if (o.Percorso != null) voce.Percorso = o.Percorso;
            voce.Ms += ms;
            categoriaProgramma = Categorie.DiProgramma(o.Programma, Mie);
        }

        string? dominioContato = null;
        if (o.Browser && o.Programma != null)
        {
            if (o.LetturaFallita)
            {
                long falliti = msFallitiPerBrowser.GetValueOrDefault(o.Programma) + ms;
                msFallitiPerBrowser[o.Programma] = falliti;
                if (falliti > MsCecitaSiti && !g.BrowserNonLeggibili.Contains(o.Programma))
                {
                    g.BrowserNonLeggibili.Add(o.Programma);
                    g.SitiNonLeggibili = true;
                    esito.BrowserNonLeggibili.Add(o.Programma);
                }
                // Lo stato delle visite non cambia: una lettura mancata non è un cambio di sito.
            }
            else
            {
                msFallitiPerBrowser[o.Programma] = 0;
                if (o.Dominio != null)
                {
                    if (!g.Siti.TryGetValue(o.Dominio, out var sito))
                    {
                        sito = new VoceSito();
                        g.Siti[o.Dominio] = sito;
                    }
                    sito.Ms += ms;
                    if (ultimoDominio != o.Dominio)
                    {
                        sito.Visite++;
                        ultimoDominio = o.Dominio;
                    }
                    dominioContato = o.Dominio;
                }
                else
                {
                    ultimoDominio = null;
                }
            }
        }
        else
        {
            ultimoDominio = null;
        }

        // Nel browser il tempo va nella categoria del sito, se ne ha una: quella del nome intero della
        // pagina quando il lettore l'ha data (YouTube Music è musica, youtube.com è social), se no quella
        // del dominio. Un sito senza categoria lascia il tempo in quella del programma.
        var categoria = dominioContato == null
            ? categoriaProgramma
            : o.CategoriaSito ?? Categorie.DelTempo(categoriaProgramma, dominioContato);
        var chiave = Categorie.Chiave(categoria);
        g.MsPerCategoria[chiave] = g.MsPerCategoria.GetValueOrDefault(chiave) + ms;
        g.Revisione++;
    }
}

/// <summary>Quando l'utente "c'è" (docs/pc-programma.md, "Tempo attivo").</summary>
public static class Presenza
{
    /// <summary>Un input negli ultimi 3 minuti.</summary>
    public const long MsSenzaInput = 3 * 60_000;

    /// <param name="sessioneDisponibile">Sessione sbloccata, sulla console, sistema non in sospensione.</param>
    /// <param name="salvaschermo">Il salvaschermo è in funzione (è a schermo intero, ma nessuno guarda).</param>
    /// <param name="schermoAcceso">Il monitor è acceso: un gioco in pausa a schermo intero non conta tutta la notte.</param>
    public static bool Attivo(bool sessioneDisponibile, bool salvaschermo, bool schermoAcceso, long msDallUltimoInput, bool schermoIntero)
    {
        if (!sessioneDisponibile || salvaschermo) return false;
        if (msDallUltimoInput >= 0 && msDallUltimoInput < MsSenzaInput) return true;
        return schermoIntero && schermoAcceso;
    }
}
