# Pactum — Contratto API v1 (app ⇄ postino)

**Questo file è la fonte di verità del protocollo.** Ogni modifica al modo in cui app e server si parlano passa da qui PRIMA di toccare il codice. Server e app si allineano a questo documento, non l'uno all'altro.

## Regole generali

- Tutti gli endpoint sotto `/api`. Base URL configurabile nell'app.
- Autenticazione: header `Authorization: Bearer <token>` — token del **figlio** per gli endpoint del figlio, del **genitore** per quelli del genitore. `401` token assente o ignoto, `403` token valido ma del ruolo sbagliato.
- **`ts_device` = epoch in millisecondi UTC (numero intero).** È SOLO informativo: fa sempre fede `ts_server` (ISO 8601 UTC) assegnato dal server alla ricezione (architettura.md).
- Tolleranza evolutiva: il server ignora i campi che non conosce; l'app ignora i campi sconosciuti nelle risposte.

## Endpoint del figlio

### POST /api/battito
Il battito "sono viva", ogni ~15 minuti.
```json
{ "ts_device": 1784056519000, "versione_app": "0.1.0", "elapsed_realtime": 123456789, "batteria": 87 }
```
- `batteria` opzionale (0-100). `elapsed_realtime` = millisecondi dall'ultimo avvio del telefono (serve alle euristiche su riavvii/orologio).
- Risposta `200`: `{ "ricevuto": true }`.

### POST /api/eventi
Batch di eventi del registro.
```json
{ "eventi": [
  { "id": "550e8400-e29b-41d4-a716-446655440000",
    "tipo": "uso_giornaliero",
    "ts_device": 1784056519000,
    "dettagli": { "giorno": "2026-07-14", "uso_minuti": { "com.instagram.android": 42 }, "totale_minuti": 137 } }
] }
```
- `id`: UUID generato dall'app alla creazione dell'evento — **chiave di idempotenza**: un id già visto non viene reinserito.
- `tipo` ∈ `uso_giornaliero · riavvio · manomissione · sforamento · bonus_usato · dichiarazione`.
- `dettagli` per tipo:
  - **uso_giornaliero** — fotografia cumulativa del giorno: `{ "giorno": "YYYY-MM-DD", "uso_minuti": {package: minuti}, "totale_minuti": n }`. Il `giorno` è il giorno locale del telefono e deve essere una data reale `YYYY-MM-DD` (altrimenti la fotografia resta solo nel registro, senza indicizzare la vigente). La fotografia **vigente** per un `giorno` è **monotona su `totale_minuti`**: una fotografia con `totale_minuti` inferiore a quella vigente non la sovrascrive (protegge da consegne fuori ordine; `totale_minuti` mancante o non valido vale 0). Il registro eventi conserva comunque ogni fotografia ricevuta.
  - **riavvio** — `{ }`. Marca l'azzeramento di `elapsed_realtime`; NON è una manomissione.
  - **manomissione** — `{ "sotto_tipo": "cambio_ora" | "cambio_fuso" | "silenzio" | altro, "drift_secondi": n? , "regola_id": n? }`.
  - **sforamento** — `{ "regola_id": n, ... }` (dettagli liberi in più).
  - **bonus_usato / dichiarazione** — riservati a tappe successive; il bonus autoritativo passa SOLO da `POST /api/bonus`.
- Risposta `200`: `{ "ricevuti": N, "nuovi": M, "duplicati": K }`.

### POST /api/bonus
L'unico canale con cui il figlio si concede bonus time (i tetti li applica il server).
```json
{ "minuti": 15, "motivo": "..." }
```
- `minuti` ∈ {5, 15, 30}; `motivo` opzionale.
- `200` con i residui aggiornati; `409` se un tetto (giorno o settimana) verrebbe superato, con i residui nel corpo.
- I bucket giorno/settimana si calcolano nel **fuso del patto** (config server `PACTUM_TIMEZONE`, default `Europe/Rome`); i timestamp restano in UTC ISO.

### Regole — GET /api/regole · POST /api/regole · PATCH /api/regole/{id} · DELETE /api/regole/{id}
- Tipi: `limite_tempo {app_o_categoria, minuti_al_giorno}` · `fascia_oraria {dalle:"HH:MM", alle:"HH:MM", giorni:[lun..dom]}` · `vita_reale {descrizione, arbitro_nome, frequenza}`.
- PATCH: corpo `{ "parametri": {...}, "proposta_id": n? }` — `proposta_id` (proposta accettata, monouso) bypassa il lock dei 4 giorni (modifica concordata). La modifica concordata applica **esattamente** i parametri della proposta: se `parametri` non coincide con i `parametri_proposti` della proposta → `409 {"errore": "parametri_non_concordati"}` e la proposta NON viene consumata. Su una modifica che STRINGE, `proposta_id` viene ignorato (niente consumo, `concordata=false`).
- Lock asimmetrico: modifica che ALLENTA entro 4 giorni dall'ultima creazione/modifica → `409` con i secondi residui; modifica che STRINGE → subito.
- DELETE = allentamento massimo (stesso lock) e soft-delete; eliminare l'ultima regola attiva → `409 errore=ultima_regola`. DELETE concordato: `?proposta_id=n` vale solo se i `parametri_proposti` della proposta sono il marcatore `{"azione": "elimina"}`, altrimenti `409 parametri_non_concordati` senza consumo.

## Endpoint del genitore

### GET /api/finestra
La finestra: per ogni regola il semaforo (oggi + 7 giorni), sforamenti recenti, storico modifiche, bonus residui (giorno+settimana), manomissioni, stato silenzio `{ultimo_battito, silente}` (silente = nessun battito da > 45 minuti).
- **Semaforo per regola: solo `verde` / `rosso` / `grigio`** (niente `giallo`). `rosso` = sforamento nel giorno; `grigio` = giorno prima della creazione della regola oppure giorno strettamente successivo alla sua eliminazione (soft-delete); `verde` = il resto.
- **`bonus_giornalieri`**: riepilogo globale (non per regola) dei minuti bonus concessi in ciascun giorno della stessa finestra di 8 giorni — `[{"giorno": "YYYY-MM-DD", "minuti": n}, ...]` dal più vecchio a oggi — calcolato dalla tabella bonus autoritativa (`POST /api/bonus`), non dagli eventi.
- I giorni della finestra (semaforo e `bonus_giornalieri`) si contano nel fuso del patto (`PACTUM_TIMEZONE`, default `Europe/Rome`).

### GET /api/notifiche · POST /api/notifiche/{id}/letta
Notifiche non lette (polling in v1; push alla tappa 4) e marcatura come letta.

---
**Versione: v1 — 14/07/2026.** Cambi al contratto: prima qui, poi nel codice di entrambi i lati.
