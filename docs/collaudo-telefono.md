# Pactum — Collaudo sui telefoni veri (guida passo-passo)

Tutte e 6 le tappe sono costruite, revisionate e committate. Server: 275 test verdi. APK di release **firmati** (versione 0.3.0, stesso certificato per le due app) pronti in `server/apk/`. Questa guida serve a provarli su telefoni Motorola veri.

## Cosa ti serve
- Il PC (dove gira il server) e i telefoni **sulla stessa rete WiFi**.
- Un telefono per il **figlio**, uno per il **genitore** (o due qualsiasi per provare).

## Passo 1 — Accendi il postino sul PC
Da terminale, nella cartella `server`:
```
.venv\Scripts\python -m uvicorn --factory app.main:create_app --host 0.0.0.0 --port 8000
```
`--host 0.0.0.0` = il server è raggiungibile dagli altri dispositivi della rete (non solo dal PC).
Trova l'IP del PC nella rete: `ipconfig` → "Indirizzo IPv4" (es. `192.168.1.x`). L'indirizzo del server per i telefoni sarà `http://192.168.1.x:8000`.

> Nota: gli APK di release parlano **solo in https** (il cleartext è disattivato). Per il collaudo in rete locale su http hai due strade: (a) installare gli **APK di debug** (`app-*/app/build/outputs/apk/debug/`, che permettono http in locale) puntando a `http://<IP>:8000`; (b) oppure esporre il server in https col tunnel Cloudflare (come farà il NAS) e usare quell'URL con gli APK di release. Per un primo collaudo veloce, la strada (a) è la più semplice.

## Passo 2 — Scarica le app dal browser del telefono
Apri sul telefono: `http://192.168.1.x:8000/scarica` (con gli APK di debug servi tu i file, oppure usa i link diretti). La pagina spiega quale app installa chi. In alternativa copia gli APK via cavo.
- Telefono **figlio** → `pactum-figlio.apk`
- Telefono **genitore** → `pactum-genitore.apk`

Durante l'installazione, il telefono chiederà di consentire l'installazione da "origini sconosciute" per il browser: è normale (è la scelta di distribuzione fuori store). Play Protect può mostrare "app sconosciuta": premi "Installa comunque".

## Passo 3 — Configura l'app del figlio (la parte più delicata)
1. Apri Pactum sul telefono del figlio → l'onboarding chiede tre permessi.
2. **Accesso ai dati di utilizzo**: qui scatta il percorso Android 15/16. Se il toggle è grigio con "impostazione con limitazioni", vai in **Info app → menu ⋮ (tre puntini) → "Consenti impostazioni con limitazioni"**, poi torna e attivalo. L'app ti guida, ma è IL punto dove serve attenzione.
3. Concedi notifiche ed esenzione batteria.
4. **Impostazioni** dell'app → indirizzo server `http://192.168.1.x:8000` + il token del figlio (di default `dev-token-figlio`; in produzione lo cambierai). Premi **"Prova adesso"** → deve dire "battito consegnato".
5. L'onboarding chiede la **prima regola** (obbligatoria): creane una, es. limite di tempo su un'app.

## Passo 4 — Configura l'app del genitore
Impostazioni → stesso indirizzo server + token del genitore (`dev-token-genitore`) → "Prova adesso". Poi gira tra Finestra, Tempo, Notifiche.

## Passo 5 — Prova il patto (la lista del collaudo)
- [ ] **Misura**: usa qualche app sul telefono del figlio; entro ~15 min (o riavviando l'app) i minuti compaiono in "Oggi" e nella sezione **Tempo** del genitore.
- [ ] **Sforamento**: dai un limite basso, superalo → il figlio riceve l'avviso gentile, il genitore lo vede nella finestra (semaforo rosso quel giorno).
- [ ] **Bonus**: il figlio si dà +15 su una regola → il genitore riceve la notifica e vede il bonus.
- [ ] **Lock 4 giorni**: prova ad allentare una regola appena creata → deve dire "potrai allentarla tra N giorni". Stringerla invece è immediato.
- [ ] **Proposta**: il genitore propone una modifica → il figlio vede il confronto ("−30 min rispetto ad ora") e accetta/rifiuta; se accetta, la regola cambia subito.
- [ ] **Vita reale**: crea una regola "cammino 1h", il figlio dichiara successo → il genitore conferma (anche "per conto di").
- [ ] **Manomissione/silenzio**: chiudi forzatamente l'app del figlio o togli l'accesso all'uso → dopo ~45 min il genitore vede il silenzio / l'evento.
- [ ] **Digest**: imposta l'ora del digest vicina all'ora attuale → arriva la notifica col totale e le prime app.
- [ ] **Uso di tutte le app** (richiesta del padre): la sezione Tempo mostra TUTTE le app, non solo quelle con limiti, col limite accanto dove c'è.

## Dopo il collaudo
- **Server sul NAS**: quando il collaudo va, si sposta il postino sul NAS (Docker + tunnel Cloudflare) così il patto funziona anche fuori casa e con gli APK di release in https. Il `Dockerfile` è pronto.
- **Token veri**: cambia `PACTUM_TOKEN_FIGLIO` / `PACTUM_TOKEN_GENITORE` (variabili d'ambiente del server) con valori segreti.
- **Chiave di firma**: `<cartella delle chiavi, fuori dal repo>` va copiata in un posto sicuro — persa quella, niente più aggiornamenti.
- **Push istantaneo**: oggi le notifiche arrivano col controllo ogni 15 min (polling). Se vuoi l'istantaneo, è la scelta FCM (Google) o ntfy (sul NAS) rimasta aperta.
