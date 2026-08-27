# Morse Code

A fast, private, fully offline device-to-device file transfer app for **Android**
and **Windows Desktop**, built from one shared Kotlin codebase. Files move
directly over local Wi-Fi. No internet connection, no cloud relay, no ads, no
analytics, no bloatware.

```
Android (APK)  ──┐
                 ├──►  shared/  ──  one Kotlin Multiplatform module
Windows (MSI)  ──┘      protocol · crypto · storage · media logic · Compose UI
```

---

## Build status — read this first

**Nothing in this repository has been compiled yet.** That is a statement of
fact, not a caveat.

The sandbox this project was authored in has **no route to the dependency
ecosystem**. Verified directly:

| Host | Result |
|---|---|
| `github.com`, `api.github.com` | reachable (200) |
| `codeload.github.com`, `registry.npmjs.org`, `pypi.org` | reachable |
| `repo1.maven.org` (Maven Central) | **TLS handshake fails (000)** |
| `dl.google.com` (Google Maven, Android SDK) | **TLS handshake fails (000)** |
| `services.gradle.org` (Gradle distributions) | **TLS handshake fails (000)** |
| `plugins.gradle.org` | **TLS handshake fails (000)** |

No JDK was present either. A JDK 17 *runtime* could be obtained (via the
`jdk4py` package on PyPI), but it ships without `javac`, and no Kotlin compiler
is published to PyPI or npm. With Maven Central unreachable, `./gradlew` cannot
resolve a single dependency, so no build, no test run, and no APK/MSI is
possible there.

**The verification path is CI.** `.github/workflows/build.yml` runs
`./gradlew :shared:jvmTest`, builds the APK, and packages the Windows installer
on GitHub's runners, which do have network access. Push this branch and the
workflow is the first real check of everything below.

Because of that, the following are **not yet measured** and are left blank
rather than guessed at — Section 0 of the spec asks for them to be documented
"once a working build is achieved":

- [ ] first-sync duration on the target dev machine
- [ ] clean build time
- [ ] incremental build time
- [ ] API 23 x86 emulator cold boot / snapshot resume time

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

# Android APK  ->  androidApp/build/outputs/apk/
./gradlew :androidApp:assembleDebug

# Windows installer  ->  desktopApp/build/compose/binaries/main/
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
`:shared:desktopTest`. The root `build.gradle.kts` registers `jvmTest` as an
alias so **both commands work**.

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

Both are places where the spec contradicts itself. Recorded here so nobody
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
| `jvmMain` | `Crypto.kt` — the JCE primitives (ECDH P-256, HMAC-SHA256, AES-GCM). Inherited by **both** `androidMain` and `desktopMain`, so it is compiled once and both platforms ship identical bytes. |

`shared/build.gradle.kts` therefore leaves `applyDefaultHierarchyTemplate()` at
its default. Adding `common { }` there *replaces* the template and silently
deletes the `jvm` group — do not do it.

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
.github/workflows/      CI: tests, APK, MSI, release publishing
```

---

## Implementation status by phase

| Phase | Scope | State |
|---|---|---|
| 1 | `Framing`, crypto scheme, message types | **written** — `Framing.kt`, `Hkdf.kt`, `CryptoApi.kt`, `Crypto.kt`, `Messages.kt`, `Base64.kt` |
| 2 | `Handshake`, transport abstraction | **written** — `Handshake.kt`, `Transport.kt`, `SecureConnection.kt`, `SocketJvm.kt` |
| 3 | SQLDelight schema + repos | **schema written** — `TransferState.sq`, `ChatMessage.sq`, `TrustedDevice.sq`; repos pending |
| 4 | Windowed sender / receiver | **written** — `TransferSender.kt`, `TransferReceiver.kt`, `ChunkBitmap.kt` |
| 5 | `BroadcastCoordinator`, `RoomManager` | not started |
| 6 | Theme + core screens | not started |
| 7 | `androidApp` wrapper | not started |
| 8 | `desktopApp` wrapper | not started |
| 9 | Media models, categoriser, date grouping | not started |
| 10 | Platform media libraries | not started |
| 11 | Library UI (six tabs) | not started |
| 12 | Viewers / players | not started |
| 13 | Chat | not started (schema already present) |
| 14 | Web Connect | not started |
| 15 | Logo, trusted devices, throttling polish | partially — throttle written |
| 16 | Packaging | CI wired, signing pending |
| 17 | Integration testing | not started |

The test suite (`shared/src/jvmTest/`) covers Phases 1–4: framing byte layout,
stream reassembly, the RFC 5869 HKDF vectors, ECDH convergence, GCM tamper and
replay rejection, the full handshake including every rejection path, and an
end-to-end windowed transfer with injected packet loss.

**These tests have never been executed.** They are written against the real
production classes, not stand-ins, but "written" is not "passing". CI is the
first run.

---

## Signing the release APK

`assembleDebug` needs no signing config. For a release build, add these
repository secrets (Settings → Secrets and variables → Actions):

| Secret | Contents |
|---|---|
| `MORSECODE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `MORSECODE_KEYSTORE_PASSWORD` | keystore password |
| `MORSECODE_KEY_ALIAS` | key alias |
| `MORSECODE_KEY_PASSWORD` | key password |

The keystore itself is `.gitignore`d. Never commit it.

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
