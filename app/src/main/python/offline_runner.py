# -*- coding: utf-8 -*-
"""offline_runner.py

Android equivalent of emg_util_yaml_runner.py on Windows.

Offline post-capture pipeline:
  1. discover_sessions(captures_dir)
       Groups all frames_YYYYMMDD_HHMMSS.bin files into tennis sessions
       using a 30-minute gap threshold (mirrors Windows "one folder per session").

  2. process_tennis_session(captures_dir, session_id)
       Decodes and stitches all BLE captures belonging to one tennis session.
       Android equivalent of emg_util_yaml_runner.analyze_tennis_session().

  3. process_session(bin_path)
       Decodes ONE frames_*.bin file → rotated CSVs (single-capture use).

Storage layout (Android — flat folder, all captures in one place):
  <captures_dir>/
    frames_20260225_140000.bin   ← BLE connection 1 (session A)
    frames_20260225_142500.bin   ← BLE connection 2 (25 min gap → same session A)
    frames_20260225_151000.bin   ← BLE connection 3 (8 min gap → same session A)
    frames_20260225_220000.bin   ← BLE connection 4 (4h gap → NEW session B)
    frames_20260226_090000.bin   ← BLE connection 5 (11h gap → NEW session C)

Session boundary rule: gap >= SESSION_GAP_MIN (30 min) → new session.
This mirrors Windows where the user puts each session in a separate folder.

Output CSVs are schema-compatible with emg_util_yaml_runner.py so the decoded
folder can be transferred to PC and analyzed without re-decoding:
    python emg_util_yaml_runner.py --log-dir <captures_dir>

Java usage:
    PyObject mod = Python.getInstance().getModule("offline_runner");
    String sessionsJson = mod.callAttr("discover_sessions", capturesDir).toString();
    String resultJson   = mod.callAttr("process_tennis_session",
                              capturesDir, sessionId).toString();
"""

import csv as _csv
import json
import shutil
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np

from btcentral_offline_decode import (
    FrameParser, DeviceClockExtender,
    decode_emg, decode_imu, decode_vitals,
    SegmentCsv, _read_len_prefixed_frames,
    TLV_EMG_BATCH, TLV_IMU_BATCH, TLV_VITALS, TLV_HOST_MS,
)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Captures separated by >= this many minutes are considered separate sessions.
# Mirrors Windows "one folder per session" convention.
SESSION_GAP_MIN: float = 30.0

# Filename timestamp format: frames_YYYYMMDD_HHMMSS.bin
_BIN_FMT = "%Y%m%d_%H%M%S"


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _parse_bin_dt(path: Path) -> Optional[datetime]:
    """Extract datetime from frames_YYYYMMDD_HHMMSS.bin filename."""
    stem = path.stem  # e.g. "frames_20260225_140000"
    parts = stem.split("_", 1)
    if len(parts) == 2:
        try:
            return datetime.strptime(parts[1], _BIN_FMT)
        except ValueError:
            pass
    return None


def _decode_bin_direct(bin_path: Path, out_dir: Path,
                        session_id: str, rotate_min: float) -> Dict:
    """Decode a named frames_YYYYMMDD_HHMMSS.bin to rotated CSVs.

    Produces the same output schema as btcentral_offline_decode.py /
    btCentral.py on Windows:
      emg_log_<session_id>_<idx>.csv
      imu_log_<session_id>_<idx>.csv
      vitals_log.csv
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    rotate_us = int(float(rotate_min) * 60.0 * 1_000_000.0)

    emg_writers: Dict = {}
    imu_writers: Dict = {}

    vitals_fp = open(out_dir / "vitals_log.csv", "w", newline="", encoding="utf-8")
    vitals_w = _csv.writer(vitals_fp)
    vitals_w.writerow(["dev_us_frame", "dev_us_computed", "spo2", "hr_bpm",
                        "age_ms", "flags", "brightness", "frame_seq", "host_rx_ms"])

    parser = FrameParser()
    clock = DeviceClockExtender()
    t0_dev_us: Optional[int] = None

    stats: Dict = {
        "session_id": session_id, "decoded_dir": str(out_dir),
        "rotate_min": float(rotate_min), "frames_total": 0, "frames_ok": 0,
        "crc_errors": 0, "emg_samples": 0, "imu_samples": 0,
        "vitals_records": 0, "emg_segments": 0, "imu_segments": 0, "outputs": [],
    }

    def _get_emg_w(seg_idx: int, sr_hz: int, ch: int) -> SegmentCsv:
        if seg_idx not in emg_writers:
            w = SegmentCsv(
                outdir=out_dir, base="emg_log", session_id=session_id,
                header=["dev_us", "emg_seq", "env", "raw", "ref", "frame_seq", "host_rx_ms"],
                meta={"session_id": session_id, "segment_index": seg_idx,
                      "emg_rate_hz": sr_hz, "channels": ch,
                      "rotation_minutes": rotate_min, "host_rx_ms": 0},
                idx=seg_idx,
                # open_id = session_id so filename matches Windows format:
                # emg_log_<session_id>_<open_id>_<idx>.csv
                # required by capture_loader.discover_sessions() on PC.
                open_id=session_id,
            )
            emg_writers[seg_idx] = w
            stats["emg_segments"] = len(emg_writers)
        return emg_writers[seg_idx]

    def _get_imu_w(seg_idx: int, sr_hz: int,
                   acc_fsr_g: Optional[int], gyro_fsr_dps: Optional[int]) -> SegmentCsv:
        if seg_idx not in imu_writers:
            w = SegmentCsv(
                outdir=out_dir, base="imu_log", session_id=session_id,
                header=["dev_us", "imu_seq", "ax", "ay", "az", "gx", "gy", "gz",
                        "acc_fsr_g", "gyro_fsr_dps", "frame_seq", "host_rx_ms"],
                meta={"session_id": session_id, "segment_index": seg_idx,
                      "imu_rate_hz": sr_hz, "imu_acc_fsr_g": acc_fsr_g,
                      "imu_gyro_fsr_dps": gyro_fsr_dps,
                      "rotation_minutes": rotate_min, "host_rx_ms": 0},
                idx=seg_idx,
                open_id=session_id,
            )
            imu_writers[seg_idx] = w
            stats["imu_segments"] = len(imu_writers)
        return imu_writers[seg_idx]

    for raw in _read_len_prefixed_frames(bin_path):
        stats["frames_total"] += 1
        for ver, flags, frame_seq, t_us_u32, tlvs in parser.feed(raw):
            if tlvs and tlvs[0][0] == 0xFF:
                stats["crc_errors"] += 1
                continue
            stats["frames_ok"] += 1
            dev_frame_us = clock.extend(t_us_u32)
            if t0_dev_us is None:
                t0_dev_us = dev_frame_us

            # Pre-scan TLVs for TLV_HOST_MS (0x04).
            # Android Java writes System.currentTimeMillis() per BLE notification.
            # Falls back to 0 for legacy frames without this TLV.
            frame_host_ms: int = 0
            for tlv_t, tlv_v in tlvs:
                if tlv_t == TLV_HOST_MS and len(tlv_v) >= 8:
                    import struct as _struct  # noqa: PLC0415
                    frame_host_ms = int(_struct.unpack_from("<Q", tlv_v, 0)[0])
                    break

            for t, v in tlvs:
                if t == TLV_EMG_BATCH:
                    seq0, t0_off, sr_hz, ch, samples = decode_emg(v)
                    t0_us = dev_frame_us + int(t0_off)
                    step_us = int(round(1_000_000 / float(sr_hz))) if sr_hz else 0
                    for i in range(samples.shape[0]):
                        dev_us = t0_us + i * step_us
                        seg_idx = int((dev_us - t0_dev_us) // rotate_us) if rotate_us > 0 else 0
                        env = int(samples[i, 0])
                        rawv = int(samples[i, 1]) if ch > 1 else 0
                        ref = int(samples[i, 2]) if ch > 2 else 0
                        _get_emg_w(seg_idx, sr_hz, ch).writerow(
                            [int(dev_us), int(seq0 + i), env, rawv, ref, int(frame_seq), frame_host_ms])
                    stats["emg_samples"] += int(samples.shape[0])

                elif t == TLV_IMU_BATCH:
                    seq0, t0_off, sr_hz, count, acc_fsr_g, gyro_fsr_dps, samples = \
                        decode_imu(v, int(ver), int(flags))
                    t0_us = dev_frame_us + int(t0_off)
                    step_us = int(round(1_000_000 / float(sr_hz))) if sr_hz else 0
                    for i in range(samples.shape[0]):
                        dev_us = t0_us + i * step_us
                        seg_idx = int((dev_us - t0_dev_us) // rotate_us) if rotate_us > 0 else 0
                        ax, ay, az, gx, gy, gz = [int(x) for x in samples[i, :].tolist()]
                        _get_imu_w(seg_idx, sr_hz, acc_fsr_g, gyro_fsr_dps).writerow([
                            int(dev_us), int(seq0 + i), ax, ay, az, gx, gy, gz,
                            acc_fsr_g if acc_fsr_g is not None else "",
                            gyro_fsr_dps if gyro_fsr_dps is not None else "",
                            int(frame_seq), frame_host_ms])
                    stats["imu_samples"] += int(samples.shape[0])

                elif t == TLV_VITALS:
                    vit = decode_vitals(v)
                    age_ms = int(vit["age_ms"]) if vit.get("age_ms") is not None else 65535
                    dev_us_computed = int(dev_frame_us - age_ms * 1000)
                    vitals_w.writerow([
                        int(dev_frame_us), dev_us_computed,
                        "" if vit["spo2"] is None else int(vit["spo2"]),
                        "" if vit["hr"] is None else float(vit["hr"]),
                        int(age_ms), int(vit["flags"]), int(vit["brightness"]),
                        int(frame_seq), frame_host_ms])
                    stats["vitals_records"] += 1

    for w in emg_writers.values():
        w.close()
    for w in imu_writers.values():
        w.close()
    vitals_fp.flush()
    vitals_fp.close()

    stats["outputs"] = (
        [str(w.csv_path()) for w in emg_writers.values()] +
        [str(w.meta_path()) for w in emg_writers.values()] +
        [str(w.csv_path()) for w in imu_writers.values()] +
        [str(w.meta_path()) for w in imu_writers.values()] +
        [str(out_dir / "vitals_log.csv")]
    )
    return stats


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def discover_sessions(captures_dir: str,
                      gap_thr_min: float = SESSION_GAP_MIN) -> str:
    """Group all frames_*.bin files in captures_dir into tennis sessions.

    Consecutive captures with gap < gap_thr_min minutes belong to the same
    session. This mirrors Windows where the user puts each session in a
    separate folder.

    Args:
        captures_dir: directory containing frames_YYYYMMDD_HHMMSS.bin files
        gap_thr_min:  gap threshold in minutes (default 30)

    Returns:
        JSON string:
          n_sessions  int   — number of sessions found
          sessions    list  — each entry:
            session_id    str   — YYYYMMDD_HHMMSS of first capture
            bin_files     list  — sorted frames_*.bin paths in this session
            capture_ids   list  — YYYYMMDD_HHMMSS of each BLE connection
            duration_min  float — wall-clock span in minutes
            n_captures    int   — number of BLE connections

    Java usage:
        String json = mod.callAttr("discover_sessions", capturesDir).toString();
        // or with custom gap:
        String json = mod.callAttr("discover_sessions", capturesDir, 45.0).toString();
    """
    root = Path(str(captures_dir))
    bin_files = sorted(root.glob("frames_*.bin"))

    if not bin_files:
        return json.dumps({"sessions": [], "n_sessions": 0})

    entries: List[Tuple[datetime, Path]] = []
    for p in bin_files:
        dt = _parse_bin_dt(p)
        if dt is not None:
            entries.append((dt, p))
    entries.sort(key=lambda x: x[0])

    if not entries:
        return json.dumps({"sessions": [], "n_sessions": 0,
                           "error": "No parseable timestamps in filenames"})

    gap_thr_s = gap_thr_min * 60.0
    groups: List[List[Tuple[datetime, Path]]] = []
    cur: List[Tuple[datetime, Path]] = [entries[0]]

    for i in range(1, len(entries)):
        gap_s = (entries[i][0] - entries[i - 1][0]).total_seconds()
        if gap_s >= gap_thr_s:
            groups.append(cur)
            cur = [entries[i]]
        else:
            cur.append(entries[i])
    groups.append(cur)

    sessions = []
    for grp in groups:
        first_dt, last_dt = grp[0][0], grp[-1][0]
        sessions.append({
            "session_id": first_dt.strftime(_BIN_FMT),
            "bin_files": [str(p) for _, p in grp],
            "capture_ids": [dt.strftime(_BIN_FMT) for dt, _ in grp],
            "duration_min": round((last_dt - first_dt).total_seconds() / 60.0, 1),
            "n_captures": len(grp),
        })

    return json.dumps({"sessions": sessions, "n_sessions": len(sessions)})


def process_session(bin_path: str, out_dir: Optional[str] = None,
                    rotate_min: float = 20.0) -> str:
    """Decode ONE frames_*.bin file → rotated CSVs.

    For a full tennis session with multiple BLE reconnections, use
    process_tennis_session() instead.

    Args:
        bin_path:   path to frames_YYYYMMDD_HHMMSS.bin
        out_dir:    output directory (default: decoded_<stem>/ next to bin file)
        rotate_min: CSV rotation interval in minutes (default 20)

    Returns:
        JSON string with decode stats and output file paths.

    Java usage:
        String json = mod.callAttr("process_session", binPath).toString();
    """
    p = Path(str(bin_path))
    stem = p.stem
    session_id = stem.split("_", 1)[1] if "_" in stem else stem
    decode_dir = Path(str(out_dir)) if out_dir else p.parent / f"decoded_{stem}"
    if decode_dir.exists():
        shutil.rmtree(decode_dir)
    stats = _decode_bin_direct(p, decode_dir, session_id, float(rotate_min))
    return json.dumps(stats)


def analyze_tennis_session(captures_dir: str, session_id: str,
                           emg_fs_hz: float,
                           rotate_min: float = 20.0,
                           gap_thr_min: float = SESSION_GAP_MIN,
                           win_sec: float = 1.0,
                           step_sec: float = 0.25,
                           warmup_skip_min: float = 5.0,
                           baseline_swings: int = 20,
                           swing_pre_ms: float = 250.0,
                           swing_post_ms: float = 450.0,
                           mdf_pad_ms: float = 300.0) -> str:
    """Full pipeline: decode all BLE captures for a session, stitch by host_rx_ms,
    and run swing detection + fatigue analysis.

    This is the Android equivalent of emg_util_yaml_runner.analyze_tennis_session()
    on Windows. It handles:
      - Multiple frames_*.bin files (BLE reconnects, ESP32 resets)
      - Physical timeline construction via host_rx_ms (TLV_HOST_MS = 0x04)
      - Stitching across captures: sort all decoded rows by ts_ms (= host_rx_ms)
      - Swing detection on the stitched IMU timeline
      - Per-swing EMG features + fatigue baseline

    Pipeline
    --------
    1. decode: process_tennis_session() → per-capture decoded_frames_*/ dirs
    2. stitch: stitch_decoded_session_from_paths() → sorted by ts_ms
    3. analyze: windowed features + swing detection + baseline + fatigue

    Args:
        captures_dir:    flat directory containing all frames_*.bin files
        session_id:      YYYYMMDD_HHMMSS of the first capture in the session
        emg_fs_hz:       EMG sampling rate in Hz (e.g. 2000.0)
        rotate_min:      CSV rotation interval in minutes (default 20)
        gap_thr_min:     session boundary gap in minutes (default 30)
        win_sec:         windowed feature window size in seconds (default 1.0)
        step_sec:        windowed feature step size in seconds (default 0.25)
        warmup_skip_min: minutes to skip for baseline selection (default 5.0)
        baseline_swings: number of baseline swings to use (default 20)
        swing_pre_ms:    pre-peak window for swing detection (default 250 ms)
        swing_post_ms:   post-peak window for swing detection (default 450 ms)
        mdf_pad_ms:      MDF computation padding (default 300 ms)

    Returns:
        JSON string with full analysis results:
          session_id, n_captures, capture_ids, reconnection_times_s,
          window_feats, swing_windows, swing_feats, baseline, summary

    Java usage:
        String json = mod.callAttr("analyze_tennis_session",
                          capturesDir, sessionId, 2000.0).toString();
        JSONObject res = new JSONObject(json);
        int numSwings = res.getJSONObject("summary").getInt("num_swings");
    """
    try:
        from emg_util.emg_util_nopandas import stitch_decoded_session_from_paths
    except ImportError:
        from emg_util_nopandas import stitch_decoded_session_from_paths  # type: ignore

    # Step 1: Decode all frames_*.bin files for this session
    decode_json = process_tennis_session(captures_dir, session_id,
                                         rotate_min, gap_thr_min)
    decode_result = json.loads(decode_json)

    if "error" in decode_result:
        return json.dumps(decode_result)

    emg_paths = decode_result.get("emg_csv_paths", [])
    imu_paths = decode_result.get("imu_csv_paths", [])

    if not emg_paths:
        return json.dumps({
            "error": "No EMG CSV files found after decoding",
            "decode_result": decode_result,
        })

    # Step 2+3: Stitch all decoded CSVs by ts_ms and run full analysis.
    # ts_ms = host_rx_ms (from TLV_HOST_MS) when non-zero, else dev_us/1000.
    # Sorting by ts_ms constructs the physical timeline across all captures:
    #   - BLE reconnect gaps are preserved (host_rx_ms jumps forward)
    #   - ESP32 resets are handled (host_rx_ms is continuous, dev_us resets)
    try:
        result = stitch_decoded_session_from_paths(
            emg_paths=emg_paths,
            imu_paths=imu_paths,
            emg_fs_hz=float(emg_fs_hz),
            win_sec=float(win_sec),
            step_sec=float(step_sec),
            warmup_skip_min=float(warmup_skip_min),
            baseline_swings=int(baseline_swings),
            swing_pre_ms=float(swing_pre_ms),
            swing_post_ms=float(swing_post_ms),
            mdf_pad_ms=float(mdf_pad_ms),
        )
        # Attach session metadata
        result["session_id"] = session_id
        result["n_captures"] = decode_result.get("n_captures", 0)
        result["capture_ids"] = decode_result.get("capture_ids", [])
        result["reconnection_times_s"] = decode_result.get("reconnection_times_s", [])
        result["emg_csv_paths"] = emg_paths
        result["imu_csv_paths"] = imu_paths
        return json.dumps(result, default=lambda x: None if (x != x) else x)
    except Exception as e:
        return json.dumps({
            "error": str(e),
            "session_id": session_id,
            "decode_result": decode_result,
        })


def process_tennis_session(captures_dir: str, session_id: str,
                           rotate_min: float = 20.0,
                           gap_thr_min: float = SESSION_GAP_MIN) -> str:
    """Decode and stitch all BLE captures belonging to one tennis session.

    Android equivalent of emg_util_yaml_runner.analyze_tennis_session() on Windows
    (where the user selects a folder containing all captures for one session).

    Use discover_sessions() first to find available session_ids.

    Args:
        captures_dir: flat directory containing all frames_*.bin files
        session_id:   YYYYMMDD_HHMMSS of the first capture in the session
        rotate_min:   CSV rotation interval in minutes (default 20)
        gap_thr_min:  session boundary gap in minutes (default 30)

    Returns:
        JSON string:
          session_id          str    — YYYYMMDD_HHMMSS of session start
          n_captures          int    — number of BLE connections in session
          capture_ids         list   — YYYYMMDD_HHMMSS of each BLE connection
          reconnection_times_s list  — wall-clock offsets of reconnections (s)
          total_emg_files     int    — total decoded EMG CSV files
          total_imu_files     int    — total decoded IMU CSV files
          emg_csv_paths       list   — all decoded emg_log_*.csv paths
          imu_csv_paths       list   — all decoded imu_log_*.csv paths
          decode_stats        list   — per-capture decode stats
          errors              list   — any per-capture errors

    Java usage:
        // Step 1: find sessions
        String sessJson = mod.callAttr("discover_sessions", capturesDir).toString();
        // Step 2: decode + stitch chosen session
        String resJson  = mod.callAttr("process_tennis_session",
                              capturesDir, sessionId).toString();
        JSONObject res = new JSONObject(resJson);
        JSONArray emgPaths = res.getJSONArray("emg_csv_paths");
    """
    sessions_data = json.loads(discover_sessions(captures_dir, gap_thr_min))
    target = None
    for s in sessions_data.get("sessions", []):
        if s["session_id"] == session_id:
            target = s
            break
    if target is None:
        return json.dumps({
            "error": f"Session '{session_id}' not found in {captures_dir}",
            "available_sessions": [s["session_id"]
                                   for s in sessions_data.get("sessions", [])],
        })

    bin_files = [Path(p) for p in target["bin_files"]]
    capture_ids: List[str] = target["capture_ids"]
    root = Path(str(captures_dir))

    # Wall-clock offsets between BLE connections (reconnection times)
    first_dt = datetime.strptime(capture_ids[0], _BIN_FMT)
    reconnection_times_s: List[float] = [
        (datetime.strptime(cid, _BIN_FMT) - first_dt).total_seconds()
        for cid in capture_ids[1:]
    ]

    all_emg_paths: List[str] = []
    all_imu_paths: List[str] = []
    decode_stats_list: List[Dict] = []
    errors: List[str] = []

    for bin_path in bin_files:
        stem = bin_path.stem
        cap_session_id = stem.split("_", 1)[1] if "_" in stem else stem
        decode_dir = root / f"decoded_{stem}"
        if decode_dir.exists():
            shutil.rmtree(decode_dir)
        try:
            stats = _decode_bin_direct(bin_path, decode_dir,
                                        cap_session_id, float(rotate_min))
            decode_stats_list.append(stats)
            if decode_dir.exists():
                all_emg_paths.extend(
                    sorted(str(p) for p in decode_dir.glob("emg_log_*.csv")))
                all_imu_paths.extend(
                    sorted(str(p) for p in decode_dir.glob("imu_log_*.csv")))
        except Exception as e:
            errors.append(f"{bin_path.name}: {e}")

    return json.dumps({
        "session_id": session_id,
        "n_captures": len(bin_files),
        "capture_ids": capture_ids,
        "reconnection_times_s": reconnection_times_s,
        "total_emg_files": len(all_emg_paths),
        "total_imu_files": len(all_imu_paths),
        "emg_csv_paths": all_emg_paths,
        "imu_csv_paths": all_imu_paths,
        "decode_stats": decode_stats_list,
        "errors": errors,
    })
