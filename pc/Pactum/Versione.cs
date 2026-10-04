namespace Pactum;

/// <summary>
/// La versione del programma per il computer (contratto v3, GET /api/versione → "computer").
/// (0.10) Il codice segue quello delle app del telefono: 0.8.0 = 8, 0.9.0 = 9, 0.10.0 = 10. Il server per la
/// 0.8 e la 0.9 annunciava 1 e 2, più bassi del codice del programma: per questo non avvisava mai. Col server
/// a 10 anche il programma 0.8 già installato (codice 8) vede la 0.10 e lo dice.
/// (0.13) Il codice del computer segue ancora quello delle app: 0.13.0 = 13 (contratto v3.6, le faccende).
/// (0.14) 0.14.0 = 14 (contratto v3.7: "lavori di casa" nei testi, sospensione allo spegnimento più robusta).
/// </summary>
public static class Versione
{
    public const string Nome = "0.14.0";
    public const int Codice = 14;
}
