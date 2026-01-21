package com.clj.blesample.views;

/** Ring buffer for marker times (vertical lines). */
public class MarkerBufferF {
    private final float[] ts;
    private final int cap;
    private volatile int head = 0;
    private volatile int size = 0;

    public MarkerBufferF(int capacity) {
        this.cap = Math.max(16, capacity);
        this.ts = new float[this.cap];
    }

    public void add(float t) {
        int h = head;
        ts[h] = t;
        h++;
        if (h >= cap) h = 0;
        head = h;
        if (size < cap) size++;
    }

    public int snapshot(float tMin, float tMax, float[] outT) {
        int n = Math.min(size, outT.length);
        if (n <= 0) return 0;
        int start = head - size;
        if (start < 0) start += cap;
        int k = 0;
        int idx = start;
        for (int i = 0; i < size; i++) {
            float t = ts[idx];
            if (t >= tMin && t <= tMax) {
                outT[k++] = t;
                if (k >= n) break;
            }
            idx++;
            if (idx >= cap) idx = 0;
        }
        return k;
    }

    public float latestT() {
        if (size <= 0) return 0f;
        int idx = head - 1;
        if (idx < 0) idx += cap;
        return ts[idx];
    }
}
