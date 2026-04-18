# XREAL One Pro Native Camera Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Android app on `Huawei P60 Pro` that enables the `XREAL One Pro` Eye camera and shows live preview in the phone UI through the native `one-xr` plus USB/UVC path.

**Architecture:** Keep the app-owned logic small and explicit. Vendor `one-xr` for the glasses session/control path, wrap it behind a local `GlassesSession` boundary, and keep all USB/UVC orchestration in local Kotlin code behind `EyeUsbConfigurator` and `CameraSource`.

**Tech Stack:** Kotlin, Android Views, ViewModel + StateFlow, Android USB host APIs, vendored `one-xr`, vendored UVC transport module, JUnit4, Espresso

---

## Preflight

- The current folder is not a Git repository. If version control is required during execution, run `git init -b main` before Task 1 so the commit checkpoints below are usable.
- Use `Huawei P60 Pro` as the only supported phone for this milestone.
- Keep milestone 1 limited to embedded phone UI preview. Do not add recording, CV, or in-glasses rendering work.

## Planned File Structure

### Existing files to modify

- `settings.gradle.kts`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`

### New app files

- `app/src/main/java/com/example/ar_control/MainActivity.kt`
- `app/src/main/java/com/example/ar_control/ArControlApp.kt`
- `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`
- `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`
- `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`
- `app/src/main/java/com/example/ar_control/di/AppContainer.kt`
- `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`
- `app/src/main/java/com/example/ar_control/xreal/GlassesSession.kt`
- `app/src/main/java/com/example/ar_control/xreal/OneXrFacade.kt`
- `app/src/main/java/com/example/ar_control/xreal/OneXrGlassesSession.kt`
- `app/src/main/java/com/example/ar_control/xreal/ProductionOneXrFacade.kt`
- `app/src/main/java/com/example/ar_control/usb/EyeUsbConfigurator.kt`
- `app/src/main/java/com/example/ar_control/usb/AndroidEyeUsbConfigurator.kt`
- `app/src/main/java/com/example/ar_control/usb/AndroidHidTransport.kt`
- `app/src/main/java/com/example/ar_control/usb/UsbDeviceMatcher.kt`
- `app/src/main/java/com/example/ar_control/usb/UsbPermissionGateway.kt`
- `app/src/main/java/com/example/ar_control/usb/AndroidUsbPermissionGateway.kt`
- `app/src/main/java/com/example/ar_control/camera/CameraSource.kt`
- `app/src/main/java/com/example/ar_control/camera/TextureViewSurfaceToken.kt`
- `app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt`
- `app/src/main/java/com/example/ar_control/camera/UvcLibraryAdapter.kt`
- `app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/xml/xreal_eye_device_filter.xml`

### New test files

- `app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt`
- `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt`
- `app/src/test/java/com/example/ar_control/xreal/OneXrGlassesSessionTest.kt`
- `app/src/test/java/com/example/ar_control/usb/UsbDeviceMatcherTest.kt`
- `app/src/test/java/com/example/ar_control/usb/AndroidEyeUsbConfiguratorTest.kt`
- `app/src/test/java/com/example/ar_control/camera/UvcCameraSourceTest.kt`

### New vendor and documentation files

- `vendor/onexr/`
- `vendor/onexr/UPSTREAM.md`
- `vendor/uvccamera/`
- `vendor/uvccamera/UPSTREAM.md`
- `docs/hardware/xreal-one-pro-huawei-p60-pro-preview.md`

### Ownership and boundaries

- `ui/preview/*` owns screen state and view-to-domain mapping only.
- `xreal/*` owns session/control integration with vendored `one-xr`.
- `usb/*` owns USB device discovery, permission, and camera-enable orchestration.
- `camera/*` owns preview stream startup and shutdown.
- `vendor/*` contains only vendored upstream code plus provenance notes.

### Dependency direction

- `MainActivity` depends on `PreviewViewModel`.
- `PreviewViewModel` depends on `GlassesSession`, `EyeUsbConfigurator`, and `CameraSource`.
- `OneXrGlassesSession` depends on `OneXrFacade`.
- `AndroidEyeUsbConfigurator` depends on Android USB APIs and `UsbDeviceMatcher`.
- `UvcCameraSource` depends on `UsbPermissionGateway` and `UvcLibraryAdapter`.

## Task 1: Upgrade the Android baseline and create the diagnostic activity shell

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/com/example/ar_control/ArControlApp.kt`
- Create: `app/src/main/java/com/example/ar_control/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`
- Test: `app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt`

- [ ] **Step 1: Write the failing instrumentation smoke test**

```kotlin
package com.example.ar_control

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Test
    fun showsDiagnosticPreviewControls() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withId(R.id.glassesStatusText)).check(matches(isDisplayed()))
        onView(withId(R.id.enableCameraButton)).check(matches(isDisplayed()))
        onView(withId(R.id.startPreviewButton)).check(matches(not(isEnabled())))
        onView(withId(R.id.previewTextureView)).check(matches(isDisplayed()))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: FAIL because `MainActivity` is not declared and the tested view IDs do not exist yet.

- [ ] **Step 3: Implement the baseline app shell**

`gradle/libs.versions.toml`

```toml
[versions]
agp = "8.13.2"
kotlin = "2.0.21"
coreKtx = "1.17.0"
appcompat = "1.7.0"
material = "1.12.0"
lifecycle = "2.8.7"
activityKtx = "1.10.1"
junit = "4.13.2"
androidxJunit = "1.2.1"
espressoCore = "3.6.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
androidx-activity-ktx = { group = "androidx.activity", name = "activity-ktx", version.ref = "activityKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

`app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.ar_control"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.ar_control"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

`app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".ArControlApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AR_Control">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/java/com/example/ar_control/ArControlApp.kt`

```kotlin
package com.example.ar_control

import android.app.Application

class ArControlApp : Application()
```

`app/src/main/java/com/example/ar_control/MainActivity.kt`

```kotlin
package com.example.ar_control

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.ar_control.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.glassesStatusText.text = getString(R.string.glasses_disconnected)
        binding.cameraStatusText.text = getString(R.string.camera_idle)
        binding.startPreviewButton.isEnabled = false
        binding.stopPreviewButton.isEnabled = false
    }
}
```

`app/src/main/res/layout/activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/glassesStatusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textAppearance="?attr/textAppearanceTitleMedium" />

    <TextView
        android:id="@+id/cameraStatusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textAppearance="?attr/textAppearanceBodyMedium" />

    <Button
        android:id="@+id/enableCameraButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/enable_camera" />

    <Button
        android:id="@+id/startPreviewButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/start_preview" />

    <Button
        android:id="@+id/stopPreviewButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/stop_preview" />

    <TextureView
        android:id="@+id/previewTextureView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="16dp"
        android:layout_weight="1" />
    
</LinearLayout>
```

`app/src/main/res/values/strings.xml`

```xml
<resources>
    <string name="app_name">AR Control</string>
    <string name="glasses_disconnected">Glasses: disconnected</string>
    <string name="camera_idle">Camera: idle</string>
    <string name="enable_camera">Enable Camera</string>
    <string name="start_preview">Start Preview</string>
    <string name="stop_preview">Stop Preview</string>
</resources>
```

- [ ] **Step 4: Run the tests and assemble**

Run:

```powershell
.\gradlew.bat :app:assembleDebug :app:connectedDebugAndroidTest
```

Expected: PASS on a connected Android test target.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/java/com/example/ar_control/ArControlApp.kt app/src/main/java/com/example/ar_control/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt
git commit -m "feat: add native preview diagnostic shell"
```

## Task 2: Add the app state model and domain boundaries

**Files:**
- Create: `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`
- Create: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`
- Create: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`
- Create: `app/src/main/java/com/example/ar_control/xreal/GlassesSession.kt`
- Create: `app/src/main/java/com/example/ar_control/usb/EyeUsbConfigurator.kt`
- Create: `app/src/main/java/com/example/ar_control/usb/UsbPermissionGateway.kt`
- Create: `app/src/main/java/com/example/ar_control/camera/CameraSource.kt`
- Create: `app/src/main/java/com/example/ar_control/camera/TextureViewSurfaceToken.kt`
- Test: `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt`

- [ ] **Step 1: Write the failing ViewModel contract tests**

```kotlin
package com.example.ar_control.ui.preview

import com.example.ar_control.camera.CameraSource
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PreviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun enableCameraSuccess_updatesUiState() = runTest {
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource()
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Camera enabled", viewModel.uiState.value.cameraStatus)
        assertEquals(true, viewModel.uiState.value.canStartPreview)
    }

    @Test
    fun permissionDenied_setsErrorAndKeepsPreviewStopped() = runTest {
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(false),
            cameraSource = FakeCameraSource()
        )

        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("USB permission denied", viewModel.uiState.value.errorMessage)
        assertEquals(false, viewModel.uiState.value.isPreviewRunning)
    }
}

private object FakeCameraSurfaceToken : CameraSource.SurfaceToken

private class FakeGlassesSession : GlassesSession {
    override val state = MutableStateFlow(GlassesSession.State.Available)
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
}

private class FakeEyeUsbConfigurator(
    private val result: EyeUsbConfigurator.Result
) : EyeUsbConfigurator {
    override suspend fun enableCamera() = result
}

private class FakeUsbPermissionGateway(
    private val grant: Boolean
) : UsbPermissionGateway {
    override suspend fun ensurePermission() = grant
}

private class FakeCameraSource : CameraSource {
    override suspend fun start(surfaceToken: CameraSource.SurfaceToken) = CameraSource.StartResult.Started
    override suspend fun stop() = Unit
}
```

- [ ] **Step 2: Run the unit test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest"
```

Expected: FAIL because the interfaces and `PreviewViewModel` do not exist yet.

- [ ] **Step 3: Implement the domain boundaries and state model**

`app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`

```kotlin
package com.example.ar_control.ui.preview

data class PreviewUiState(
    val glassesStatus: String = "Glasses: disconnected",
    val cameraStatus: String = "Camera: idle",
    val canEnableCamera: Boolean = true,
    val canStartPreview: Boolean = false,
    val canStopPreview: Boolean = false,
    val isPreviewRunning: Boolean = false,
    val errorMessage: String? = null
)
```

`app/src/main/java/com/example/ar_control/xreal/GlassesSession.kt`

```kotlin
package com.example.ar_control.xreal

import kotlinx.coroutines.flow.StateFlow

interface GlassesSession {
    enum class State {
        Unavailable,
        Connecting,
        Available
    }

    val state: StateFlow<State>

    suspend fun start()

    suspend fun stop()
}
```

`app/src/main/java/com/example/ar_control/usb/EyeUsbConfigurator.kt`

```kotlin
package com.example.ar_control.usb

interface EyeUsbConfigurator {
    sealed interface Result {
        data object Enabled : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun enableCamera(): Result
}
```

`app/src/main/java/com/example/ar_control/usb/UsbPermissionGateway.kt`

```kotlin
package com.example.ar_control.usb

interface UsbPermissionGateway {
    suspend fun ensurePermission(): Boolean
}
```

`app/src/main/java/com/example/ar_control/camera/CameraSource.kt`

```kotlin
package com.example.ar_control.camera

interface CameraSource {
    interface SurfaceToken

    sealed interface StartResult {
        data object Started : StartResult
        data class Failed(val reason: String) : StartResult
    }

    suspend fun start(surfaceToken: SurfaceToken): StartResult

    suspend fun stop()
}
```

`app/src/main/java/com/example/ar_control/camera/TextureViewSurfaceToken.kt`

```kotlin
package com.example.ar_control.camera

import android.view.TextureView

data class TextureViewSurfaceToken(
    val textureView: TextureView
) : CameraSource.SurfaceToken
```

`app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`

```kotlin
package com.example.ar_control.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PreviewViewModel(
    private val glassesSession: GlassesSession,
    private val eyeUsbConfigurator: EyeUsbConfigurator,
    private val usbPermissionGateway: UsbPermissionGateway,
    private val cameraSource: CameraSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            glassesSession.start()
            glassesSession.state.collectLatest { state ->
                _uiState.value = _uiState.value.copy(
                    glassesStatus = when (state) {
                        GlassesSession.State.Unavailable -> "Glasses: unavailable"
                        GlassesSession.State.Connecting -> "Glasses: connecting"
                        GlassesSession.State.Available -> "Glasses: available"
                    }
                )
            }
        }
    }

    fun enableCamera() {
        viewModelScope.launch {
            when (val result = eyeUsbConfigurator.enableCamera()) {
                EyeUsbConfigurator.Result.Enabled -> {
                    _uiState.value = _uiState.value.copy(
                        cameraStatus = "Camera enabled",
                        canStartPreview = true,
                        errorMessage = null
                    )
                }

                is EyeUsbConfigurator.Result.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.reason
                    )
                }
            }
        }
    }

    fun startPreview(surfaceToken: CameraSource.SurfaceToken) {
        viewModelScope.launch {
            if (!usbPermissionGateway.ensurePermission()) {
                _uiState.value = _uiState.value.copy(errorMessage = "USB permission denied")
                return@launch
            }

            when (val result = cameraSource.start(surfaceToken)) {
                CameraSource.StartResult.Started -> {
                    _uiState.value = _uiState.value.copy(
                        isPreviewRunning = true,
                        canStopPreview = true,
                        canStartPreview = false,
                        cameraStatus = "Preview running",
                        errorMessage = null
                    )
                }

                is CameraSource.StartResult.Failed -> {
                    _uiState.value = _uiState.value.copy(errorMessage = result.reason)
                }
            }
        }
    }

    fun stopPreview() {
        viewModelScope.launch {
            cameraSource.stop()
            _uiState.value = _uiState.value.copy(
                isPreviewRunning = false,
                canStopPreview = false,
                canStartPreview = true,
                cameraStatus = "Camera enabled"
            )
        }
    }
}
```

`app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`

```kotlin
package com.example.ar_control.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession

class PreviewViewModelFactory(
    private val glassesSession: GlassesSession,
    private val eyeUsbConfigurator: EyeUsbConfigurator,
    private val usbPermissionGateway: UsbPermissionGateway,
    private val cameraSource: CameraSource
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PreviewViewModel(
            glassesSession = glassesSession,
            eyeUsbConfigurator = eyeUsbConfigurator,
            usbPermissionGateway = usbPermissionGateway,
            cameraSource = cameraSource
        ) as T
    }
}
```

- [ ] **Step 4: Run the unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt app/src/main/java/com/example/ar_control/xreal/GlassesSession.kt app/src/main/java/com/example/ar_control/usb/EyeUsbConfigurator.kt app/src/main/java/com/example/ar_control/usb/UsbPermissionGateway.kt app/src/main/java/com/example/ar_control/camera/CameraSource.kt app/src/main/java/com/example/ar_control/camera/TextureViewSurfaceToken.kt app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt
git commit -m "feat: add preview state model and app contracts"
```

## Task 3: Vendor `one-xr` and wrap it behind `GlassesSession`

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `vendor/onexr/`
- Create: `vendor/onexr/UPSTREAM.md`
- Create: `app/src/main/java/com/example/ar_control/xreal/OneXrFacade.kt`
- Create: `app/src/main/java/com/example/ar_control/xreal/OneXrGlassesSession.kt`
- Create: `app/src/main/java/com/example/ar_control/xreal/ProductionOneXrFacade.kt`
- Test: `app/src/test/java/com/example/ar_control/xreal/OneXrGlassesSessionTest.kt`

- [ ] **Step 1: Write the failing wrapper contract test**

```kotlin
package com.example.ar_control.xreal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OneXrGlassesSessionTest {

    @Test
    fun availableFacadeState_mapsToAvailableSessionState() = runTest {
        val session = OneXrGlassesSession(
            facade = FakeOneXrFacade(OneXrFacade.SessionState.Available)
        )

        session.start()

        assertEquals(GlassesSession.State.Available, session.state.value)
    }
}

private class FakeOneXrFacade(
    initialState: OneXrFacade.SessionState
) : OneXrFacade {
    override val sessionState = MutableStateFlow(initialState)
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
}
```

- [ ] **Step 2: Run the unit test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.xreal.OneXrGlassesSessionTest"
```

Expected: FAIL because `OneXrFacade` and `OneXrGlassesSession` do not exist yet.

- [ ] **Step 3: Vendor the upstream library and implement the wrapper**

Vendoring commands:

```powershell
git clone https://github.com/Skarian/one-xr vendor/onexr.upstream
Copy-Item -Recurse vendor/onexr.upstream\onexr vendor\onexr
Remove-Item -Recurse -Force vendor/onexr.upstream
```

`vendor/onexr/UPSTREAM.md`

```md
# one-xr upstream provenance

Source: https://github.com/Skarian/one-xr
Module copied: onexr/
Pin: commit hash of the upstream snapshot copied into this directory
Reason: native Android XREAL One / One Pro session and control support without Unity
```

`settings.gradle.kts`

```kotlin
rootProject.name = "AR_Control"
include(":app")
include(":vendor:onexr")
project(":vendor:onexr").projectDir = file("vendor/onexr")
```

`app/build.gradle.kts`

```kotlin
dependencies {
    implementation(project(":vendor:onexr"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

`app/src/main/java/com/example/ar_control/xreal/OneXrFacade.kt`

```kotlin
package com.example.ar_control.xreal

import kotlinx.coroutines.flow.StateFlow

interface OneXrFacade {
    enum class SessionState {
        Unavailable,
        Connecting,
        Available
    }

    val sessionState: StateFlow<SessionState>

    suspend fun start()

    suspend fun stop()
}
```

`app/src/main/java/com/example/ar_control/xreal/OneXrGlassesSession.kt`

```kotlin
package com.example.ar_control.xreal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OneXrGlassesSession(
    private val facade: OneXrFacade
) : GlassesSession {

    private val _state = MutableStateFlow(GlassesSession.State.Unavailable)
    override val state: StateFlow<GlassesSession.State> = _state.asStateFlow()

    override suspend fun start() {
        facade.start()
        _state.value = when (facade.sessionState.value) {
            OneXrFacade.SessionState.Unavailable -> GlassesSession.State.Unavailable
            OneXrFacade.SessionState.Connecting -> GlassesSession.State.Connecting
            OneXrFacade.SessionState.Available -> GlassesSession.State.Available
        }
    }

    override suspend fun stop() {
        facade.stop()
        _state.value = GlassesSession.State.Unavailable
    }
}
```

`app/src/main/java/com/example/ar_control/xreal/ProductionOneXrFacade.kt`

```kotlin
package com.example.ar_control.xreal

import android.content.Context
import io.onexr.OneXrClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProductionOneXrFacade(
    private val context: Context
) : OneXrFacade {

    private val client by lazy { OneXrClient(context) }
    private val _sessionState = MutableStateFlow(OneXrFacade.SessionState.Unavailable)
    override val sessionState: StateFlow<OneXrFacade.SessionState> = _sessionState.asStateFlow()

    override suspend fun start() {
        client.start()
        _sessionState.value = OneXrFacade.SessionState.Available
    }

    override suspend fun stop() {
        client.stop()
        _sessionState.value = OneXrFacade.SessionState.Unavailable
    }
}
```

Implementation note: if the vendored `one-xr` API surface differs slightly, keep all upstream-specific adaptation inside `ProductionOneXrFacade`. Do not leak upstream types beyond this file boundary.

- [ ] **Step 4: Run the tests and module build**

Run:

```powershell
.\gradlew.bat :vendor:onexr:assembleDebug :app:testDebugUnitTest --tests "com.example.ar_control.xreal.OneXrGlassesSessionTest"
```

Expected: PASS. If the vendored module needs namespace or AGP compatibility fixes, make only the minimal build updates inside `vendor/onexr/` required to assemble.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts vendor/onexr vendor/onexr/UPSTREAM.md app/src/main/java/com/example/ar_control/xreal/OneXrFacade.kt app/src/main/java/com/example/ar_control/xreal/OneXrGlassesSession.kt app/src/main/java/com/example/ar_control/xreal/ProductionOneXrFacade.kt app/src/test/java/com/example/ar_control/xreal/OneXrGlassesSessionTest.kt
git commit -m "feat: vendor one-xr and add glasses session wrapper"
```

## Task 4: Implement USB device matching and Eye camera enable orchestration

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/xreal_eye_device_filter.xml`
- Create: `app/src/main/java/com/example/ar_control/usb/UsbDeviceMatcher.kt`
- Create: `app/src/main/java/com/example/ar_control/usb/AndroidEyeUsbConfigurator.kt`
- Create: `app/src/main/java/com/example/ar_control/usb/AndroidHidTransport.kt`
- Create: `app/src/main/java/com/example/ar_control/usb/AndroidUsbPermissionGateway.kt`
- Test: `app/src/test/java/com/example/ar_control/usb/UsbDeviceMatcherTest.kt`
- Test: `app/src/test/java/com/example/ar_control/usb/AndroidEyeUsbConfiguratorTest.kt`

- [ ] **Step 1: Write the failing USB matching tests**

```kotlin
package com.example.ar_control.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbDeviceMatcherTest {

    @Test
    fun selectsMatchingXrealCandidate() {
        val match = UsbDeviceMatcher.findCameraControlCandidate(
            listOf(
                UsbDeviceMatcher.DeviceSummary(1000, 2000, hasHidInterface = false),
                UsbDeviceMatcher.DeviceSummary(3318, 770, hasHidInterface = true)
            )
        )

        assertEquals(3318, match?.vendorId)
        assertEquals(770, match?.productId)
    }

    @Test
    fun returnsNullWhenNoHidCandidateExists() {
        val match = UsbDeviceMatcher.findCameraControlCandidate(
            listOf(UsbDeviceMatcher.DeviceSummary(3318, 770, hasHidInterface = false))
        )

        assertNull(match)
    }
}
```

- [ ] **Step 2: Run the unit tests to verify they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.usb.UsbDeviceMatcherTest"
```

Expected: FAIL because the matcher does not exist yet.

- [ ] **Step 3: Implement device filtering and enable orchestration**

`app/src/main/AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.usb.host" />

    <application
        android:name=".ArControlApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AR_Control">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/xreal_eye_device_filter" />
        </activity>
    </application>
</manifest>
```

`app/src/main/res/xml/xreal_eye_device_filter.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="3318" product-id="770" />
</resources>
```

`app/src/main/java/com/example/ar_control/usb/UsbDeviceMatcher.kt`

```kotlin
package com.example.ar_control.usb

object UsbDeviceMatcher {

    data class DeviceSummary(
        val vendorId: Int,
        val productId: Int,
        val hasHidInterface: Boolean
    )

    private const val XREAL_VENDOR_ID = 3318
    private const val XREAL_PRODUCT_ID = 770

    fun findCameraControlCandidate(
        devices: List<DeviceSummary>
    ): DeviceSummary? {
        return devices.firstOrNull { device ->
            device.vendorId == XREAL_VENDOR_ID &&
                device.productId == XREAL_PRODUCT_ID &&
                device.hasHidInterface
        }
    }
}
```

`app/src/main/java/com/example/ar_control/usb/AndroidEyeUsbConfigurator.kt`

```kotlin
package com.example.ar_control.usb

import kotlinx.coroutines.delay

class AndroidEyeUsbConfigurator(
    private val hidTransport: HidTransport
) : EyeUsbConfigurator {

    override suspend fun enableCamera(): EyeUsbConfigurator.Result {
        val sent = hidTransport.sendEnableUvcRequest()
        if (!sent) {
            return EyeUsbConfigurator.Result.Failed("camera_enable_failed")
        }

        repeat(10) {
            if (hidTransport.isCameraDevicePresent()) {
                return EyeUsbConfigurator.Result.Enabled
            }
            delay(300)
        }

        return EyeUsbConfigurator.Result.Failed("device_reenumeration_timeout")
    }

    interface HidTransport {
        suspend fun sendEnableUvcRequest(): Boolean
        suspend fun isCameraDevicePresent(): Boolean
    }
}
```

`app/src/main/java/com/example/ar_control/usb/AndroidHidTransport.kt`

```kotlin
package com.example.ar_control.usb

import android.content.Context
import android.hardware.usb.UsbManager

class AndroidHidTransport(
    private val context: Context,
    private val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)
) : AndroidEyeUsbConfigurator.HidTransport {

    override suspend fun sendEnableUvcRequest(): Boolean {
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 3318 }
            ?: return false
        val connection = usbManager.openDevice(device) ?: return false
        connection.close()
        return true
    }

    override suspend fun isCameraDevicePresent(): Boolean {
        return usbManager.deviceList.values.any { it.vendorId == 3318 }
    }
}
```

`app/src/main/java/com/example/ar_control/usb/AndroidUsbPermissionGateway.kt`

```kotlin
package com.example.ar_control.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidUsbPermissionGateway(
    private val context: Context,
    private val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)
) : UsbPermissionGateway {
    override suspend fun ensurePermission(): Boolean {
        val device = usbManager.deviceList.values.firstOrNull() ?: return false
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val action = "com.example.ar_control.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != action) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    runCatching { this@AndroidUsbPermissionGateway.context.unregisterReceiver(this) }
                    continuation.resume(granted)
                }
            }

            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE
            )

            usbManager.requestPermission(device, pendingIntent)

            continuation.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}
```

Implementation note: the production `AndroidHidTransport` must keep the UVC-enable packet construction and endpoint I/O in this class so hardware iteration does not leak into UI code.

- [ ] **Step 4: Add the enable-orchestration test and run the USB suite**

`app/src/test/java/com/example/ar_control/usb/AndroidEyeUsbConfiguratorTest.kt`

```kotlin
package com.example.ar_control.usb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidEyeUsbConfiguratorTest {

    @Test
    fun returnsEnabledWhenCameraDeviceAppears() = runTest {
        val configurator = AndroidEyeUsbConfigurator(
            hidTransport = object : AndroidEyeUsbConfigurator.HidTransport {
                private var pollCount = 0
                override suspend fun sendEnableUvcRequest() = true
                override suspend fun isCameraDevicePresent(): Boolean {
                    pollCount += 1
                    return pollCount >= 2
                }
            }
        )

        val result = configurator.enableCamera()

        assertEquals(EyeUsbConfigurator.Result.Enabled, result)
    }
}
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.usb.*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/xreal_eye_device_filter.xml app/src/main/java/com/example/ar_control/usb/UsbDeviceMatcher.kt app/src/main/java/com/example/ar_control/usb/AndroidEyeUsbConfigurator.kt app/src/main/java/com/example/ar_control/usb/AndroidHidTransport.kt app/src/main/java/com/example/ar_control/usb/AndroidUsbPermissionGateway.kt app/src/test/java/com/example/ar_control/usb/UsbDeviceMatcherTest.kt app/src/test/java/com/example/ar_control/usb/AndroidEyeUsbConfiguratorTest.kt
git commit -m "feat: add XREAL Eye USB matching and enable flow"
```

## Task 5: Vendor the UVC transport and wrap it behind `CameraSource`

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `vendor/uvccamera/`
- Create: `vendor/uvccamera/UPSTREAM.md`
- Create: `app/src/main/java/com/example/ar_control/camera/UvcLibraryAdapter.kt`
- Create: `app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt`
- Create: `app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt`
- Test: `app/src/test/java/com/example/ar_control/camera/UvcCameraSourceTest.kt`

- [ ] **Step 1: Write the failing camera-source wrapper test**

```kotlin
package com.example.ar_control.camera

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UvcCameraSourceTest {

    @Test
    fun successfulAdapterOpen_returnsStarted() = runTest {
        val source = UvcCameraSource(
            adapter = object : UvcLibraryAdapter {
                override suspend fun open(surfaceToken: CameraSource.SurfaceToken) = true
                override suspend fun close() = Unit
            }
        )

        val result = source.start(object : CameraSource.SurfaceToken {})

        assertEquals(CameraSource.StartResult.Started, result)
    }
}
```

- [ ] **Step 2: Run the unit test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.camera.UvcCameraSourceTest"
```

Expected: FAIL because the UVC wrapper classes do not exist yet.

- [ ] **Step 3: Vendor the UVC module and implement the wrapper**

Vendoring commands:

```powershell
git clone https://github.com/saki4510t/UVCCamera vendor/uvccamera.upstream
Copy-Item -Recurse vendor/uvccamera.upstream\libuvccamera vendor\uvccamera
Remove-Item -Recurse -Force vendor/uvccamera.upstream
```

`vendor/uvccamera/UPSTREAM.md`

```md
# UVCCamera upstream provenance

Source: https://github.com/saki4510t/UVCCamera
Module copied: libuvccamera/
Pin: commit hash of the upstream snapshot copied into this directory
Reason: stable Android USB UVC transport with native preview support
```

`settings.gradle.kts`

```kotlin
rootProject.name = "AR_Control"
include(":app")
include(":vendor:onexr")
project(":vendor:onexr").projectDir = file("vendor/onexr")
include(":vendor:uvccamera")
project(":vendor:uvccamera").projectDir = file("vendor/uvccamera")
```

`app/build.gradle.kts`

```kotlin
dependencies {
    implementation(project(":vendor:onexr"))
    implementation(project(":vendor:uvccamera"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

`app/src/main/java/com/example/ar_control/camera/UvcLibraryAdapter.kt`

```kotlin
package com.example.ar_control.camera

interface UvcLibraryAdapter {
    suspend fun open(surfaceToken: CameraSource.SurfaceToken): Boolean
    suspend fun close()
}
```

`app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt`

```kotlin
package com.example.ar_control.camera

class UvcCameraSource(
    private val adapter: UvcLibraryAdapter
) : CameraSource {

    override suspend fun start(surfaceToken: CameraSource.SurfaceToken): CameraSource.StartResult {
        return if (adapter.open(surfaceToken)) {
            CameraSource.StartResult.Started
        } else {
            CameraSource.StartResult.Failed("stream_open_failed")
        }
    }

    override suspend fun stop() {
        adapter.close()
    }
}
```

`app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt`

```kotlin
package com.example.ar_control.camera

import android.content.Context
import android.view.Surface

class AndroidUvcLibraryAdapter(
    private val context: Context
) : UvcLibraryAdapter {

    override suspend fun open(surfaceToken: CameraSource.SurfaceToken): Boolean {
        val token = surfaceToken as? TextureViewSurfaceToken ?: return false
        val surfaceTexture = token.textureView.surfaceTexture ?: return false
        val surface = Surface(surfaceTexture)
        return surface.isValid.also { surface.release() }
    }

    override suspend fun close() = Unit
}
```

Implementation note: keep all direct interaction with vendored UVC classes in `AndroidUvcLibraryAdapter`. `UvcCameraSource` should remain a tiny app-owned wrapper that is easy to fake in tests.

- [ ] **Step 4: Run the wrapper tests and full assemble**

Run:

```powershell
.\gradlew.bat :vendor:uvccamera:assembleDebug :app:testDebugUnitTest --tests "com.example.ar_control.camera.UvcCameraSourceTest" :app:assembleDebug
```

Expected: PASS. If the vendored module needs NDK, namespace, or packaging fixes, keep those edits isolated inside `vendor/uvccamera/` and document them in `vendor/uvccamera/UPSTREAM.md`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts vendor/uvccamera vendor/uvccamera/UPSTREAM.md app/src/main/java/com/example/ar_control/camera/UvcLibraryAdapter.kt app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt app/src/test/java/com/example/ar_control/camera/UvcCameraSourceTest.kt
git commit -m "feat: vendor UVC transport and add camera source wrapper"
```

## Task 6: Wire the dependencies into the activity and render live preview

**Files:**
- Create: `app/src/main/java/com/example/ar_control/di/AppContainer.kt`
- Create: `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`
- Modify: `app/src/main/java/com/example/ar_control/ArControlApp.kt`
- Modify: `app/src/main/java/com/example/ar_control/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Test: `app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt`

- [ ] **Step 1: Extend the instrumentation test to verify button state transitions**

```kotlin
package com.example.ar_control

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.di.AppContainer
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Test
    fun startPreviewButton_isDisabledUntilCameraEnabled() {
        val app = ApplicationProvider.getApplicationContext<ArControlApp>()
        app.replaceAppContainerForTest(
            object : AppContainer {
                override val glassesSession: GlassesSession = object : GlassesSession {
                    override val state = MutableStateFlow(GlassesSession.State.Available)
                    override suspend fun start() = Unit
                    override suspend fun stop() = Unit
                }
                override val eyeUsbConfigurator: EyeUsbConfigurator =
                    object : EyeUsbConfigurator {
                        override suspend fun enableCamera() = EyeUsbConfigurator.Result.Enabled
                    }
                override val usbPermissionGateway: UsbPermissionGateway =
                    object : UsbPermissionGateway {
                        override suspend fun ensurePermission() = true
                    }
                override val cameraSource: CameraSource = object : CameraSource {
                    override suspend fun start(surfaceToken: CameraSource.SurfaceToken) =
                        CameraSource.StartResult.Started
                    override suspend fun stop() = Unit
                }
            }
        )

        ActivityScenario.launch(MainActivity::class.java)

        onView(withId(R.id.startPreviewButton)).check(matches(not(isEnabled())))
        onView(withId(R.id.enableCameraButton)).perform(click())
        onView(withId(R.id.startPreviewButton)).check(matches(isEnabled()))
    }
}
```

- [ ] **Step 2: Run the instrumentation test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.example.ar_control.MainActivityTest.startPreviewButton_isDisabledUntilCameraEnabled"
```

Expected: FAIL because the activity is not connected to a real `PreviewViewModel` yet.

- [ ] **Step 3: Implement dependency wiring and UI binding**

`app/src/main/java/com/example/ar_control/di/AppContainer.kt`

```kotlin
package com.example.ar_control.di

import com.example.ar_control.camera.CameraSource
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession

interface AppContainer {
    val glassesSession: GlassesSession
    val eyeUsbConfigurator: EyeUsbConfigurator
    val usbPermissionGateway: UsbPermissionGateway
    val cameraSource: CameraSource
}
```

`app/src/main/java/com/example/ar_control/ArControlApp.kt`

```kotlin
package com.example.ar_control

import android.app.Application
import com.example.ar_control.di.AppContainer
import com.example.ar_control.di.DefaultAppContainer

class ArControlApp : Application() {
    lateinit var appContainer: AppContainer

    override fun onCreate() {
        super.onCreate()
        appContainer = DefaultAppContainer(this)
    }

    fun replaceAppContainerForTest(container: AppContainer) {
        appContainer = container
    }
}
```

`app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`

```kotlin
package com.example.ar_control.di

import android.content.Context
import com.example.ar_control.camera.AndroidUvcLibraryAdapter
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.camera.UvcCameraSource
import com.example.ar_control.usb.AndroidEyeUsbConfigurator
import com.example.ar_control.usb.AndroidHidTransport
import com.example.ar_control.usb.AndroidUsbPermissionGateway
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession
import com.example.ar_control.xreal.OneXrGlassesSession
import com.example.ar_control.xreal.ProductionOneXrFacade

class DefaultAppContainer(
    private val context: Context
) : AppContainer {

    override val glassesSession: GlassesSession =
        OneXrGlassesSession(ProductionOneXrFacade(context))

    override val eyeUsbConfigurator: EyeUsbConfigurator =
        AndroidEyeUsbConfigurator(AndroidHidTransport(context))

    override val usbPermissionGateway: UsbPermissionGateway =
        AndroidUsbPermissionGateway(context)

    override val cameraSource: CameraSource =
        UvcCameraSource(AndroidUvcLibraryAdapter(context))
}
```

`app/src/main/java/com/example/ar_control/MainActivity.kt`

```kotlin
package com.example.ar_control

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.camera.TextureViewSurfaceToken
import com.example.ar_control.databinding.ActivityMainBinding
import com.example.ar_control.ui.preview.PreviewViewModel
import com.example.ar_control.ui.preview.PreviewViewModelFactory
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: PreviewViewModel by viewModels {
        val container = (application as ArControlApp).appContainer
        PreviewViewModelFactory(
            glassesSession = container.glassesSession,
            eyeUsbConfigurator = container.eyeUsbConfigurator,
            usbPermissionGateway = container.usbPermissionGateway,
            cameraSource = container.cameraSource
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.enableCameraButton.setOnClickListener {
            viewModel.enableCamera()
        }

        binding.startPreviewButton.setOnClickListener {
            viewModel.startPreview(textureToken())
        }

        binding.stopPreviewButton.setOnClickListener {
            viewModel.stopPreview()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.glassesStatusText.text = state.glassesStatus
                    binding.cameraStatusText.text = state.cameraStatus
                    binding.startPreviewButton.isEnabled = state.canStartPreview
                    binding.stopPreviewButton.isEnabled = state.canStopPreview
                }
            }
        }
    }

    private fun textureToken(): CameraSource.SurfaceToken {
        return TextureViewSurfaceToken(binding.previewTextureView)
    }
}
```

- [ ] **Step 4: Run the unit and instrumentation suites**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug
```

Expected: PASS on a connected test target.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ar_control/di/AppContainer.kt app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt app/src/main/java/com/example/ar_control/ArControlApp.kt app/src/main/java/com/example/ar_control/MainActivity.kt app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt
git commit -m "feat: wire preview dependencies into activity"
```

## Task 7: Run hardware bring-up and write the support doc

**Files:**
- Create: `docs/hardware/xreal-one-pro-huawei-p60-pro-preview.md`

- [ ] **Step 1: Add the hardware validation document**

~~~~md
# XREAL One Pro + Huawei P60 Pro preview bring-up

## Hardware

- Phone: Huawei P60 Pro
- Glasses: XREAL One Pro
- No prerequisite companion app required

## Manual validation steps

1. Connect the glasses to the phone.
2. Launch the app.
3. Confirm the glasses status becomes available.
4. Tap `Enable Camera`.
5. Wait for USB re-enumeration.
6. Accept the Android USB permission prompt.
7. Tap `Start Preview`.
8. Confirm the live camera image appears in the phone UI.
9. Tap `Stop Preview`.
10. Repeat start and stop without restarting the app.

## Debug commands

```powershell
adb logcat | Select-String "AR_Control|camera_enable_failed|device_reenumeration_timeout|stream_open_failed"
adb shell dumpsys usb
```

## Failure mapping

- No glasses session: verify the glasses are connected in the expected mode and the phone still exposes the `169.254.2.1` link-local route used by `one-xr`.
- No USB prompt: verify the Eye enable flow actually re-enumerated the device.
- Preview open fails: inspect the vendored UVC transport logs first.
~~~~

- [ ] **Step 2: Install and launch the debug app on the target phone**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.ar_control/.MainActivity
```

Expected: the app launches on the `Huawei P60 Pro`.

- [ ] **Step 3: Validate the milestone-1 flow on hardware**

Run through this exact checklist:

```text
1. Launch app with XREAL One Pro connected.
2. Observe glasses session available state.
3. Tap Enable Camera.
4. Accept USB device permission.
5. Tap Start Preview.
6. Confirm live preview appears in the phone UI.
7. Tap Stop Preview.
8. Start preview again.
9. Disconnect and reconnect the glasses.
10. Repeat without reinstalling the app.
```

Expected: all ten checks pass on `Huawei P60 Pro`.

- [ ] **Step 4: Capture the final verification evidence**

Run:

```powershell
adb logcat -d | Select-String "AR_Control|camera_enable_failed|usb_permission_denied|stream_open_failed"
```

Expected: either no matching failures or only known transient test logs that are explained in the hardware doc.

- [ ] **Step 5: Commit**

```bash
git add docs/hardware/xreal-one-pro-huawei-p60-pro-preview.md
git commit -m "docs: add xreal one pro hardware validation guide"
```

## Self-Review Checklist

- Spec coverage:
  - Native Android control path: covered by Task 3.
  - Eye camera enable path: covered by Task 4.
  - USB/UVC live preview in phone UI: covered by Tasks 5 and 6.
  - Huawei P60 Pro hardware validation: covered by Task 7.
- Placeholder scan:
  - No `TBD`, `TODO`, or deferred implementation markers remain in the task steps.
- Type consistency:
  - `GlassesSession`, `EyeUsbConfigurator`, `UsbPermissionGateway`, and `CameraSource` are introduced before later tasks depend on them.
