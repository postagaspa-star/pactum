# Pactum sul NAS — guida precisa, passo per passo

Guida concreta: quale elemento, dove sta ORA sul tuo PC, dove va. Segui in ordine.
I dettagli sulle schermate di DSM sono in [deploy-nas.md](deploy-nas.md); qui c'è il
percorso più corto e senza scelte.

## Gli ingredienti (dove sono, sul tuo PC)

| Cosa | Dove sta ORA | Dove va |
|---|---|---|
| **Cartella pronta per il NAS** | `C:\Users\andre\OneDrive\Desktop\Pactum-NAS` | sul NAS, in `docker/Pactum-NAS` |
| I 2 APK firmati | già dentro `Pactum-NAS\server\apk\` (viaggiano con la cartella) | — |
| **La chiave di firma** | `C:\Users\andre\pactum-keys` | **NON sul NAS** — su chiavetta/cloud tuo |

Tutto ciò che serve al NAS è già dentro **`Pactum-NAS`** (16 MB, senza zavorra).

---

## PASSO 0 — Metti la chiave al sicuro (2 minuti)

Copia l'intera cartella `C:\Users\andre\pactum-keys` su una **chiavetta USB** o nel
tuo **cloud personale**. È la chiave che firma gli aggiornamenti di Pactum per
sempre: se la perdi, non potrai più aggiornare le app. Non va sul NAS, non va nel
repo. Fatto questo, dimenticatene (ma tienila).

## PASSO 1 — Genera i 2 token (sul PC)

I token sono l'unica barriera tra Internet e il registro. Apri **PowerShell**
(o il Prompt) e lancia **due volte** questo comando:

```
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

Escono due stringhe di ~43 caratteri (tipo `PWR7Euy4-Frtg...`). Scrivile su un
foglio così:
- **TOKEN A** (primo) = del **figlio**
- **TOKEN B** (secondo) = del **genitore**

Ti serviranno due volte: nel file `.env` (Passo 3) e nelle app (Passo 7).

## PASSO 2 — Porta la cartella `Pactum-NAS` sul NAS

Ti serve una cartella condivisa `docker` sul NAS (Container Manager la usa).

1. Se non esiste: **DSM → Pannello di controllo → Cartella condivisa → Crea**,
   nome esatto `docker`, avanti fino a fine.
2. Apri **File Station** (icona nel menu di DSM).
3. Entra nella cartella `docker`.
4. Trascina dentro l'**intera cartella `Pactum-NAS`** dal tuo PC.
   - Da browser: pulsante **Carica → Carica cartella**, scegli `Pactum-NAS`.
   - Oppure mappa il NAS come unità di rete: in Esplora file digita
     `\\IP-DEL-NAS\docker`, accedi, e copia-incolla la cartella.

Risultato atteso sul NAS:
`docker/Pactum-NAS/docker-compose.yml` e `docker/Pactum-NAS/server/...`

## PASSO 3 — Crea il file `.env` con i token (sul NAS)

I token veri li mettiamo **direttamente sul NAS**, così non passano dal PC/cloud.

1. In File Station entra in `docker/Pactum-NAS/server`.
2. Se non vedi i file che iniziano con punto: menu **⚙ (Impostazioni) → Mostra
   file nascosti**.
3. Trovi **`.env.example`**. Tasto destro → **Copia**, poi **Incolla** nella stessa
   cartella. Rinomina la copia in esattamente **`.env`** (tasto destro → Rinomina:
   deve restare `.env`, senza `.example`).
4. Doppio clic su **`.env`** → si apre l'editor di testo di DSM. Cambia solo queste
   due righe:
   ```
   PACTUM_TOKEN_FIGLIO=<incolla qui TOKEN A>
   PACTUM_TOKEN_GENITORE=<incolla qui TOKEN B>
   ```
   Lascia tutto il resto com'è (`PACTUM_ENV=prod`, i percorsi, ecc.). **Salva.**

## PASSO 4 — Avvia il progetto (Container Manager)

1. Apri **Container Manager** (installalo dal Centro pacchetti se non c'è).
2. **Progetto → Crea.**
3. **Nome progetto:** `pactum`.
4. **Percorso:** sfoglia fino a `docker/Pactum-NAS` (la cartella che **contiene**
   `docker-compose.yml`).
5. Sorgente: sceglie da solo **"Usa docker-compose.yml esistente"**.
6. **Avanti → Fatto.** La prima volta costruisce l'immagine (qualche minuto) e
   avvia due container: **`pactum`** (il postino) e **`pactum-tunnel`** (il tunnel).

Se al posto di partire dà errore "token non sicuri": vuol dire che il `.env` del
Passo 3 non è a posto (token mancante o troppo corto). È voluto — sistema il `.env`
e riavvia il progetto.

## PASSO 5 — Verifica che sia vivo (in rete di casa)

Da un browser sullo stesso WiFi:
```
http://IP-DEL-NAS:8000/api/salute
```
(l'IP del NAS è lo stesso che usi per aprire DSM). Deve rispondere:
```
{"stato":"ok","versione":"0.1.0","env":"prod","db_ok":true}
```
`env: prod` + `db_ok: true` = tutto a posto.

## PASSO 6 — Leggi l'indirizzo del tunnel (per i telefoni)

Container Manager → **Container → `pactum-tunnel` → Dettagli → Log**. Cerca:
```
https://<parole-a-caso>.trycloudflare.com
```
**Quello è l'indirizzo del server** da mettere nelle app. Provalo: da un telefono
**in rete dati** (WiFi spento) apri `https://<quell-URL>/api/salute` → deve
rispondere come al Passo 5.

> ⚠️ Questo URL **cambia a ogni riavvio del NAS**. Quando succede: rileggilo qui e
> re-incollalo nelle due app (Passo 7.3). I token NON cambiano.

## PASSO 7 — Installa e configura le app

1. **Telefono del figlio:** browser → `https://<URL-tunnel>/scarica` → scarica e
   installa **`pactum-figlio.apk`** (Play Protect: "Installa comunque").
2. **Telefono del genitore:** stessa pagina → **`pactum-genitore.apk`**.
3. In **ciascuna** app: **Impostazioni** →
   - **Indirizzo del server** = l'URL del tunnel;
   - **Token** = **TOKEN A** nell'app del figlio, **TOKEN B** in quella del genitore;
   - **Salva → Prova adesso** (deve dire "il server risponde").
4. Nell'app del figlio: completa l'onboarding (permessi — incluso l'accesso all'uso
   con il percorso "Consenti impostazioni con limitazioni" — e la prima regola).

## PASSO 8 — Backup notturno del registro (consigliato)

DSM → **Pannello di controllo → Utilità di pianificazione → Crea → Attività
pianificata → Script definito dall'utente**. Utente `root`, ogni giorno alle 03:00,
comando:
```
sh /volume1/docker/Pactum-NAS/server/scripts/backup-registro.sh
```
(adatta `Pactum-NAS` se hai usato un altro nome). Per una copia off-site, con
**Hyper Backup** replica la cartella `server/backup/` altrove.

---

## Fatto! Poi: il test finale

Quando i Passi 1–7 sono a posto e le app dicono "il server risponde", siamo pronti
per la **prova vera**: dai telefoni, **staccati dal WiFi di casa** (rete dati),
tutto il giro del patto (crea regola, sforamento, proposta, bonus, digest). La lista
completa è in [collaudo-telefono.md](collaudo-telefono.md). Fammi sapere l'URL del
tunnel e la facciamo insieme.

## Se qualcosa non torna
Incollami: (a) l'errore che vedi in Container Manager, oppure (b) le ultime righe di
log del container `pactum` (Container → pactum → Log). Lo sbroglio al volo.
