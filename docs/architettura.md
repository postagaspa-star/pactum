# Pactum — Architettura

**Verificata il 14/07/2026 contro Android 15/16 (fonti: doc ufficiali Android + verifica web). Nessun "non fattibile".**

## I tre pezzi

1. **App del figlio** (`app-figlio/`, Android, Kotlin + Jetpack Compose, package `eu.stgm.pactum.figlio`)
   Misura l'uso delle app, custodisce le regole del patto (copia locale), registra sforamenti/bonus/dichiarazioni, rileva manomissioni locali, manda tutto al server. Invia un **heartbeat** (battito "sono viva") ogni ~15 minuti.

2. **App del genitore** (`app-genitore/`, Android, Kotlin + Compose, package `eu.stgm.pactum.genitore`)
   Sola lettura + due azioni: conferme arbitro (anche per conto di) e proposte di modifica (sempre con confronto vs valore attuale). Riceve notifiche push. Tappa 4.

3. **Server "postino"** (`server/`, FastAPI + SQLite, Docker sul NAS Synology, esposto via Cloudflare Tunnel)
   - **Fonte di verità:** regole vigenti, conteggio lock 4 giorni, contatori bonus, **orologio del server** (il trucco del cambio-ora del telefono muore qui).
   - **Registro fuori dal telefono:** disinstallare l'app non cancella la storia.
   - **Rilevamento silenzio:** gap nei battiti → evento manomissione + notifica. Force-stop e disinstallazione sono indistinguibili lato server: va bene così, distinguerli è una conversazione umana, non una API.

## Vincoli tecnici verificati (da rispettare nel codice)

### Misurazione uso (app figlio)
- `UsageStatsManager` senza root; permesso speciale `PACKAGE_USAGE_STATS` (toggle "Accesso ai dati di utilizzo").
- Calcolare il tempo da **`queryEvents()`** (eventi `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` accoppiati, attenzione al confine di mezzanotte), NON dai bucket `INTERVAL_DAILY` (divergono da Digital Wellbeing).
- Il sistema registra la storia d'uso da solo → **design retroattivo**: WorkManager periodico ~15 min rilegge gli eventi e sincronizza. Non dipendere da un'app sempre viva.
- FGS (servizio in primo piano) tipo **`specialUse`**: permessi `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` + property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; nessun timeout runtime; avviabile da `BOOT_COMPLETED`. Serve solo per il loop notifiche quasi-real-time; la misura vive nel worker periodico.

### Onboarding (attrito verificato, da guidare passo-passo nell'app)
- **Android 15/16 + APK da browser:** "Usage access" è bloccato da *restricted settings* (Enhanced Confirmation Mode). Percorso obbligato: tentare l'attivazione (fallisce con dialogo "impostazione non disponibile") → Info app → menu ⋮ → **"Consenti impostazioni con limitazioni"** (PIN/biometria) → riattivare. Il tentativo fallito è NECESSARIO: prima, la voce di sblocco non appare.
- Play Protect: possibile avviso alla prima installazione ("analizza app") con opzione "installa comunque".
- Esenzione batteria: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (nessuna policy Play si applica in sideload).
- `POST_NOTIFICATIONS` runtime (Android 13+).
- Android 16 "Advanced Protection" (opt-in) blocca il sideload → verificare sia spento sui telefoni.

### Battery killer OEM
- Peggiori: Huawei, Xiaomi/HyperOS, OnePlus, Samsung. **Motorola = fascia moderata** (telefoni della famiglia: tutti Motorola; figlio: Motorola Edge 50 Fusion) → corazza media: esenzione batteria + WorkManager come rete di sicurezza bastano quasi sempre; niente passi-per-marca estremi in v1.
- L'esenzione Doze NON protegge dai layer proprietari → il segnale affidabile resta il **gap di heartbeat sul server**. WorkManager non sopravvive al force-stop (confermato): nessun watchdog locale può rilevare il proprio force-stop.

### Orologio e tempo
- `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED`: esenti dalle restrizioni sui broadcast impliciti, dichiarabili nel manifest.
- Ora del server fa fede per i timestamp; `SystemClock.elapsedRealtime()` per gli intervalli locali. Attenzione: si azzera al reboot → marcare i reboot per non scambiarli per manomissioni.

### Push
- **FCM funziona per APK sideload** (basta Play services sul telefono). Dal server usare **HTTP v1 API** con service account (la legacy server key è morta nel 2024).
- Alternativa no-Google: ntfy/UnifiedPush self-hosted sul NAS (richiede app ntfy come distributor + esenzione batteria).
- Scelta rimandata alla tappa postino-notifiche; inclinazione: FCM per v1.

### Versioni e firma
- **targetSdk 35, minSdk 26.** (Android 16 blocca install con targetSdk < 24; target alto non impone nulla che danneggi Pactum.)
- Categorie app: `ApplicationInfo.category` è quasi sempre `UNDEFINED` → **mapping proprio pacchetto→categoria** (JSON ~50-100 app rilevanti, aggiornabile dal server).
- Firma: stessa chiave + `versionCode` crescente per sempre — **custodire il keystore, mai nel repo**. Self-update: `REQUEST_INSTALL_PACKAGES` + PackageInstaller session (non `ACTION_INSTALL_PACKAGE`, deprecato); update silenziosi possibili dal secondo aggiornamento.
- ⚠️ **2027:** developer verification Google in Italia — APK di sviluppatori non verificati bloccati anche in sideload sui dispositivi certificati. Tier gratuito ≤20 dispositivi ok per famiglia/test.

## Struttura repo

```
Pactum/
├── docs/            concept.md (fondazione) · architettura.md (questo file)
├── app-figlio/      app Android del figlio (Kotlin/Compose)
├── app-genitore/    app Android del genitore (tappa 4)
└── server/          postino FastAPI + SQLite (Docker sul NAS)
```

## Tappe

| # | Tappa | Contenuto | Stato |
|---|-------|-----------|-------|
| 1 | Fondamenta | repo, docs, scheletri | ✅ fatta (14/07) |
| 2 | Il cuore | app figlio misura uso + registro locale + battiti | ✅ costruita, compilata e **collaudata end-to-end su emulatore Android 15** (15/07): misura reale ok, battiti su entrambi i canali (FGS + worker), eventi uso_giornaliero consegnati e indicizzati, finestra corretta. Resta il collaudo su telefono reale (percorso restricted settings) |
| 3 | Il postino | heartbeat, registro remoto, rilevamento silenzio | server v1 pronto (**135 test verdi** dopo revisione adversariale: proposta a parametri esatti, bonus atomico, snapshot monotono, fuso del patto Europe/Rome) — da deployare sul NAS |
| 4 | Il binocolo | app genitore: finestra + notifiche | ✅ costruita, compilata, revisionata (8 fix) |
| 5 | Il patto completo | regole, sforamenti, bonus, proposte col confronto, dichiarazioni+arbitro | ✅ fatta e revisionata (server 249→ test, corse concorrenti chiuse, selettore app) |
| 6 | La corazza | manomissioni complete, firma release, download, self-update | ✅ fatta e revisionata (keystore fuori repo, APK firmati 0.3.0, /scarica) |
| + | Uso tutte le app + digest | richiesta del padre: uso_recente + digest giornaliero | ✅ fatta e revisionata |

Stato al 15/07/2026: **tutte le tappe costruite, revisionate (revisione adversariale a ogni tappa) e committate.** Server: 275 test verdi. APK di release firmati 0.3.0 (stesso certificato per le due app, chiave in `C:\Users\andre\pactum-keys` fuori dal repo). Resta il **collaudo su telefono reale** (guida in [collaudo-telefono.md](collaudo-telefono.md)) e, per Andrea: deploy sul NAS, token di produzione, custodia della chiave, scelta push istantaneo. Nota onesta: il flusso completo del patto (regole→sforamenti→proposte→dichiarazioni→digest) è coperto da unit test del server + build verdi + revisione, ma non è stato guidato end-to-end tra le due app dal vivo dopo la tappa 2 — il collaudo sul telefono è esattamente quella prova.

Nota di build (14/07): toolchain = JBR di Android Studio (`C:\Program Files\Android\Android Studio\jbr`, OpenJDK 21) + SDK in `%LOCALAPPDATA%\Android\Sdk`; `gradlew.bat assembleDebug` con `JAVA_HOME` puntato al JBR. Prima build verde al primo colpo. Nota Doze: il canale primario dei battiti è il FGS (il worker WorkManager è misuratore + backstop) — deciso dopo revisione, per evitare falsi "silente" notturni.

## Convenzioni
- Termini di dominio in italiano nel codice (regola, sforamento, bonus, patto, finestra, arbitro); plumbing tecnico in inglese.
- Commenti solo dove il codice non può spiegarsi da solo.
- Il server non si fida MAI dell'orologio del telefono: ogni evento ha `ts_device` (informativo) e `ts_server` (fa fede).
