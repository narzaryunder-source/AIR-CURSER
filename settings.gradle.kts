pluginManagement {
    repositories {
        google()
        // Google's copy of Maven Central first: Maven Central itself sometimes refuses CI with
        // "429 Too Many Requests".
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's copy of Maven Central first: Maven Central itself sometimes refuses CI with
        // "429 Too Many Requests".
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        // GeckoView (the Air browser engine) from Mozilla's own repository.
        maven("https://maven.mozilla.org/maven2/") { content { includeGroup("org.mozilla.geckoview") } }
        mavenCentral()
    }
}

rootProject.name = "AirCursor"
include(":app")
