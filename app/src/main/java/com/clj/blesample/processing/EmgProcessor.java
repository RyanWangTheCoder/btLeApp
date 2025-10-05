package com.clj.blesample.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * EMG preprocessing + feature extraction (Android/Java, no deps).
 * Mirrors Python pipeline:
 * detrend -> bandpass -> notch -> sliding windows -> RMS/MNF/MDF/WaveletEntropy/SampleEntropy
 */
public final class EmgProcessor {

    // ---------- Public API ----------

    public static class FeatureSeries {
        public final double[] tCenterSec;    // per-window time center (s)
        public final double[] rms;
        public final double[] mnfHz;
        public final double[] mdfHz;
        public final double[] waveletEntropy;
        public final double[] sampleEntropy;

        public FeatureSeries(double[] tCenterSec, double[] rms, double[] mnfHz,
                             double[] mdfHz, double[] waveletEntropy, double[] sampleEntropy) {
            this.tCenterSec = tCenterSec;
            this.rms = rms;
            this.mnfHz = mnfHz;
            this.mdfHz = mdfHz;
            this.waveletEntropy = waveletEntropy;
            this.sampleEntropy = sampleEntropy;
        }
    }

    /** Detrend (remove DC), band-pass, and notch (60 Hz + optional harmonics). */
    public static double[] preprocess(double[] x, double fsHz, double bpLowHz, double bpHighHz,
                                      double notchHz, double notchQ, int[] notchHarmonics) {
        if (x == null || x.length == 0) return new double[0];

        double[] y = detrendConstant(x);

        // Band-pass biquad (0 < low < high < fs/2). Use zero-phase (forward+reverse).
        y = bandpassZeroPhase(y, fsHz, bpLowHz, Math.min(0.45 * fsHz, bpHighHz), 2); // 2nd-order is stable & low pad

        // Notch at mains (e.g., 60 Hz) and selected harmonics if below Nyquist
        y = notchZeroPhase(y, fsHz, notchHz, notchQ);
        if (notchHarmonics != null) {
            for (int h : notchHarmonics) {
                double f = notchHz * h;
                if (f < fsHz / 2.0 - 1.0) {
                    y = notchZeroPhase(y, fsHz, f, notchQ);
                }
            }
        }
        return y;
    }

    /** Compute features over sliding windows, with adaptive behavior for short signals. */
    public static FeatureSeries computeFeatures(double[] x, double fsHz, double winSec, double stepSec) {
        if (x == null) x = new double[0];
        int n = x.length;
        if (n == 0 || fsHz <= 0) {
            return new FeatureSeries(new double[0], new double[0], new double[0],
                    new double[0], new double[0], new double[0]);
        }

        int winSamples = (int) Math.round(winSec * fsHz);
        int stepSamples = (int) Math.round(stepSec * fsHz);
        if (winSamples <= 0) winSamples = Math.max(1, n);
        if (stepSamples <= 0) stepSamples = Math.max(1, winSamples / 4);

        // If too short, shrink window/hop
        if (n < winSamples) {
            winSamples = n;
            stepSamples = Math.max(1, winSamples / 4);
        }

        List<int[]> windows = new ArrayList<>();
        for (int start = 0; start + winSamples <= n; start += stepSamples) {
            windows.add(new int[]{start, start + winSamples});
        }
        if (windows.isEmpty()) {
            windows.add(new int[]{0, n}); // ensure at least one window
        }

        int W = windows.size();
        double[] tCenter = new double[W];
        double[] rms = new double[W];
        double[] mnf = new double[W];
        double[] mdf = new double[W];
        double[] went = new double[W];
        double[] sampen = new double[W];

        for (int i = 0; i < W; i++) {
            int i0 = windows.get(i)[0], i1 = windows.get(i)[1];
            double[] seg = Arrays.copyOfRange(x, i0, i1);
            tCenter[i] = 0.5 * (i0 + i1) / fsHz;
            rms[i] = rms(seg);
            double[] mnfMdf = mnfMdfWelch(seg, fsHz);
            mnf[i] = mnfMdf[0];
            mdf[i] = mnfMdf[1];
            went[i] = waveletEntropyDb4(seg);
            sampen[i] = sampleEntropy(seg, 2, Double.NaN); // r auto=0.2*std if NaN
        }

        return new FeatureSeries(tCenter, rms, mnf, mdf, went, sampen);
    }

    // ---------- Basic ops ----------

    private static double[] detrendConstant(double[] x) {
        double m = mean(x);
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i] - m;
        return y;
    }

    private static double mean(double[] x) {
        if (x.length == 0) return 0;
        double s = 0;
        for (double v : x) s += v;
        return s / x.length;
    }

    private static double std(double[] x) {
        if (x.length == 0) return 0;
        double mu = mean(x);
        double ss = 0;
        for (double v : x) {
            double d = v - mu;
            ss += d * d;
        }
        return Math.sqrt(ss / Math.max(1, x.length - 1));
    }

    // ---------- Filters (biquad + filtfilt-like) ----------

    private static class Biquad {
        // Direct Form I
        final double b0, b1, b2, a0, a1, a2;

        Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a0 = a0;
            this.a1 = a1;
            this.a2 = a2;
        }

        void filterInPlace(double[] x) {
            double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
            for (int n = 0; n < x.length; n++) {
                double xn = x[n];
                double yn = (b0 * xn + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
                x2 = x1;
                x1 = xn;
                y2 = y1;
                y1 = yn;
                x[n] = yn;
            }
        }
    }

    /** RBJ biquad band-pass (constant skirt gain). */
    private static Biquad designBandpass(double fsHz, double fLow, double fHigh) {
        // Use center frequency & Q from fLow/fHigh (approx)
        double f0 = Math.sqrt(fLow * fHigh);
        f0 = Math.max(1e-6, Math.min(f0, 0.499 * fsHz));
        double bw = Math.max(1e-6, fHigh - fLow);
        double Q = f0 / bw;

        double w0 = 2.0 * Math.PI * (f0 / fsHz);
        double alpha = Math.sin(w0) / (2.0 * Math.max(1e-6, Q));

        double b0 = alpha;
        double b1 = 0.0;
        double b2 = -alpha;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * Math.cos(w0);
        double a2 = 1.0 - alpha;

        return new Biquad(b0, b1, b2, a0, a1, a2);
    }

    /** RBJ biquad notch (band-stop). */
    private static Biquad designNotch(double fsHz, double f0, double Q) {
        f0 = Math.max(1e-6, Math.min(f0, 0.499 * fsHz));
        double w0 = 2.0 * Math.PI * (f0 / fsHz);
        double alpha = Math.sin(w0) / (2.0 * Math.max(1e-6, Q));

        double b0 = 1.0;
        double b1 = -2.0 * Math.cos(w0);
        double b2 = 1.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * Math.cos(w0);
        double a2 = 1.0 - alpha;

        return new Biquad(b0, b1, b2, a0, a1, a2);
    }

    private static double[] filtfilt(Biquad biq, double[] x) {
        int n = x.length;
        if (n <= 6) {
            // short-signal guard; return copy
            return Arrays.copyOf(x, x.length);
        }
        double[] y = Arrays.copyOf(x, n);
        biq.filterInPlace(y);
        reverseInPlace(y);
        biq.filterInPlace(y);
        reverseInPlace(y);
        return y;
    }

    private static void reverseInPlace(double[] y) {
        for (int i = 0, j = y.length - 1; i < j; i++, j--) {
            double tmp = y[i];
            y[i] = y[j];
            y[j] = tmp;
        }
    }

    private static double[] bandpassZeroPhase(double[] x, double fsHz, double lowHz, double highHz, int order) {
        // Use a cascade of identical biquads to approximate higher order if needed
        int sections = Math.max(1, order / 2);
        double[] y = Arrays.copyOf(x, x.length);
        for (int s = 0; s < sections; s++) {
            Biquad bq = designBandpass(fsHz, lowHz, highHz);
            y = filtfilt(bq, y);
        }
        return y;
    }

    private static double[] notchZeroPhase(double[] x, double fsHz, double f0, double Q) {
        Biquad bq = designNotch(fsHz, f0, Q);
        return filtfilt(bq, x);
    }

    // ---------- Features ----------

    public static double rms(double[] x) {
        if (x.length == 0) return Double.NaN;
        double s = 0;
        for (double v : x) s += v * v;
        return Math.sqrt(s / x.length);
    }

    /** Welch PSD -> MNF/MDF (robust for short windows). */
    public static double[] mnfMdfWelch(double[] x, double fsHz) {
        int n = x.length;
        if (n < 8) return new double[]{Double.NaN, Double.NaN};

        int nperseg = Math.min(n, 256);
        int noverlap = nperseg / 2;
        int step = Math.max(1, nperseg - noverlap);

        List<double[]> psds = new ArrayList<>();
        List<double[]> freqs = new ArrayList<>();

        double[] win = hann(nperseg);
        double U = 0;
        for (double v : win) U += v * v; // window power normalization

        for (int start = 0; start + nperseg <= n; start += step) {
            double[] seg = Arrays.copyOfRange(x, start, start + nperseg);
            for (int i = 0; i < seg.length; i++) seg[i] *= win[i];

            // real FFT magnitude squared
            Complex[] X = FFT.fftReal(seg);
            int kMax = X.length; // includes DC..Nyquist
            double[] Pxx = new double[kMax];
            double[] f = new double[kMax];
            for (int k = 0; k < kMax; k++) {
                double re = X[k].re, im = X[k].im;
                Pxx[k] = (re * re + im * im) / (fsHz * U); // density-like
                f[k] = k * fsHz / (2.0 * (kMax - 1)); // 0..fs/2
            }
            psds.add(Pxx);
            freqs.add(f);
        }

        if (psds.isEmpty()) return new double[]{Double.NaN, Double.NaN};

        int K = psds.get(0).length;
        double[] P = new double[K];
        double[] f = freqs.get(0);
        for (double[] pxx : psds) {
            for (int k = 0; k < K; k++) P[k] += pxx[k];
        }
        for (int k = 0; k < K; k++) P[k] /= psds.size();

        // MNF
        double psdSum = 0, num = 0;
        for (int k = 0; k < K; k++) {
            double pk = Math.max(P[k], 1e-20);
            psdSum += pk;
            num += f[k] * pk;
        }
        double mnf = num / psdSum;

        // MDF
        double half = 0.5 * psdSum;
        double cdf = 0;
        double mdf = f[K - 1];
        for (int k = 0; k < K; k++) {
            cdf += Math.max(P[k], 0);
            if (cdf >= half) {
                mdf = f[k];
                break;
            }
        }

        return new double[]{mnf, mdf};
    }

    /** Wavelet entropy via db4 multilevel DWT. Returns NaN if no valid decomposition. */
    public static double waveletEntropyDb4(double[] x) {
        if (x.length < 8) return Double.NaN; // too short for db4
        DWTDb4.Result wd = DWTDb4.wavedec(x); // auto max level
        if (wd == null || wd.coeffs.isEmpty()) return Double.NaN;

        // Energy of all coeff arrays (approx + details)
        double total = 0;
        double[] energies = new double[wd.coeffs.size()];
        for (int i = 0; i < wd.coeffs.size(); i++) {
            double[] c = wd.coeffs.get(i);
            double e = 0;
            for (double v : c) e += v * v;
            energies[i] = e;
            total += e;
        }
        if (!(total > 0)) return Double.NaN;

        double ent = 0;
        for (double e : energies) {
            double p = e / total;
            ent += -p * Math.log(p + 1e-20);
        }
        return ent;
    }

    /** Sample Entropy (m, r). If r is NaN -> r = 0.2*std(x). */
    public static double sampleEntropy(double[] x, int m, double r) {
        int N = x.length;
        if (N <= m + 1) return Double.NaN;
        if (Double.isNaN(r)) r = 0.2 * std(x);

        double phi_m = phi(x, m, r);
        double phi_m1 = phi(x, m + 1, r);
        if (phi_m <= 1e-20 || phi_m1 <= 1e-20) return Double.POSITIVE_INFINITY;
        return -Math.log(phi_m1 / phi_m);
    }

    private static double phi(double[] x, int m, double r) {
        int N = x.length;
        int count = 0;
        int nTemplates = N - m + 1;
        if (nTemplates <= 1) return 0.0;

        for (int i = 0; i < nTemplates; i++) {
            for (int j = i + 1; j < nTemplates; j++) {
                double d = 0;
                for (int k = 0; k < m; k++) {
                    double diff = Math.abs(x[i + k] - x[j + k]);
                    if (diff > d) d = diff; // Chebyshev
                    if (d > r) break;
                }
                if (d <= r) count++;
            }
        }
        double denom = (nTemplates * (nTemplates - 1)) / 2.0;
        return (denom > 0) ? (count / denom) : 0.0;
    }

    // ---------- Windows, FFT, utility ----------

    private static double[] hann(int n) {
        double[] w = new double[n];
        if (n == 1) {
            w[0] = 1.0;
            return w;
        }
        for (int i = 0; i < n; i++) {
            w[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)));
        }
        return w;
    }

    // Minimal complex & FFT for real input
    private static final class Complex {
        final double re, im;

        Complex(double re, double im) {
            this.re = re;
            this.im = im;
        }
    }

    /** Real-to-complex FFT returning bins 0..Nyquist (length n/2+1 when n even). */
    private static final class FFT {
        static Complex[] fftReal(double[] x) {
            int n = x.length;
            int m = nextPow2(n); // zero-pad to power of two
            double[] xr = new double[m];
            System.arraycopy(x, 0, xr, 0, n);
            double[] xi = new double[m];
            fftInPlace(xr, xi);

            int kMax = m / 2 + 1;
            Complex[] out = new Complex[kMax];
            for (int k = 0; k < kMax; k++) {
                out[k] = new Complex(xr[k], xi[k]);
            }
            return out;
        }

        private static int nextPow2(int n) {
            int p = 1;
            while (p < n) p <<= 1;
            return p;
        }

        // Iterative radix-2 Cooley-Tukey
        private static void fftInPlace(double[] re, double[] im) {
            int n = re.length;

            // bit-reversal permutation
            int j = 0;
            for (int i = 0; i < n; i++) {
                if (i < j) {
                    double tr = re[i];
                    re[i] = re[j];
                    re[j] = tr;
                    double ti = im[i];
                    im[i] = im[j];
                    im[j] = ti;
                }
                int m = n >>> 1;
                while (j >= m && m >= 1) {
                    j -= m;
                    m >>>= 1;
                }
                j += m;
            }

            // butterflies
            for (int len = 2; len <= n; len <<= 1) {
                double ang = -2.0 * Math.PI / len;
                double wLenRe = Math.cos(ang);
                double wLenIm = Math.sin(ang);
                for (int i = 0; i < n; i += len) {
                    double wr = 1.0, wi = 0.0;
                    for (int k = 0; k < len / 2; k++) {
                        int u = i + k;
                        int v = u + len / 2;
                        double tr = wr * re[v] - wi * im[v];
                        double ti = wr * im[v] + wi * re[v];
                        re[v] = re[u] - tr;
                        im[v] = im[u] - ti;
                        re[u] = re[u] + tr;
                        im[u] = im[u] + ti;
                        double tmp = wr * wLenRe - wi * wLenIm;
                        wi = wr * wLenIm + wi * wLenRe;
                        wr = tmp;
                    }
                }
            }
        }
    }

    // ---------- Minimal db4 DWT (orthogonal) with symmetric extension ----------

    private static final class DWTDb4 {
        // db4 decimation filters (Daubechies-4) – orthonormal
        // Low-pass decomposition (h):
        private static final double[] h = {
                0.48296291314469025,
                0.8365163037378079,
                0.2241438680420134,
                -0.12940952255126037
        };
        // High-pass decomposition (g): alternating signs from h (Quadrature mirror)
        private static final double[] g = {
                -0.12940952255126037,
                -0.2241438680420134,
                0.8365163037378079,
                -0.48296291314469025
        };

        static class Result {
            final List<double[]> coeffs; // [approx_L, detail_L, ..., detail_1]

            Result(List<double[]> coeffs) {
                this.coeffs = coeffs;
            }
        }

        static Result wavedec(double[] x) {
            List<double[]> all = new ArrayList<>();
            double[] a = Arrays.copyOf(x, x.length);
            int level = 0;

            while (a.length >= 8) { // minimum length for db4 step
                Pair ad = dwtStep(a);
                a = ad.approx;
                double[] d = ad.detail;
                level++;

                // we store progressively, keep for entropy computation
                if (level == 1) {
                    all.add(a); // A_L (will be replaced in next loop)
                    all.add(d); // D_1
                } else {
                    all.set(0, a); // update latest approx at index 0
                    all.add(d); // D_level
                }
                if (a.length < 8) break;
            }

            if (all.isEmpty()) return null;
            return new Result(all);
        }

        private static class Pair {
            final double[] approx, detail;

            Pair(double[] a, double[] d) {
                approx = a;
                detail = d;
            }
        }

        private static Pair dwtStep(double[] x) {
            int n = x.length;
            int outLen = (n + 1) / 2; // decimate by 2 with symmetric extension
            double[] a = new double[outLen];
            double[] d = new double[outLen];

            for (int k = 0; k < outLen; k++) {
                double accA = 0, accD = 0;
                for (int i = 0; i < 4; i++) {
                    int idx = 2 * k + i;
                    double xv = symAt(x, idx);
                    accA += xv * h[i];
                    accD += xv * g[i];
                }
                a[k] = accA;
                d[k] = accD;
            }
            return new Pair(a, d);
        }

        private static double symAt(double[] x, int i) {
            int n = x.length;
            // symmetric padding like PyWavelets 'symmetric'
            while (i < 0 || i >= n) {
                if (i < 0) i = -i - 1;
                if (i >= n) i = 2 * n - i - 1;
            }
            return x[i];
        }
    }
}
