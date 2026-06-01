# Worklog — btLeApp Android App (Play Store Release)

## 2026-05-31

### Session goal
Plan and begin Phase 1 (Demo Mode) of the Play Store release effort.

### Decisions made

**Release plan** (`release_plan.md` created):
- Target: Google Play Open Testing track (publicly accessible via link, not search-discoverable)
- 5 phases: Demo Mode → Recovery Recommendations → Session History → Play Store Readiness → Polish
- Tooling: all coding in VSCode/Claude Code over SSH; UI verification on physical device via ADB

**Demo mode approach:**
- Bundle a pre-converted real session (log0305 golden session) as an app asset — NOT synthetic data
- Replay through the existing ring buffers (`emgRawBuf`, `imuBuf`, etc.) and `FatigueMonitor` pipeline
- Bypasses BLE/TLV decode path entirely; `SessionCaptureActivity` extended with `isDemoMode` flag
- `MainActivity` gets a "Try Demo" button (dead `demoCheckBox` at lines 125–144 removed)

**Asset format decision:**
- Initial attempt: interleaved CSV → 38 MB (too large for a bundled asset)
- Decided: **binary format** — two files, `demo_emg.bin` + `demo_imu.bin`
  - `demo_emg.bin`: magic(4) + n(int32) + per-sample `{t_ms:int32, raw:int16, env:int16}` = 8 B/sample
  - `demo_imu.bin`: magic(4) + n(int32) + per-sample `{t_ms:int32, ax:int16×3, gx:int16×3}` = 16 B/sample
  - IMU fixed scale: accel × 1000 (stored as milli-g int16), gyro × 10 (stored as 0.1 dps int16)
  - Target: ~5.8 MB total for 5 min of data

**log0305 window:**
- Skip first 20 min (electrode settling — EMG/MDF invalid before this; same reason `config_w20` exists)
- Take next 5 min of clean play (30+ swings, visible fatigue trend)
- Sanity check passed: median RAW = 1836 (≈1843 expected), max gyro mag = 3460 dps (swings confirmed)

### Work completed

- `release_plan.md` created at `app/release_plan.md`
- `task.md` populated for Phase 1 (4 steps with checkboxes)
- `tools/make_demo_asset.py` created — loads log0305 via `capture_loader`, trims to 20–28 min window,
  outputs merged CSV; confirmed correct values but CSV format discarded (38 MB too large)
- Binary asset format agreed; `make_demo_asset.py` rewritten to output binary format
- **Step 1 complete**: `demo_emg.bin` (4.6 MB) + `demo_imu.bin` (942 KB) copied to `app/src/main/assets/`
  - Sanity: median RAW=1836 ✓, max gyro=2845 dps ✓, 4148 samples above 300 dps swing threshold ✓

### Next step
- Step 2 — add "Try Demo" button to `MainActivity` (layout XML + Java wiring)
- Step 3 — `DemoPlaybackThread` in `SessionCaptureActivity` (reads binary assets, feeds ring buffers)

---

## 2026-05-31 (continued) — Steps 2 & 3: Demo Mode wiring

### Work completed

**Step 2 — "Try Demo" button wired (MainActivity.java):**
- `btn_demo` already existed in layout and field declaration; was missing `setOnClickListener` and `onClick` handler
- Added `btn_demo.setOnClickListener(this)` in `initView()`
- Added `onClick` branch: launches `SessionCaptureActivity` with `intent.putExtra("DEMO_MODE", true)`
- Removed unused `CompoundButton` import

**Step 3 — DemoPlaybackThread (SessionCaptureActivity.java):**
- `isDemoMode` already read from intent; `onCreate` demo branch already present
- Implemented `startDemoPlayback()`: background thread reads `demo_emg.bin` + `demo_imu.bin` assets,
  replays samples into ring buffers at 4× real-time speed, drives `detectSwingPeak()` for fatigue
- Implemented `stopDemoPlayback()`: interrupt + join on `onDestroy`
- Extracted `detectSwingPeak(tSec, gmag_dps, devUs)` helper — removes code duplication between
  live `decodeImuBatch` and demo path
- Completion message: "Demo complete — N swings detected"
- Asset loader: multi-pass reads (3× EMG, 7× IMU) — correct but not optimized; good enough for Phase 1

### Next step
- Build: `./gradlew assembleDebug` (needs SDK env — see Linux build setup section above)
- Fix any compile errors, then deploy to device and verify "Try Demo" button launches and plays back

---

## 2026-05-31 (continued) — Build fixes and APK ready

### Issues fixed during build
- `gradle.properties`: `org.gradle.java.home` pointed to Windows path → changed to `/usr/lib/jvm/java-17-openjdk-amd64`
- `AndroidManifest.xml`: missing `android:exported="true"` on `MainActivity` (required for targetSdk 34)
- `SessionCaptureActivity.java`: linter removed `BufferedOutputStream` import and added stale duplicate
  demo methods (from a prior session) — removed duplicates via Python script, restored import
- `detectSwingPeak()` helper: was part of the stale block, accidentally deleted — restored as standalone method

### Build result
- `./gradlew assembleDebug` → **BUILD SUCCESSFUL** (17s, 71 tasks)
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Target device: Android 9 (API 28) — compatible with minSdk 24

### Next step
- Install APK on Android 9 device: `adb install app-debug.apk`
- Verify "Try Demo" button appears, launches `SessionCaptureActivity`, EMG/IMU plots animate at 4× speed,
  fatigue monitoring fires after ~20 swings

---

## 2026-05-31 (continued) — Phase 1 polish + Phase 2 Session Summary

### Phase 1 Polish (Step 4) — completed

**Demo badge:**
- Added `txtDemoBadge` TextView to `activity_capture.xml` (red, bold, hidden by default)
- Shows `● DEMO MODE` on demo start; changes to `● DEMO COMPLETE` at EOF; hides on Stop
- Fixed missing `btnStop.setOnClickListener` in demo branch — was skipped by early `return`

**Completion summary:**
- Tracks `demoSwingCount` (incremented alongside `markers.add()` in demo loop)
- EOF status: "Demo complete — N swings detected"
- `btnProcessSession` in demo shows Toast explaining live-sessions-only

**Verified on device:** 42 swings detected, demo plays and stops correctly.

---

### Phase 2 — Session Summary Activity

**New files:**
- `app/src/main/java/com/clj/blesample/capture/SessionSummaryActivity.java`
- `app/src/main/res/layout/activity_session_summary.xml`
- Registered in `AndroidManifest.xml`

**In `SessionCaptureActivity`:**
- Added `lastFatigueLevel`, `lastMdfDropPct`, `sessionStartWallMs` fields
- `parseFatigueDisplay()` now stores last level/drop% when baseline is ready
- `launchSummary(swingCount)` helper builds Intent and starts `SessionSummaryActivity`
- Called from `stopSession()` (live) and demo EOF (with queue-drain wait)

**`SessionSummaryActivity` shows:**
- Duration, swing count, MDF drop %
- Colored badge: green=FRESH, orange=MODERATE, red=HIGH, grey=MONITORING
- Recovery recommendation text per fatigue level
- "Done" button returns to capture screen

**Bug fix — baseline never reached at 4× demo speed:**
- Root cause: `fatigueQueue` capacity was 1 with non-blocking `offer()` — all but 1 swing
  dropped before Python processed 20 for baseline
- Fix: queue capacity 1 → 64
- Demo EOF now polls until `fatigueQueue.isEmpty()` (max 10s) before launching summary

### Status
Testing in progress on Android 9 device.

---

## 2026-06-01 — Phase 2 debug: fatigue analysis pipeline

### Root causes found and fixed (in order)

**1. `fatigue_monitor` not packaged (ModuleNotFoundError)**

- `app/src/main/python/emg_util/` is a git submodule, SSH key not configured on Linux host → submodule never populated (empty dir)
- Fix: copied `fatigue_monitor.py` + `emg_util_nopandas.py` directly from `/local/mnt/workspace/wangx/projects/emg_util/`
- Required `./gradlew clean assembleDebug` — incremental build skipped Chaquopy repackage due to cached empty dir

**2. `reset_fatigue_monitor` never executed (warmup=5 min applied to demo)**

- `Python.start()` throws `IllegalStateException: Python already started` → aborted try block before `reset_fatigue_monitor` was called → old monitor (`warmup_skip_min=5.0`) stayed active → all 42 demo swings fell within warmup window → baseline never collected
- Fix: added retry in catch block — call `reset_fatigue_monitor` directly via `Python.getInstance()` if `Python.start()` fails
- Demo params: `warmup_skip_min=0.0`, `baseline_swings=5`

**3. Double-threading artefact**

- Two fatigue threads processed each swing (swing IDs doubled) — stale Chaquopy instance from prior APK; resolved with `adb uninstall` before install

### Verified working (logcat 04:57 run)

- `reset_fatigue_monitor OK (warmup=0, baseline=5)` confirmed
- Baseline ready at swing #7, `baseline_mdf=332 Hz`
- From swing #10: `fatigue_level: "high"`, `mdf_drop_pct: 40%`, `warning: true`
- `SessionSummaryActivity` expected to show RED HIGH FATIGUE badge + 48h rest recommendation

### Status
Phase 2 COMPLETE. Verified on Android 9: MDF drop 39.4%, HIGH fatigue badge, 48h rest recommendation displayed correctly.

### Outstanding (not blocking)
- `fatigue_monitor.py` + `emg_util_nopandas.py` copied into submodule path but not git-tracked
  (git blocks staging files inside submodule). Fix: set up SSH key on Linux host and run
  `git submodule update --init` — then the submodule commit pointer tracks the right version.
- Debug `Log.d(TAG, "parseFatigueDisplay: ...")` left in code — remove before Play Store build.

---

## 2026-06-01 (continued) — Phase 3: Session History & Trends

### New files created

| File | Purpose |
|------|---------|
| `capture/SessionRecord.java` | POJO: ts, durationSec, swingCount, fatigueLevel, mdfDropPct; JSON serialization |
| `capture/SessionHistoryManager.java` | Reads/writes `getFilesDir()/sessions.json`; capped at 50 entries |
| `capture/TrendSparklineView.java` | Custom canvas view: MDF drop % trend, coloured dots per fatigue level |
| `capture/SessionRowAdapter.java` | RecyclerView adapter — newest session first |
| `capture/SessionHistoryActivity.java` | Sparkline + RecyclerView list + "Clear" button with confirmation dialog |
| `res/layout/activity_session_history.xml` | Dark-background sparkline (140dp) + RecyclerView below |
| `res/layout/item_session_row.xml` | Row: date, duration/swings/MDF stats, fatigue badge chip |

### Files modified

- `SessionSummaryActivity.java` — appends `SessionRecord` to history on every session (live + demo); added "View History" button
- `activity_session_summary.xml` — "View History" button below "Done"
- `MainActivity.java` + `activity_main.xml` — "History" button in main screen button row
- `AndroidManifest.xml` — registered `SessionHistoryActivity`
- `app/build.gradle` — added `androidx.recyclerview:recyclerview:1.2.1` dependency

### Build result
`assembleDebug` → BUILD SUCCESSFUL (6s, 71 tasks)

### Verified on device
Session history screen opens from both MainActivity and SessionSummaryActivity; sparkline and row list populate after completing a demo session.

---

## 2026-06-01 (continued) — Phase 4: Play Store Readiness (coding portion)

### Adaptive icon

- `res/drawable/ic_launcher_foreground.xml` — Android vector drawable from Material Symbols `monitor_heart` icon (white on transparent)
- `res/drawable/ic_launcher_background.xml` — solid dark navy `#1A237E`
- `res/mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` — adaptive icon wrappers
- Source SVG saved at repo root: `monitor_heart_24dp.svg` (not used by build; kept for reference)
- Legacy PNG mipmap files untouched — Android <8 devices continue to use them

### App name & strings

- Fixed typo in `strings.xml`: `AthletAssitant` → `Athlete EMG`
- Added three permission rationale strings: `perm_bt_scan_rationale`, `perm_bt_connect_rationale`, `perm_location_rationale`

### Onboarding screen (first-launch only)

- `OnboardingActivity.java` + `activity_onboarding.xml` — dark navy branded screen; explains the app, hardware requirement, and Demo Mode
- Two buttons: "Try Demo" (launches demo directly + marks onboarded) and "Got it — go to app" (dismisses)
- `SharedPreferences` key `onboarded` (in `app_prefs`) — screen never shown again after first interaction
- Registered in `AndroidManifest.xml`
- `MainActivity.onCreate` checks `OnboardingActivity.isOnboarded(this)` and starts onboarding if false

### Build result
`assembleDebug` → BUILD SUCCESSFUL (3s, 71 tasks)

### Remaining Phase 4 tasks (manual / out of Claude scope)
- Privacy policy page (host at GitHub Pages; link from Play Store listing and in-app Settings)
- Keystore generation + `signingConfigs` in `build.gradle` for release AAB
- Play Console: create app, upload AAB to Open Testing track, fill store listing, add screenshots

---

## 2026-05-31 (continued) — Linux build environment setup

### Context
Moved Android development from Windows/Android Studio to VSCode SSH remote on Linux.
Goal: build APK from command line on Linux for eventual CI and Play Store release.

### Work completed

**Android SDK installed** at `/local/mnt/workspace/wangx/android-sdk`:
- `platforms/android-34`
- `build-tools/34.0.0`
- `ndk/25.2.9519653`
- `platform-tools` (adb)
- Installed via `cmdline-tools-linux-14742923` (sdkmanager)

**Config changes:**
- `config.gradle`: bumped `compileSdkVersion` and `targetSdkVersion` from 30 → **34** (Play Store requires ≥34)
- `app/build.gradle`: `buildPython` changed from `C:/Python310/python.exe` → `/usr/bin/python3.10`
- `local.properties` (new): `sdk.dir=/local/mnt/workspace/wangx/android-sdk`
- `.vscode/settings.json` (new): `python.defaultInterpreterPath=/usr/bin/python3.10` for IntelliSense
- `~/.bashrc`: added `ANDROID_HOME` and `platform-tools` to PATH; `JAVA_HOME` (java-17) already present

### Next step
- Open a new terminal (`source ~/.bashrc`) and run `./gradlew assembleDebug`
- Fix any build errors from targetSdk 30→34 API behavior changes (exported activity flags, etc.)
