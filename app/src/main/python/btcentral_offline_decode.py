# -*- coding: utf-8 -*-
"""Offline decoder for frames.bin recorded on Android.

Reads len-prefixed raw BLE frames (binary) and writes Windows-compatible rotated CSV logs
under <session_dir>/decoded/.

Design goals:
- Deterministic segmentation: rotation based on device time (dev_us), not wall-clock.
- Output schemas aligned with btCentral.py on Windows.
- Session-local outputs: decoded/ folder is deleted+recreated each run.

Notes:
- host_rx_ms cannot be reconstructed offline; we write 0.
"""

import os
import csv
import json
import struct
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional, Tuple, List

import numpy as np

# --- Binary protocol constants (match Arduino + btCentral.py) ---
MAGIC = b"\xA5Z"               # 0xA5 then ASCII 'Z' (
HEADER_LEN = 14
CRC_LEN = 2

TLV_EMG_BATCH = 0x01
TLV_IMU_BATCH = 0x02
TLV_VITALS    = 0x03

FLAG_CRC16 = 0x01
FLAG_IMU_META_V2 = 0x02

ACC_FSR_CODE_TO_G = {0: 2, 1: 4, 2: 8, 3: 16}
GYRO_FSR_CODE_TO_DPS = {0: 250, 1: 500, 2: 1000, 3: 2000}

MAX_PAYLOAD_LEN = 512


def crc16_ccitt(data: bytes, crc: int = 0xFFFF) -> int:
    for b in data:
        crc ^= (b << 8) & 0xFFFF
        for _ in range(8):
            crc = (((crc << 1) ^ 0x1021) & 0xFFFF) if (crc & 0x8000) else ((crc << 1) & 0xFFFF)
    return crc & 0xFFFF


class FrameParser:
    """Streaming frame parser (handles partial input)."""

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data: bytes):
        self.buf.extend(data)
        frames = []
        while True:
            i = self.buf.find(MAGIC)
            if i < 0:
                # keep last byte to allow magic spanning boundary
                if len(self.buf) > 1:
                    del self.buf[:-1]
                break
            if i > 0:
                del self.buf[:i]
            if len(self.buf) < HEADER_LEN:
                break

            # Header: <BBBBIIH
            _m0, _m1, ver, flags, frame_seq, t_us_u32, payload_len = struct.unpack_from("<BBBBIIH", self.buf, 0)
            if payload_len > MAX_PAYLOAD_LEN:
                del self.buf[0:1]
                continue

            total_len = HEADER_LEN + payload_len + CRC_LEN
            if len(self.buf) < total_len:
                break

            frame_bytes = bytes(self.buf[:total_len])
            del self.buf[:total_len]

            if flags & FLAG_CRC16:
                pre_crc = frame_bytes[:-2]
                rx_crc = struct.unpack_from("<H", frame_bytes, len(frame_bytes) - 2)[0]
                if crc16_ccitt(pre_crc) != rx_crc:
                    frames.append((ver, flags, frame_seq, t_us_u32, [(0xFF, b"CRC_ERROR")]))
                    continue

            payload = frame_bytes[HEADER_LEN:HEADER_LEN + payload_len]
            tlvs = []
            off = 0
            while off + 3 <= len(payload):
                tlv_type = payload[off]
                tlv_len = struct.unpack_from("<H", payload, off + 1)[0]
                off += 3
                if off + tlv_len > len(payload):
                    break
                tlvs.append((tlv_type, payload[off:off + tlv_len]))
                off += tlv_len

            frames.append((ver, flags, frame_seq, t_us_u32, tlvs))

        return frames


class DeviceClockExtender:
    """Extend uint32 microsecond clock to uint64 across wrap (~71 min)."""
    def __init__(self):
        self.wraps = 0
        self.last_u32: Optional[int] = None

    def extend(self, t_us_u32: int) -> int:
        t = int(t_us_u32) & 0xFFFFFFFF
        if self.last_u32 is None:
            self.last_u32 = t
            return t
        if t < self.last_u32 and (self.last_u32 - t) > 0x40000000:
            self.wraps += 1
        self.last_u32 = t
        return (self.wraps << 32) + t


def decode_emg(val: bytes):
    # meta: seq0(u32) t0_off(i16) sr(u16) ch(u8) mask(u8) rsv(u8) count(u16)
    seq0, t0_off, sr_hz = struct.unpack_from("<IhH", val, 0)
    ch = val[8]
    count = struct.unpack_from("<H", val, 11)[0]
    samples = np.frombuffer(val[13:], dtype="<u2").reshape(count, ch)
    return int(seq0), int(t0_off), int(sr_hz), int(ch), samples


def decode_imu(val: bytes, ver: int, flags: int):
    seq0, t0_off, sr_hz = struct.unpack_from("<IhH", val, 0)
    count = val[8]
    meta_len = 12
    acc_fsr_g = None
    gyro_fsr_dps = None
    if (ver >= 2) or (flags & FLAG_IMU_META_V2):
        meta_len = 14
        if len(val) >= 14:
            acc_fsr_g = ACC_FSR_CODE_TO_G.get(int(val[12]))
            gyro_fsr_dps = GYRO_FSR_CODE_TO_DPS.get(int(val[13]))
    samples = np.frombuffer(val[meta_len:], dtype="<i2").reshape(count, 6)
    return int(seq0), int(t0_off), int(sr_hz), int(count), acc_fsr_g, gyro_fsr_dps, samples


def decode_vitals(val: bytes) -> Dict[str, Optional[float]]:
    spo2_u16, hr_x10_u16, age_ms, flags, bright = struct.unpack_from("<HHHBB", val, 0)
    spo2 = None if spo2_u16 == 0xFFFF else float(spo2_u16)
    hr = None if hr_x10_u16 == 0xFFFF else float(hr_x10_u16) / 10.0
    return {
        "spo2": spo2,
        "hr": hr,
        "age_ms": int(age_ms),
        "flags": int(flags),
        "brightness": int(bright),
    }


@dataclass
class SegmentCsv:
    outdir: Path
    base: str
    session_id: str
    header: List[str]
    meta: Dict
    idx: int
    # open_id: timestamp when this file was "opened" (rotation boundary).
    # Defaults to session_id so output matches Windows btCentral.py filename format:
    #   emg_log_<session_id>_<open_id>_<idx>.csv
    # which is required by capture_loader.discover_sessions() on PC.
    open_id: str = ""

    fp: Optional[object] = None
    w: Optional[csv.writer] = None

    def __post_init__(self):
        # Default open_id to session_id if not provided
        if not self.open_id:
            self.open_id = self.session_id

    def csv_path(self) -> Path:
        return self.outdir / f"{self.base}_{self.session_id}_{self.open_id}_{self.idx:03d}.csv"

    def meta_path(self) -> Path:
        return self.outdir / f"{self.base}_{self.session_id}_{self.open_id}_{self.idx:03d}.meta.json"

    def open(self):
        self.outdir.mkdir(parents=True, exist_ok=True)
        self.fp = open(self.csv_path(), "w", newline="", encoding="utf-8")
        self.w = csv.writer(self.fp)
        self.w.writerow(self.header)
        self.write_meta()

    def write_meta(self):
        tmp = str(self.meta_path()) + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.meta, f, indent=2, sort_keys=True)
        os.replace(tmp, self.meta_path())

    def writerow(self, row: List):
        if self.fp is None:
            self.open()
        self.w.writerow(row)

    def close(self):
        if self.fp:
            self.fp.flush()
            self.fp.close()
        self.fp = None
        self.w = None


def _read_len_prefixed_frames(frames_path: Path):
    """Yield raw frames from frames.bin: [u32_len][frame_bytes]."""
    with open(frames_path, "rb") as f:
        while True:
            hdr = f.read(4)
            if not hdr or len(hdr) < 4:
                break
            (n,) = struct.unpack("<I", hdr)
            if n <= 0:
                break
            data = f.read(n)
            if len(data) < n:
                break
            yield data


def decode_frames_bin_to_rotated_csv(session_dir: str, rotate_min: float = 20.0, overwrite_decoded: bool = True) -> Dict:
    session_dir = Path(session_dir)
    frames_path = session_dir / "frames.bin"
    meta_path = session_dir / "meta.json"
    decoded_dir = session_dir / "decoded"

    if overwrite_decoded:
        if decoded_dir.exists():
            shutil.rmtree(decoded_dir)
        decoded_dir.mkdir(parents=True, exist_ok=True)

    if not frames_path.exists():
        raise FileNotFoundError(str(frames_path))

    session_id = session_dir.name
    rotate_us = int(float(rotate_min) * 60.0 * 1_000_000.0)

    # Writers (created on-demand per segment)
    emg_writers: Dict[int, SegmentCsv] = {}
    imu_writers: Dict[int, SegmentCsv] = {}

    vitals_fp = open(decoded_dir / "vitals_log.csv", "w", newline="", encoding="utf-8")
    vitals_w = csv.writer(vitals_fp)
    vitals_w.writerow(["dev_us_frame", "dev_us_computed", "spo2", "hr_bpm", "age_ms", "flags", "brightness", "frame_seq", "host_rx_ms"])

    parser = FrameParser()
    clock = DeviceClockExtender()

    t0_dev_us: Optional[int] = None

    # Meta templates
    emg_meta_base = {
        "session_id": session_id,
        "rotation_minutes": float(rotate_min),
        "proto": "frames.bin_offline_decode",
        "host_rx_ms": 0,
    }
    imu_meta_base = {
        "session_id": session_id,
        "rotation_minutes": float(rotate_min),
        "proto": "frames.bin_offline_decode",
        "host_rx_ms": 0,
    }

    stats = {
        "session_dir": str(session_dir),
        "decoded_dir": str(decoded_dir),
        "rotate_min": float(rotate_min),
        "frames_total": 0,
        "frames_ok": 0,
        "crc_errors": 0,
        "emg_samples": 0,
        "imu_samples": 0,
        "vitals_records": 0,
        "emg_segments": 0,
        "imu_segments": 0,
        "outputs": [],
    }

    def get_emg_writer(seg_idx: int, sr_hz: int, ch: int) -> SegmentCsv:
        if seg_idx in emg_writers:
            return emg_writers[seg_idx]
        meta = dict(emg_meta_base)
        meta.update({
            "segment_index": int(seg_idx),
            "emg_rate_hz": int(sr_hz),
            "channels": int(ch),
        })
        w = SegmentCsv(
            outdir=decoded_dir,
            base="emg_log",
            session_id=session_id,
            header=["dev_us", "emg_seq", "env", "raw", "ref", "frame_seq", "host_rx_ms"],
            meta=meta,
            idx=seg_idx,
        )
        emg_writers[seg_idx] = w
        stats["emg_segments"] = len(emg_writers)
        stats["outputs"].append(str(w.csv_path()))
        stats["outputs"].append(str(w.meta_path()))
        return w

    def get_imu_writer(seg_idx: int, sr_hz: int, acc_fsr_g: Optional[int], gyro_fsr_dps: Optional[int]) -> SegmentCsv:
        if seg_idx in imu_writers:
            return imu_writers[seg_idx]
        meta = dict(imu_meta_base)
        meta.update({
            "segment_index": int(seg_idx),
            "imu_rate_hz": int(sr_hz),
            "imu_acc_fsr_g": acc_fsr_g,
            "imu_gyro_fsr_dps": gyro_fsr_dps,
        })
        w = SegmentCsv(
            outdir=decoded_dir,
            base="imu_log",
            session_id=session_id,
            header=["dev_us", "imu_seq", "ax", "ay", "az", "gx", "gy", "gz", "acc_fsr_g", "gyro_fsr_dps", "frame_seq", "host_rx_ms"],
            meta=meta,
            idx=seg_idx,
        )
        imu_writers[seg_idx] = w
        stats["imu_segments"] = len(imu_writers)
        stats["outputs"].append(str(w.csv_path()))
        stats["outputs"].append(str(w.meta_path()))
        return w

    # Process frames
    for raw in _read_len_prefixed_frames(frames_path):
        stats["frames_total"] += 1
        for ver, flags, frame_seq, t_us_u32, tlvs in parser.feed(raw):
            if tlvs and tlvs[0][0] == 0xFF:
                stats["crc_errors"] += 1
                continue

            stats["frames_ok"] += 1
            dev_frame_us = clock.extend(t_us_u32)
            if t0_dev_us is None:
                t0_dev_us = dev_frame_us
                stats["t0_dev_us"] = int(t0_dev_us)

            for t, v in tlvs:
                if t == TLV_EMG_BATCH:
                    seq0, t0_off, sr_hz, ch, samples = decode_emg(v)
                    # sample timestamps
                    t0_us = dev_frame_us + int(t0_off)
                    step_us = int(round(1_000_000 / float(sr_hz))) if sr_hz else 0
                    for i in range(samples.shape[0]):
                        dev_us = t0_us + i * step_us
                        seg_idx = int((dev_us - t0_dev_us) // rotate_us) if rotate_us > 0 else 0
                        env = int(samples[i, 0])
                        rawv = int(samples[i, 1]) if ch > 1 else 0
                        ref = int(samples[i, 2]) if ch > 2 else 0
                        get_emg_writer(seg_idx, sr_hz, ch).writerow([
                            int(dev_us), int(seq0 + i), env, rawv, ref, int(frame_seq), 0
                        ])
                    stats["emg_samples"] += int(samples.shape[0])

                elif t == TLV_IMU_BATCH:
                    seq0, t0_off, sr_hz, count, acc_fsr_g, gyro_fsr_dps, samples = decode_imu(v, int(ver), int(flags))
                    t0_us = dev_frame_us + int(t0_off)
                    step_us = int(round(1_000_000 / float(sr_hz))) if sr_hz else 0
                    for i in range(samples.shape[0]):
                        dev_us = t0_us + i * step_us
                        seg_idx = int((dev_us - t0_dev_us) // rotate_us) if rotate_us > 0 else 0
                        ax, ay, az, gx, gy, gz = [int(x) for x in samples[i, :].tolist()]
                        get_imu_writer(seg_idx, sr_hz, acc_fsr_g, gyro_fsr_dps).writerow([
                            int(dev_us), int(seq0 + i), ax, ay, az, gx, gy, gz,
                            acc_fsr_g if acc_fsr_g is not None else "",
                            gyro_fsr_dps if gyro_fsr_dps is not None else "",
                            int(frame_seq), 0
                        ])
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
                        int(frame_seq), 0
                    ])
                    stats["vitals_records"] += 1

    # Close writers
    for w in emg_writers.values():
        w.close()
    for w in imu_writers.values():
        w.close()
    vitals_fp.flush()
    vitals_fp.close()

    # Add vitals file to outputs
    stats["outputs"].append(str(decoded_dir / "vitals_log.csv"))

    # Write processing summary
    summary_path = decoded_dir / "processing_summary.json"
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=2, sort_keys=True)
    stats["outputs"].append(str(summary_path))

    return stats
