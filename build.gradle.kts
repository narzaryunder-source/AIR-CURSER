plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Sign-in (Firebase): only used when app/google-services.json exists (see docs/LOGIN-SETUP.md).
    id("com.google.gms.google-services") version "4.4.2" apply false
}
