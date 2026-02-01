package com.clj.blesample.operation;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
public class SensorDataFile {
    private FileOutputStream emgStream;
    private FileOutputStream vitalsStream;

    public SensorDataFile(Context context) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

            File emgFile = new File(dir, "emg_log_" + timestamp + ".csv");
            emgStream = new FileOutputStream(emgFile, true);
            emgStream.write("dev_us,emg_seq,env,raw,frame_seq,host_rx_ms\n".getBytes());

            File vitalsFile = new File(dir, "vitals_log_" + timestamp + ".csv");
            vitalsStream = new FileOutputStream(vitalsFile, true);
            vitalsStream.write("dev_us_frame,dev_us_computed,spo2,hr_bpm,age_ms,flags,brightness,battery_pct,battery_v,frame_seq,host_rx_ms\n".getBytes());
        } catch (IOException e) {
            Log.e("SensorDataFile", "Error opening file streams", e);
        }
    }

    public void writeEmgData(long dev_us, long emg_seq, int env, int raw, int frame_seq, long host_rx_ms) {
        if (emgStream == null) return;
        try {
            String line = String.format(Locale.US, "%d,%d,%d,%d,%d,%d\n",
                    dev_us, emg_seq, env, raw, frame_seq, host_rx_ms);
            emgStream.write(line.getBytes());
        } catch (IOException e) {
            Log.e("SensorDataFile", "Error writing EMG data", e);
        }
    }

    public void writeVitalsData(long dev_us_frame, long dev_us_computed, int spo2, float hr_bpm, int age_ms, int flags, int brightness, int battery_pct, float battery_v, int frame_seq, long host_rx_ms) {
        if (vitalsStream == null) return;
        try {
            String line = String.format(Locale.US, "%d,%d,%d,%.1f,%d,%d,%d,%d,%.2f,%d,%d\n",
                    dev_us_frame, dev_us_computed, spo2, hr_bpm, age_ms, flags, brightness, battery_pct, battery_v, frame_seq, host_rx_ms);
            vitalsStream.write(line.getBytes());
        } catch (IOException e) {
            Log.e("SensorDataFile", "Error writing vitals data", e);
        }
    }

    public void close() {
        try {
            if (emgStream != null) {
                emgStream.close();
            }
            if (vitalsStream != null) {
                vitalsStream.close();
            }
        } catch (IOException e) {
            Log.e("SensorDataFile", "Error closing file streams", e);
        }
    }
}
