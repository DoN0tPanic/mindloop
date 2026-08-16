// Versioni dei plugin Kotlin/Android centralizzate qui e applicate senza
// versione nei sottoprogetti (:core, :app). Con "apply false" il plugin non
// gira sul progetto radice, ma viene risolto una sola volta invece che una
// volta per sottoprogetto -- e' quello che eliminava il warning di Gradle
// "The Kotlin Gradle plugin was loaded multiple times in different
// subprojects".
plugins {
    id("com.android.application") version "8.11.1" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("jvm") version "2.1.21" apply false
    kotlin("kapt") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
