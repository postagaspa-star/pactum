using System.Globalization;
using System.Text.Json.Nodes;

namespace Pactum.Nucleo;

/// <summary>
/// Le parole degli avvisi del motore (il fumetto vicino all'orologio e, dalla 0.9, l'avviso a
/// tutto schermo), in un posto solo. Sono quelle dell'interfaccia (<c>pc/ui/testi.js</c>: durata,
/// "Tutto il computer", "48 min oltre") e dell'avviso del telefono: stessi fatti, stesse parole.
/// </summary>
public static class Testi
{
    /// <summary>La frase con cui ogni avviso ricorda che Pactum non blocca niente.</summary>
    public const string NessunBlocco = "Nessun blocco: è il tuo patto.";

    public const string TitoloOltre = "Oggi sei andato oltre";
    public const string TitoloFascia = "Hai usato il computer in una fascia che ti sei imposto";

    /// <summary>(v3.3) Il nome della regola sul totale (<c>app_o_categoria = "totale"</c>) di un computer.</summary>
    public const string TuttoIlComputer = "Tutto il computer";

    public const string HoCapito = "Ho capito";
    public const string ApriPactum = "Apri Pactum";

    /// <summary>
    /// Sotto i pulsanti dell'avviso: Windows non dà la tastiera a una finestra che si apre mentre si usa
    /// un altro programma (un gioco, per esempio) finché non ci si fa clic sopra.
    /// </summary>
    public const string TastiNonRispondono = "Se i tasti non rispondono, fai clic qui.";

    /// <summary>Il pezzo di <see cref="TastiNonRispondono"/> che si mostra come un collegamento.</summary>
    public const string FaiClicQui = "fai clic qui";

    /// <summary>"48 min", "1 h", "1 h 15 min": come <c>durata()</c> di testi.js.</summary>
    public static string Durata(long minuti)
    {
        long m = Math.Max(0, minuti);
        if (m < 60) return m.ToString(CultureInfo.InvariantCulture) + " min";
        var ore = (m / 60).ToString(CultureInfo.InvariantCulture);
        if (m % 60 == 0) return ore + " h";
        return ore + " h " + (m % 60).ToString(CultureInfo.InvariantCulture) + " min";
    }

    /// <summary>
    /// Il bersaglio di un limite detto per nome: "Tutto il computer", "Social", "youtube.com",
    /// "Minecraft" (il nome visto oggi per quel programma, altrimenti il file senza ".exe").
    /// </summary>
    public static string NomeBersaglio(string? chiave, Giornata? oggi)
    {
        if (string.IsNullOrWhiteSpace(chiave)) return "?";
        var k = chiave.Trim().ToLowerInvariant();
        if (k == Bersagli.Totale) return TuttoIlComputer;
        if (k.StartsWith(Categorie.Prefisso, StringComparison.Ordinal))
        {
            var c = k[Categorie.Prefisso.Length..];
            return c.Length > 0 ? char.ToUpper(c[0], CultureInfo.InvariantCulture) + c[1..] : c;
        }
        if (k.StartsWith(Programma.PrefissoSito, StringComparison.Ordinal)) return k[Programma.PrefissoSito.Length..];
        if (oggi != null && oggi.Programmi.TryGetValue(k, out var voce) && !string.IsNullOrWhiteSpace(voce.Nome)) return voce.Nome;
        return Programma.NomeDiRipiego(k);
    }

    // ---------- Il fumetto vicino all'orologio (le frasi di sempre) ----------

    public static (string Titolo, string Testo) Fumetto(Avviso a) => a.Fascia
        ? (TitoloFascia, $"Hai usato il computer {a.MinutiOltre} min in una fascia che ti sei imposto ({a.Dalle}–{a.Alle}). {NessunBlocco}")
        : (TitoloOltre, $"{a.Nome}: oggi sei andato {a.MinutiOltre} min oltre il limite che ti sei dato ({a.LimiteEfficace} min). {NessunBlocco}");

    // ---------- L'avviso a tutto schermo (le frasi dell'avviso del telefono) ----------

    /// <summary>Il titolo: quello dei fumetti. Se sono tutte fasce, quello delle fasce.</summary>
    public static string TitoloAvviso(IReadOnlyCollection<Avviso> avvisi) =>
        avvisi.Count > 0 && avvisi.All(a => a.Fascia) ? TitoloFascia : TitoloOltre;

    /// <summary>"2 h 10 min su 2 h": quanto hai usato oggi sul limite di oggi, come nella riga di Oggi.</summary>
    public static string MinutiSuLimite(long usati, long limite) => Durata(usati) + " su " + Durata(limite);

    /// <summary>"10 min oltre", come l'etichetta della riga di Oggi.</summary>
    public static string Oltre(long minuti) => Durata(minuti) + " oltre";

    /// <summary>Il limite che ti sei dato (<c>minuti_al_giorno</c>), con i bonus di oggi se ci sono.</summary>
    public static string LimiteDato(long limite, long bonus) => bonus > 0
        ? $"Il limite che ti sei dato: {Durata(limite)} al giorno, più {Durata(bonus)} di bonus oggi."
        : $"Il limite che ti sei dato: {Durata(limite)} al giorno.";

    // ---------- Il blocco dei lavori di casa (0.13, contratto v3.6; testi 0.14, contratto v3.7) ----------
    // Nel codice si chiamano "faccende" (come le chiavi del contratto, /api/faccende); quello che il figlio
    // legge dice "lavori di casa", e al singolare "lavoro" (maschile).

    /// <summary>Il titolo della finestra che copre gli schermi quando ci sono lavori di casa da fare.</summary>
    public const string TitoloBlocco = "Prima i lavori di casa";

    /// <summary>
    /// (0.18, contratto v4.0) La frase che dice come ci si sblocca: non basta più mandare la foto, serve
    /// che un genitore l'approvi. Le foto si mandano dal telefono.
    /// </summary>
    public const string SottoBlocco = "Si sblocca da solo quando un genitore ha approvato la foto di ogni lavoro.";

    /// <summary>(0.18, contratto v4.0) Accanto a un lavoro che ha già la foto: aspetta che un genitore la approvi.</summary>
    public const string FotoDaApprovare = "foto mandata, aspetta l'approvazione";

    /// <summary>L'elenco di ripiego quando lo stato salvato si è perso (v. <c>StatoBlocco.Generico</c>).</summary>
    public const string LavoriDaFare = "Ci sono lavori di casa da fare";

    /// <summary>"da Mamma" accanto a un lavoro di casa, o stringa vuota se non si sa chi l'ha dato.</summary>
    public static string DaChi(string? chi) => string.IsNullOrWhiteSpace(chi) ? "" : "da " + chi!.Trim();

    /// <summary>
    /// Tutto il blocco in righe di testo semplice: per chi usa un lettore di schermo e per i test.
    /// Il titolo, ogni faccenda (con chi l'ha data e la nota), e la frase di come ci si sblocca.
    /// </summary>
    public static string TestoBlocco(IReadOnlyCollection<Faccenda> faccende)
    {
        var righe = new List<string> { TitoloBlocco };
        foreach (var f in faccende)
        {
            var riga = f.Titolo;
            var chi = DaChi(f.DataDa);
            if (chi.Length > 0) riga += " — " + chi;
            if (!string.IsNullOrWhiteSpace(f.Nota)) riga += ": " + f.Nota!.Trim();
            // (0.18) il lavoro con la foto già mandata aspetta l'approvazione di un genitore.
            if (f.AspettaApprovazione) riga += " (" + FotoDaApprovare + ")";
            righe.Add(riga);
        }
        righe.Add(SottoBlocco);
        return string.Join(Environment.NewLine, righe);
    }

    // ---------- (0.18, contratto v4.0) La Sessione Studio ----------

    /// <summary>Il titolo della copertura dello Studio.</summary>
    public const string TitoloStudio = "Sei in Studio";

    /// <summary>La frase sotto la copertura dello Studio: solo la lista, e si chiude dal telefono.</summary>
    public const string SottoStudio = "Si usano solo le app, i programmi e i siti della lista. Lo Studio si chiude dal telefono.";

    /// <summary>Quando non c'è niente in lista: solo le app sempre usabili.</summary>
    public const string StudioListaVuota = "Per ora la lista è vuota: proponi programmi e siti dalla finestra di Pactum, poi un genitore li approva.";

    /// <summary>Lo stato dello Studio nella finestra di Pactum: "si chiude dal telefono" (dal computer non si chiude).</summary>
    public const string StudioSiChiudeDalTelefono = "Si chiude dal telefono";

    // ---------- (0.18, contratto v4.0) Il guardiano ----------

    /// <summary>La domanda di "Chiudi Pactum": dalla 0.18 dice anche che Pactum si riapre da solo entro un minuto.</summary>
    public const string DomandaChiudiPactum =
        "Se chiudi, i tuoi genitori vedranno un'interruzione nella registrazione.\n\nPactum si riapre da solo entro un minuto.\n\nChiudere Pactum?";

    /// <summary>(correzione 0.18) «Sì» a «Chiudi Pactum» quando intanto è partito il blocco o lo Studio.</summary>
    public const string ChiusuraNonAdesso = "Adesso Pactum non si chiude: c'è lo Studio o il blocco dei lavori di casa.";

    /// <summary>Un avvio a mano di una versione più alta, con una più bassa ancora aperta (nessuna istanza chiude un'altra).</summary>
    public const string VersionePiùVecchiaAperta =
        "È aperta una versione più vecchia di Pactum. Chiudila dal menu (icona vicino all'orologio → Chiudi Pactum) e riapri questo entro un minuto.";

    /// <summary>I nomi leggibili delle voci permesse dello Studio, per la copertura (dal più in alto). "Word", "classeviva.it"…</summary>
    public static IReadOnlyList<string> VociStudio(IReadOnlyList<string> chiavi, IReadOnlyDictionary<string, string> nomi)
    {
        var righe = new List<string>();
        foreach (var k in chiavi)
        {
            if (nomi.TryGetValue(k, out var nome) && !string.IsNullOrWhiteSpace(nome)) { righe.Add(nome); continue; }
            if (k.StartsWith(Programma.PrefissoSito, StringComparison.Ordinal)) righe.Add(k[Programma.PrefissoSito.Length..]);
            else if (k.StartsWith(Programma.Prefisso, StringComparison.Ordinal)) righe.Add(Programma.NomeDiRipiego(k));
            else righe.Add(k);
        }
        return righe;
    }

    /// <summary>Il nome di una fascia oraria del computer: "Niente computer dalle 22:00 alle 07:00".</summary>
    public static string FasciaRegola(string dalle, string alle) => $"Niente computer dalle {dalle} alle {alle}";

    public static string FasciaUso(long minuti) => $"Oggi {Durata(minuti)} di computer dentro questa fascia.";

    // ---------- Le notifiche del server (i fumetti del giro di rete) ----------

    public const string TitoloNovita = "Novità dal patto";
    public const string TitoloNuovaProposta = "Nuova proposta del genitore";

    /// <summary>(0.10, contratto v3.4) Il genitore ha risposto a una proposta del figlio.</summary>
    public const string PropostaAccettata = "Il genitore ha accettato la tua proposta";
    public const string PropostaRifiutata = "Il genitore ha rifiutato la tua proposta";
    public const string PropostaRisposta = "Il genitore ha risposto alla tua proposta";

    /// <summary>(0.10, contratto v3.4) Il genitore ha ritirato una sua proposta, prima che il figlio rispondesse.</summary>
    public const string PropostaRitirata = "Il genitore ha ritirato la sua proposta";

    /// <summary>(0.10) Le sezioni della finestra che il clic su un fumetto apre (come le notifiche del telefono).</summary>
    public const string SezioneProposte = "proposte";
    public const string SezioneDiario = "diario";

    /// <summary>(0.14) La sezione "Lavori di casa" della finestra (il suo nome nel codice resta "faccende").</summary>
    public const string SezioneLavori = "faccende";

    /// <summary>(0.14, contratto v3.7) I fumetti delle notifiche sui lavori di casa (tipi <c>nuove_faccende</c>, <c>faccenda_bocciata</c>, <c>faccenda_annullata</c>).</summary>
    public const string TitoloNuoviLavori = "Nuovi lavori di casa";
    public const string TitoloLavoroRimandato = "Lavoro di casa rimandato";
    public const string TitoloLavoroAnnullato = "Lavoro di casa annullato";

    /// <summary>Il confronto fisso di una proposta di eliminare (contratto, POST /api/proposte).</summary>
    private const string ConfrontoEliminazione = "propone di eliminare la regola";

    /// <summary>
    /// Il fumetto di una notifica del server: il titolo dal tipo (e, per la risposta a una proposta del
    /// figlio, dall'esito), il testo dal messaggio del server senza ripetere il titolo (il contratto scrive
    /// "&lt;titolo&gt;: &lt;confronto&gt;": nel testo resta il confronto, più cosa cambia). Se il messaggio
    /// manca o è solo il titolo, una frase che dice cosa cambia: mai vuoto, il fumetto di Windows non
    /// accetta un testo vuoto. (0.10) In più la sezione che il clic apre: Proposte per le proposte, il
    /// Diario per un verdetto, null per il resto. Le notifiche nuove della v3.4 arrivano al computer solo
    /// così: <c>proposta_risposta</c> (con <c>autore: "figlio"</c>, il genitore ha deciso su una proposta
    /// del figlio) e <c>proposta_ritirata</c> (con <c>autore: "genitore"</c>). Un autore diverso al figlio
    /// non arriva mai: se succedesse, resta una "novità" col suo messaggio.
    /// </summary>
    public static (string Titolo, string Testo, string? Sezione) Notifica(string tipo, string? messaggio, JsonObject? payload)
    {
        var autore = Json.Testo(payload?["autore"]);
        var esito = Json.Testo(payload?["esito"]);
        bool rispostaDelGenitore = tipo == "proposta_risposta" && autore is null or "figlio";
        var (titolo, ripiego) = tipo switch
        {
            "nuova_proposta" when autore is null or "genitore" => (TitoloNuovaProposta, "Decidi tu: la trovi in Pactum, nelle proposte."),
            "proposta_risposta" when rispostaDelGenitore => esito switch
            {
                "accetta" => (PropostaAccettata, "Vale già da adesso."),
                "rifiuta" => (PropostaRifiutata, "La regola resta com'è."),
                _ => (PropostaRisposta, "La risposta è in Pactum, nelle proposte."),
            },
            "proposta_ritirata" when autore is null or "genitore" => (PropostaRitirata, "Non c'è più niente da decidere: la regola resta com'è."),
            "verdetto" => ("Esito della tua dichiarazione", "L'esito è in Pactum, nel Diario."),
            "segno" => ("Un segno dal genitore", "Ho visto la settimana. Bene così."),
            // (0.14) I lavori di casa: il testo è il messaggio del server ("Mamma ti ha dato 3 lavori di casa").
            "nuove_faccende" => (TitoloNuoviLavori, "Li trovi in Pactum, in Lavori di casa. Le foto si mandano dal telefono."),
            "faccenda_bocciata" => (TitoloLavoroRimandato, "Rifallo e manda una foto nuova dal telefono."),
            "faccenda_annullata" => (TitoloLavoroAnnullato, "Non c'è più da farlo."),
            _ => (TitoloNovita, "Apri Pactum per vedere cosa è cambiato."),
        };
        string? sezione = tipo switch
        {
            "nuova_proposta" or "proposta_risposta" or "proposta_ritirata" => SezioneProposte,
            "verdetto" => SezioneDiario,
            "nuove_faccende" or "faccenda_bocciata" or "faccenda_annullata" => SezioneLavori,
            _ => null,
        };

        var resto = SenzaTitolo(messaggio, titolo);
        string testo;
        if (rispostaDelGenitore && esito is "accetta" or "rifiuta" && resto.Length > 0)
        {
            // Una proposta di eliminare: il confronto del server è scritto per il genitore, al figlio si dice cosa è successo.
            testo = string.Equals(resto.TrimEnd('.'), ConfrontoEliminazione, StringComparison.OrdinalIgnoreCase)
                ? (esito == "accetta" ? "La regola è uscita dal patto." : "La regola resta nel patto.")
                : Frase(resto) + " " + ripiego;
        }
        else
        {
            testo = resto.Length > 0 ? Frase(resto) : ripiego;
        }
        return (titolo, testo, sezione);
    }

    /// <summary>
    /// Il messaggio senza il titolo in testa, quando lo ripete ("Il genitore ha accettato la tua proposta:
    /// +30 min…" → "+30 min…"; solo il titolo → ""). Altrimenti il messaggio com'è.
    /// </summary>
    private static string SenzaTitolo(string? messaggio, string titolo)
    {
        var m = (messaggio ?? "").Trim();
        if (!m.StartsWith(titolo, StringComparison.OrdinalIgnoreCase)) return m;
        var dopo = m[titolo.Length..].Trim();
        if (dopo.Length == 0 || dopo == ".") return "";
        return dopo[0] == ':' ? dopo[1..].Trim() : m;
    }

    /// <summary>Una frase intera: la prima lettera maiuscola, il punto in fondo.</summary>
    private static string Frase(string testo)
    {
        var t = testo.Trim();
        if (t.Length == 0) return t;
        if (char.IsLower(t[0])) t = char.ToUpper(t[0], CultureInfo.InvariantCulture) + t[1..];
        return t[^1] is '.' or '!' or '?' ? t : t + ".";
    }

    /// <summary>
    /// Tutto l'avviso in righe di testo semplice: per chi usa un lettore di schermo e per le prove
    /// (<c>--prova-avvisi</c>). Stesse parole della finestra, nello stesso ordine.
    /// </summary>
    public static string TestoAvviso(IReadOnlyCollection<Avviso> avvisi)
    {
        var righe = new List<string> { TitoloAvviso(avvisi) };
        foreach (var a in avvisi)
        {
            if (a.Fascia)
            {
                righe.Add($"{a.Nome}. {FasciaUso(a.MinutiUsati)}");
                continue;
            }
            var riga = $"{a.Nome}: {MinutiSuLimite(a.MinutiUsati, a.LimiteEfficace ?? 0)}, {Oltre(a.MinutiOltre)}.";
            if (a.Limite is int limite) riga += " " + LimiteDato(limite, a.Bonus);
            righe.Add(riga);
        }
        righe.Add(NessunBlocco);
        return string.Join(Environment.NewLine, righe);
    }
}
