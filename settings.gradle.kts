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
        // Air browser engine (GeckoView) – Mozilla's own repository.
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "AirCursor"
include(":app")
