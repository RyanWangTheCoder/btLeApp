package com.clj.blesample.analysis;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.clj.blesample.R;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProcessingActivity extends AppCompatActivity {
    public static final String EXTRA_SESSION_DIR = "session_dir";

    private ProgressBar progressBar;
    private TextView txtStage;
    private TextView txtOutput;
    private Button btnStart;
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
        btnStart = findViewById(R.id.btnStart);
        btnCancel = findViewById(R.id.btnCancel);

        sessionDir = getIntent().getStringExtra(EXTRA_SESSION_DIR);

        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);

        btnStart.setOnClickListener(v -> startProcessing());
        btnCancel.setOnClickListener(v -> { cancelled = true; btnCancel.setEnabled(false); txtStage.setText("Cancelled"); });
        startProcessing();
    }

    private void setStage(String s, int pct) {
        mainHandler.post(() -> { txtStage.setText(s); progressBar.setProgress(pct); });
    }

    private void appendOut(String s) {
        mainHandler.post(() -> txtOutput.append(s + "\n"));
    }

    private void startProcessing() {
        cancelled = false;
        btnStart.setEnabled(false);
        btnCancel.setEnabled(true);
        txtOutput.setText("");
        executor.submit(() -> {
            try {
                setStage("Starting Python...", 5);
                if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                if (cancelled) return;
                setStage("Importing...", 15);
                Python py = Python.getInstance();
                PyObject mod = py.getModule("analysis_bridge");
                if (cancelled) return;

                PyObject res;
                if (sessionDir != null && !sessionDir.isEmpty()) {
                    setStage("Processing saved session...", 40);
                    res = mod.callAttr("process_session", sessionDir);
                } else {
                    setStage("Running demo...", 40);
                    res = mod.callAttr("run_demo");
                }
                if (cancelled) return;
                setStage("Done", 100);
                JSONObject obj = new JSONObject(res.toString());
                appendOut(obj.toString(2));
            } catch (Exception e) {
                setStage("Error", 100);
                appendOut(e.toString());
            } finally {
                mainHandler.post(() -> btnStart.setEnabled(true));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
