# Swing Detection Design Decision

**Date:** 2026-02-27  
**Status:** Implemented  
**Decision:** Align Android app and Python plotter to use gyroscope-based detection

---

## Problem Statement

We had **three different swing detection implementations** across our codebase:

1. **Android App (Java)** - Real-time visual feedback during recording
2. **Python Plotter (sensordata_plotter.py)** - Real-time monitoring & fatigue analysis
3. **Offline Analysis (emg_util.py)** - Post-processing for research

The Android app and Python plotter were **inconsistent**, using different sensors and methods, making it difficult to compare results and debug issues.

---

## Initial Analysis

### Three Implementations Compared

| Feature | Android (Before) | Python Plotter | Offline Analysis |
|---------|-----------------|----------------|------------------|
| **Sensor** | Accelerometer | **Gyroscope** | Gyroscope |
| **Method** | Fixed threshold | Fixed threshold | Z-score (adaptive) |
| **Threshold** | 20,000 ADC | 150 dps | 5.0 σ |
| **Debounce** | 300ms | 300ms | 300ms |
| **Real-time** | Yes | Yes | No |
| **Purpose** | Visual feedback | Fatigue monitoring | Accurate segmentation |

### Key Inconsistency

**Android** and **Python Plotter** both provide real-time detection but used:
- ❌ Different sensors (accelerometer vs gyroscope)
- ❌ Different threshold units (ADC vs dps)
- ✅ Same debounce time (300ms)
- ✅ Same approach (fixed threshold)

---

## Options Considered

### Option 1: Keep Status Quo
**Pros:**
- No code changes needed
- Android app already working

**Cons:**
- ❌ Inconsistent results between tools
- ❌ Harder to debug (different algorithms)
- ❌ Confusing for users (different thresholds)
- ❌ Accelerometer has technical limitations

### Option 2: Make Python Plotter Match Android (Accelerometer)
**Pros:**
- Python plotter changes only
- Android app unchanged

**Cons:**
- ❌ Wrong direction (accelerometer is inferior)
- ❌ Doesn't fix technical issues
- ❌ Offline analysis still different

### Option 3: Make Android Match Python Plotter (Gyroscope) ✅
**Pros:**
- ✅ Better detection quality (gyroscope superior)
- ✅ Consistency across real-time tools
- ✅ Same threshold units (dps)
- ✅ Easier to debug/compare
- ✅ Low implementation effort (~1 hour)

**Cons:**
- Minor code changes needed
- Users need to re-tune threshold (but better defaults)

---

## Technical Comparison: Accelerometer vs Gyroscope

### Accelerometer Limitations

```
At Rest:    ~16,384 (1g gravity baseline)
Walking:    18,000-22,000 (false positives!)
Swing:      20,000-40,000
Problem:    Gravity contamination, orientation-dependent
```

**Issues:**
- ❌ 1g gravity baseline varies with arm orientation
- ❌ Walking/gestures trigger false positives
- ❌ Throwing ball for serve triggers detection
- ❌ Threshold in arbitrary ADC units (not intuitive)

### Gyroscope Advantages

```
At Rest:    ~0 dps (no baseline!)
Walking:    10-50 dps (ignored)
Swing:      150-500 dps (clean signal)
Benefit:    Direct rotation measurement
```

**Benefits:**
- ✅ Zero baseline (no gravity)
- ✅ Measures rotation directly (tennis swing = rotation)
- ✅ Fewer false positives
- ✅ Threshold in physical units (dps = intuitive)
- ✅ Industry standard for motion detection

---

## Decision: Use Gyroscope for Real-time Detection

### Implementation Strategy

**Align Tools #1 & #2 (Real-time):**
- Both use **gyroscope + 150 dps threshold**
- Consistent detection across Android & Python plotter
- Easy to compare/debug

**Keep Tool #3 Different (Offline):**
- Uses **gyroscope + z-score (5σ)**
- More accurate post-processing
- Different purpose (analysis vs feedback)

---

## Implementation Details

### Android App Changes

#### 1. Constants Updated
```java
// BEFORE (Accelerometer)
private volatile float imuThreshold = 20000f;  // ADC units
private static final float IMU_FIXED_MAX = 40000f;

// AFTER (Gyroscope)
private volatile float imuThreshold = 150f;  // dps
private static final float IMU_FIXED_MAX = 600f;  // dps
private static final float GYRO_SCALE_FACTOR = 16.4f;  // ICM-20948 scale
```

#### 2. Detection Logic Updated
```java
// BEFORE (Accelerometer only)
short ax = (short)u16le(buf, base);
short ay = (short)u16le(buf, base + 2);
short az = (short)u16le(buf, base + 4);
float amag = sqrt(ax² + ay² + az²);
if (amag >= imuThreshold && debounce) { /* detect */ }

// AFTER (Gyroscope for detection, accelerometer for plotting)
short ax = (short)u16le(buf, base);
short ay = (short)u16le(buf, base + 2);
short az = (short)u16le(buf, base + 4);
short gx = (short)u16le(buf, base + 6);
short gy = (short)u16le(buf, base + 8);
short gz = (short)u16le(buf, base + 10);

float amag = sqrt(ax² + ay² + az²);  // For plotting
float gmag = sqrt(gx² + gy² + gz²);
float gmag_dps = gmag / 16.4f;  // Convert to dps

imuBuf.add(tSec, amag);  // Plot accelerometer
if (gmag_dps >= imuThreshold && debounce) { /* detect using gyro */ }
```

#### 3. UI Updated
```java
// BEFORE
txtImuThr.setText("IMU marker threshold: " + (int)imuThreshold);
final int thrMin = 5000;   // ADC
final int thrMax = 80000;  // ADC

// AFTER
txtImuThr.setText("Gyro swing threshold: " + (int)imuThreshold + " dps");
final int thrMin = 50;    // dps
final int thrMax = 500;   // dps
```

### Python Plotter (No Changes Needed)
Already uses gyroscope at 150 dps - perfect!

### Offline Analysis (No Changes Needed)
Uses z-score method for more accurate segmentation - appropriate for its purpose.

---

## Threshold Recommendations

### Gyroscope Thresholds (dps)

| Threshold | Sensitivity | Use Case |
|-----------|-------------|----------|
| 50-100 | Very High | Practice swings, all movements |
| **100-150** | **High** | **Light to normal swings** |
| **150-200** | **Optimal** | **Normal tennis swings** ✓ |
| 200-300 | Medium | Hard groundstrokes only |
| 300-500 | Low | Serves, smashes only |

**Default: 150 dps** (matches Python plotter)

---

## Expected Behavior

### During Recording (Real-time)
- **Android app** shows markers using gyro @ 150 dps
- **Python plotter** shows markers using gyro @ 150 dps
- **Both should match closely** (same algorithm!)

### After Recording (Offline)
- **Offline analysis** may find slightly different swings
- Z-score method is more accurate
- May catch swings missed by threshold
- May filter out false positives

**This is GOOD!** Real-time gives immediate feedback, offline gives accurate analysis.

---

## Benefits Achieved

### ✅ Consistency
- Android and Python plotter now use identical algorithm
- Same sensor, same threshold units, same method
- Easy to compare results

### ✅ Better Detection
- Fewer false positives (walking, gestures ignored)
- Catches light swings (rotation-based)
- More intuitive threshold (dps vs ADC)

### ✅ Maintainability
- One algorithm to document
- Easier to debug issues
- Transferable knowledge

### ✅ User Experience
- More reliable swing detection
- Intuitive threshold adjustment
- Consistent across tools

---

## Testing & Validation

### Test Cases
1. **Normal swing** - Should detect (150-300 dps)
2. **Light practice** - Should detect (100-200 dps)
3. **Walking** - Should NOT detect (< 50 dps)
4. **Throwing ball** - Should NOT detect (< 100 dps)
5. **Arm gestures** - Should NOT detect (< 100 dps)

### Validation Steps
1. Record test session with Android app
2. Verify markers appear on actual swings
3. Check no false triggers from non-swing movements
4. Compare with Python plotter output
5. Tune default threshold if needed

---

## Migration Notes

### For Users
- **Threshold changed:** Old values (5K-80K ADC) → New values (50-500 dps)
- **Default changed:** 20,000 ADC → 150 dps
- **Better detection:** Fewer false positives, catches more swings
- **Re-tune if needed:** Start with 150 dps, adjust based on your swing style

### For Developers
- **Code location:** `SessionCaptureActivity.java` line ~650-680
- **Key constant:** `GYRO_SCALE_FACTOR = 16.4f` (ICM-20948 specific)
- **Detection uses:** Gyroscope magnitude in dps
- **Plotting uses:** Accelerometer magnitude (unchanged)

---

## Future Improvements

### Potential Enhancements
1. **Adaptive threshold** - Learn from user's baseline
2. **Swing classification** - Forehand vs backhand vs serve
3. **Multi-stage detection** - Backswing → contact → follow-through
4. **Machine learning** - Train classifier on labeled data

### Not Recommended
- ❌ Reverting to accelerometer (inferior)
- ❌ Using both sensors (unnecessary complexity)
- ❌ Removing user adjustment (flexibility needed)

---

## Conclusion

**Decision:** Switch Android app to gyroscope detection (matching Python plotter)

**Rationale:**
- Technical superiority (gyroscope > accelerometer for rotation)
- Consistency (real-time tools aligned)
- Low effort (1 hour implementation)
- Better UX (fewer false positives, intuitive threshold)

**Status:** ✅ Implemented (2026-02-27)

**Result:** Android app and Python plotter now use identical gyroscope-based detection at 150 dps, while offline analysis continues to use more sophisticated z-score method for accurate post-processing.
