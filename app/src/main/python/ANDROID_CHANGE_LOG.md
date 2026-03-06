# Android Python Layer — Changelog

All notable changes to `btLeApp/app/src/main/python/` are documented here.  
Format: newest entry at the top.

---

## [2026-03-05] — Physical timeline stitching across BLE reconnects & ESP32 resets

### Problem Being Solved

Before this change, multi-capture stitching relied on `dev_us` (ESP32 device clock).
This produced **wrong timestamps** in three common scenarios:

| Scenario | What happened | Impact |
|---|---|---|
| **ESP32 reset** between captures | `dev_us` restarts from 0 → timestamps overlap | Swings from capture 2 appear at t=0, mixed with capture 1 |
| **BLE reconnect gap** (5–30 s) | `dev_us` continues but gap is invisible | Swing intervals appear shorter than reality; fatigue timeline compressed |
| **uint32 wrap** after ~71 min | `DeviceClockExtender` handles within one file, but not across files | Timestamps jump backward at file boundary |

### Solution: Three-Layer Fix

#### Layer 1 — `TLV_HOST_MS` protocol extension (`SessionCaptureActivity.java`)

New TLV type `0x04` injected by Android Java into every frame **before** writing to
`frames.bin`. Value = `System.currentTimeMillis()` (wall-clock ms, uint64 LE).

```
Frame wire format (after this change):
  [14-byte header]
  [TLV 0x04: type(1) + len=8(2) + hostMs_u64_le(8)]  ← NEW: 11 bytes
  [TLV 0x01: EMG batch]
  [TLV 0x02: IMU batch]
  [TLV 0x03: vitals]
  [CRC-16/CCITT (2)]
```

`injectHostTimestamp(frame, hostMs)` in `SessionCaptureActivity.java`:
- Prepends the 11-byte TLV to the payload
- Updates `payload_len` in the 14-byte header
- Recomputes CRC-16/CCITT if the CRC flag (`flags & 0x01`) is set
- Returns original frame unchanged if frame is too short or malformed

**ESP32 firmware: NO CHANGE NEEDED.** The TLV is added by Android, not the device.

**Backward compatible:** Old decoders ignore unknown TLV types. Old `frames.bin` files
without `TLV_HOST_MS` fall back to `host_rx_ms = 0` → `ts_ms = dev_us / 1000`.

#### Layer 2 — Decoder writes `host_rx_ms` column (`btcentral_offline_decode.py`, `offline_runner.py`)

Both decoders pre-scan TLVs per frame to extract `frame_host_ms` before processing
data TLVs. The value is written as `host_rx_ms` in every row of `emg_log_*.csv`,
`imu_log_*.csv`, and `vitals_log.csv`.

#### Layer 3 — Stitcher uses `host_rx_ms` as `ts_ms` (`emg_util_nopandas.py`)

`load_decoded_emg_csv()` and `load_decoded_imu_csv()` now select `ts_ms` as:
```python
ts_ms = host_rx_ms  if host_rx_ms is non-zero and finite
ts_ms = dev_us/1000 otherwise  (legacy files)
```

`stitch_decoded_session_from_paths()` sorts all rows by `ts_ms` → physical timeline.

### New Function: `stitch_decoded_session_from_paths()` (`emg_util_nopandas.py`)

The previous `stitch_decoded_session(decoded_dir)` only worked for a **single**
`decoded/` directory (session-dir layout from `btcentral_offline_decode.py`).

For the **flat-folder layout** (`offline_runner.py` output), each `frames_*.bin` is
decoded into its own `decoded_frames_*/` directory. There was no function to stitch
across multiple directories.

**New function:**
```python
stitch_decoded_session_from_paths(
    emg_paths: List[Path],   # from any number of decoded_frames_*/ dirs
    imu_paths: List[Path],
    emg_fs_hz: float,
    ...
) -> Dict
```

Physical timeline construction inside this function:
1. Load all EMG rows from all paths → concatenate
2. Sort by `ts_ms` (= `host_rx_ms`) → gaps between captures preserved
3. Filter invalid rows
4. Rebuild uniform timestamp axis (for windowed feature computation)
5. Same for IMU rows
6. Run windowed features → swing detection → baseline → fatigue

`stitch_decoded_session(decoded_dir)` is now a thin wrapper that globs
`emg_log_*.csv` / `imu_log_*.csv` from a single dir and calls
`stitch_decoded_session_from_paths()`.

### New Function: `analyze_tennis_session()` (`offline_runner.py`)

Full end-to-end pipeline callable from Java:

```python
analyze_tennis_session(
    captures_dir,   # flat dir with all frames_*.bin
    session_id,     # YYYYMMDD_HHMMSS of first capture
    emg_fs_hz,      # e.g. 2000.0
    ...
) -> str  # JSON
```

Pipeline:
1. `process_tennis_session()` → decode all `frames_*.bin` → per-capture `decoded_frames_*/` dirs
2. Collect all `emg_log_*.csv` and `imu_log_*.csv` paths
3. `stitch_decoded_session_from_paths()` → sort by `ts_ms` → physical timeline
4. Windowed features + swing detection + baseline + fatigue

Returns JSON with `summary`, `swing_windows`, `swing_feats`, `baseline`,
`window_feats`, `session_id`, `n_captures`, `capture_ids`, `reconnection_times_s`.

**Java usage:**
```java
String json = mod.callAttr("analyze_tennis_session",
    capturesDir, sessionId, 2000.0).toString();
JSONObject res = new JSONObject(json);
int numSwings = res.getJSONObject("summary").getInt("num_swings");
```

### Stitching Flow (complete picture)

```
BLE notification arrives
  → SessionCaptureActivity.injectHostTimestamp(data, System.currentTimeMillis())
  → frames_20260225_140000.bin  host_rx_ms = 1740492000000 … 1740492025000

[5-second BLE reconnect gap — new frames_*.bin opened by triggerReconnect()]

  → frames_20260225_140005.bin  host_rx_ms = 1740492025000 … 1740492030000

[8-second BLE reconnect gap]

  → frames_20260225_140013.bin  host_rx_ms = 1740492030000 … 1740492045000

analyze_tennis_session()
  step 1: decode each frames_*.bin → decoded_frames_*/emg_log_*.csv (host_rx_ms column)
  step 2: stitch_decoded_session_from_paths([all emg_log_*.csv], [all imu_log_*.csv])
            sort by ts_ms (= host_rx_ms)
            → correct wall-clock timeline with visible gaps ✅
            → swing detection sees correct inter-swing intervals ✅
            → fatigue analysis uses correct session duration ✅
  step 3: windowed features + swing detection + baseline + fatigue → JSON
```

### Files Changed

| File | Change |
|---|---|
| `SessionCaptureActivity.java` | `TLV_HOST_MS = 0x04` constant; `injectHostTimestamp()` method; `onCharacteristicChanged()` injects TLV before `qLog` |
| `btcentral_offline_decode.py` | `TLV_HOST_MS = 0x04`; per-frame pre-scan; `host_rx_ms` column in all CSVs |
| `offline_runner.py` | Imports `TLV_HOST_MS`; same pre-scan in `_decode_bin_direct()`; new `analyze_tennis_session()` |
| `emg_util_nopandas.py` | `load_decoded_emg_csv/imu_csv()` use `host_rx_ms` as `ts_ms`; new `stitch_decoded_session_from_paths()`; `stitch_decoded_session()` refactored as thin wrapper |

---

## [2026-03-03] — IMU fixed-scale bug fix (`IMU_FIXED_MAX`)

### Fixed
- **`IMU_FIXED_MAX` wrong units** — was set to `600f` with comment "Gyro range: 0-600 dps",
  but the IMU plot shows **accelerometer magnitude in raw ADC counts**, not gyroscope dps.
  - ICM-20948 ±4g FSR: 8192 LSB/g → at rest `amag ≈ 8192`, swing peak `≈ 16000–32768`
  - `600` is far below the actual signal → curve was completely invisible in `IMU:Fix` mode
  - **Before:** `IMU_FIXED_MAX = 600f` (gyro dps — wrong units)
  - **After:** `IMU_FIXED_MAX = 40000f` (accel magnitude counts — correct, ~10% headroom above 4g)

### IMU:Fix mode behavior after fix
| Condition | `amag` value | % of plot height |
|---|---|---|
| At rest (1g) | ~8192 | ~20% |
| Moderate movement (2g) | ~16384 | ~41% |
| Hard swing (4g) | ~32768 | ~82% |
| Full-scale headroom | 40000 | 100% |

Fixed scale is useful for **comparing swing intensities across time** — consistent Y-axis
means a taller peak = harder swing, directly comparable between swings.

---

## [2026-03-03] — EMG plot auto-zoom + colored legend title

### Added
- **RAW auto-zoom** — RAW (cyan) is normalized each frame to fill the full 0–4095 plot height:
  - Computes peak deviation from ADC midpoint (2048) in the current window
  - Minimum range = 100 counts (prevents over-zoom on baseline noise)
  - `zoom_factor = 2048 / peak_deviation` — shown live in the title
  - At rest (±30 cts): zoom×20.5; Active (±300 cts): zoom×6.8; Strong (±800 cts): zoom×2.6

- **LPF dynamic scaling (Option C)** — LPF (magenta) is scaled each frame to match ENV's
  current window vertical range, so both envelope curves are directly comparable:
  - `lpf_zoom = env_range / lpf_range` — shown live in the title
  - LPF and ENV track each other visually; divergence reveals HW vs SW envelope differences

- **Colored legend title** — each signal label in the plot title uses the curve's color:
  - `RAW(zoom×6.8)` in **cyan** / `ENV(0-4095)` in **yellow** / `LPF(×4.2)` in **magenta**
  - Implemented via new `SimplePlotView.setTitleSegments(String[], int[])` API

### Fixed
- **LPF computation bug** — was computing `abs(raw)` where `raw` is always 0–4095 (unsigned),
  so `abs(raw) = raw` and the LPF converged to ~2048 (DC level) — a flat invisible line.
  - **Before:** `float x = Math.abs((float)raw);` → LPF ≈ 2048 (useless)
  - **After:** `float x = Math.abs((float)raw - 2048f);` → LPF = 0 at rest, ~100–500 active

### Changed
- **`SimplePlotView.java`:**
  - Added `setTitleSegments(String[] texts, int[] colors)` — draws multi-color title
  - `setTitle()` clears segments (backward compatible)
  - `onDraw()` measures each segment's text width and draws sequentially with per-segment color

- **`SessionCaptureActivity.java`:**
  - `decodeEmgBatch()` — fixed LPF formula; stores raw 0–2047 (no `×2` hack)
  - `renderPlots()` — added RAW normalization block + LPF Option C scaling block
  - `renderPlots()` — replaced `emgPlot.setTitle()` with `emgPlot.setTitleSegments()`

### Signal summary (all 3 curves now clearly visible)
| Color | Signal | Scale | What you see |
|---|---|---|---|
| Cyan | RAW | Normalized to fill 0–4095 | Oscillations always fill plot; zoom factor in title |
| Yellow | ENV | Actual 0–4095 (unchanged) | Absolute muscle intensity — rises with contraction |
| Magenta | LPF | Scaled to match ENV range | Smooth envelope tracking ENV — compare shapes |

---

## [2026-03-03] — Auto-reconnect to ESP32 in `SessionCaptureActivity`

### Added
- **Auto-reconnect** — `SessionCaptureActivity` now automatically reconnects to the ESP32 on
  disconnect or data stall, mirroring the behavior of `btCentral.py` on the PC side.

#### Mechanism (mirrors `btCentral.py` exactly):

| `btCentral.py` | `SessionCaptureActivity.java` |
|---|---|
| `handle_disconnect` callback | Watchdog detects `isConnected() == false` |
| `watchdog_task` (polls every 0.5s, stall=5s) | `startWatchdog()` — Handler fires every 500ms, stall threshold 5000ms |
| `ping_task` (writes `b"PING"` every 5s) | `pingTimer` already existed (1s interval) |
| Exponential backoff 0.5s→1s→…→20s | `reconnectBackoffMs` doubles each attempt, cap 20s |
| `st.parser = FrameParser()` | `qLog.clear()`, `qPlot.clear()` |
| `st.clock = DeviceClockExtender()` | `wrapCount=0`, `lastTFrameU32=-1` |
| `st.last_frame_seq = None` | `t0SessionUs=-1` |
| New CSV segment (same session) | New `frames_*.bin` in same `sessionDir` |

#### New fields in `SessionCaptureActivity`:
```java
private volatile long lastNotifyMs = 0;       // watchdog heartbeat
private volatile int reconnectCount = 0;       // total reconnect attempts (for UI)
private volatile int reconnectBackoffMs = 500; // current backoff delay
private static final int MAX_RECONNECT_BACKOFF_MS = 20_000;
private static final int STALL_RECONNECT_MS   = 5_000;
private static final int WATCHDOG_INTERVAL_MS = 500;
private Runnable watchdogRunnable;
private Runnable reconnectRunnable;
```

#### New methods in `SessionCaptureActivity`:
- `startWatchdog()` — starts stall-detection loop on main Handler
- `stopWatchdog()` — cancels watchdog
- `cancelReconnect()` — cancels pending reconnect runnable
- `triggerReconnect(reason)` — stops ping+watchdog, clears queues, resets clock state,
  opens new `frames_*.bin` segment, starts backoff
- `scheduleReconnect()` — posts `BleManager.connect()` after backoff delay; on success
  resets backoff and calls `subscribeNotifyAndStartPing()`; on fail doubles backoff and retries

#### Lifecycle integration:
- Watchdog starts in `onNotifySuccess()` (after notify subscription confirmed)
- Watchdog + reconnect cancelled in `stopSession()` and `onDestroy()`
- `onCharacteristicChanged()` updates `lastNotifyMs` on every received packet

### Changed
- **`SessionCaptureActivity.java`:**
  - Added `android.bluetooth.BluetoothGatt` and `BleGattCallback` imports
  - `onNotifySuccess()` — calls `startWatchdog()` after `startPingTimer()`
  - `onCharacteristicChanged()` — adds `lastNotifyMs = System.currentTimeMillis()`
  - `stopSession()` — calls `stopWatchdog()`, `cancelReconnect()`, resets `reconnectBackoffMs`
  - `onDestroy()` — calls `stopWatchdog()`, `cancelReconnect()` before `stopSession()`

- **`MainActivity.java`:**
  - `setReConnectCount(1, 5000)` → `setReConnectCount(0, 5000)` — disables FastBLE's
    built-in single reconnect attempt to avoid conflicting with the new custom loop

### File organization on reconnect
Each reconnect opens a **new `frames_*.bin` segment in the same `sessionDir`** (same tennis
session). `writeMetaJson()` already lists all `frames_*.bin` files, so no extra work needed:

```
session_20260302_143000/
  frames_20260302_143000.bin   ← initial recording
  frames_20260302_143512.bin   ← after reconnect #1
  frames_20260302_144023.bin   ← after reconnect #2
  meta.json                    ← lists all 3 files automatically
```

### Processing compatibility
**No changes needed to the Python processing pipeline.** `analysis_bridge.py` already:
- `process_session_compat()` — globs all `frames_*.bin` and passes session dir to decoder
- `analyze_session()` — calls `stitch_decoded_session()` which stitches all decoded CSVs

Reconnect segments are decoded and analyzed as one continuous session automatically.

### UI feedback during reconnect
- `txtStatus` shows: `"Reconnecting... (attempt N, wait X.Xs)"`
- `txtStatus` shows: `"Reconnecting... (attempt N)"` while connecting
- `txtStatus` shows: `"Reconnected! MTU=247 (recon#N)"` on success

### Scope
- Reconnect is **activity-scoped**: only active while `SessionCaptureActivity` is alive
  and `recording == true`
- Pressing Stop cancels all reconnect activity and resets backoff
- Navigating to `ProcessingActivity` requires stopping first (by design — `btnProcessSession`
  is only enabled after `stopSession()`)

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
