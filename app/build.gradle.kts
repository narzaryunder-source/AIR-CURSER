import java.net.URI


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Sign-in turns on when the owner adds app/google-services.json (Firebase project). Without it the
// app still builds and people continue as a guest.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// CI passes the run number so every APK has a higher versionCode (installs as an update).
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.aircursor"
    // Android 17 SDK (37.2): needed by the Air browser engine (GeckoView). Built-in Kotlin (AGP 9).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.aircursor"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Phones only (no x86 emulator libraries): keeps the APK smaller.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // A fixed debug key is committed so every CI build can be installed over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress.addAll(listOf("task", "tflite"))
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // The Cloud PC engine (proot) must exist as real files in the native library folder:
            // that is the only place Android 10+ lets an app start programs from.
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libproot.so", "**/libproot-loader.so", "**/libtalloc.so", "**/libandroid-shmem.so",
                "**/libvirgl_server.so", "**/libvirglrenderer.so", "**/libepoxy.so",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric: real Android views in unit tests (touch routing of the remote pointer).
        unitTests.isIncludeAndroidResources = true
    }
}

// The built-in Cloud PC uses the same desktop tools as the Termux setup (App Store, Windows layout…):
// copy them from ubuntu/ into the APK's assets.
val copyCloudPcTools by tasks.registering(Copy::class) {
    from(rootProject.file("ubuntu")) {
        include(
            "app-store.sh", "app-store.py", "ubuntu-extras.sh", "install-deb.sh", "windows-layout.sh", "wallpaper-gui.sh",
            "launch.sh", "apps-setup.sh", "style.sh", "aircursor-wallpaper.svg", "airstore.py", "airstore.html",
            "aircursor-logo.svg", "air-logo.svg", "web-icons.py", "aircursor-city.jpg", "aircursor-start.svg", "vscode.sh", "shell-index.py", "airmac/xfwm4/*",
        )
    }
    // TS Station (the game console) ships with the app now, for everyone.
    from(rootProject.file("tsstation")) {
        include("server.py", "ts-station", "ts-station.svg", "VERSION", "ui/*")
        into("tsstation")
    }
    into(layout.buildDirectory.dir("generated/cloudpc-assets/cloudpc"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/cloudpc-assets").get().asFile)
tasks.named("preBuild") { dependsOn(copyCloudPcTools) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.documentfile:documentfile:1.0.1")
    // Unpacks the ready-made AirCursor PC (tar.xz).
    implementation("org.tukaani:xz:1.9")

    // Air browser: Mozilla's GeckoView engine (arm64 phones; same phones as AirCursor PC). A full browser
    // engine, so Google sign-in works – unlike Android's in-app WebView. Keep it current (security).
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:159.0.20261022150525")

    // Sign-in: Firebase Authentication (Google + phone OTP) and Android's Credential Manager.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Robolectric's Android jar (200 MB, only for unit tests), downloaded once from Google's Maven
// Central mirror; tests then run offline from build/robolectric.
val robolectricJar = "android-all-instrumented-15-robolectric-12650502-i7.jar"
val robolectricDir = layout.buildDirectory.dir("robolectric")
val downloadRobolectricAndroid by tasks.registering {
    val out = robolectricDir.map { it.file(robolectricJar) }
    outputs.file(out)
    doLast {
        val f = out.get().asFile
        if (!f.exists() || f.length() < 100_000_000L) {
            f.parentFile.mkdirs()
            val tmp = File(f.path + ".part")
            URI("https://maven-central.storage-download.googleapis.com/maven2/org/robolectric/android-all-instrumented/" +
                "15-robolectric-12650502-i7/$robolectricJar").toURL().openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp.renameTo(f)
        }
    }
}
tasks.withType<Test>().configureEach {
    dependsOn(downloadRobolectricAndroid)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
}
