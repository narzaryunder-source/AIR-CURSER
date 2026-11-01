import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aircursor"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
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
    kotlinOptions {
        jvmTarget = "17"
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
    }
}

// Downloads the MediaPipe hand landmark model into assets (only if it is missing).
val downloadHandModel by tasks.registering {
    val modelUrl =
        "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
    val outFile = layout.projectDirectory.file("src/main/assets/hand_landmarker.task").asFile
    outputs.file(outFile)
    doLast {
        if (!outFile.exists() || outFile.length() < 100_000L) {
            outFile.parentFile.mkdirs()
            logger.lifecycle("Downloading hand_landmarker.task ...")
            URI(modelUrl).toURL().openStream().use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            logger.lifecycle("Saved ${outFile.length()} bytes to $outFile")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadHandModel)
}

// The built-in Cloud PC uses the same desktop tools as the Termux setup (App Store, Windows layout…):
// copy them from ubuntu/ into the APK's assets.
val copyCloudPcTools by tasks.registering(Copy::class) {
    from(rootProject.file("ubuntu")) {
        include(
            "app-store.sh", "app-store.py", "ubuntu-extras.sh", "install-deb.sh", "windows-layout.sh", "wallpaper-gui.sh",
            "launch.sh", "apps-setup.sh",
        )
    }
    into(layout.buildDirectory.dir("generated/cloudpc-assets/cloudpc"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/cloudpc-assets"))
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

    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Sign-in: Firebase Authentication (Google + phone OTP) and Android's Credential Manager.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
}
