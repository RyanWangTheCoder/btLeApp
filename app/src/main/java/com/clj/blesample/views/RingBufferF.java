package com.clj.blesample.views;

public class RingBufferF {
    private final float[] xs;
    private final float[] ys;
    private final int cap;
    private volatile int head = 0;
    private volatile int size = 0;

    public RingBufferF(int capacity) {
        this.cap = Math.max(16, capacity);
        this.xs = new float[this.cap];
        this.ys = new float[this.cap];
    }

    public void add(float x, float y) {
        int h = head;
        xs[h] = x;
        ys[h] = y;
        h++;
        if (h >= cap) h = 0;
        head = h;
        if (size < cap) size++;
    }

    public int snapshot(float tMin, float tMax, float[] outX, float[] outY) {
        int n = Math.min(size, Math.min(outX.length, outY.length));
        if (n <= 0) return 0;
        int start = head - size;
        if (start < 0) start += cap;
        int idx = start;
        int k = 0;
        for (int i = 0; i < size; i++) {
            float x = xs[idx];
            if (x >= tMin && x <= tMax) {
                outX[k] = x;
                outY[k] = ys[idx];
                k++;
                if (k >= n) break;
            }
            idx++;
            if (idx >= cap) idx = 0;
        }
        return k;
    }

    /** Return all y-values in chronological order as a new float[]. */
    public float[] snapshotY() {
        int n = size;
        float[] out = new float[n];
        if (n <= 0) return out;
        int start = head - n;
        if (start < 0) start += cap;
        int idx = start;
        for (int i = 0; i < n; i++) {
            out[i] = ys[idx];
            idx++;
            if (idx >= cap) idx = 0;
        }
        return out;
    }

    public float latestX() {
        if (size <= 0) return 0f;
        int idx = head - 1;
        if (idx < 0) idx += cap;
        return xs[idx];
    }
}
