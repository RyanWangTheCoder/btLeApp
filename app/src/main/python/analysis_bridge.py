# -*- coding: utf-8 -*-
"""analysis_bridge.py - COMPATIBILITY WRAPPER

This module provides a compatibility layer for existing Java code in
ProcessingActivity.java and SessionCaptureActivity.java.

It delegates to the modern modular architecture:
  - offline_runner.py for post-capture analysis
  - realtime_bridge.py for real-time fatigue monitoring

This wrapper is ACTIVELY MAINTAINED to ensure backward compatibility
while the codebase uses the new modular structure.
"""

import json
from pathlib import Path
from typing import Optional

# Import modern modules
from offline_runner import discover_sessions, process_tennis_session, process_session
from realtime_bridge import reset_fatigue_monitor, push_swing_realtime


# ---------------------------------------------------------------------------
# Compatibility API for ProcessingActivity.java
# ---------------------------------------------------------------------------

def process_session_compat(session_dir: Optional[str] = None) -> str:
    """Compatibility wrapper for ProcessingActivity.java.
    
    Handles Android session directory structure:
      session_YYYYMMDD_HHMMSS/
        frames.bin
        meta.json
    
    Args:
        session_dir: Path to session directory (e.g., /path/to/session_20260228_044323)
        
    Returns:
        JSON string with processing results
    """
    if session_dir is None or session_dir == "":
        # No session specified - return available sessions
        # Use default Android captures directory
        captures_dir = "/storage/emulated/0/Android/data/com.clj.blesample/files"
        return discover_sessions(captures_dir)
    
    # Android session structure: session_YYYYMMDD_HHMMSS/frames_*.bin (or legacy frames.bin)
    session_path = Path(session_dir)
    
    # Extract session_id from directory name (e.g., "session_20260228_044616" → "20260228_044616")
    dir_name = session_path.name
    if dir_name.startswith("session_"):
        session_id = dir_name[8:]  # Remove "session_" prefix
    else:
        session_id = dir_name
    
    # Look for frames_*.bin files (multi-file sessions) or legacy frames.bin
    frames_bins = sorted(session_path.glob("frames_*.bin"))
    legacy_frames = session_path / "frames.bin"
    
    if frames_bins or legacy_frames.exists():
        # Process session (handles both multi-file and legacy single-file)
        try:
            from btcentral_offline_decode import decode_frames_bin_to_rotated_csv
            
            # Decode all frames files with proper session_id from directory name
            # The decoder now automatically handles multiple frames_*.bin files
            stats = decode_frames_bin_to_rotated_csv(
                session_dir=str(session_path),
                rotate_min=20.0,
                overwrite_decoded=True
            )
            
            # Update session_id in stats to match directory name
            stats["session_id"] = session_id
            stats["decoded_dir"] = str(session_path / "decoded")
            
            # Add info about multi-file sessions
            if frames_bins:
                stats["multi_file_session"] = True
                stats["frames_files_count"] = len(frames_bins)
            
            return json.dumps(stats)
        except Exception as e:
            import traceback
            return json.dumps({
                "error": f"Failed to process session: {e}",
                "session_dir": str(session_path),
                "session_id": session_id,
                "frames_bins": [str(f.name) for f in frames_bins] if frames_bins else [],
                "legacy_frames": str(legacy_frames) if legacy_frames.exists() else None,
                "exception": str(type(e).__name__),
                "traceback": traceback.format_exc()
            })
    else:
        # No frames files found - list what's in the directory
        try:
            contents = list(session_path.glob("*"))
            return json.dumps({
                "error": f"No frames_*.bin or frames.bin found in {session_dir}",
                "session_dir": str(session_path),
                "directory_contents": [str(p.name) for p in contents],
                "help": "Expected structure: session_YYYYMMDD_HHMMSS/frames_*.bin or frames.bin"
            })
        except Exception as e:
            return json.dumps({
                "error": f"Could not access {session_dir}: {e}",
                "session_dir": str(session_path)
            })


def run_demo() -> str:
    """DEPRECATED: Compatibility wrapper for demo mode.
    
    Returns information about available sessions instead of running a demo.
    
    Returns:
        JSON string with available sessions
    """
    # Use default Android captures directory
    captures_dir = "/storage/emulated/0/Android/data/com.clj.blesample/files/captures"
    
    try:
        sessions_json = discover_sessions(captures_dir)
        sessions_data = json.loads(sessions_json)
        
        return json.dumps({
            "demo_mode": True,
            "message": "Demo mode: showing available sessions",
            "captures_dir": captures_dir,
            "sessions": sessions_data.get("sessions", []),
            "n_sessions": sessions_data.get("n_sessions", 0),
            "instructions": "Use process_session() with a session_id to analyze a specific session"
        })
    except Exception as e:
        return json.dumps({
            "demo_mode": True,
            "error": f"Could not list sessions: {e}",
            "captures_dir": captures_dir
        })


# ---------------------------------------------------------------------------
# Compatibility API for SessionCaptureActivity.java
# ---------------------------------------------------------------------------

# These functions are already provided by realtime_bridge.py
# We just re-export them here for compatibility

def analyze_session(session_dir: str) -> str:
    """Run full analysis on decoded session (Android equivalent of emg_util_yaml_runner.py).
    
    This function performs the complete analysis pipeline:
    1. Find all decoded CSVs in session_dir/decoded/
    2. Stitch multiple captures together (if multiple frames_*.bin were decoded)
    3. Run swing detection
    4. Compute fatigue metrics
    5. Generate summary statistics
    
    Args:
        session_dir: Path to session directory containing decoded/ subdirectory
        
    Returns:
        JSON string with analysis results including:
        - n_swings: Total number of swings detected
        - baseline metrics (RMS, MDF, IMU acceleration)
        - fatigue metrics (RMS drop %, MDF drop %, acc drop %)
        - bout summary statistics
    """
    from pathlib import Path
    
    session_path = Path(session_dir)
    decoded_dir = session_path / "decoded"
    
    if not decoded_dir.exists():
        return json.dumps({
            "error": f"Decoded directory not found: {decoded_dir}",
            "help": "Click Analyze button after decode completes"
        })
    
    try:
        # Import analysis functions from emg_util submodule
        import sys
        emg_util_path = str(Path(__file__).parent / "emg_util")
        if emg_util_path not in sys.path:
            sys.path.insert(0, emg_util_path)
        
        from emg_util_nopandas import stitch_decoded_session
        
        # Run full analysis (handles multiple decoded CSVs automatically).
        # Parameters must match emg_util_yaml_runner.py defaults on Windows:
        #   emg_fs_hz=2000.0  (MyoWare 2.0 ADC at 2000 Hz — NOT 500 Hz)
        #   baseline_swings=20 (matches FatigueMonitor and yaml_runner defaults)
        #   thr_gyro_dps=300.0 (fixed threshold, replaces legacy swing_z_thr)
        results = stitch_decoded_session(
            decoded_dir=str(decoded_dir),
            emg_fs_hz=2000.0,   # MyoWare 2.0 ADC: 2000 Hz
            win_sec=1.0,
            step_sec=0.25,
            warmup_skip_min=5.0,
            baseline_swings=20,  # matches yaml_runner + FatigueMonitor defaults
            thr_gyro_dps=300.0,  # fixed threshold (replaces legacy swing_z_thr)
            swing_pre_ms=250.0,
            swing_post_ms=450.0,
            mdf_pad_ms=300.0,
        )
        
        # Extract summary from results
        summary_data = results.get("summary", {})
        baseline_data = results.get("baseline", {})
        
        # Format results for display
        output = {
            "session_dir": str(session_path),
            "decoded_dir": str(decoded_dir),
            "n_swings": summary_data.get("num_swings", 0),
            "num_windows": summary_data.get("num_windows", 0),
            "session_duration_s": round(summary_data.get("session_duration_s", 0), 1),
            "baseline": {
                "rms": round(baseline_data.get("rms", 0), 2) if baseline_data.get("rms") else None,
                "mdf_hz": round(baseline_data.get("mdf", 0), 2) if baseline_data.get("mdf") else None,
                "imu_mean_acc_dyn": round(baseline_data.get("imu_mean_acc_dyn", 0), 3) if baseline_data.get("imu_mean_acc_dyn") else None,
            },
            "fatigue": {
                "mean_mdf_drop_pct": round(summary_data.get("mean_mdf_drop_pct", 0), 1) if summary_data.get("mean_mdf_drop_pct") else None,
                "max_mdf_drop_pct": round(summary_data.get("max_mdf_drop_pct", 0), 1) if summary_data.get("max_mdf_drop_pct") else None,
                "mean_acc_drop_pct": round(summary_data.get("mean_acc_drop_pct", 0), 1) if summary_data.get("mean_acc_drop_pct") else None,
            },
        }
        
        return json.dumps(output)
        
    except Exception as e:
        import traceback
        return json.dumps({
            "error": f"Analysis failed: {e}",
            "session_dir": str(session_path),
            "traceback": traceback.format_exc()
        })


__all__ = [
    'process_session_compat',
    'analyze_session',
    'run_demo',
    'reset_fatigue_monitor',
    'push_swing_realtime',
]
