import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firma di release (tappa 6). La chiave di Pactum vive FUORI dal repo e da
// OneDrive (C:\Users\andre\pactum-keys): mai versionata, la stessa per sempre —
// è la STESSA chiave dell'app del figlio (un solo keystore per tutto il progetto).
// Se il file manca (altra macchina, CI), la release resta non firmata ma la
// build NON si rompe: le build di debug restano intatte ovunque.
val keystorePropsFile = file("C:/Users/andre/pactum-keys/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "eu.stgm.pactum.genitore"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.stgm.pactum.genitore"
        minSdk = 26
        targetSdk = 35
        // Nuova funzione = nuovo versionCode, altrimenti l'auto-aggiornamento
        // non la propone. v0.10.0: le proposte del figlio (contratto v3.4) — il
        // genitore le accetta (valgono subito) o le rifiuta, e ritira le sue.
        // v0.11.0: le Sessioni (contratto v3.5) — il genitore approva (o no) le
        // sessioni del figlio e ogni loro cambio, e vede quelle fatte.
        // v0.12.0: accanto al nome di ogni sessione la prima emoji del suo tema
        // (TemaSessione in core-design, come nell'app del figlio). Le due app
        // viaggiano sempre alla stessa versione.
        // v0.13.0: più genitori e le faccende (contratto v3.6) — un genitore si
        // collega con un codice di 6 cifre, la famiglia ha i suoi genitori, e si
        // danno faccende al figlio (con la foto da guardare e, entro 24 ore, da
        // bocciare).
        versionCode = 13
        versionName = "0.13.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig =
                if (keystorePropsFile.exists()) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // Il design system condiviso (redesign C5): una cartella sorgente comune
    // alle due app, non un modulo — le due app sono build Gradle separate.
    sourceSets["main"].kotlin.srcDir("../../core-design/src/main/kotlin")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Test JVM (nessun Android): cosa sale in cima alla finestra, in che ordine
    // stanno le voci del Tempo e cosa dice la riga di riepilogo sono logica
    // pura — ed è lì che un errore farebbe dire all'app una cosa falsa sul patto.
    testImplementation(libs.junit)
}
