# High-Level Design — Android Python Layer (`btLeApp/app/src/main/python/`)

## Overview

The Android app embeds a Python layer (via **Chaquopy**) that runs EMG/IMU signal
processing on-device. The design goal is to **reuse the same algorithms developed and
validated on the Windows PC side** (`emg_util/`) while satisfying Android's runtime
constraints (no pandas, no matplotlib, no scipy).

---

## End-to-End Pipeline Comparison

### Windows PC pipeline

```
btCentral.py (BLE recorder)
    │  records live BLE stream, rotates CSV every 20 min
    │  reconnects automatically → new session_id per BLE connection
    ▼
<user-chosen folder per session>/
  emg_log_20260225_140000_*.csv   ← BLE connection 1 (rotated files)
  imu_log_20260225_140000_*.csv
  emg_log_20260225_142500_*.csv   ← BLE connection 2 (after reconnect)
  imu_log_20260225_142500_*.csv
  ...                             ← up to ~9 rotated files per stream for 3h
    │
    ▼
emg_util_yaml_runner.analyze_tennis_session(folder)
    │  capture_loader.discover_sessions() — groups files by session_id prefix
    │  capture_loader.load_session_stitched() — stitches all BLE connections
    │    using wall-clock offsets from session_id timestamps
    │  runs: activity gating → swing detection → EMG features → fatigue → bouts
    ▼
swing_features.csv, bout_summary.csv, fatigue_trend.png, ...
```

**Windows session boundary:** user puts each tennis session in a separate folder.

### Android pipeline

```
Android BLE recorder (Java/Kotlin)
    │  records raw BLE frames to frames_YYYYMMDD_HHMMSS.bin (one per BLE connection)
    │  all captures go into ONE flat folder (app-managed storage)
    ▼
<captures_dir>/                 ← single flat folder, all sessions mixed
  frames_20260225_140000.bin    ← BLE connection 1 (session A)
  frames_20260225_142500.bin    ← BLE connection 2 (25 min gap → same session A)
  frames_20260225_151000.bin    ← BLE connection 3 (8 min gap → same session A)
  frames_20260225_220000.bin    ← BLE connection 4 (4h gap → NEW session B)
  frames_20260226_090000.bin    ← BLE connection 5 (11h gap → NEW session C)
    │
    ▼
offline_runner.discover_sessions(captures_dir, gap_thr_min=30)
    │  groups consecutive captures with gap < 30 min → one tennis session
    │  returns list of sessions with their bin_files and capture_ids
    ▼
offline_runner.process_tennis_session(captures_dir, session_id="20260225_140000")
    │  for each frames_*.bin in session:
    │    _decode_bin_direct() → decoded_<stem>/emg_log_*.csv, imu_log_*.csv
    │  computes reconnection_times_s from filename timestamps
    │  returns all decoded CSV paths + reconnection metadata
    ▼
decoded CSVs (same schema as Windows) + reconnection_times_s
```

**Android session boundary:** consecutive captures with gap ≥ 30 minutes are
treated as separate tennis sessions. This mirrors Windows where the user puts
each session in a separate folder.

---

## Why `frames_YYYYMMDD_HHMMSS.bin` (Flat Folder)?

On Windows, `btCentral.py` writes CSV files directly during live BLE capture.
On Android, the Java/Kotlin BLE layer records raw frames to named `.bin` files because:
- Writing CSV from Java during live BLE is more complex
- Binary format is compact and lossless
- All captures go in one flat folder — simpler app storage management
- Session boundaries are detected automatically by the 30-minute gap rule
- `_decode_bin_direct()` converts each `.bin` to CSV offline (after capture ends),
  producing the same schema as Windows so the same analysis code can be reused

**Java requirement:** when establishing a BLE connection, create the file named
`frames_YYYYMMDD_HHMMSS.bin` where the timestamp is the wall-clock connection time.
This is the only requirement for stitching to work correctly.

---

## Session Boundary Rule

| Platform | How sessions are separated |
|----------|---------------------------|
| Windows | User puts each session in a separate folder |
| Android | `discover_sessions()`: gap ≥ `SESSION_GAP_MIN` (30 min) → new session |

The 30-minute threshold is configurable. A typical tennis session has BLE reconnects
every few minutes (watchdog timeout ~30s); a break between sessions is at least
30–60 minutes.

---

## File Responsibilities

### `offline_runner.py`  ← Android equivalent of `emg_util_yaml_runner.py`
- Post-capture offline analysis pipeline. Java module name: `"offline_runner"`.
- **`discover_sessions(captures_dir)`** — groups all `frames_*.bin` files into
  tennis sessions using the 30-minute gap rule.
- **`process_session(bin_path)`** — decodes ONE `frames_*.bin` → rotated CSVs.
- **`process_tennis_session(captures_dir, session_id)`** — decodes and stitches
  all BLE captures in a session; returns all decoded CSV paths and
  `reconnection_times_s`. Android equivalent of
  `emg_util_yaml_runner.analyze_tennis_session()`.
- Contains `_decode_bin_direct()` — the core binary decoder for named `.bin` files.

### `realtime_bridge.py`  ← Android-specific live fatigue monitoring
- Real-time fatigue monitoring during live BLE capture. Java module: `"realtime_bridge"`.
- No Windows equivalent — Android-only.
- **`reset_fatigue_monitor()`** — call at session start (`startSession()`).
- **`push_swing_realtime(emg_raw_json, t_peak_ms, imu_peak_mag)`** — call after
  each IMU swing trigger; returns fatigue level + warning flag.
- **`get_fatigue_summary()`** — call at session end (`stopSession()`).
- **`run_demo()`** — smoke test.

### `analysis_bridge.py`  ← compatibility shim (deprecated)
- Re-exports everything from `offline_runner` and `realtime_bridge`.
- Kept so existing Java code using `"analysis_bridge"` continues to work.
- New Java code should import `"offline_runner"` or `"realtime_bridge"` directly.

### `btcentral_offline_decode.py`  ← low-level binary decoder (original API)
- Reads `frames.bin` from a session directory (original single-file API).
- Parses the same binary TLV protocol as the Arduino firmware (magic, CRC16-CCITT,
  EMG/IMU/vitals TLVs).
- Extends the 32-bit `micros()` device clock to 64-bit (`DeviceClockExtender`).
- Writes rotated CSV files with the same schema as Windows `btCentral.py`.
- Note: `host_rx_ms` cannot be reconstructed offline; written as 0.
- `offline_runner._decode_bin_direct()` extends this to handle named `.bin` files.

### `emg_util_nopandas.py`  ← Android equivalent of `emg_util.py` algorithms
- Pandas-free port of the core signal processing algorithms from `emg_util/emg_util.py`.
- Uses only **numpy** and Python stdlib (no pandas, no matplotlib, no scipy).
- Provides: EMG envelope, swing detection, per-swing features (MDF, RMS, WL, ZC, SSC),
  baseline computation.
- **Function names are aligned with `emg_util.py`** — the `_nopandas` suffix has been
  removed from public functions; old names are kept as backward-compat aliases:

  | Canonical name (both files) | Old Android-only name (alias) |
  |-----------------------------|-------------------------------|
  | `compute_swing_emg_features` | `compute_swing_emg_features_nopandas` |
  | `select_baseline_swings` | `select_baseline_swings_nopandas` |
  | `compute_baseline_from_swing_features` | `compute_baseline_from_swing_features_nopandas` |
  | `apply_baseline_deltas` | `apply_baseline_deltas_nopandas` |
  | `detect_swing_windows_from_imu_arrays` | `detect_swings_from_imu` (alias) |

- **Cross-platform swing detection names:**
  - Android canonical: `detect_swing_windows_from_imu_arrays(ts_ms, ax, ay, az, gx, gy, gz, ...)` — takes numpy arrays
  - Windows canonical: `detect_swing_windows_from_imu(imu_df, ...)` — takes pandas DataFrame
  - `detect_swings_from_imu` is an alias for `detect_swing_windows_from_imu_arrays` on Android
  - `detect_swing_windows_from_imu_arrays` is a thin DataFrame-building wrapper on Windows

### `fatigue_monitor.py`  ← single source of truth, shared with PC
- Canonical `FatigueMonitor` and `preprocess_emg` — pure numpy + stdlib, runs identically on PC and Android.
- **Source of truth: `emg_util/fatigue_monitor.py`.**
- Exports:
  - `preprocess_emg(x, fs)` — **public API** for EMG bandpass filter (50–450 Hz + 60/120 Hz notch).
    This is the **single source of truth for filter parameters** across PC and Android.
    Both `emg_util.py` and `emg_util_nopandas.py` import `preprocess_emg` from here — they do NOT define their own copies.
  - `FatigueMonitor` — stateful per-session fatigue tracker.
- This file is a copy refreshed before each APK build (and whenever filter parameters change):
  ```
  copy emg_util\fatigue_monitor.py btLeApp\app\src\main\python\fatigue_monitor.py
  ```

---

## Dependency Graph (Android APK)

```
offline_runner.py               (post-capture analysis)
    └── btcentral_offline_decode.py
            └── numpy

realtime_bridge.py              (live fatigue monitoring)
    ├── fatigue_monitor.py
    │       └── numpy
    └── emg_util_nopandas.py
            └── numpy

analysis_bridge.py              (compatibility shim)
    ├── offline_runner.py
    └── realtime_bridge.py

btcentral_offline_decode.py     (also usable standalone)
    └── numpy
```

No pandas, no matplotlib, no scipy — all forbidden in the Android Python layer.

---

## Schema Compatibility: Android ↔ Windows

`_decode_bin_direct()` produces CSV files with the same column schema as `btCentral.py`:

| File | Key columns |
|------|-------------|
| `emg_log_*.csv` | `dev_us`, `emg_seq`, `env`, `raw`, `ref`, `frame_seq`, `host_rx_ms` |
| `imu_log_*.csv` | `dev_us`, `imu_seq`, `ax`, `ay`, `az`, `gx`, `gy`, `gz`, `acc_fsr_g`, `gyro_fsr_dps`, `frame_seq`, `host_rx_ms` |

The decoded CSV folder can be transferred to PC and analyzed directly with
`emg_util_yaml_runner.py` without re-decoding.

---

## Maintenance Rules

| Scenario | Action |
|----------|--------|
| **EMG filter parameters change** | Edit **only** `emg_util/fatigue_monitor.py`, then `copy emg_util\fatigue_monitor.py btLeApp\app\src\main\python\fatigue_monitor.py`. Do NOT edit `emg_util.py` or `emg_util_nopandas.py` — they import `preprocess_emg` from `fatigue_monitor`. |
| `FatigueMonitor` logic changes | Edit `emg_util/fatigue_monitor.py`, copy to `btLeApp/.../python/` before APK build |
| Core EMG algorithm changes | Update `emg_util/emg_util.py` (PC), port changes to `emg_util_nopandas.py` (Android) |
| Adding a new analysis function | Use the canonical name (no `_nopandas` suffix) in both files; old aliases remain for backward compat |
| Swing detection logic changes | Update `detect_swing_windows_from_imu` in `emg_util.py` AND `detect_swing_windows_from_imu_arrays` in `emg_util_nopandas.py` |
| Binary protocol changes (firmware) | Update `btCentral.py` (Windows) AND `btcentral_offline_decode.py` (Android) in sync |
| Session gap threshold change | Update `SESSION_GAP_MIN` in `offline_runner.py` |
| New Python dependency on Android | Must be pandas-free and numpy-compatible; add to Chaquopy `pip` config in `build.gradle` |

---

## Why Not Share One Analysis File?

`emg_util.py` and `emg_util_yaml_runner.py` import pandas, matplotlib, scipy, and
seaborn at module level. These packages are either unavailable on Android or
prohibitively large for APK packaging. The split is intentional:

- **PC**: full-featured versions for exploratory analysis, plotting, batch processing.
- **Android**: stripped numpy-only versions for real-time on-device inference.

`FatigueMonitor` and `preprocess_emg` are the exceptions — designed pure-numpy from
the start so they can be shared as a single file (`fatigue_monitor.py`) across both
environments without modification. `emg_util.py` and `emg_util_nopandas.py` both
import `preprocess_emg` from `fatigue_monitor.py` rather than defining their own copies,
ensuring the filter parameters are always in sync.
