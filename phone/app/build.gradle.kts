import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Signing: phone/keystore.properties (never committed) points at your key.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.cinder.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.cinder.phone"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (signing.isNotEmpty()) {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // cloudflared ships as libcloudflared.so so Android unpacks it somewhere it may run.
    packaging { jniLibs { useLegacyPackaging = true } }
    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/cinder-assets"))
        }
    }
}

// The node runs the same Python code as the rest of the repo, and serves the
// same website. Copy them in at build time so there's one copy to maintain.
val copyCinderPython by tasks.registering(Sync::class) {
    from(rootProject.file("..")) {
        include("node.py", "cinder/**")
        exclude("**/__pycache__/**")
    }
    into(layout.buildDirectory.dir("generated/cinder-python"))
}
val copyCinderSite by tasks.registering(Sync::class) {
    from(rootProject.file("../website")) { exclude("phone/*.apk") }
    into(layout.buildDirectory.dir("generated/cinder-assets/website"))
}
tasks.named("preBuild") { dependsOn(copyCinderPython, copyCinderSite) }
tasks.configureEach {
    if (name.startsWith("copyCinder")) return@configureEach
    if (name.contains("Python")) dependsOn(copyCinderPython)
    if (name.contains("Assets") || name.contains("Lint")) dependsOn(copyCinderSite)
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip { install("ecdsa==0.19.1") }
    }
    sourceSets {
        getByName("main") { srcDir(layout.buildDirectory.dir("generated/cinder-python")) }
    }
}
