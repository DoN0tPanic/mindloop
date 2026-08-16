import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SpacedCards"

include(":core")

fun configuredSdkPath(): String? {
    val envSdk = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
    if (!envSdk.isNullOrBlank()) {
        return envSdk
    }
    val localProperties = file("local.properties")
    if (!localProperties.isFile) {
        return null
    }
    val properties = Properties()
    localProperties.inputStream().use(properties::load)
    return properties.getProperty("sdk.dir")
}

val requestedTasks = gradle.startParameter.taskNames
val coreOnlyTasks = requestedTasks.isNotEmpty() && requestedTasks.all { task ->
    val normalized = task.removePrefix(":")
    normalized == "core" ||
        normalized.startsWith("core:") ||
        normalized == "clean" ||
        normalized == "help"
}

if (configuredSdkPath() != null || !coreOnlyTasks) {
    include(":app")
} else {
    println("Skipping :app because the Android SDK is not configured and only :core tasks were requested.")
}
