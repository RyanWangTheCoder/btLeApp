# Gradle Build Configuration & Troubleshooting Guide

## Build Environment

### Requirements
- **Java Version:** Java 17 or higher (Java 21 recommended)
- **Android Gradle Plugin:** 8.5.1
- **Gradle Version:** 8.5
- **Build Tools:** Android SDK Build Tools (configured in config.gradle)

### Current Configuration
- **Project Location:** `C:\wangx\ftc\btLeApp`
- **Java Home:** `C:\Program Files\Android\Android Studio\jbr` (Java 21)
- **Gradle Wrapper:** `gradlew.bat` (Windows) / `gradlew` (Linux/Mac)

---

## Build Fixes Applied (2026-02-28)

### Issue 1: Gradle Cache Corruption ❌ → ✅

**Problem:**
Build failed with multiple "Could not read workspace metadata" errors:
```
FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred configuring root project 'btLeApp'.
> Multiple build operations failed.
      Could not read workspace metadata from C:\Users\wangx\.gradle\caches\transforms-4\691b361d6b7d73959f91be7a4ce05efa\metadata.bin
      Could not read workspace metadata from C:\Users\wangx\.gradle\caches\transforms-4\50c330448be708c8bcb5180751c25143\metadata.bin
      ...and 26 more failures.
```

**Root Cause:**
Corrupted Gradle cache files in the `transforms-4` directory. This can happen due to:
- Interrupted builds
- System crashes
- Multiple Gradle processes accessing cache simultaneously

**Solution Steps:**

1. **Stop all Gradle daemons:**
   ```bash
   gradlew --stop
   ```
   Output:
   ```
   Stopping Daemon(s)
   2 Daemons stopped
   ```

2. **Attempt to delete corrupted cache (optional):**
   ```bash
   rmdir /s /q C:\Users\wangx\.gradle\caches\transforms-4
   ```
   Note: Some files may be locked. If deletion fails, proceed to step 3.

3. **Rebuild with refresh dependencies:**
   ```bash
   gradlew assembleDebug --refresh-dependencies
   ```
   This forces Gradle to re-download and rebuild the cache.

**Result:** ✅ Cache corruption resolved

---

### Issue 2: Java Version Mismatch ❌ → ✅

**Problem:**
Android Gradle Plugin 8.5.1 requires Java 17+, but system was using Java 12:
```
FAILURE: Build failed with an exception.

* Where:
Build file 'C:\wangx\ftc\btLeApp\app\build.gradle' line: 2

* What went wrong:
An exception occurred applying plugin request [id: 'com.android.application']
> Failed to apply plugin 'com.android.internal.application'.
   > Android Gradle plugin requires Java 17 to run. You are currently using Java 12.
      Your current JDK is located in C:\Program Files\jdk-12.0.2
      You can try some of the following options:
       - changing the IDE settings.
       - changing the JAVA_HOME environment variable.
       - changing `org.gradle.java.home` in `gradle.properties`.
```

**Root Cause:**
System JAVA_HOME was pointing to Java 12, which is too old for Android Gradle Plugin 8.5.1.

**Solution Steps:**

1. **Check available Java installations:**
   ```bash
   java -version
   ```
   Output:
   ```
   openjdk version "12.0.2" 2019-07-16
   ```

2. **Check Android Studio's bundled JDK:**
   ```bash
   "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" -version
   ```
   Output:
   ```
   openjdk version "21.0.9" 2025-10-21
   OpenJDK Runtime Environment (build 21.0.9+-14649483-b1163.86)
   ```

3. **Configure Gradle to use Android Studio's JDK:**
   
   **File Modified:** `gradle.properties`
   
   Added the following line:
   ```properties
   org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr
   ```
   
   Complete file content:
   ```properties
   ## Project-wide Gradle settings.
   #
   # For more details on how to configure your build environment visit
   # http://www.gradle.org/docs/current/userguide/build_environment.html
   #
   # Specifies the JVM arguments used for the daemon process.
   # The setting is particularly useful for tweaking memory settings.
   # Default value: -Xmx10248m -XX:MaxPermSize=256m
   # org.gradle.jvmargs=-Xmx2048m -XX:MaxPermSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
   #
   # When configured, Gradle will run in incubating parallel mode.
   # This option should only be used with decoupled projects. More details, visit
   # http://www.gradle.org/docs/current/userguide/multi_project_builds.html#sec:decoupled_projects
   # org.gradle.parallel=true
   #Wed Aug 10 16:29:06 CST 2016
   android.useAndroidX=true
   org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr
   ```

4. **Stop Gradle daemon to apply changes:**
   ```bash
   gradlew --stop
   ```

5. **Rebuild:**
   ```bash
   gradlew assembleDebug
   ```

**Result:** ✅ Now using Java 21 from Android Studio

**Alternative Solutions:**
- Install Java 17+ from [Adoptium](https://adoptium.net/) or [Oracle](https://www.oracle.com/java/technologies/downloads/)
- Update system JAVA_HOME environment variable
- Configure in Android Studio: File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK

---

### Issue 3: Corrupted XML Layout File ❌ → ✅

**Problem:**
Build failed with XML parsing error:
```
[Fatal Error] activity_plot_new.xml:2:1: Premature end of file.
> Task :app:parseDebugLocalResources FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:parseDebugLocalResources'.
> A failure occurred while executing com.android.build.gradle.internal.res.ParseLibraryResourcesTask$ParseResourcesRunnable
   > Failed to parse XML file 'C:\wangx\ftc\btLeApp\app\build\intermediates\packaged_res\debug\packageDebugResources\layout\activity_plot_new.xml'
```

**Root Cause:**
The file `app/src/main/res/layout/activity_plot_new.xml` only contained an XML declaration with no content:

**Before:**
```xml
<?xml version="1.0" encoding="utf-8"?> 
```

This is invalid XML - every XML file must have a root element.

**Solution:**

**File Modified:** `app/src/main/res/layout/activity_plot_new.xml`

Added minimal valid layout structure:

**After:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

</LinearLayout>
```

**Result:** ✅ XML parsing error resolved

**Note:** This is a placeholder layout. If this activity is actually used, it should be properly designed with the required UI elements.

---

### Issue 4: Missing BuildConfig Class ❌ → ✅

**Problem:**
Compilation failed with "cannot find symbol" error:
```
> Task :app:compileDebugJavaWithJavac FAILED

C:\wangx\ftc\btLeApp\app\src\main\java\com\clj\blesample\operation\CharacteristicOperationFragment.java:19: error: cannot find symbol
import com.clj.blesample.BuildConfig;
                        ^
  symbol:   class BuildConfig
  location: package com.clj.blesample

C:\wangx\ftc\btLeApp\app\src\main\java\com\clj\blesample\operation\CharacteristicOperationFragment.java:48: error: cannot find symbol
        Log.e("BT_NOTIFY", "CharacteristicOperationFragment loaded (build=" + BuildConfig.VERSION_NAME + ")");
                                                                              ^
  symbol:   variable BuildConfig
  location: class CharacteristicOperationFragment
2 errors
```

**Root Cause:**
Starting with Android Gradle Plugin 8.0+, the `BuildConfig` class is no longer generated by default. This is a breaking change from previous versions where BuildConfig was always generated.

**Solution:**

**File Modified:** `app/build.gradle`

Added `buildFeatures` block to enable BuildConfig generation:

**Before:**
```gradle
android {
    compileSdkVersion rootProject.ext.android.compileSdkVersion
    buildToolsVersion rootProject.ext.android.buildToolsVersion

    defaultConfig {
        // ... config ...
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
        }
    }
    namespace 'com.clj.blesample'

    aaptOptions {
        noCompress "so"
    }
    // ... rest of config ...
}
```

**After:**
```gradle
android {
    compileSdkVersion rootProject.ext.android.compileSdkVersion
    buildToolsVersion rootProject.ext.android.buildToolsVersion

    defaultConfig {
        // ... config ...
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
        }
    }
    namespace 'com.clj.blesample'
    
    buildFeatures {
        buildConfig = true
    }

    aaptOptions {
        noCompress "so"
    }
    // ... rest of config ...
}
```

**Result:** ✅ BuildConfig class now generated successfully

**What BuildConfig Contains:**
```java
package com.clj.blesample;

public final class BuildConfig {
    public static final boolean DEBUG = Boolean.parseBoolean("true");
    public static final String APPLICATION_ID = "com.clj.blesample";
    public static final String BUILD_TYPE = "debug";
    public static final String FLAVOR = "";
    public static final int VERSION_CODE = 1;
    public static final String VERSION_NAME = "1.0";
}
```

---

## Build Success! ✅

After applying all four fixes, the build completed successfully:

```
BUILD SUCCESSFUL in 2m
71 actionable tasks: 23 executed, 48 up-to-date

> Task :app:assembleDebug
```

**Output:**
- **APK Location:** `app\build\outputs\apk\debug\app-debug.apk`
- **APK Size:** 85,805,463 bytes (~85.8 MB)
- **Build Type:** Debug (unsigned, for testing)

---

## Common Gradle Commands

### Building

```bash
# Build debug APK
gradlew assembleDebug

# Build release APK (requires signing configuration)
gradlew assembleRelease

# Build all variants
gradlew build

# Clean build (removes all build artifacts)
gradlew clean

# Clean and rebuild
gradlew clean assembleDebug
```

### Installing

```bash
# Install debug APK on connected device
gradlew installDebug

# Install release APK
gradlew installRelease

# Uninstall from device
gradlew uninstallDebug
```

### Maintenance

```bash
# Stop all Gradle daemons
gradlew --stop

# View Gradle daemon status
gradlew --status

# Refresh dependencies (force re-download)
gradlew assembleDebug --refresh-dependencies

# Build with stack trace (for debugging)
gradlew assembleDebug --stacktrace

# Build with detailed info
gradlew assembleDebug --info

# Build with debug output
gradlew assembleDebug --debug
```

### Cleaning Cache

```bash
# Clean project build directory
gradlew clean

# Clean Gradle cache (nuclear option - use with caution)
# Windows:
rmdir /s /q %USERPROFILE%\.gradle\caches

# Linux/Mac:
rm -rf ~/.gradle/caches
```

---

## Troubleshooting Guide

### Problem: "Gradle daemon disappeared unexpectedly"

**Symptoms:**
```
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
```

**Solutions:**
1. Stop all daemons: `gradlew --stop`
2. Check available memory (Gradle needs ~2GB)
3. Increase Gradle memory in `gradle.properties`:
   ```properties
   org.gradle.jvmargs=-Xmx4096m -XX:MaxPermSize=512m
   ```

---

### Problem: "Could not resolve dependencies"

**Symptoms:**
```
Could not resolve com.android.tools.build:gradle:8.5.1
```

**Solutions:**
1. Check internet connection
2. Refresh dependencies: `gradlew assembleDebug --refresh-dependencies`
3. Check repository configuration in `build.gradle`:
   ```gradle
   repositories {
       google()
       mavenCentral()
   }
   ```

---

### Problem: "Execution failed for task ':app:mergeDebugResources'"

**Symptoms:**
```
Execution failed for task ':app:mergeDebugResources'.
> Multiple task action failures occurred
```

**Solutions:**
1. Clean project: `gradlew clean`
2. Check for duplicate resources in `res/` folders
3. Validate all XML files are well-formed
4. Check for invalid resource names (must be lowercase, alphanumeric + underscore)

---

### Problem: "Unsupported class file major version"

**Symptoms:**
```
Unsupported class file major version 61
```

**Solutions:**
1. This means code was compiled with Java 17 but running with older Java
2. Update `org.gradle.java.home` in `gradle.properties`
3. Or update system JAVA_HOME to Java 17+

---

### Problem: Build is very slow

**Solutions:**
1. Enable Gradle daemon (should be default):
   ```properties
   org.gradle.daemon=true
   ```

2. Enable parallel builds:
   ```properties
   org.gradle.parallel=true
   ```

3. Enable configuration on demand:
   ```properties
   org.gradle.configureondemand=true
   ```

4. Increase memory:
   ```properties
   org.gradle.jvmargs=-Xmx4096m
   ```

5. Use build cache:
   ```properties
   org.gradle.caching=true
   ```

---

## Files Modified Summary

### 1. `gradle.properties`
**Change:** Added Java home configuration
```properties
org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr
```

### 2. `app/build.gradle`
**Change:** Enabled BuildConfig generation
```gradle
buildFeatures {
    buildConfig = true
}
```

### 3. `app/src/main/res/layout/activity_plot_new.xml`
**Change:** Fixed incomplete XML file
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

</LinearLayout>
```

---

## Verification Steps

After applying fixes, verify the build works:

1. **Stop all Gradle processes:**
   ```bash
   gradlew --stop
   ```

2. **Clean build:**
   ```bash
   gradlew clean
   ```

3. **Build debug APK:**
   ```bash
   gradlew assembleDebug
   ```

4. **Verify APK exists:**
   ```bash
   dir app\build\outputs\apk\debug\app-debug.apk
   ```

5. **Check APK size (should be ~85 MB):**
   ```
   app-debug.apk - 85,805,463 bytes
   ```

---

## Additional Resources

- [Android Gradle Plugin Release Notes](https://developer.android.com/build/releases/gradle-plugin)
- [Gradle Build Environment](https://docs.gradle.org/current/userguide/build_environment.html)
- [Configure Your Build](https://developer.android.com/build)
- [Troubleshoot Build Issues](https://developer.android.com/studio/troubleshoot)

---

## Summary

✅ **All build issues resolved**
✅ **Using Java 21 from Android Studio**
✅ **BuildConfig generation enabled**
✅ **XML layout files fixed**
✅ **APK builds successfully**

**Build Status:** READY FOR DEVELOPMENT ✨
