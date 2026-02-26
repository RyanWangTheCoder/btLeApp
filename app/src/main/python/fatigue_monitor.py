# -*- coding: utf-8 -*-
"""fatigue_monitor.py — Canonical FatigueMonitor implementation.

Self-contained: zero pandas / matplotlib dependency.
Works on PC (full SciPy) and Android (Chaquopy, SciPy optional — numpy FFT fallback).

Imported by:
  emg_util/emg_util.py          (PC offline analysis + sensordata_plotter.py)
  emg_util/emg_util_nopandas.py (Android via Chaquopy)

DO NOT duplicate FatigueMonitor in those files — import from here.

Android deployment: copy this file alongside emg_util_nopandas.py into
  btLeApp/app/src/main/python/
"""

from __future__ import annotations

from typing import List, Optional, Tuple
import numpy as np

try:
    from scipy.signal import butter, filtfilt, iirnotch, detrend, welch
    _HAVE_SCIPY = True
except Exception:
    butter = filtfilt = iirnotch = detrend = welch = None  # type: ignore[assignment]
    _HAVE_SCIPY = False


# ---------------------------------------------------------------------------
# Minimal signal helpers (scipy-optional, numpy fallback)
# Private to this module — prefixed with _ to avoid polluting namespace.
# ---------------------------------------------------------------------------

def _filtfilt_safe(b, a, x: np.ndarray) -> np.ndarray:
    x = np.asarray(x, dtype=np.float64)
    n = x.size
    if n == 0:
        return x
    if not _HAVE_SCIPY:
        return x
    padlen = 3 * (max(len(a), len(b)) - 1)
    if n <= padlen:
        return filtfilt(b, a, x, method='gust')
    return filtfilt(b, a, x)


def preprocess_emg(x: np.ndarray, fs: float) -> np.ndarray:
    """Detrend + 4th-order Butterworth bandpass (50–450 Hz) + 60/120 Hz notch filters.

    Public API — single source of truth for EMG preprocessing across PC and Android.
    Imported by emg_util.py so the filter parameters live in exactly one place.

    Low cutoff: 50 Hz (matches MyoWare hardware bandpass, suppresses motion artifact).
    High cutoff: 450 Hz (below Nyquist for 2000 Hz sampling).
    Notch: 60 Hz + 120 Hz (power-line interference).

    Falls back to DC removal only when SciPy is unavailable (Android without SciPy).
    """
    x = np.asarray(x, dtype=np.float64)
    if x.size == 0:
        return x
    if _HAVE_SCIPY:
        y = detrend(x, type='constant')
        nyq = fs / 2.0
        high = min(450.0, 0.95 * nyq)
        if high <= 50.0:
            return y
        b, a = butter(4, [50.0 / nyq, high / nyq], btype='bandpass')
        y = _filtfilt_safe(b, a, y)
        for f0 in (60.0, 120.0):
            if f0 < nyq - 1:
                b, a = iirnotch(w0=f0 / nyq, Q=30.0)
                y = _filtfilt_safe(b, a, y)
        return y
    # Numpy-only fallback: simple DC removal
    return x - np.nanmean(x)


# Private alias kept for internal use within this module
_preprocess_emg = preprocess_emg


def _mnf_mdf(x: np.ndarray, fs: float) -> Tuple[float, float]:
    """Compute Mean Frequency (MNF) and Median Frequency (MDF) via Welch PSD.

    Falls back to plain FFT power spectrum when SciPy is unavailable (Android).
    Returns (mnf_hz, mdf_hz).
    """
    x = np.asarray(x, dtype=np.float64)
    if x.size < 2:
        return float('nan'), float('nan')

    if _HAVE_SCIPY:
        f, Pxx = welch(x, fs=float(fs), nperseg=min(1024, len(x)),
                       noverlap=min(512, len(x) // 2), window='hann', scaling='density')
        if Pxx.size == 0:
            return float('nan'), float('nan')
        Pxx = np.maximum(Pxx, 1e-20)
        s = float(np.sum(Pxx))
        mnf_val = float(np.sum(f * Pxx) / s)
        cdf = np.cumsum(Pxx)
        mdf_val = float(f[min(int(np.searchsorted(cdf, 0.5 * s)), len(f) - 1)])
        return mnf_val, mdf_val

    # Numpy-only fallback: FFT power spectrum
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


# ---------------------------------------------------------------------------
# FatigueMonitor
# ---------------------------------------------------------------------------

class FatigueMonitor:
    """Stateful per-session fatigue tracker for real-time use.

    Works on PC (sensordata_plotter.py) and Android (analysis_bridge.py via Chaquopy).
    Zero pandas / matplotlib dependency.

    Usage:
        monitor = FatigueMonitor(fs=2000.0, warmup_skip_min=5.0, baseline_swings=20)
        monitor.reset()
        result = monitor.push_swing(emg_raw_list, t_peak_ms, imu_peak_mag)
        # result is a plain dict — JSON-serializable

    Fatigue levels:
        "warming_up"  — before baseline is established
        "fresh"       — fatigue_pct < 33%  (green)
        "moderate"    — 33% <= fatigue_pct < 67%  (yellow)
        "high"        — fatigue_pct >= 67%  (red)

    Baseline is established from the first `baseline_swings` swings
    that occur after `warmup_skip_min` minutes.
    """

    def __init__(
        self,
        fs: float = 2000.0,
        warmup_skip_min: float = 5.0,
        baseline_swings: int = 20,
        mdf_warn_pct: float = 12.0,
        mdf_high_pct: float = 20.0,
    ):
        self._fs = float(fs)
        self._warmup_skip_ms = warmup_skip_min * 60.0 * 1000.0
        self._baseline_swings = int(baseline_swings)
        self._mdf_warn_pct = float(mdf_warn_pct)   # yellow threshold
        self._mdf_high_pct = float(mdf_high_pct)   # red threshold

        # Session state (reset on each new session)
        self._swing_id: int = 0
        self._t_session_start_ms: Optional[float] = None
        self._baseline_mdf: Optional[float] = None
        self._baseline_rms: Optional[float] = None
        self._baseline_samples: List[Tuple[float, float]] = []  # (mdf, rms)
        self._history: List[dict] = []

    def reset(self) -> None:
        """Call at the start of each new session."""
        self._swing_id = 0
        self._t_session_start_ms = None
        self._baseline_mdf = None
        self._baseline_rms = None
        self._baseline_samples = []
        self._history = []

    def set_fs(self, fs: float) -> None:
        """Update the EMG sampling rate (call when emg_meta arrives with a new sr_hz)."""
        self._fs = float(fs)

    def push_swing(
        self,
        emg_raw: List[float],
        t_peak_ms: float,
        imu_peak_mag: float,
    ) -> dict:
        """Process one swing window and return fatigue metrics.

        Args:
            emg_raw:      raw EMG samples at self._fs Hz
                          (2600 samples = 1300ms at 2000Hz, includes +/-300ms MDF padding)
            t_peak_ms:    absolute time of swing peak in ms (from session start)
            imu_peak_mag: IMU gyro magnitude at swing peak (dps)

        Returns:
            dict with keys:
                swing_id                int
                t_peak_ms               float
                mdf                     float  (Hz, None until first valid swing)
                rms                     float
                env_mean                float
                imu_peak_mag            float
                mdf_drop_pct            float  (None until baseline established)
                rms_increase_pct        float  (None until baseline established)
                fatigue_pct             float  (0-100, None until baseline established)
                fatigue_level           str    ("warming_up"|"fresh"|"moderate"|"high")
                warning                 bool
                baseline_ready          bool
                baseline_swings_collected int
                baseline_mdf            float  (None until baseline established)
        """
        t_peak_ms = float(t_peak_ms)
        imu_peak_mag = float(imu_peak_mag)

        # Record session start time on first swing
        if self._t_session_start_ms is None:
            self._t_session_start_ms = t_peak_ms

        # Compute EMG features
        raw = np.asarray(emg_raw, dtype=np.float64)
        mdf_val = float('nan')
        rms_val = float('nan')
        env_mean = float('nan')

        if raw.size >= 32:
            proc = _preprocess_emg(raw, self._fs)
            _, mdf_val = _mnf_mdf(proc, self._fs)
            rms_val = float(np.sqrt(np.nanmean(proc * proc)))
            env_mean = float(np.nanmean(np.abs(raw)))

        # Baseline collection: after warmup, first N valid swings
        t_elapsed_ms = t_peak_ms - self._t_session_start_ms
        in_warmup = t_elapsed_ms < self._warmup_skip_ms
        baseline_ready = self._baseline_mdf is not None

        if not baseline_ready and not in_warmup:
            if np.isfinite(mdf_val) and np.isfinite(rms_val):
                self._baseline_samples.append((mdf_val, rms_val))
                if len(self._baseline_samples) >= self._baseline_swings:
                    mdfs = [s[0] for s in self._baseline_samples]
                    rmss = [s[1] for s in self._baseline_samples]
                    self._baseline_mdf = float(np.median(mdfs))
                    self._baseline_rms = float(np.median(rmss))

        # Fatigue deltas (only after baseline is established)
        mdf_drop_pct = float('nan')
        rms_increase_pct = float('nan')
        fatigue_pct = float('nan')
        warning = False

        if baseline_ready and np.isfinite(mdf_val):
            if self._baseline_mdf and self._baseline_mdf > 0:
                mdf_drop_pct = (self._baseline_mdf - mdf_val) / self._baseline_mdf * 100.0
            if self._baseline_rms and self._baseline_rms > 0 and np.isfinite(rms_val):
                rms_increase_pct = (rms_val - self._baseline_rms) / self._baseline_rms * 100.0

            # Fatigue % based on MDF drop (clamped 0-100)
            if np.isfinite(mdf_drop_pct):
                fatigue_pct = float(np.clip(mdf_drop_pct / self._mdf_high_pct * 100.0, 0.0, 100.0))
                warning = mdf_drop_pct >= self._mdf_warn_pct

        # Fatigue level label
        if in_warmup or not baseline_ready:
            level = 'warming_up'
        elif not np.isfinite(fatigue_pct):
            level = 'unknown'
        elif fatigue_pct < 33.0:
            level = 'fresh'
        elif fatigue_pct < 67.0:
            level = 'moderate'
        else:
            level = 'high'

        result = {
            'swing_id': int(self._swing_id),
            't_peak_ms': t_peak_ms,
            'mdf': round(mdf_val, 2) if np.isfinite(mdf_val) else None,
            'rms': round(rms_val, 4) if np.isfinite(rms_val) else None,
            'env_mean': round(env_mean, 2) if np.isfinite(env_mean) else None,
            'imu_peak_mag': round(imu_peak_mag, 1),
            'mdf_drop_pct': round(mdf_drop_pct, 2) if np.isfinite(mdf_drop_pct) else None,
            'rms_increase_pct': round(rms_increase_pct, 2) if np.isfinite(rms_increase_pct) else None,
            'fatigue_pct': round(fatigue_pct, 1) if np.isfinite(fatigue_pct) else None,
            'fatigue_level': level,
            'warning': bool(warning),
            'baseline_ready': bool(baseline_ready),
            'baseline_swings_collected': len(self._baseline_samples),
            'baseline_mdf': round(self._baseline_mdf, 2) if self._baseline_mdf is not None else None,
        }

        self._history.append(result)
        self._swing_id += 1
        return result

    def get_summary(self) -> dict:
        """Return session summary dict (JSON-serializable)."""
        n = len(self._history)
        if n == 0:
            return {'num_swings': 0, 'baseline_ready': False}

        mdfs  = [r['mdf']          for r in self._history if r['mdf']          is not None]
        drops = [r['mdf_drop_pct'] for r in self._history if r['mdf_drop_pct'] is not None]
        warnings = sum(1 for r in self._history if r['warning'])

        return {
            'num_swings': n,
            'baseline_ready': bool(self._baseline_mdf is not None),
            'baseline_mdf': round(self._baseline_mdf, 2) if self._baseline_mdf is not None else None,
            'baseline_rms': round(self._baseline_rms, 4) if self._baseline_rms is not None else None,
            'latest_mdf': round(mdfs[-1], 2) if mdfs else None,
            'latest_mdf_drop_pct': round(drops[-1], 2) if drops else None,
            'max_mdf_drop_pct': round(max(drops), 2) if drops else None,
            'warning_swings': warnings,
            'fatigue_level': self._history[-1]['fatigue_level'] if self._history else 'unknown',
        }
