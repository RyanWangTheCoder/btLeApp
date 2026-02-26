# -*- coding: utf-8 -*-
"""emg_util_nopandas.py

A pandas-free subset of your `emg_util.py`, designed to run under Chaquopy on Android.

What this module keeps (core computation):
- EMG preprocessing: detrend + bandpass + notch
- EMG envelope: rectify + lowpass
- Windowed features: RMS, ENV mean/peak, MNF, MDF, (optional MAV/WL/ZC/SSC)
- Simple fatigue analysis (MDF/MNF drop + optional onset detection)
- Optional IMU swing detection (robust z-score on gyro/acc magnitude)
- FatigueMonitor: stateful per-session real-time fatigue tracking

What this module removes (Android-unfriendly):
- pandas DataFrames
- matplotlib plotting
- CLI argument parsing

Data interchange:
- Functions return plain Python dict-of-numpy-arrays ("columnar" format) or lists of dict rows.

This is intentionally lightweight; you can extend it as needed.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Tuple, Union

import csv
import glob
from pathlib import Path

import numpy as np

# SciPy is optional but recommended for better filters/PSD.
try:
    from scipy.signal import butter, filtfilt, iirnotch, detrend, welch
    _HAVE_SCIPY = True
except Exception:
    butter = filtfilt = iirnotch = detrend = welch = None
    _HAVE_SCIPY = False


EMG_IMU_COLS = ["ts_ms", "env", "raw", "ref", "ax", "ay", "az", "gx", "gy", "gz"]
VITALS_COLS = ["ts_ms", "spo2", "hr_bpm", "age_ms", "flags", "brightness"]


# ============================
# Small utilities (no pandas)
# ============================

def _to_float(x: str) -> float:
    try:
        if x is None:
            return float('nan')
        s = str(x).strip()
        if s == '' or s.lower() == 'nan':
            return float('nan')
        return float(s)
    except Exception:
        return float('nan')


def _as_array(d: Dict[str, List[float]], cols: List[str]) -> Dict[str, np.ndarray]:
    return {c: np.asarray(d.get(c, []), dtype=np.float64) for c in cols}


def _nan_rolling_mean(x: np.ndarray, win: int, min_periods: int = 1) -> np.ndarray:
    """NaN-aware rolling mean using cumulative sums."""
    x = np.asarray(x, dtype=np.float64)
    n = x.size
    if win <= 1 or n == 0:
        return x.copy()

    isn = ~np.isfinite(x)
    xv = x.copy()
    xv[isn] = 0.0
    cnt = (~isn).astype(np.float64)

    csum = np.cumsum(xv)
    ccnt = np.cumsum(cnt)

    out = np.full(n, np.nan, dtype=np.float64)
    for i in range(n):
        j0 = max(0, i - win + 1)
        s = csum[i] - (csum[j0 - 1] if j0 > 0 else 0.0)
        c = ccnt[i] - (ccnt[j0 - 1] if j0 > 0 else 0.0)
        if c >= float(min_periods):
            out[i] = s / max(c, 1.0)
    return out


def interpolate_nans_limited(x: np.ndarray, limit: int = 100) -> np.ndarray:
    """Linear interpolation for NaN gaps up to `limit` samples.

    Roughly matches: pandas.Series(x).interpolate(limit=limit, limit_direction='both').
    For gaps longer than `limit`, values remain NaN.
    """
    x = np.asarray(x, dtype=np.float64)
    n = x.size
    if n == 0:
        return x

    y = x.copy()
    idx = np.arange(n)
    good = np.isfinite(y)

    if not np.any(good):
        return y

    # Full interpolation (including ends via nearest)
    y_interp = np.interp(idx, idx[good], y[good])

    # Restore long NaN runs (more than limit)
    isn = ~good
    if np.any(isn):
        # find NaN runs
        run_start = None
        for i in range(n + 1):
            v = isn[i] if i < n else False
            if v and run_start is None:
                run_start = i
            if (not v) and run_start is not None:
                run_end = i  # exclusive
                run_len = run_end - run_start
                if run_len > int(limit):
                    y_interp[run_start:run_end] = np.nan
                run_start = None

    return y_interp


# ============================
# CSV loading (optional)
# ============================

def load_csv_columns(path: Union[str, Path], cols: List[str]) -> Dict[str, np.ndarray]:
    """Read selected columns from a CSV into a dict of numpy arrays."""
    d: Dict[str, List[float]] = {c: [] for c in cols}
    with open(path, 'r', newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            for c in cols:
                d[c].append(_to_float(row.get(c)))
    return _as_array(d, cols)


def load_emg_imu_logs(pattern_or_paths: Union[str, Path, Iterable[str]]) -> Dict[str, np.ndarray]:
    """Load one or many interleaved EMG/IMU CSV logs (no pandas).

    Returns a dict with keys EMG_IMU_COLS containing float64 arrays.
    """
    if isinstance(pattern_or_paths, (str, Path)):
        paths = sorted(glob.glob(str(pattern_or_paths)))
        if not paths:
            paths = [str(pattern_or_paths)]
    else:
        paths = list(pattern_or_paths)

    agg: Dict[str, List[float]] = {c: [] for c in EMG_IMU_COLS}

    for p in paths:
        data = load_csv_columns(p, EMG_IMU_COLS)
        for c in EMG_IMU_COLS:
            agg[c].extend(data[c].tolist())

    out = _as_array(agg, EMG_IMU_COLS)
    # sort by ts_ms (stable)
    ts = out["ts_ms"]
    order = np.argsort(ts, kind='mergesort') if ts.size else np.array([], dtype=np.int64)
    for c in EMG_IMU_COLS:
        out[c] = out[c][order]
    return out


def load_vitals_log(path: Union[str, Path]) -> Dict[str, np.ndarray]:
    data = load_csv_columns(path, VITALS_COLS)
    # drop rows with NaN ts
    m = np.isfinite(data['ts_ms'])
    for c in VITALS_COLS:
        data[c] = data[c][m]
    # sort
    order = np.argsort(data['ts_ms'], kind='mergesort') if data['ts_ms'].size else np.array([], dtype=np.int64)
    for c in VITALS_COLS:
        data[c] = data[c][order]
    return data


# ============================
# Filtering helpers
# ============================

def filtfilt_safe(b, a, x: np.ndarray) -> np.ndarray:
    x = np.asarray(x, dtype=np.float64)
    n = x.size
    if n == 0:
        return x
    if not _HAVE_SCIPY:
        # Fallback: no zero-phase filtering available.
        return x
    padlen = 3 * (max(len(a), len(b)) - 1)
    if n <= padlen:
        return filtfilt(b, a, x, method="gust")
    return filtfilt(b, a, x)


# preprocess_emg is the single source of truth in fatigue_monitor.py.
# Imported below alongside FatigueMonitor — do NOT define it here.


def envelope(x: np.ndarray, fs: float, cutoff_hz: float = 3.6) -> np.ndarray:
    x = np.asarray(x, dtype=np.float64)
    if x.size == 0:
        return x
    rect = np.abs(x)

    if _HAVE_SCIPY:
        nyq = fs / 2.0
        c = min(float(cutoff_hz), 0.95 * nyq)
        b, a = butter(2, c / nyq, btype="low")
        y = filtfilt_safe(b, a, rect)
        return np.maximum(y, 0.0)

    # Numpy-only fallback: moving average
    win = max(1, int(round(fs / max(cutoff_hz, 1e-6))))
    k = np.ones(win, dtype=np.float64) / float(win)
    y = np.convolve(rect, k, mode='same')
    return np.maximum(y, 0.0)


# ============================
# Feature helpers
# ============================

def rms(x: np.ndarray) -> float:
    x = np.asarray(x, dtype=np.float64)
    return float(np.sqrt(np.nanmean(x * x))) if x.size else float('nan')


def mnf_mdf(x: np.ndarray, fs: float) -> Tuple[float, float]:
    x = np.asarray(x, dtype=np.float64)
    if x.size < 2:
        return float('nan'), float('nan')

    if _HAVE_SCIPY:
        f, Pxx = welch(
            x,
            fs=float(fs),
            nperseg=min(1024, len(x)),
            noverlap=min(512, len(x) // 2),
            window="hann",
            scaling="density",
        )
        if Pxx.size == 0:
            return float('nan'), float('nan')
        Pxx = np.maximum(Pxx, 1e-20)
        s = float(np.sum(Pxx))
        mnf_val = float(np.sum(f * Pxx) / s)
        cdf = np.cumsum(Pxx)
        mdf_val = float(f[min(int(np.searchsorted(cdf, 0.5 * s)), len(f) - 1)])
        return mnf_val, mdf_val

    # Numpy-only fallback using FFT power spectrum
    n = x.size
    w = np.hamming(n)
    xw = (x - np.nanmean(x)) * w
    X = np.fft.rfft(xw)
    p = (np.abs(X) ** 2) / (np.sum(w ** 2) + 1e-12)
    freqs = np.fft.rfftfreq(n, d=1.0 / float(fs))
    if freqs.size > 1:
        freqs, p = freqs[1:], p[1:]  # drop DC
    total = float(np.sum(p))
    if total <= 0 or not np.isfinite(total):
        return float('nan'), float('nan')
    mnf_val = float(np.sum(freqs * p) / total)
    cdf = np.cumsum(p)
    idx = int(np.searchsorted(cdf, 0.5 * total))
    idx = min(max(idx, 0), freqs.size - 1)
    mdf_val = float(freqs[idx])
    return mnf_val, mdf_val


# Classic EMG features

def feat_mav(sig: np.ndarray) -> float:
    sig = np.asarray(sig, dtype=np.float64)
    return float(np.nanmean(np.abs(sig)))


def feat_rms(sig: np.ndarray) -> float:
    sig = np.asarray(sig, dtype=np.float64)
    return float(np.sqrt(np.nanmean(sig * sig)))


def feat_wl(sig: np.ndarray) -> float:
    sig = np.asarray(sig, dtype=np.float64)
    return float(np.nansum(np.abs(np.diff(sig))))


def feat_zc(sig: np.ndarray, thr: float = 0.01) -> float:
    sig = np.asarray(sig, dtype=np.float64)
    s = sig - np.nanmean(sig)
    return float(np.nansum(((s[:-1] * s[1:]) < 0) & (np.abs(s[:-1] - s[1:]) > thr)))


def feat_ssc(sig: np.ndarray, thr: float = 0.01) -> float:
    sig = np.asarray(sig, dtype=np.float64)
    s = sig - np.nanmean(sig)
    a = s[1:-1] - s[:-2]
    b = s[1:-1] - s[2:]
    return float(np.nansum((a * b > 0) & (np.abs(a) > thr) & (np.abs(b) > thr)))


# ============================
# Core compute: windowed features
# ============================

@dataclass
class Results:
    feats: Dict[str, np.ndarray]
    emg_ts_ms: np.ndarray
    emg_raw: np.ndarray
    emg_proc: np.ndarray
    emg_env: np.ndarray


def compute_windowed_features(
    emg: np.ndarray,
    *,
    emg_fs_hz: float,
    emg_start_ts_ms: float = 0.0,
    win_sec: float = 1.0,
    step_sec: float = 0.25,
    treat_zero_as_nan: bool = False,
    interpolate_limit: int = 100,
    include_classic: bool = False,
    zc_thr: float = 0.01,
    ssc_thr: float = 0.01,
) -> Tuple[np.ndarray, Dict[str, np.ndarray]]:
    """Compute processed EMG + envelope + sliding-window features.

    Returns (sig_float64, feats_dict).
    feats_dict contains numpy arrays.

    Android tip: keep include_classic=False unless you need MAV/WL/ZC/SSC.
    """
    fs = float(emg_fs_hz)
    x = np.asarray(emg, dtype=np.float64).reshape(-1)

    if treat_zero_as_nan:
        x = x.copy()
        x[x == 0] = np.nan

    x = interpolate_nans_limited(x, limit=int(interpolate_limit))

    proc = preprocess_emg(x, fs)
    env = envelope(proc, fs)

    win_samp = max(int(round(float(win_sec) * fs)), 256)
    step_samp = max(int(round(float(step_sec) * fs)), 1)

    n = x.size
    if n < win_samp:
        # Empty output
        empty = np.array([], dtype=np.float64)
        feats = {
            "t_center_s": empty,
            "t_start_ms": empty,
            "t_end_ms": empty,
            "rms": empty,
            "env_mean": empty,
            "env_peak": empty,
            "mnf": empty,
            "mdf": empty,
        }
        return x, feats

    # Build timestamps for each sample, consistent with your emg_util.py
    t0_ms = float(emg_start_ts_ms)
    emg_ts_ms = t0_ms + (np.arange(n, dtype=np.float64) / fs) * 1000.0

    rows: List[Dict[str, float]] = []
    for i0 in range(0, n - win_samp + 1, step_samp):
        i1 = i0 + win_samp
        t_start_ms = float(emg_ts_ms[i0])
        t_end_ms = float(emg_ts_ms[i1 - 1])
        seg = proc[i0:i1]
        seg_env = env[i0:i1]

        mnf_val, mdf_val = mnf_mdf(seg, fs)
        r = {
            "t_start_ms": t_start_ms,
            "t_end_ms": t_end_ms,
            "t_center_s": 0.5 * (t_start_ms + t_end_ms) / 1000.0,
            "rms": feat_rms(seg),
            "env_mean": float(np.nanmean(seg_env)),
            "env_peak": float(np.nanmax(seg_env)),
            "mnf": float(mnf_val),
            "mdf": float(mdf_val),
        }
        if include_classic:
            r.update({
                "mav": feat_mav(seg),
                "wl": feat_wl(seg),
                "zc": feat_zc(seg, thr=zc_thr),
                "ssc": feat_ssc(seg, thr=ssc_thr),
            })
        rows.append(r)

    feats = {k: np.asarray([rr[k] for rr in rows], dtype=np.float64) for k in rows[0].keys()} if rows else {}
    return x, feats


# Backward-compatibility alias (some call sites use singular name)
compute_windowed_feature = compute_windowed_features


def compute(
    emg_imu: Dict[str, np.ndarray],
    emg_fs_hz: float,
    win_sec: float = 1.0,
    step_sec: float = 0.25,
    vitals: Optional[Dict[str, np.ndarray]] = None,
    treat_zero_as_nan: bool = False,
) -> Results:
    """Equivalent of your pandas-based `compute` but using dict-of-arrays.

    emg_imu is expected to have keys like EMG_IMU_COLS.
    """
    ts = np.asarray(emg_imu.get("ts_ms", []), dtype=np.float64)
    raw_col = np.asarray(emg_imu.get("raw", []), dtype=np.float64)

    # EMG rows: where raw is finite
    m_emg = np.isfinite(raw_col)
    if not np.any(m_emg):
        raise ValueError("No EMG rows found.")

    raw = raw_col[m_emg]
    if treat_zero_as_nan:
        raw = raw.copy()
        raw[raw == 0] = np.nan
    raw = interpolate_nans_limited(raw, limit=100)

    t0_ms = float(ts[m_emg][0]) if np.isfinite(ts[m_emg][0]) else 0.0

    _, feats = compute_windowed_features(
        raw,
        emg_fs_hz=float(emg_fs_hz),
        emg_start_ts_ms=t0_ms,
        win_sec=float(win_sec),
        step_sec=float(step_sec),
        treat_zero_as_nan=False,
        interpolate_limit=0,
        include_classic=False,
    )

    # rebuild full signals
    n = raw.size
    emg_ts_ms = t0_ms + (np.arange(n, dtype=np.float64) / float(emg_fs_hz)) * 1000.0
    proc = preprocess_emg(raw, float(emg_fs_hz))
    env = envelope(proc, float(emg_fs_hz))

    return Results(feats=feats, emg_ts_ms=emg_ts_ms, emg_raw=raw, emg_proc=proc, emg_env=env)


# ============================
# Fatigue analysis (no pandas)
# ============================

@dataclass
class FatigueSummary:
    baseline_mdf: float
    baseline_mnf: float
    final_mdf: float
    final_mnf: float
    mdf_drop_pct: float
    mnf_drop_pct: float
    slope_mdf: float
    slope_mnf: float
    fatigue_onset_s: float


def analyze_fatigue_from_windowed(
    feats: Dict[str, np.ndarray],
    *,
    min_windows: int = 10,
    baseline_frac: float = 0.1,
    onset_after_frac: float = 0.2,
    onset_drop_frac: float = 0.85,
    rolling_win: int = 20,
) -> FatigueSummary:
    """Pandas-free fatigue analysis.

    Expects feats to have keys:
      - t_center_s (or time_s)
      - mdf
      - mnf

    Returns FatigueSummary.
    """
    if feats is None:
        raise ValueError("feats is None")

    # Flexible key names
    def _pick(*names):
        for n in names:
            if n in feats:
                return n
        return None

    t_key = _pick("t_center_s", "time_s", "time")
    mdf_key = _pick("mdf", "EMG_MDF_Hz", "EMG_MDF_Hz")
    mnf_key = _pick("mnf", "EMG_MNF_Hz", "EMG_MNF_Hz")

    if t_key is None or mdf_key is None or mnf_key is None:
        raise KeyError(f"Need time+mdf+mnf in feats. Keys={list(feats.keys())}")

    t = np.asarray(feats[t_key], dtype=np.float64)
    mdf = np.asarray(feats[mdf_key], dtype=np.float64)
    mnf = np.asarray(feats[mnf_key], dtype=np.float64)

    if t.size < int(min_windows):
        # Not enough data
        return FatigueSummary(
            baseline_mdf=float('nan'), baseline_mnf=float('nan'),
            final_mdf=float('nan'), final_mnf=float('nan'),
            mdf_drop_pct=float('nan'), mnf_drop_pct=float('nan'),
            slope_mdf=float('nan'), slope_mnf=float('nan'),
            fatigue_onset_s=float('nan'),
        )

    total_sec = float(np.nanmax(t)) if np.isfinite(np.nanmax(t)) else float(t[-1])
    bidx = max(1, int(round(t.size * float(baseline_frac))))

    baseline_mdf = float(np.nanmean(mdf[:bidx]))
    baseline_mnf = float(np.nanmean(mnf[:bidx]))
    final_mdf = float(np.nanmean(mdf[-bidx:]))
    final_mnf = float(np.nanmean(mnf[-bidx:]))

    mdf_drop_pct = 100.0 * (baseline_mdf - final_mdf) / baseline_mdf if baseline_mdf else float('nan')
    mnf_drop_pct = 100.0 * (baseline_mnf - final_mnf) / baseline_mnf if baseline_mnf else float('nan')

    def _safe_slope(x, y) -> float:
        m = np.isfinite(x) & np.isfinite(y)
        if m.sum() < 5:
            return float('nan')
        return float(np.polyfit(x[m], y[m], 1)[0])

    slope_mdf = _safe_slope(t, mdf)
    slope_mnf = _safe_slope(t, mnf)

    # onset detection: after 20% of capture, rolling MDF mean below 85% baseline
    onset_time = float('nan')
    if np.isfinite(baseline_mdf) and baseline_mdf != 0.0:
        thr = baseline_mdf * float(onset_drop_frac)
        min_t = total_sec * float(onset_after_frac)
        rolling = _nan_rolling_mean(mdf, win=int(rolling_win), min_periods=5)
        cand = np.where((t >= min_t) & (rolling <= thr))[0]
        if cand.size:
            onset_time = float(t[int(cand[0])])

    return FatigueSummary(
        baseline_mdf=baseline_mdf,
        baseline_mnf=baseline_mnf,
        final_mdf=final_mdf,
        final_mnf=final_mnf,
        mdf_drop_pct=float(mdf_drop_pct),
        mnf_drop_pct=float(mnf_drop_pct),
        slope_mdf=slope_mdf,
        slope_mnf=slope_mnf,
        fatigue_onset_s=onset_time,
    )


# ============================
# Swing-aligned + baseline helpers (pandas-free, mirrors emg_util.py)
# ============================

def compute_swing_emg_features(
    proc: np.ndarray,
    env: np.ndarray,
    emg_ts_ms: np.ndarray,
    emg_fs_hz: float,
    swings: List[Dict[str, float]],
    mdf_pad_ms: float = 300.0,
    thr_zc: float = 0.01,
    thr_ssc: float = 0.01,
    imu_ts_ms: Optional[np.ndarray] = None,
    imu_acc_dyn: Optional[np.ndarray] = None,
) -> List[Dict[str, float]]:
    """Compute per-swing EMG + IMU features (pandas-free version of emg_util.compute_swing_emg_features).

    MDF is computed on a padded window (±mdf_pad_ms) for PSD stability.
    Envelope features use the exact swing window.

    Args:
        proc:         preprocessed EMG signal
        env:          envelope signal
        emg_ts_ms:    timestamp array (ms) aligned with proc/env
        emg_fs_hz:    EMG sampling rate
        swings:       list of dicts with keys: swing_id, t_start_ms, t_peak_ms, t_end_ms, peak_z, peak_mag
        mdf_pad_ms:   padding around swing window for MDF computation
        imu_ts_ms:    optional IMU timestamp array (ms) — enables imu_mean_acc_dyn / imu_peak_acc_dyn
        imu_acc_dyn:  optional |acc_mag - 1g| array aligned with imu_ts_ms (physical units, g)

    Returns:
        list of dicts with per-swing features
    """
    if not swings:
        return []

    dt = 1.0 / float(emg_fs_hz)
    rows = []

    for r in swings:
        t0 = float(r['t_start_ms'])
        t1 = float(r['t_end_ms'])

        # Envelope features on exact swing window
        i0 = int(np.searchsorted(emg_ts_ms, t0, side='left'))
        i1 = int(np.searchsorted(emg_ts_ms, t1, side='right'))
        i0 = max(0, min(i0, len(emg_ts_ms) - 1))
        i1 = max(i0 + 1, min(i1, len(emg_ts_ms)))
        seg_env = env[i0:i1]
        emg_env_peak = float(np.nanmax(seg_env)) if seg_env.size else float('nan')
        emg_env_mean = float(np.nanmean(seg_env)) if seg_env.size else float('nan')
        emg_iemg = float(np.nansum(seg_env) * dt) if seg_env.size else float('nan')

        # Processed features on padded window (better PSD)
        t0p = t0 - float(mdf_pad_ms)
        t1p = t1 + float(mdf_pad_ms)
        j0 = int(np.searchsorted(emg_ts_ms, t0p, side='left'))
        j1 = int(np.searchsorted(emg_ts_ms, t1p, side='right'))
        j0 = max(0, min(j0, len(emg_ts_ms) - 1))
        j1 = max(j0 + 1, min(j1, len(emg_ts_ms)))
        seg = proc[j0:j1]

        mnf_val, mdf_val = mnf_mdf(seg, emg_fs_hz)

        # IMU accelerometer features (optional — requires physical-unit IMU data in g)
        imu_mean_acc_dyn = float('nan')
        imu_peak_acc_dyn = float('nan')
        if imu_ts_ms is not None and imu_acc_dyn is not None and imu_ts_ms.size > 0:
            ia0 = int(np.searchsorted(imu_ts_ms, t0, side='left'))
            ia1 = int(np.searchsorted(imu_ts_ms, t1, side='right'))
            seg_acc = imu_acc_dyn[ia0:ia1]
            if seg_acc.size > 0:
                imu_mean_acc_dyn = float(np.nanmean(seg_acc))
                imu_peak_acc_dyn = float(np.nanmax(seg_acc))

        rows.append({
            "swing_id": int(r['swing_id']),
            "t_start_ms": t0,
            "t_peak_ms": float(r['t_peak_ms']),
            "t_end_ms": t1,
            "peak_z": float(r.get('peak_z', float('nan'))),
            "peak_mag": float(r.get('peak_mag', float('nan'))),
            "emg_env_peak": emg_env_peak,
            "emg_env_mean": emg_env_mean,
            "emg_iemg": emg_iemg,
            "mav": feat_mav(seg),
            "rms": feat_rms(seg),
            "wl": feat_wl(seg),
            "zc": feat_zc(seg, thr=thr_zc),
            "ssc": feat_ssc(seg, thr=thr_ssc),
            "mnf": float(mnf_val),
            "mdf": float(mdf_val),
            "imu_mean_acc_dyn": imu_mean_acc_dyn,
            "imu_peak_acc_dyn": imu_peak_acc_dyn,
        })

    return rows


# Backward-compat alias
compute_swing_emg_features_nopandas = compute_swing_emg_features


def select_baseline_swings(
    swings: List[Dict[str, float]],
    session_t0_ms: float,
    warmup_skip_min: float = 5.0,
    warmup_skip_swings: Optional[int] = None,
    baseline_swings: int = 20,
) -> List[Dict[str, float]]:
    """Select baseline swings after warm-up (pandas-free version of emg_util.select_baseline_swings).

    Prefers swings during active (work) periods (in_active==1) when available,
    falling back to all post-warmup swings if there are not enough active-period swings.
    This prevents rest-period swings (lower MDF) from contaminating the baseline.
    """
    if not swings:
        return []
    sw = sorted(swings, key=lambda r: r['t_peak_ms'])
    cutoff = float(session_t0_ms) + float(warmup_skip_min) * 60.0 * 1000.0
    sw2 = [r for r in sw if r['t_peak_ms'] >= cutoff]
    if len(sw2) < max(3, baseline_swings // 2):
        sw2 = sw
    if warmup_skip_swings is not None and int(warmup_skip_swings) > 0:
        sw2 = sw2[int(warmup_skip_swings):]
    # Prefer active-period swings for baseline; fall back to all if not enough
    active = [r for r in sw2 if r.get('in_active', 0) == 1]
    if len(active) >= max(3, baseline_swings // 2):
        sw2 = active
    return sw2[:int(baseline_swings)]


# Backward-compat alias
select_baseline_swings_nopandas = select_baseline_swings


def compute_baseline_from_swing_features(
    swing_feats: List[Dict[str, float]],
) -> Dict[str, float]:
    """Compute robust (median) baseline from per-swing features."""
    base: Dict[str, float] = {}
    if not swing_feats:
        return base
    for k in ['mdf', 'rms', 'mav', 'wl', 'emg_env_mean', 'emg_iemg', 'imu_mean_acc_dyn']:
        vals = [r[k] for r in swing_feats if k in r and r[k] is not None and np.isfinite(r[k])]
        if vals:
            base[k] = float(np.median(vals))
    return base


# Backward-compat alias
compute_baseline_from_swing_features_nopandas = compute_baseline_from_swing_features


def apply_baseline_deltas(
    rows: List[Dict[str, float]],
    base: Dict[str, float],
    prefix: str = '',
) -> List[Dict[str, float]]:
    """Add mdf_drop_pct, rms_increase_pct, and acc_drop_pct to each row (pandas-free)."""
    out = []
    for r in rows:
        r2 = dict(r)
        if 'mdf' in r2 and 'mdf' in base and base['mdf'] and np.isfinite(base['mdf']) and r2['mdf'] is not None:
            r2[prefix + 'mdf_drop_pct'] = (base['mdf'] - float(r2['mdf'])) / base['mdf'] * 100.0
        if 'rms' in r2 and 'rms' in base and base['rms'] and np.isfinite(base['rms']) and r2['rms'] is not None:
            r2[prefix + 'rms_increase_pct'] = (float(r2['rms']) - base['rms']) / base['rms'] * 100.0
        if ('imu_mean_acc_dyn' in r2 and r2['imu_mean_acc_dyn'] is not None
                and np.isfinite(float(r2['imu_mean_acc_dyn']))
                and 'imu_mean_acc_dyn' in base and base['imu_mean_acc_dyn']
                and np.isfinite(base['imu_mean_acc_dyn'])):
            # acc_drop_pct > 0 means acc_dyn decreased (less force = fatigue)
            r2[prefix + 'acc_drop_pct'] = (
                (base['imu_mean_acc_dyn'] - float(r2['imu_mean_acc_dyn']))
                / base['imu_mean_acc_dyn'] * 100.0
            )
        out.append(r2)
    return out


# Backward-compat alias
apply_baseline_deltas_nopandas = apply_baseline_deltas


def load_decoded_emg_csv(path: Union[str, Path]) -> Dict[str, np.ndarray]:
    """Load a decoded EMG CSV (dev_us, emg_seq, env, raw, ref, ...) into arrays.

    Converts dev_us (microseconds) → ts_ms (milliseconds) for compatibility
    with emg_util.py column naming.
    """
    cols_in = ["dev_us", "env", "raw"]
    d: Dict[str, List[float]] = {c: [] for c in cols_in}
    with open(path, 'r', newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            for c in cols_in:
                d[c].append(_to_float(row.get(c)))
    dev_us = np.asarray(d["dev_us"], dtype=np.float64)
    return {
        "ts_ms": dev_us / 1000.0,
        "env": np.asarray(d["env"], dtype=np.float64),
        "raw": np.asarray(d["raw"], dtype=np.float64),
    }


def load_decoded_imu_csv(path: Union[str, Path]) -> Dict[str, np.ndarray]:
    """Load a decoded IMU CSV (dev_us, imu_seq, ax, ay, az, gx, gy, gz, ...) into arrays.

    Converts dev_us → ts_ms.
    """
    cols_in = ["dev_us", "ax", "ay", "az", "gx", "gy", "gz"]
    d: Dict[str, List[float]] = {c: [] for c in cols_in}
    with open(path, 'r', newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            for c in cols_in:
                d[c].append(_to_float(row.get(c)))
    dev_us = np.asarray(d["dev_us"], dtype=np.float64)
    return {
        "ts_ms": dev_us / 1000.0,
        "ax": np.asarray(d["ax"], dtype=np.float64),
        "ay": np.asarray(d["ay"], dtype=np.float64),
        "az": np.asarray(d["az"], dtype=np.float64),
        "gx": np.asarray(d["gx"], dtype=np.float64),
        "gy": np.asarray(d["gy"], dtype=np.float64),
        "gz": np.asarray(d["gz"], dtype=np.float64),
    }


def stitch_decoded_session(
    decoded_dir: Union[str, Path],
    emg_fs_hz: float,
    win_sec: float = 1.0,
    step_sec: float = 0.25,
    warmup_skip_min: float = 5.0,
    baseline_swings: int = 20,
    swing_z_thr: float = 5.0,
    swing_pre_ms: float = 250.0,
    swing_post_ms: float = 450.0,
    mdf_pad_ms: float = 300.0,
) -> Dict:
    """Load all decoded CSVs from a session, stitch them, run full analysis.

    Mirrors the emg_util.py CLI pipeline but pandas-free.

    Returns a dict with:
        window_feats:   list of dicts (windowed features)
        swing_windows:  list of dicts (detected swings)
        swing_feats:    list of dicts (per-swing features with baseline deltas)
        baseline:       dict (baseline values)
        summary:        dict (fatigue summary)
    """
    decoded_dir = Path(decoded_dir)

    # --- Load and stitch all EMG CSVs ---
    emg_paths = sorted(decoded_dir.glob("emg_log_*.csv"))
    if not emg_paths:
        raise FileNotFoundError(f"No emg_log_*.csv found in {decoded_dir}")

    emg_ts_list: List[float] = []
    emg_env_list: List[float] = []
    emg_raw_list: List[float] = []

    for p in emg_paths:
        d = load_decoded_emg_csv(p)
        emg_ts_list.extend(d["ts_ms"].tolist())
        emg_env_list.extend(d["env"].tolist())
        emg_raw_list.extend(d["raw"].tolist())

    emg_ts_ms = np.asarray(emg_ts_list, dtype=np.float64)
    emg_raw = np.asarray(emg_raw_list, dtype=np.float64)

    # Sort by timestamp
    order = np.argsort(emg_ts_ms, kind='mergesort')
    emg_ts_ms = emg_ts_ms[order]
    emg_raw = emg_raw[order]

    # Filter valid rows
    valid = np.isfinite(emg_raw) & np.isfinite(emg_ts_ms)
    emg_ts_ms = emg_ts_ms[valid]
    emg_raw = emg_raw[valid]

    if emg_raw.size == 0:
        raise ValueError("No valid EMG samples after stitching")

    # Rebuild uniform timestamp axis from session start
    t0_ms = float(emg_ts_ms[0])
    emg_ts_uniform = t0_ms + (np.arange(emg_raw.size, dtype=np.float64) / float(emg_fs_hz)) * 1000.0

    # Preprocess
    proc = preprocess_emg(emg_raw, float(emg_fs_hz))
    env_sig = envelope(proc, float(emg_fs_hz))

    # --- Load and stitch all IMU CSVs ---
    imu_paths = sorted(decoded_dir.glob("imu_log_*.csv"))
    imu_ts_ms = np.array([], dtype=np.float64)
    imu_ax = imu_ay = imu_az = imu_gx = imu_gy = imu_gz = np.array([], dtype=np.float64)

    if imu_paths:
        imu_ts_list: List[float] = []
        imu_ax_list: List[float] = []
        imu_ay_list: List[float] = []
        imu_az_list: List[float] = []
        imu_gx_list: List[float] = []
        imu_gy_list: List[float] = []
        imu_gz_list: List[float] = []

        for p in imu_paths:
            d = load_decoded_imu_csv(p)
            imu_ts_list.extend(d["ts_ms"].tolist())
            imu_ax_list.extend(d["ax"].tolist())
            imu_ay_list.extend(d["ay"].tolist())
            imu_az_list.extend(d["az"].tolist())
            imu_gx_list.extend(d["gx"].tolist())
            imu_gy_list.extend(d["gy"].tolist())
            imu_gz_list.extend(d["gz"].tolist())

        imu_ts_ms = np.asarray(imu_ts_list, dtype=np.float64)
        imu_order = np.argsort(imu_ts_ms, kind='mergesort')
        imu_ts_ms = imu_ts_ms[imu_order]
        imu_ax = np.asarray(imu_ax_list, dtype=np.float64)[imu_order]
        imu_ay = np.asarray(imu_ay_list, dtype=np.float64)[imu_order]
        imu_az = np.asarray(imu_az_list, dtype=np.float64)[imu_order]
        imu_gx = np.asarray(imu_gx_list, dtype=np.float64)[imu_order]
        imu_gy = np.asarray(imu_gy_list, dtype=np.float64)[imu_order]
        imu_gz = np.asarray(imu_gz_list, dtype=np.float64)[imu_order]

    # Compute |acc_mag - 1g| for per-swing IMU fatigue features (physical units, g)
    # Only meaningful when IMU CSV has physical-unit data (acc_fsr_g written per row by decoder)
    imu_acc_dyn: Optional[np.ndarray] = None
    if imu_ts_ms.size > 0:
        acc_mag = np.sqrt(imu_ax**2 + imu_ay**2 + imu_az**2)
        imu_acc_dyn = np.abs(acc_mag - 1.0)  # remove gravity component

    # --- Windowed features ---
    _, feats_dict = compute_windowed_features(
        emg_raw,
        emg_fs_hz=float(emg_fs_hz),
        emg_start_ts_ms=t0_ms,
        win_sec=float(win_sec),
        step_sec=float(step_sec),
        include_classic=True,
    )
    window_feats = []
    if feats_dict:
        keys = list(feats_dict.keys())
        n_rows = len(feats_dict[keys[0]])
        for i in range(n_rows):
            window_feats.append({k: float(feats_dict[k][i]) for k in keys})

    # --- Swing detection ---
    swing_windows: List[Dict[str, float]] = []
    swing_feats: List[Dict[str, float]] = []
    baseline: Dict[str, float] = {}

    if imu_ts_ms.size > 0:
        swing_windows = detect_swing_windows_from_imu_arrays(
            imu_ts_ms, imu_ax, imu_ay, imu_az, imu_gx, imu_gy, imu_gz,
            z_thr=float(swing_z_thr),
            pre_ms=float(swing_pre_ms),
            post_ms=float(swing_post_ms),
            use_gyro=True,
        )

        if swing_windows:
            swing_feats = compute_swing_emg_features(
                proc, env_sig, emg_ts_uniform, float(emg_fs_hz),
                swing_windows, mdf_pad_ms=float(mdf_pad_ms),
                imu_ts_ms=imu_ts_ms,
                imu_acc_dyn=imu_acc_dyn,
            )

            # Baseline selection
            base_swings = select_baseline_swings(
                swing_windows, session_t0_ms=t0_ms,
                warmup_skip_min=float(warmup_skip_min),
                baseline_swings=int(baseline_swings),
            )
            base_ids = {int(r['swing_id']) for r in base_swings}
            base_feats = [r for r in swing_feats if int(r['swing_id']) in base_ids]
            baseline = compute_baseline_from_swing_features(base_feats)

            if baseline:
                swing_feats = apply_baseline_deltas(swing_feats, baseline)
                window_feats = apply_baseline_deltas(window_feats, baseline)

    # --- Summary ---
    mdf_drops = [r.get('mdf_drop_pct') for r in swing_feats if r.get('mdf_drop_pct') is not None]
    acc_drops = [r.get('acc_drop_pct') for r in swing_feats if r.get('acc_drop_pct') is not None]
    summary = {
        "num_emg_samples": int(emg_raw.size),
        "num_imu_samples": int(imu_ts_ms.size),
        "num_windows": len(window_feats),
        "num_swings": len(swing_windows),
        "baseline_mdf": baseline.get('mdf'),
        "baseline_rms": baseline.get('rms'),
        "baseline_imu_mean_acc_dyn": baseline.get('imu_mean_acc_dyn'),
        "max_mdf_drop_pct": float(max(mdf_drops)) if mdf_drops else None,
        "mean_mdf_drop_pct": float(np.mean(mdf_drops)) if mdf_drops else None,
        "mean_acc_drop_pct": float(np.mean(acc_drops)) if acc_drops else None,
        "session_duration_s": float(emg_ts_ms[-1] - emg_ts_ms[0]) / 1000.0 if emg_ts_ms.size > 1 else 0.0,
    }

    return {
        "window_feats": window_feats,
        "swing_windows": swing_windows,
        "swing_feats": swing_feats,
        "baseline": baseline,
        "summary": summary,
    }


# ============================
# Real-time per-session fatigue monitor
# ============================
# FatigueMonitor lives in fatigue_monitor.py (canonical, pandas-free, Android-compatible).
# Imported here so existing callers (analysis_bridge.py, etc.) continue to work unchanged.
# Android deployment: fatigue_monitor.py must be present alongside this file.
from fatigue_monitor import FatigueMonitor, preprocess_emg  # noqa: F401  (re-export)


# ============================
# Optional IMU swing detection (no pandas)
# ============================

def robust_zscore(x: np.ndarray, baseline_mask: np.ndarray, eps: float = 1e-9) -> Tuple[np.ndarray, float, float]:
    x = np.asarray(x, dtype=np.float64)
    baseline_mask = np.asarray(baseline_mask, dtype=bool)
    xb = x[baseline_mask]
    xb = xb[np.isfinite(xb)]
    if xb.size < 10:
        xb = x[np.isfinite(x)]
    med = float(np.median(xb)) if xb.size else float(np.nanmedian(x))
    mad = float(np.median(np.abs(xb - med))) if xb.size else float(np.nanmedian(np.abs(x - med)))
    scale = 1.4826 * mad
    if (not np.isfinite(scale)) or scale < eps:
        scale = float(np.nanstd(xb)) if xb.size else float(np.nanstd(x))
    if (not np.isfinite(scale)) or scale < eps:
        scale = 1.0
    z = (x - med) / (scale + eps)
    return z, med, scale


def build_time_axis_from_ts(ts_ms: np.ndarray, fs_fallback: float = 200.0) -> np.ndarray:
    """Equivalent of build_time_axis_from_ts but for plain ts array."""
    ts = np.asarray(ts_ms, dtype=np.int64)
    if ts.size == 0:
        return np.array([], dtype=np.float64)

    uniq, idx_start, counts = np.unique(ts, return_index=True, return_counts=True)
    dt_u = np.diff(uniq.astype(np.float64)) / 1000.0
    dt_u_pos = dt_u[dt_u > 0]
    dt_med = float(np.median(dt_u_pos)) if dt_u_pos.size else 1.0 / float(fs_fallback)

    t = np.empty(ts.size, dtype=np.float64)
    base = 0.0
    for k, (start, cnt) in enumerate(zip(idx_start, counts)):
        if k < dt_u.size:
            span = float(dt_u[k])
            if span <= 0:
                span = dt_med
        else:
            span = dt_med

        if cnt == 1:
            t[start] = base
        else:
            offs = np.linspace(0.0, span, cnt, endpoint=False)
            t[start:start+cnt] = base + offs
        base += span

    t -= t[0]
    eps = 1e-9
    for i in range(1, t.size):
        if t[i] <= t[i-1]:
            t[i] = t[i-1] + eps
    return t


def detect_swing_windows_from_imu_arrays(
    ts_ms: np.ndarray,
    ax: np.ndarray,
    ay: np.ndarray,
    az: np.ndarray,
    gx: np.ndarray,
    gy: np.ndarray,
    gz: np.ndarray,
    *,
    baseline_s: float = 2.0,
    z_thr: float = 5.0,
    min_consec: int = 3,
    min_gap_ms: float = 300.0,
    pre_ms: float = 250.0,
    post_ms: float = 450.0,
    use_gyro: bool = True,
) -> List[Dict[str, float]]:
    """Detect swing windows from IMU magnitude using robust z-score.

    Returns list of dict rows: swing_id, t_start_ms, t_peak_ms, t_end_ms, peak_z, peak_mag
    """
    ts_ms = np.asarray(ts_ms, dtype=np.float64)
    if ts_ms.size == 0:
        return []

    # Build usable time axis even when ts repeats
    t_rel_s = build_time_axis_from_ts(ts_ms.astype(np.int64), fs_fallback=200.0)
    t0_ms = float(ts_ms[0])
    t_ms = t0_ms + 1000.0 * t_rel_s

    if use_gyro:
        mag = np.sqrt(np.asarray(gx, dtype=np.float64)**2 + np.asarray(gy, dtype=np.float64)**2 + np.asarray(gz, dtype=np.float64)**2)
    else:
        mag = np.sqrt(np.asarray(ax, dtype=np.float64)**2 + np.asarray(ay, dtype=np.float64)**2 + np.asarray(az, dtype=np.float64)**2)

    total_s = float(np.nanmax(t_rel_s)) if t_rel_s.size else float(baseline_s)
    b_s = min(float(baseline_s), max(0.2 * total_s, 0.5))
    z, _, _ = robust_zscore(mag, t_rel_s <= b_s)

    active = z > float(z_thr)
    if int(min_consec) > 1:
        run = 0
        flags = np.zeros_like(active, dtype=bool)
        for i, v in enumerate(active):
            run = run + 1 if v else 0
            flags[i] = run >= int(min_consec)
        active = flags

    peaks = []
    i = 0
    while i < active.size:
        if not active[i]:
            i += 1
            continue
        j = i
        while j < active.size and active[j]:
            j += 1
        seg = slice(i, j)
        k = int(i + np.nanargmax(z[seg]))
        peaks.append(k)
        i = j

    if not peaks:
        return []

    peaks_sorted = sorted(peaks, key=lambda k: t_ms[k])
    kept = []
    last_t = -1e18
    for k in peaks_sorted:
        if (t_ms[k] - last_t) >= float(min_gap_ms):
            kept.append(k)
            last_t = t_ms[k]
        else:
            # keep the stronger one
            if kept and z[k] > z[kept[-1]]:
                kept[-1] = k
                last_t = t_ms[k]

    rows = []
    for si, k in enumerate(kept):
        tp = float(t_ms[k])
        rows.append({
            "swing_id": int(si),
            "t_start_ms": tp - float(pre_ms),
            "t_peak_ms": tp,
            "t_end_ms": tp + float(post_ms),
            "peak_z": float(z[k]),
            "peak_mag": float(mag[k]),
        })

    # Merge overlapping windows
    rows.sort(key=lambda r: r["t_start_ms"])
    merged = []
    cur = rows[0].copy()
    for r in rows[1:]:
        if r["t_start_ms"] <= cur["t_end_ms"]:
            cur["t_end_ms"] = max(cur["t_end_ms"], r["t_end_ms"])
            if r["peak_z"] > cur["peak_z"]:
                cur.update({"t_peak_ms": r["t_peak_ms"], "peak_z": r["peak_z"], "peak_mag": r["peak_mag"]})
        else:
            merged.append(cur)
            cur = r.copy()
    merged.append(cur)

    # Reassign IDs
    for i, r in enumerate(merged):
        r["swing_id"] = int(i)
    return merged


# Alias: Windows emg_util.py uses detect_swings_from_imu (DataFrame-based);
# Android uses detect_swing_windows_from_imu_arrays (array-based).
# This alias lets callers use either name on Android.
detect_swings_from_imu = detect_swing_windows_from_imu_arrays
