# Android Python Layer — Changelog

All notable changes to `btLeApp/app/src/main/python/` are documented here.  
Format: newest entry at the top.

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
