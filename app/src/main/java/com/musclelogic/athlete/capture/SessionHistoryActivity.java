package com.musclelogic.athlete.capture;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.musclelogic.athlete.R;

import java.util.List;

public class SessionHistoryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_history);

        SessionHistoryManager mgr = new SessionHistoryManager(this);
        List<SessionRecord> records = mgr.loadAll();

        TrendSparklineView sparkline = findViewById(R.id.sparkline);
        sparkline.setRecords(records);

        RecyclerView recycler = findViewById(R.id.recyclerHistory);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recycler.setAdapter(new SessionRowAdapter(records));

        Button btnClear = findViewById(R.id.btnClearHistory);
        btnClear.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Clear History")
                        .setMessage("Delete all saved sessions?")
                        .setPositiveButton("Delete", (d, w) -> {
                            mgr.clear();
                            records.clear();
                            sparkline.setRecords(records);
                            recycler.getAdapter().notifyDataSetChanged();
                        })
                        .setNegativeButton("Cancel", null)
                        .show()
        );
    }
}
