# BLE Sample App - Usage Guide

## Git Submodule Management

This Android app uses a **git submodule** to share Python analysis code with the desktop tools. The submodule links to the `emg_util` repository, which contains shared Python modules for EMG/IMU analysis, fatigue monitoring, and swing detection.

### What is a Git Submodule?

A git submodule is a reference to another git repository at a specific commit. Instead of copying Python files into the Android app, we link to the `emg_util` repository. This ensures:
- **Single source of truth** - Python code is maintained in one place
- **Version control** - Android app pins to a specific `emg_util` commit
- **Easy updates** - Pull latest analysis improvements with one command

### Submodule Location

```
btLeApp/
├── app/
│   └── src/
│       └── main/
│           └── python/
│               ├── analysis_bridge.py       (Android-specific)
│               ├── realtime_bridge.py       (Android-specific)
│               ├── offline_runner.py        (Android-specific)
│               └── emg_util/                ← GIT SUBMODULE
│                   ├── fatigue_monitor.py   (shared)
│                   ├── emg_util_nopandas.py (shared)
│                   ├── capture_loader.py    (shared)
│                   └── ...
```

The `emg_util/` directory is a git submodule pointing to:
- **Repository:** `https://github.com/wangxdflight/emg_util`
- **Current commit:** Check `.gitmodules` or run `git submodule status`

---

## Common Submodule Commands

### 1. Initial Clone (First Time Setup)

When cloning the `btLeApp` repository for the first time, the submodule directory will be empty. Initialize it:

```bash
cd C:\wangx\ftc\btLeApp
git submodule update --init --recursive
```

**What this does:**
- `--init` - Initializes the submodule (registers it from `.gitmodules`)
- `--recursive` - Handles nested submodules (if any)
- Downloads the `emg_util` repository at the pinned commit

**Expected output:**
```
Submodule 'app/src/main/python/emg_util' (https://github.com/wangxdflight/emg_util) registered for path 'app/src/main/python/emg_util'
Cloning into 'C:/wangx/ftc/btLeApp/app/src/main/python/emg_util'...
Submodule path 'app/src/main/python/emg_util': checked out 'fd6f58f68550765e3f8376b69dee5f357164e021'
```

### 2. Update Submodule to Latest Commit

To pull the latest changes from the `emg_util` repository:

```bash
cd C:\wangx\ftc\btLeApp
git submodule update --remote
```

**What this does:**
- Fetches the latest commit from the `emg_util` repository
- Updates the submodule to the newest version
- **Important:** This changes which commit the Android app uses

After updating, you must commit the new submodule reference:

```bash
git add app/src/main/python/emg_util
git commit -m "Update emg_util submodule to latest"
```

### 3. Check Submodule Status

To see which commit the submodule is currently at:

```bash
cd C:\wangx\ftc\btLeApp
git submodule status
```

**Example output:**
```
 fd6f58f68550765e3f8376b69dee5f357164e021 app/src/main/python/emg_util (heads/main)
```

The commit hash shows which version of `emg_util` is currently checked out.

### 4. Work Inside the Submodule

If you need to make changes to the Python analysis code:

```bash
cd C:\wangx\ftc\btLeApp\app\src\main\python\emg_util

# Make changes to Python files
# Commit changes to emg_util repository
git add .
git commit -m "Fix fatigue monitor bug"
git push origin main

# Go back to parent repo and update submodule reference
cd C:\wangx\ftc\btLeApp
git add app/src/main/python/emg_util
git commit -m "Update emg_util submodule with bug fix"
```

### 5. Reset Submodule (Fix Detached HEAD)

If the submodule gets into a "detached HEAD" state:

```bash
cd C:\wangx\ftc\btLeApp\app\src\main\python\emg_util
git checkout main
git pull origin main
```

---

## Troubleshooting

### Error: `ModuleNotFoundError: No module named 'fatigue_monitor'`

**Cause:** The submodule directory is empty (not initialized).

**Fix:**
```bash
cd C:\wangx\ftc\btLeApp
git submodule update --init --recursive
```

### Error: Submodule directory exists but is empty

**Cause:** Submodule was not cloned during initial `git clone`.

**Fix:**
```bash
cd C:\wangx\ftc\btLeApp
git submodule update --init --recursive
```

### Error: Submodule is at wrong commit

**Cause:** Someone updated the submodule reference but you haven't pulled it.

**Fix:**
```bash
cd C:\wangx\ftc\btLeApp
git pull
git submodule update --init --recursive
```

### Submodule shows uncommitted changes

**Cause:** You edited files inside the submodule directory.

**Options:**
1. **Commit changes to `emg_util` repository:**
   ```bash
   cd app/src/main/python/emg_util
   git add .
   git commit -m "Your changes"
   git push origin main
   ```

2. **Discard changes:**
   ```bash
   cd app/src/main/python/emg_util
   git checkout .
   ```

---

## Best Practices

### 1. Always Initialize Submodules After Clone

When cloning the repository on a new machine:
```bash
git clone <repo-url>
cd btLeApp
git submodule update --init --recursive
```

Or use the `--recurse-submodules` flag during clone:
```bash
git clone --recurse-submodules <repo-url>
```

### 2. Keep Submodule Updated

Periodically update to get latest analysis improvements:
```bash
cd C:\wangx\ftc\btLeApp
git submodule update --remote
git add app/src/main/python/emg_util
git commit -m "Update emg_util to latest"
```

### 3. Don't Edit Submodule Files Directly

If you need to change Python analysis code:
1. Work in the standalone `emg_util` repository
2. Test changes there first
3. Push to `emg_util` repository
4. Update submodule reference in `btLeApp`

### 4. Check Submodule Status Before Building

Before building the Android app, verify the submodule is initialized:
```bash
git submodule status
```

If you see a `-` prefix, the submodule is not initialized:
```
-fd6f58f68550765e3f8376b69dee5f357164e021 app/src/main/python/emg_util
```

Run `git submodule update --init` to fix.

---

## Android Studio Integration

Android Studio should automatically detect the submodule. However, if you see Python import errors:

1. **Sync Project with Gradle Files** (File → Sync Project)
2. **Invalidate Caches** (File → Invalidate Caches / Restart)
3. **Verify submodule is initialized:**
   ```bash
   cd C:\wangx\ftc\btLeApp
   git submodule update --init --recursive
   ```

---

## Quick Reference

| Task | Command |
|------|---------|
| Initialize submodule (first time) | `git submodule update --init --recursive` |
| Update to latest `emg_util` | `git submodule update --remote` |
| Check submodule status | `git submodule status` |
| Reset submodule to pinned commit | `git submodule update` |
| Clone repo with submodules | `git clone --recurse-submodules <url>` |

---

## Building and Installing the Android App

### Platform options

| | Windows | Linux SSH remote |
|---|---|---|
| Build tool | `.\gradlew assembleDebug` | `./gradlew assembleDebug` |
| APK path | `app\build\outputs\apk\debug\app-debug.apk` | `app/build/outputs/apk/debug/app-debug.apk` |
| ADB install path | `C:\wangx\ftc\btLeApp\...\app-debug.apk` | `H:\projects\btLeApp\...\app-debug.apk` (H: = Linux workspace mounted on Windows) |

---

### Linux SSH remote — one-time environment setup

The Linux build environment is at `/local/mnt/workspace/wangx/android-sdk`.
These are already configured; listed here for reference if you need to rebuild from scratch.

**Android SDK** (installed at `/local/mnt/workspace/wangx/android-sdk`):
- `platforms;android-34`
- `build-tools;34.0.0`
- `ndk;25.2.9519653`
- `platform-tools` (adb)

**`~/.bashrc`** — env vars (already added):
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=/local/mnt/workspace/wangx/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
```

**`local.properties`** in project root (already present):
```
sdk.dir=/local/mnt/workspace/wangx/android-sdk
```

**`app/build.gradle`** — `buildPython` (already set):
```groovy
buildPython "/usr/bin/python3.10"
```

To reinstall SDK from scratch:
```bash
mkdir -p /local/mnt/workspace/wangx/android-sdk/cmdline-tools
cd /tmp
curl -L -o commandlinetools.zip https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip
unzip -q commandlinetools.zip -d /local/mnt/workspace/wangx/android-sdk/cmdline-tools
mv /local/mnt/workspace/wangx/android-sdk/cmdline-tools/cmdline-tools \
   /local/mnt/workspace/wangx/android-sdk/cmdline-tools/latest
PATH=/bin:/usr/bin bash -c 'yes | ANDROID_HOME=/local/mnt/workspace/wangx/android-sdk \
  /local/mnt/workspace/wangx/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  --sdk_root=/local/mnt/workspace/wangx/android-sdk --licenses'
PATH=/bin:/usr/bin JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 bash -c \
  'ANDROID_HOME=/local/mnt/workspace/wangx/android-sdk \
  /local/mnt/workspace/wangx/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  --sdk_root=/local/mnt/workspace/wangx/android-sdk \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;25.2.9519653"'
```

---

### 1. Build the APK

**Windows:**
```bash
cd C:\wangx\ftc\btLeApp
.\gradlew assembleDebug
```

**Linux SSH remote** (open a new terminal after setting up env above):
```bash
cd /local/mnt/workspace/wangx/projects/btLeApp
./gradlew assembleDebug
```

**What this does:**
- Compiles Java code
- Packages Python files via Chaquopy (includes `emg_util` submodule, numpy, scipy)
- Creates debug APK at: `app/build/outputs/apk/debug/app-debug.apk`

**Build time:** ~2–5 min first build (Chaquopy downloads Python runtime); ~30–60 s incremental.

**APK size note:** `arm64-v8a` only (debug) ≈ 60–80 MB. Both ABIs would double that.
For Play Store, use `./gradlew bundleRelease` (`.aab`) — Play handles per-device ABI splits.

---

### 2. Install on Android Device

#### Prerequisites
- Enable USB debugging on your Android device
- Connect device via USB
- Verify connection: `adb devices`

#### Check device Android version
```bash
adb shell getprop ro.build.version.release
```
Must be ≥ 7.0 (API 24). Android 9 (API 28) is fully supported for testing.
Note: Android 12+ BLE permission behavior differs — test on a newer device before Play Store submission.

#### Installation Commands

**Option A: Fresh Install (Recommended)**

If you get a signature mismatch error, uninstall the old version first:

```bash
# Uninstall old version
adb uninstall com.clj.blesample

# Install — built on Windows
adb install C:\wangx\ftc\btLeApp\app\build\outputs\apk\debug\app-debug.apk

# Install — built on Linux remote (workspace mounted as H: on Windows)
adb install H:\projects\btLeApp\app\build\outputs\apk\debug\app-debug.apk
```

**Option B: Update Install**

If no signature issues (same signing key):

```bash
adb install -r C:\wangx\ftc\btLeApp\app\build\outputs\apk\debug\app-debug.apk
```

The `-r` flag replaces the existing app without uninstalling.

**Install speed:** APK is ~60–80 MB — expect 1–3 min over USB. `Performing Streamed Install` at the start is normal; wait for `Success`.

---

### 3. Troubleshooting Installation

#### Error: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

**Full error:**
```
adb.exe: failed to install app-debug.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: 
Package com.clj.blesample signatures do not match previously installed version; ignoring!]
```

**Cause:** The APK is signed with a different key than the installed version.

**Fix:** Uninstall first, then install:
```bash
adb uninstall com.clj.blesample
adb install C:\wangx\ftc\btLeApp\app\build\outputs\apk\debug\app-debug.apk
```

**Note:** Uninstalling will delete all app data (captured sessions, settings, etc.). Back up important data first if needed.

#### Error: `protocol fault (couldn't read status): connection reset`

**Cause:** ADB server is in a bad state.

**Fix:** Restart ADB server:
```bash
adb kill-server
adb start-server
adb devices
```

#### Error: `no devices/emulators found`

**Cause:** Device not connected or USB debugging not enabled.

**Fix:**
1. Check USB cable connection
2. Enable USB debugging on device (Settings → Developer Options)
3. Accept "Allow USB debugging" prompt on device
4. Run `adb devices` to verify

---

### 4. Complete Build & Install Workflow

**Windows:**
```bash
cd C:\wangx\ftc\btLeApp
git submodule update --init --recursive
.\gradlew assembleDebug
adb devices
adb uninstall com.clj.blesample
adb install app\build\outputs\apk\debug\app-debug.apk
```

**Linux SSH remote:**
```bash
cd /local/mnt/workspace/wangx/projects/btLeApp
git submodule update --init --recursive
./gradlew assembleDebug
# Then on Windows (H: = Linux workspace):
# adb uninstall com.clj.blesample
# adb install H:\projects\btLeApp\app\build\outputs\apk\debug\app-debug.apk
```

---

### 5. Verify Installation

After installation, verify the app is working:

```bash
# Check Android version on device (must be ≥ 7.0 / API 24)
adb shell getprop ro.build.version.release

# Check if app is installed
adb shell pm list packages | findstr blesample

# Launch the app
adb shell am start -n com.clj.blesample/.MainActivity

# Monitor logs
adb logcat -s SessionCapture:* ProcessingActivity:*
```

---

## Play Store Release — Open Testing Track

### Why Open Testing?
- Publicly accessible via link (not search-discoverable)
- Google runs a **pre-launch report** on real Android 12/13/14 devices automatically — no need to own those devices
- Free; no review required to start

### One-time setup (do once)
1. Create a Google Play Console account at play.google.com/console (~$25 one-time fee)
2. Create a new app → set up store listing (name, description, screenshots)
3. Go to **Setup → App signing** — let Play manage the signing key

### Build a release AAB (not APK)

Play Store requires an `.aab` (App Bundle), not an `.apk`. The bundle lets Play split by ABI/language per device — so `x86_64` comes back automatically for emulators without bloating the download.

**Windows:**
```bash
cd C:\wangx\ftc\btLeApp
.\gradlew bundleRelease
```

**Linux SSH remote:**
```bash
cd /local/mnt/workspace/wangx/projects/btLeApp
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

### Upload to Play Console
1. Play Console → your app → **Testing → Open testing**
2. Create a new release → upload `app-release.aab`
3. Play will show a **pre-launch report** within ~1 hour covering:
   - Crashes on Android 9 / 12 / 13 / 14 real devices
   - BLE permission behavior on Android 12+ (this is what we're testing for)
   - ANR (app not responding) issues
4. Fix any flagged issues, rebuild, re-upload

### BLE permissions — what to expect in the report
The manifest now declares both old (`BLUETOOTH`/`BLUETOOTH_ADMIN`) and new (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`) permissions:
- **Android ≤ 11**: uses `BLUETOOTH` + `BLUETOOTH_ADMIN` + Location (current behavior, no change)
- **Android 12+**: uses `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`; `neverForLocation` flag avoids the location permission prompt for BLE scanning

If the pre-launch report flags a crash on Android 12+ in `MainActivity.checkPermissions()`, it's because the runtime permission request for `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` is not yet wired. That Java-side change is a Phase 5 (Play Store Readiness) task.

---

## Related Documentation

- **Python Analysis Code:** See `app/src/main/python/emg_util/HIGH_LEVEL_DESIGN.md`
- **Android-Python Bridge:** See `app/src/main/python/ANDROID_HLD.md`
- **Fatigue Monitoring:** See `app/src/main/python/emg_util/emg_fatigue_research_notes.md`
