using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Pactum.Nucleo;

/// <summary>
/// Una faccenda da fare, come la manda <c>GET /api/faccende/blocco</c> (contratto v3.6). Solo quello
/// che serve a mostrare il blocco sul computer: niente foto, niente verdetti (le foto si mandano dal
/// telefono). <see cref="BloccoDaMs"/> è il <c>blocco_da</c> (ISO col fuso) già in millisecondi UTC.
/// </summary>
public sealed class Faccenda
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("titolo")] public string Titolo { get; set; } = "";
    [JsonPropertyName("nota")] public string? Nota { get; set; }
    [JsonPropertyName("blocco_da_ms")] public long BloccoDaMs { get; set; }

    /// <summary>Il nome di chi l'ha data (<c>creata_da.nome</c>), per "l'elenco con chi le ha date".</summary>
    [JsonPropertyName("data_da")] public string? DataDa { get; set; }

    /// <summary>
    /// (0.18, contratto v4.0) Lo stato del lavoro nel blocco: <c>"da_fare"</c> (ancora da fare) o
    /// <c>"fatta"</c> (foto mandata, aspetta l'approvazione di un genitore). Un server v3.9 non lo manda:
    /// vale <c>null</c>, e il lavoro si mostra come prima (solo da fare). I lavori vecchi restano bloccati.
    /// </summary>
    [JsonPropertyName("stato")] public string? Stato { get; set; }

    /// <summary>
    /// (0.18, contratto v4.0) Quando è arrivata la foto, per un lavoro che aspetta l'approvazione
    /// (<c>stato = "fatta"</c>); <c>0</c> per un lavoro ancora da fare o per un server vecchio.
    /// </summary>
    [JsonPropertyName("foto_ts_ms")] public long FotoTsMs { get; set; }

    /// <summary>(0.18) Il lavoro aspetta l'approvazione: ha mandato la foto ma nessun genitore l'ha ancora approvata.</summary>
    [JsonIgnore]
    public bool AspettaApprovazione => Stato == "fatta" && FotoTsMs > 0;
}

/// <summary>
/// Lo stato del blocco delle faccende (contratto v3.6, <c>GET /api/faccende/blocco</c>), salvato su
/// disco (<c>blocco.json</c>). Da solo sa dire se il blocco è attivo a un dato istante e quali faccende
/// mostrare, anche senza rete: ogni faccenda porta il suo <c>blocco_da</c>, quindi
/// <list type="bullet">
/// <item>un blocco programmato parte all'ora giusta anche offline (<see cref="AttivoA"/> guarda i blocco_da);</item>
/// <item>un blocco attivo resta tale finché il server non manda una lista diversa (la copia non cambia da sola).</item>
/// </list>
/// La decisione di Andrea per il computer: quando è attivo, TUTTO è bloccato (lo decide chi copre lo schermo,
/// non questa classe). Qui si calcola solo "attivo sì/no" e l'elenco da mostrare.
/// </summary>
public sealed class StatoBlocco
{
    [JsonPropertyName("da_fare")] public List<Faccenda> DaFare { get; set; } = new();

    /// <summary>
    /// (0.13) L'<c>attivo</c> detto dal server all'ultima risposta. Si copre quando questo è vero OPPURE
    /// quando il calcolo locale sui <c>blocco_da</c> dice attivo: così un orologio del PC indietro non fa
    /// saltare un blocco che il server ha già dichiarato attivo.
    /// </summary>
    [JsonPropertyName("attivo")] public bool AttivoServer { get; set; }

    /// <summary>Nessuna faccenda: niente blocco (server vecchio, o faccende finite).</summary>
    public static StatoBlocco Vuoto => new();

    /// <summary>
    /// (0.13) Lo stato di ripiego quando <c>blocco.json</c> si è perso o è rovinato ma l'ultimo "sono vivo"
    /// diceva bloccato: si resta coperti con un elenco generico finché il server non risponde. <c>AttivoServer</c>
    /// vero così copre comunque, e <c>BloccoDaMs</c> a zero lo rende attivo anche col calcolo locale.
    /// </summary>
    public static StatoBlocco Generico() => new()
    {
        AttivoServer = true,
        DaFare = { new Faccenda { Id = 0, Titolo = Testi.LavoriDaFare, BloccoDaMs = 0 } },
    };

    /// <summary>
    /// Attivo = il server lo dice, oppure c'è almeno una faccenda <c>da_fare</c> col <c>blocco_da</c> già
    /// passato (contratto v3.6). L'OR col calcolo locale copre la partenza programmata anche senza rete.
    /// </summary>
    public bool AttivoA(long adessoMs) => AttivoServer || DaFare.Any(f => f.BloccoDaMs <= adessoMs);

    /// <summary>
    /// Il prossimo inizio futuro (il <c>blocco_da</c> più vicino non ancora passato), o null: serve a
    /// sapere quando partire da solo, anche senza rete.
    /// </summary>
    public long? Prossimo(long adessoMs)
    {
        long? min = null;
        foreach (var f in DaFare)
        {
            if (f.BloccoDaMs > adessoMs && (min == null || f.BloccoDaMs < min)) min = f.BloccoDaMs;
        }
        return min;
    }

    /// <summary>Le faccende da mostrare nella finestra del blocco, dalla più vecchia (<c>blocco_da</c>).</summary>
    public IReadOnlyList<Faccenda> DaMostrare() =>
        DaFare.OrderBy(f => f.BloccoDaMs).ThenBy(f => f.Id).ToList();
}

/// <summary>
/// Legge lo stato del blocco dal JSON del server (<c>GET /api/faccende/blocco</c>, o il campo
/// <c>blocco</c> dentro <c>GET /api/patto</c>, contratto v3.6). Tollerante: una faccenda senza un
/// <c>blocco_da</c> leggibile si lascia cadere (meglio non bloccare per un dato storto che bloccare a
/// sproposito). Il server dà anche <c>attivo</c>, <c>dal</c> e <c>prossimo</c>, ma qui si ricavano dai
/// <c>blocco_da</c> delle faccende: così restano veri anche più tardi, senza rete.
/// </summary>
public static class Blocco
{
    public static StatoBlocco Leggi(JsonObject? bloccoJson)
    {
        var stato = new StatoBlocco { AttivoServer = Json.Booleano(bloccoJson?["attivo"]) == true };
        if (bloccoJson?["da_fare"] is not JsonArray lista) return stato;
        foreach (var nodo in lista)
        {
            if (nodo is not JsonObject o) continue;
            if (Json.Intero(o["id"]) is not long id) continue;
            if (!Tempo.ProvaIsoMs(Json.Testo(o["blocco_da"]), out var bloccoDaMs)) continue;
            Tempo.ProvaIsoMs(Json.Testo(o["foto_ts"]), out var fotoTsMs);
            stato.DaFare.Add(new Faccenda
            {
                Id = id,
                Titolo = Json.Testo(o["titolo"]) ?? "",
                Nota = Json.Testo(o["nota"]),
                BloccoDaMs = bloccoDaMs,
                DataDa = Json.Testo((o["creata_da"] as JsonObject)?["nome"]),
                // (0.18, contratto v4.0) stato/foto_ts: un server vecchio non li manda, restano null/0.
                Stato = Json.Testo(o["stato"]),
                FotoTsMs = fotoTsMs,
            });
        }
        return stato;
    }
}

/// <summary>Quello che la finestra del blocco deve mostrare adesso: coperto sì/no e l'elenco delle faccende.</summary>
public sealed record VistaBlocco(bool Coperto, IReadOnlyList<Faccenda> Faccende);
