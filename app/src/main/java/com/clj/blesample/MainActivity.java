package com.clj.blesample;

import static java.lang.Thread.sleep;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.clj.blesample.adapter.DeviceAdapter;
import com.clj.blesample.comm.ObserverManager;
import com.clj.blesample.operation.OperationActivity;
import com.clj.blesample.operation.PlotActivity;
import com.clj.blesample.analysis.ProcessingActivity;
import com.clj.blesample.capture.SessionCaptureActivity;
import com.clj.fastble.BleManager;
import com.clj.fastble.callback.BleGattCallback;
import com.clj.fastble.callback.BleMtuChangedCallback;
import com.clj.fastble.callback.BleReadCallback;
import com.clj.fastble.callback.BleRssiCallback;
import com.clj.fastble.callback.BleScanCallback;
import com.clj.fastble.callback.BleWriteCallback;
import com.clj.fastble.data.BleDevice;
import com.clj.fastble.exception.BleException;
import com.clj.fastble.scan.BleScanRuleConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
        //RecognitionListener {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int REQUEST_CODE_OPEN_GPS = 1;
    private static final int REQUEST_CODE_PERMISSION_LOCATION = 2;

    private LinearLayout layout_setting;
    private TextView txt_setting;
    private Button btn_scan, btn_monitor;
    private Button btn_process, btn_record;
    private EditText et_name, et_mac, et_uuid;
    private Switch sw_auto;
    private ImageView img_loading;

    private Animation operatingAnim;
    private DeviceAdapter mDeviceAdapter;
    private ProgressDialog progressDialog;

    /*
    // speech
    private SpeechRecognizer speech = null;
    private Intent recognizerIntent = null;
    private String returnedText;
    private String returnedError;
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1; */
    private BleDevice mBleDevice = null;

    // MTU negotiation state (default ATT MTU=23 -> notify payload=20)
    private volatile int negotiatedMtu = 23;

    private void requestMtuWithRetry(final BleDevice device, final int mtu, final int attemptsLeft) {
        Log.e("BT_NOTIFY", "Requesting MTU=" + mtu + " attemptsLeft=" + attemptsLeft);
        BleManager.getInstance().setMtu(device, mtu, new BleMtuChangedCallback() {
            @Override
            public void onMtuChanged(int mtuResult) {
                negotiatedMtu = mtuResult;
                Log.e("BT_NOTIFY", "MTU changed to " + mtuResult);

                // If MTU still low, retry a couple of times (some stacks need a short delay)
                if (mtuResult <= 23 && attemptsLeft > 1) {
                    getWindow().getDecorView().postDelayed(() -> requestMtuWithRetry(device, mtu, attemptsLeft - 1), 600);
                }
            }

            @Override
            public void onSetMTUFailure(BleException exception) {
                Log.e("BT_NOTIFY", "MTU request failed: " + exception);
                if (attemptsLeft > 1) {
                    getWindow().getDecorView().postDelayed(() -> requestMtuWithRetry(device, mtu, attemptsLeft - 1), 600);
                }
            }
        });
    }

    private Boolean RadioButtonState = false;
    //private CheckBox checkBox;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        //checkBox = ( CheckBox ) findViewById( R.id.demoCheckBox );
/*        checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()
        {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked)
            {
                if ( isChecked )
                {
                    // perform logic
                    RadioButtonState = true;

                }
                else RadioButtonState = false;
                Log.d("", "checkbox is " + RadioButtonState);
            }
        });
        checkBox = (CheckBox) findViewById(R.id.demoCheckBox); // initiate a radio button
        checkBox.setText("Demo"); // displayed text of radio button

        checkBox.setChecked(true);*/

        BleManager.getInstance().init(getApplication());
        BleManager.getInstance()
                .enableLog(true)
                .setReConnectCount(0, 5000)  // disabled: SessionCaptureActivity handles reconnect
                .setConnectOverTime(20000)
                .setOperateTimeout(5000);
/*
        // start speech recogniser
        //resetSpeechRecognizer();

        // check for permission
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        if (recognizerIntent == null)
            setRecogniserIntent();
        speech.startListening(recognizerIntent); */
    }

    @Override
    protected void onResume() {
        super.onResume();
        showConnectedDevice();
        /*
        resetSpeechRecognizer();
        if (recognizerIntent == null)
            setRecogniserIntent();
        speech.startListening(recognizerIntent);

         */
    }

    @Override
    protected void onDestroy() {
        /*
        if (speech != null) {
            speech.stopListening();
            speech.cancel();
            speech.destroy();
        }

         */
        super.onDestroy();
        BleManager.getInstance().disconnectAllDevice();
        BleManager.getInstance().destroy();
    }
    @Override
    protected void onStop() {
        Log.i(TAG, "stop");
        /*
        if (speech != null) {
            speech.stopListening();
            speech.cancel();
            speech.destroy();
        }

         */
        super.onStop();
    }
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btn_scan) {
            if (btn_scan.getText().equals(getString(R.string.start_scan))) {
                checkPermissions();
            } else if (btn_scan.getText().equals(getString(R.string.stop_scan))) {
                BleManager.getInstance().cancelScan();
            }
        }

        else if (v.getId() == R.id.btn_monitor) {
            if (mBleDevice == null || !BleManager.getInstance().isConnected(mBleDevice)) {
                Toast.makeText(this, "Connect a device first", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(getApplicationContext(), SessionCaptureActivity.class);
            intent.putExtra("bleDevice", mBleDevice);
            startActivity(intent);
        }

        else if (v.getId() == R.id.txt_setting) {
                if (layout_setting.getVisibility() == View.VISIBLE) {
                    layout_setting.setVisibility(View.GONE);
                    txt_setting.setText(getString(R.string.expand_search_settings));
                } else {
                    layout_setting.setVisibility(View.VISIBLE);
                    txt_setting.setText(getString(R.string.retrieve_search_settings));
                }
        }
        else if (v.getId() == R.id.btn_process) {
            Intent intent = new Intent(getApplicationContext(), ProcessingActivity.class);
            startActivity(intent);
        }
        else if (v.getId() == R.id.btn_record) {
            if (mBleDevice == null) {
                Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(getApplicationContext(), SessionCaptureActivity.class);
            intent.putExtra("bleDevice", mBleDevice);  // SessionCaptureActivity expects "bleDevice"
            startActivity(intent);
        }

    }

    private void initView() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        btn_scan = (Button) findViewById(R.id.btn_scan);
        btn_scan.setText(getString(R.string.start_scan));
        btn_scan.setOnClickListener(this);
        btn_monitor = (Button) findViewById(R.id.btn_monitor);
        btn_monitor.setText(getString(R.string.start_monitor));
        btn_monitor.setOnClickListener(this);
        btn_process = (Button) findViewById(R.id.btn_process);
        btn_process.setText("Process");
        btn_process.setOnClickListener(this);
        btn_record = (Button) findViewById(R.id.btn_record);
        btn_record.setText("Record");
        btn_record.setOnClickListener(this);
        et_name = (EditText) findViewById(R.id.et_name);
        et_mac = (EditText) findViewById(R.id.et_mac);
        et_uuid = (EditText) findViewById(R.id.et_uuid);
        sw_auto = (Switch) findViewById(R.id.sw_auto);

        layout_setting = (LinearLayout) findViewById(R.id.layout_setting);
        txt_setting = (TextView) findViewById(R.id.txt_setting);
        txt_setting.setOnClickListener(this);
        layout_setting.setVisibility(View.GONE);
        txt_setting.setText(getString(R.string.expand_search_settings));

        img_loading = (ImageView) findViewById(R.id.img_loading);
        operatingAnim = AnimationUtils.loadAnimation(this, R.anim.rotate);
        operatingAnim.setInterpolator(new LinearInterpolator());
        progressDialog = new ProgressDialog(this);

        mDeviceAdapter = new DeviceAdapter(this);
        mDeviceAdapter.setOnDeviceClickListener(new DeviceAdapter.OnDeviceClickListener() {
            @Override
            public void onConnect(BleDevice bleDevice) {
                if (!BleManager.getInstance().isConnected(bleDevice)) {
                    BleManager.getInstance().cancelScan();
                    connect(bleDevice);
                }
            }

            @Override
            public void onDisConnect(final BleDevice bleDevice) {
                if (BleManager.getInstance().isConnected(bleDevice)) {
                    BleManager.getInstance().disconnect(bleDevice);
                }
            }

            @Override
            public void onDetail(BleDevice bleDevice) {
                if (BleManager.getInstance().isConnected(bleDevice)) {
                    Intent intent = new Intent(MainActivity.this, OperationActivity.class);
                    intent.putExtra(OperationActivity.KEY_DATA, bleDevice);
                    startActivity(intent);
                }
            }
        });
        ListView listView_device = (ListView) findViewById(R.id.list_device);
        listView_device.setAdapter(mDeviceAdapter);
    }

    private void showConnectedDevice() {
        List<BleDevice> deviceList = BleManager.getInstance().getAllConnectedDevice();
        mDeviceAdapter.clearConnectedDevice();
        for (BleDevice bleDevice : deviceList) {
            mDeviceAdapter.addDevice(bleDevice);
        }
        mDeviceAdapter.notifyDataSetChanged();
    }

    private void setScanRule() {
        String[] uuids;
        String str_uuid = et_uuid.getText().toString();
        if (TextUtils.isEmpty(str_uuid)) {
            uuids = null;
        } else {
            uuids = str_uuid.split(",");
        }
        UUID[] serviceUuids = null;
        if (uuids != null && uuids.length > 0) {
            serviceUuids = new UUID[uuids.length];
            for (int i = 0; i < uuids.length; i++) {
                String name = uuids[i];
                String[] components = name.split("-");
                if (components.length != 5) {
                    serviceUuids[i] = null;
                } else {
                    serviceUuids[i] = UUID.fromString(uuids[i]);
                }
            }
        }

        String[] names;
        String str_name = et_name.getText().toString();
        if (TextUtils.isEmpty(str_name)) {
            names = null;
        } else {
            names = str_name.split(",");
        }

        String mac = et_mac.getText().toString();

        boolean isAutoConnect = sw_auto.isChecked();

        BleScanRuleConfig scanRuleConfig = new BleScanRuleConfig.Builder()
                .setServiceUuids(serviceUuids)      // 只扫描指定的服务的设备，可选
                .setDeviceName(true, names)   // 只扫描指定广播名的设备，可选
                .setDeviceMac(mac)                  // 只扫描指定mac的设备，可选
                .setAutoConnect(isAutoConnect)      // 连接时的autoConnect参数，可选，默认false
                .setScanTimeOut(10000)              // 扫描超时时间，可选，默认10秒
                .build();
        BleManager.getInstance().initScanRule(scanRuleConfig);
    }

    private void startScan() {
        BleManager.getInstance().scan(new BleScanCallback() {
            @Override
            public void onScanStarted(boolean success) {
                mDeviceAdapter.clearScanDevice();
                mDeviceAdapter.notifyDataSetChanged();
                img_loading.startAnimation(operatingAnim);
                img_loading.setVisibility(View.VISIBLE);
                btn_scan.setText(getString(R.string.stop_scan));
            }

            @Override
            public void onLeScan(BleDevice bleDevice) {
                super.onLeScan(bleDevice);
            }

            @Override
            public void onScanning(BleDevice bleDevice) {
                mDeviceAdapter.addDevice(bleDevice);
                mDeviceAdapter.notifyDataSetChanged();
            }

            @Override
            public void onScanFinished(List<BleDevice> scanResultList) {
                img_loading.clearAnimation();
                img_loading.setVisibility(View.INVISIBLE);
                btn_scan.setText(getString(R.string.start_scan));
            }
        });
    }

    private void connect(final BleDevice bleDevice) {
        BleManager.getInstance().connect(bleDevice, new BleGattCallback() {
            @Override
            public void onStartConnect() {
                progressDialog.show();
            }

            @Override
            public void onConnectFail(BleDevice bleDevice, BleException exception) {
                img_loading.clearAnimation();
                img_loading.setVisibility(View.INVISIBLE);
                btn_scan.setText(getString(R.string.start_scan));
                progressDialog.dismiss();
                Toast.makeText(MainActivity.this, getString(R.string.connect_fail), Toast.LENGTH_LONG).show();
            }

            @Override
            public void onConnectSuccess(BleDevice bleDevice, BluetoothGatt gatt, int status) {
                progressDialog.dismiss();
                mDeviceAdapter.addDevice(bleDevice);
                mDeviceAdapter.notifyDataSetChanged();
                mBleDevice = bleDevice;

                // Optional: request high connection priority (helps throughput)
                BleManager.getInstance().requestConnectionPriority(
                        bleDevice, BluetoothGatt.CONNECTION_PRIORITY_HIGH
                );

                // Request MTU BEFORE you start streaming (notify).
                // Your ESP32 frames are >20 bytes; without MTU this phone will only receive 20-byte chunks.
                requestMtuWithRetry(bleDevice, 247, 3);

                // NOTE: We deliberately do NOT start notify/streaming here.
                // Start notify in SessionCaptureActivity / PlotActivity after MTU is negotiated.
            }

            @Override
            public void onDisConnected(boolean isActiveDisConnected, BleDevice bleDevice, BluetoothGatt gatt, int status) {
                progressDialog.dismiss();

                mDeviceAdapter.removeDevice(bleDevice);
                mDeviceAdapter.notifyDataSetChanged();

                if (isActiveDisConnected) {
                    Toast.makeText(MainActivity.this, getString(R.string.active_disconnected), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.disconnected), Toast.LENGTH_LONG).show();
                    ObserverManager.getInstance().notifyObserver(bleDevice);
                }
                mBleDevice = null;
            }
        });
    }

    private void readRssi(BleDevice bleDevice) {
        BleManager.getInstance().readRssi(bleDevice, new BleRssiCallback() {
            @Override
            public void onRssiFailure(BleException exception) {
                Log.i(TAG, "onRssiFailure" + exception.toString());
            }

            @Override
            public void onRssiSuccess(int rssi) {
                Log.i(TAG, "onRssiSuccess: " + rssi);
            }
        });
    }

    private void setMtu(BleDevice bleDevice, int mtu) {
        BleManager.getInstance().setMtu(bleDevice, mtu, new BleMtuChangedCallback() {
            @Override
            public void onSetMTUFailure(BleException exception) {
                Log.i(TAG, "onsetMTUFailure" + exception.toString());
            }

            @Override
            public void onMtuChanged(int mtu) {
                Log.i(TAG, "onMtuChanged: " + mtu);
            }
        });
    }

    @Override
    public final void onRequestPermissionsResult(int requestCode,
                                                 @NonNull String[] permissions,
                                                 @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST_CODE_PERMISSION_LOCATION:
                if (grantResults.length > 0) {
                    for (int i = 0; i < grantResults.length; i++) {
                        if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                            onPermissionGranted(permissions[i]);
                        }
                    }
                }
                break;
                /*
            case PERMISSIONS_REQUEST_RECORD_AUDIO:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    speech.startListening(recognizerIntent);
                } else {
                    Toast.makeText(MainActivity.this, "Permission Denied!", Toast.LENGTH_SHORT).show();
                    finish();
                }*/
            }
        }

    private void checkPermissions() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, getString(R.string.please_open_blue), Toast.LENGTH_LONG).show();
            return;
        }

        String[] permissions = {Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> permissionDeniedList = new ArrayList<>();
        for (String permission : permissions) {
            int permissionCheck = ContextCompat.checkSelfPermission(this, permission);
            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                onPermissionGranted(permission);
            } else {
                permissionDeniedList.add(permission);
            }
        }
        if (!permissionDeniedList.isEmpty()) {
            String[] deniedPermissions = permissionDeniedList.toArray(new String[permissionDeniedList.size()]);
            ActivityCompat.requestPermissions(this, deniedPermissions, REQUEST_CODE_PERMISSION_LOCATION);
        }
    }

    private void onPermissionGranted(String permission) {
        switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !checkGPSIsOpen()) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.notifyTitle)
                            .setMessage(R.string.gpsNotifyMsg)
                            .setNegativeButton(R.string.cancel,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            finish();
                                        }
                                    })
                            .setPositiveButton(R.string.setting,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                                            startActivityForResult(intent, REQUEST_CODE_OPEN_GPS);
                                        }
                                    })

                            .setCancelable(false)
                            .show();
                } else {
                    setScanRule();
                    startScan();
                }
                break;
        }
    }

    private boolean checkGPSIsOpen() {
        LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null)
            return false;
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_GPS) {
            if (checkGPSIsOpen()) {
                setScanRule();
                startScan();
            }
        }
    }
    // on change of bars write char
    private void sendOverBT(String s) {
        Log.d("BT", "to TX " + s);
        //String RXTX = "0000ffe2-0000-1000-8000-00805f9b34fb";
        //String SERVICE_TX = "000ffe0-0000-1000-8000-00805f9b34fb";
        String TX           = "7def8317-7302-4ee6-8849-46face74ca2a";
        //String SERVICE_TX = "00002a05-0000-1000-8000-00805f9b34fb";
        String SERVICE_TX   = "7def8317-7300-4ee6-8849-46face74ca2a";
        if (mBleDevice != null && BleManager.getInstance().isConnected(mBleDevice)) {
            BleManager.getInstance().write(
                    mBleDevice,
                    SERVICE_TX,
                    TX,
                    s.getBytes(StandardCharsets.UTF_8), //HexUtil.hexStringToBytes(s),
                    //s.getBytes(),
                    new BleWriteCallback() {

                        @Override
                        public void onWriteSuccess(final int current, final int total, final byte[] justWrite) {
                            Log.d("BT", "onWriteSuccess " + total);
                        }

                        @Override
                        public void onWriteFailure(final BleException exception) {
                            Log.d("BT", "onWriteFailure " + exception);
                        }
                    });
        }
    }
    private String readOverBT() {
        Log.d("BT", "to RX ");
        String readString = "";
        String RX           = "7def8317-7301-4ee6-8849-46face74ca2a";
        String SERVICE_RX   = "7def8317-7300-4ee6-8849-46face74ca2a";
        //String SERVICE_RX   = "00002902-0000-1000-8000-00805f9b34fb";
        if (mBleDevice != null && BleManager.getInstance().isConnected(mBleDevice)) {
            BleManager.getInstance().read(
                    mBleDevice,
                    SERVICE_RX,
                    RX,
                    new BleReadCallback() {
                        @Override
                        public void onReadSuccess(byte[] justRead) {
                            String readString = new String(justRead, StandardCharsets.UTF_8);
                            Log.d("results from BT RX: ", readString);
                            readOverBT();
                        }

                        @Override
                        public void onReadFailure(final BleException exception) {
                            Log.d("BT", "onReadFailure " + exception);
                        }
                    });
        }
        return readString;
    }
/*
    @Override
    public void onEvent(int arg0, Bundle arg1) {
        Log.i(TAG, "onEvent");
    }
    @Override
    public void onPartialResults(Bundle arg0) {
        Log.i(TAG, "onPartialResults");
    }

    @Override
    public void onReadyForSpeech(Bundle arg0) {
        Log.i(TAG, "onReadyForSpeech");
        while(mBleDevice != null) {
            //sendOverBT("hello, EMG sensor " + String.valueOf(counter));
            readOverBT();
            break;
            try {
                sleep(20);
            } catch (InterruptedException e) {
                System.out.println("I was interrupted!");
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        //Log.i(TAG, "onRmsChanged: " + rmsdB);
        //progressBar.setProgress((int) rmsdB);
    }
    @Override
    public void onResults(Bundle results) {
        Log.i(TAG, "onResults");
        ArrayList<String> matches = results
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = "";
        if (matches.size() > 0) text = matches.get(0);
        //for (String result : matches)
          //  text += result + "\n";

        returnedText= text;
        Log.d(TAG, "speech text: " + text);
        Log.d(TAG, "speech text end");
        sendOverBT(text);
        speech.startListening(recognizerIntent);
    }

    @Override
    public void onError(int errorCode) {
        String errorMessage = getErrorText(errorCode);
        Log.i(TAG, "FAILED " + errorMessage);
        returnedError = errorMessage;

        // rest voice recogniser
        resetSpeechRecognizer();
        speech.startListening(recognizerIntent);
    }
    private void resetSpeechRecognizer() {

        if(speech != null)
            speech.destroy();
        speech = SpeechRecognizer.createSpeechRecognizer(this);
        Log.i(TAG, "isRecognitionAvailable: " + SpeechRecognizer.isRecognitionAvailable(this));
        if(SpeechRecognizer.isRecognitionAvailable(this))
            speech.setRecognitionListener(this);
        else
            finish();
    }
    private void setRecogniserIntent() {
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                "en");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
    }

    public String getErrorText(int errorCode) {
        String message;
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                message = "Audio recording error";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                message = "Client side error";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Insufficient permissions";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
                message = "Network error";
                break;
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                message = "Network timeout";
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "No match";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "RecognitionService busy";
                break;
            case SpeechRecognizer.ERROR_SERVER:
                message = "error from server";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "No speech input";
                break;
            default:
                message = "Didn't understand, please try again.";
                break;
        }
        return message;
    }

    @Override
    public void onEndOfSpeech() {
        Log.i(TAG, "onEndOfSpeech");
        //progressBar.setIndeterminate(true);
        speech.stopListening();
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        Log.i(TAG, "onBufferReceived: " + buffer);
    }

    @Override
    public void onBeginningOfSpeech() {
        Log.i(TAG, "onBeginningOfSpeech");
        //progressBar.setIndeterminate(false);
        //progressBar.setMax(10);
    }
    */

}
