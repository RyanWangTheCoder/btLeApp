package com.clj.blesample.capture;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.clj.blesample.R;

import java.util.Locale;
import java.util.Date;


public class SessionSummaryActivity extends AppCompatActivity {

    public static final String EXTRA_SWING_COUNT   = "SWING_COUNT";
    public static final String EXTRA_DURATION_SEC  = "DURATION_SEC";
    public static final String EXTRA_FATIGUE_LEVEL = "FATIGUE_LEVEL";
    public static final String EXTRA_MDF_DROP_PCT  = "MDF_DROP_PCT";  // -1 = not available

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_summary);

        Intent in = getIntent();
        int swingCount  = in.getIntExtra(EXTRA_SWING_COUNT, 0);
        int durationSec = in.getIntExtra(EXTRA_DURATION_SEC, 0);
        String level    = in.getStringExtra(EXTRA_FATIGUE_LEVEL);
        if (level == null) level = "unknown";
        float mdfDropPct = in.getFloatExtra(EXTRA_MDF_DROP_PCT, -1f);

        int durationMin = durationSec / 60;
        int durationSecRem = durationSec % 60;

        TextView txtStats = findViewById(R.id.txtSummaryStats);
        TextView txtBadge = findViewById(R.id.txtFatigueBadge);
        TextView txtRec   = findViewById(R.id.txtRecommendation);
        Button   btnDone    = findViewById(R.id.btnSummaryDone);
        Button   btnHistory = findViewById(R.id.btnViewHistory);

        String dropStr = (mdfDropPct >= 0)
                ? String.format(Locale.US, "%.1f%%", mdfDropPct)
                : "n/a (baseline not reached)";

        txtStats.setText(
                "Duration:    " + durationMin + " min " + durationSecRem + " sec\n" +
                "Swings:      " + swingCount + "\n" +
                "MDF drop:    " + dropStr);

        String rec;
        int badgeColor;
        String badgeText;

        switch (level.toLowerCase(Locale.US)) {
            case "moderate":
                badgeText  = "MODERATE FATIGUE";
                badgeColor = Color.parseColor("#FFE65100");  // deep orange
                rec = "Moderate forearm fatigue detected.\n\n" +
                      "Rest 24 hours before the next session. " +
                      "Focus on technique and lighter hitting next time.";
                break;
            case "high":
                badgeText  = "HIGH FATIGUE";
                badgeColor = Color.parseColor("#FFCC0000");  // red
                rec = "High forearm fatigue detected.\n\n" +
                      "Rest 48 hours before the next session. " +
                      "Consider consulting a coach if fatigue is persistent.";
                break;
            case "fresh":
                badgeText  = "FRESH";
                badgeColor = Color.parseColor("#FF2E7D32");  // green
                rec = "Good session — forearm fatigue was minimal.\n\n" +
                      "Light stretching and hydration recommended. " +
                      "Ready to play again in 2–4 hours.";
                break;
            default:
                badgeText  = "MONITORING";
                badgeColor = Color.parseColor("#FF546E7A");  // blue-grey
                if (swingCount >= 20) {
                    rec = "Swings were detected but the EMG signal quality was insufficient " +
                          "to compute an MDF baseline.\n\n" +
                          "Check electrode placement and contact. Rest as a precaution.";
                } else {
                    rec = "Not enough swings to establish a fatigue baseline " +
                          "(need 20+ swings with EMG signal).\n\n" +
                          "Try a longer session to see fatigue analysis.";
                }
                break;
        }

        txtBadge.setText(badgeText);
        txtBadge.setBackgroundColor(badgeColor);
        txtRec.setText(rec);

        // persist to history
        SessionRecord record = new SessionRecord(
                System.currentTimeMillis(), durationSec, swingCount, level, mdfDropPct);
        new SessionHistoryManager(this).append(record);

        btnDone.setOnClickListener(v -> finish());
        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, SessionHistoryActivity.class)));
    }
}
