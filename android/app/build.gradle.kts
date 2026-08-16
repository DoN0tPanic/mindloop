plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Il file viene letto a mano invece che con java.util.Properties.
//
// In un .properties la barra rovesciata e' un carattere di escape: un
// percorso Windows incollato com'e' da Esplora file, tipo
// C:\Users\me\mindloop.jks, viene silenziosamente ridotto a C:Usersme...
// e la build fallisce lamentando un file inesistente dal nome incomprensibile.
// Chi firma la sua app la prima volta non ha nessun motivo di sospettare una
// regola di escape: si legge riga per riga e il percorso resta quello scritto.
val releaseKeystoreProperties: Map<String, String> = rootProject.file("keystore.properties")
    .takeIf { it.isFile }
    ?.readLines()
    ?.mapNotNull { riga ->
        val pulita = riga.trim()
        if (pulita.isEmpty() || pulita.startsWith("#")) return@mapNotNull null
        val separatore = pulita.indexOf('=')
        if (separatore <= 0) return@mapNotNull null
        pulita.substring(0, separatore).trim() to pulita.substring(separatore + 1).trim()
    }
    ?.toMap()
    .orEmpty()

val hasReleaseKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { releaseKeystoreProperties[it]?.isNotBlank() == true }

android {
    namespace = "com.local.spacedcards"
    compileSdk = 36

    signingConfigs {
        // La build di debug usa la chiave standard che Android genera da se'
        // in ~/.android/debug.keystore. Prima puntava a un file dentro il
        // progetto che, essendo una chiave, resta fuori dal repository: chi
        // clonava non riusciva a compilare. Nessuna chiave nel repository,
        // e "git clone && gradlew assembleDebug" funziona ovunque.
        create("release") {
            if (hasReleaseKeystore) {
                val percorso = releaseKeystoreProperties.getValue("storeFile")
                // Un percorso assoluto va usato com'e': risolverlo comunque
                // dentro il progetto produceva nomi assurdi e messaggi
                // d'errore che non aiutavano a capire cosa fosse sbagliato.
                val chiave = File(percorso).let { if (it.isAbsolute) it else rootProject.file(percorso) }
                require(chiave.isFile) {
                    "Chiave di firma non trovata: ${chiave.absolutePath}\n" +
                        "Controlla 'storeFile' in android/keystore.properties. " +
                        "Su Windows scrivi il percorso con le barre in avanti " +
                        "(C:/Users/tuonome/mindloop-release.jks)."
                }
                storeFile = chiave
                storePassword = releaseKeystoreProperties.getValue("storePassword")
                keyAlias = releaseKeystoreProperties.getValue("keyAlias")
                keyPassword = releaseKeystoreProperties.getValue("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.local.spacedcards"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("keystore.properties assente: la build di rilascio usera' la firma di debug e NON e' distribuibile")
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.core:core:1.16.0")

    // `collection.anki21b` e' compresso con zstd: su Android usiamo la build
    // nativa di `zstd-jni`, perche' `aircompressor` dipende da parti di
    // `sun.misc.Unsafe` che l'ART non espone.
    //
    // Coordinata col classifier `@aar`, non il jar nudo: il jar nudo contiene
    // solo i .so desktop/server (linux/darwin/win, layout non Android). Le
    // build arm64-v8a/armeabi-v7a/x86/x86_64 stanno nell'AAR separato, ed e'
    // l'AAR che AGP sa impacchettare sotto lib/<abi>/ nell'APK.
    implementation("com.github.luben:zstd-jni:1.5.6-6@aar")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kapt {
    correctErrorTypes = true
}
