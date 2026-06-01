# Add project specific ProGuard rules here.

# ── Chaquopy Python runtime ──────────────────────────────────────────────────
# Keep all Chaquopy classes; R8 must not rename or remove them.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ── emg_util Python modules (loaded by name at runtime) ──────────────────────
# No Java/Kotlin classes to keep — Python files are bundled as assets by
# Chaquopy and are never processed by R8.

# ── FastBLE ───────────────────────────────────────────────────────────────────
-keep class com.clj.fastble.** { *; }
-dontwarn com.clj.fastble.**

# ── App capture/analysis classes (referenced by name via reflection) ──────────
-keep class com.clj.blesample.capture.** { *; }
-keep class com.clj.blesample.processing.** { *; }

# ── AndroidX / AppCompat ──────────────────────────────────────────────────────
-dontwarn androidx.**
