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
