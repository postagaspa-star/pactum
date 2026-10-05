import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firma di release (tappa 6). La chiave di Pactum vive FUORI dal repo e da
// OneDrive (C:\Users\andre\pactum-keys): mai versionata, la stessa per sempre.
// Se il file manca (altra macchina, CI), la release resta non firmata ma la
// build NON si rompe: le build di debug restano intatte ovunque.
val keystorePropsFile = file("C:/Users/andre/pactum-keys/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "eu.stgm.pactum.figlio"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.stgm.pactum.figlio"
        minSdk = 26
        targetSdk = 35
        // 0.9: sentinella ogni minuto a schermo acceso, avviso a tutto schermo,
        // limite su tutto il telefono. 0.10: le proposte del figlio al genitore
        // (contratto v3.4). 0.11: le Sessioni (contratto v3.5). 0.12: i
        // preavvisi "il tempo sta per finire" e le pagine animate delle
        // sessioni (nessun cambio al server). 0.13: le faccende e il loro
        // blocco (contratto v3.6). 0.14: la sospensione allo spegnimento e il
        // battito anche in stand-by, "lavori di casa" nei testi (contratto
        // v3.7). 0.15: il riordino dell'interfaccia (4 schede fisse, componenti
        // comuni), comportamento invariato. Nuova funzione = nuovo versionCode,
        // altrimenti l'auto-aggiornamento non la propone.
        // 0.16 (contratto v3.8): avviso del tempo finito a 30 su 30, fasce con lo
        // stato, il tempo dei giorni passati e i totali dal server.
        versionCode = 16
        versionName = "0.16.0"
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
    // Solo per i test: il fotografo delle schermate (Robolectric) legge le
    // risorse dell'app (stringhe, icone). L'APK non cambia.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    // Test JVM (nessun Android): la lettura dei pacchetti DNS e il filtro dei
    // domini sono logica pura, ed è la parte dove un errore si vedrebbe come
    // "internet rotto" o "registro sempre vuoto". Va provata.
    testImplementation(libs.junit)
    // Il fotografo delle schermate: Compose disegnato sul PC (Robolectric con la
    // grafica vera) e salvato in PNG (Roborazzi). Solo test, mai nell'APK.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
}

// Il fotografo (src/test/.../fotografo) NON gira con la suite normale: è lento
// e scarica Android per Robolectric. Si lancia a parte con -Pfotografo
// (oppure -Pfotografo=<cartella dei PNG>; senza cartella: build/fotografo).
// Con -Pfotografo girano SOLO i suoi test; -Pfotografo.solo=<pezzo di nome>
// rifà solo le foto il cui nome lo contiene (un'espressione regolare).
val fotografo: String? = providers.gradleProperty("fotografo").orNull
tasks.withType<Test>().configureEach {
    if (fotografo != null) {
        filter { includeTestsMatching("eu.stgm.pactum.figlio.fotografo.*") }
        val cartella = fotografo.takeIf { it.isNotBlank() && it != "true" }
            ?: layout.buildDirectory.dir("fotografo").get().asFile.absolutePath
        systemProperty("fotografo.cartella", cartella)
        // (0.15) -Pfotografo.solo=<pezzo di nome>: rifà solo una parte delle foto.
        providers.gradleProperty("fotografo.solo").orNull?.let { systemProperty("fotografo.solo", it) }
        systemProperty("roborazzi.test.record", "true")
        maxHeapSize = "4g"
        outputs.upToDateWhen { false }
    } else {
        exclude("eu/stgm/pactum/figlio/fotografo/**")
    }
}
