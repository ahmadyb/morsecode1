import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ────────────────────────────────────────────────────────────────────────────
// :desktopApp — thin Windows/macOS/Linux wrapper hosting the shared Compose UI.
//
// `packageDistributionForCurrentOS` produces the native installer. On a Windows
// runner that is the .msi / .exe the spec asks for (Section 16). Cross-compiling
// a Windows installer from Linux is not supported by the Compose Desktop
// plugin, which is why CI runs this job on windows-latest.
// ────────────────────────────────────────────────────────────────────────────
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                // Desktop playback backend (Section F). Chosen over JavaFX;
                // see README "Desktop video playback".
                implementation(libs.vlcj)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "net.morsecode.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MorseCode"
            packageVersion = "1.0.0"
            description = "Private, offline device-to-device file transfer."
            vendor = "Morse Code"

            windows {
                menuGroup = "Morse Code"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // iconFile intentionally omitted: no .ico is committed yet, so
                // packaging uses the default icon instead of failing to build.
            }
        }

        buildTypes.release.proguard.isEnabled = false
    }
}
