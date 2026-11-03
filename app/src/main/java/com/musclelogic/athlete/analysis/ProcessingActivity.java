package com.musclelogic.athlete.analysis;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.musclelogic.athlete.R;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProcessingActivity extends AppCompatActivity {
    private static final String TAG = "ProcessingActivity";
    public static final String EXTRA_SESSION_DIR = "session_dir";

    private ProgressBar progressBar;
    private TextView txtStage;
    private TextView txtOutput;
    private Button btnAnalyze;
    private Button btnCancel;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;

    private String sessionDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_processing);
        progressBar = findViewById(R.id.progressBar);
        txtStage = findViewById(R.id.txtStage);
        txtOutput = findViewById(R.id.txtOutput);
        btnAnalyze = findViewById(R.id.btnAnalyze);
        btnCancel = findViewById(R.id.btnCancel);

        sessionDir = getIntent().getStringExtra(EXTRA_SESSION_DIR);

        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);

        btnAnalyze.setOnClickListener(v -> startAnalysis());
        btnCancel.setOnClickListener(v -> { cancelled = true; btnCancel.setEnabled(false); txtStage.setText("Cancelled"); });
        
        // Auto-decode on open
        startDecoding();
    }

    private void setStage(String s, int pct) {
        mainHandler.post(() -> { txtStage.setText(s); progressBar.setProgress(pct); });
    }

    private void appendOut(String s) {
        mainHandler.post(() -> txtOutput.append(s + "\n"));
    }

    private void startDecoding() {
        cancelled = false;
        btnAnalyze.setEnabled(false);
        btnCancel.setEnabled(true);
        txtOutput.setText("");
        executor.submit(() -> {
            try {
                Log.d(TAG, "=== Decoding Started ===");
                Log.d(TAG, "Session dir: " + sessionDir);
                
                setStage("Starting Python...", 5);
                if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                if (cancelled) return;
                
                setStage("Importing...", 15);
                Python py = Python.getInstance();
                Log.d(TAG, "Getting analysis_bridge module...");
                PyObject mod = py.getModule("analysis_bridge");
                Log.d(TAG, "Module loaded successfully");
                if (cancelled) return;

                PyObject res;
                if (sessionDir != null && !sessionDir.isEmpty()) {
                    setStage("Decoding frames.bin...", 40);
                    Log.d(TAG, "Calling process_session_compat (decode only) with: " + sessionDir);
                    res = mod.callAttr("process_session_compat", sessionDir);
                    Log.d(TAG, "Decoding complete");
                } else {
                    setStage("Running demo...", 40);
                    Log.d(TAG, "Calling run_demo...");
                    res = mod.callAttr("run_demo");
                    Log.d(TAG, "run_demo returned");
                }
                if (cancelled) return;
                
                setStage("Decode Complete", 100);
                String resultStr = res.toString();
                Log.d(TAG, "Result: " + resultStr);
                JSONObject obj = new JSONObject(resultStr);
                appendOut("=== DECODE RESULTS ===");
                appendOut(obj.toString(2));
                appendOut("\n✓ Decoded CSVs ready for analysis");
                Log.d(TAG, "=== Decoding Complete ===");
            } catch (Exception e) {
                setStage("Error", 100);
                Log.e(TAG, "Decoding error", e);
                appendOut("Error: " + e.toString());
                appendOut("\nStack trace:");
                for (StackTraceElement elem : e.getStackTrace()) {
                    appendOut("  " + elem.toString());
                    Log.e(TAG, "  " + elem.toString());
                }
                if (e.getCause() != null) {
                    appendOut("\nCaused by: " + e.getCause().toString());
                    Log.e(TAG, "Caused by: " + e.getCause().toString());
                }
            } finally {
                mainHandler.post(() -> btnAnalyze.setEnabled(true));
            }
        });
    }

    private void startAnalysis() {
        cancelled = false;
        btnAnalyze.setEnabled(false);
        btnCancel.setEnabled(true);
        txtOutput.setText("");
        executor.submit(() -> {
            try {
                Log.d(TAG, "=== Analysis Started ===");
                Log.d(TAG, "Session dir: " + sessionDir);
                
                setStage("Starting Python...", 5);
                if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                if (cancelled) return;
                
                setStage("Importing...", 15);
                Python py = Python.getInstance();
                Log.d(TAG, "Getting analysis_bridge module...");
                PyObject mod = py.getModule("analysis_bridge");
                Log.d(TAG, "Module loaded successfully");
                if (cancelled) return;

                if (sessionDir != null && !sessionDir.isEmpty()) {
                    setStage("Running full analysis...", 40);
                    Log.d(TAG, "Calling analyze_session with: " + sessionDir);
                    PyObject res = mod.callAttr("analyze_session", sessionDir);
                    Log.d(TAG, "Analysis complete");
                    
                    if (cancelled) return;
                    
                    setStage("Analysis Complete", 100);
                    String resultStr = res.toString();
                    Log.d(TAG, "Result: " + resultStr);
                    JSONObject obj = new JSONObject(resultStr);
                    appendOut("=== ANALYSIS RESULTS ===");
                    appendOut(obj.toString(2));
                    Log.d(TAG, "=== Analysis Complete ===");
                } else {
                    setStage("Error", 100);
                    appendOut("Error: No session directory specified");
                }
            } catch (Exception e) {
                setStage("Error", 100);
                Log.e(TAG, "Analysis error", e);
                appendOut("Error: " + e.toString());
                appendOut("\nStack trace:");
                for (StackTraceElement elem : e.getStackTrace()) {
                    appendOut("  " + elem.toString());
                    Log.e(TAG, "  " + elem.toString());
                }
                if (e.getCause() != null) {
                    appendOut("\nCaused by: " + e.getCause().toString());
                    Log.e(TAG, "Caused by: " + e.getCause().toString());
                }
            } finally {
                mainHandler.post(() -> btnAnalyze.setEnabled(true));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
