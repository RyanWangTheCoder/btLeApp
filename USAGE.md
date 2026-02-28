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

## Related Documentation

- **Python Analysis Code:** See `app/src/main/python/emg_util/HIGH_LEVEL_DESIGN.md`
- **Android-Python Bridge:** See `app/src/main/python/ANDROID_HLD.md`
- **Fatigue Monitoring:** See `app/src/main/python/emg_util/emg_fatigue_research_notes.md`
