package com.clj.blesample.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class SimplePlotView extends View {
    private final Paint gridPaint = new Paint();
    private final Paint[] linePaints = new Paint[3];
    private final Paint markerPaint = new Paint();
    private final Paint textPaint = new Paint();

    private float[] x0, y0, x1, y1, x2, y2;
    private int n0, n1, n2;
    private float[] markers;
    private int nMarkers;

    private float tMin = 0f, tMax = 10f;
    private float yMin = 0f, yMax = 1f;
    private String title = "";

    public SimplePlotView(Context c) { super(c); init(); }
    public SimplePlotView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setBackgroundColor(0xFF101418);
        gridPaint.setColor(0x22FFFFFF);
        gridPaint.setStrokeWidth(1f);
        int[] colors = new int[]{ Color.CYAN, Color.YELLOW, Color.MAGENTA };
        for (int i = 0; i < 3; i++) {
            linePaints[i] = new Paint();
            linePaints[i].setColor(colors[i]);
            linePaints[i].setStrokeWidth(2f);
            linePaints[i].setAntiAlias(true);
        }
        markerPaint.setColor(0x88FF4444);
        markerPaint.setStrokeWidth(2f);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(30f);
        textPaint.setAntiAlias(true);
    }

    public void setTitle(String t) { this.title = t; }
    public void setViewport(float tMin, float tMax, float yMin, float yMax) {
        this.tMin = tMin; this.tMax = tMax; this.yMin = yMin; this.yMax = yMax;
    }
    public void setSeries0(float[] x, float[] y, int n) { this.x0 = x; this.y0 = y; this.n0 = n; }
    public void setSeries1(float[] x, float[] y, int n) { this.x1 = x; this.y1 = y; this.n1 = n; }
    public void setSeries2(float[] x, float[] y, int n) { this.x2 = x; this.y2 = y; this.n2 = n; }
    public void setMarkers(float[] t, int n) { this.markers = t; this.nMarkers = n; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 10 || h <= 10) return;

        int gx = 5;
        int gy = 3;
        for (int i = 1; i < gx; i++) {
            float x = i * (w / (float)gx);
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (int j = 1; j < gy; j++) {
            float y = j * (h / (float)gy);
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        if (markers != null && nMarkers > 0) {
            for (int i = 0; i < nMarkers; i++) {
                float px = mapX(markers[i], w);
                canvas.drawLine(px, 0, px, h, markerPaint);
            }
        }

        drawSeries(canvas, x0, y0, n0, linePaints[0], w, h);
        drawSeries(canvas, x1, y1, n1, linePaints[1], w, h);
        drawSeries(canvas, x2, y2, n2, linePaints[2], w, h);

        if (title != null && !title.isEmpty()) {
            canvas.drawText(title, 12, 34, textPaint);
        }
    }

    private void drawSeries(Canvas c, float[] x, float[] y, int n, Paint p, int w, int h) {
        if (x == null || y == null || n <= 1) return;
        float xPrev = mapX(x[0], w);
        float yPrev = mapY(y[0], h);
        for (int i = 1; i < n; i++) {
            float xCur = mapX(x[i], w);
            float yCur = mapY(y[i], h);
            c.drawLine(xPrev, yPrev, xCur, yCur, p);
            xPrev = xCur;
            yPrev = yCur;
        }
    }

    private float mapX(float t, int w) {
        float denom = (tMax - tMin);
        if (denom <= 1e-6f) denom = 1f;
        return (t - tMin) * (w / denom);
    }

    private float mapY(float v, int h) {
        float denom = (yMax - yMin);
        if (denom <= 1e-6f) denom = 1f;
        float y = (v - yMin) / denom;
        y = 1f - y;
        return y * h;
    }
}
