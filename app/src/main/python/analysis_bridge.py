# -*- coding: utf-8 -*-
"""analysis_bridge.py — COMPATIBILITY SHIM (deprecated)

This file is kept for backward compatibility with existing Java code that
imports from "analysis_bridge". New code should import directly from:

  offline_runner   — post-capture decode + stitch + session discovery
                     (Android equivalent of emg_util_yaml_runner.py)
  realtime_bridge  — live fatigue monitoring during BLE capture
                     (Android-specific, no Windows equivalent)

Java migration:
  OLD: Python.getInstance().getModule("analysis_bridge")
  NEW: Python.getInstance().getModule("offline_runner")    // for offline
       Python.getInstance().getModule("realtime_bridge")   // for real-time
"""

# Re-export everything from the two new modules so existing callers still work.
from offline_runner import (
    SESSION_GAP_MIN,
    discover_sessions,
    process_session,
    process_tennis_session,
)

from realtime_bridge import (
    reset_fatigue_monitor,
    push_swing_realtime,
    get_fatigue_summary,
    run_demo,
)
