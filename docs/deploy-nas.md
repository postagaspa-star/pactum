# Pactum — deploy del postino sul NAS Synology

Runbook operativo per mettere il server "postino" in produzione sul NAS Synology
con **Container Manager** (l'interfaccia Docker di DSM 7). Passo-passo, con i clic.

Il postino e' un container che:
- tiene il **registro della famiglia** in un database SQLite su un volume persistente;
- serve le API alle due app (figlio/genitore) e la pagina `/scarica` con gli APK;
- in produzione **rifiuta di partire con token deboli** (vedi `PACTUM_ENV=prod`).

> Prerequisiti: NAS Synology con **Container Manager** installato (Centro pacchetti),
> accesso admin a DSM, la cartella del progetto `Pactum/` copiata sul NAS (es. in
> una cartella condivisa `docker/pactum`). I file che servono sono gia' nel repo:
> `docker-compose.yml` (radice), `server/Dockerfile`, `server/.env.example`.

---

## Fase 1 — Preparare i token (sul PC, una volta)

I token sono l'unica barriera tra Internet e il registro. Devono essere **forti**
(>= 24 caratteri) e **distinti**. Generane due nuovi:

```bash
python -c "import secrets; print(secrets.token_urlsafe(32))"
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

Ognuno esce ~43 caratteri. Tienili da parte: uno sara' del **figlio**, l'altro del
**genitore**. Serviranno anche quando si configurano le due app (Fase 4).

## Fase 2 — Creare il file `.env` (segreti, mai nel repo)

1. Copia `server/.env.example` in `server/.env` (stessa cartella).
2. Aprilo e incolla i due token generati:
   ```
   PACTUM_ENV=prod
   PACTUM_TOKEN_FIGLIO=<primo token generato>
   PACTUM_TOKEN_GENITORE=<secondo token generato>
   PACTUM_DB=/data/pactum.db
   PACTUM_APK_DIR=/apk
   PACTUM_TIMEZONE=Europe/Rome
   ```
3. Salva. `server/.env` e' **gitignorato**: i token veri non finiscono nel repo.

> Se lasci un token di default o troppo corto, alla partenza il container muore
> subito con un errore chiaro (`PACTUM_ENV=prod ma la configurazione dei token non
> e' sicura...`). E' voluto: meglio non partire che partire aperto.

## Fase 3 — Mettere gli APK firmati (opzionale ma consigliato)

La pagina `/scarica` serve i file da `server/apk/`. Copia li' gli APK firmati:
- `server/apk/pactum-figlio.apk`
- `server/apk/pactum-genitore.apk`

Se non ci sono ancora, `/scarica` mostra una pagina "non ancora disponibile" e le
API funzionano lo stesso; li aggiungerai dopo. (Il compose monta `./server/apk`
dentro il container su `/apk`.)

## Fase 4 — Costruire e avviare il container (Container Manager)

### Via interfaccia (consigliato per Synology)

1. Copia l'intera cartella `Pactum/` in una cartella condivisa del NAS
   (es. `/volume1/docker/pactum`), con dentro `docker-compose.yml`, `server/`.
2. **Container Manager -> Progetto -> Crea.**
3. Nome progetto: `pactum`. **Percorso:** la cartella che contiene
   `docker-compose.yml` (la radice `Pactum/`).
4. Sorgente: **"Usa docker-compose.yml esistente"** (Container Manager lo rileva).
5. Avanti fino a **Fatto**: Container Manager fa il build dell'immagine dal
   `server/Dockerfile` e avvia il servizio `pactum`.
6. Il volume **`pactum-data`** (il database) viene creato da Docker
   automaticamente; la cartella host `server/apk` e' montata su `/apk`.

### Via SSH (alternativa)

```bash
cd /volume1/docker/pactum          # dove sta docker-compose.yml
sudo docker compose up -d --build
```

## Fase 5 — Verificare che sia vivo

Dal browser (o da un altro PC in rete):

```
http://IP-DEL-NAS:8000/api/salute
```

Deve rispondere:

```json
{"stato": "ok", "versione": "0.1.0", "env": "prod", "db_ok": true}
```

- `env: prod` conferma che gira in produzione;
- `db_ok: true` conferma che il database sul volume `/data` e' scrivibile.

Controlla anche il **log** del container (Container Manager -> Container -> pactum
-> Dettagli -> Terminale/Log): all'avvio compare una riga tipo
`Pactum avvio — env=prod db_path=/data/pactum.db ... token_figlio=forte token_genitore=forte`.
Se leggi `token_figlio=dev-default` o `debole`, i token nel `.env` non sono a posto.

La sonda `HEALTHCHECK` del Dockerfile marca il container **healthy** solo quando
`/api/salute` risponde: in Container Manager lo stato del container deve diventare
verde ("healthy") entro ~mezzo minuto.

## Fase 6 — Aggiornamenti futuri

Quando cambia il codice del server:

```bash
cd /volume1/docker/pactum
sudo docker compose up -d --build      # ricostruisce e riparte; il volume dati resta
```

Il database in `pactum-data` **non** viene toccato dal rebuild: il registro
sopravvive agli aggiornamenti.

---

## Fase 7 — Tunnel Cloudflare gratuito (raggiungere i telefoni fuori casa)

Finora il postino e' raggiungibile solo dentro la rete di casa
(`http://IP-DEL-NAS:8000`). Il **quick tunnel Cloudflare** lo espone su Internet
via https **senza account, senza dominio, senza aprire porte sul router**. E' gia'
nel `docker-compose.yml` come secondo container `tunnel`: se hai avviato il
progetto, sta gia' girando.

> **Il patto (scelto da Andrea):** e' gratis, ma l'URL pubblico
> (`https://<parole-a-caso>.trycloudflare.com`) **cambia a ogni riavvio** del
> container `tunnel` (quindi a ogni reboot del NAS o `compose up`). Vivibile per una
> famiglia (il NAS sta quasi sempre acceso); quando vorrai un URL fisso baster un
> dominio (named tunnel) — upgrade di ~15 minuti.

### Leggere l'URL pubblico

Container Manager -> Container -> **pactum-tunnel** -> **Dettagli -> Log**. Cerca
una riga incorniciata tipo:

```
+--------------------------------------------------------------------------------------------+
|  Your quick Tunnel has been created! Visit it at:                                          |
|  https://calm-forest-1234.trycloudflare.com                                                |
+--------------------------------------------------------------------------------------------+
```

Quello e' l'**indirizzo del server** da mettere nelle due app. Da SSH, in un colpo:

```bash
sudo docker logs pactum-tunnel 2>&1 | grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' | tail -1
```

### Verifica

Apri da un telefono **in rete dati** (non WiFi di casa):
`https://<il-tuo-url>.trycloudflare.com/api/salute` -> deve rispondere
`{"stato":"ok",...,"db_ok":true}`. Se risponde, il tunnel e' vivo e i telefoni lo
raggiungono da ovunque.

### Ogni volta che l'URL cambia (dopo un riavvio)

1. Rileggi l'URL dai log di `pactum-tunnel` (sopra).
2. Nelle due app: **Impostazioni -> Indirizzo del server** -> incolla il nuovo URL
   -> **Salva** -> **Prova adesso** (deve dire "il server risponde"). Fatto.

Solo l'indirizzo cambia: **i token restano gli stessi** (non li rigeneri).

## Fase 8 — Mettere l'indirizzo e i token nelle app

1. Installa le app dai link della pagina **`/scarica`** (aprila dal browser del
   telefono all'URL del tunnel): `pactum-figlio.apk` sul telefono del figlio,
   `pactum-genitore.apk` su quello del genitore.
2. In ciascuna app: **Impostazioni** -> **Indirizzo del server** = l'URL del tunnel;
   **Token** = quello del **figlio** nell'app-figlio, del **genitore** in quella del
   genitore (i due generati nella Fase 1). **Salva -> Prova adesso**.
3. Nell'app del figlio completa l'onboarding (permessi + prima regola).

Da qui il patto e' operativo: vedi la lista di collaudo in
[collaudo-telefono.md](collaudo-telefono.md), stavolta **dai telefoni veri fuori
casa** (Fase 7 del piano generale).

> Nota sulla scelta "tunnel gratuito": l'indirizzo si configura a mano nelle app
> (non e' cablato nell'APK) proprio perche' l'URL del quick tunnel cambia. Con un
> dominio (URL fisso) si potrebbe un domani metterlo come default nell'app e far
> digitare alla famiglia solo il token — miglioria rimandata all'upgrade dominio.

## Fase 9 — Backup del registro e resilienza

**Il registro e' il prodotto:** se sparisce il database, sparisce la storia del
patto. Due difese.

### Backup automatico (una copia al giorno)

Nel repo c'e' `server/scripts/backup-registro.sh`: fa una copia **consistente** del
database (sicura anche mentre il postino gira) nella cartella `server/backup/`,
tenendo le ultime 30. Schedulalo:

1. **DSM -> Pannello di controllo -> Utilita' di pianificazione -> Crea -> Attivita'
   pianificata -> Script definito dall'utente.**
2. Utente: quello che ha accesso a Docker (di solito `root`); pianificazione:
   ogni giorno, es. alle 03:00.
3. Comando:
   ```bash
   sh /volume1/docker/pactum/server/scripts/backup-registro.sh
   ```
   (adatta il percorso se hai messo Pactum altrove).
4. **Off-site (consigliato):** con **Hyper Backup** copia periodicamente la cartella
   `server/backup/` su un altro NAS o un cloud, cosi' un guasto del NAS non porta via
   anche i backup. (Come per NormaAI: backup off-site.)

Per **ripristinare**: ferma il container, sostituisci il file nel volume
`pactum-data` con una copia di `server/backup/pactum-*.db` rinominata `pactum.db`,
riavvia. (In caso di bisogno chiedi: si fa in due comandi.)

### Riavvio automatico

- I container hanno `restart: unless-stopped`: se il postino o il tunnel si
  piantano, Docker li **riavvia da solo**.
- Perche' ripartano **dopo un reboot del NAS**, in Container Manager il progetto
  `pactum` deve avere l'avvio automatico attivo: **Container Manager -> Progetto ->
  pactum -> Impostazioni -> "Avvia il progetto all'avvio di Container Manager"**
  (attivo di default per i progetti; verificalo).
- Ricorda: dopo un reboot il **tunnel cambia URL** (vedi Fase 7) -> rileggilo dai
  log e aggiornalo nelle app.
