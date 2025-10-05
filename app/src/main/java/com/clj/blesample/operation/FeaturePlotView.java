package com.clj.blesample.operation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom view to plot EMG features over time
 */
public class FeaturePlotView extends View {
    
    private Paint paintRms;
    private Paint paintMnf;
    private Paint paintMdf;
    private Paint paintWaveletEntropy;
    private Paint paintSampleEntropy;
    private Paint paintGrid;
    private Paint paintText;
    
    private List<Float> rmsHistory = new ArrayList<>();
    private List<Float> mnfHistory = new ArrayList<>();
    private List<Float> mdfHistory = new ArrayList<>();
    private List<Float> waveletEntropyHistory = new ArrayList<>();
    private List<Float> sampleEntropyHistory = new ArrayList<>();
    
    private static final int MAX_HISTORY = 100; // Keep last 100 data points
    
    // Feature visibility flags
    private boolean showRms = true;
    private boolean showMnf = true;
    private boolean showMdf = false;
    private boolean showWaveletEntropy = false;
    private boolean showSampleEntropy = false;
    
    // Auto-scaling ranges
    private float rmsMax = 100f;
    private float mnfMax = 200f;
    private float mdfMax = 200f;
    private float waveletEntropyMax = 5f;
    private float sampleEntropyMax = 3f;
    
    public FeaturePlotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        paintRms = new Paint();
        paintRms.setColor(Color.RED);
        paintRms.setStrokeWidth(4);
        paintRms.setStyle(Paint.Style.STROKE);
        paintRms.setAntiAlias(true);
        
        paintMnf = new Paint();
        paintMnf.setColor(Color.BLUE);
        paintMnf.setStrokeWidth(4);
        paintMnf.setStyle(Paint.Style.STROKE);
        paintMnf.setAntiAlias(true);
        
        paintMdf = new Paint();
        paintMdf.setColor(Color.GREEN);
        paintMdf.setStrokeWidth(4);
        paintMdf.setStyle(Paint.Style.STROKE);
        paintMdf.setAntiAlias(true);
        
        paintWaveletEntropy = new Paint();
        paintWaveletEntropy.setColor(Color.MAGENTA);
        paintWaveletEntropy.setStrokeWidth(4);
        paintWaveletEntropy.setStyle(Paint.Style.STROKE);
        paintWaveletEntropy.setAntiAlias(true);
        
        paintSampleEntropy = new Paint();
        paintSampleEntropy.setColor(Color.CYAN);
        paintSampleEntropy.setStrokeWidth(4);
        paintSampleEntropy.setStyle(Paint.Style.STROKE);
        paintSampleEntropy.setAntiAlias(true);
        
        paintGrid = new Paint();
        paintGrid.setColor(Color.LTGRAY);
        paintGrid.setStrokeWidth(1);
        paintGrid.setStyle(Paint.Style.STROKE);
        
        paintText = new Paint();
        paintText.setColor(Color.BLACK);
        paintText.setTextSize(24);
        paintText.setAntiAlias(true);
    }
    
    public void addFeatureData(double rms, double mnf, double mdf, double waveletEntropy, double sampleEntropy) {
        // Add new data
        if (!Double.isNaN(rms)) {
            rmsHistory.add((float) rms);
            if (rms > rmsMax) rmsMax = (float) rms * 1.2f;
        }
        if (!Double.isNaN(mnf)) {
            mnfHistory.add((float) mnf);
            if (mnf > mnfMax) mnfMax = (float) mnf * 1.2f;
        }
        if (!Double.isNaN(mdf)) {
            mdfHistory.add((float) mdf);
            if (mdf > mdfMax) mdfMax = (float) mdf * 1.2f;
        }
        if (!Double.isNaN(waveletEntropy)) {
            waveletEntropyHistory.add((float) waveletEntropy);
            if (waveletEntropy > waveletEntropyMax) waveletEntropyMax = (float) waveletEntropy * 1.2f;
        }
        if (!Double.isNaN(sampleEntropy) && !Double.isInfinite(sampleEntropy)) {
            sampleEntropyHistory.add((float) sampleEntropy);
            if (sampleEntropy > sampleEntropyMax) sampleEntropyMax = (float) sampleEntropy * 1.2f;
        }
        
        // Keep only last MAX_HISTORY points
        if (rmsHistory.size() > MAX_HISTORY) rmsHistory.remove(0);
        if (mnfHistory.size() > MAX_HISTORY) mnfHistory.remove(0);
        if (mdfHistory.size() > MAX_HISTORY) mdfHistory.remove(0);
        if (waveletEntropyHistory.size() > MAX_HISTORY) waveletEntropyHistory.remove(0);
        if (sampleEntropyHistory.size() > MAX_HISTORY) sampleEntropyHistory.remove(0);
        
        invalidate(); // Trigger redraw
    }
    
    public void setShowRms(boolean show) {
        this.showRms = show;
        invalidate();
    }
    
    public void setShowMnf(boolean show) {
        this.showMnf = show;
        invalidate();
    }
    
    public void setShowMdf(boolean show) {
        this.showMdf = show;
        invalidate();
    }
    
    public void setShowWaveletEntropy(boolean show) {
        this.showWaveletEntropy = show;
        invalidate();
    }
    
    public void setShowSampleEntropy(boolean show) {
        this.showSampleEntropy = show;
        invalidate();
    }
    
    public void clearHistory() {
        rmsHistory.clear();
        mnfHistory.clear();
        mdfHistory.clear();
        waveletEntropyHistory.clear();
        sampleEntropyHistory.clear();
        invalidate();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
        if (width == 0 || height == 0) return;
        
        // Draw background
        canvas.drawColor(Color.WHITE);
        
        // Draw grid
        drawGrid(canvas, width, height);
        
        // Draw each feature line
        if (showRms && rmsHistory.size() > 1) {
            drawFeatureLine(canvas, rmsHistory, rmsMax, paintRms, width, height);
        }
        if (showMnf && mnfHistory.size() > 1) {
            drawFeatureLine(canvas, mnfHistory, mnfMax, paintMnf, width, height);
        }
        if (showMdf && mdfHistory.size() > 1) {
            drawFeatureLine(canvas, mdfHistory, mdfMax, paintMdf, width, height);
        }
        if (showWaveletEntropy && waveletEntropyHistory.size() > 1) {
            drawFeatureLine(canvas, waveletEntropyHistory, waveletEntropyMax, paintWaveletEntropy, width, height);
        }
        if (showSampleEntropy && sampleEntropyHistory.size() > 1) {
            drawFeatureLine(canvas, sampleEntropyHistory, sampleEntropyMax, paintSampleEntropy, width, height);
        }
        
        // Draw legend
        drawLegend(canvas, width, height);
    }
    
    private void drawGrid(Canvas canvas, int width, int height) {
        // Horizontal lines
        for (int i = 0; i <= 4; i++) {
            float y = height * i / 4f;
            canvas.drawLine(0, y, width, y, paintGrid);
        }
        
        // Vertical lines
        for (int i = 0; i <= 10; i++) {
            float x = width * i / 10f;
            canvas.drawLine(x, 0, x, height, paintGrid);
        }
    }
    
    private void drawFeatureLine(Canvas canvas, List<Float> data, float maxValue, Paint paint, int width, int height) {
        if (data.size() < 2) return;
        
        Path path = new Path();
        float xStep = (float) width / MAX_HISTORY;
        
        // Start from the oldest data point
        int startIdx = Math.max(0, data.size() - MAX_HISTORY);
        float x = 0;
        
        for (int i = startIdx; i < data.size(); i++) {
            float value = data.get(i);
            float y = height - (value / maxValue * height * 0.9f) - height * 0.05f; // 5% margin
            
            if (i == startIdx) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            x += xStep;
        }
        
        canvas.drawPath(path, paint);
    }
    
    private void drawLegend(Canvas canvas, int width, int height) {
        int legendX = 10;
        int legendY = 30;
        int lineLength = 40;
        int lineSpacing = 35;
        
        if (showRms) {
            canvas.drawLine(legendX, legendY, legendX + lineLength, legendY, paintRms);
            canvas.drawText("RMS", legendX + lineLength + 10, legendY + 5, paintText);
            legendY += lineSpacing;
        }
        if (showMnf) {
            canvas.drawLine(legendX, legendY, legendX + lineLength, legendY, paintMnf);
            canvas.drawText("MNF", legendX + lineLength + 10, legendY + 5, paintText);
            legendY += lineSpacing;
        }
        if (showMdf) {
            canvas.drawLine(legendX, legendY, legendX + lineLength, legendY, paintMdf);
            canvas.drawText("MDF", legendX + lineLength + 10, legendY + 5, paintText);
            legendY += lineSpacing;
        }
        if (showWaveletEntropy) {
            canvas.drawLine(legendX, legendY, legendX + lineLength, legendY, paintWaveletEntropy);
            canvas.drawText("W.Ent", legendX + lineLength + 10, legendY + 5, paintText);
            legendY += lineSpacing;
        }
        if (showSampleEntropy) {
            canvas.drawLine(legendX, legendY, legendX + lineLength, legendY, paintSampleEntropy);
            canvas.drawText("S.Ent", legendX + lineLength + 10, legendY + 5, paintText);
        }
    }
}
