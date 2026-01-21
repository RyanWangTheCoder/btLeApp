# -*- coding: utf-8 -*-
"""emg_util_nopandas.py

A pandas-free subset of your `emg_util.py`, designed to run under Chaquopy on Android.

What this module keeps (core computation):
- EMG preprocessing: detrend + bandpass + notch
- EMG envelope: rectify + lowpass
- Windowed features: RMS, ENV mean/peak, MNF, MDF, (optional MAV/WL/ZC/SSC)
- Simple fatigue analysis (MDF/MNF drop + optional onset detection)
- Optional IMU swing detection (robust z-score on gyro/acc magnitude)

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


def preprocess_emg(x: np.ndarray, fs: float) -> np.ndarray:
    x = np.asarray(x, dtype=np.float64)
    if x.size == 0:
        return x

    if _HAVE_SCIPY:
        y = detrend(x, type="constant")
        nyq = fs / 2.0
        high = min(450.0, 0.95 * nyq)
        if high <= 20.0:
            return y
        b, a = butter(4, [20.0 / nyq, high / nyq], btype="bandpass")
        y = filtfilt_safe(b, a, y)
        for f0 in (60.0, 120.0):
            if f0 < nyq - 1:
                b, a = iirnotch(w0=f0 / nyq, Q=30.0)
                y = filtfilt_safe(b, a, y)
        return y

    # Numpy-only fallback: simple DC removal
    return x - np.nanmean(x)


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
