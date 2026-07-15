# Compilare l'app del genitore

Stessa toolchain dell'app del figlio, già pronta su questa macchina:
Gradle 8.9 (wrapper) · AGP 8.7.3 · Kotlin 2.0.21 · compileSdk/targetSdk 35 · minSdk 26.

## Compilare

In `app-genitore\` (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

APK risultante: `app\build\outputs\apk\debug\app-debug.apk`.

- L'SDK è in `%LOCALAPPDATA%\Android\Sdk` (v. `local.properties`, non nel repo).
- La build **debug** permette HTTP in chiaro (test contro il NAS in LAN); la
  **release** parla solo https (tunnel Cloudflare).
- Firma release (più avanti): stessa chiave per sempre, `versionCode` sempre
  crescente, keystore MAI nel repo (v. architettura.md).

## Installare sul telefono

- Con cavo: `adb install app\build\outputs\apk\debug\app-debug.apk`
- Oppure copiare l'APK sul telefono e aprirlo (sideload). Nessun permesso
  speciale: solo notifiche (richieste alla prima apertura su Android 13+).
