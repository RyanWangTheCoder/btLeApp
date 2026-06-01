# Release Plan — Athlete EMG App (Play Store)

## Goal
Publish the Android EMG/IMU fatigue monitoring app to Google Play Store (Open Testing track)
so anyone can download and try it — with or without the ESP32 hardware — via a Demo Mode.

## Target Track
**Open Testing** on Google Play — publicly accessible via direct link, not search-discoverable.
Lower review bar than production; suitable for hardware-dependent apps with a small initial audience.

---

## Phase 1 — Demo Mode (Bundled Session Replay)

Bundle the `log0305` golden session CSV files as app assets. Add a "Try Demo" entry point on the
launch screen that bypasses BLE and replays the pre-captured session through the same
`FatigueMonitor` / `offline_runner.py` pipeline as a live capture, with a persistent "DEMO" badge
in the UI.

**Tasks:**
- Copy `log0305` EMG/IMU CSVs into `app/src/main/assets/demo_session/`
- Add "Try Demo" button to `MainActivity`
- Add demo playback path in `SessionCaptureActivity` (or a new `DemoPlaybackActivity`)
- Show "DEMO" badge overlay; disable BLE controls during replay
- Verify fatigue output matches PC-side analysis of the same session

---

## Phase 2 — Post-Session Recovery Recommendations

After a session ends (live or demo), display a summary screen with a rule-based recovery
recommendation derived from `mdf_drop_pct`, swing count, and session duration. No ML — thresholds
use existing algorithm parameters.

**Rules (initial):**
- fresh (<12% MDF drop): "Good session — light stretch, hydrate, ready to play again in 2-4 h"
- moderate (12-20%): "Moderate fatigue — rest 24 h, focus on technique next session"
- high (>20%): "High fatigue — rest 48 h, consult a coach if persistent"

**Tasks:**
- Add `SessionSummaryActivity` (or bottom sheet) shown after session stop
- Compute summary from `session_summary.json` output of `offline_runner.py`
- Map fatigue level to recommendation text (strings.xml for i18n readiness)
- Show swing count, session duration, peak fatigue level, and recommendation

---

## Phase 3 — Multi-Session History and Trends

Store a lightweight per-session summary (JSON) on device. Add a Sessions screen listing past
sessions with date, duration, swing count, and fatigue badge. Show a trend sparkline across the
last N sessions.

**Tasks:**
- Define `SessionRecord` schema (JSON, stored in app-private storage)
- Append record after every completed session (live + demo)
- Add `SessionHistoryActivity` with RecyclerView list
- Add trend chart (reuse existing plot infrastructure) for MDF drop % across sessions
- "Clear history" option in settings

---

## Phase 4 — Play Store Readiness

Everything needed to submit and pass review.

**Tasks:**
- App icon (adaptive icon: foreground muscle/wave glyph + background color)
- App name, short/long description, What's New text
- First-run onboarding screen: explains hardware, offers Demo Mode for those without it
- Privacy policy page (required — app uses Bluetooth + stores health-adjacent data)
  - Host at GitHub Pages or similar; link from Play Store listing and in-app Settings
- Permissions rationale strings for BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION
- Min SDK: confirm Android 7.0 (API 24) — Chaquopy minimum
- Keystore generation + signingConfigs in build.gradle (keep keystore out of git)
- Build release AAB: ./gradlew bundleRelease
- Play Console: create app, upload AAB to Open Testing track, fill store listing, add screenshots

---

## Phase 5 — Polish and Stability

**Tasks:**
- ProGuard/R8 rules for Chaquopy, emg_util submodule, and FastBLE
- Review uncaught-exception surfaces in BLE state machine (reconnect, device-not-found)
- Smoke-test on Android 7, 10, 13 (or emulator equivalents)
- Check APK/AAB size; confirm under Play Store limits with Chaquopy included
- Add crash reporting (Firebase Crashlytics or equivalent) — optional for beta

---

## Out of Scope (for now)
- ML-based fatigue prediction (Stage 2 per Paper 3 research program)
- Cloud sync / account system
- iOS port
- Production (non-testing) Play Store track

---

## Tooling Notes
- Coding: VSCode + Claude Code over SSH — Java/Python/XML edits, ./gradlew builds
- UI verification: Android Studio layout preview or physical device via ADB
- Signing: generate keystore locally; store password in a .env file excluded from git