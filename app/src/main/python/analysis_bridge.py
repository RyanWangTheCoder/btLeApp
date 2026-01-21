# -*- coding: utf-8 -*-
import json
import os
import numpy as np

from emg_util_nopandas import compute_windowed_features, analyze_fatigue_from_windowed


def run_demo():
    fs = 2000.0
    dur_s = 5.0
    n = int(fs * dur_s)
    t = np.arange(n) / fs
    rng = np.random.default_rng(123)
    x = rng.standard_normal(n) * (0.2 + 0.8 * (t / dur_s))
    sig, feats = compute_windowed_features(x, emg_fs_hz=fs, emg_start_ts_ms=0.0, win_sec=1.0, step_sec=0.25)
    fat = analyze_fatigue_from_windowed(feats)
    return json.dumps({"mode": "demo", "windows": int(feats.t_center_s.size), "mdf_drop_pct": float(fat.mdf_drop_pct)})


def process_session(session_dir: str):
    session_dir = str(session_dir)
    frames = os.path.join(session_dir, 'frames.bin')
    meta = os.path.join(session_dir, 'meta.json')
    out = {
        "mode": "process_session_stub",
        "session_dir": session_dir,
        "frames_exists": os.path.exists(frames),
        "meta_exists": os.path.exists(meta),
        "frames_size": os.path.getsize(frames) if os.path.exists(frames) else 0,
    }
    return json.dumps(out)
