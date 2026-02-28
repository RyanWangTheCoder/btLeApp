# BLE EMG/IMU Sensor App - High Level Design

## Overview

This Android application provides real-time collection, visualization, and analysis of EMG (Electromyography) and IMU (Inertial Measurement Unit) sensor data via Bluetooth Low Energy (BLE). The app is specifically designed for **sports performance monitoring** with a focus on tennis swing analysis and real-time fatigue detection.

---

## System Architecture

### Technology Stack

- **Platform**: Android (Java)
- **BLE Library**: FastBLE (wrapper around Android BluetoothGatt)
- **Python Integration**: Chaquopy (embedded Python runtime)
- **Signal Processing**: Custom Java implementations + Python scipy/numpy
- **UI Framework**: Android Activities, Custom Views
- **Threading**: Java ExecutorService, Handler, ArrayBlockingQueue

### High-Level Components

```
┌─────────────────────────────────────────────────────────────┐
│                     Android Application                      │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Activities)                                       │
│  ├─ MainActivity (BLE scan/connect)                         │
│  ├─ SessionCaptureActivity (real-time capture)              │
│  ├─ ProcessingActivity (post-session analysis)              │
│  └─ OperationActivity (BLE service explorer)                │
├─────────────────────────────────────────────────────────────┤
│  Business Logic                                              │
│  ├─ BLE Manager (FastBLE wrapper)                           │
│  ├─ Data Decoders (TLV protocol)                            │
│  ├─ Signal Processing (EmgProcessor.java)                   │
│  └─ Session Management                                       │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                  │
│  ├─ Ring Buffers (real-time plotting)                       │
│  ├─ File I/O (binary frames, CSV export)                    │
│  └─ Python Bridge (Chaquopy)                                │
├─────────────────────────────────────────────────────────────┤
│  Python Layer (Chaquopy)                                     │
│  ├─ analysis_bridge.py (Java ↔ Python interface)           │
│  ├─ Frame decoder (binary → CSV)                            │
│  ├─ Feature extraction (scipy/numpy)                        │
│  └─ Fatigue monitoring (MDF analysis)                       │
└─────────────────────────────────────────────────────────────┘
                            ↕ BLE
┌─────────────────────────────────────────────────────────────┐
│              BLE Device (ESP32 + Sensors)                    │
│  ├─ EMG sensor (2000 Hz, 2 channels)                        │
│  ├─ IMU sensor (200 Hz, 6-axis)                             │
│  └─ Vitals sensor (HR, SpO2, Battery)                       │
└─────────────────────────────────────────────────────────────┘
```

---

## UI Activities & User Interactions

### 1. MainActivity - Device Connection Hub

**Purpose**: BLE device discovery, connection management, and navigation

**UI Elements**:
- **Scan Button**: Start/stop BLE scanning
- **Device List**: Shows discovered and connected devices
- **Search Settings** (expandable):
  - Device Name filter (EditText)
  - MAC Address filter (EditText)
  - Service UUID filter (EditText)
  - Auto-connect toggle (Switch)
- **Action Buttons**:
  - "Monitor" → SessionCaptureActivity (live monitoring)
  - "Record" → SessionCaptureActivity (same as Monitor)
  - "Process" → ProcessingActivity (analyze saved sessions)

**User Interactions**:
1. User taps "Start Scan" → App scans for BLE devices
2. User taps device in list → App connects and negotiates MTU (up to 247 bytes)
3. User taps "Monitor/Record" → Navigate to SessionCaptureActivity
4. User taps "Process" → Navigate to ProcessingActivity

**BLE Connection Flow**:
```
User taps device
  ↓
BleManager.connect()
  ↓
onConnectSuccess()
  ↓
Request MTU (247 bytes, 3 retries with 600ms delay)
  ↓
Request CONNECTION_PRIORITY_HIGH
  ↓
Device ready (do NOT start notify here - wait for SessionCaptureActivity)
```

**Key Features**:
- Permission handling (ACCESS_FINE_LOCATION, Bluetooth)
- GPS check (required for BLE scanning on Android)
- Device list adapter with connect/disconnect/detail actions
- MTU negotiation with retry logic

---

### 2. SessionCaptureActivity - Real-Time Data Capture

**Purpose**: Core data collection with live visualization and fatigue monitoring

**UI Elements**:

**Top Controls**:
- **Status Text**: Connection/recording status
- **Stats Text**: Frame count, bytes written, settings
- **Battery Text**: Device battery percentage
- **Heart Rate Text**: Real-time HR (if available)
- **SpO2 Text**: Blood oxygen saturation
- **Fatigue Text**: Real-time fatigue analysis results

**Action Buttons**:
- **Start Session**: Begin recording
- **Stop Session**: End recording and save
- **Process Session**: Navigate to ProcessingActivity for current session

**Plot Controls**:
- **Freeze Switch**: Freeze/unfreeze plot display
- **Window Button**: Cycle time window (5s/10s/20s)
- **RAW Hz Button**: Toggle raw data decimation (250/500 Hz)
- **IMU Scale Button**: Toggle auto/fixed scaling
- **IMU Threshold SeekBar**: Adjust gyro swing detection threshold (50-500 dps)

**Plots**:
- **EMG Plot**: 3 series (RAW, ENV, LPF) + swing markers
- **IMU Plot**: Accelerometer magnitude + swing markers

**User Interactions**:

1. **Start Recording**:
   ```
   User taps "Start Session"
     ↓
   Create session directory (or continue existing if gap < 30 min)
     ↓
   Open binary output file (frames_YYYYMMDD_HHMMSS.bin)
     ↓
   Start worker threads (logThread, plotThread, fatigueThread)
     ↓
   Request MTU (247 bytes)
     ↓
   Subscribe to BLE notifications (NUS_TX_CHAR_UUID)
     ↓
   Start ping timer (1 Hz keepalive)
     ↓
   Recording active (green status)
   ```

2. **During Recording**:
   - BLE notifications arrive → queued to logThread and plotThread
   - logThread writes raw frames to disk
   - plotThread decodes frames → updates ring buffers → triggers plot refresh
   - fatigueThread processes EMG snapshots at swing peaks
   - User can adjust plot settings (window, freeze, threshold) in real-time

3. **Stop Recording**:
   ```
   User taps "Stop Session"
     ↓
   Set recording = false
     ↓
   Stop ping timer
     ↓
   Stop BLE notifications
     ↓
   Wait for worker threads to drain queues
     ↓
   Flush and close output file
     ↓
   Write meta.json (device info, file list)
     ↓
   Enable "Process Session" button
   ```

4. **Adjust Swing Detection**:
   - User drags IMU threshold seekbar (50-500 dps)
   - Real-time update of gyro threshold
   - Markers appear on plots when gyro magnitude exceeds threshold
   - Peak detection prevents double-counting (1-second debounce)

**Multi-Threading Architecture**:

```
BLE Notification Thread (Android system)
  ↓ [byte[] frame]
  ├─→ qLog (ArrayBlockingQueue) → logThread
  │     ↓
  │   Write to frames_*.bin (4-byte length + frame data)
  │
  └─→ qPlot (ArrayBlockingQueue) → plotThread
        ↓
      Decode TLV frame
        ↓
      ├─ EMG batch → emgRawBuf, emgEnvBuf, emgFiltBuf, emgFatigueBuf
      ├─ IMU batch → imuBuf + swing detection
      └─ Vitals → UI update (battery, HR, SpO2)
        ↓
      Swing detected? → fatigueQueue → fatigueThread
                                          ↓
                                        Python MDF analysis
                                          ↓
                                        UI fatigue display

UI Thread (33ms refresh)
  ↓
renderPlots() reads ring buffers → draws to custom views
```

**Swing Detection Algorithm**:

```java
For each IMU sample:
  Calculate gyro magnitude (dps) = sqrt(gx² + gy² + gz²) / 16.4
  
  If gyro_mag >= threshold:
    If not in peak:
      Start new peak (record magnitude and time)
    Else:
      Update peak if this is higher
  
  Else if in peak:
    // Dropped below threshold - end of peak
    If (peak_time - last_marker_time) > 1 second:
      Add marker at peak_time
      Snapshot EMG buffer (1300ms window)
      Queue for fatigue analysis
    End peak
```

**Real-Time Fatigue Monitoring**:

```
Swing detected (gyro peak)
  ↓
Snapshot EMG buffer (2600 samples @ 2000 Hz = 1300ms)
  ↓
Queue FatigueJob(emg[], tPeakMs, imuPeakMag)
  ↓
fatigueThread picks up job
  ↓
Convert EMG to JSON array
  ↓
Python: analysis_bridge.push_swing_realtime(emgJson, tPeakMs, imuPeakMag)
  ↓
Python computes MDF (Median Frequency)
  ↓
Compare to baseline (first 20 swings)
  ↓
Return JSON: {swing_id, mdf, mdf_drop_pct, fatigue_level, warning, ...}
  ↓
UI displays: "Swing #N | MDF=X Hz | Drop=Y% | LEVEL (!)"
```

**Session Continuation Logic**:

```
User starts new recording
  ↓
Find most recent frames_*.bin across all session directories
  ↓
Parse timestamp from filename (frames_20260228_051234.bin)
  ↓
Calculate gap = current_time - last_timestamp
  ↓
If gap < 30 minutes:
  Continue existing session (append new frames_*.bin to same directory)
Else:
  Create new session directory (session_YYYYMMDD_HHMMSS/)
```

---

### 3. ProcessingActivity - Post-Session Analysis

**Purpose**: Decode binary frames and run comprehensive analysis

**UI Elements**:
- **Progress Bar**: Analysis progress (0-100%)
- **Stage Text**: Current processing stage
- **Output Text**: Scrollable analysis results (JSON)
- **Analyze Button**: Start full analysis
- **Cancel Button**: Cancel ongoing analysis

**User Interactions**:

1. **Auto-Decode on Open**:
   ```
   Activity opens with session_dir intent extra
     ↓
   Auto-start decoding (background thread)
     ↓
   Python: process_session_compat(session_dir)
     ↓
   Decode all frames_*.bin → CSV files
     ↓
   Display results: "✓ Decoded CSVs ready for analysis"
   ```

2. **Manual Analysis**:
   ```
   User taps "Analyze" button
     ↓
   Python: analyze_session(session_dir)
     ↓
   Load decoded CSVs
     ↓
   Run full feature extraction pipeline
     ↓
   Detect swings, compute fatigue metrics
     ↓
   Return JSON results
     ↓
   Display formatted output
   ```

**Processing Stages**:
- 5%: Starting Python runtime
- 15%: Importing modules
- 40%: Decoding frames / Running analysis
- 100%: Complete

**Error Handling**:
- Catches all exceptions
- Displays full stack trace in output text
- Logs to Android logcat

---

### 4. OperationActivity - BLE Service Explorer

**Purpose**: Generic GATT service/characteristic browser (debugging tool)

**UI Elements**:
- **Toolbar**: Navigation breadcrumb
- **Fragment Container**: Shows service list → characteristic list → console

**User Interactions**:
1. User selects service → Navigate to characteristic list
2. User selects characteristic → Navigate to console
3. Console allows read/write/notify operations
4. Back button navigates up the hierarchy

---

## Data Flow & File Organization

### Real-Time Data Path

```
ESP32 Device
  ↓ [BLE Notification: byte[] frame]
Android BLE Stack
  ↓
FastBLE Library (onCharacteristicChanged)
  ↓
SessionCaptureActivity
  ├─→ qLog.offer(frame.clone())
  │     ↓
  │   logThread.run()
  │     ↓
  │   BufferedOutputStream.write(length + frame)
  │     ↓
  │   frames_YYYYMMDD_HHMMSS.bin
  │
  └─→ qPlot.offer(frame.clone())
        ↓
      plotThread.run()
        ↓
      decodeForPlot(frame)
        ├─ TLV_EMG_BATCH → emgRawBuf, emgEnvBuf, emgFiltBuf, emgFatigueBuf
        ├─ TLV_IMU_BATCH → imuBuf + swing detection
        └─ TLV_VITALS → UI update
        ↓
      Swing detected?
        ↓
      fatigueQueue.offer(FatigueJob)
        ↓
      fatigueThread.run()
        ↓
      Python: push_swing_realtime()
        ↓
      UI: txtFatigue.setText()
```

### File Organization

```
/storage/emulated/0/Android/data/com.clj.blesample/files/
├── session_20260228_051234/
│   ├── frames_20260228_051234.bin    (binary TLV frames)
│   ├── frames_20260228_052145.bin    (continuation if gap < 30 min)
│   ├── meta.json                      (device info, file list)
│   ├── emg_decoded.csv                (generated by Python decoder)
│   ├── imu_decoded.csv                (generated by Python decoder)
│   ├── vitals_decoded.csv             (generated by Python decoder)
│   └── analysis_results.json          (generated by full analysis)
├── session_20260228_063012/
│   ├── frames_20260228_063012.bin
│   ├── meta.json
│   └── ...
└── ...
```

**meta.json Structure**:
```json
{
  "device_name": "EMG_Sensor_01",
  "mac": "AA:BB:CC:DD:EE:FF",
  "frames_files": [
    "frames_20260228_051234.bin",
    "frames_20260228_052145.bin"
  ],
  "frames_written": 12543,
  "bytes_written": 8234567
}
```

**Binary Frame Format** (TLV Protocol):
```
┌─────────────────────────────────────────────────────────┐
│ Header (14 bytes)                                        │
├─────────────────────────────────────────────────────────┤
│ 0-1:   Magic (0xA5 0x5A)                                │
│ 2:     Version                                           │
│ 3:     Flags (bit 0: CRC enabled)                       │
│ 4-7:   Reserved                                          │
│ 8-11:  Timestamp (uint32, microseconds, wraps)          │
│ 12-13: Payload Length (uint16)                          │
├─────────────────────────────────────────────────────────┤
│ Payload (TLV blocks)                                     │
├─────────────────────────────────────────────────────────┤
│ TLV Block 1:                                             │
│   Type (1 byte): 0x01=EMG, 0x02=IMU, 0x03=Vitals       │
│   Length (2 bytes): Value length                         │
│   Value (N bytes): Type-specific data                    │
├─────────────────────────────────────────────────────────┤
│ TLV Block 2: ...                                         │
├─────────────────────────────────────────────────────────┤
│ CRC-16 CCITT (2 bytes, if flag set)                     │
└─────────────────────────────────────────────────────────┘
```

**EMG Batch TLV (Type 0x01)**:
```
Offset  Size  Field
0       4     seq0 (uint32, sequence number of first sample)
4       2     t0_offset (int16, microseconds offset from frame timestamp)
6       2     sample_rate (uint16, Hz)
8       1     channel_count (uint8)
9       2     reserved
11      2     sample_count (uint16)
13      N     samples (channel_count * 2 bytes per sample, uint16)
              [env_ch0, raw_ch1, env_ch0, raw_ch1, ...]
```

**IMU Batch TLV (Type 0x02)**:
```
Offset  Size  Field
0       4     seq0 (uint32)
4       2     t0_offset (int16, microseconds)
6       2     sample_rate (uint16, Hz)
8       1     sample_count (uint8)
9       5     reserved
14      N     samples (12 bytes per sample)
              [ax, ay, az, gx, gy, gz] (int16 each)
```

**Vitals TLV (Type 0x03)**:
```
Offset  Size  Field
0       2     spo2 (uint16, %)
2       2     heart_rate (uint16, bpm)
4       2     age (uint16)
6       1     flags (uint8)
7       1     brightness (uint8)
8       1     battery_pct (uint8, %)
9       2     battery_mv (uint16, millivolts)
```

### Decoded CSV Format

**emg_decoded.csv**:
```csv
timestamp_us,seq,channel,value,sample_rate
1234567890,0,0,2048,2000
1234567890,0,1,1856,2000
1234568390,1,0,2051,2000
...
```

**imu_decoded.csv**:
```csv
timestamp_us,seq,ax,ay,az,gx,gy,gz,sample_rate
1234567890,0,128,-45,16384,23,-12,8,200
1234572890,1,130,-43,16380,25,-10,6,200
...
```

**vitals_decoded.csv**:
```csv
timestamp_us,spo2,heart_rate,battery_pct,battery_mv
1234567890,98,72,85,3850
1235567890,98,73,85,3848
...
```

---

## Java ↔ Python Integration (Chaquopy)

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   Java Layer                             │
├─────────────────────────────────────────────────────────┤
│  SessionCaptureActivity.java                             │
│    ├─ fatigueThread (background)                        │
│    │    ↓                                                │
│    │  Python.getInstance()                              │
│    │    ↓                                                │
│    │  PyObject mod = py.getModule("analysis_bridge")   │
│    │    ↓                                                │
│    │  mod.callAttr("push_swing_realtime", ...)         │
│    │    ↓                                                │
│    │  String result = pyResult.toString()              │
│    │    ↓                                                │
│    └─ Parse JSON, update UI                             │
│                                                          │
│  ProcessingActivity.java                                 │
│    ├─ executor.submit(() -> {                           │
│    │    Python.start(new AndroidPlatform(this))        │
│    │    PyObject mod = py.getModule("analysis_bridge") │
│    │    mod.callAttr("process_session_compat", dir)    │
│    │    mod.callAttr("analyze_session", dir)           │
│    └─ })                                                 │
└─────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────┐
│                  Python Layer (Chaquopy)                 │
├─────────────────────────────────────────────────────────┤
│  app/src/main/python/analysis_bridge.py                 │
│    ├─ push_swing_realtime(emg_json, t_peak_ms, imu_mag)│
│    │    ↓                                                │
│    │  Parse JSON array → numpy array                    │
│    │    ↓                                                │
│    │  Compute MDF (scipy.signal.welch)                 │
│    │    ↓                                                │
│    │  Update baseline / compare to baseline            │
│    │    ↓                                                │
│    │  Determine fatigue level                           │
│    │    ↓                                                │
│    │  Return JSON: {swing_id, mdf, mdf_drop_pct, ...} │
│    │                                                     │
│    ├─ process_session_compat(session_dir)              │
│    │    ↓                                                │
│    │  Find all frames_*.bin files                       │
│    │    ↓                                                │
│    │  Decode binary → CSV (emg/imu/vitals)             │
│    │    ↓                                                │
│    │  Return JSON: {status, files_decoded, ...}        │
│    │                                                     │
│    └─ analyze_session(session_dir)                      │
│         ↓                                                │
│       Load CSVs → pandas DataFrames                     │
│         ↓                                                │
│       Feature extraction (RMS, MNF, MDF, entropy)       │
│         ↓                                                │
│       Swing detection (gyro threshold)                  │
│         ↓                                                │
│       Fatigue analysis (MDF trends)                     │
│         ↓                                                │
│       Return JSON: {swings, features, fatigue_metrics}  │
└─────────────────────────────────────────────────────────┘
```

### Python Module Location

```
app/src/main/python/
├── analysis_bridge.py       (Java ↔ Python interface)
├── frame_decoder.py         (Binary TLV decoder)
├── feature_extractor.py     (Signal processing)
├── fatigue_monitor.py       (Real-time MDF analysis)
└── swing_detector.py        (Gyro-based swing detection)
```

### Key Integration Points

**1. Python Initialization** (one-time per app lifecycle):
```java
if (!Python.isStarted()) {
    Python.start(new AndroidPlatform(context));
}
Python py = Python.getInstance();
```

**2. Module Import**:
```java
PyObject mod = py.getModule("analysis_bridge");
```

**3. Function Call with Arguments**:
```java
// Simple call
PyObject result = mod.callAttr("function_name");

// With arguments
PyObject result = mod.callAttr("function_name", arg1, arg2, arg3);

// Example: Real-time fatigue
String emgJson = "[2048, 1856, 2051, ...]";  // JSON array
float tPeakMs = 12345.67f;
float imuMag = 234.5f;
PyObject result = mod.callAttr("push_swing_realtime", emgJson, tPeakMs, imuMag);
```

**4. Result Handling**:
```java
String resultStr = result.toString();  // Python returns JSON string
JSONObject obj = new JSONObject(resultStr);
int swingId = obj.getInt("swing_id");
double mdf = obj.getDouble("mdf");
```

**5. Threading Considerations**:
- Python calls are **blocking** (can take 50-200ms for MDF computation)
- Always call Python from **background threads** (not UI thread)
- Use `Handler.post()` to update UI from background thread

**6. Error Handling**:
```java
try {
    PyObject result = mod.callAttr("function_name", args);
} catch (Exception e) {
    Log.e(TAG, "Python error", e);
    // Chaquopy wraps Python exceptions in Java exceptions
}
```

### Data Exchange Formats

**Java → Python**:
- Primitives: `int`, `float`, `double`, `String` (auto-converted)
- Arrays: Convert to JSON string (e.g., `"[1,2,3]"`)
- Complex objects: Serialize to JSON string

**Python → Java**:
- Return JSON string from Python
- Parse in Java using `org.json.JSONObject`

**Example**:
```python
# Python (analysis_bridge.py)
def push_swing_realtime(emg_json_str, t_peak_ms, imu_peak_mag):
    import json
    emg_array = json.loads(emg_json_str)  # Parse JSON array
    # ... process ...
    result = {
        "swing_id": 42,
        "mdf": 123.45,
        "mdf_drop_pct": 8.2,
        "fatigue_level": "mild"
    }
    return json.dumps(result)  # Return JSON string
```

```java
// Java (SessionCaptureActivity.java)
String emgJson = "[2048, 1856, 2051, ...]";
PyObject pyResult = mod.callAttr("push_swing_realtime", emgJson, 12345.67f, 234.5f);
String resultStr = pyResult.toString();
JSONObject obj = new JSONObject(resultStr);
int swingId = obj.getInt("swing_id");
```

---

## Signal Processing Pipeline

### Java-Based Processing (EmgProcessor.java)

**Purpose**: Standalone signal processing without Python dependencies

**Pipeline**:
```
Raw EMG signal (double[])
  ↓
1. Detrend (remove DC offset)
  ↓
2. Bandpass filter (e.g., 20-450 Hz)
   - Biquad IIR filter (2nd order)
   - Zero-phase (forward + reverse pass)
  ↓
3. Notch filter (60 Hz + harmonics)
   - Remove mains interference
   - Zero-phase
  ↓
4. Sliding window feature extraction
   - Window: 0.5-1.0 seconds
   - Overlap: 75% (step = window/4)
   ↓
5. Features per window:
   ├─ RMS (signal amplitude)
   ├─ MNF (Mean Frequency via Welch PSD)
   ├─ MDF (Median Frequency via Welch PSD)
   ├─ Wavelet Entropy (Daubechies-4 DWT)
   └─ Sample Entropy (complexity measure)
```

**Key Algorithms**:

1. **Biquad Filter Design** (RBJ cookbook):
   - Bandpass: Center frequency = sqrt(fLow * fHigh), Q = f0 / bandwidth
   - Notch: Q = f0 / bandwidth (typically Q=30 for narrow notch)

2. **Zero-Phase Filtering** (filtfilt):
   - Forward pass: y = filter(x)
   - Reverse: y = reverse(y)
   - Forward pass again: y = filter(y)
   - Reverse: y = reverse(y)
   - Result: Zero phase distortion, double magnitude response

3. **Welch PSD**:
   - Segment signal into overlapping windows (256 samples, 50% overlap)
   - Apply Hann window to each segment
   - Compute FFT magnitude squared
   - Average across segments
   - Normalize by window power

4. **MNF/MDF Calculation**:
   - MNF = Σ(f * P(f)) / Σ(P(f))  (weighted average)
   - MDF = frequency where CDF(f) = 0.5 * total_power

5. **Wavelet Entropy**:
   - Multi-level DWT decomposition (Daubechies-4)
   - Compute energy of each coefficient array
   - Entropy = -Σ(p_i * log(p_i)), where p_i = energy_i / total_energy

6. **Sample Entropy**:
   - Template matching with Chebyshev distance
   - m=2 (template length), r=0.2*std(signal) (tolerance)
   - SampEn = -log(φ(m+1) / φ(m))

### Python-Based Processing (analysis_bridge.py)

**Purpose**: Leverage scipy/numpy for advanced analysis

**Advantages**:
- Faster FFT (numpy.fft)
- More filter options (scipy.signal)
- Advanced statistical tools
- Easier prototyping

**Usage**:
- Post-session analysis (ProcessingActivity)
- Real-time fatigue monitoring (background thread)

---

## BLE Protocol Details

### Service & Characteristics

**Nordic UART Service (NUS)**:
- Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- TX Characteristic: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` (device → phone, notify)
- RX Characteristic: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` (phone → device, write)

### MTU Negotiation

**Purpose**: Increase BLE packet size from default 23 bytes (20-byte payload) to 247 bytes (244-byte payload)

**Flow**:
```
Phone connects to device
  ↓
Phone requests MTU=247
  ↓
Device accepts (or negotiates lower)
  ↓
negotiatedMtu = result (e.g., 247)
  ↓
Notify payload size = negotiatedMtu - 3 bytes (ATT header)
  ↓
Example: MTU=247 → 244-byte notify payload
```

**Retry Logic**:
```java
private void requestMtuWithRetry(BleDevice device, int mtu, int attemptsLeft) {
    BleManager.getInstance().setMtu(device, mtu, new BleMtuChangedCallback() {
        @Override
        public void onMtuChanged(int mtuResult) {
            negotiatedMtu = mtuResult;
            if (mtuResult <= 23 && attemptsLeft > 1) {
                // Retry after 600ms delay
                handler.postDelayed(() -> requestMtuWithRetry(device, mtu, attemptsLeft - 1), 600);
            }
        }
        @Override
        public void onSetMTUFailure(BleException exception) {
            if (attemptsLeft > 1) {
                handler.postDelayed(() -> requestMtuWithRetry(device, mtu, attemptsLeft - 1), 600);
            }
        }
    });
}
```

### Connection Priority

**Purpose**: Request faster connection interval for higher throughput

```java
BleManager.getInstance().requestConnectionPriority(
    bleDevice, 
    BluetoothGatt.CONNECTION_PRIORITY_HIGH
);
```

**Effect**:
- Default: 30-50ms connection interval
- High priority: 7.5-15ms connection interval
- Result: ~4x more frequent data updates

### Ping Mechanism

**Purpose**: Keep connection alive during recording

```java
Timer pingTimer = new Timer();
pingTimer.scheduleAtFixedRate(new TimerTask() {
    @Override
    public void run() {
        byte[] ping = "PING".getBytes();
        BleManager.getInstance().write(bleDevice, SERVICE_UUID, RX_CHAR_UUID, ping, callback);
    }
}, 0, 1000);  // Every 1 second
```

---

## Performance Considerations

### Threading Strategy

**UI Thread**:
- Plot rendering (33ms refresh = 30 FPS)
- Button clicks, UI updates
- **Never** block with heavy computation

**Background Threads**:
- `logThread`: Write frames to disk (I/O bound)
- `plotThread`: Decode frames, update ring buffers (CPU bound)
- `fatigueThread`: Python MDF analysis (CPU + Python GIL)
- `executor` (ProcessingActivity): Python analysis (CPU intensive)

### Queue Sizing

```java
ArrayBlockingQueue<byte[]> qLog = new ArrayBlockingQueue<>(4096);
ArrayBlockingQueue<byte[]> qPlot = new ArrayBlockingQueue<>(4096);
ArrayBlockingQueue<FatigueJob> fatigueQueue = new ArrayBlockingQueue<>(1);
```

**Rationale**:
- Large queues (4096) for log/plot: Handle burst traffic without dropping frames
- Small queue (1) for fatigue: Only process latest swing (skip if busy)

### Ring Buffer Sizing

```java
RingBufferF emgRawBuf = new RingBufferF(12000);   // 24 seconds @ 500 Hz
RingBufferF emgEnvBuf = new RingBufferF(2000);    // 40 seconds @ 50 Hz
RingBufferF imuBuf = new RingBufferF(12000);      // 60 seconds @ 200 Hz
RingBufferF emgFatigueBuf = new RingBufferF(2600); // 1.3 seconds @ 2000 Hz
```

**Rationale**:
- Raw buffers: Large enough for max window (20s) + margin
- Fatigue buffer: Exact size for MDF window (1300ms ± 300ms padding)

### Data Rate Estimation

**EMG**: 2000 Hz × 2 channels × 2 bytes = 8000 bytes/sec  
**IMU**: 200 Hz × 6 axes × 2 bytes = 2400 bytes/sec  
**Vitals**: ~1 Hz × 11 bytes = 11 bytes/sec  
**Overhead**: TLV headers, frame headers, CRC = ~20% overhead  
**Total**: ~12.5 KB/sec raw data

**BLE Throughput** (MTU=247, high priority):
- Theoretical: ~15-20 KB/sec
- Practical: ~10-15 KB/sec (accounting for protocol overhead)
- **Conclusion**: System operates near BLE capacity, MTU negotiation critical

---

## Key Design Decisions

### 1. Why Binary TLV Protocol?

**Alternatives Considered**:
- JSON: Too verbose, high overhead
- CSV: No framing, difficult to parse streaming data
- Protobuf: Requires code generation, overkill for simple data

**TLV Advantages**:
- Compact (minimal overhead)
- Self-describing (type field)
- Extensible (add new types without breaking old code)
- Easy to parse (fixed header, length-prefixed values)

### 2. Why Separate Log and Plot Threads?

**Rationale**:
- Logging is I/O bound (disk writes)
- Plotting is CPU bound (decoding, buffer updates)
- Separation prevents I/O stalls from affecting real-time display
- Clone frames to avoid race conditions

### 3. Why Java Signal Processing + Python?

**Java (EmgProcessor.java)**:
- Portable, no external dependencies
- Fast enough for real-time features
- Useful for offline analysis without Python

**Python (Chaquopy)**:
- Leverage scipy/numpy for advanced algorithms
- Faster prototyping for new features
- Access to ML libraries (future: fatigue prediction models)

**Hybrid Approach**:
- Use Java for real-time display features (if needed)
- Use Python for complex analysis and fatigue monitoring

### 4. Why Session Continuation Logic?

**Problem**: User may pause/resume recording during a tennis session

**Solution**:
- If gap < 30 minutes: Continue existing session (append new frames file)
- If gap ≥ 30 minutes: Start new session (new directory)

**Benefits**:
- Logical grouping of related recordings
- Easier post-session analysis (all files in one directory)
- Preserves temporal context

### 5. Why Peak Detection for Swings?

**Alternatives Considered**:
- Simple threshold crossing: Double-counts (backswing + forward swing)
- Fixed time windows: Misses variable swing durations

**Peak Detection Advantages**:
- Captures highest point of swing (most relevant for fatigue)
- 1-second debounce prevents double-counting
- Adaptive to swing speed variations

---

## Future Enhancements

### Planned Features

1. **Cloud Sync**:
   - Upload sessions to cloud storage
   - Cross-device access to historical data

2. **ML-Based Fatigue Prediction**:
   - Train models on historical data
   - Predict fatigue before it occurs

3. **Multi-Sensor Support**:
   - Connect multiple devices simultaneously
   - Bilateral EMG comparison (left vs. right arm)

4. **Advanced Visualizations**:
   - 3D IMU orientation display
   - Spectrogram view for EMG
   - Fatigue trend charts

5. **Export Formats**:
   - Export to MATLAB (.mat)
   - Export to EDF (European Data Format)
   - PDF reports with plots and statistics

### Technical Debt

1. **Error Recovery**:
   - Improve handling of BLE disconnections during recording
   - Auto-resume on reconnection

2. **Memory Management**:
   - Implement ring buffer pruning for very long sessions
   - Add memory pressure monitoring

3. **Testing**:
   - Unit tests for signal processing algorithms
   - Integration tests for BLE protocol
   - UI tests for critical user flows

---

## Appendix: Key Classes Reference

### MainActivity.java
- **Purpose**: BLE device management
- **Key Methods**:
  - `startScan()`: Initiate BLE scanning
  - `connect(BleDevice)`: Connect to device with MTU negotiation
  - `setScanRule()`: Configure scan filters

### SessionCaptureActivity.java
- **Purpose**: Real-time data capture and visualization
- **Key Methods**:
  - `startSession()`: Begin recording
  - `stopSession()`: End recording and save
  - `decodeForPlot(byte[])`: Parse TLV frame
  - `decodeEmgBatch()`: Extract EMG samples
  - `decodeImuBatch()`: Extract IMU samples + swing detection
  - `renderPlots()`: Update UI plots (33ms refresh)

### ProcessingActivity.java
- **Purpose**: Post-session analysis
- **Key Methods**:
  - `startDecoding()`: Decode frames.bin to CSV
  - `startAnalysis()`: Run full feature extraction

### EmgProcessor.java
- **Purpose**: Pure Java signal processing
- **Key Methods**:
  - `preprocess()`: Detrend + bandpass + notch
  - `computeFeatures()`: Sliding window feature extraction
  - `mnfMdfWelch()`: Spectral features via Welch PSD
  - `waveletEntropyDb4()`: Wavelet-based entropy
  - `sampleEntropy()`: Complexity measure

### RingBufferF.java
- **Purpose**: Efficient circular buffer for time-series data
- **Key Methods**:
  - `add(float t, float y)`: Append sample
  - `snapshot(float tMin, float tMax, float[] xs, float[] ys)`: Extract window

### SimplePlotView.java
- **Purpose**: Custom view for real-time waveform plotting
- **Key Methods**:
  - `setViewport(float xMin, float xMax, float yMin, float yMax)`
  - `setSeries0/1/2(float[] xs, float[] ys, int n)`
  - `setMarkers(float[] times, int n)`

---

## Glossary

- **BLE**: Bluetooth Low Energy
- **EMG**: Electromyography (muscle electrical activity)
- **IMU**: Inertial Measurement Unit (accelerometer + gyroscope)
- **MTU**: Maximum Transmission Unit (BLE packet size)
- **TLV**: Type-Length-Value (data encoding format)
- **MDF**: Median Frequency (fatigue indicator)
- **MNF**: Mean Frequency (spectral centroid)
- **RMS**: Root Mean Square (signal amplitude)
- **DWT**: Discrete Wavelet Transform
- **PSD**: Power Spectral Density
- **NUS**: Nordic UART Service (BLE service)
- **Chaquopy**: Python runtime for Android

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-28  
**Author**: System Analysis
