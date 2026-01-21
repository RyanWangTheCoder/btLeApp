package com.clj.blesample.operation;

import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.clj.blesample.R;
import com.clj.blesample.processing.EmgProcessor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import com.clj.blesample.operation.EmgView;
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

        private int flag = 0;
        public static final String KEY_DATA = "key_data";
        private BleDevice bleDevice = null;
        private Boolean demoMode = true;

        // BLE UUIDs (custom UART-like service)
        private static final String SERVICE_UUID = "7def8317-7300-4ee6-8849-46face74ca2a";
        private static final String CHAR_UUID_NOTIFY = "7def8317-7301-4ee6-8849-46face74ca2a";


        // UI elements for feature display
        private TextView txtRms;
        private TextView txtMnf;
        private TextView txtMdf;
        private TextView txtWaveletEntropy;
        private TextView txtSampleEntropy;
        private FeaturePlotView featurePlotView;
        private CheckBox chkRms, chkMnf, chkMdf, chkWaveletEntropy, chkSampleEntropy;
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Log.d("", "onCreate");
            setContentView(R.layout.activity_plot);

            initUI();
            initData();
            if (demoMode) {
                    loadDatas();
                    simulator();
            }
            else
                startNotifyOverBT();

            // Start periodic feature extraction
            startFeatureExtraction();
        }
        private void initUI() {
            txtRms = findViewById(R.id.txtRms);
            txtMnf = findViewById(R.id.txtMnf);
            txtMdf = findViewById(R.id.txtMdf);
            txtWaveletEntropy = findViewById(R.id.txtWaveletEntropy);
            txtSampleEntropy = findViewById(R.id.txtSampleEntropy);

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
    }

        private void initData() {
            bleDevice = getIntent().getParcelableExtra(KEY_DATA);
            if (bleDevice == null)
                demoMode = true;
            else demoMode = false;
            Log.d("", "demo mode " + demoMode);
                //finish();

        }/**
         * 模拟心电发送，心电数据是一秒500个包，所以
         */
        private void simulator(){
        simulatorTimer = new Timer();
        simulatorTimer.schedule(new TimerTask() {
                        @Override
                public void run() {
                Random random = new Random();
                if(EmgView.isRunning){
                    if(data0Q.size() > 0){
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
                ));// Update UI on main thread
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

        private void loadDatas(){
        try{
            String data0 = "";
            InputStream in = getResources().openRawResource(R.raw.emgdata);
            int length = in.available();
            byte [] buffer = new byte[length];
            in.read(buffer);
            data0 = new String(buffer);
            in.close();
            String[] data0s = data0.split(",");
            for(String str : data0s){
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
        }catch (Exception e){
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

                        // 1) HEX dump (always reliable)
                        StringBuilder hex = new StringBuilder();
                        for (byte b : data) hex.append(String.format("%02X ", b));
                        Log.d("BT_NOTIFY", "len=" + data.length + " hex=" + hex);

                        // 2) ASCII dump (may include partial / weird chars, so sanitize)
                        String ascii = new String(data, StandardCharsets.UTF_8);
                        Log.d("BT_NOTIFY", "ascii='" + ascii.replace("\n","\\n").replace("\r","\\r") + "'");

                        if (data == null || data.length == 0) return;

                        // Current firmware sends ASCII numbers separated by spaces, e.g. "1234 56"
                        String rxStr = new String(data, StandardCharsets.UTF_8).trim();
                        if (rxStr.isEmpty()) return;
                        Log.d("BT", "notify: " + rxStr);

                        try {
                            String[] splited = rxStr.split("\\s+");
                            int emgAggregatedCount = 1;
                            for (int i = 0; i < Math.min(emgAggregatedCount, splited.length); i++) {
                                int emgData = Integer.parseInt(splited[i]);

                                // Collect raw data for processing
                                synchronized (rawEmgBuffer) {
                                    rawEmgBuffer.add((double) emgData);
                                    if (rawEmgBuffer.size() > BUFFER_SIZE) {
                                        rawEmgBuffer.remove(0);
                                    }
                                }

                                EmgView.addEcgData0(emgData);
                            }

                            if (splited.length > emgAggregatedCount) {
                                int gsrData = Integer.parseInt(splited[emgAggregatedCount]);
                                EmgView.addEcgData1(gsrData);
                            }
                        } catch (Exception e) {
                            Log.w("BT", "Failed to parse notify payload: ' " + rxStr + "'", e);
                        }
                    }
                });
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
