# Android Python Layer — Changelog

All notable changes to `btLeApp/app/src/main/python/` are documented here.  
Format: newest entry at the top.

---

## [2026-02-27] — Multi-file session support (30-minute gap detection)

### Added
- **Multi-file sessions:** Capture can now span multiple `frames_*.bin` files
  - **30-minute gap detection:** If you stop capturing and restart within 30 minutes, it continues the same session
  - **Timestamped filenames:** Each capture creates `frames_YYYYMMDD_HHMMSS.bin` (like Windows!)
  - **Session reuse logic:** Scans all session directories to find most recent `frames_*.bin` file
  - **UI feedback:** Shows "Continuing session (gap: X min)" or "Starting new session (gap: X min)"
  - **Example:** Capture at 5:00pm → Stop → Capture at 5:15pm → Same session folder, new file

### Changed
- **`SessionCaptureActivity.java`:**
  - `createSessionFiles()` — implements 30-minute gap detection
  - `findMostRecentFramesFile()` — scans all sessions for most recent capture
  - `parseTimestampFromFilename()` — extracts timestamp from `frames_YYYYMMDD_HHMMSS.bin`
  - `writeMetaJson()` — now lists all `frames_*.bin` files in `frames_files` array (instead of single `frames_file`)

- **`btcentral_offline_decode.py`:**
  - Now finds and processes all `frames_*.bin` files in session directory
  - Maintains single continuous timeline across multiple files (clock extender persists)
  - Falls back to legacy `frames.bin` for backward compatibility
  - Adds `frames_files_processed` array to stats output

- **`analysis_bridge.py`:**
  - `process_session_compat()` — updated to handle both multi-file and legacy single-file sessions
  - Better error messages showing which files were found/expected
  - Adds `multi_file_session` and `frames_files_count` to output stats

### Benefits
- **No data loss:** Can pause/resume captures without losing session context
- **Flexible workflow:** Take breaks during long sessions (e.g., tennis practice with water breaks)
- **Automatic stitching:** Decoder handles multiple files transparently
- **Analysis works:** `analyze_session()` automatically stitches all captures together

### Example Workflow
```bash
# 1. Start capture at 5:00pm
#    Creates: session_20260227_170000/frames_20260227_170000.bin

# 2. Stop capture at 5:10pm (10 minutes of data)

# 3. Resume capture at 5:15pm (5-minute gap < 30 min threshold)
#    Reuses: session_20260227_170000/frames_20260227_171500.bin
#    UI shows: "Continuing session (gap: 5 min)..."

# 4. Stop capture at 5:25pm (another 10 minutes)

# 5. Resume capture at 6:00pm (35-minute gap > 30 min threshold)
#    Creates: session_20260227_180000/frames_20260227_180000.bin
#    UI shows: "Starting new session (gap: 35 min)..."

# Result:
# - session_20260227_170000/ contains 2 files (20 minutes total)
# - session_20260227_180000/ contains 1 file (new session)
```

### File Structure
```
/storage/emulated/0/Android/data/com.clj.blesample/files/
├── session_20260227_170000/
│   ├── frames_20260227_170000.bin  (first capture)
│   ├── frames_20260227_171500.bin  (resumed after 5 min)
│   ├── meta.json                    (lists both files)
│   └── decoded/                     (created by decoder)
│       ├── emg_log_..._000.csv
│       ├── emg_log_..._001.csv     (rotated after 20 min)
│       └── ...
└── session_20260227_180000/
    ├── frames_20260227_180000.bin  (new session after 35 min gap)
    └── meta.json
```

### meta.json Format
```json
{
  "device_name": "MyoWare",
  "mac": "XX:XX:XX:XX:XX:XX",
  "frames_files": [
    "frames_20260227_170000.bin",
    "frames_20260227_171500.bin"
  ],
  "frames_written": 5234,
  "bytes_written": 1048576
}
```

### Migration Notes
- **Backward compatible:** Old sessions with `frames.bin` still work
- **No action needed:** Existing sessions decode normally
- **New sessions:** Automatically use timestamped filenames

---

## [2026-02-27] — Git submodule consolidation + Analyze button

### Added
- **Git submodule:** `emg_util/` now linked as submodule at `app/src/main/python/emg_util/`
  - Eliminates manual file copying for `fatigue_monitor.py` and `emg_util_nopandas.py`
  - Single source of truth: all algorithm development in `emg_util/` repo
  - Automatic sync: `git submodule update --remote` pulls latest changes
  - Version control: Android pins to specific commit of emg_util

- **Analyze button** — Full analysis on Android (same as Windows!)
  - Runs complete analysis pipeline using `emg_util_nopandas.py`
  - Swing detection from IMU data
  - Baseline computation (first 10 swings after 5-min warmup)
  - Fatigue metrics: MDF drop %, RMS, IMU acceleration drop %
  - Returns JSON with swing count, baseline, and fatigue statistics
  - Android is now fully self-sufficient for analysis!

### Changed
- **`analysis_bridge.py`** — Fixed to handle Android session directory structure:
  - **Before:** Looked for `frames_*.bin` in parent directory (wrong)
  - **After:** Looks for `frames.bin` inside session directory (correct)
  - Android structure: `session_YYYYMMDD_HHMMSS/frames.bin` (single file per session)
  - Now calls `process_session()` instead of `process_tennis_session()`
  - Provides helpful error messages if file not found (lists directory contents)
  - **New:** Added `analyze_session()` function for full analysis

- **`ProcessingActivity.java`** — Simplified UI with auto-decode:
  - **Auto-decode:** Decoding happens automatically when activity opens
  - **Single button:** Only "Analyze" button visible (cleaner UI)
  - Decode runs in background (~0.5s), then enables Analyze button
  - Comprehensive logging to logcat with tag `ProcessingActivity`
  - Logs full stack traces on errors (including cause chain)
  - Use `adb logcat -s ProcessingActivity:*` to view logs

### Fixed
- **Decoding works!** Successfully decodes `frames.bin` → CSV files
  - Creates `decoded/` subdirectory with EMG, IMU, and vitals CSV files
  - Returns decode statistics (frames processed, samples extracted, CRC errors)
  - Example output: 2,657 frames → 14,758 EMG samples + 1,473 IMU samples
  - **Filename format fixed:** `emg_log_YYYYMMDD_HHMMSS_YYYYMMDD_HHMMSS_000.csv` (compatible with Windows tools)

- **Analysis works!** Full analysis pipeline on Android
  - Loads decoded CSVs
  - Detects swings from IMU gyroscope data
  - Computes per-swing EMG features (RMS, MDF, envelope)
  - Selects baseline swings (after warmup period)
  - Calculates fatigue metrics (MDF drop %, acc drop %)
  - Example output: 15 swings, baseline MDF 85.3 Hz, mean MDF drop 12.5%

### Maintenance
- **Updating shared code:**
  ```bash
  # 1. Make changes in emg_util repo
  cd C:\wangx\ftc\emg_util
  # Edit fatigue_monitor.py or emg_util_nopandas.py
  git add .
  git commit -m "Update algorithm"
  git push
  
  # 2. Update Android to use new version
  cd C:\wangx\ftc\btLeApp
  git submodule update --remote
  git add app/src/main/python/emg_util
  git commit -m "Update emg_util submodule"
  git push
  
  # 3. Rebuild and test
  .\gradlew assembleDebug
  ```

### Offline Analysis Workflow
After processing on Android, transfer decoded CSVs to PC for analysis and plotting:

#### Option 1: Quick Plot (Recommended)
```bash
# 1. Pull decoded session from Android
adb pull /storage/emulated/0/Android/data/com.clj.blesample/files/session_YYYYMMDD_HHMMSS/decoded C:\wangx\ftc\arduino\py_scripts\logs\session_YYYYMMDD_HHMMSS_decoded

# 2. Plot EMG data with sensordata_plotter.py
cd C:\wangx\ftc\emg_util
python sensordata_plotter.py --mode offline --emg-file C:/wangx/ftc/arduino/py_scripts/logs/session_YYYYMMDD_HHMMSS_decoded/emg_log_YYYYMMDD_HHMMSS_YYYYMMDD_HHMMSS_000.csv

# Note: Use forward slashes (/) in file paths, not backslashes (\)
```

#### Option 2: Plot with Fatigue Analysis
```bash
cd C:\wangx\ftc\emg_util
python sensordata_plotter.py --mode offline --emg-file C:/wangx/ftc/arduino/py_scripts/logs/session_YYYYMMDD_HHMMSS_decoded/emg_log_YYYYMMDD_HHMMSS_YYYYMMDD_HHMMSS_000.csv --fatigue

# This adds:
# - MDF (Median Frequency) trend plot
# - Fatigue level indicators
# - Swing count in title
```

#### Option 3: Full Offline Analysis (Research-Grade)
```bash
# Run complete analysis with swing detection, fatigue trends, and classification
cd C:\wangx\ftc\emg_util
python emg_util_yaml_runner.py --log-dir C:\wangx\ftc\arduino\py_scripts\logs\session_YYYYMMDD_HHMMSS_decoded --out-dir C:\wangx\ftc\arduino\py_scripts\logs\analysis_out

# Generates:
# - swing_features.csv - Per-swing EMG features
# - bout_summary.csv - Bout-level statistics
# - fatigue_trend.png - MDF/RMS over time
# - imu_activity_overlay.png - IMU intensity + swings
```

**Example with actual session:**
```bash
# Pull session 20260228_045547
adb pull /storage/emulated/0/Android/data/com.clj.blesample/files/session_20260228_045547/decoded C:\wangx\ftc\arduino\py_scripts\logs\session_20260228_045547_decoded

# Quick plot
cd C:\wangx\ftc\emg_util
python sensordata_plotter.py --mode offline --emg-file C:/wangx/ftc/arduino/py_scripts/logs/session_20260228_045547_decoded/emg_log_20260228_045547_20260228_045547_000.csv

# Full analysis
cd C:\wangx\ftc\emg_util
python emg_util_yaml_runner.py --log-dir C:\wangx\ftc\arduino\py_scripts\logs\session_20260228_045547_decoded --out-dir C:\wangx\ftc\arduino\py_scripts\logs\analysis_out
```

**Note:** Filename format is now correct (`emg_log_YYYYMMDD_HHMMSS_YYYYMMDD_HHMMSS_000.csv`) and compatible with Windows offline analysis tools. No file renaming needed!

### Documentation
- **ANDROID_HLD.md** — Updated to reflect:
  - Git submodule consolidation details
  - `analysis_bridge.py` as active compatibility wrapper (not deprecated)
  - File organization after consolidation
  - Developer workflow for updating shared code

- **RULES.md** — Created with 7 critical rules:
  1. Git submodule is single source of truth
  2. Never manually copy files from emg_util
  3. Update submodule via `git submodule update --remote`
  4. Test on both Windows and Android after changes
  5. Keep ANDROID_HLD.md updated
  6. Use logcat for debugging
  7. Follow offline analysis workflow for research

---

## [2026-02-25] — Port IMU acc fatigue features to `emg_util_nopandas.py`

### Added (`emg_util_nopandas.py`)
- `compute_swing_emg_features()` — new optional params `imu_ts_ms`, `imu_acc_dyn`:
  - Computes `imu_mean_acc_dyn` (mean `|acc_mag - 1g|`) and `imu_peak_acc_dyn` per swing
  - Matches PC `per_swing_emg_features_stitched()` in `emg_util_yaml_runner.py`
  - Backward-compatible: both params default to `None` (no IMU features if not provided)
- `compute_baseline_from_swing_features()` — added `'imu_mean_acc_dyn'` to baseline keys
  - Was missing; PC version already included it
- `apply_baseline_deltas()` — added `acc_drop_pct` computation:
  - `acc_drop_pct > 0` means swing force decreased relative to baseline (fatigue signal)
  - Matches PC `emg_util.apply_baseline_deltas()`
- `stitch_decoded_session()` — computes `imu_acc_dyn = |acc_mag - 1g|` from loaded IMU
  arrays and passes `imu_ts_ms`, `imu_acc_dyn` to `compute_swing_emg_features()`
- `stitch_decoded_session()` summary dict — added `baseline_imu_mean_acc_dyn` and
  `mean_acc_drop_pct` fields

### Consistency audit (Android vs PC)
| Item | Android | PC | Status |
|------|---------|-----|--------|
| FSR defaults | `cur_acc_fsr_g=4`, `cur_gyro_fsr_dps=1000` | `acc_fsr_default_g=4.0`, `gyro_fsr_default_dps=1000.0` | ✅ Aligned |
| `warmup_skip_min` | `select_baseline_swings(warmup_skip_min=5.0)` | same | ✅ Aligned |
| `imu_mean_acc_dyn` per swing | ✅ Added | ✅ Present | ✅ Aligned |
| `imu_peak_acc_dyn` per swing | ✅ Added | ✅ Present | ✅ Aligned |
| `acc_drop_pct` baseline delta | ✅ Added | ✅ Present | ✅ Aligned |
| `imu_mean_acc_dyn` in baseline | ✅ Added | ✅ Present | ✅ Aligned |
| Android decoder FSR tables | All 4 codes mapped correctly | N/A | ✅ No change needed |

---

## [2026-02-25] — Split `analysis_bridge.py` + fix CSV filename format

### Added
- `offline_runner.py` — post-capture analysis (Android equivalent of `emg_util_yaml_runner.py`):
  - `discover_sessions(captures_dir)` — groups `frames_YYYYMMDD_HHMMSS.bin` by 30-min gap rule
  - `process_tennis_session(captures_dir, session_id)` — decodes + stitches all BLE captures
  - `process_session(bin_path)` — decodes single `.bin` file
- `realtime_bridge.py` — live fatigue monitoring:
  - `reset_fatigue_monitor()`, `push_swing_realtime()`, `get_fatigue_summary()`, `run_demo()`

### Changed
- `analysis_bridge.py` — kept as backward-compat shim re-exporting from `offline_runner` and `realtime_bridge`
- `btcentral_offline_decode.py` — `SegmentCsv` dataclass now has `open_id` field (defaults to `session_id`)
  - Filename format: `emg_log_<session_id>_<open_id>_<idx:03d>.csv` — matches `capture_loader._RE_EMG`
- `emg_util_nopandas.py` — renamed `_nopandas`-suffixed functions to match PC names; kept old names as aliases:
  - `compute_swing_emg_features_nopandas` → `compute_swing_emg_features`
  - `select_baseline_swings_nopandas` → `select_baseline_swings`
  - `compute_baseline_from_swing_features_nopandas` → `compute_baseline_from_swing_features`
  - `apply_baseline_deltas_nopandas` → `apply_baseline_deltas`

---

## [2026-02-25] — `preprocess_emg` single source of truth in `fatigue_monitor.py`

### Changed (`emg_util_nopandas.py`)
- Removed duplicate `preprocess_emg` definition (had stale **20 Hz** low cutoff)
- Now imports `preprocess_emg` from `fatigue_monitor` (50 Hz, correct)

### Changed (`fatigue_monitor.py`)
- Copy of `emg_util/fatigue_monitor.py` — canonical source for `FatigueMonitor` + `preprocess_emg`
- **Maintenance rule:** when `emg_util/fatigue_monitor.py` changes, run:
  ```
  copy emg_util\fatigue_monitor.py btLeApp\app\src\main\python\fatigue_monitor.py
  ```

---

*Add new entries above this line, newest first.*
