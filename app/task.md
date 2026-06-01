# task.md — Phase 1: Demo Mode (Bundled Session Replay)

## Goal
Add a "Try Demo" button to MainActivity that launches SessionCaptureActivity in demo mode,
replaying the bundled log0305 golden session through the same ring buffers and fatigue pipeline
as a live capture — no BLE device required.

## Context
- `SessionCaptureActivity` already handles `bleDevice == null` (disables buttons, shows message).
  We extend that path rather than creating a new Activity.
- `MainActivity` has a dead `demoCheckBox` (commented out, lines 125-144) — prior attempt.
- `log0305` is PC-captured CSV (emg_log_*.csv, imu_log_*.csv). Android uses frames_*.bin TLV.
  We pre-convert on PC to a single compact asset CSV; the demo thread reads it directly into
  the existing ring buffers, bypassing the BLE/TLV decode path entirely.

## Tasks

### Step 1 — Prepare demo asset (PC side, run once) ✅
- [x] Write `tools/make_demo_asset.py`: loads log0305, skips first 20 min (electrode settling),
      takes 5-min window, outputs binary demo_emg.bin + demo_imu.bin
      Format: demo_emg.bin = magic(DEMG) + n(i32) + {t_ms:i32, raw:i16, env:i16} × n
              demo_imu.bin = magic(DIMU) + n(i32) + {t_ms:i32, ax,ay,az,gx,gy,gz:i16} × n
      IMU scale: accel ×1000 (milli-g), gyro ×10 (0.1 dps)
- [x] Copied to `app/src/main/assets/demo_emg.bin` (4.6 MB) + `demo_imu.bin` (942 KB)
- [x] Verified: median RAW=1836 (≈1843 ✓), max gyro=2845 dps (swings confirmed ✓),
      4,148 samples above 300 dps threshold ✓

### Step 2 — Add "Try Demo" button to MainActivity
- [ ] Edit `app/src/main/res/layout/activity_main.xml`:
      - Add `<Button android:id="@+id/btn_demo" android:text="Try Demo" />` in the button row
- [ ] Edit `MainActivity.java`:
      - Add `btn_demo` field and wire in `initView()`
      - Remove dead demoCheckBox code (lines 125-144, already commented out — just clean up)
      - In `onClick()`: launch `SessionCaptureActivity` with
        `intent.putExtra("DEMO_MODE", true)` — no bleDevice extra needed

### Step 3 — DemoPlaybackThread in SessionCaptureActivity
- [ ] Add `boolean isDemoMode` field, set from `getIntent().getBooleanExtra("DEMO_MODE", false)`
- [ ] In `onCreate()`, if `isDemoMode`:
      - Skip the `bleDevice == null` early-return block
      - Set status: "DEMO — replaying log0305 session"
      - Show a "DEMO" badge (can reuse `txtStats` or add a TextView)
      - Disable BLE-related controls: btnStart, btnStop (or relabel them Play/Stop)
      - Enable btnProcessSession pointing to a temp copy of the demo asset
      - Call `startDemoPlayback()`
- [ ] Implement `startDemoPlayback()`:
      - Opens `assets/demo_session.csv` via `getAssets().open("demo_session.csv")`
      - Reads rows in a background thread (`demoThread`)
      - For each row, calls `emgRawBuf.add(tSec, raw)`, `emgEnvBuf.add(tSec, env)`,
        `imuBuf.add(tSec, accelMag)` directly — same buffers renderPlots() already reads
      - Feeds `emgFatigueBuf` and triggers `fatigueQueue` on gyro peak (reuse peak-detection
        logic already in `decodeImuBatch`, extracted to a helper)
      - Playback speed: real-time (sleep to match t_sec deltas) or 4× — TBD
- [ ] Add `stopDemoPlayback()` and call from `onDestroy()`

### Step 4 — Polish
- [ ] "DEMO" label visible at all times during demo (color badge in status bar area)
- [ ] After demo ends (EOF): show "Demo complete — X swings detected, fatigue: Y"
- [ ] btnProcessSession in demo mode: copy asset CSV to a temp sessionDir, launch ProcessingActivity
      (ProcessingActivity expects a dir with frames_*.bin — need to check if it can accept CSV
      directly, or if we need to skip/stub this for now)

## Open questions before coding
1. Playback speed: real-time (boring for a demo) vs 4× vs "fast-forward to first swing"?
2. ProcessingActivity compatibility: does it work on the demo session dir, or stub for Phase 1?
3. Asset size: after skipping the first 20 min, the 5–8 min window is ~2–4 MB CSV. Fine to
   bundle as-is; no additional subsampling needed.

## Files touched
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/java/com/clj/blesample/MainActivity.java`
- `app/src/main/java/com/clj/blesample/capture/SessionCaptureActivity.java`
- `app/src/main/assets/demo_session.csv` (new)
- `tools/make_demo_asset.py` (new, in btLeApp repo)
