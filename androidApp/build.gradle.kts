import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ────────────────────────────────────────────────────────────────────────────
// :androidApp — thin Android wrapper. ALL logic and UI live in :shared.
// This module only hosts the Compose UI in an Activity and supplies the
// Android-specific services and permissions (Sections C and 16).
// ────────────────────────────────────────────────────────────────────────────
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "net.morsecode.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.morsecode.android"
        minSdk = 23                      // compatibility target, not a toolchain limit
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release signing is optional and read from env/CI secrets or local props.
    // assembleDebug never needs it.
    signingConfigs {
        create("release") {
            val ks = System.getenv("MORSECODE_KEYSTORE") ?: extraProp("keystore.path")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("MORSECODE_KEYSTORE_PASSWORD") ?: extraProp("keystore.password")
                keyAlias = System.getenv("MORSECODE_KEY_ALIAS") ?: extraProp("keystore.alias")
                keyPassword = System.getenv("MORSECODE_KEY_PASSWORD") ?: extraProp("keystore.keypassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val cfg = signingConfigs.getByName("release")
            if (cfg.storeFile != null) signingConfig = cfg
        }
        debug { }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // :shared uses kotlinx-datetime, whose JVM implementation is built on
        // java.time — absent below Android API 26. Desugaring backports it so
        // the minSdk 23 target can call Clock.System.now().
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures { compose = true }

    packaging {
        // Ktor/CIO pulls in a few duplicate META-INF files on Android.
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
    }

    lint { abortOnError = false }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

fun extraProp(name: String): String? =
    (rootProject.findProperty(name) as String?) ?: System.getProperty(name)

dependencies {
    // Must match the version :shared desugars against, or the two copies of the
    // backported java.time classes collide at dex time.
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":shared"))

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
}
