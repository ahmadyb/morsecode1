import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ────────────────────────────────────────────────────────────────────────────
// :shared — Kotlin Multiplatform module.
//
// Holds every byte of shared logic: the wire protocol (PROTOCOL SPECIFICATION),
// persistence (Section 13), media categorisation (Section E.3), the Web Connect
// server (Section H) and the whole Compose UI tree (Section A).
//
// Targets, per the spec:
//   androidTarget()  -> source set  androidMain
//   jvm("desktop")   -> source set  desktopMain   (matches the PROJECT
//                          STRUCTURE tree; its test task is :shared:desktopTest,
//                          aliased to :shared:jvmTest by the root build script)
//
// DEVIATION FROM THE SPEC'S FILE TREE — read before moving files:
//   The spec says Crypto.kt is "implemented once in shared/commonMain", but it
//   also mandates `javax.crypto` + `java.security`. JCE is not on the KMP
//   common classpath, so those two instructions cannot both be honoured. The
//   resolution below keeps the *property the spec actually cares about* — the
//   scheme written exactly once and shared by both platforms:
//
//     commonMain   pure Kotlin + coroutines. Framing, Messages, the sender and
//                  receiver state machines, Throttle, RoomManager, the
//                  BroadcastCoordinator, the media/grouping logic, all UI.
//                  Talks to crypto only through the `SessionCipher` interface.
//     jvmMain      Crypto.kt + JceSessionCipher.kt. Written ONCE, inherited by
//                  both androidMain and desktopMain via the default hierarchy.
//
//   `applyDefaultHierarchyTemplate()` is deliberately left at its default so
//   that jvmMain exists. Do not add `common { }` here — that call *replaces*
//   the template and would silently delete the jvm group.
// ────────────────────────────────────────────────────────────────────────────
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            // JBR 17 (Section 0). JVM 17 class files are fine for minSdk 23:
            // the class-file version is a compile target, and D8 desugars it.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // --- Core KotlinX (TECH STACK) ---
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)

                // --- Persistence: shared schema, driver injected per platform ---
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)

                // --- Web Connect embedded server (Section H.3).
                // Ktor CIO is plain JVM code and runs unmodified on Android and
                // Desktop, so the server itself needs no expect/actual split.
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                // --- Image loading / thumbnails (Section E.4, F.1) ---
                implementation(libs.coil.compose)

                // --- Compose Multiplatform UI (Section A) ---
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        // --- jvmMain: an INTERMEDIATE source set shared by BOTH Android and
        // Desktop. It is NOT produced by the default hierarchy (the JVM target
        // is named "desktop"), so it must be created and wired manually. Both
        // androidMain and desktopMain `dependsOn` it (see their blocks below),
        // which is what lets Crypto.kt / DiscoveryJvm.kt / FileChunkSource.kt
        // live once under src/jvmMain and compile into both platforms.
        val jvmMain by creating {
            dependsOn(commonMain)
            dependencies {
                // Pure-Java artifacts that work identically on both platforms.
                implementation(libs.jmdns)
                implementation(libs.zxing.core)
            }
        }

        val jvmTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val androidMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.sqldelight.android.driver)
                implementation(libs.androidx.core.ktx)

                // AndroidX Media3 — Section F.2 / F.3 (Android playback path)
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.ui)
                implementation(libs.media3.session)
                implementation(libs.media3.common)

                // MediaSessionCompat fallback for API 23, where Media3's
                // MediaSessionService path is unavailable (Section C).
                implementation(libs.androidx.media)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.junit)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)

                implementation(libs.sqldelight.sqlite.driver)

                // Desktop playback. VLCJ is the primary implementation — see
                // the README's "Desktop video playback" section for why VLCJ
                // was chosen over the JavaFX Media fallback, and what that
                // costs the end user at runtime.
                implementation(libs.vlcj)
            }
        }

        val desktopTest by getting {
            dependsOn(jvmTest)
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.junit)
            }
        }
    }
}

android {
    namespace = "net.morsecode.shared"
    // minSdk 23 is the compatibility TARGET for the compiled app (Section 0),
    // unrelated to the toolchain: AGP 8.7.2 / Kotlin 2.0.21 / JBR 17 are modern
    // regardless of which OS versions the output APK supports.
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keeps HDD sync cost down: no lint pass on every library build.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// ────────────────────────────────────────────────────────────────────────────
// SQLDelight — ONE schema shared by both platforms (Section 13).
// .sq sources live in src/commonMain/sqldelight/.
// ────────────────────────────────────────────────────────────────────────────
sqldelight {
    databases {
        create("MorseCodeDatabase") {
            packageName.set("net.morsecode.storage.db")
            // The schema sticks to the default sqlite-3.18 dialect (no upserts,
            // no window functions) so no extra `-dialect` artifact is required;
            // conflict handling uses INSERT OR REPLACE / INSERT OR IGNORE.
            // chat_message is created here in Phase 3 alongside transfer_state,
            // as the phase list requires — not bolted on later in Phase 13.
        }
    }
}

// Section 0 documents `./gradlew :shared:jvmTest`. The JVM target is named
// "desktop", so the real test task is `desktopTest`. Register the spec's name
// EAGERLY as an alias (a lazy matching-based alias is never created when the
// task is requested directly, which is what broke the CI run).
tasks.register("jvmTest") {
    group = "verification"
    description = "Alias for :shared:desktopTest — the command named in Section 0 of the spec."
    dependsOn("desktopTest")
}
