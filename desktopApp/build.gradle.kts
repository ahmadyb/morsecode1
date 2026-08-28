import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ────────────────────────────────────────────────────────────────────────────
// :desktopApp — thin Windows/macOS/Linux wrapper hosting the shared Compose UI.
//
// `packageDistributionForCurrentOS` produces the native installer. On a Windows
// runner that is the .exe the spec asks for (Section 16); only the EXE target
// is enabled. Cross-compiling a Windows installer from Linux is not supported
// by the Compose Desktop plugin, which is why CI runs this job on windows-latest.
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
            targetFormats(TargetFormat.Exe)
            packageName = "MorseCode"
            packageVersion = "1.0.0"
            description = "Private, offline device-to-device file transfer."
            vendor = "Morse Code"

            // Bundle the whole JDK instead of the jlink auto-detected subset.
            //
            // Detection is static, so it misses modules reached only through
            // reflection or service loading — and this app depends on exactly
            // those:
            //   java.sql       SQLDelight's JdbcSqliteDriver opens connections
            //                  through java.sql.DriverManager, so the Chat,
            //                  Library and Settings screens all died with
            //                  NoClassDefFoundError: java/sql/DriverManager.
            //   jdk.crypto.ec  the SunEC provider backing ECDH P-256, without
            //                  which every handshake would fail at runtime.
            //   jdk.unsupported  sqlite-jdbc touches sun.misc.Unsafe.
            //   java.naming, java.management, jdk.locale.data  JmDNS and the
            //                  system tray.
            // The installer grows by roughly 40 MB; a smaller installer that
            // cannot open its database is not a trade worth making.
            includeAllModules = true

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
