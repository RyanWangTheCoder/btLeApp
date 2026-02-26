# -*- coding: utf-8 -*-
"""realtime_bridge.py

Android-specific real-time fatigue monitoring during live BLE capture.
Called from Java via Chaquopy during an active recording session.

No Windows equivalent — this is Android-only live inference.

Functions:
  reset_fatigue_monitor()     -- call at session start (Java: startSession)
  push_swing_realtime(...)    -- call after each IMU swing trigger
  get_fatigue_summary()       -- call at session end (Java: stopSession)
  run_demo()                  -- quick smoke test

Java usage:
    PyObject mod = Python.getInstance().getModule("realtime_bridge");
    mod.callAttr("reset_fatigue_monitor");
    // ... for each swing:
    String json = mod.callAttr("push_swing_realtime",
                      emgJsonStr, tPeakMs, imuPeakMag).toString();
    // ... at session end:
    String summary = mod.callAttr("get_fatigue_summary").toString();
"""

import json
import numpy as np

from fatigue_monitor import FatigueMonitor
from emg_util_nopandas import compute_windowed_features, analyze_fatigue_from_windowed


# ---------------------------------------------------------------------------
# Module-level singleton — persists across Chaquopy calls within one session
# ---------------------------------------------------------------------------

_monitor: FatigueMonitor = FatigueMonitor(
    fs=2000.0,
    warmup_skip_min=5.0,
    baseline_swings=20,
    mdf_warn_pct=12.0,
    mdf_high_pct=20.0,
)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def reset_fatigue_monitor(
    fs: float = 2000.0,
    warmup_skip_min: float = 5.0,
    baseline_swings: int = 20,
    mdf_warn_pct: float = 12.0,
    mdf_high_pct: float = 20.0,
) -> str:
    """Reset the fatigue monitor for a new session.

    Call this from Java when startSession() is called.

    Args:
        fs:                 EMG sample rate in Hz (default 2000)
        warmup_skip_min:    minutes to skip before collecting baseline (default 5)
        baseline_swings:    number of swings to use for baseline (default 20)
        mdf_warn_pct:       MDF drop % threshold for warning (default 12)
        mdf_high_pct:       MDF drop % threshold for high fatigue (default 20)

    Returns:
        JSON: {"status": "reset", "fs": ..., "warmup_skip_min": ...}

    Java usage:
        mod.callAttr("reset_fatigue_monitor");
        // or with custom params:
        mod.callAttr("reset_fatigue_monitor", 2000.0, 5.0, 20, 12.0, 20.0);
    """
    global _monitor
    _monitor = FatigueMonitor(
        fs=float(fs),
        warmup_skip_min=float(warmup_skip_min),
        baseline_swings=int(baseline_swings),
        mdf_warn_pct=float(mdf_warn_pct),
        mdf_high_pct=float(mdf_high_pct),
    )
    return json.dumps({
        "status": "reset",
        "fs": float(fs),
        "warmup_skip_min": float(warmup_skip_min),
        "baseline_swings": int(baseline_swings),
    })


def push_swing_realtime(
    emg_raw_json: str,
    t_peak_ms: float,
    imu_peak_mag: float,
) -> str:
    """Process one swing window and return fatigue metrics as JSON.

    Called from Java fatigueThread after each IMU swing trigger.

    Args:
        emg_raw_json:  JSON array string of raw EMG samples at 2000 Hz.
                       Window should be ~1300ms (±300ms MDF padding around swing).
                       e.g. "[2048, 2051, 2045, ...]"  (~2600 samples)
        t_peak_ms:     time of swing peak in ms since session start
        imu_peak_mag:  IMU gyro/accel magnitude at swing peak

    Returns:
        JSON string with fatigue metrics:
          swing_id                  int    — sequential swing number (0-based)
          mdf                       float  — median frequency (Hz), null if insufficient data
          rms                       float  — RMS amplitude, null if insufficient data
          mdf_drop_pct              float  — % drop from baseline, null until baseline ready
          fatigue_pct               float  — 0–100 fatigue score, null until baseline ready
          fatigue_level             str    — "warming_up" | "fresh" | "moderate" | "high"
          warning                   bool   — true if mdf_drop_pct >= mdf_warn_pct (12%)
          baseline_ready            bool   — true once baseline_swings collected
          baseline_swings_collected int    — swings collected toward baseline so far

    Java usage:
        String json = mod.callAttr("push_swing_realtime",
            emgJsonStr, tPeakMs, imuPeakMag).toString();
        JSONObject obj = new JSONObject(json);
        String level = obj.getString("fatigue_level");
        boolean warn = obj.getBoolean("warning");
        if (warn) showFatigueWarning();
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
        return json.dumps({
            "error": str(e),
            "fatigue_level": "unknown",
            "warning": False,
        })


def get_fatigue_summary() -> str:
    """Return full session fatigue summary as JSON.

    Call this at session end (Java: stopSession) to get aggregate stats.

    Returns:
        JSON string with session-level fatigue summary from FatigueMonitor.

    Java usage:
        String json = mod.callAttr("get_fatigue_summary").toString();
    """
    try:
        return json.dumps(_monitor.get_summary())
    except Exception as e:
        return json.dumps({"error": str(e)})


def run_demo() -> str:
    """Quick smoke test — generates synthetic EMG and runs fatigue analysis.

    Returns:
        JSON: {"mode": "demo", "windows": N, "mdf_drop_pct": X}

    Java usage:
        String json = mod.callAttr("run_demo").toString();
    """
    fs = 2000.0
    dur_s = 5.0
    n = int(fs * dur_s)
    t = np.arange(n) / fs
    rng = np.random.default_rng(123)
    x = rng.standard_normal(n) * (0.2 + 0.8 * (t / dur_s))
    sig, feats = compute_windowed_features(
        x, emg_fs_hz=fs, emg_start_ts_ms=0.0, win_sec=1.0, step_sec=0.25)
    fat = analyze_fatigue_from_windowed(feats)
    return json.dumps({
        "mode": "demo",
        "windows": int(feats["t_center_s"].size),
        "mdf_drop_pct": float(fat.mdf_drop_pct),
    })
