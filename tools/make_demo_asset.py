#!/usr/bin/env python3
"""make_demo_asset.py

Converts the log0305 golden session into two compact binary assets for bundling
in the Android app:

  demo_emg.bin  — EMG samples (2000 Hz), ~4.8 MB for 5 min
  demo_imu.bin  — IMU samples (200 Hz),  ~0.96 MB for 5 min

Binary format — demo_emg.bin:
  magic      : 4 bytes  = b'DEMG'
  n_samples  : int32 LE
  per sample : int32 t_ms, int16 raw, int16 env   (8 bytes each)

Binary format — demo_imu.bin:
  magic      : 4 bytes  = b'DIMU'
  n_samples  : int32 LE
  per sample : int32 t_ms, int16 ax, int16 ay, int16 az,
                           int16 gx, int16 gy, int16 gz  (16 bytes each)

IMU fixed scale (applied before int16 clamp):
  accel: stored as round(value_g * 1000)  → resolution 0.001 g, range ±32.767 g
  gyro:  stored as round(value_dps * 10)  → resolution 0.1 dps, range ±3276.7 dps

t_ms: milliseconds since the 20-min skip cutoff (re-zeroed), stored as int32.

Key decisions:
- Skips first 20 min: electrode settling makes EMG/MDF invalid before this point
  (canonical analysis uses config_w20 / out0305_w20 for this same reason).
- Takes next 5 min of clean play: 30+ baseline swings, visible fatigue trend.

Usage (from emg_util/ or with emg_util on PYTHONPATH):
  python ../btLeApp/tools/make_demo_asset.py

Outputs demo_emg.bin and demo_imu.bin in tools/ directory.
Copy both to app/src/main/assets/.
"""

import os
import struct
import sys

_HERE   = os.path.dirname(os.path.abspath(__file__))
_EMGUTIL = os.path.abspath(os.path.join(_HERE, "..", "..", "emg_util"))
if _EMGUTIL not in sys.path:
    sys.path.insert(0, _EMGUTIL)

import numpy as np
from capture_loader import discover_sessions, load_session_stitched

# ── Configuration ──────────────────────────────────────────────────────────────
LOG0305_DIR  = os.path.join(_EMGUTIL, "log0305")
OUT_EMG      = os.path.join(_HERE, "demo_emg.bin")
OUT_IMU      = os.path.join(_HERE, "demo_imu.bin")

SKIP_MIN     = 20.0   # skip first 20 min — electrode settling
WINDOW_MIN   =  5.0   # 5 min of clean play after the skip

SKIP_US      = int(SKIP_MIN   * 60 * 1e6)
WINDOW_US    = int(WINDOW_MIN * 60 * 1e6)

# IMU fixed scale factors
ACCEL_SCALE  = 1000   # g  → int16 (milli-g)
GYRO_SCALE   =   10   # dps → int16 (0.1 dps)
# ──────────────────────────────────────────────────────────────────────────────


def clamp_int16(arr):
    return np.clip(np.round(arr).astype(np.int64), -32768, 32767).astype(np.int16)


def write_emg_bin(path, t_ms, raw, env):
    n = len(t_ms)
    with open(path, 'wb') as f:
        f.write(b'DEMG')
        f.write(struct.pack('<i', n))
        # pack as a flat array: t_ms(i32), raw(i16), env(i16) per sample
        t_arr   = np.clip(t_ms, 0, 2**31 - 1).astype(np.int32)
        raw_arr = clamp_int16(raw)
        env_arr = clamp_int16(env)
        # interleave: each record = t(4) + raw(2) + env(2)
        buf = np.empty(n * 4, dtype=np.int16)
        # reinterpret t_arr (int32) as pairs of int16
        buf[0::4] = t_arr.view(np.int16)[0::2]   # t low word
        buf[1::4] = t_arr.view(np.int16)[1::2]   # t high word
        buf[2::4] = raw_arr
        buf[3::4] = env_arr
        f.write(buf.tobytes())
    return os.path.getsize(path)


def write_imu_bin(path, t_ms, ax, ay, az, gx, gy, gz):
    n = len(t_ms)
    with open(path, 'wb') as f:
        f.write(b'DIMU')
        f.write(struct.pack('<i', n))
        # pack as flat array: t_ms(i32), ax,ay,az,gx,gy,gz (i16 each) per sample
        t_arr  = np.clip(t_ms, 0, 2**31 - 1).astype(np.int32)
        ax_arr = clamp_int16(ax * ACCEL_SCALE)
        ay_arr = clamp_int16(ay * ACCEL_SCALE)
        az_arr = clamp_int16(az * ACCEL_SCALE)
        gx_arr = clamp_int16(gx * GYRO_SCALE)
        gy_arr = clamp_int16(gy * GYRO_SCALE)
        gz_arr = clamp_int16(gz * GYRO_SCALE)
        buf = np.empty(n * 8, dtype=np.int16)
        buf[0::8] = t_arr.view(np.int16)[0::2]
        buf[1::8] = t_arr.view(np.int16)[1::2]
        buf[2::8] = ax_arr
        buf[3::8] = ay_arr
        buf[4::8] = az_arr
        buf[5::8] = gx_arr
        buf[6::8] = gy_arr
        buf[7::8] = gz_arr
        f.write(buf.tobytes())
    return os.path.getsize(path)


def main():
    print(f"Loading log0305 from: {LOG0305_DIR}")
    sessions = discover_sessions(LOG0305_DIR)
    if not sessions:
        sys.exit(f"ERROR: no sessions found in {LOG0305_DIR}")

    emg_df, imu_df, _, _ = load_session_stitched(sessions, use_physical_units=True)
    print(f"Loaded  EMG rows: {len(emg_df):,}  IMU rows: {len(imu_df):,}")
    print(f"Session duration: {emg_df['abs_us'].max() / 60e6:.1f} min")

    # ── Trim window ───────────────────────────────────────────────────────────
    emg_win = emg_df[
        (emg_df['abs_us'] >= SKIP_US) &
        (emg_df['abs_us'] <  SKIP_US + WINDOW_US)
    ]
    imu_win = imu_df[
        (imu_df['abs_us'] >= SKIP_US) &
        (imu_df['abs_us'] <  SKIP_US + WINDOW_US)
    ]
    print(f"After {SKIP_MIN:.0f}-min skip + {WINDOW_MIN:.0f}-min window:")
    print(f"  EMG rows: {len(emg_win):,}  IMU rows: {len(imu_win):,}")

    if len(emg_win) == 0 or len(imu_win) == 0:
        sys.exit("ERROR: window is empty — check SKIP_MIN/WINDOW_MIN vs session duration")

    # ── Compute t_ms re-zeroed from the skip cutoff ───────────────────────────
    emg_t_ms = (emg_win['abs_us'].values - SKIP_US) / 1000.0
    imu_t_ms = (imu_win['abs_us'].values - SKIP_US) / 1000.0

    # ── Sanity checks ─────────────────────────────────────────────────────────
    raw_vals = emg_win['raw'].values.astype(float)
    print(f"\nSanity checks:")
    print(f"  Median RAW  (expect ~1843):     {np.median(raw_vals):.0f}")
    print(f"  RAW range:                       {raw_vals.min():.0f} – {raw_vals.max():.0f}")

    imu_cols = ['ax', 'ay', 'az', 'gx', 'gy', 'gz']
    missing = [c for c in imu_cols if c not in imu_win.columns]
    if missing:
        sys.exit(f"ERROR: missing IMU columns: {missing}\nAvailable: {list(imu_win.columns)}")

    gmag = np.sqrt(imu_win['gx'].values**2 + imu_win['gy'].values**2 + imu_win['gz'].values**2)
    print(f"  Max gyro magnitude (dps):        {gmag.max():.1f}  (expect >300 for swings)")
    n_swing_samples = int((gmag > 300).sum())
    print(f"  Samples above 300 dps threshold: {n_swing_samples:,}")

    # ── Write EMG binary ──────────────────────────────────────────────────────
    emg_bytes = write_emg_bin(
        OUT_EMG,
        t_ms = emg_t_ms,
        raw  = raw_vals,
        env  = emg_win['env'].values.astype(float),
    )
    print(f"\nWritten: {OUT_EMG}  ({emg_bytes/1e6:.2f} MB)  [{len(emg_t_ms):,} samples]")

    # ── Write IMU binary ──────────────────────────────────────────────────────
    imu_bytes = write_imu_bin(
        OUT_IMU,
        t_ms = imu_t_ms,
        ax   = imu_win['ax'].values,
        ay   = imu_win['ay'].values,
        az   = imu_win['az'].values,
        gx   = imu_win['gx'].values,
        gy   = imu_win['gy'].values,
        gz   = imu_win['gz'].values,
    )
    print(f"Written: {OUT_IMU}  ({imu_bytes/1e6:.2f} MB)  [{len(imu_t_ms):,} samples]")
    print(f"\nTotal asset size: {(emg_bytes + imu_bytes)/1e6:.2f} MB")
    print("Next: copy both files to app/src/main/assets/")


if __name__ == '__main__':
    main()
