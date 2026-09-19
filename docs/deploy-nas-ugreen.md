# Pactum sul NAS Ugreen — guida definitiva (indirizzo fisso, sempre acceso)

Obiettivo: il postino gira **sul NAS, 24 ore su 24**, con un indirizzo pubblico
**che non cambia mai**. Niente più tunnel che cadono, niente più indirizzi da
re-incollare nelle app.

Costo: **zero**. Nessuna porta aperta sul router. Il registro del patto resta a
casa tua, non passa da nessun fornitore esterno.

---

## Come funziona (in due righe)

Sul NAS girano **due container**: `pactum` (il server) e `pactum-tailscale`.
Il secondo dà al primo un indirizzo pubblico https stabile, tipo
`https://pactum.tuo-nome.ts.net`, senza toccare il router.

---

## PASSO 1 — Crea l'account Tailscale (2 minuti, gratis)

1. Vai su **https://login.tailscale.com** e accedi con Google (o GitHub).
   Il piano gratuito personale basta e avanza.
2. Vai su **https://login.tailscale.com/admin/settings/keys**
3. **Generate auth key** → attiva **Reusable** → lascia *Ephemeral* **spento** →
   **Generate key**.
4. Copia la chiave: comincia con `tskey-auth-...`. **Tienila da parte**, la
   incolli al PASSO 3. (Se la perdi, ne generi un'altra: nessun dramma.)

## PASSO 2 — Abilita Funnel sul tuo account

Funnel è la funzione che rende il NAS raggiungibile da Internet.

1. Vai su **https://login.tailscale.com/admin/acls/file**
2. Seleziona tutto il contenuto dell'editor (Ctrl+A) e sostituiscilo con questo file
   completo (è la policy di default di Tailscale con in più il blocco `nodeAttrs`;
   `grants` e `ssh` sono identici a quelli che c'erano):
   ```
   {
   	"grants": [
   		{"src": ["*"], "dst": ["*"], "ip": ["*"]},
   	],

   	"ssh": [
   		{
   			"action": "check",
   			"src":    ["autogroup:member"],
   			"dst":    ["autogroup:self"],
   			"users":  ["autogroup:nonroot", "root"],
   		},
   	],

   	// Permette ai dispositivi del tuo account di usare Funnel.
   	"nodeAttrs": [
   		{
   			"target": ["autogroup:member"],
   			"attr":   ["funnel"],
   		},
   	],
   }
   ```
3. **Save**. Se compare un errore rosso, copialo e mandamelo.

## PASSO 2-bis — Abilita HTTPS e MagicDNS (obbligatorio per Funnel)

1. Vai su **https://login.tailscale.com/admin/dns**
2. Controlla che **MagicDNS** sia attivo (di solito lo è già).
3. Nella sezione **HTTPS Certificates** premi **Enable HTTPS** e conferma.
   (Il nome `pactum.<tuo-tailnet>.ts.net` finisce nel registro pubblico dei
   certificati: è normale, non contiene nulla del patto.)

> Se al PASSO 6 il log dice che Funnel o HTTPS non sono abilitati, manca uno di
> questi due passi.

## PASSO 3 — Prepara i file sul PC

Nella cartella `Pactum-NAS` sul Desktop (quella che porterai sul NAS):

1. Entra in `server`, copia `.env.example` e rinomina la copia in **`.env`**
   (esattamente così, col punto davanti).
2. Aprila e compila **tre** righe:
   ```
   PACTUM_TOKEN_FIGLIO=<un token forte>
   PACTUM_TOKEN_GENITORE=<un altro token forte, diverso>
   TS_AUTHKEY=<la chiave tskey-auth-... del PASSO 1>
   ```
   I due token li generi sul PC con questo comando (lanciato due volte):
   ```
   python -c "import secrets; print(secrets.token_urlsafe(32))"
   ```
   > Se le app sono già configurate coi token attuali, **riusa quelli** invece di
   > generarne di nuovi: così non devi riconfigurare i telefoni.
3. Lascia tutto il resto com'è.

## PASSO 4 — Porta la cartella sul NAS

Con l'app Ugreen (o File Station via browser), crea una cartella condivisa
**`docker`** se non c'è, e copiaci dentro l'**intera cartella `Pactum-NAS`**.

Risultato atteso sul NAS:
```
docker/Pactum-NAS/docker-compose.yml
docker/Pactum-NAS/server/...
docker/Pactum-NAS/tailscale/funnel.json
```

## PASSO 5 — Avvia i container (UGOS)

1. Apri l'app **Docker** (o *Container*) sul NAS.
2. Cerca la sezione **Progetto** / **Project** / **Compose** → **Crea**.
3. **Percorso**: seleziona la cartella `docker/Pactum-NAS` (quella che *contiene*
   `docker-compose.yml`).
4. Conferma. La prima volta costruisce l'immagine: qualche minuto.
5. Devono risultare **due container attivi**: `pactum` e `pactum-tailscale`.

> **Se UGOS non ha la funzione "Progetto/Compose"** (alcune versioni hanno solo
> l'avvio di singoli container), dimmelo: si fa via SSH con un comando solo, oppure
> creo io una procedura alternativa a container singoli.

## PASSO 6 — Trova il tuo indirizzo fisso

1. Torna su **https://login.tailscale.com/admin/machines**
2. Deve comparire una macchina chiamata **`pactum`**.
3. Il suo indirizzo pubblico è: **`https://pactum.<tuo-tailnet>.ts.net`**
   (il pezzo centrale te lo mostra la console; è una cosa tipo `tuonome.ts.net`).

**Verifica**: apri dal telefono in **rete dati** (WiFi spento):
```
https://pactum.<tuo-tailnet>.ts.net/api/salute
```
Deve rispondere `{"stato":"ok",...,"db_ok":true}`.

> Questo indirizzo **non cambierà mai più**, nemmeno se riavvii NAS o container.

## PASSO 7 — Metti l'indirizzo nelle app (l'ultima volta)

In **entrambe** le app: **Impostazioni → Indirizzo del server** → incolla
`https://pactum.<tuo-tailnet>.ts.net` → **Salva** → **Prova adesso**.

I token restano quelli del `.env`.

## PASSO 8 — Backup del registro (consigliato)

Il registro è il cuore del patto. Se il NAS ha un'utilità di pianificazione,
schedula ogni notte:
```
sh /volume1/docker/Pactum-NAS/server/scripts/backup-registro.sh
```
(adatta il percorso a come il tuo NAS chiama i volumi). Le copie finiscono in
`server/backup/`; con l'app di backup del NAS mandane una copia fuori casa.

---

## Da qui in poi

- **Il NAS resta acceso**: le app funzionano sempre, ovunque, anche fuori casa.
- **L'indirizzo non cambia mai**: nessuna riconfigurazione, mai più.
- **Aggiornare l'app in futuro**: ricompilo gli APK, li copi in `server/apk/`,
  e i telefoni si aggiornano da soli (o dalla pagina `/scarica`).
- **Aggiornare il server**: nell'app Docker del NAS, ricostruisci il progetto.
  Il volume `pactum-data` NON viene toccato: il registro sopravvive.

## Se qualcosa non va

- **Il container `pactum` si spegne subito** → i token nel `.env` non sono a
  posto (deboli, uguali, o rimasti quelli d'esempio). È voluto: meglio non
  partire che partire aperto.
- **`pactum-tailscale` non compare tra le macchine** → la `TS_AUTHKEY` è
  sbagliata o scaduta: generane un'altra (PASSO 1).
- **L'indirizzo `.ts.net` non risponde da fuori** → manca il PASSO 2 (Funnel non
  abilitato). Guarda il log del container `pactum-tailscale`: lo dice.
- **Dentro casa funziona ma fuori no** → di solito è il PASSO 2.
- **Il container `pactum` non parte e l'indirizzo dà 502** → la porta dell'host è
  già occupata da un'altra app del NAS (sul nostro la 8000 è di NormaAI: per
  questo Pactum usa la **8100** per le verifiche in casa, `http://IP-NAS:8100`).
- **Da fuori dà `ERR_CONNECTION_CLOSED` e nel log di `pactum-tailscale` non
  arriva nulla** → succede dopo aver eliminato e ricreato la macchina con lo
  stesso nome: la porta d'ingresso di Tailscale ricorda quella vecchia.
  **Riavvia il container `pactum-tailscale`** (solo riavvio) e passa.
- **Mai cancellare i volumi** quando elimini o ricrei il progetto: in
  `pactum-data` c'è il registro del patto, in `tailscale-state` l'identità del
  NAS (cancellarla = macchina nuova, a volte con un indirizzo diverso).

Incollami il log del container che fa i capricci e lo sbroglio.
