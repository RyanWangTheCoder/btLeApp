package com.clj.blesample.capture;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.clj.blesample.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SessionRowAdapter extends RecyclerView.Adapter<SessionRowAdapter.VH> {

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.US);

    private final List<SessionRecord> records;

    public SessionRowAdapter(List<SessionRecord> records) {
        this.records = records;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_session_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        // show newest first
        SessionRecord r = records.get(records.size() - 1 - position);

        h.txtDate.setText(DATE_FMT.format(new Date(r.ts)));

        int min = r.durationSec / 60;
        int sec = r.durationSec % 60;
        String drop = (r.mdfDropPct >= 0)
                ? String.format(Locale.US, "MDF −%.0f%%", r.mdfDropPct)
                : "MDF n/a";
        h.txtStats.setText(min + " min " + sec + " sec  ·  " + r.swingCount + " swings  ·  " + drop);

        String badge;
        int color;
        switch (r.fatigueLevel == null ? "unknown" : r.fatigueLevel.toLowerCase(Locale.US)) {
            case "fresh":
                badge = "FRESH";    color = Color.parseColor("#FF2E7D32"); break;
            case "moderate":
                badge = "MODERATE"; color = Color.parseColor("#FFE65100"); break;
            case "high":
                badge = "HIGH";     color = Color.parseColor("#FFCC0000"); break;
            default:
                badge = "—";        color = Color.parseColor("#FF546E7A"); break;
        }
        h.txtBadge.setText(badge);
        h.txtBadge.setBackgroundColor(color);
    }

    @Override
    public int getItemCount() { return records.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView txtDate, txtStats, txtBadge;
        VH(@NonNull View v) {
            super(v);
            txtDate  = v.findViewById(R.id.txtRowDate);
            txtStats = v.findViewById(R.id.txtRowStats);
            txtBadge = v.findViewById(R.id.txtRowBadge);
        }
    }
}
