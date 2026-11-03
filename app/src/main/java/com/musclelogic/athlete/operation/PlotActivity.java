package com.musclelogic.athlete.operation;

import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.musclelogic.athlete.R;
import com.musclelogic.athlete.processing.EmgProcessor;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import com.musclelogic.athlete.operation.EmgView;
import com.musclelogic.athlete.operation.SensorDataFile;
import com.clj.fastble.BleManager;
import com.clj.fastble.callback.BleReadCallback;
import com.clj.fastble.callback.BleNotifyCallback;
import com.clj.fastble.data.BleDevice;
import com.clj.fastble.exception.BleException;

public class PlotActivity extends AppCompatActivity {
    private List<Integer> datas = new ArrayList<Integer>();
    private List<Integer> data1Datas = new ArrayList<Integer>();

    private Queue<Integer> data0Q = new LinkedList<Integer>();
    private Queue<Integer> data1Q = new LinkedList<Integer>();

    // EMG Processing additions
    private List<Double> rawEmgBuffer = new ArrayList<>();
    private static final int BUFFER_SIZE = 2000; // 4 seconds at 500 Hz
    private static final double SAMPLING_RATE = 500.0; // Hz
    private Timer processingTimer;
    private Timer simulatorTimer;
    private SensorDataFile sensorDataFile;

    private int flag = 0;
    public static final String KEY_DATA = "key_data";
    private BleDevice bleDevice = null;
    private Boolean demoMode = true;

    // BLE UUIDs (Nordic UART style)
    private static final String SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    private static final String CHAR_UUID_NOTIFY = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

    // Protocol constants
    private static final byte MAGIC0 = (byte) 0xA5;
    private static final byte MAGIC1 = (byte) 0x5A;
    private static final int HEADER_LEN = 14;
    private static final int CRC_LEN = 2;

    private static final byte TLV_EMG_BATCH = 0x01;
    private static final byte TLV_IMU_BATCH = 0x02;
    private static final byte TLV_VITALS = 0x03;
    private ByteBuffer frameBuffer = ByteBuffer.allocate(1024); // Reusable buffer for parsing frames


    // UI elements for feature display
    private TextView txtRms;
    private TextView txtMnf;
    private TextView txtMdf;
    private TextView txtWaveletEntropy;
    private TextView txtSampleEntropy;
    private TextView txtBattery;
    private FeaturePlotView featurePlotView;
    private CheckBox chkRms, chkMnf, chkMdf, chkWaveletEntropy, chkSampleEntropy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("", "onCreate");
        setContentView(R.layout.activity_plot);

        initUI();
        initData();

        sensorDataFile = new SensorDataFile(this);

        if (demoMode) {
            loadDatas();
            simulator();
        } else {
            startNotifyOverBT();
        }

        // Start periodic feature extraction
        startFeatureExtraction();
    }

    private void initUI() {
        txtRms = findViewById(R.id.txtRms);
        txtMnf = findViewById(R.id.txtMnf);
        txtMdf = findViewById(R.id.txtMdf);
        txtWaveletEntropy = findViewById(R.id.txtWaveletEntropy);
        txtSampleEntropy = findViewById(R.id.txtSampleEntropy);
        txtBattery = findViewById(R.id.txtBattery);

        featurePlotView = findViewById(R.id.featurePlotView);

        chkRms = findViewById(R.id.chkRms);
        chkMnf = findViewById(R.id.chkMnf);
        chkMdf = findViewById(R.id.chkMdf);
        chkWaveletEntropy = findViewById(R.id.chkWaveletEntropy);
        chkSampleEntropy = findViewById(R.id.chkSampleEntropy);

        // Setup checkbox listeners
        chkRms.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (featurePlotView != null) {
                    featurePlotView.setShowRms(isChecked);
                }
            }
        });

        chkMnf.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (featurePlotView != null) {
                    featurePlotView.setShowMnf(isChecked);
                }
            }
        });

        chkMdf.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (featurePlotView != null) {
                    featurePlotView.setShowMdf(isChecked);
                }
            }
        });

        chkWaveletEntropy.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (featurePlotView != null) {
                    featurePlotView.setShowWaveletEntropy(isChecked);
                }
            }
        });

        chkSampleEntropy.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (featurePlotView != null) {
                    featurePlotView.setShowSampleEntropy(isChecked);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (processingTimer != null) {
            processingTimer.cancel();
            processingTimer = null;
        }
        if (simulatorTimer != null) {
            simulatorTimer.cancel();
            simulatorTimer = null;
        }

        // Stop BLE notifications to avoid leaking callbacks
        if (!demoMode) {
            stopNotifyOverBT();
        }

        if (sensorDataFile != null) {
            sensorDataFile.close();
        }
    }

    private void initData() {
        bleDevice = getIntent().getParcelableExtra(KEY_DATA);
        if (bleDevice == null)
            demoMode = true;
        else demoMode = false;
        Log.d("", "demo mode " + demoMode);
        //finish();

    }

    /**
     * 模拟心电发送，心电数据是一秒500个包，所以
     */
    private void simulator() {
        simulatorTimer = new Timer();
        simulatorTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Random random = new Random();
                if (EmgView.isRunning) {
                    if (data0Q.size() > 0) {
                        Integer tmp = data0Q.poll();
                        Integer tmp1 = data1Q.poll();
                        if (tmp != null && tmp1 != null) {
                            // Collect raw data for processing
                            synchronized (rawEmgBuffer) {
                                rawEmgBuffer.add((double) tmp);
                                if (rawEmgBuffer.size() > BUFFER_SIZE) {
                                    rawEmgBuffer.remove(0);
                                }
                            }

                            EmgView.addEcgData0(tmp);
                            EmgView.addEcgData1(tmp1 / 10 * random.nextInt(10));
                        }
                    }
                }
            }
        }, 0, 2);
    }

    /**
     * Periodic feature extraction (every 1 second)
     */
    private void startFeatureExtraction() {
        processingTimer = new Timer();
        processingTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                processEmgFeatures();
            }
        }, 1000, 1000); // Run every 1 second
    }

    /**
     * Process EMG features using EmgProcessor
     */
    private void processEmgFeatures() {
        double[] rawData;
        synchronized (rawEmgBuffer) {
            if (rawEmgBuffer.size() < 500) {
                Log.d("EMG", "Not enough data for processing: " + rawEmgBuffer.size() + " samples");
                return;
            }
            // Convert List<Double> to double[]
            rawData = new double[rawEmgBuffer.size()];
            for (int i = 0; i < rawEmgBuffer.size(); i++) {
                rawData[i] = rawEmgBuffer.get(i);
            }
        }

        // Step 1: Preprocess (detrend, bandpass 20-450 Hz, notch 60 Hz)
        double[] filtered = EmgProcessor.preprocess(
                rawData,
                SAMPLING_RATE,
                20.0,    // bandpass low
                450.0,   // bandpass high
                60.0,    // notch frequency (mains)
                30.0,    // notch Q
                new int[]{2, 3}  // harmonics: 120 Hz, 180 Hz
        );

        // Step 2: Extract features (0.5s windows, 0.25s step)
        EmgProcessor.FeatureSeries features = EmgProcessor.computeFeatures(
                filtered,
                SAMPLING_RATE,
                0.5,  // window size (seconds)
                0.25  // step size (seconds)
        );

        // Step 3: Log and display features
        if (features.rms.length > 0) {
            final int lastIdx = features.rms.length - 1;
            Log.d("EMG_Features", String.format(
                    "Time: %.2fs | RMS: %.2f | MNF: %.2f Hz | MDF: %.2f Hz | WaveletEnt: %.3f | SampEnt: %.3f",
                    features.tCenterSec[lastIdx],
                    features.rms[lastIdx],
                    features.mnfHz[lastIdx],
                    features.mdfHz[lastIdx],
                    features.waveletEntropy[lastIdx],
                    features.sampleEntropy[lastIdx]
            ));
            // Update UI on main thread
            final EmgProcessor.FeatureSeries finalFeatures = features;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateFeatureUI(finalFeatures, lastIdx);
                    // Update feature plot
                    if (featurePlotView != null) {
                        featurePlotView.addFeatureData(
                                finalFeatures.rms[lastIdx],
                                finalFeatures.mnfHz[lastIdx],
                                finalFeatures.mdfHz[lastIdx],
                                finalFeatures.waveletEntropy[lastIdx],
                                finalFeatures.sampleEntropy[lastIdx]
                        );
                    }
                }
            });
            // TODO: Use features for classification, gesture recognition, etc.
            // Example: if (features.rms[lastIdx] > THRESHOLD) { triggerAction(); }
        }
    }

    private void updateFeatureUI(EmgProcessor.FeatureSeries features, int idx) {
        if (txtRms != null) {
            txtRms.setText(String.format("RMS: %.2f", features.rms[idx]));
        }
        if (txtMnf != null) {
            double mnf = features.mnfHz[idx];
            if (Double.isNaN(mnf)) {
                txtMnf.setText("MNF: --");
            } else {
                txtMnf.setText(String.format("MNF: %.2f Hz", mnf));
            }
        }
        if (txtMdf != null) {
            double mdf = features.mdfHz[idx];
            if (Double.isNaN(mdf)) {
                txtMdf.setText("MDF: --");
            } else {
                txtMdf.setText(String.format("MDF: %.2f Hz", mdf));
            }
        }
        if (txtWaveletEntropy != null) {
            double went = features.waveletEntropy[idx];
            if (Double.isNaN(went)) {
                txtWaveletEntropy.setText("Wavelet Entropy: --");
            } else {
                txtWaveletEntropy.setText(String.format("Wavelet Entropy: %.3f", went));
            }
        }
        if (txtSampleEntropy != null) {
            double sampen = features.sampleEntropy[idx];
            if (Double.isNaN(sampen) || Double.isInfinite(sampen)) {
                txtSampleEntropy.setText("Sample Entropy: --");
            } else {
                txtSampleEntropy.setText(String.format("Sample Entropy: %.3f", sampen));
            }
        }
    }

    private void loadDatas() {
        try {
            String data0 = "";
            InputStream in = getResources().openRawResource(R.raw.emgdata);
            int length = in.available();
            byte[] buffer = new byte[length];
            in.read(buffer);
            data0 = new String(buffer);
            in.close();
            String[] data0s = data0.split(",");
            for (String str : data0s) {
                str = str.trim();
                if (!str.isEmpty()) {
                    try {
                        int value = Integer.parseInt(str);
                        datas.add(value);
                    } catch (NumberFormatException nfe) {
                        // Skip invalid data
                    }
                }
            }

            data0Q.addAll(datas);
            data1Q.addAll(datas);
            Log.d("PlotActivity", "Loaded " + datas.size() + " data points");
        } catch (Exception e) {
            Log.e("PlotActivity", "Error loading data", e);
        }
    }

    private void startNotifyOverBT() {
        // Data from the peripheral is streamed via NOTIFY (not READ).
        Log.e("BT_NOTIFY", "startNotifyOverBT() called");
        if (bleDevice == null) {
            Log.w("BT", "bleDevice is null - cannot start notify");
            return;
        }
        if (!BleManager.getInstance().isConnected(bleDevice)) {
            Log.w("BT", "bleDevice not connected - cannot start notify");
            return;
        }

        // Optional: dump discovered services/characteristics once for debugging
        dumpGattOnce();

        BleManager.getInstance().notify(
                bleDevice,
                SERVICE_UUID,
                CHAR_UUID_NOTIFY,
                new BleNotifyCallback() {
                    @Override
                    public void onNotifySuccess() {
                        Log.i("BT", "Notify enabled on " + CHAR_UUID_NOTIFY);
                    }

                    @Override
                    public void onNotifyFailure(final BleException exception) {
                        Log.e("BT", "Notify enable failed: " + exception);
                    }

                    @Override
                    public void onCharacteristicChanged(byte[] data) {
                        if (data == null || data.length == 0) return;

                        frameBuffer.put(data);
                        frameBuffer.flip();

                        while (frameBuffer.remaining() > HEADER_LEN) {
                            int startPos = frameBuffer.position();
                            // Find the magic bytes
                            while (frameBuffer.remaining() > 1 && (frameBuffer.get() != MAGIC0 || frameBuffer.get() != MAGIC1)) {
                                // If we've searched to the end without a match, compact and wait for more data
                                if (frameBuffer.remaining() < 2) {
                                    frameBuffer.position(startPos + 1);
                                    frameBuffer.compact();
                                    return;
                                }
                                // Rewind one byte in case the second byte was MAGIC0
                                frameBuffer.position(frameBuffer.position() - 1);
                                startPos = frameBuffer.position();
                            }

                            frameBuffer.position(startPos); // Reset to start of magic bytes

                            if (frameBuffer.remaining() < HEADER_LEN) { // Not enough for a header
                                frameBuffer.compact();
                                return;
                            }

                            byte[] header = new byte[HEADER_LEN];
                            frameBuffer.get(header);
                            ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

                            headerBuffer.getShort(); // Skip magic bytes
                            byte ver = headerBuffer.get(2);
                            byte flags = headerBuffer.get(3);
                            int frameSeq = headerBuffer.getInt(4);
                            long t_us_u32 = headerBuffer.getInt(8) & 0xFFFFFFFFL;
                            int payloadLen = headerBuffer.getShort(12) & 0xFFFF;

                            if (frameBuffer.remaining() < payloadLen + CRC_LEN) {
                                frameBuffer.position(startPos); // Not enough data for full frame
                                frameBuffer.compact();
                                return;
                            }

                            byte[] payload = new byte[payloadLen];
                            frameBuffer.get(payload);

                            // Assuming CRC check is handled on the firmware/Python side for now
                            frameBuffer.get(new byte[CRC_LEN]); // Consume CRC bytes

                            parsePayload(payload, t_us_u32, frameSeq);
                        }

                        if (frameBuffer.hasRemaining()) {
                            frameBuffer.compact();
                        } else {
                            frameBuffer.clear();
                        }
                    }
                });
    }

    private void parsePayload(byte[] payload, long frameTimestamp, int frameSeq) {
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() > 3) { // 3 bytes for TLV header
            byte tlvType = buffer.get();
            int tlvLen = buffer.getShort() & 0xFFFF;

            if (buffer.remaining() < tlvLen) break;

            byte[] tlvValue = new byte[tlvLen];
            buffer.get(tlvValue);

            if (tlvType == TLV_EMG_BATCH) {
                decodeEmg(tlvValue, frameTimestamp, frameSeq);
            } else if (tlvType == TLV_VITALS) {
                decodeVitals(tlvValue, frameTimestamp, frameSeq);
            }
        }
    }

    private void decodeEmg(byte[] value, long frameTimestamp, int frameSeq) {
        ByteBuffer emgBuffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        long seq0 = emgBuffer.getInt() & 0xFFFFFFFFL;
        short t0_off = emgBuffer.getShort();
        int sr_hz = emgBuffer.getShort() & 0xFFFF;
        if (sr_hz == 0) return; // Avoid division by zero
        emgBuffer.get(); // channels
        emgBuffer.get(); // mask
        emgBuffer.get(); // reserved
        int count = emgBuffer.getShort() & 0xFFFF;
        long t0_us = frameTimestamp + t0_off;

        for (int i = 0; i < count; i++) {
            int env = emgBuffer.getShort() & 0xFFFF;
            int raw = emgBuffer.getShort() & 0xFFFF;

            synchronized (rawEmgBuffer) {
                rawEmgBuffer.add((double) raw);
                if (rawEmgBuffer.size() > BUFFER_SIZE) {
                    rawEmgBuffer.remove(0);
                }
            }
            EmgView.addEcgData0(raw);
            EmgView.addEcgData1(env);

            long dev_us = t0_us + i * (1000000L / sr_hz);
            sensorDataFile.writeEmgData(dev_us, seq0 + i, env, raw, frameSeq, System.currentTimeMillis());
        }
    }

    private void decodeVitals(byte[] value, long frameTimestamp, int frameSeq) {
        ByteBuffer vitalsBuffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        if (vitalsBuffer.remaining() >= 11) {
            int spo2_u16 = vitalsBuffer.getShort() & 0xFFFF;
            int hr_x10_u16 = vitalsBuffer.getShort() & 0xFFFF;
            int age_ms = vitalsBuffer.getShort() & 0xFFFF;
            byte flags = vitalsBuffer.get();
            byte brightness = vitalsBuffer.get();
            final int batt_pct = vitalsBuffer.get() & 0xFF;
            int batt_mv = vitalsBuffer.getShort() & 0xFFFF;

            runOnUiThread(() -> {
                if (batt_pct <= 100) {
                    txtBattery.setText("Battery: " + batt_pct + "%");
                } else {
                    txtBattery.setText("Battery: N/A");
                }
            });

            long dev_us_computed = frameTimestamp - (long)age_ms * 1000;
            float hr_bpm = (hr_x10_u16 == 0xFFFF) ? 0 : (float) hr_x10_u16 / 10.0f;
            float battery_v = (batt_mv == 0) ? 0 : (float) batt_mv / 1000.0f;
            int spo2 = (spo2_u16 == 0xFFFF) ? -1 : spo2_u16;

            sensorDataFile.writeVitalsData(frameTimestamp, dev_us_computed, spo2, hr_bpm, age_ms, flags, brightness, batt_pct, battery_v, frameSeq, System.currentTimeMillis());
        }
    }

    private boolean gattDumped = false;

    private void dumpGattOnce() {
        if (gattDumped) return;
        gattDumped = true;
        try {
            android.bluetooth.BluetoothGatt gatt = BleManager.getInstance().getBluetoothGatt(bleDevice);
            if (gatt == null) {
                Log.w("BT", "BluetoothGatt is null (services may not be discovered yet)");
                return;
            }
            java.util.List<android.bluetooth.BluetoothGattService> services = gatt.getServices();
            Log.i("BT", "Discovered services: " + (services == null ? 0 : services.size()));
            if (services != null) {
                for (android.bluetooth.BluetoothGattService s : services) {
                    Log.i("BT", "  SVC " + s.getUuid());
                    for (android.bluetooth.BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        int p = c.getProperties();
                        Log.i("BT", "    CHR " + c.getUuid() + " props=0x" + Integer.toHexString(p));
                    }
                }
            }
        } catch (Exception e) {
            Log.w("BT", "dumpGattOnce failed", e);
        }
    }

    private void stopNotifyOverBT() {
        if (bleDevice == null) return;
        try {
            boolean ok = BleManager.getInstance().stopNotify(bleDevice, SERVICE_UUID, CHAR_UUID_NOTIFY);
            Log.i("BT", "stopNotify: " + ok);
        } catch (Exception e) {
            Log.w("BT", "stopNotify exception", e);
        }
    }

    @Override
    public void onBackPressed() {
        super.finish();
        finish();
    }
}
