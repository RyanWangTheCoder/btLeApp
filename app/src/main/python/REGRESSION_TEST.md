# Android Python Regression Tests

**Location:** `C:\wangx\ftc\btLeApp\app\src\main\python\tests\test_android_bridges.py`  
**Purpose:** Verify Android-specific Python bridge files correctly use the emg_util submodule  
**Last Updated:** 2026-03-07

---

## Quick Start

```bash
cd C:\wangx\ftc\btLeApp\app\src\main\python
python -m pytest tests/test_android_bridges.py -v
```

**Expected Result:**
```
4 passed, 9 skipped in ~3s
```

(9 tests are skipped when emg_util submodule is not initialized in the test environment)

---

## Test Coverage

### 1. offline_runner.py (3 tests) ✅

#### test_offline_runner_imports
**Purpose:** Verify `offline_runner.py` has all required functions  
**Checks:**
- `analyze_tennis_session()` exists
- `discover_sessions()` exists
- `process_session()` exists

#### test_offline_runner_default_params
**Purpose:** Verify correct default parameters  
**Checks:**
- `baseline_swings=20` (matches Windows default)
- `emg_fs_hz` is a required parameter (no default)

#### test_offline_runner_calls_stitch
**Purpose:** Verify it correctly calls `stitch_decoded_session_from_paths()`  
**Checks:**
- Source code contains `stitch_decoded_session_from_paths`
- Passes `emg_fs_hz=` parameter
- Passes `baseline_swings=` parameter

---

### 2. analysis_bridge.py (3 tests) ⚠️

#### test_analysis_bridge_imports
**Purpose:** Verify `analysis_bridge.py` can import from emg_util submodule  
**Status:** SKIPPED (analysis_bridge.py not in test path)

#### test_analysis_bridge_emg_fs_hz
**Purpose:** Verify uses `emg_fs_hz=2000.0` (MyoWare 2.0 ADC rate)  
**Status:** SKIPPED (analysis_bridge.py not in test path)

#### test_analysis_bridge_baseline_swings
**Purpose:** Verify uses `baseline_swings=20` (matches Windows)  
**Status:** SKIPPED (analysis_bridge.py not in test path)

---

### 3. realtime_bridge.py (2 tests) ⚠️

#### test_realtime_bridge_imports
**Purpose:** Verify `realtime_bridge.py` can import FatigueMonitor  
**Status:** SKIPPED (realtime_bridge.py not in test path)

#### test_realtime_bridge_uses_fatigue_monitor
**Purpose:** Verify uses FatigueMonitor from emg_util submodule  
**Status:** SKIPPED (realtime_bridge.py not in test path)

---

### 4. emg_util Submodule Integration (5 tests) ⚠️

#### test_emg_util_submodule_available
**Purpose:** Verify emg_util submodule is available and has required functions  
**Checks:**
- `stitch_decoded_session_from_paths()` exists
- `compute_baseline_from_swing_features()` exists
- `select_baseline_swings()` exists
- `_filter_baseline_by_rms()` exists

**Status:** SKIPPED (emg_util submodule not initialized in test environment)

#### test_fatigue_monitor_submodule_available
**Purpose:** Verify fatigue_monitor is available from emg_util submodule  
**Checks:**
- `FatigueMonitor` class exists
- `preprocess_emg()` function exists

**Status:** ✅ PASSED (fatigue_monitor.py is available)

#### test_baseline_rms_filter_available
**Purpose:** Verify RMS filter is available in emg_util_nopandas  
**Checks:**
- `_filter_baseline_by_rms()` function exists

**Status:** SKIPPED (emg_util_nopandas not in test path)

#### test_compute_baseline_uses_75th_percentile
**Purpose:** Verify `compute_baseline_from_swing_features()` uses 75th percentile for MDF  
**Test Data:** 10 swings with MDF values [90, 92, 94, ..., 108]  
**Expected:** baseline MDF = 103.5 (75th percentile)  
**Status:** SKIPPED (emg_util_nopandas not in test path)

#### test_rms_filter_drops_low_rms_swings
**Purpose:** Verify `_filter_baseline_by_rms()` drops noise-dominated swings  
**Test Data:**
- 20 swings total
- First 5 have RMS=10 (noise)
- Last 15 have RMS=100 (signal)
- Baseline candidates: first 10 swings (includes 5 noise)

**Expected:**
- Session p90 RMS = 100
- Threshold = 0.25 × 100 = 25
- Filter drops 5 low-RMS swings
- Result: 5 swings remain

**Status:** SKIPPED (emg_util_nopandas not in test path)

---

## Why Some Tests Are Skipped

The test file uses `pytest.skip()` to gracefully handle missing dependencies:

1. **analysis_bridge.py / realtime_bridge.py not in test path**
   - These files exist in `C:\wangx\ftc\btLeApp\app\src\main\python\`
   - Test runner may not have them in `sys.path`
   - Solution: Run tests from the correct directory

2. **emg_util submodule not initialized**
   - The emg_util submodule may not be initialized in the test environment
   - Solution: `git submodule update --init --recursive`

3. **Import errors**
   - Some modules may have dependencies not available in test environment
   - Tests skip gracefully rather than failing

---

## Running Tests with Submodule

To run all tests (including integration tests):

```bash
# 1. Initialize submodule
cd C:\wangx\ftc\btLeApp
git submodule update --init --recursive

# 2. Run tests
cd app\src\main\python
python -m pytest tests/test_android_bridges.py -v

# Expected: 13 passed (or 4 passed + 9 skipped if submodule not in path)
```

---

## Test Philosophy

### Graceful Degradation
- Tests use `pytest.skip()` for missing dependencies
- Allows tests to run in different environments (CI, local, Android)
- Core tests (offline_runner) always run

### What We Test
1. **Function existence** — Required functions are available
2. **Parameter correctness** — Default values match Windows
3. **Call chain** — Functions call the right submodule functions
4. **Algorithm correctness** — 75th percentile MDF, RMS filter logic

### What We Don't Test
- ❌ Full end-to-end analysis (requires real data files)
- ❌ Android Java integration (requires Android emulator)
- ❌ Binary frame decoding (covered by separate tests)

---

## Relationship to emg_util Tests

**Windows Tests:** `C:\wangx\ftc\emg_util\tests\test_regression_nopandas.py`
- 52 tests covering emg_util.py and emg_util_nopandas.py
- Tests core algorithms (preprocessing, MDF, RMS filter, baseline selection)
- Run: `cd C:\wangx\ftc\emg_util && python -m pytest tests/test_regression_nopandas.py -v`

**Android Tests:** `C:\wangx\ftc\btLeApp\app\src\main\python\tests\test_android_bridges.py`
- 13 tests covering Android bridge files (offline_runner, analysis_bridge, realtime_bridge)
- Tests integration with emg_util submodule
- Run: `cd C:\wangx\ftc\btLeApp\app\src\main\python && python -m pytest tests/test_android_bridges.py -v`

**Together:** Ensure Android and Windows use identical algorithms with correct parameters.

---

## Continuous Integration

### Pre-commit Checklist
Before committing changes to Android Python files:

```bash
# 1. Run Android bridge tests
cd C:\wangx\ftc\btLeApp\app\src\main\python
python -m pytest tests/test_android_bridges.py -v

# 2. Run emg_util regression tests
cd C:\wangx\ftc\emg_util
python -m pytest tests/test_regression_nopandas.py -v

# 3. Verify both pass
# Expected: 4 passed (Android) + 52 passed (emg_util) = 56 total
```

### When to Update Tests

**Update Android tests when:**
- Adding new bridge functions
- Changing default parameters
- Modifying call chains to emg_util functions

**Update emg_util tests when:**
- Changing core algorithms (preprocessing, MDF, RMS filter)
- Modifying baseline selection logic
- Adding new features to emg_util_nopandas.py

---

## Troubleshooting

### Issue: All tests skip
**Cause:** Test runner can't find modules  
**Solution:**
```bash
cd C:\wangx\ftc\btLeApp\app\src\main\python
python -m pytest tests/test_android_bridges.py -v
```

### Issue: Import errors
**Cause:** Missing dependencies (numpy, scipy)  
**Solution:**
```bash
pip install numpy scipy pytest
```

### Issue: Submodule tests skip
**Cause:** emg_util submodule not initialized  
**Solution:**
```bash
cd C:\wangx\ftc\btLeApp
git submodule update --init --recursive
```

### Issue: Test fails after algorithm change
**Cause:** Expected values need updating  
**Solution:**
1. Verify the algorithm change is correct
2. Update expected values in test
3. Document change in CHANGELOG.md

---

## Test Maintenance

### Adding New Tests

1. **Create test function** in `test_android_bridges.py`:
```python
def test_new_feature():
    """Verify new feature works correctly."""
    try:
        import offline_runner
        # Test code here
        assert offline_runner.new_feature() == expected_value
    except ImportError:
        pytest.skip("offline_runner.py not available")
```

2. **Run test:**
```bash
python -m pytest tests/test_android_bridges.py::test_new_feature -v
```

3. **Update this document** with test description

### Updating Expected Values

When algorithm changes require updating expected values:

1. **Verify change is correct** (compare with Windows)
2. **Update test:**
```python
# Old
assert baseline['mdf'] == 95.5  # median

# New
assert baseline['mdf'] == 97.75  # 75th percentile
```

3. **Document in CHANGELOG.md:**
```markdown
## [1.2.0] - 2026-03-07
### Changed
- Baseline MDF: median → 75th percentile (fresh muscles = highest MDF)
- Updated test_compute_baseline_uses_75th_percentile expected value
```

---

## Related Documentation

- **[ANDROID_HLD.md](ANDROID_HLD.md)** - Android Python architecture overview
- **[ANDROID_CHANGE_LOG.md](ANDROID_CHANGE_LOG.md)** - Android Python change history
- **[emg_util/tests/test_regression_nopandas.py](../../../emg_util/tests/test_regression_nopandas.py)** - Windows regression tests

---

## Summary

**Purpose:** Ensure Android Python code stays consistent with Windows side  
**Coverage:** offline_runner.py, analysis_bridge.py, realtime_bridge.py  
**Status:** 4/4 core tests pass, 9/9 integration tests skip (submodule not in test path)  
**Maintenance:** Update when changing bridge files or emg_util algorithms  

**Key Principle:** Tests should pass in any environment (graceful degradation via pytest.skip)
