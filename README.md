# Morse Code

A fast, private, fully offline device-to-device file transfer app for **Android**
and **Windows Desktop**, built from one shared Kotlin codebase. Files move
directly over local Wi-Fi. No internet connection, no cloud relay, no ads, no
analytics, no bloatware.

```
Android (APK)  ──┐
                 ├──►  shared/  ──  one Kotlin Multiplatform module
Windows (EXE)  ──┘      protocol · crypto · storage · media logic · Compose UI
```

---

## Build status — read this first

**Green on GitHub Actions.** Run
[`33146408718`](https://github.com/ahmadyb/morsecode1/actions/runs/33146408718)
at commit `2da9df1` — all three jobs succeeded:

| Job | Result | Wall time | Artifact |
|---|---|---|---|
| Shared JVM tests (Phases 1-5, 9) | success | 1 m 18 s | `shared-test-report` (16 KB) |
| Android release APK | success | 1 m 37 s | `android-apk` (19.4 MB, signed) |
| Windows EXE installer | success | 9 m 19 s | `desktop-installer` (122 MB) |

The suite is the 102-test set covering Phases 1-5 and 9. On the preceding run it
reported `102 tests completed, 5 failed`; those five are fixed and the job now
finishes with zero test-failure annotations. (The exact per-run count cannot be
re-read from here — this sandbox cannot reach
`productionresultssa*.blob.core.windows.net`, so log and artifact downloads
fail. The count above is from the earlier run's annotation; what is verified for
the green run is the job conclusion and the absence of failure annotations.)

### Why CI is the only verification path

The sandbox this project was authored in has **no route to the dependency
ecosystem**, and no JDK (`/usr/lib/jvm` is empty, `java` and `javac` are
absent). Verified directly:

| Host | Result |
|---|---|
| `github.com`, `api.github.com` | reachable (200) |
| `codeload.github.com`, `registry.npmjs.org`, `pypi.org` | reachable |
| `repo1.maven.org` (Maven Central) | **TLS handshake fails (000)** |
| `dl.google.com` (Google Maven, Android SDK) | **TLS handshake fails (000)** |
| `services.gradle.org` (Gradle distributions) | **TLS handshake fails (000)** |
| `plugins.gradle.org` | **TLS handshake fails (000)** |

`./gradlew` cannot resolve a single dependency there, so no local build, test
run, APK or installer is possible. Every compile and test result in this
document comes from a GitHub Actions run.

### Measured times (Section 0.5)

Only the CI numbers above are measured. These are **not**, because the target
dev machine and an emulator are not available in the authoring sandbox — they
are left blank rather than guessed at:

- [ ] first-sync duration on the target dev machine
- [ ] clean build time on the target dev machine
- [ ] incremental build time on the target dev machine
- [ ] API 23 x86 emulator cold boot / snapshot resume time

The CI wall times include checkout, JDK setup and a cold Gradle download, so
they are an upper bound for a warm local build, not a substitute for it.

---

## Pinned toolchain (Section 0 — do not substitute)

| Component | Version |
|---|---|
| IDE | Android Studio **Ladybug 2024.2.1 Patch 3** (stable) |
| JDK | JBR **17** bundled with Android Studio |
| Gradle | **8.9** via wrapper only |
| Android Gradle Plugin | **8.7.2** |
| Kotlin | **2.0.21** |
| Compose Multiplatform | **1.7.0** |
| kotlinx.coroutines | **1.9.0** |
| kotlinx.serialization | **1.7.3** |
| kotlinx.datetime | **0.6.1** |
| SQLDelight | **2.0.2** |
| JmDNS | **3.5.9** |
| ZXing core | **3.5.3** |
| Coil 3 | **3.0.4** |
| AndroidX Media3 | **1.4.1** |
| VLCJ | **4.8.2** |
| Ktor server | **2.3.12** |
| `minSdk` / `compileSdk` | 23 / 35 |

All of these live in **`gradle/libs.versions.toml`** — the single place to
change a version. `minSdk = 23` is the compatibility target for the compiled
app; it has no bearing on the toolchain above.

---

## Building

```bash
# Phase 1-5 verification: pure JVM, no Android SDK, no emulator.
./gradlew :shared:jvmTest

# Signed release APK  ->  androidApp/build/outputs/apk/release/
#   (release only: no debug build. Needs the MORSECODE_* signing env vars)
./gradlew :androidApp:assembleRelease

# Windows installer (.exe)  ->  desktopApp/build/compose/binaries/main/exe/
./gradlew :desktopApp:packageDistributionForCurrentOS

# Desktop app, run directly
./gradlew :desktopApp:run
```

The Gradle wrapper JAR **is** committed, so a fresh clone works without a
preinstalled Gradle. Never install Gradle globally — the wrapper pins 8.9
per-project, which is the whole point.

### Note on `:shared:jvmTest`

Section 0 of the spec repeatedly names `./gradlew :shared:jvmTest`. With a
`jvm("desktop")` target — required so the source set is `desktopMain`, as the
spec's own project tree demands — the generated task is actually
`:shared:desktopTest`. `shared/build.gradle.kts` registers `jvmTest` as an alias
for it, so **both commands work**.

The alias must be registered **eagerly** (`tasks.register("jvmTest") { … }` at
the end of the shared build script). A lazy
`tasks.matching { … }.configureEach { register(…) }` form never creates the task
when it is requested by name, and Gradle fails with `task 'jvmTest' not found` —
which is exactly what happened on an earlier CI run.

### Gradle settings for constrained hardware

`gradle.properties` carries the Section 0.3 values verbatim:

```properties
org.gradle.jvmargs=-Xmx2560m
org.gradle.parallel=false
org.gradle.caching=true
kotlin.incremental=true
org.gradle.configuration-cache=true
```

`parallel=false` is deliberate on a dual-core machine with a mechanical disk:
parallel module builds contend for I/O and end up slower than serial ones. Pair
this with the IDE heap cap in Section 0.4 (2048–3072 MB) — the two compete for
the same 8 GB.

---

## Three deliberate deviations from the spec

All three are places where the spec contradicts itself, or asks for something the
toolchain does not provide. Recorded here so nobody
"fixes" them back into a broken state.

### 1. `Crypto.kt` lives in `jvmMain`, not `commonMain`

The spec says the crypto scheme is "implemented once in `shared/commonMain`"
**and** that it must use `javax.crypto` + `java.security`. Those cannot both
hold: JCE is not on the KMP common classpath.

The resolution keeps the property the spec actually cares about — the scheme
written exactly once, shared by both platforms:

| Source set | Contents |
|---|---|
| `commonMain` | `Framing`, `Messages`, `Hkdf` (RFC 5869, pure), `GcmNonceSequence`, the sender/receiver state machines, `Throttle`, media + grouping logic, all UI. Talks to crypto only through the `CryptoProvider` / `SessionCipher` interfaces. |
| `jvmMain` | Everything JVM-backed: `Crypto.kt` (ECDH P-256, HMAC-SHA256, AES-GCM), `SocketJvm.kt`, `DiscoveryJvm.kt` (JmDNS), `FileChunkSource.kt`, `WebConnectServer.kt`. Inherited by **both** `androidMain` and `desktopMain`, so it is compiled once and both platforms ship identical bytes. |

`shared/build.gradle.kts` leaves `applyDefaultHierarchyTemplate()` at its default
and declares the intermediate source sets itself:

```kotlin
val jvmMain by creating { dependsOn(commonMain) }
val jvmTest by creating { dependsOn(commonTest) }
val androidMain by getting { dependsOn(jvmMain) }
val desktopMain by getting { dependsOn(jvmMain) }
val desktopTest by getting { dependsOn(jvmTest) }
```

Those two `by creating` blocks are required, not decorative: with `androidTarget()`
*and* `jvm("desktop")` the default hierarchy template creates no intermediate
`jvm` group, so without them `jvmMain` would not exist at all. Note also that
`jvmTest` deliberately does **not** `dependsOn(jvmMain)` — the Phase 1-5/9 tests
compile against the production classes through the target's own main/test pairing.
Adding `common { }` to the hierarchy template *replaces* it and silently deletes
the `jvm` group, so do not do that either.

A side benefit: the transfer state machines depend on an interface, so they can
be tested with a fake cipher, and `Hkdf` is pure enough to be checked against
the published RFC 5869 vectors.

### 2. Package names are deeper than the directory names

Files sit at `shared/src/commonMain/kotlin/net/Framing.kt` — matching the
spec's PROJECT STRUCTURE tree exactly — but declare `package net.morsecode.net`.
Kotlin does not require a package to mirror its directory (unlike Java), so this
compiles cleanly. It is deliberate: the flat `net/`, `storage/`, `media/`
directories keep the spec's tree readable, while the `net.morsecode.*` packages
keep the namespace collision-free.

### 3. Desktop video playback uses VLCJ

Section F.2 offers VLCJ or a JavaFX Media fallback and asks for a documented
choice. **VLCJ 4.8.2 was chosen**, matching the version pinned in Section 0.

| | VLCJ (chosen) | JavaFX Media (rejected) |
|---|---|---|
| Runtime requirement | VLC must be installed on the host | JavaFX modules bundled with the app |
| Installer size | smaller | +~60 MB |
| Codec coverage | everything VLC supports | narrower, platform-dependent |
| Failure mode | detectable up front, banner + download link | silent gaps on odd codecs |

The trade-off is real: VLCJ makes VLC a runtime dependency. That is mitigated
rather than ignored — per Section D, `FirewallDiagnostics`-style startup checks
verify VLC's presence and show a non-blocking banner with a download link
instead of failing when the user first presses play. The broader codec support
wins for an app whose entire purpose is moving other people's media files
around.

---

## Repository layout

```
shared/                 Kotlin Multiplatform module — all shared logic
  src/commonMain/kotlin/net/       framing, crypto API, handshake, transfer,
                                   rooms, broadcast, throttle
  src/commonMain/kotlin/storage/   SQLDelight repositories
  src/commonMain/kotlin/media/     models, categoriser, date grouping
  src/commonMain/kotlin/ui/        theme, screens, components
  src/commonMain/sqldelight/       schema (.sq)
  src/jvmMain/kotlin/net/          Crypto.kt, JVM sockets
  src/androidMain/kotlin/          MediaStore, PackageManager, Media3
  src/desktopMain/kotlin/          filesystem scan, VLCJ
androidApp/             Android wrapper: MainActivity, permissions, services
desktopApp/             Desktop wrapper: window, tray, autostart, drag-drop
gradle/libs.versions.toml          every pinned version
.github/workflows/      CI: tests, signed APK, Windows .exe, release publishing
```

---

## Implementation status by phase

| Phase | Scope | State |
|---|---|---|
| 1 | `Framing`, crypto scheme, message types | done, **test-covered** — `Framing.kt`, `Hkdf.kt`, `CryptoApi.kt`, `Crypto.kt`, `Messages.kt`, `Base64.kt` |
| 2 | `Handshake`, transport abstraction | done, **test-covered** — `Handshake.kt`, `Transport.kt`, `SecureConnection.kt`, `SocketJvm.kt` |
| 3 | SQLDelight schema + repos | done — `TransferState.sq`, `ChatMessage.sq`, `TrustedDevice.sq` + `Database`, `TransferStateRepo`, `ChatRepo`, `TrustedDeviceRepo`, `HistoryRepo` |
| 4 | Windowed sender / receiver | done, **test-covered** — `TransferSender.kt`, `TransferReceiver.kt`, `ChunkBitmap.kt`, `TransferController.kt`, `Throttle.kt` |
| 5 | `BroadcastCoordinator`, `RoomManager` | done, **test-covered** — `BroadcastCoordinator.kt`, `RoomManager.kt`, `Discovery.kt`, `DiscoveryJvm.kt` |
| 6 | Theme + core screens | done — `theme/`, `App.kt`, `AppState.kt`, `HomeScreen`, `SettingsScreen` |
| 7 | `androidApp` wrapper | done — `MainActivity`, `PermissionsManager`, `MulticastLockManager`, `MorseForegroundService`; APK builds and is signed in CI |
| 8 | `desktopApp` wrapper | done — `Main`, `TrayManager`, `AutostartManager`, `FirewallDiagnostics`, `DragDropHandler`; `.exe` packages in CI |
| 9 | Media models, categoriser, date grouping | done, **test-covered** — `MediaModels`, `FileCategorizer`, `DateGrouping`, `MediaLibrary` |
| 10 | Platform media libraries | done — `MediaLibraryAndroid` (MediaStore), `AppLibraryAndroid` (PackageManager), `ApkExtractor`/`ApkInstaller`, `MediaLibraryDesktop` (filesystem scan) |
| 11 | Library UI (six tabs) | done — `LibraryScreen.kt` |
| 12 | Viewers / players | done — `PlayerApi.kt`, `PlayerAndroid` + `AudioPlaybackService` (Media3), `PlayerDesktop` (VLCJ) |
| 13 | Chat | done — `ChatModels.kt`, `ChatRepo.kt`, `ChatScreen.kt` |
| 14 | Web Connect | done — `PairingManager`, `WebAssets` (hand-written HTML/CSS/JS), `WebConnectServer` (JDK `HttpServer`) |
| 15 | Logo, trusted devices, throttling | done — `TrustedDeviceRepo`, `Throttle` |
| 16 | Packaging | done — release-only signed APK, `.exe`-only desktop installer, GitHub Actions release publishing |
| 17 | Integration testing | **partial** — see the caveat below |

Two different words are used above on purpose:

- **test-covered** means an automated JVM test exercises that code. The suite in
  `shared/src/jvmTest/` covers Phases 1-5 and 9 only: framing byte layout, stream
  reassembly, the RFC 5869 HKDF vectors, ECDH convergence, GCM tamper and replay
  rejection, every handshake rejection path, broadcast/room coordination, and an
  end-to-end windowed transfer with injected packet loss, run against the real
  production classes rather than stand-ins.
- **done** means the code compiles and ships in the APK and the installer, which
  CI proves. It is *not* a claim of correctness: Phases 6-8 and 10-14 have no
  automated coverage, and none of the platform code has been exercised on a real
  device or a running Windows install. A two-endpoint test (API 23 emulator plus
  `desktopApp` on the same PC, per Section 0.2) is still outstanding.

This distinction is the whole of Section 0's "phases must pass their tests
before later phases begin" rule: Phases 1-5 did, and Phase 6 onwards were only
started after that. But a green build is not a substitute for Phase 17.

---

## Signing the release APK

CI builds **release only** — there is no debug APK job. The job fails fast with
`MORSECODE_KEYSTORE_BASE64 secret is not set` if the keystore secret is missing,
so a green Android job means the APK really is signed.

Repository secrets (Settings → Secrets and variables → Actions), with the
environment variable each one is exported to for `androidApp/build.gradle.kts`:

| Repository secret | Exported as | Contents |
|---|---|---|
| `MORSECODE_KEYSTORE_BASE64` | written to `morsecode-release.jks` | `base64 -w0 release.jks` |
| `MORSECODE_STORE_PASSWORD` | `MORSECODE_KEYSTORE_PASSWORD` | keystore password |
| `MORSECODE_KEY_ALIAS` | `MORSECODE_KEY_ALIAS` | key alias |
| `MORSECODE_KEY_PASSWORD` | `MORSECODE_KEY_PASSWORD` | key password |

Note the asymmetry on the second row: the *secret* is
`MORSECODE_STORE_PASSWORD`, while the *env var* Gradle reads is
`MORSECODE_KEYSTORE_PASSWORD` — the names do not match, so when rotating the
keystore password, set the *secret* named `MORSECODE_STORE_PASSWORD`. The keystore file is deleted from the runner in an
`if: always()` step.

To build a signed APK locally without CI, export the same four `MORSECODE_*`
variables before running `./gradlew :androidApp:assembleRelease`, or put
`keystore.path` / `keystore.password` / `keystore.alias` / `keystore.keypassword`
in a local `gradle.properties`. The keystore itself is `.gitignore`d. Never
commit it.

---

## Testing on the API 23 emulator

Per Section 0.2:

- Use the **x86** "Google APIs" system image for API 23, not ARM — ARM images on
  an x86 host fall back to software emulation and are unusably slow.
- Confirm Intel HAXM (or Windows Hypervisor Platform) is installed via SDK
  Manager → SDK Tools before the first boot.
- Hardware GLES 2.0, 1024–1536 MB RAM, 3–4 GB internal storage.
- Expect a 2–5 minute cold boot on a mechanical disk. Resume the quick-boot
  snapshot rather than cold-booting repeatedly.
- API 23 predates scoped storage (29), granular media permissions (33) and
  `REQUEST_INSTALL_PACKAGES` as a runtime permission (26). All Android-side code
  must branch on `Build.VERSION.SDK_INT`.
- For two-endpoint scenarios (broadcast, rooms, chat, Web Connect), prefer one
  API 23 emulator plus one `desktopApp` instance on the same PC over two AVDs.

---

## Non-negotiables

- Zero ads, zero analytics or tracking SDKs, zero bloatware.
- No "hot apps", no recommended-app listings, no AI-branded upsell tabs. The
  Apps tab only ever lists what the user already has installed.
- LAN only. Web Connect binds to the LAN interface and never relays externally.
- All of `shared/commonMain/net/`, `storage/` and `media/` must stay testable
  without a UI or a platform data source.
