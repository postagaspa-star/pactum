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

## TODO — Fase 3 del piano: Tunnel Cloudflare (da definire con Andrea)

> **Non ancora implementato.** Finora il postino e' raggiungibile solo dentro la
> rete di casa (`http://IP-DEL-NAS:8000`). Per farlo arrivare ai telefoni fuori
> casa serve un **Cloudflare Tunnel** (quick tunnel gratuito o named tunnel per un
> URL stabile). Da decidere e documentare qui:
> - se `cloudflared` gira come container accanto a `pactum` o come pacchetto sul NAS;
> - hostname/URL pubblico del tunnel (named tunnel = URL fisso, consigliato);
> - che il tunnel punti a `http://pactum:8000` (rete Docker) o `http://IP-NAS:8000`.
>
> Non inventare qui i dettagli del tunnel finche' non e' scelto il metodo.

## TODO — Fase 4 del piano: indirizzo del server nelle app

> **Non ancora implementato.** Una volta fissato l'URL pubblico (Fase 3), va
> "cablato" nelle due app Android (base URL delle chiamate) e vanno inseriti i due
> token generati nella Fase 1 (figlio nell'app-figlio, genitore nell'app-genitore).
> Documentare qui il punto di configurazione e la procedura di ri-configurazione se
> l'URL cambia.
