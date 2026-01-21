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

import com.clj.blesample.R;
import com.clj.blesample.analysis.ProcessingActivity;
import com.clj.blesample.views.MarkerBufferF;
import com.clj.blesample.views.RingBufferF;
import com.clj.blesample.views.SimplePlotView;
import com.clj.fastble.BleManager;
import com.clj.fastble.callback.BleNotifyCallback;
import com.clj.fastble.callback.BleWriteCallback;
import com.clj.fastble.data.BleDevice;
import com.clj.fastble.exception.BleException;

import java.io.BufferedOutputStream;
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

    private static final int ENV_PLOT_HZ = 50;
    private volatile int rawPlotHz = 500;

    private TextView txtStatus;
    private TextView txtStats;
    private Button btnStart;
    private Button btnStop;
    private Button btnProcessSession;
    private Switch swFreeze;
    private Button btnWindow;
    private Button btnRawHz;
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
    private final RingBufferF imuBuf = new RingBufferF(3000);
    private final MarkerBufferF markers = new MarkerBufferF(500);

    private final float[] xs0 = new float[12000];
    private final float[] ys0 = new float[12000];
    private final float[] xs1 = new float[2000];
    private final float[] ys1 = new float[2000];
    private final float[] xs2 = new float[2000];
    private final float[] ys2 = new float[2000];
    private final float[] xsi = new float[3000];
    private final float[] ysi = new float[3000];
    private final float[] tmk = new float[500];

    private volatile long t0SessionUs = -1;
    private long wrapCount = 0;
    private long lastTFrameU32 = -1;
    private volatile float frozenNowSec = 0f;
    private volatile boolean frozen = false;
    private volatile float windowSec = 10f;

    private volatile float imuThreshold = 20000f;
    private volatile long lastMarkerUs = 0;
    private static final long MARKER_DEBOUNCE_US = 300_000;

    private float envLpf = 0f;
    private float envAlpha = alphaForCutoff(3.6f, ENV_PLOT_HZ);

    private final Handler ui = new Handler(Looper.getMainLooper());
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
        txtStats = findViewById(R.id.txtStats);
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

        emgPlot.setTitle("EMG (RAW / ENV / LPF(abs(RAW)))");
        imuPlot.setTitle("IMU acc magnitude + markers");


        if (Build.VERSION.SDK_INT >= 33) { // API 33+
            //bleDevice = getIntent().getParcelableExtra("bleDevice", BleDevice.class);
            bleDevice = getIntent().getParcelableExtra("bleDevice");
        } else {
            bleDevice = getIntent().getParcelableExtra("bleDevice");
        }


        if (bleDevice == null) {
            txtStatus.setText("No BLE device. Connect first.");
            btnStart.setEnabled(false);
            btnStop.setEnabled(false);
            btnProcessSession.setEnabled(false);
            return;
        }

        btnStart.setOnClickListener(v -> startSession());
        btnStop.setOnClickListener(v -> stopSession());
        btnStop.setEnabled(false);

        btnProcessSession.setEnabled(false);
        btnProcessSession.setOnClickListener(v -> launchProcessThisSession());

        swFreeze.setOnCheckedChangeListener((CompoundButton b, boolean isChecked) -> {
            frozen = isChecked;
            if (frozen) frozenNowSec = latestNowSec();
        });

        btnWindow.setOnClickListener(v -> {
            windowSec = (windowSec < 15f) ? 20f : 10f;
            btnWindow.setText("Window: " + (int)windowSec + "s");
            if (frozen) frozenNowSec = latestNowSec();
        });
        btnWindow.setText("Window: 10s");

        btnRawHz.setOnClickListener(v -> {
            rawPlotHz = (rawPlotHz == 500) ? 250 : 500;
            btnRawHz.setText("RAW: " + rawPlotHz + "Hz");
        });
        btnRawHz.setText("RAW: 500Hz");

        final int thrMin = 5000;
        final int thrMax = 80000;
        seekImuThr.setMax(thrMax - thrMin);
        seekImuThr.setProgress((int)(imuThreshold - thrMin));
        txtImuThr.setText("IMU marker threshold: " + (int)imuThreshold);
        seekImuThr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                imuThreshold = thrMin + progress;
                txtImuThr.setText("IMU marker threshold: " + (int)imuThreshold);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        txtStatus.setText("Ready. Connected=" + BleManager.getInstance().isConnected(bleDevice));
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
        btnProcessSession.setEnabled(false);
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        txtStatus.setText("Subscribing notify...");
        startWorkerThreads();

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
                        byte[] a = data.clone();
                        byte[] b = data.clone();
                        qLog.offer(a);
                        qPlot.offer(b);
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
        String sessionId = "session_" + ts;
        File root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        sessionDir = new File(root, sessionId);
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            throw new IOException("mkdir failed: " + sessionDir);
        }
        framesFile = new File(sessionDir, "frames.bin");
        bytesWritten = 0;
        framesWritten = 0;
        t0SessionUs = -1;
        wrapCount = 0;
        lastTFrameU32 = -1;
        lastMarkerUs = 0;
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
                "RAW=" + rawPlotHz + "Hz window=" + (int)windowSec + "s freeze=" + (frozen ? 1 : 0));
    }

    private void writeMetaJson() throws IOException {
        if (sessionDir == null) return;
        File meta = new File(sessionDir, "meta.json");

        String json =
                "{\n" +
                        "  \"device_name\": \"" + (bleDevice.getName() != null ? bleDevice.getName() : "") + "\",\n" +
                        "  \"mac\": \"" + bleDevice.getMac() + "\",\n" +
                        "  \"frames_file\": \"frames.bin\",\n" +
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
            off += tlvLen;
        }
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

            long seq = seq0 + i;
            if ((seq % rawDecim) == 0) emgRawBuf.add(tSec, (float)raw);
            if ((seq % envDecim) == 0) {
                emgEnvBuf.add(tSec, (float)env);
                float x = Math.abs((float)raw);
                envLpf = envLpf + envAlpha * (x - envLpf);
                emgFiltBuf.add(tSec, envLpf);
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
        int imuDecim = Math.max(1, srHz / ENV_PLOT_HZ);

        for (int i = 0; i < count; i++) {
            long devUs = (long)(t0Us + i * stepUs);
            float tSec = (devUs - t0SessionUs) / 1_000_000f;

            int base = sampOff + i * bytesPerSample;
            short ax = (short)u16le(buf, base);
            short ay = (short)u16le(buf, base + 2);
            short az = (short)u16le(buf, base + 4);
            float amag = (float)Math.sqrt((double)ax*ax + (double)ay*ay + (double)az*az);

            long seq = seq0 + i;
            if ((seq % imuDecim) == 0) {
                imuBuf.add(tSec, amag);
                if (amag >= imuThreshold && (devUs - lastMarkerUs) > MARKER_DEBOUNCE_US) {
                    markers.add(tSec);
                    lastMarkerUs = devUs;
                }
            }
        }
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

        emgPlot.setViewport(tMin, tMax, 0f, 4095f);
        emgPlot.setSeries0(xs0, ys0, nRaw);
        emgPlot.setSeries1(xs1, ys1, nEnv);
        emgPlot.setSeries2(xs2, ys2, nFlt);
        emgPlot.setMarkers(tmk, nMk);
        emgPlot.invalidate();

        float imuMin = 0f, imuMax = 1f;
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
        stopSession();
    }
}
