# CLAUDE.md — btLeApp/app (Android capture + on-device analysis)

This is the **Android app** repo (Java + Chaquopy Python) that captures EMG/IMU from the ESP32 over BLE
(FastBLE) and runs on-device analysis. Design: `HLD_androidApp.md`, `INTEGRATION_SUMMARY.md`,
`src/main/python/ANDROID_HLD.md`; tests: `src/main/python/REGRESSION_TEST.md`.

**It embeds the emg_util repo as a git submodule** at `app/src/main/python/emg_util` — emg_util is the
canonical source of truth for all signal-processing algorithms. `fatigue_monitor.py` is the exception:
it must be **hand-copied** to `app/src/main/python/fatigue_monitor.py` on every change. The Android-facing
`emg_util_nopandas.py` must stay algorithmically identical to `emg_util.py`.

Shared system context, data formats, terminology, algorithm parameters, and working rules:

@../../emg_util/CLAUDE.md
