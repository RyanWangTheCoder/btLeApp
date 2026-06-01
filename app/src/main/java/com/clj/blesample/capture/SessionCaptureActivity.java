package com.clj.blesample.capture;

import android.os.Build;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.clj.blesample.R;
import com.clj.blesample.analysis.ProcessingActivity;
import com.clj.blesample.views.MarkerBufferF;
import com.clj.blesample.views.RingBufferF;
import com.clj.blesample.views.SimplePlotView;
import com.clj.fastble.BleManager;
import android.bluetooth.BluetoothGatt;
import com.clj.fastble.callback.BleGattCallback;
import com.clj.fastble.callback.BleNotifyCallback;
import com.clj.fastble.callback.BleMtuChangedCallback;
import com.clj.fastble.callback.BleWriteCallback;
import com.clj.fastble.data.BleDevice;
import com.clj.fastble.exception.BleException;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SessionCaptureActivity extends AppCompatActivity {

    private static final String TAG = "SessionCapture";

    private static final String NUS_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    private static final String NUS_TX_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
    private static final String NUS_RX_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";

    private static final int HEADER_LEN = 14;
    private static final int CRC_LEN = 2;
    private static final byte MAGIC0 = (byte)0xA5;
    private static final byte MAGIC1 = (byte)0x5A;
    private static final int TLV_EMG_BATCH = 0x01;
    private static final int TLV_IMU_BATCH = 0x02;
    private static final int TLV_VITALS    = 0x03;
    /** Host-side wall-clock timestamp injected by Android per BLE notification.
     *  TLV value: uint64 little-endian = System.currentTimeMillis().
     *  Enables correct multi-capture stitching across BLE reconnects / ESP32 resets. */
    private static final int TLV_HOST_MS   = 0x04;

    private static final int ENV_PLOT_HZ = 50;
    private static final int IMU_PLOT_HZ = 200;  // Plot IMU at full rate for smooth waveforms
    private volatile int rawPlotHz = 500;

    // ── Fatigue monitoring ──────────────────────────────────────────────────
    /** 1300 ms at 2000 Hz, includes ±300 ms MDF padding on each side. */
    private static final int EMG_FATIGUE_BUF_SIZE = 2600;
    private final RingBufferF emgFatigueBuf = new RingBufferF(EMG_FATIGUE_BUF_SIZE);
    private final ArrayBlockingQueue<FatigueJob> fatigueQueue = new ArrayBlockingQueue<>(64);
    private Thread fatigueThread;
    private volatile boolean fatigueRunning = false;
    private TextView txtFatigue;

    // ── Demo mode ───────────────────────────────────────────────────────────
    private boolean isDemoMode = false;
    private Thread demoThread;
    private volatile boolean demoRunning = false;
    private int demoSwingCount = 0;
    private TextView txtDemoBadge;
    private static final float DEMO_SPEED = 4.0f;  // replay at 4× real time
    private static final int  DEMO_EMG_MAGIC = 0x474D4544; // 'DEMG' LE
    private static final int  DEMO_IMU_MAGIC = 0x554D4944; // 'DIMU' LE
    // ───────────────────────────────────────────────────────────────────────
    // ───────────────────────────────────────────────────────────────────────

    private TextView txtStatus;
    private TextView txtStats;
    private TextView txtBattery;
    private TextView txtHeartRate;
    private TextView txtSpo2;
    private Button btnStart;
    private Button btnStop;
    private Button btnProcessSession;
    private Switch swFreeze;
    private Button btnWindow;
    private Button btnRawHz;
    private Button btnImuScale;
    private TextView txtImuThr;
    private SeekBar seekImuThr;
    private SimplePlotView emgPlot;
    private SimplePlotView imuPlot;

    private BleDevice bleDevice;
    private volatile boolean recording = false;
    private BufferedOutputStream out;
    private File sessionDir;
    private File framesFile;
    private long bytesWritten = 0;
    private long framesWritten = 0;

    private Timer pingTimer;
    private static final long PING_PERIOD_MS = 1000;

    private final ArrayBlockingQueue<byte[]> qLog = new ArrayBlockingQueue<>(4096);
    private final ArrayBlockingQueue<byte[]> qPlot = new ArrayBlockingQueue<>(4096);

    private Thread logThread;
    private Thread plotThread;

    private final RingBufferF emgRawBuf = new RingBufferF(12000);
    private final RingBufferF emgEnvBuf = new RingBufferF(2000);
    private final RingBufferF emgFiltBuf = new RingBufferF(2000);
    private final RingBufferF imuBuf = new RingBufferF(12000);  // 60 seconds @ 200Hz (was 3000 = 15s)
    private final MarkerBufferF markers = new MarkerBufferF(500);

    private final float[] xs0 = new float[12000];
    private final float[] ys0 = new float[12000];
    private final float[] xs1 = new float[2000];
    private final float[] ys1 = new float[2000];
    private final float[] xs2 = new float[2000];
    private final float[] ys2 = new float[2000];
    private final float[] xsi = new float[12000];  // Match imuBuf size (was 3000)
    private final float[] ysi = new float[12000];  // Match imuBuf size (was 3000)
    private final float[] tmk = new float[500];

    private volatile long t0SessionUs = -1;
    private long wrapCount = 0;
    private long lastTFrameU32 = -1;
    private volatile float frozenNowSec = 0f;
    private volatile boolean frozen = false;
    private volatile float windowSec = 10f;

    // ── Session summary (for SessionSummaryActivity) ────────────────────────
    private volatile String lastFatigueLevel = "unknown";
    private volatile double lastMdfDropPct = Double.NaN;
    private long sessionStartWallMs = 0;

    private volatile float imuThreshold = 300f;  // Gyroscope threshold in dps (degrees per second)
    private volatile long lastMarkerUs = 0;
    private static final long MARKER_DEBOUNCE_US = 2_000_000;  // 2 second debounce (aligns with offline + plotter)
    
    // Peak detection state
    private volatile float lastGyroMag = 0f;
    private volatile boolean inPeak = false;
    private volatile float peakGyroMag = 0f;
    private volatile long peakTimeUs = 0;
    
    private volatile boolean imuScaleAuto = true;  // true=Auto, false=Fixed
    private static final float IMU_FIXED_MIN = 0f;
    // Accelerometer magnitude in raw ADC counts (ICM-20948 ±4g FSR: 8192 LSB/g).
    // At rest: ~8192 (1g). Typical swing peak: 2–4g = 16000–32768.
    // Fixed max = 40000 gives ~10% headroom above full-scale.
    private static final float IMU_FIXED_MAX = 40000f;
    private static final float GYRO_SCALE_FACTOR = 16.4f;  // ICM-20948: ±2000dps range, 32768/2000 = 16.4 LSB/dps

    private float envLpf = 0f;
    private float envAlpha = alphaForCutoff(3.6f, ENV_PLOT_HZ);
    // DC offset tracker: slow LPF (0.1 Hz) to estimate RAW's true midpoint.
    // MyoWare DC bias shifts the center away from the theoretical 2048 (e.g., ~1830–1843).
    // Initialized to 1843 (typical observed value) to minimize convergence time.
    private float rawDcLpf = 1843f;
    private final float rawDcAlpha = alphaForCutoff(0.1f, ENV_PLOT_HZ); // ~0.013 at 50 Hz

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ── Auto-reconnect state ────────────────────────────────────────────────
    private volatile long lastNotifyMs = 0;
    private volatile int reconnectCount = 0;
    private volatile int reconnectBackoffMs = 500;
    private static final int MAX_RECONNECT_BACKOFF_MS = 20_000;
    private static final int STALL_RECONNECT_MS = 5_000;
    private static final int WATCHDOG_INTERVAL_MS = 500;
    private Runnable watchdogRunnable;
    private Runnable reconnectRunnable;
    // ───────────────────────────────────────────────────────────────────────

    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            renderPlots();
            ui.postDelayed(this, 33);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);

        txtStatus = findViewById(R.id.txtStatus);
        txtDemoBadge = findViewById(R.id.txtDemoBadge);
        txtStats = findViewById(R.id.txtStats);
        txtBattery = findViewById(R.id.txtBattery);
        txtHeartRate = findViewById(R.id.txtHeartRate);
        txtSpo2 = findViewById(R.id.txtSpo2);
        btnStart = findViewById(R.id.btnStartSession);
        btnStop = findViewById(R.id.btnStopSession);
        btnProcessSession = findViewById(R.id.btnProcessSession);
        swFreeze = findViewById(R.id.swFreeze);
        btnWindow = findViewById(R.id.btnWindow);
        btnRawHz = findViewById(R.id.btnRawHz);
        txtImuThr = findViewById(R.id.txtImuThr);
        seekImuThr = findViewById(R.id.seekImuThr);
        emgPlot = findViewById(R.id.emgPlot);
        imuPlot = findViewById(R.id.imuPlot);
        txtFatigue = findViewById(R.id.txtFatigue);

        emgPlot.setTitle("EMG: RAW(zoom) / ENV(0-4095) / LPF(abs(RAW))");
        imuPlot.setTitle("IMU acc magnitude + markers");

        if (Build.VERSION.SDK_INT >= 33) {
            //bleDevice = getIntent().getParcelableExtra("bleDevice", BleDevice.class);
            bleDevice = getIntent().getParcelableExtra("bleDevice");
        } else {
            bleDevice = getIntent().getParcelableExtra("bleDevice");
        }

        isDemoMode = getIntent().getBooleanExtra("DEMO_MODE", false);

        if (isDemoMode) {
            txtStatus.setText("DEMO — replaying log0305 session (4× speed)");
            txtDemoBadge.setVisibility(android.view.View.VISIBLE);
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            btnStop.setText("Stop Demo");
            btnStop.setOnClickListener(v -> stopDemoPlayback());
            btnProcessSession.setEnabled(false);
            btnProcessSession.setText("Post-process (live sessions only)");
            btnProcessSession.setOnClickListener(v ->
                Toast.makeText(this, "Post-processing works on recorded BLE sessions, not demo.", Toast.LENGTH_SHORT).show());
            startFatigueThread();
            ui.post(uiTick);
            startDemoPlayback();
            return;
        }

        if (bleDevice == null) {
            txtStatus.setText("No BLE device. Connect first.");
            btnStart.setEnabled(false);
            btnStop.setEnabled(false);
            btnProcessSession.setEnabled(false);
            return;
        }

        btnStart.setOnClickListener(v -> startSession());
        btnStop.setOnClickListener(v -> { if (isDemoMode) stopDemoPlayback(); else stopSession(); });
        btnStop.setEnabled(false);

        btnProcessSession.setEnabled(false);
        btnProcessSession.setOnClickListener(v -> launchProcessThisSession());

        swFreeze.setOnCheckedChangeListener((CompoundButton b, boolean isChecked) -> {
            frozen = isChecked;
            if (frozen) frozenNowSec = latestNowSec();
        });

        btnWindow.setOnClickListener(v -> {
            // Cycle through 5s -> 10s -> 20s -> 5s
            if (windowSec <= 5f) windowSec = 10f;
            else if (windowSec <= 10f) windowSec = 20f;
            else windowSec = 5f;
            btnWindow.setText("Win:" + (int)windowSec + "s");
            if (frozen) frozenNowSec = latestNowSec();
        });
        btnWindow.setText("Win:10s");

        btnRawHz.setOnClickListener(v -> {
            rawPlotHz = (rawPlotHz == 500) ? 250 : 500;
            btnRawHz.setText("RAW:" + rawPlotHz);
        });
        btnRawHz.setText("RAW:500");

        btnImuScale = findViewById(R.id.btnImuScale);
        btnImuScale.setOnClickListener(v -> {
            imuScaleAuto = !imuScaleAuto;
            btnImuScale.setText(imuScaleAuto ? "IMU:Auto" : "IMU:Fix");
        });
        btnImuScale.setText("IMU:Auto");

        final int thrMin = 50;   // 50 dps minimum
        final int thrMax = 500;  // 500 dps maximum
        seekImuThr.setMax(thrMax - thrMin);
        seekImuThr.setProgress((int)(imuThreshold - thrMin));
        txtImuThr.setText("Gyro swing threshold: " + (int)imuThreshold + " dps");
        seekImuThr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                imuThreshold = thrMin + progress;
                txtImuThr.setText("Gyro swing threshold: " + (int)imuThreshold + " dps");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        txtStatus.setText("Ready. Connected=" + BleManager.getInstance().isConnected(bleDevice));
        startFatigueThread();
        ui.post(uiTick);
    }

    private void launchProcessThisSession() {
        if (sessionDir == null) {
            Toast.makeText(this, "No saved session yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(getApplicationContext(), ProcessingActivity.class);
        intent.putExtra(ProcessingActivity.EXTRA_SESSION_DIR, sessionDir.getAbsolutePath());
        startActivity(intent);
    }

    private void startSession() {
        if (recording) return;
        if (!BleManager.getInstance().isConnected(bleDevice)) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            createSessionFiles();
            openOutput();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open log: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        recording = true;
        sessionStartWallMs = System.currentTimeMillis();
        btnProcessSession.setEnabled(false);
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        txtStatus.setText("Requesting MTU...");
        startWorkerThreads();

        // Reset fatigue monitor for the new session (background — Chaquopy can be slow)
        new Thread(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(SessionCaptureActivity.this));
                Python py = Python.getInstance();
                PyObject mod = py.getModule("analysis_bridge");
                mod.callAttr("reset_fatigue_monitor");
                ui.post(() -> txtFatigue.setText("Fatigue: monitoring..."));
            } catch (Exception e) {
                Log.w(TAG, "reset_fatigue_monitor failed", e);
            }
        }, "fatigueReset").start();

        BleManager.getInstance().setMtu(bleDevice, 247, new BleMtuChangedCallback() {
            @Override public void onMtuChanged(int mtu) {
                Log.e("BT_NOTIFY", "MTU changed to " + mtu);
                runOnUiThread(() -> txtStatus.setText("MTU=" + mtu + ", subscribing notify..."));
                subscribeNotifyAndStartPing();
            }
            @Override public void onSetMTUFailure(BleException exception) {
                Log.e("BT_NOTIFY", "MTU request failed: " + exception);
                subscribeNotifyAndStartPing();
            }
        });
    }

    private void subscribeNotifyAndStartPing() {
        BleManager.getInstance().notify(
                bleDevice,
                NUS_SERVICE_UUID,
                NUS_TX_CHAR_UUID,
                new BleNotifyCallback() {
                    @Override public void onNotifySuccess() {
                        Log.e("BT_NOTIFY", "Notify success for chr=" + NUS_TX_CHAR_UUID);
                        runOnUiThread(() -> {
                            txtStatus.setText("Recording... (notify OK)");
                            startPingTimer();
                            startWatchdog(); // start stall watchdog
                        });
                    }
                    @Override public void onNotifyFailure(BleException exception) {
                        runOnUiThread(() -> {
                            txtStatus.setText("Notify failed: " + exception);
                            stopSession();
                        });
                    }
                    @Override public void onCharacteristicChanged(byte[] data) {
                        if (!recording) return;
                        lastNotifyMs = System.currentTimeMillis(); // watchdog heartbeat
                        // Inject TLV_HOST_MS (0x04) into the frame payload before writing
                        // to frames.bin. The Python decoder reads host_rx_ms from this TLV
                        // and uses it as ts_ms, enabling correct stitching across BLE
                        // reconnects and ESP32 resets (where dev_us resets to 0).
                        byte[] withHostTs = injectHostTimestamp(data, lastNotifyMs);
                        qLog.offer(withHostTs);
                        qPlot.offer(data.clone()); // plot path uses original (no TLV needed)
                    }
                }
        );
    }

    private void stopSession() {
        if (!recording) {
            btnStop.setEnabled(false);
            btnStart.setEnabled(true);
            return;
        }
        recording = false;
        btnStop.setEnabled(false);
        btnStart.setEnabled(true);

        stopPingTimer();
        stopWatchdog();
        cancelReconnect();
        reconnectBackoffMs = 500;
        try { BleManager.getInstance().stopNotify(bleDevice, NUS_SERVICE_UUID, NUS_TX_CHAR_UUID); } catch (Exception ignored) {}
        stopWorkerThreads();

        try {
            if (out != null) {
                out.flush();
                out.close();
                out = null;
            }
        } catch (IOException ignored) {}

        try { writeMetaJson(); } catch (Exception e) { Log.w(TAG, "meta.json failed", e); }

        txtStatus.setText("Saved to: " + (sessionDir != null ? sessionDir.getAbsolutePath() : "(null)"));
        btnProcessSession.setEnabled(sessionDir != null);
        updateStats();
        launchSummary(markers.snapshot(0, Float.MAX_VALUE, tmk));
    }

    private void launchSummary(int swingCount) {
        int durationSec = sessionStartWallMs > 0
                ? (int) ((System.currentTimeMillis() - sessionStartWallMs) / 1000) : 0;
        Intent intent = new Intent(this, SessionSummaryActivity.class);
        intent.putExtra(SessionSummaryActivity.EXTRA_SWING_COUNT, swingCount);
        intent.putExtra(SessionSummaryActivity.EXTRA_DURATION_SEC, durationSec);
        intent.putExtra(SessionSummaryActivity.EXTRA_FATIGUE_LEVEL, lastFatigueLevel);
        intent.putExtra(SessionSummaryActivity.EXTRA_MDF_DROP_PCT,
                Double.isNaN(lastMdfDropPct) ? -1f : (float) lastMdfDropPct);
        startActivity(intent);
    }

    private void startWorkerThreads() {
        logThread = new Thread(() -> {
            try {
                while (recording || !qLog.isEmpty()) {
                    byte[] frame = qLog.poll();
                    if (frame == null) { try { Thread.sleep(2); } catch (InterruptedException ignored) {} continue; }
                    writeFrameRecord(frame);
                }
            } catch (Exception e) {
                Log.e(TAG, "logThread", e);
            }
        }, "logThread");
        logThread.start();

        plotThread = new Thread(() -> {
            try {
                while (recording || !qPlot.isEmpty()) {
                    byte[] frame = qPlot.poll();
                    if (frame == null) { try { Thread.sleep(2); } catch (InterruptedException ignored) {} continue; }
                    decodeForPlot(frame);
                }
            } catch (Exception e) {
                Log.e(TAG, "plotThread", e);
            }
        }, "plotThread");
        plotThread.start();
    }

    private void stopWorkerThreads() {
        try { if (logThread != null) logThread.join(200); } catch (Exception ignored) {}
        try { if (plotThread != null) plotThread.join(200); } catch (Exception ignored) {}
        logThread = null;
        plotThread = null;
        qLog.clear();
        qPlot.clear();
    }

    // ── Fatigue thread ──────────────────────────────────────────────────────

    private void startFatigueThread() {
        fatigueRunning = true;
        fatigueThread = new Thread(() -> {
            // Ensure Python is started before entering the loop
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(SessionCaptureActivity.this));
            } catch (Exception e) {
                Log.w(TAG, "fatigueThread: Python.start failed", e);
            }
            while (fatigueRunning) {
                FatigueJob job;
                try {
                    job = fatigueQueue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (job == null) continue;
                try {
                    // Build JSON array string from raw EMG samples
                    StringBuilder sb = new StringBuilder(job.emg.length * 6 + 2);
                    sb.append('[');
                    for (int i = 0; i < job.emg.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append((int) job.emg[i]); // raw ADC values are integers
                    }
                    sb.append(']');
                    String emgJson = sb.toString();

                    Python py = Python.getInstance();
                    PyObject mod = py.getModule("analysis_bridge");
                    String result = mod.callAttr(
                            "push_swing_realtime", emgJson, job.tPeakMs, job.imuPeakMag
                    ).toString();

                    final String display = parseFatigueDisplay(result);
                    ui.post(() -> txtFatigue.setText(display));
                } catch (Exception e) {
                    Log.w(TAG, "fatigueThread: push_swing_realtime failed", e);
                }
            }
        }, "fatigueThread");
        fatigueThread.start();
    }

    private void stopFatigueThread() {
        fatigueRunning = false;
        if (fatigueThread != null) {
            fatigueThread.interrupt();
            try { fatigueThread.join(500); } catch (InterruptedException ignored) {}
            fatigueThread = null;
        }
        fatigueQueue.clear();
    }

    /**
     * Parse the JSON returned by push_swing_realtime and format a one-line display string.
     *
     * Key fields used:
     *   swing_id (int), mdf (float|null), mdf_drop_pct (float|null),
     *   fatigue_level (str), warning (bool), baseline_ready (bool),
     *   baseline_swings_collected (int)
     */
    private String parseFatigueDisplay(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("error")) return "Fatigue: " + obj.getString("error");

            int swingId = obj.optInt("swing_id", 0);
            double mdf = obj.isNull("mdf") ? Double.NaN : obj.optDouble("mdf", Double.NaN);
            boolean baselineReady = obj.optBoolean("baseline_ready", false);
            int baselineCollected = obj.optInt("baseline_swings_collected", 0);
            String level = obj.optString("fatigue_level", "");
            boolean warning = obj.optBoolean("warning", false);
            double mdfDropPct = obj.isNull("mdf_drop_pct") ? Double.NaN
                    : obj.optDouble("mdf_drop_pct", Double.NaN);

            String mdfStr = Double.isNaN(mdf) ? "?" : String.format(Locale.US, "%.1f", mdf);

            if (!baselineReady) {
                return String.format(Locale.US,
                        "Swing #%d | MDF=%s Hz [baseline %d/20]",
                        swingId, mdfStr, baselineCollected);
            } else {
                lastFatigueLevel = level;
                lastMdfDropPct = mdfDropPct;
                String dropStr = Double.isNaN(mdfDropPct) ? "?"
                        : String.format(Locale.US, "%.1f%%", mdfDropPct);
                String warn = warning ? " (!)" : "";
                return String.format(Locale.US,
                        "Swing #%d | MDF=%s Hz | Drop=%s | %s%s",
                        swingId, mdfStr, dropStr, level.toUpperCase(Locale.US), warn);
            }
        } catch (Exception e) {
            return "Fatigue: " + json;
        }
    }

    // ───────────────────────────────────────────────────────────────────────

    // ── Demo playback ────────────────────────────────────────────────────────

    private void startDemoPlayback() {
        demoRunning = true;
        demoSwingCount = 0;
        lastFatigueLevel = "unknown";
        lastMdfDropPct = Double.NaN;
        sessionStartWallMs = System.currentTimeMillis();
        // Reset Python fatigue monitor before playback begins
        new Thread(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(SessionCaptureActivity.this));
                // warmup_skip_min=0: demo asset already starts after electrode settling
                // baseline_swings=5: sufficient given ~42 swings; some have low RMS
                Python.getInstance().getModule("analysis_bridge").callAttr(
                        "reset_fatigue_monitor", 2000.0, 0.0, 5, 12.0, 20.0);
                ui.post(() -> txtFatigue.setText("Fatigue: monitoring..."));
                Log.i(TAG, "demo: reset_fatigue_monitor OK (warmup=0, baseline=5)");
            } catch (Exception e) {
                Log.w(TAG, "demo: reset_fatigue_monitor failed", e);
                // Retry without Python.start — it may already be running
                try {
                    Python.getInstance().getModule("analysis_bridge").callAttr(
                            "reset_fatigue_monitor", 2000.0, 0.0, 5, 12.0, 20.0);
                    ui.post(() -> txtFatigue.setText("Fatigue: monitoring..."));
                    Log.i(TAG, "demo: reset_fatigue_monitor OK on retry");
                } catch (Exception e2) {
                    Log.e(TAG, "demo: reset_fatigue_monitor retry also failed", e2);
                }
            }
        }, "demoFatigueReset").start();

        demoThread = new Thread(() -> {
            try {
                // Load both asset files fully into memory before playback
                int[] emgT_ms; short[] emgRaw; short[] emgEnv;
                int[] imuT_ms; short[] iAx, iAy, iAz, iGx, iGy, iGz;

                try (DataInputStream dis = new DataInputStream(new BufferedInputStream(
                        getAssets().open("demo_emg.bin"), 65536))) {
                    int magic = readInt32LE(dis);
                    if (magic != DEMO_EMG_MAGIC) throw new IOException("Bad EMG magic: " + magic);
                    int n = readInt32LE(dis);
                    emgT_ms = new int[n]; emgRaw = new short[n]; emgEnv = new short[n];
                    for (int i = 0; i < n; i++) {
                        emgT_ms[i] = readInt32LE(dis);
                        emgRaw[i]  = readInt16LE(dis);
                        emgEnv[i]  = readInt16LE(dis);
                    }
                    Log.i(TAG, "Demo EMG loaded: " + n + " samples");
                }

                try (DataInputStream dis = new DataInputStream(new BufferedInputStream(
                        getAssets().open("demo_imu.bin"), 65536))) {
                    int magic = readInt32LE(dis);
                    if (magic != DEMO_IMU_MAGIC) throw new IOException("Bad IMU magic: " + magic);
                    int n = readInt32LE(dis);
                    imuT_ms = new int[n];
                    iAx = new short[n]; iAy = new short[n]; iAz = new short[n];
                    iGx = new short[n]; iGy = new short[n]; iGz = new short[n];
                    for (int i = 0; i < n; i++) {
                        imuT_ms[i] = readInt32LE(dis);
                        iAx[i] = readInt16LE(dis); iAy[i] = readInt16LE(dis); iAz[i] = readInt16LE(dis);
                        iGx[i] = readInt16LE(dis); iGy[i] = readInt16LE(dis); iGz[i] = readInt16LE(dis);
                    }
                    Log.i(TAG, "Demo IMU loaded: " + n + " samples");
                }

                // Merge-playback: walk both streams in t_ms order
                // EMG at 2000 Hz, IMU at 200 Hz — interleave by timestamp
                final float ACCEL_SCALE_INV = 1.0f / 1000.0f; // milli-g → g
                final float GYRO_SCALE_INV  = 1.0f / 10.0f;   // 0.1 dps → dps

                int ei = 0, ii = 0;
                int nEmg = emgT_ms.length, nImu = imuT_ms.length;
                long wallStart = System.currentTimeMillis();
                long dataStart = Math.min(emgT_ms[0], imuT_ms[0]); // both start ~0

                t0SessionUs = 0;

                while (demoRunning && (ei < nEmg || ii < nImu)) {
                    // Pick whichever stream has the earlier timestamp
                    boolean doEmg = (ii >= nImu) ||
                            (ei < nEmg && emgT_ms[ei] <= imuT_ms[ii]);

                    if (doEmg) {
                        float tSec = emgT_ms[ei] / 1000.0f;
                        float raw  = emgRaw[ei];
                        float env  = emgEnv[ei];

                        emgFatigueBuf.add(tSec, raw);
                        emgRawBuf.add(tSec, raw);
                        emgEnvBuf.add(tSec, env);
                        rawDcLpf = rawDcLpf + rawDcAlpha * (raw - rawDcLpf);
                        float x = Math.abs(raw - rawDcLpf);
                        envLpf = envLpf + envAlpha * (x - envLpf);
                        emgFiltBuf.add(tSec, envLpf);
                        ei++;
                    } else {
                        float tSec = imuT_ms[ii] / 1000.0f;
                        float ax = iAx[ii] * ACCEL_SCALE_INV;
                        float ay = iAy[ii] * ACCEL_SCALE_INV;
                        float az = iAz[ii] * ACCEL_SCALE_INV;
                        float gxDps = iGx[ii] * GYRO_SCALE_INV;
                        float gyDps = iGy[ii] * GYRO_SCALE_INV;
                        float gzDps = iGz[ii] * GYRO_SCALE_INV;

                        float amag = (float) Math.sqrt(ax*ax + ay*ay + az*az) * 8192f; // back to ~LSB scale for plot
                        float gmag_dps = (float) Math.sqrt(gxDps*gxDps + gyDps*gyDps + gzDps*gzDps);
                        imuBuf.add(tSec, amag);

                        // Swing peak detection (mirrors live path)
                        long devUs = (long)(tSec * 1e6f);
                        if (gmag_dps >= imuThreshold) {
                            if (!inPeak) {
                                inPeak = true; peakGyroMag = gmag_dps; peakTimeUs = devUs;
                            } else if (gmag_dps > peakGyroMag) {
                                peakGyroMag = gmag_dps; peakTimeUs = devUs;
                            }
                        } else if (inPeak) {
                            if ((peakTimeUs - lastMarkerUs) > MARKER_DEBOUNCE_US) {
                                float peakTSec = peakTimeUs / 1e6f;
                                markers.add(peakTSec);
                                demoSwingCount++;
                                lastMarkerUs = peakTimeUs;
                                float[] emgSnap = emgFatigueBuf.snapshotY();
                                if (emgSnap.length > 0)
                                    fatigueQueue.offer(new FatigueJob(emgSnap, peakTSec * 1000f, peakGyroMag));
                            }
                            inPeak = false; peakGyroMag = 0f;
                        }
                        lastGyroMag = gmag_dps;
                        ii++;
                    }

                    // Pace playback: sleep to match real-time data rate / DEMO_SPEED
                    // Check every 200 EMG samples (~0.1s of data) to avoid constant sleep overhead
                    if (doEmg && (ei % 200) == 0) {
                        long dataElapsedMs = (ei < nEmg ? emgT_ms[ei] : emgT_ms[nEmg-1]) - dataStart;
                        long wallElapsedMs = System.currentTimeMillis() - wallStart;
                        long targetWallMs  = (long)(dataElapsedMs / DEMO_SPEED);
                        long sleepMs = targetWallMs - wallElapsedMs;
                        if (sleepMs > 2) {
                            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { break; }
                        }
                    }
                }

                if (demoRunning) {
                    // Reached EOF naturally — wait for fatigueQueue to drain before showing summary
                    final int swings = demoSwingCount;
                    ui.post(() -> {
                        txtStatus.setText("Demo complete — " + swings + " swings detected");
                        txtDemoBadge.setText("● DEMO COMPLETE");
                        btnStop.setEnabled(false);
                    });
                    // Poll until queue empty (max 10s) then launch summary on UI thread
                    new Thread(() -> {
                        long deadline = System.currentTimeMillis() + 10000;
                        while (System.currentTimeMillis() < deadline) {
                            if (fatigueQueue.isEmpty()) break;
                            try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
                        }
                        ui.post(() -> launchSummary(swings));
                    }, "demoSummaryWait").start();
                }
            } catch (Exception e) {
                Log.e(TAG, "demoThread error", e);
                ui.post(() -> txtStatus.setText("Demo error: " + e.getMessage()));
            }
        }, "demoThread");
        demoThread.start();
    }

    private void stopDemoPlayback() {
        demoRunning = false;
        if (demoThread != null) {
            demoThread.interrupt();
            try { demoThread.join(500); } catch (InterruptedException ignored) {}
            demoThread = null;
        }
        ui.post(() -> {
            txtDemoBadge.setVisibility(android.view.View.GONE);
            btnStop.setEnabled(false);
            txtStatus.setText("Demo stopped");
        });
    }

    private static int readInt32LE(DataInputStream dis) throws IOException {
        int b0 = dis.read(), b1 = dis.read(), b2 = dis.read(), b3 = dis.read();
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }

    private static short readInt16LE(DataInputStream dis) throws IOException {
        int b0 = dis.read(), b1 = dis.read();
        return (short)((b0 & 0xFF) | ((b1 & 0xFF) << 8));
    }

    // ────────────────────────────────────────────────────────────────────────

    private void startPingTimer() {
        stopPingTimer();
        pingTimer = new Timer("pingTimer");
        pingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                if (!recording) return;
                sendPing();
            }
        }, 0, PING_PERIOD_MS);
    }

    private void stopPingTimer() {
        if (pingTimer != null) {
            pingTimer.cancel();
            pingTimer = null;
        }
    }

    private void sendPing() {
        final byte[] ping = "PING".getBytes();
        BleManager.getInstance().write(
                bleDevice,
                NUS_SERVICE_UUID,
                NUS_RX_CHAR_UUID,
                ping,
                new BleWriteCallback() {
                    @Override public void onWriteSuccess(int current, int total, byte[] justWrite) {}
                    @Override public void onWriteFailure(BleException exception) { Log.w(TAG, "PING failed: " + exception); }
                }
        );
    }

    private void createSessionFiles() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        
        // Find most recent frames_*.bin file across all session directories
        File mostRecentFile = findMostRecentFramesFile(root);
        
        if (mostRecentFile != null) {
            // Parse timestamp from filename: frames_20260228_051234.bin
            long lastTimestamp = parseTimestampFromFilename(mostRecentFile.getName());
            long currentTimestamp = parseTimestamp(ts);
            long gapMinutes = (currentTimestamp - lastTimestamp) / 60000;
            
            if (gapMinutes < 30) {
                // Continue existing session (gap < 30 min)
                sessionDir = mostRecentFile.getParentFile();
                runOnUiThread(() -> txtStatus.setText("Continuing session (gap: " + gapMinutes + " min)..."));
            } else {
                // Start new session (gap >= 30 min)
                sessionDir = new File(root, "session_" + ts);
                runOnUiThread(() -> txtStatus.setText("Starting new session (gap: " + gapMinutes + " min)..."));
            }
        } else {
            // First capture ever - create new session
            sessionDir = new File(root, "session_" + ts);
            runOnUiThread(() -> txtStatus.setText("Starting new session (first capture)..."));
        }
        
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            throw new IOException("mkdir failed: " + sessionDir);
        }
        
        // Timestamped filename (like Windows!)
        framesFile = new File(sessionDir, "frames_" + ts + ".bin");
        bytesWritten = 0;
        framesWritten = 0;
        t0SessionUs = -1;
        wrapCount = 0;
        lastTFrameU32 = -1;
        lastMarkerUs = 0;
    }
    
    private File findMostRecentFramesFile(File root) {
        File[] sessionDirs = root.listFiles(f -> f.isDirectory() && f.getName().startsWith("session_"));
        if (sessionDirs == null || sessionDirs.length == 0) return null;
        
        File mostRecent = null;
        long mostRecentTime = 0;
        
        for (File dir : sessionDirs) {
            File[] framesFiles = dir.listFiles(f -> f.isFile() && f.getName().startsWith("frames_") && f.getName().endsWith(".bin"));
            if (framesFiles == null) continue;
            
            for (File f : framesFiles) {
                long timestamp = parseTimestampFromFilename(f.getName());
                if (timestamp > mostRecentTime) {
                    mostRecentTime = timestamp;
                    mostRecent = f;
                }
            }
        }
        
        return mostRecent;
    }
    
    private long parseTimestampFromFilename(String filename) {
        // Extract timestamp from "frames_20260228_051234.bin"
        try {
            String ts = filename.replace("frames_", "").replace(".bin", "");
            return parseTimestamp(ts);
        } catch (Exception e) {
            return 0;
        }
    }
    
    private long parseTimestamp(String ts) {
        // Parse "20260228_051234" to milliseconds since epoch
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            Date date = sdf.parse(ts);
            return date != null ? date.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void openOutput() throws IOException {
        out = new BufferedOutputStream(new FileOutputStream(framesFile, true), 64 * 1024);
    }

    private void writeFrameRecord(byte[] frame) throws IOException {
        if (out == null) return;
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(frame.length);
        out.write(bb.array());
        out.write(frame);
        bytesWritten += (4L + frame.length);
        framesWritten++;
        if ((framesWritten % 50) == 0) runOnUiThread(this::updateStats);
    }

    private void updateStats() {
        txtStats.setText("frames=" + framesWritten + " bytes=" + bytesWritten +
                " RAW=" + rawPlotHz + "Hz window=" + (int)windowSec + "s freeze=" + (frozen ? 1 : 0));
    }

    private void writeMetaJson() throws IOException {
        if (sessionDir == null) return;
        File meta = new File(sessionDir, "meta.json");

        // List all frames_*.bin files in the session directory
        File[] framesFiles = sessionDir.listFiles(f -> f.isFile() && 
                f.getName().startsWith("frames_") && f.getName().endsWith(".bin"));
        
        StringBuilder framesListJson = new StringBuilder();
        framesListJson.append("  \"frames_files\": [");
        if (framesFiles != null && framesFiles.length > 0) {
            for (int i = 0; i < framesFiles.length; i++) {
                if (i > 0) framesListJson.append(", ");
                framesListJson.append("\"").append(framesFiles[i].getName()).append("\"");
            }
        }
        framesListJson.append("],\n");

        String json =
                "{\n" +
                        "  \"device_name\": \"" + (bleDevice.getName() != null ? bleDevice.getName() : "") + "\",\n" +
                        "  \"mac\": \"" + bleDevice.getMac() + "\",\n" +
                        framesListJson.toString() +
                        "  \"frames_written\": " + framesWritten + ",\n" +
                        "  \"bytes_written\": " + bytesWritten + "\n" +
                        "}\n";

        try (FileOutputStream fos = new FileOutputStream(meta, false)) {
            fos.write(json.getBytes());
        }
    }

    private long extendDeviceUs(long tU32) {
        long t = tU32 & 0xFFFFFFFFL;
        if (lastTFrameU32 < 0) {
            lastTFrameU32 = t;
            return t;
        }
        if (t < lastTFrameU32 && (lastTFrameU32 - t) > 0x40000000L) {
            wrapCount++;
        }
        lastTFrameU32 = t;
        return (wrapCount << 32) + t;
    }

    private void decodeForPlot(byte[] frame) {
        if (frame == null || frame.length < HEADER_LEN + CRC_LEN) return;
        if (frame[0] != MAGIC0 || frame[1] != MAGIC1) return;
        int flags = frame[3] & 0xFF;

        long tFrameUs = extendDeviceUs(u32le(frame, 8));
        if (t0SessionUs < 0) t0SessionUs = tFrameUs;

        int payloadLen = u16le(frame, 12);
        int totalLen = HEADER_LEN + payloadLen + CRC_LEN;
        if (payloadLen < 0 || totalLen > frame.length) return;

        if ((flags & 0x01) != 0) {
            int rxCrc = u16le(frame, totalLen - 2);
            int calc = crc16_ccitt(frame, 0, totalLen - 2);
            if (((calc) & 0xFFFF) != rxCrc) return;
        }

        int payloadOff = HEADER_LEN;
        int off = 0;
        while (off + 3 <= payloadLen) {
            int tlvType = frame[payloadOff + off] & 0xFF;
            int tlvLen = u16le(frame, payloadOff + off + 1);
            off += 3;
            if (off + tlvLen > payloadLen) break;
            int valOff = payloadOff + off;
            if (tlvType == TLV_EMG_BATCH) decodeEmgBatch(frame, valOff, tlvLen, tFrameUs);
            else if (tlvType == TLV_IMU_BATCH) decodeImuBatch(frame, valOff, tlvLen, tFrameUs);
            else if (tlvType == TLV_VITALS) decodeVitals(frame, valOff, tlvLen);
            off += tlvLen;
        }
    }

    private void decodeVitals(byte[] buf, int valOff, int valLen) {
        if (valLen < 11) return; // Vitals payload is 11 bytes
        // spo2(2) hr(2) age(2) flags(1) bright(1) batt_pct(1) batt_mv(2)
        
        final int spo2_u16 = u16le(buf, valOff);      // Offset 0-1
        final int hr_x10_u16 = u16le(buf, valOff + 2); // Offset 2-3
        final int age_ms = u16le(buf, valOff + 4);     // Offset 4-5
        final int flags = buf[valOff + 6] & 0xFF;      // Offset 6
        final int batt_pct = buf[valOff + 8] & 0xFF;   // Offset 8
        final int batt_mv = u16le(buf, valOff + 9);    // Offset 9-10
        
        final boolean spo2Valid = (flags & 0x01) != 0;
        final boolean hrValid = (flags & 0x02) != 0;

        runOnUiThread(() -> {
            // Update SpO2 display
            if (spo2Valid && spo2_u16 <= 100) {
                txtSpo2.setText("SpO2: " + spo2_u16 + "%");
            } else {
                txtSpo2.setText("SpO2: N/A");
            }
            
            // Update Heart Rate display
            if (hrValid && hr_x10_u16 < 0xFFFF) {
                float hr_bpm = hr_x10_u16 / 10.0f;
                txtHeartRate.setText(String.format(Locale.US, "HR: %.1f bpm", hr_bpm));
            } else {
                txtHeartRate.setText("HR: N/A");
            }
            
            // Update Battery display
            if (batt_pct <= 100) {
                txtBattery.setText("Battery: " + batt_pct + "%");
            } else {
                txtBattery.setText("Battery: N/A");
            }
        });
    }

    private void decodeEmgBatch(byte[] buf, int valOff, int valLen, long tFrameUs) {
        if (valLen < 13) return;
        long seq0 = u32le(buf, valOff);
        short t0Off = (short)u16le(buf, valOff + 4);
        int srHz = u16le(buf, valOff + 6);
        int ch = buf[valOff + 8] & 0xFF;
        int count = u16le(buf, valOff + 11);
        int sampOff = valOff + 13;
        int bytesPerSample = ch * 2;
        if (count <= 0 || bytesPerSample <= 0) return;
        if (sampOff + count * bytesPerSample > valOff + valLen) return;

        long t0Us = tFrameUs + t0Off;
        float stepUs = (srHz > 0) ? (1_000_000f / (float)srHz) : 0f;
        int rawDecim = Math.max(1, srHz / rawPlotHz);
        int envDecim = Math.max(1, srHz / ENV_PLOT_HZ);

        for (int i = 0; i < count; i++) {
            long devUs = (long)(t0Us + i * stepUs);
            float tSec = (devUs - t0SessionUs) / 1_000_000f;

            int base = sampOff + i * bytesPerSample;
            int env = u16le(buf, base);
            int raw = (ch >= 2) ? u16le(buf, base + 2) : 0;

            // Push every raw sample into the fatigue ring buffer at full rate (2000 Hz).
            // This must happen before any decimation so the MDF window is complete.
            emgFatigueBuf.add(tSec, (float)raw);

            long seq = seq0 + i;
            if ((seq % rawDecim) == 0) emgRawBuf.add(tSec, (float)raw);
            if ((seq % envDecim) == 0) {
                emgEnvBuf.add(tSec, (float)env);
                // DC offset tracking: slow LPF (0.1 Hz) estimates the true RAW midpoint.
                // MyoWare DC bias shifts center from theoretical 2048 to ~1830–1843.
                // Using the actual center makes the envelope symmetric and correct.
                rawDcLpf = rawDcLpf + rawDcAlpha * ((float)raw - rawDcLpf);
                // Envelope: deviation from the tracked DC center (0 at rest, ~40+ during contraction)
                float x = Math.abs((float)raw - rawDcLpf);
                envLpf = envLpf + envAlpha * (x - envLpf);
                emgFiltBuf.add(tSec, envLpf); // store raw envelope; renderPlots() scales dynamically
            }
        }
    }

    private void decodeImuBatch(byte[] buf, int valOff, int valLen, long tFrameUs) {
        if (valLen < 14) return;
        long seq0 = u32le(buf, valOff);
        short t0Off = (short)u16le(buf, valOff + 4);
        int srHz = u16le(buf, valOff + 6);
        int count = buf[valOff + 8] & 0xFF;
        int metaLen = 14;
        int sampOff = valOff + metaLen;
        int bytesPerSample = 12;
        if (count <= 0) return;
        if (sampOff + count * bytesPerSample > valOff + valLen) return;

        long t0Us = tFrameUs + t0Off;
        float stepUs = (srHz > 0) ? (1_000_000f / (float)srHz) : 0f;
        // NO decimation - plot every IMU sample for smooth waveforms
        // int imuDecim = Math.max(1, srHz / IMU_PLOT_HZ);

        for (int i = 0; i < count; i++) {
            long devUs = (long)(t0Us + i * stepUs);
            float tSec = (devUs - t0SessionUs) / 1_000_000f;

            int base = sampOff + i * bytesPerSample;
            short ax = (short)u16le(buf, base);
            short ay = (short)u16le(buf, base + 2);
            short az = (short)u16le(buf, base + 4);
            short gx = (short)u16le(buf, base + 6);
            short gy = (short)u16le(buf, base + 8);
            short gz = (short)u16le(buf, base + 10);
            
            // Calculate accelerometer magnitude for plotting
            float amag = (float)Math.sqrt((double)ax*ax + (double)ay*ay + (double)az*az);
            
            // Calculate gyroscope magnitude for swing detection
            float gmag_dps = (float)Math.sqrt((double)gx*gx + (double)gy*gy + (double)gz*gz)
                             / GYRO_SCALE_FACTOR;

            // Plot accelerometer magnitude (for visual reference)
            imuBuf.add(tSec, amag);

            detectSwingPeak(tSec, gmag_dps, devUs);
        }
    }

    private void detectSwingPeak(float tSec, float gmag_dps, long devUs) {
        if (gmag_dps >= imuThreshold) {
            if (!inPeak) {
                inPeak = true; peakGyroMag = gmag_dps; peakTimeUs = devUs;
            } else if (gmag_dps > peakGyroMag) {
                peakGyroMag = gmag_dps; peakTimeUs = devUs;
            }
        } else if (inPeak) {
            if ((peakTimeUs - lastMarkerUs) > MARKER_DEBOUNCE_US) {
                float peakTSec = peakTimeUs / 1_000_000f;
                markers.add(peakTSec);
                lastMarkerUs = peakTimeUs;
                float[] emgSnap = emgFatigueBuf.snapshotY();
                if (emgSnap.length > 0)
                    fatigueQueue.offer(new FatigueJob(emgSnap, peakTSec * 1000f, peakGyroMag));
            }
            inPeak = false; peakGyroMag = 0f;
        }
        lastGyroMag = gmag_dps;
    }

    private void renderPlots() {
        float nowSec = frozen ? frozenNowSec : latestNowSec();
        float tMin = Math.max(0f, nowSec - windowSec);
        float tMax = nowSec;

        int nRaw = emgRawBuf.snapshot(tMin, tMax, xs0, ys0);
        int nEnv = emgEnvBuf.snapshot(tMin, tMax, xs1, ys1);
        int nFlt = emgFiltBuf.snapshot(tMin, tMax, xs2, ys2);
        int nImu = imuBuf.snapshot(tMin, tMax, xsi, ysi);
        int nMk = markers.snapshot(tMin, tMax, tmk);

        // ── RAW: normalize to fill 0–4095 (Option F) ─────────────────────
        // Use the tracked DC center (rawDcLpf) instead of hardcoded 2048.
        // This correctly handles MyoWare's DC bias (~1830–1843 instead of 2048).
        // Map: rawDcLpf → 2048 (plot center), peak deviation fills ±2048.
        final float PLOT_CENTER = 2048f;
        final float rawCenter = rawDcLpf; // tracked DC midpoint (converges in ~10s)
        final float RAW_MIN_RANGE = 100f; // min peak deviation — prevents over-zoom on baseline noise
        float rawPeakDev = RAW_MIN_RANGE;
        for (int i = 0; i < nRaw; i++) {
            float dev = Math.abs(ys0[i] - rawCenter);
            if (dev > rawPeakDev) rawPeakDev = dev;
        }
        float rawZoom = PLOT_CENTER / rawPeakDev; // e.g., 2048/40 = 51.2× at rest
        for (int i = 0; i < nRaw; i++) {
            ys0[i] = PLOT_CENTER + (ys0[i] - rawCenter) * rawZoom;
        }

        // ── LPF: scale to match ENV's current window range (Option C) ─────
        // ENV (yellow) is kept at its actual 0–4095 value (absolute scale preserved).
        // LPF (magenta) = LPF(|RAW-2048|), range 0–2047. Scale it so its variation
        // spans the same vertical range as ENV in the current window → easy comparison.
        float envLo = 4095f, envHi = 0f;
        for (int i = 0; i < nEnv; i++) {
            if (ys1[i] < envLo) envLo = ys1[i];
            if (ys1[i] > envHi) envHi = ys1[i];
        }
        if (envHi - envLo < 50f) { envLo = 0f; envHi = 200f; } // fallback when no data yet
        float envRange = envHi - envLo;

        float lpfLo = Float.MAX_VALUE, lpfHi = -Float.MAX_VALUE;
        for (int i = 0; i < nFlt; i++) {
            if (ys2[i] < lpfLo) lpfLo = ys2[i];
            if (ys2[i] > lpfHi) lpfHi = ys2[i];
        }
        float lpfRange = (lpfHi > lpfLo) ? (lpfHi - lpfLo) : 1f;
        if (lpfRange < 10f) lpfRange = 10f; // prevent over-zoom on flat baseline
        float lpfZoom = envRange / lpfRange;
        for (int i = 0; i < nFlt; i++) {
            ys2[i] = envLo + (ys2[i] - lpfLo) * lpfZoom;
        }

        // ── Colored title: each label in the color of its curve ───────────
        emgPlot.setTitleSegments(
            new String[]{
                String.format(Locale.US, "RAW(zoom\u00d7%.1f)", rawZoom),
                " / ",
                "ENV(0-4095)",
                " / ",
                String.format(Locale.US, "LPF(\u00d7%.1f)", lpfZoom)
            },
            new int[]{
                android.graphics.Color.CYAN,
                android.graphics.Color.WHITE,
                android.graphics.Color.YELLOW,
                android.graphics.Color.WHITE,
                android.graphics.Color.MAGENTA
            }
        );
        // ─────────────────────────────────────────────────────────────────

        emgPlot.setViewport(tMin, tMax, 0f, 4095f);
        emgPlot.setSeries0(xs0, ys0, nRaw);
        emgPlot.setSeries1(xs1, ys1, nEnv);
        emgPlot.setSeries2(xs2, ys2, nFlt);
        emgPlot.setMarkers(tmk, nMk);
        emgPlot.invalidate();

        float imuMin, imuMax;
        if (imuScaleAuto) {
            // Auto-scaling: dynamically adjust to data range
            imuMin = 0f; imuMax = 1f;
            if (nImu > 1) {
                imuMin = ysi[0]; imuMax = ysi[0];
                for (int i = 1; i < nImu; i++) {
                    float v = ysi[i];
                    if (v < imuMin) imuMin = v;
                    if (v > imuMax) imuMax = v;
                }
                if (imuMax <= imuMin) imuMax = imuMin + 1f;
                float pad = 0.1f * (imuMax - imuMin);
                imuMin -= pad; imuMax += pad;
            }
        } else {
            // Fixed scaling: use constant range for comparing swing magnitudes
            imuMin = IMU_FIXED_MIN;
            imuMax = IMU_FIXED_MAX;
        }
        imuPlot.setViewport(tMin, tMax, imuMin, imuMax);
        imuPlot.setSeries0(xsi, ysi, nImu);
        imuPlot.setSeries1(null, null, 0);
        imuPlot.setSeries2(null, null, 0);
        imuPlot.setMarkers(tmk, nMk);
        imuPlot.invalidate();

        if ((framesWritten % 100) == 0) updateStats();
    }

    private float latestNowSec() {
        float a = emgRawBuf.latestX();
        float b = imuBuf.latestX();
        return Math.max(a, b);
    }

    /**
     * Inject a TLV_HOST_MS (0x04) TLV into the frame payload.
     *
     * The TLV is prepended to the existing payload so the Python decoder can
     * find it in a pre-scan before processing data TLVs (EMG/IMU/vitals).
     *
     * TLV wire format: type(1 B) + length(2 B LE) + value(8 B LE uint64) = 11 bytes
     *
     * The payload_len field in the 14-byte header is updated to include the new
     * TLV, and the CRC-16/CCITT is recomputed when the CRC flag (flags & 0x01)
     * is set in the frame header.
     *
     * @param frame  original BLE notification bytes (unmodified)
     * @param hostMs System.currentTimeMillis() captured at BLE notification time
     * @return new frame bytes with TLV_HOST_MS prepended to payload;
     *         returns original frame unchanged if it is too short or malformed
     */
    private byte[] injectHostTimestamp(byte[] frame, long hostMs) {
        if (frame.length < HEADER_LEN + CRC_LEN) return frame;

        int origPayloadLen = u16le(frame, 12);
        int origTotalLen   = HEADER_LEN + origPayloadLen + CRC_LEN;
        if (origTotalLen > frame.length) return frame; // malformed — don't touch

        // TLV_HOST_MS: type(1) + len_u16_le(2) + value_u64_le(8) = 11 bytes
        final int TLV_SIZE     = 11;
        int newPayloadLen      = origPayloadLen + TLV_SIZE;
        int newTotalLen        = HEADER_LEN + newPayloadLen + CRC_LEN;

        byte[] out = new byte[newTotalLen];

        // 1. Copy original 14-byte header
        System.arraycopy(frame, 0, out, 0, HEADER_LEN);

        // 2. Update payload_len field (bytes 12-13, little-endian)
        out[12] = (byte)(newPayloadLen & 0xFF);
        out[13] = (byte)((newPayloadLen >> 8) & 0xFF);

        // 3. Write TLV_HOST_MS at the start of the payload
        out[HEADER_LEN + 0] = (byte) TLV_HOST_MS; // type = 0x04
        out[HEADER_LEN + 1] = 8;                   // length low byte  (8 bytes of value)
        out[HEADER_LEN + 2] = 0;                   // length high byte
        ByteBuffer.wrap(out, HEADER_LEN + 3, 8)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .putLong(hostMs);                 // uint64 LE wall-clock ms

        // 4. Copy original payload after the new TLV
        System.arraycopy(frame, HEADER_LEN, out, HEADER_LEN + TLV_SIZE, origPayloadLen);

        // 5. Recompute CRC if the CRC flag (bit 0 of flags byte) is set
        int flags = frame[3] & 0xFF;
        if ((flags & 0x01) != 0) {
            int crc = crc16_ccitt(out, 0, HEADER_LEN + newPayloadLen);
            out[HEADER_LEN + newPayloadLen]     = (byte)(crc & 0xFF);
            out[HEADER_LEN + newPayloadLen + 1] = (byte)((crc >> 8) & 0xFF);
        }

        return out;
    }

    private static int u16le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32le(byte[] b, int off) {
        return ((long)(b[off] & 0xFF)) |
                ((long)(b[off + 1] & 0xFF) << 8) |
                ((long)(b[off + 2] & 0xFF) << 16) |
                ((long)(b[off + 3] & 0xFF) << 24);
    }

    private static int crc16_ccitt(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[off + i] & 0xFF) << 8;
            for (int bb = 0; bb < 8; bb++) {
                if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                else crc = (crc << 1) & 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }

    private static float alphaForCutoff(float cutoffHz, float fsHz) {
        return (float)(1.0 - Math.exp(-2.0 * Math.PI * (double)cutoffHz / (double)fsHz));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(uiTick);
        stopDemoPlayback();
        stopFatigueThread();
        stopDemoPlayback();
        stopWatchdog();
        cancelReconnect();
        stopSession();
    }

    // ── Auto-reconnect ──────────────────────────────────────────────────────

    /** Start the stall-detection watchdog. Fires every WATCHDOG_INTERVAL_MS on the main thread. */
    private void startWatchdog() {
        stopWatchdog();
        lastNotifyMs = System.currentTimeMillis();
        watchdogRunnable = new Runnable() {
            @Override public void run() {
                if (!recording) return;
                long age = System.currentTimeMillis() - lastNotifyMs;
                boolean connected = BleManager.getInstance().isConnected(bleDevice);
                if (!connected || age > STALL_RECONNECT_MS) {
                    String reason = connected ? ("stall_" + age + "ms") : "disconnect";
                    triggerReconnect(reason);
                    return; // don't re-post; triggerReconnect takes over
                }
                ui.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        ui.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    private void stopWatchdog() {
        if (watchdogRunnable != null) {
            ui.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
    }

    private void cancelReconnect() {
        if (reconnectRunnable != null) {
            ui.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    /**
     * Mirrors btCentral.py reconnect trigger:
     * - stops ping + watchdog
     * - clears stale BLE queues (= reset FrameParser)
     * - resets device clock state (= reset DeviceClockExtender)
     * - opens a new frames_*.bin segment in the SAME sessionDir (same tennis session)
     * - schedules reconnect with exponential backoff
     */
    private void triggerReconnect(String reason) {
        if (!recording) return;
        Log.w(TAG, "triggerReconnect: reason=" + reason + " attempt=" + (reconnectCount + 1));

        stopPingTimer();
        stopWatchdog();
        cancelReconnect();
        try { BleManager.getInstance().stopNotify(bleDevice, NUS_SERVICE_UUID, NUS_TX_CHAR_UUID); } catch (Exception ignored) {}

        // Clear stale BLE data (mirrors btCentral.py: clear queue + reset FrameParser)
        qLog.clear();
        qPlot.clear();

        // Reset device clock state (mirrors btCentral.py: reset DeviceClockExtender + last_frame_seq)
        wrapCount = 0;
        lastTFrameU32 = -1;
        t0SessionUs = -1;

        // Open a new frames segment in the SAME sessionDir (same tennis session).
        // Multiple frames_*.bin files in one session dir is intentional — writeMetaJson()
        // already lists all of them automatically.
        if (sessionDir != null) {
            try {
                if (out != null) { out.flush(); out.close(); out = null; }
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                framesFile = new File(sessionDir, "frames_" + ts + ".bin");
                openOutput();
                Log.i(TAG, "triggerReconnect: new segment -> " + framesFile.getName());
            } catch (Exception e) {
                Log.e(TAG, "triggerReconnect: failed to open new frames file", e);
            }
        }

        reconnectCount++;
        scheduleReconnect();
    }

    /**
     * Schedules a single reconnect attempt after exponential backoff.
     * On success: resets backoff, re-subscribes notify + restarts watchdog.
     * On failure: doubles backoff (cap 20s), retries.
     */
    private void scheduleReconnect() {
        if (!recording) return;

        final int backoff = reconnectBackoffMs;
        reconnectBackoffMs = Math.min(MAX_RECONNECT_BACKOFF_MS, reconnectBackoffMs * 2);

        runOnUiThread(() -> txtStatus.setText(
                "Reconnecting... (attempt " + reconnectCount + ", wait " + (backoff / 1000f) + "s)"));

        reconnectRunnable = () -> {
            if (!recording) return;
            runOnUiThread(() -> txtStatus.setText("Reconnecting... (attempt " + reconnectCount + ")"));

            BleManager.getInstance().connect(bleDevice, new BleGattCallback() {
                @Override public void onStartConnect() {}

                @Override public void onConnectFail(BleDevice device, BleException exception) {
                    Log.w(TAG, "Reconnect attempt " + reconnectCount + " failed: " + exception);
                    reconnectCount++;
                    scheduleReconnect();
                }

                @Override public void onConnectSuccess(BleDevice device, BluetoothGatt gatt, int status) {
                    Log.i(TAG, "Reconnected after " + reconnectCount + " attempt(s)");
                    reconnectBackoffMs = 500; // reset backoff on success

                    BleManager.getInstance().setMtu(bleDevice, 247, new BleMtuChangedCallback() {
                        @Override public void onMtuChanged(int mtu) {
                            runOnUiThread(() -> txtStatus.setText(
                                    "Reconnected! MTU=" + mtu + " (recon#" + reconnectCount + ")"));
                            subscribeNotifyAndStartPing();
                        }
                        @Override public void onSetMTUFailure(BleException exception) {
                            runOnUiThread(() -> txtStatus.setText(
                                    "Reconnected! (MTU fail, recon#" + reconnectCount + ")"));
                            subscribeNotifyAndStartPing();
                        }
                    });
                }

                @Override public void onDisConnected(boolean isActiveDisConnected, BleDevice device,
                                                     BluetoothGatt gatt, int status) {
                    // Watchdog will detect this within WATCHDOG_INTERVAL_MS and trigger another cycle
                    Log.w(TAG, "onDisConnected during reconnect, isActive=" + isActiveDisConnected);
                }
            });
        };

        ui.postDelayed(reconnectRunnable, backoff);
    }

    // ───────────────────────────────────────────────────────────────────────

    // ── Inner classes ───────────────────────────────────────────────────────

    /** Carries one swing's EMG snapshot plus timing/magnitude metadata to fatigueThread. */
    private static class FatigueJob {
        final float[] emg;
        final float tPeakMs;
        final float imuPeakMag;

        FatigueJob(float[] emg, float tPeakMs, float imuPeakMag) {
            this.emg = emg;
            this.tPeakMs = tPeakMs;
            this.imuPeakMag = imuPeakMag;
        }
    }
}
