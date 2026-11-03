package com.musclelogic.athlete.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class TrendSparklineView extends View {

    private static final int COLOR_FRESH    = Color.parseColor("#FF2E7D32");
    private static final int COLOR_MODERATE = Color.parseColor("#FFE65100");
    private static final int COLOR_HIGH     = Color.parseColor("#FFCC0000");
    private static final int COLOR_UNKNOWN  = Color.parseColor("#FF546E7A");
    private static final int COLOR_LINE     = Color.parseColor("#FFAAAAAA");
    private static final int COLOR_AXIS     = Color.parseColor("#FF555555");

    private final Paint linePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<SessionRecord> records = new ArrayList<>();

    public TrendSparklineView(Context ctx) { super(ctx); init(); }
    public TrendSparklineView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        linePaint.setColor(COLOR_LINE);
        linePaint.setStrokeWidth(3f);
        linePaint.setStyle(Paint.Style.STROKE);

        dotPaint.setStyle(Paint.Style.FILL);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(28f);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        axisPaint.setColor(COLOR_AXIS);
        axisPaint.setTextSize(24f);
    }

    public void setRecords(List<SessionRecord> records) {
        this.records = records;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (records == null || records.isEmpty()) {
            axisPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("No sessions yet", getWidth() / 2f, getHeight() / 2f, axisPaint);
            return;
        }

        float padL = 48f, padR = 24f, padT = 24f, padB = 32f;
        float w = getWidth() - padL - padR;
        float h = getHeight() - padT - padB;
        int n = records.size();

        // y range: 0–50% (cap at 50 for display)
        float yMax = 50f;

        // collect valid (mdfDropPct >= 0) points
        float[] xs = new float[n];
        float[] ys = new float[n];
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            xs[i] = padL + (n == 1 ? w / 2f : i * w / (n - 1));
            float pct = records.get(i).mdfDropPct;
            valid[i] = pct >= 0;
            ys[i] = padT + h - (valid[i] ? Math.min(pct, yMax) / yMax * h : h / 2f);
        }

        // draw connecting line through valid points
        Path path = new Path();
        boolean started = false;
        for (int i = 0; i < n; i++) {
            if (!valid[i]) continue;
            if (!started) { path.moveTo(xs[i], ys[i]); started = true; }
            else            path.lineTo(xs[i], ys[i]);
        }
        canvas.drawPath(path, linePaint);

        // draw dots
        float dotR = 14f;
        for (int i = 0; i < n; i++) {
            SessionRecord r = records.get(i);
            dotPaint.setColor(badgeColor(r.fatigueLevel));
            canvas.drawCircle(xs[i], ys[i], dotR, dotPaint);
            if (valid[i]) {
                String label = String.format("%.0f%%", r.mdfDropPct);
                canvas.drawText(label, xs[i], ys[i] + 9f, labelPaint);
            }
        }

        // y-axis tick labels
        axisPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("0%",  padL - 4f, padT + h, axisPaint);
        canvas.drawText("25%", padL - 4f, padT + h / 2f, axisPaint);
        canvas.drawText("50%", padL - 4f, padT, axisPaint);
    }

    private int badgeColor(String level) {
        if (level == null) return COLOR_UNKNOWN;
        switch (level.toLowerCase()) {
            case "fresh":    return COLOR_FRESH;
            case "moderate": return COLOR_MODERATE;
            case "high":     return COLOR_HIGH;
            default:         return COLOR_UNKNOWN;
        }
    }
}
