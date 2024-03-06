package com.clj.blesample.operation;

import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.RadioButton;

import androidx.appcompat.app.AppCompatActivity;

import com.clj.blesample.R;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

import com.clj.blesample.operation.EmgView;
import com.clj.fastble.BleManager;
import com.clj.fastble.callback.BleReadCallback;
import com.clj.fastble.data.BleDevice;
import com.clj.fastble.exception.BleException;

public class PlotActivity extends AppCompatActivity {
        private List<Integer> datas = new ArrayList<Integer>();
        private List<Integer> data1Datas = new ArrayList<Integer>();

        private Queue<Integer> data0Q = new LinkedList<Integer>();
        private Queue<Integer> data1Q = new LinkedList<Integer>();

        private int flag = 0;
        public static final String KEY_DATA = "key_data";
        private BleDevice bleDevice = null;
        private Boolean demoMode = true;
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Log.d("", "onCreate");
            setContentView(R.layout.activity_plot);

            initData();
            if (demoMode) {
                loadDatas();
                simulator();
            }
            else
                readOverBT();
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
        private void simulator(){
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if(EmgView.isRunning){
                    if(data0Q.size() > 0){
                        EmgView.addEcgData0(data0Q.poll());
                        EmgView.addEcgData1(data1Q.poll());
                    }
                }
            }
        }, 0, 2);
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
                datas.add(Integer.parseInt(str));
            }

            data0Q.addAll(datas);
            data1Q.addAll(datas);
        }catch (Exception e){}
    }
    private void readOverBT() {
        //Log.d("BT", "to RX ");
        String RX           = "7def8317-7301-4ee6-8849-46face74ca2a";
        String SERVICE_RX   = "7def8317-7300-4ee6-8849-46face74ca2a";
        //String SERVICE_RX   = "00002902-0000-1000-8000-00805f9b34fb";
        if (bleDevice != null && BleManager.getInstance().isConnected(bleDevice)) {
            BleManager.getInstance().read(
                    bleDevice,
                    SERVICE_RX,
                    RX,
                    new BleReadCallback() {
                        @Override
                        public void onReadSuccess(byte[] justRead) {
                            String rxStr = new String(justRead, StandardCharsets.UTF_8);
                            Log.d("BT", "received: " + rxStr);
                            String[] splited = rxStr.split(" ");
                            int emgAggregatedCount = 4;
                            for (int i = 0; i < emgAggregatedCount; i ++) {
                                int emgData = Integer.parseInt(splited[i]);
                                EmgView.addEcgData0(emgData);
                            }
                            int gsrData = Integer.parseInt(splited[emgAggregatedCount]);
                            EmgView.addEcgData1(gsrData);
                            Log.d("BT", "read from BT again: ");
                            readOverBT();
                        }

                        @Override
                        public void onReadFailure(final BleException exception) {
                            Log.d("BT", "onReadFailure " + exception);
                        }
                    });
        }
    }
    @Override
    public void onBackPressed() {
            super.finish();
            finish();
    }

}
