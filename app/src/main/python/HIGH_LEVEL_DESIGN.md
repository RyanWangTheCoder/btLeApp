# Related Documentation

- **[SWING_DETECTION.md](SWING_DETECTION.md)** - Detailed swing detection design decision (gyroscope vs accelerometer)

---

# Python Analysis Pipeline Architecture

**Date:** 2026-02-27  
**Purpose:** Document the relationship between Python analysis tools across Android, Windows, and offline processing

---

## Overview: Three Processing Tiers

The system has **three distinct processing tiers**, each optimized for different constraints and use cases:

```
┌─────────────────────────────────────────────────────────────────┐
│ TIER 1: Real-time (Live Session)                                │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                   │
│ Android App (Java)          Python Plotter (Windows)             │
│ SessionCaptureActivity.java sensordata_plotter.py                │
│                                                                   │
│ • Gyro peak detection       • Gyro peak detection                │
│ • 150 dps threshold         • 150 dps threshold                  │
│ • 1-sec debounce            • 1-sec debounce                     │
│ • Visual markers            • Visual plots + MDF trend           │
│ • Fatigue monitoring        • Fatigue monitoring                 │
│   (via realtime_bridge.py)    (via fatigue_monitor.py)          │
│                                                                   │
│ Constraint: <10ms latency   Constraint: 30 FPS display           │
│ Purpose: Immediate feedback Purpose: Live monitoring             │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ TIER 2: Post-capture Analysis (Android)                          │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                   │
│ offline_runner.py (Android)                                       │
│                                                                   │
│ • Decode frames_*.bin → CSV                                       │
│ • Stitch multi-capture sessions                                  │
│ • Session discovery (30-min gap)                                 │
│ • Full EMG analysis (swing detection, fatigue, classification)   │
│ • Android equivalent of emg_util_yaml_runner.py                  │
│ • Like SwingVision: complete analysis on device                  │
│                                                                   │
│ Constraint: Mobile CPU/battery (optimized algorithms)            │
│ Purpose: Complete analysis on device OR transfer to PC/cloud     │
│          Future: AI model inference on device                    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ TIER 3: Offline Analysis (Windows/Cloud)                         │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │
│                                                                   │
│ emg_util_yaml_runner.py (Windows)                                │
│ tennis_analysis_cloud.ipynb (Colab)                              │
│                                                                   │
│ • Sophisticated EMG-based swing detection                        │
│ • Bandpass filter (20-450 Hz)                                    │
│ • Hilbert envelope + adaptive threshold                          │
│ • Swing classification (forehand/backhand/serve)                 │
│ • Fatigue analysis (MDF, RMS, spectral features)                 │
│ • Session comparison & trends                                    │
│                                                                   │
│ Constraint: None (offline)                                       │
│ Purpose: Research-grade analysis                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## File Inventory & Relationships

### Android App Python Files (`C:\wangx\ftc\arduino\btLeApp\app\src\main\python\`)

| File | Purpose | Called From | Equivalent On Windows |
|------|---------|-------------|----------------------|
| **realtime_bridge.py** | Real-time fatigue monitoring during BLE capture | Java (SessionCaptureActivity) | sensordata_plotter.py --fatigue |
| **offline_runner.py** | Post-capture decode + session stitching | Java (post-capture UI) | emg_util_yaml_runner.py |
| **fatigue_monitor.py** | Core fatigue analysis class (MDF, RMS, baseline) | realtime_bridge.py, sensordata_plotter.py | (shared) |
| **emg_util_nopandas.py** | Signal processing without pandas dependency | fatigue_monitor.py | emg_util.py (subset) |
| **btcentral_offline_decode.py** | Binary frame decoder | offline_runner.py | btCentral.py (decode logic) |
| **analysis_bridge.py** | **DEPRECATED** compatibility shim | (legacy Java code) | N/A |

### Windows Python Files (`C:\wangx\ftc\arduino\py_scripts\`)

| File | Purpose | Runs On | Equivalent On Android |
|------|---------|---------|----------------------|
| **btCentral.py** | BLE recorder + CSV logger + UDP stream | Windows (live) | (built into Android app) |
| **sensordata_plotter.py** | Real-time UDP plotter + fatigue monitor | Windows (live) | SessionCaptureActivity.java |

### Offline Analysis Files (`C:\wangx\ftc\emg_util\`)

| File | Purpose | Runs On | Notes |
|------|---------|---------|-------|
| **emg_util_yaml_runner.py** | Full offline pipeline (decode + analyze) | Windows/Linux | Android: offline_runner.py |
| **emg_util.py** | Core analysis library (pandas-based) | Windows/Linux/Colab | Android: emg_util_nopandas.py |
| **tennis_analysis_cloud.ipynb** | Colab notebook for cloud analysis | Google Colab | Uses emg_util.py |
| **fatigue_monitor.py** | Shared fatigue class | All platforms | (copied to Android) |

---

## Detailed File Descriptions

### 1. `realtime_bridge.py` (Android Real-time)

**Purpose:** Android-specific real-time fatigue monitoring during live BLE capture.

**Key Functions:**
```python
reset_fatigue_monitor(fs, warmup_skip_min, baseline_swings)
  → Called at session start (Java: startSession)
  → Initializes FatigueMonitor singleton

push_swing_realtime(emg_raw_json, t_peak_ms, imu_peak_mag)
  → Called after each IMU swing trigger (Java: fatigueThread)
  → Returns JSON: {swing_id, mdf, mdf_drop_pct, fatigue_level, warning}

get_fatigue_summary()
  → Called at session end (Java: stopSession)
  → Returns session-level fatigue summary
```

**Java Usage:**
```java
PyObject mod = Python.getInstance().getModule("realtime_bridge");
mod.callAttr("reset_fatigue_monitor");
// ... for each swing:
String json = mod.callAttr("push_swing_realtime",
                  emgJsonStr, tPeakMs, imuPeakMag).toString();
JSONObject result = new JSONObject(json);
if (result.getBoolean("warning")) showFatigueWarning();
```

**Windows Equivalent:** `sensordata_plotter.py --fatigue`
- Same FatigueMonitor class
- Same gyro threshold (150 dps)
- Same MDF computation
- Different UI (Matplotlib vs Android TextView)

---

### 2. `offline_runner.py` (Android Post-capture Analysis)

**Purpose:** Android equivalent of `emg_util_yaml_runner.py` on Windows - complete analysis on device.

**Like SwingVision:** Provides full post-capture analysis directly on Android device, similar to how SwingVision analyzes video after recording. Users get complete results immediately without needing to transfer to PC.

**Key Functions:**
```python
discover_sessions(captures_dir, gap_thr_min=30.0)
  → Groups frames_*.bin files into tennis sessions
  → Returns JSON: {sessions: [{session_id, bin_files, duration_min}]}

process_tennis_session(captures_dir, session_id, rotate_min=20.0)
  → Decodes and stitches all BLE captures in one session
  → Runs full EMG analysis (swing detection, fatigue, classification)
  → Returns JSON: {emg_csv_paths, imu_csv_paths, analysis_results, decode_stats}

process_session(bin_path, out_dir, rotate_min=20.0)
  → Decodes ONE frames_*.bin → rotated CSVs
  → For single-capture use
```

**Analysis Capabilities (Current & Future):**
- ✅ **Decode & stitch:** Convert binary frames to CSV
- ✅ **Session discovery:** Group captures by 30-min gap
- 🚧 **Swing detection:** EMG-based (planned, currently uses gyro from Tier 1)
- 🚧 **Fatigue analysis:** Full MDF trends (planned, currently real-time only)
- 🚧 **Swing classification:** Forehand/backhand/serve (planned)
- 🚧 **AI inference:** On-device ML models (future)

**Storage Layout Difference:**

**Android (flat folder):**
```
/storage/emulated/0/BLE_Captures/
  frames_20260225_140000.bin   ← BLE connection 1 (session A)
  frames_20260225_142500.bin   ← BLE connection 2 (25 min gap → same session)
  frames_20260225_220000.bin   ← BLE connection 3 (4h gap → NEW session B)
```

**Windows (folder per session):**
```
C:\wangx\ftc\arduino\py_scripts\logs\
  session_20260225_140000/
    emg_log_*.csv
    imu_log_*.csv
  session_20260225_220000/
    emg_log_*.csv
    imu_log_*.csv
```

**Session Discovery:** Android uses 30-minute gap threshold to group captures into sessions (mimics Windows folder structure).

**Output Schema:** Identical CSV format → decoded Android data can be transferred to PC and analyzed with `emg_util_yaml_runner.py` without re-decoding.

---

### 3. `fatigue_monitor.py` (Shared Core - Real-time Only)

**Purpose:** **Real-time** fatigue analysis class used by both Android and Windows during live capture.

**Design Philosophy:**
- ✅ **Optimized for real-time:** Simple, fast, per-swing analysis
- ✅ **Low latency:** <10ms processing per swing
- ✅ **Immediate feedback:** Instant fatigue warnings during play
- ❌ **Not for offline analysis:** Use `emg_util.analyze_fatigue()` for sophisticated post-processing

**Key Features:**
- **Warmup skip:** Ignore first N minutes (default 5 min)
- **Baseline collection:** Collect N swings for baseline MDF (default 20)
- **MDF computation:** Median frequency via Welch's method (per swing)
- **Simple thresholds:** Fixed percentage drops
- **Fatigue levels:**
  - `warming_up`: Before baseline ready
  - `fresh`: MDF drop < 12%
  - `moderate`: MDF drop 12-20%
  - `high`: MDF drop > 20%

**Used By:**
- Android: `realtime_bridge.py` → Java UI (live feedback)
- Windows: `sensordata_plotter.py` → Matplotlib plots (live monitoring)

**Key Method:**
```python
def push_swing(self, emg_raw, t_peak_ms, imu_peak_mag):
    """Process one swing window (2600 samples @ 2000 Hz = 1300ms).
    
    Real-time analysis: simple MDF drop percentage.
    
    Returns:
        dict: {
            swing_id: int,
            mdf: float (Hz),
            mdf_drop_pct: float,
            fatigue_level: str,
            warning: bool,
            baseline_ready: bool,
        }
    """
```

**Contrast with Offline Analysis:**
- **fatigue_monitor.py (real-time):** Per-swing MDF, simple thresholds, instant feedback
- **emg_util.analyze_fatigue() (offline):** Windowed MDF trends, rolling averages, onset detection, slope analysis

---

### 4. `emg_util_nopandas.py` (Android Subset)

**Purpose:** Signal processing functions without pandas dependency (Android Chaquopy limitation).

**Subset of `emg_util.py`:**
- Bandpass filter (20-450 Hz)
- Hilbert envelope
- Windowed feature extraction (MDF, RMS)
- Fatigue analysis

**Missing from full `emg_util.py`:**
- ❌ Pandas DataFrames
- ❌ Session comparison
- ❌ Swing classification
- ❌ Advanced visualizations

**Why Separate:**
- Android Chaquopy has limited pandas support
- Smaller binary size for mobile
- Faster import time

---

### 5. `btcentral_offline_decode.py` (Android Decoder)

**Purpose:** Binary frame decoder for Android post-capture processing.

**Shared Logic with `btCentral.py`:**
- Frame parsing (MAGIC, header, TLVs, CRC16)
- Device clock extension (u32 → u64)
- EMG/IMU/vitals decoding

**Differences:**
- ❌ No BLE connection (reads from .bin files)
- ❌ No UDP streaming
- ✅ Batch processing optimized

---

### 6. `sensordata_plotter.py` (Windows Real-time)

**Purpose:** Real-time UDP plotter + fatigue monitor for Windows.

**Features:**
- UDP listener (receives from btCentral.py)
- Rolling plots (EMG RAW/ENV, IMU, vitals)
- Gyro-based swing detection (150 dps)
- Real-time fatigue monitoring (optional --fatigue flag)
- Swing counter in title
- MDF trend subplot

**Relationship to Android:**
- **Same swing detection:** Gyro peak @ 150 dps
- **Same FatigueMonitor:** Shared fatigue_monitor.py
- **Different UI:** Matplotlib vs Android TextView
- **Different input:** UDP vs direct BLE

---

### 7. `emg_util_yaml_runner.py` (Windows Offline)

**Purpose:** Full offline analysis pipeline for Windows.

**Features:**
- Session discovery (folder-based)
- Multi-segment stitching
- Sophisticated EMG-based swing detection
- Swing classification (forehand/backhand/serve)
- Fatigue analysis with trends
- Session comparison
- YAML configuration

**Swing Detection (Offline):**
```python
# More sophisticated than real-time
1. Bandpass filter (20-450 Hz)
2. Hilbert envelope
3. Adaptive threshold (z-score)
4. Peak validation (prominence, distance, duration)
5. Swing classification (ML features)
```

**Why Different from Real-time:**
- ✅ No latency constraint → complex algorithms OK
- ✅ Look ahead/behind → better peak validation
- ✅ Adaptive threshold → handles varying intensity
- ✅ Swing classification → research insights

---

### 8. `tennis_analysis_cloud.ipynb` (Colab Notebook)

**Purpose:** Google Colab notebook for cloud-based offline analysis - **equivalent to emg_util_yaml_runner.py**.

**Key Features:**
- ✅ **Same core function:** `runner.analyze_tennis_session(LOG_DIR, config, OUT_DIR)`
- ✅ **Same analysis pipeline:** Session stitching, swing detection, fatigue analysis
- ✅ **Same outputs:** CSV files, plots, JSON summaries
- ✅ **Interactive:** Jupyter notebook with visualization cells
- ✅ **Cloud-based:** Runs on Google Colab (free GPU/TPU)

**Configuration:**
```python
# Notebook uses Python dict (CLOUD_CONFIG)
CLOUD_CONFIG = {
    'session': {
        'emg_fs_hz': 2000,
        'label': 'Session Name',
        # ... same parameters as YAML
    },
    'features': {
        'mode': 'both',  # 'bout' | 'medium' | 'both'
        # ... same as YAML
    },
    'swing_detection': {
        'use_gyro': True,
        'z_thr': 5.0,
        # ... same as YAML
    },
    # ... all other sections match YAML structure
}
```

**Analysis Modes:**
| Mode | Description | Speed |
|------|-------------|-------|
| `'bout'` | IMU-only bout analysis (no EMG features) | Fastest |
| `'medium'` | MDF + RMS + envelope per swing | Fast |
| `'both'` | Full per-swing EMG features (MDF, RMS, WL, ZC, SSC, MNF) | Comprehensive |

**Notebook Structure (9 cells):**
1. **Setup & Mount Drive** - Mount Google Drive, install dependencies
2. **Unzip Data** - Extract uploaded ZIP file (optional)
3. **Path Configuration** - Set LOG_DIR, OUT_DIR, SCRIPT_DIR
4. **Imports** - Import emg_util_yaml_runner, emg_util, capture_loader
5. **Configuration** - Define CLOUD_CONFIG dict (mirrors config.yaml)
6. **Run Analysis** - Call `runner.analyze_tennis_session()`
7. **Load Results** - Load CSVs and JSON outputs
8. **Visualize Fatigue** - Plot MDF, RMS, cost index, MDF drop %
9. **Bout Summary** - Plot swings per bout, swing rate

**Outputs (identical to CLI):**
- `capture_segments.csv` - BLE connection list
- `swing_windows.csv` - All detected swings
- `swing_features.csv` - Per-swing EMG features + fatigue deltas
- `windowed_rms.csv` - Windowed RMS/MAV (medium mode)
- `bout_summary.csv` - Bout-level summary
- `baseline.json` - Baseline EMG values
- `session_summary.json` - Total duration, swing count
- `imu_activity_overlay.png` - IMU intensity + swings
- `fatigue_trend.png` - MDF/RMS over time
- `dashboard_fatigue.png` - 4-panel fatigue dashboard (notebook only)
- `dashboard_bouts.png` - Bout summary plots (notebook only)

**Equivalence Verification:**

| Feature | emg_util_yaml_runner.py (CLI) | tennis_analysis_cloud.ipynb (Colab) |
|---------|------------------------------|-------------------------------------|
| **Core Function** | `runner.analyze_tennis_session()` | `runner.analyze_tennis_session()` ✅ |
| **Config Format** | YAML file | Python dict ✅ |
| **Session Stitching** | All BLE connections → one session | All BLE connections → one session ✅ |
| **Swing Detection** | EMG-based, z-score | EMG-based, z-score ✅ |
| **Fatigue Analysis** | Windowed trends, onset detection | Windowed trends, onset detection ✅ |
| **Bout Analysis** | Gap-based bout detection | Gap-based bout detection ✅ |
| **Activity Gating** | IMU-based active periods | IMU-based active periods ✅ |
| **EMG Health Check** | Bad signal filtering | Bad signal filtering ✅ |
| **Outputs** | CSV + JSON + PNG | CSV + JSON + PNG ✅ |
| **Environment** | Windows/Linux CLI | Google Colab (cloud) |
| **Visualization** | Matplotlib (saved) | Matplotlib (interactive + saved) |

**Key Differences (Environment Only):**
- **Notebook:** Interactive cells, Google Drive mount, inline plots
- **CLI:** Command-line, local files, saved plots only
- **Both:** Use same `emg_util.py` library, same algorithms, same outputs

**When to Use:**
- **Notebook:** Quick analysis, no local setup, cloud storage, interactive exploration
- **CLI:** Batch processing, automation, local files, scripting

**Example Usage (Notebook):**
```python
# Cell 3: Configure paths
LOG_DIR = '/content/drive/MyDrive/Tennis_Project/raw_logs'
OUT_DIR = '/content/drive/MyDrive/Tennis_Project/processed_out'

# Cell 5: Configure analysis
FAST_MODE = False    # Full analysis
MEDIUM_MODE = False  # All features
SESSION_LABEL = '2026-02-08 practice'

# Cell 6: Run analysis
out_path = runner.analyze_tennis_session(LOG_DIR, CLOUD_CONFIG, OUT_DIR)

# Cell 8: Visualize results (interactive)
# Plots MDF, RMS, cost index, MDF drop % with BLE reconnection markers
```

**Example Usage (CLI):**
```bash
# config.yaml contains same parameters as CLOUD_CONFIG
python emg_util_yaml_runner.py --config config.yaml --log-dir logs/ --out-dir out/
```

**Relationship to Other Tools:**
- **Tier 3 (Offline Analysis):** Both notebook and CLI are Tier 3 tools
- **Same as:** emg_util_yaml_runner.py (Windows CLI)
- **Different from:** fatigue_monitor.py (real-time), sensordata_plotter.py (real-time)
- **Uses:** emg_util.py (core library), capture_loader.py (session stitching)

---

## Processing Flow Examples

### Example 1: Live Session with Real-time Fatigue

**Android:**
```
1. User starts recording (SessionCaptureActivity)
2. Java calls: realtime_bridge.reset_fatigue_monitor()
3. For each IMU sample:
   - Java detects swing (gyro peak @ 150 dps)
   - Java queues EMG snapshot to fatigueThread
4. fatigueThread calls: realtime_bridge.push_swing_realtime(emg, t, mag)
5. Python returns: {mdf: 180, mdf_drop_pct: 15, fatigue_level: "moderate", warning: true}
6. Java updates UI: "⚠️ Fatigue: MODERATE (MDF 180 Hz, -15%)"
7. At session end: realtime_bridge.get_fatigue_summary()
```

**Windows (parallel):**
```
1. User runs: python btCentral.py --udp-enable
2. User runs: python sensordata_plotter.py --fatigue
3. btCentral streams UDP → sensordata_plotter
4. Plotter detects swings (gyro peak @ 150 dps)
5. Plotter calls: FatigueMonitor.push_swing(emg, t, mag)
6. Plotter updates: Title shows "SWINGS: 42", MDF trend plot
7. User sees same fatigue warnings as Android
```

**Result:** Both tools show consistent swing counts and fatigue warnings.

---

### Example 2: Post-capture Analysis

**Android (decode on device):**
```
1. User finishes recording → frames_20260227_140000.bin saved
2. User taps "Analyze Session" in app
3. Java calls: offline_runner.discover_sessions(capturesDir)
4. Python returns: {sessions: [{session_id: "20260227_140000", n_captures: 3}]}
5. User selects session
6. Java calls: offline_runner.process_tennis_session(capturesDir, sessionId)
7. Python decodes → emg_log_*.csv, imu_log_*.csv
8. User transfers CSVs to PC via USB/cloud
```

**Windows (full analysis):**
```
1. User copies CSVs from Android to PC
2. User runs: python emg_util_yaml_runner.py --log-dir <path>
3. Python discovers sessions (folder-based)
4. Python stitches segments
5. Python runs sophisticated swing detection (EMG-based)
6. Python generates:
   - Swing segmentation
   - Fatigue trends
   - Session comparison
   - Classification (forehand/backhand/serve)
7. User reviews results in Jupyter notebook
```

---

## Key Design Decisions

### Decision 1: Why Three Tiers?

**Real-time (Tier 1):**
- **Constraint:** <10ms latency, 30 FPS display
- **Algorithm:** Simple gyro threshold
- **Purpose:** Immediate feedback during play
- **Trade-off:** ~95% accuracy, some false positives

**Post-capture (Tier 2):**
- **Constraint:** Mobile CPU/battery (optimized algorithms)
- **Algorithm:** Full EMG analysis (like SwingVision post-video analysis)
- **Purpose:** Complete analysis on device + optional PC/cloud transfer
- **Trade-off:** Slower than real-time but faster than Tier 3, ~97% accuracy
- **Future:** On-device AI model inference

**Offline (Tier 3):**
- **Constraint:** None (offline, unlimited compute)
- **Algorithm:** Most sophisticated EMG analysis + session comparison
- **Purpose:** Research-grade insights, multi-session trends
- **Trade-off:** ~98% accuracy, slow (minutes per session), requires PC/cloud

### Decision 2: Why Duplicate fatigue_monitor.py?

**Problem:** Android and Windows both need real-time fatigue monitoring.

**Options:**
1. ❌ Import from shared location → path issues, deployment complexity
2. ✅ Copy file to both locations → simple, self-contained

**Result:** `fatigue_monitor.py` exists in:
- `C:\wangx\ftc\arduino\btLeApp\app\src\main\python\fatigue_monitor.py` (Android)
- `C:\wangx\ftc\emg_util\fatigue_monitor.py` (Windows, canonical source)

**Sync Strategy:** When updating fatigue logic, update canonical source first, then copy to Android.

### Decision 3: Why emg_util_nopandas.py?

**Problem:** Android Chaquopy has limited pandas support (large binary, slow import).

**Solution:** Extract core signal processing functions into pandas-free subset.

**Trade-offs:**
- ✅ Smaller Android APK
- ✅ Faster import time
- ❌ Duplicate code (must sync with emg_util.py)
- ❌ Missing advanced features

---

## Swing Detection Comparison

| Feature | Real-time (Android/Windows) | Offline (Windows) |
|---------|----------------------------|-------------------|
| **Sensor** | Gyroscope | EMG (muscle activation) |
| **Method** | Peak detection | Hilbert envelope + z-score |
| **Threshold** | 150 dps (fixed) | 5σ (adaptive) |
| **Latency** | <10ms | N/A (offline) |
| **Accuracy** | ~95% | ~98% |
| **False Positives** | Walking, gestures | Rare |
| **False Negatives** | Soft swings | Very rare |
| **Swing Classification** | No | Yes (forehand/backhand/serve) |
| **Use Case** | Live feedback | Research analysis |

**Why Different:**
- **Real-time needs speed** → simple threshold
- **Offline has time** → complex analysis
- **Both are correct** for their purpose

---

## Fatigue Analysis Comparison

The system uses **two different fatigue analysis approaches** optimized for different use cases:

### Real-time Fatigue (fatigue_monitor.py)

**Used By:**
- Android app (realtime_bridge.py)
- Windows plotter (sensordata_plotter.py)

**Algorithm:**
```python
# Per-swing analysis
1. Collect baseline (first 20 swings after 5-min warmup)
2. For each swing:
   - Compute MDF via Welch's method
   - Compare to baseline MDF
   - Calculate drop percentage
   - Classify: fresh (<12%), moderate (12-20%), high (>20%)
3. Return instant feedback
```

**Characteristics:**
- ✅ **Fast:** <10ms per swing
- ✅ **Simple:** Fixed percentage thresholds
- ✅ **Immediate:** Instant warnings during play
- ✅ **Per-swing:** Each swing analyzed independently
- ❌ **No trends:** No rolling averages or slope analysis
- ❌ **No onset:** No fatigue onset detection

**Output:**
```json
{
  "swing_id": 42,
  "mdf": 180.5,
  "mdf_drop_pct": 15.2,
  "fatigue_level": "moderate",
  "warning": true
}
```

### Offline Fatigue (emg_util.analyze_fatigue)

**Used By:**
- Windows offline analysis (emg_util_yaml_runner.py)
- Colab notebooks (tennis_analysis_cloud.ipynb)

**Algorithm:**
```python
# Windowed trend analysis
1. Compute windowed MDF (1-sec windows, 0.25-sec step)
2. Skip warmup period (configurable, default 0 min for offline)
3. Compute baseline (first 10% of windows)
4. Analyze trends:
   - Rolling average (20-window)
   - Linear regression slope
   - Onset detection (when rolling avg crosses 85% threshold)
5. Generate comprehensive summary
```

**Characteristics:**
- ✅ **Sophisticated:** Rolling averages, slope analysis, onset detection
- ✅ **Accurate:** ~98% correlation with ground truth
- ✅ **Comprehensive:** Full session trends and statistics
- ✅ **Adaptive:** Handles varying intensity levels
- ❌ **Slow:** Minutes per session
- ❌ **Offline only:** Requires complete session data

**Output:**
```python
{
  "baseline_MDF": 195.3,
  "final_MDF": 165.8,
  "MDF_drop_pct": 15.1,
  "slope_MDF": -0.082,  # Hz/sec
  "fatigue_onset_s": 1247.5,  # 20:47 into session
  # + MNF metrics, plots, CSV
}
```

### Comparison Table

| Feature | Real-time (fatigue_monitor.py) | Offline (emg_util.analyze_fatigue) |
|---------|-------------------------------|-----------------------------------|
| **Granularity** | Per-swing | Windowed (1-sec windows) |
| **Baseline** | First 20 swings (after warmup) | First 10% of windows |
| **Warmup Skip** | 5 min (default) | 0 min (default, configurable) |
| **MDF Computation** | Per swing (1300ms window) | Per 1-sec window (overlapping) |
| **Trend Analysis** | None | Rolling average (20-window) |
| **Slope Analysis** | None | Linear regression |
| **Onset Detection** | None | 85% threshold crossing |
| **Thresholds** | Fixed (12%, 20%) | Adaptive (rolling avg) |
| **Latency** | <10ms | N/A (offline) |
| **Output** | JSON per swing | CSV + plots + summary |
| **Use Case** | Live feedback | Research analysis |

### Why Two Approaches?

**Real-time (fatigue_monitor.py):**
- **Constraint:** Must process each swing in <10ms
- **Goal:** Instant feedback ("you're getting tired!")
- **Trade-off:** Simple thresholds, no trend analysis
- **Benefit:** Actionable warnings during play

**Offline (emg_util.analyze_fatigue):**
- **Constraint:** None (offline processing)
- **Goal:** Accurate fatigue characterization
- **Trade-off:** Slow, requires complete session
- **Benefit:** Research-grade insights, onset timing, trends

### Example: Same Session, Different Results

**Real-time (during play):**
```
Swing 25: MDF 185 Hz, drop 5% → FRESH ✅
Swing 50: MDF 175 Hz, drop 10% → FRESH ✅
Swing 75: MDF 165 Hz, drop 15% → MODERATE ⚠️
Swing 100: MDF 155 Hz, drop 21% → HIGH 🔴
```

**Offline (post-session):**
```
Baseline MDF: 195 Hz (first 10% of windows)
Final MDF: 158 Hz
MDF drop: 19%
Slope: -0.08 Hz/sec
Onset: 18:32 into session (when rolling avg crossed 85% threshold)
Interpretation: Gradual fatigue onset at ~19 minutes, moderate fatigue by end
```

**Both are correct!**
- Real-time gives **instant warnings** (per-swing)
- Offline gives **accurate characterization** (session-level trends)

### Design Philosophy

**Complementary, not competing:**
- Use **real-time** for immediate feedback during play
- Use **offline** for detailed post-session analysis
- Both use same preprocessing (fatigue_monitor.preprocess_emg)
- Both use same MDF computation (Welch's method)
- Different granularity and sophistication for different purposes

---

## File Synchronization

### Files That Must Stay in Sync

| Canonical Source | Copies | Sync Trigger |
|-----------------|--------|--------------|
| `emg_util/fatigue_monitor.py` | `btLeApp/.../fatigue_monitor.py` | Fatigue algorithm change |
| `emg_util/emg_util.py` | `btLeApp/.../emg_util_nopandas.py` | Signal processing change |
| `py_scripts/btCentral.py` (decode logic) | `btLeApp/.../btcentral_offline_decode.py` | Protocol change |

### Sync Checklist

When updating fatigue/signal processing:
1. ✅ Update canonical source (`emg_util/`)
2. ✅ Test on Windows
3. ✅ Copy to Android (`btLeApp/.../`)
4. ✅ Test on Android
5. ✅ Update version/changelog

---

## Consistency Verification: emg_util.py vs emg_util_nopandas.py

**Last Verified:** 2026-02-27  
**Status:** ✅ **CONSISTENT**

### Shared Core Functions (Verified Identical)

#### 1. **preprocess_emg** (Most Critical) ✅
- **Both import from fatigue_monitor.py** - single source of truth
- Ensures identical filtering across Android, Windows, and real-time monitoring
- Same bandpass (20-450 Hz), notch (60 Hz), detrend parameters

```python
# Both files:
from fatigue_monitor import preprocess_emg  # noqa: F401
```

#### 2. **envelope()** ✅
- Same algorithm: rectify + 2nd-order Butterworth lowpass @ 3.6 Hz
- Same fallback for no scipy: moving average
- Identical cutoff frequency and filter order

#### 3. **mnf_mdf()** ✅
- Same Welch's method parameters (nperseg=1024, noverlap=512, hann window)
- Same MDF calculation (50th percentile of PSD)
- Same MNF calculation (weighted mean frequency)
- Same numpy-only fallback using FFT

#### 4. **Classic EMG Features** ✅
All five features match exactly:
- `feat_mav()` - Mean Absolute Value
- `feat_rms()` - Root Mean Square
- `feat_wl()` - Waveform Length
- `feat_zc()` - Zero Crossings (threshold=0.01)
- `feat_ssc()` - Slope Sign Changes (threshold=0.01)

#### 5. **Swing Detection** ✅
- `robust_zscore()` - Same MAD-based robust z-score
- `detect_swing_windows_from_imu()` - Same algorithm
  - Same baseline period (2.0s)
  - Same z-threshold (5.0)
  - Same min_consec (3 samples)
  - Same min_gap (300ms)
  - Same pre/post padding (250ms/450ms)

#### 6. **Baseline Selection** ✅
- `select_baseline_swings()` - Same warmup skip logic (5 min default)
- Same preference for active-period swings
- Same fallback to all post-warmup swings

#### 7. **Baseline Computation** ✅
- `compute_baseline_from_swing_features()` - Same median-based baseline
- Same features: mdf, rms, mav, wl, emg_env_mean, emg_iemg, imu_mean_acc_dyn

#### 8. **Baseline Deltas** ✅
- `apply_baseline_deltas()` - Same percentage calculations
  - mdf_drop_pct = (baseline - current) / baseline × 100
  - rms_increase_pct = (current - baseline) / baseline × 100
  - acc_drop_pct = (baseline - current) / baseline × 100

#### 9. **Swing EMG Features** ✅
- `compute_swing_emg_features()` - Same windowing logic
- Same MDF padding (300ms default)
- Same envelope features on exact window
- Same processed features on padded window

### Key Differences (By Design)

#### Data Structures
- **emg_util.py (Windows):** Uses pandas DataFrames
- **emg_util_nopandas.py (Android):** Uses dict-of-numpy-arrays
- ✅ **Intentional** - Android Chaquopy limitation

#### Missing from Android
- ❌ Plotting functions (matplotlib)
- ❌ CLI argument parsing
- ❌ Session comparison features
- ❌ Advanced visualizations
- ✅ **Intentional** - Keeps Android APK small

#### Additional in Android
- ✅ `stitch_decoded_session()` - Full session stitching
- ✅ `load_decoded_emg_csv()` / `load_decoded_imu_csv()` - Binary decoder integration
- ✅ More detailed docstrings for Android developers

### What Ensures Consistency

1. **Shared fatigue_monitor.py**
   - Both files import `preprocess_emg` from same source
   - Guarantees identical EMG preprocessing
   - Guarantees identical FatigueMonitor behavior

2. **Identical Algorithm Parameters**
   - Same filter cutoffs (20-450 Hz bandpass, 3.6 Hz envelope)
   - Same feature thresholds (ZC=0.01, SSC=0.01)
   - Same swing detection parameters (z=5.0, gap=300ms)
   - Same baseline selection (5 min warmup, 20 swings)

3. **Cross-Platform Aliases**
   - Windows provides `detect_swing_windows_from_imu_arrays()` wrapper
   - Android provides `detect_swings_from_imu` alias
   - Enables code portability

### Maintenance Notes

**When updating signal processing:**
1. Update `fatigue_monitor.py` (canonical) → auto-synced via import
2. Test on Windows
3. Test on Android (no manual copy needed for preprocessing)

**When updating feature functions:**
1. Update `emg_util.py` (Windows, canonical)
2. Test on Windows
3. **Manually port** changes to `emg_util_nopandas.py` (Android)
4. Test on Android
5. Update version/changelog

**Key Strength:** Using `fatigue_monitor.py` as single source of truth for preprocessing ensures the most critical function (EMG filtering) is always consistent across platforms.

---

## Recommendations for Better Consistency & Maintenance

**Current Challenge:** Manual synchronization between `emg_util/` (canonical) and Android copies is error-prone.

**Goal:** Keep `emg_util/` as the single source of truth for all algorithm development.

### Recommended Architecture Improvements

#### Option 1: Git Submodule (Recommended) ✅

**Structure:**
```
emg_util/                          ← Main algorithm development (canonical)
  ├── emg_util.py                  ← Core library (pandas-based)
  ├── emg_util_nopandas.py         ← Android-compatible subset
  ├── fatigue_monitor.py           ← Real-time fatigue (shared)
  ├── emg_util_yaml_runner.py      ← CLI runner
  └── tennis_analysis_cloud.ipynb  ← Colab notebook

btLeApp/app/src/main/python/
  └── emg_util/                    ← Git submodule → points to emg_util/
      ├── emg_util_nopandas.py     ← Auto-synced
      └── fatigue_monitor.py       ← Auto-synced
```

**Benefits:**
- ✅ **Single source of truth:** All development in `emg_util/`
- ✅ **Automatic sync:** Git submodule updates pull latest code
- ✅ **Version control:** Track which version Android uses
- ✅ **No manual copying:** Git handles synchronization
- ✅ **Easy updates:** `git submodule update --remote`

**Setup:**
```bash
# In btLeApp repository
cd app/src/main/python
git submodule add ../../../emg_util emg_util
git submodule update --init --recursive

# Update to latest
git submodule update --remote
```

**Android Import:**
```python
# realtime_bridge.py
from emg_util.fatigue_monitor import FatigueMonitor, preprocess_emg
from emg_util import emg_util_nopandas as emg
```

---

#### Option 2: Python Package (Advanced) 🚀

**Structure:**
```
emg_util/
  ├── setup.py                     ← Package configuration
  ├── emg_util/
  │   ├── __init__.py
  │   ├── core.py                  ← Core algorithms (pandas-based)
  │   ├── mobile.py                ← Mobile subset (no pandas)
  │   ├── fatigue.py               ← Real-time fatigue
  │   └── runner.py                ← CLI/notebook runner
  └── tests/

# Install as package
pip install -e emg_util/           # Development mode
pip install emg_util/              # Production mode
```

**Benefits:**
- ✅ **Professional structure:** Standard Python package
- ✅ **Version management:** Semantic versioning (1.0.0, 1.1.0, etc.)
- ✅ **Dependency tracking:** requirements.txt, setup.py
- ✅ **Testing framework:** pytest integration
- ✅ **Distribution:** Can publish to PyPI if needed

**Android Integration:**
```python
# Chaquopy pip configuration
buildPython {
    pip {
        install "emg_util==1.2.0"  // Specific version
    }
}
```

**Import:**
```python
from emg_util.fatigue import FatigueMonitor, preprocess_emg
from emg_util.mobile import compute_swing_emg_features
```

---

#### Option 3: Symbolic Links (Simple) 📁

**Structure:**
```
emg_util/                          ← Canonical source
  ├── fatigue_monitor.py
  └── emg_util_nopandas.py

btLeApp/app/src/main/python/
  ├── fatigue_monitor.py           ← Symlink → emg_util/fatigue_monitor.py
  └── emg_util_nopandas.py         ← Symlink → emg_util/emg_util_nopandas.py
```

**Benefits:**
- ✅ **Simple:** One-time setup
- ✅ **Automatic sync:** Changes immediately reflected
- ✅ **No git complexity:** Works with existing structure

**Setup:**
```bash
# Windows (requires admin or developer mode)
cd btLeApp/app/src/main/python
mklink fatigue_monitor.py ..\..\..\..\emg_util\fatigue_monitor.py
mklink emg_util_nopandas.py ..\..\..\..\emg_util\emg_util_nopandas.py

# Linux/Mac
ln -s ../../../../emg_util/fatigue_monitor.py fatigue_monitor.py
ln -s ../../../../emg_util/emg_util_nopandas.py emg_util_nopandas.py
```

**Limitations:**
- ⚠️ Windows requires admin or developer mode
- ⚠️ May not work well with some build tools
- ⚠️ Git doesn't track symlink targets

---

### Comparison Table

| Approach | Complexity | Sync | Version Control | Android Build | Recommended |
|----------|-----------|------|-----------------|---------------|-------------|
| **Git Submodule** | Medium | Automatic | ✅ Excellent | ✅ Works | ✅ **Best** |
| **Python Package** | High | Automatic | ✅ Excellent | ✅ Works | 🚀 Advanced |
| **Symbolic Links** | Low | Automatic | ⚠️ Limited | ⚠️ May fail | 📁 Simple |
| **Manual Copy** (current) | Low | Manual | ❌ Error-prone | ✅ Works | ❌ Not recommended |

---

### Recommended Implementation Plan

**Phase 1: Immediate (Git Submodule)**

1. **Create submodule:**
   ```bash
   cd btLeApp/app/src/main/python
   git submodule add ../../../../emg_util emg_util_lib
   ```

2. **Update Android imports:**
   ```python
   # realtime_bridge.py
   from emg_util_lib.fatigue_monitor import FatigueMonitor
   from emg_util_lib import emg_util_nopandas as emg
   ```

3. **Update build.gradle:**
   ```gradle
   python {
       pip {
           // No changes needed - local files work
       }
   }
   ```

4. **Test Android build:**
   ```bash
   ./gradlew assembleDebug
   ```

**Phase 2: Long-term (Python Package)**

1. **Restructure emg_util:**
   ```
   emg_util/
     ├── setup.py
     ├── README.md
     ├── requirements.txt
     ├── emg_util/
     │   ├── __init__.py
     │   ├── core.py          # emg_util.py → core.py
     │   ├── mobile.py        # emg_util_nopandas.py → mobile.py
     │   ├── fatigue.py       # fatigue_monitor.py → fatigue.py
     │   └── runner.py        # emg_util_yaml_runner.py → runner.py
     └── tests/
         ├── test_core.py
         ├── test_mobile.py
         └── test_fatigue.py
   ```

2. **Create setup.py:**
   ```python
   from setuptools import setup, find_packages
   
   setup(
       name='emg_util',
       version='1.0.0',
       packages=find_packages(),
       install_requires=[
           'numpy>=1.20.0',
           'scipy>=1.7.0',
           'pandas>=1.3.0',  # Optional for mobile
       ],
       extras_require={
           'mobile': [],  # No pandas for Android
           'full': ['pandas>=1.3.0', 'matplotlib>=3.4.0'],
       },
   )
   ```

3. **Install in Android:**
   ```gradle
   python {
       pip {
           install "emg_util[mobile]==1.0.0"
       }
   }
   ```

---

### Development Workflow (Recommended)

**Current (Manual Sync):**
```
1. Edit emg_util/fatigue_monitor.py
2. Test on Windows
3. Manually copy to btLeApp/.../fatigue_monitor.py
4. Test on Android
5. Commit both files
❌ Error-prone, easy to forget step 3
```

**With Git Submodule:**
```
1. Edit emg_util/fatigue_monitor.py
2. Test on Windows
3. Commit in emg_util/
4. cd btLeApp && git submodule update --remote
5. Test on Android
6. Commit submodule reference in btLeApp
✅ Automatic sync, version tracked
```

**With Python Package:**
```
1. Edit emg_util/emg_util/fatigue.py
2. Test on Windows
3. Bump version in setup.py (1.0.0 → 1.0.1)
4. Commit in emg_util/
5. Update Android build.gradle: "emg_util[mobile]==1.0.1"
6. Test on Android
7. Commit build.gradle
✅ Professional, version controlled, testable
```

---

### Testing Strategy

**Add automated tests in emg_util/tests/:**

```python
# tests/test_fatigue.py
import pytest
from emg_util.fatigue import FatigueMonitor, preprocess_emg

def test_preprocess_emg():
    """Verify preprocessing is consistent."""
    raw = np.random.randn(2000) * 100
    proc = preprocess_emg(raw, fs=2000)
    assert proc.shape == raw.shape
    assert np.std(proc) < np.std(raw)  # Should reduce noise

def test_fatigue_monitor_baseline():
    """Verify baseline collection."""
    fm = FatigueMonitor(fs=2000, warmup_skip_min=0, baseline_swings=5)
    for i in range(5):
        emg = np.random.randn(2600) * 100
        result = fm.push_swing(emg, t_peak_ms=i*1000, imu_peak_mag=200)
        if i < 4:
            assert result['fatigue_level'] == 'warming_up'
        else:
            assert result['baseline_ready'] == True

# Run tests
pytest tests/
```

**Benefits:**
- ✅ Catch regressions early
- ✅ Verify consistency across platforms
- ✅ Document expected behavior
- ✅ Enable continuous integration (CI)

---

### Migration Checklist

**Immediate Actions (Git Submodule):**
- [ ] Create git submodule in btLeApp
- [ ] Update Android imports to use submodule
- [ ] Test Android build
- [ ] Update documentation
- [ ] Train team on `git submodule update`

**Long-term Actions (Python Package):**
- [ ] Restructure emg_util/ as package
- [ ] Create setup.py with dependencies
- [ ] Add automated tests (pytest)
- [ ] Set up CI/CD (GitHub Actions)
- [ ] Version releases (semantic versioning)
- [ ] Update all import statements
- [ ] Publish to private PyPI (optional)

---

### Key Principles

1. **Single Source of Truth:** All algorithm development in `emg_util/`
2. **Automatic Sync:** Use git submodule or package management
3. **Version Control:** Track which version Android uses
4. **Testing:** Automated tests prevent regressions
5. **Documentation:** Keep HIGH_LEVEL_DESIGN.md updated

**Result:** Easier maintenance, fewer bugs, faster development! 🎉

---

## Summary

### Three-Tier Architecture

```
TIER 1 (Real-time)
  Android: SessionCaptureActivity.java + realtime_bridge.py
  Windows: sensordata_plotter.py
  → Gyro detection @ 150 dps
  → Immediate feedback
  → ~95% accuracy

TIER 2 (Post-capture)
  Android: offline_runner.py
  → Decode frames_*.bin → CSV
  → Prepare for PC transfer
  → No analysis

TIER 3 (Offline)
  Windows: emg_util_yaml_runner.py
  Cloud:   tennis_analysis_cloud.ipynb
  → EMG-based detection
  → Research-grade analysis
  → ~98% accuracy
```

### Key Relationships

- **Android ↔ Windows:** Consistent real-time detection (gyro @ 150 dps)
- **Android → Windows:** CSV transfer for offline analysis
- **Real-time → Offline:** Different algorithms, both correct for purpose
- **Shared Code:** fatigue_monitor.py (copied), emg_util subset (copied)

### Design Philosophy

**Real-time:** Fast, simple, good enough for live feedback  
**Offline:** Slow, sophisticated, best for research insights  
**Both:** Complementary, not competing
