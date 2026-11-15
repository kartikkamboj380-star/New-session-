# MultiSpace

**MultiSpace** is an Android session manager built with Kotlin + Jetpack Compose. It gives you a browser-style, multi-tab workspace for **isolated WEB sessions** and **imported-APK sessions**, with a user-controlled **background manager** foreground service, live **RAM / CPU / battery-temperature** monitoring and crash-safe session state recovery.

- **Package:** `com.multispace.manager`
- **Tech:** Kotlin, Jetpack Compose (Material 3, dark theme), Room, Storage Access Framework, Foreground Services, Gradle (Kotlin DSL)
- **minSdk 26 · targetSdk 34 · JDK 17 · AGP 8.4.2 · Gradle 8.7**

---

## Features

### APK Import
- **Import App** button opens the Android file picker (Storage Access Framework).
- The picked file is validated (`.apk` name, size, ZIP `PK\x03\x04` header) **before** parsing — invalid files are rejected with a precise reason.
- Real metadata is parsed with `PackageManager.getPackageArchiveInfo`: **app name, icon, package name, versionName/versionCode**.
- The APK binary is copied into the app's private storage; the icon is decoded once and cached as a downsampled PNG (low-RAM friendly).
- Everything is stored in a **Room database** (`imported_apps` table) — the library survives restarts.

### Multi Session
- **New Session** creates a session with a unique name + UUID.
- Every session gets **isolated private storage**: `filesDir/sessions/<id>/{data,cache,web,downloads}` plus an atomic `meta.json` snapshot.
- Session list with real lifecycle status: `CREATED → ACTIVE → IDLE → RECOVERING → CLOSED/ERROR` — transitions are enforced by a testable state machine, so invalid states can never be persisted.
- **Open / Close / Restart / Delete** — restart reuses persisted tabs; delete removes the row, tabs and the whole directory tree.
- **Session state is preserved** across restarts and only removed on explicit delete.

### Browser-style interface
- Top **tab strip**: one tab per session, with an **active-tab underline indicator** (green when running), status dot, per-tab **close**, and a **+ new tab** button.
- Inside a WEB session: full browser UI — **multiple web tabs**, new tab, close tab (long-press), tab switching, URL bar, progress bar, back navigation, session downloads into the session's private directory.
- WEB sessions run in a **dedicated Android process per session** (up to 4 concurrent). Each process sets its own WebView data directory suffix — genuine storage isolation between concurrent sessions. When all slots are busy the app **refuses to open a 5th session with an explicit message** instead of silently sharing storage.

### Background Manager
- Android **foreground service** (`specialUse` type) with a **persistent notification** and a user-controlled **Start/Stop**.
- While the user is in other apps it keeps **session bookkeeping alive**, samples **RAM / CPU / battery temperature** and periodically **persists session snapshots** for recovery.
- **Screen-off behaviour:** the service keeps running; sampling interval doubles and the snapshot cadence is preserved. (Note: Android Doze can still defer network/CPU — that is a platform limit, documented honestly.)
- **Battery & temperature monitoring** via the sticky `ACTION_BATTERY_CHANGED` broadcast; **RAM** via `ActivityManager.MemoryInfo`; **CPU** parsed from `/proc/stat` (shown as **N/A** — never faked — on vendors that seal it).
- **Session state recovery:** on app start, sessions persisted as `ACTIVE` are resolved against a real process-liveness check (`RECOVERING → ACTIVE` if a slot process is alive, `IDLE` otherwise). Snapshots are written atomically (temp file + rename) so a crash mid-write cannot corrupt them.

### Dashboard UI (premium dark)
Modern dark dashboard with device vitals (**RAM %, CPU %, battery temperature**), Imported Apps section, Active Sessions, Background Status, session cards and the browser-style tab bar.

---

## How "running an APK" really works (honest architecture)

This is the part most apps fake. MultiSpace does **not** pretend a session is running by showing a name — here is exactly what it does and does not do:

| Level | What it is | MultiSpace support |
|---|---|---|
| In-process APK virtualization | Running another app's APK inside this app with hooks | **Not implemented.** On modern Android this is broken for any app targeting recent APIs and requires running untrusted code in-process. MultiSpace will not pretend otherwise. |
| Device install via PackageInstaller | The platform's real install flow with mandatory user confirmation | ✅ Implemented — "Install on device" uses `PackageInstaller` sessions; results are surfaced via notification + in-app event. |
| Managed work profiles / OEM clone profiles | Genuinely isolated Android users where the same app runs as a separate instance | ✅ **Detected and used.** MultiSpace lists existing profiles through `LauncherApps` and offers "launch in isolated profile" when the app is installed there. A regular app **cannot create** a work profile (needs device provisioning) — the UI states this plainly. |
| Per-process WebView sessions | Real storage-isolated web sessions | ✅ Implemented — up to 4 concurrent sessions, each in its own process with its own WebView data directory (`setDataDirectorySuffix`), private downloads, persisted tabs. |

**What MultiSpace never does:** bypass any app's security, authentication or account restrictions; run APKs without the platform install flow; report fake "running" sessions.

### Known limitations (by design, not hidden)
1. **Arbitrary APK virtualization is not possible** for a regular Android app on modern Android. Imported APKs run either (a) installed on the device via the platform installer, (b) in an isolated profile when one exists, or (c) as web sessions when the session type is WEB.
2. WebView data-directory isolation needs **Android 9 (API 28)+**; on Android 8.x sessions still work but show a "limited isolation" notice.
3. Cleartext HTTP is blocked (`usesCleartextTraffic=false`).
4. Doze may defer background network/CPU while the screen is off — the service keeps session state, not arbitrary workloads, alive.
5. CPU usage shows **N/A** on devices that seal `/proc/stat`.
6. Concurrent isolated WEB sessions are capped at **4 process slots** (a 5th waits with an explicit message).

---

## Building

```bash
# Debug APK (same command used in CI)
gradle :app:assembleDebug      # or ./gradlew :app:assembleDebug

# Unit tests
./gradlew :app:testDebugUnitTest
```

Requirements: JDK 17, Android SDK with platform 34 (AGP 8.4.2 / Gradle 8.7 wrapper included).

### CI (GitHub Actions)
`.github/workflows/android.yml` runs on push/PR/manual trigger:
1. Checkout → 2. JDK 17 (Temurin) → 3. Android SDK setup → 4. `gradle :app:assembleDebug` → 5. unit tests (`testDebugUnitTest`) → 6. uploads the debug APK artifact **MultiSpace-debug-APK** (+ test results).

## Tests
Pure-JVM unit tests (no emulator needed) cover the requested areas:

| Test class | Covers |
|---|---|
| `ApkImportValidatorTest` | APK import: valid APK accepted, invalid APK/renamed file/tiny/empty rejected |
| `SessionLifecycleTest` | Session state machine, **session persistence** (snapshot round-trip, corrupt file, atomic overwrite), **app-restart recovery**, path-traversal safety |
| `TabControllerTest` | **Tab switching**, new/close tab, active-tab invariants |
| `MonitorPolicyTest` | **Low-RAM behaviour**, screen-off interval, `/proc/stat` CPU parsing, battery temperature |
| `WebSlotRegistryTest` | Isolation slot pool: assignment, reuse, **exhaustion refusal**, release |

Instrumented (device) tests are out of scope for CI, which has no emulator; the service/Compose layers are covered indirectly through the pure-logic layers above.

## Project structure

```
app/src/main/java/com/multispace/manager/
├── MainActivity.kt                  # Compose host + notification permission
├── MultiSpaceApp.kt                 # Application + manual DI container
├── core/                            # Pure, unit-tested logic
│   ├── ApkImportValidator.kt        # APK header/name/size validation
│   ├── TabController.kt             # Browser tab model
│   ├── MonitorPolicy.kt             # Sampling intervals, low-RAM policy
│   ├── SystemParsers.kt             # /proc/stat CPU + battery temp parsing
│   ├── SessionRecoveryPolicy.kt     # Restart recovery decisions
│   ├── SessionSnapshot.kt           # Atomic JSON snapshot store + path safety
│   ├── SessionStatus.kt             # Lifecycle state machine
│   └── WebSlotRegistry.kt           # Isolation process-slot pool
├── data/db/                         # Room: AppDatabase, DAOs, entities
├── apk/                             # ApkImporter (SAF) + ApkInstaller (PackageInstaller)
├── profile/ProfileIntegration.kt    # Work/clone profile detection & launching
├── session/SessionManager.kt        # Session lifecycle + isolated storage
├── service/                         # BackgroundManagerService (FGS) + SystemMonitor
├── web/                             # Isolated WebView host (4 process slots) + downloader
└── ui/                              # Compose theme, screens, components, ViewModel
app/src/test/java/com/multispace/manager/   # Unit tests
.github/workflows/android.yml               # CI: build + test + APK artifact
```

## Privacy
All sessions, imported APKs and downloads live in the app's private storage. `allowBackup=false`. No network permission is held by the app itself; WebView sessions use HTTPS with Safe Browsing enabled.

## License
Placeholder — choose and add a license before distributing.
