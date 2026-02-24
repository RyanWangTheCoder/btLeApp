# -*- coding: utf-8 -*-
"""analysis_bridge.py

Python entry points called from Java via Chaquopy.

Functions:
  run_demo()                  -- quick smoke test, returns JSON
  process_session(dir)        -- offline decode frames.bin -> rotated CSVs
                                 (full analysis runs on PC via emg_util_yaml_runner.py)
  reset_fatigue_monitor()     -- call at session start to reset state
  push_swing_realtime(...)    -- call after each swing trigger during capture
  get_fatigue_summary()       -- call at session end for full summary

Offline analysis pipeline (PC):
  emg_util_yaml_runner.py --config config.yaml --log-dir <folder>
  → analyze_tennis_session() stitches ALL BLE captures in the folder as one
    continuous tennis session (activity gating, swing detection, fatigue, bouts).
"""

import json
import os
import numpy as np

from emg_util_nopandas import (
    FatigueMonitor,
    compute_windowed_features,
    analyze_fatigue_from_windowed,
)
from btcentral_offline_decode import decode_frames_bin_to_rotated_csv


# ============================
# Offline processing
# ============================

def run_demo():
    fs = 2000.0
    dur_s = 5.0
    n = int(fs * dur_s)
    t = np.arange(n) / fs
    rng = np.random.default_rng(123)
    x = rng.standard_normal(n) * (0.2 + 0.8 * (t / dur_s))
    sig, feats = compute_windowed_features(x, emg_fs_hz=fs, emg_start_ts_ms=0.0, win_sec=1.0, step_sec=0.25)
    fat = analyze_fatigue_from_windowed(feats)
    return json.dumps({
        "mode": "demo",
        "windows": int(feats["t_center_s"].size),
        "mdf_drop_pct": float(fat.mdf_drop_pct),
    })


def process_session(session_dir: str) -> str:
    """Decode a recorded session: frames.bin → rotated CSVs under <session_dir>/decoded/.

    This is the on-device decode step only. Full analysis (stitching all captures
    in a folder as one tennis session, activity gating, swing detection, fatigue,
    bouts) is done on PC via:

        python emg_util_yaml_runner.py --config config.yaml --log-dir <folder>

    The decoded CSVs are compatible with emg_util_yaml_runner.py's
    capture_loader.discover_sessions() which expects:
        emg_log_<session_id>_<open_id>_<idx>.csv
        imu_log_<session_id>_<open_id>_<idx>.csv

    Args:
        session_dir: path to session directory (contains frames.bin + meta.json)

    Returns:
        JSON string with decode stats and output file paths.
    """
    session_dir = str(session_dir)
    stats = decode_frames_bin_to_rotated_csv(
        session_dir, rotate_min=20.0, overwrite_decoded=True
    )
    return json.dumps(stats)


# ============================
# Real-time fatigue monitor (singleton per session)
# ============================

# Module-level singleton — persists across Chaquopy calls within the same session
_monitor: FatigueMonitor = FatigueMonitor(
    fs=2000.0,
    warmup_skip_min=5.0,
    baseline_swings=20,
    mdf_warn_pct=12.0,
    mdf_high_pct=20.0,
)


def reset_fatigue_monitor(
    fs: float = 2000.0,
    warmup_skip_min: float = 5.0,
    baseline_swings: int = 20,
    mdf_warn_pct: float = 12.0,
    mdf_high_pct: float = 20.0,
) -> str:
    """Reset the fatigue monitor for a new session.

    Call this from Java when startSession() is called.
    Returns JSON confirmation.

    Java usage:
        mod.callAttr("reset_fatigue_monitor")
        // or with custom params:
        mod.callAttr("reset_fatigue_monitor", 2000.0, 5.0, 20, 12.0, 20.0)
    """
    global _monitor
    _monitor = FatigueMonitor(
        fs=float(fs),
        warmup_skip_min=float(warmup_skip_min),
        baseline_swings=int(baseline_swings),
        mdf_warn_pct=float(mdf_warn_pct),
        mdf_high_pct=float(mdf_high_pct),
    )
    return json.dumps({"status": "reset", "fs": float(fs), "warmup_skip_min": float(warmup_skip_min)})


def push_swing_realtime(
    emg_raw_json: str,
    t_peak_ms: float,
    imu_peak_mag: float,
) -> str:
    """Process one swing window and return fatigue metrics as JSON.

    Called from Java fatigueThread after each IMU swing trigger.

    Args:
        emg_raw_json:  JSON array string of raw EMG samples at 2000 Hz
                       (1300ms window = ~2600 samples, includes ±300ms MDF padding)
                       e.g. "[2048, 2051, 2045, ...]"
        t_peak_ms:     time of swing peak in ms since session start
        imu_peak_mag:  IMU acceleration magnitude at swing peak

    Returns:
        JSON string with fatigue metrics. Key fields:
            swing_id        int    — sequential swing number
            mdf             float  — median frequency (Hz), null if not enough data
            rms             float  — RMS amplitude, null if not enough data
            mdf_drop_pct    float  — % drop from baseline, null until baseline ready
            fatigue_pct     float  — 0-100 fatigue score, null until baseline ready
            fatigue_level   str    — "warming_up" | "fresh" | "moderate" | "high"
            warning         bool   — true if mdf_drop_pct >= 12%
            baseline_ready  bool   — true once 20 baseline swings collected
            baseline_swings_collected int

    Java usage:
        String json = mod.callAttr("push_swing_realtime",
            emgJsonStr, tPeakMs, imuPeakMag).toString();
        JSONObject obj = new JSONObject(json);
        String level = obj.getString("fatigue_level");
        boolean warn  = obj.getBoolean("warning");
    """
    try:
        emg_raw = json.loads(emg_raw_json)
        result = _monitor.push_swing(
            emg_raw=emg_raw,
            t_peak_ms=float(t_peak_ms),
            imu_peak_mag=float(imu_peak_mag),
        )
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"error": str(e), "fatigue_level": "unknown", "warning": False})


def get_fatigue_summary() -> str:
    """Return full session fatigue summary as JSON.

    Call this at session end (stopSession) to get aggregate stats.

    Java usage:
        String json = mod.callAttr("get_fatigue_summary").toString();
    """
    try:
        return json.dumps(_monitor.get_summary())
    except Exception as e:
        return json.dumps({"error": str(e)})
