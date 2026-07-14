# Compilare l'app del figlio

Su questa macchina mancano JDK e Android SDK: questi sono i passi per la prima build.
Versioni bloccate nel progetto: Gradle 8.9 · AGP 8.7.3 · Kotlin 2.0.21 · compileSdk/targetSdk 35 · minSdk 26.

## 1. Strumenti (una volta sola)

1. **JDK 17** (Temurin): <https://adoptium.net> → installare, poi verificare con `java -version`.
2. **Android command-line tools**: <https://developer.android.com/studio#command-line-tools-only>
   → scompattare in `C:\Android\cmdline-tools\latest` (dentro `latest` deve esserci `bin`).
3. Variabili d'ambiente:
   - `ANDROID_HOME = C:\Android`
   - al `PATH` aggiungere `%ANDROID_HOME%\cmdline-tools\latest\bin` e `%ANDROID_HOME%\platform-tools`
4. Pacchetti SDK e licenze (PowerShell):

   ```powershell
   sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   sdkmanager --licenses    # accettare tutto
   ```

## 2. Gradle wrapper (una volta sola)

Il `gradle-wrapper.jar` non si può generare senza Gradle, quindi nel repo c'è solo
`gradle-wrapper.properties`. Installare Gradle 8.9+ (es. `scoop install gradle`, oppure
zip da gradle.org), poi in `app-figlio\`:

```powershell
gradle wrapper --gradle-version 8.9
```

Genera `gradlew.bat`, `gradlew` e `gradle\wrapper\gradle-wrapper.jar` (vanno committati).
Da qui in poi si usa solo il wrapper: il Gradle installato non serve più.

## 3. Compilare

In `app-figlio\`:

```powershell
.\gradlew.bat assembleDebug
```

APK risultante: `app\build\outputs\apk\debug\app-debug.apk`.
Se l'SDK non viene trovato: creare `app-figlio\local.properties` con `sdk.dir=C\:\\Android`.

## 4. Installare sul telefono

- Con cavo: `adb install app\build\outputs\apk\debug\app-debug.apk`
- Oppure copiare l'APK sul telefono e aprirlo (sideload). Play Protect può avvisare
  alla prima installazione: scegliere "installa comunque" (v. architettura.md).

## Note

- La build **debug** permette HTTP in chiaro (test contro il NAS in LAN); la **release**
  parla solo https, quindi serve l'URL del tunnel Cloudflare.
- Firma release (più avanti): stessa chiave per sempre, `versionCode` sempre crescente,
  keystore MAI nel repo (v. architettura.md, sezione "Versioni e firma").
