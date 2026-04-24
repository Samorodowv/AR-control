# Gemma Camera Subtitles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional Gemma subtitles mode that captions live camera frames in a floating pill over fullscreen preview using a sideloaded `.litertlm` model.

**Architecture:** Add a focused `gemma` package for preferences, model import, frame captioning interfaces, YUV-to-image conversion, and LiteRT-LM inference. `PreviewViewModel` owns feature state and session lifecycle, reusing the existing UVC frame callback and `FrameCallbackTargetFanOut` path. `MainActivity` owns document-picking and rendering the control checkbox, model status, import button, and fullscreen subtitle pill.

**Tech Stack:** Android/Kotlin, Gradle version catalog, LiteRT-LM Android `0.10.2`, Kotlin coroutines, SharedPreferences, Robolectric/JUnit, Espresso instrumentation.

---

## File Structure

- Create `app/src/main/java/com/example/ar_control/gemma/GemmaSubtitlePreferences.kt`: app-owned preference interface for enabled state and imported model metadata.
- Create `app/src/main/java/com/example/ar_control/gemma/SharedPreferencesGemmaSubtitlePreferences.kt`: SharedPreferences implementation.
- Create `app/src/main/java/com/example/ar_control/gemma/GemmaModelImporter.kt`: Android document URI import into app-private model storage.
- Create `app/src/main/java/com/example/ar_control/gemma/GemmaFrameCaptioner.kt`: captioning interfaces and no-op implementation.
- Create `app/src/main/java/com/example/ar_control/gemma/Yuv420SpImageEncoder.kt`: UV-order YUV420SP to NV21/JPEG conversion for `Content.ImageBytes`.
- Create `app/src/main/java/com/example/ar_control/gemma/LiteRtGemmaFrameCaptioner.kt`: LiteRT-LM-backed frame caption session.
- Modify `gradle/libs.versions.toml` and `app/build.gradle.kts`: add LiteRT-LM dependency.
- Modify `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`: wire preferences, importer, and captioner.
- Modify `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`: inject Gemma dependencies.
- Modify `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`: add Gemma UI fields.
- Modify `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`: persist checkbox, import model, start/stop caption sessions, fan out frame callbacks.
- Modify `app/src/main/java/com/example/ar_control/MainActivity.kt`: add checkbox/import handlers and render subtitle pill.
- Modify `app/src/main/res/layout/activity_main.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/dimens.xml`: add controls and overlay UI.
- Add tests under `app/src/test/java/com/example/ar_control/gemma/` and update `PreviewViewModelTest`.
- Update `app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt` for UI rendering.

---

### Task 1: Add LiteRT-LM Dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the version catalog entries**

Add these lines in `gradle/libs.versions.toml`:

```toml
[versions]
litertLm = "0.10.2"

[libraries]
litert-lm-android = { group = "com.google.ai.edge.litertlm", name = "litertlm-android", version.ref = "litertLm" }
```

Keep existing version and library entries intact.

- [ ] **Step 2: Add the app dependency**

Add this line in `app/build.gradle.kts` inside `dependencies`:

```kotlin
implementation(libs.litert.lm.android)
```

- [ ] **Step 3: Verify Gradle resolves the dependency**

Run:

```powershell
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
```

Expected: exit code `0` and output includes `com.google.ai.edge.litertlm:litertlm-android:0.10.2`.

- [ ] **Step 4: Commit**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add LiteRT-LM dependency"
```

---

### Task 2: Add Gemma Subtitle Preferences

**Files:**
- Create: `app/src/main/java/com/example/ar_control/gemma/GemmaSubtitlePreferences.kt`
- Create: `app/src/main/java/com/example/ar_control/gemma/SharedPreferencesGemmaSubtitlePreferences.kt`
- Create: `app/src/test/java/com/example/ar_control/gemma/SharedPreferencesGemmaSubtitlePreferencesTest.kt`

- [ ] **Step 1: Write the failing preference test**

Create `app/src/test/java/com/example/ar_control/gemma/SharedPreferencesGemmaSubtitlePreferencesTest.kt`:

```kotlin
package com.example.ar_control.gemma

import android.content.Context
import com.example.ar_control.ArControlApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesGemmaSubtitlePreferencesTest {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferencesGemmaSubtitlePreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        preferences = SharedPreferencesGemmaSubtitlePreferences(context, FILE_NAME)
    }

    @Test
    fun defaultsToDisabledWithoutModel() {
        assertFalse(preferences.isGemmaSubtitlesEnabled())
        assertNull(preferences.getModelPath())
        assertNull(preferences.getModelDisplayName())
    }

    @Test
    fun persistsEnabledState() {
        preferences.setGemmaSubtitlesEnabled(true)

        val reloaded = SharedPreferencesGemmaSubtitlePreferences(context, FILE_NAME)

        assertTrue(reloaded.isGemmaSubtitlesEnabled())
    }

    @Test
    fun persistsImportedModelMetadata() {
        preferences.setModel(path = "/private/models/gemma.litertlm", displayName = "gemma.litertlm")

        val reloaded = SharedPreferencesGemmaSubtitlePreferences(context, FILE_NAME)

        assertEquals("/private/models/gemma.litertlm", reloaded.getModelPath())
        assertEquals("gemma.litertlm", reloaded.getModelDisplayName())
    }

    @Test
    fun clearModelRemovesPathAndDisplayName() {
        preferences.setModel(path = "/private/models/gemma.litertlm", displayName = "gemma.litertlm")

        preferences.clearModel()

        assertNull(preferences.getModelPath())
        assertNull(preferences.getModelDisplayName())
    }

    private companion object {
        const val FILE_NAME = "gemma_subtitle_prefs_test"
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.gemma.SharedPreferencesGemmaSubtitlePreferencesTest"
```

Expected: FAIL because `SharedPreferencesGemmaSubtitlePreferences` does not exist.

- [ ] **Step 3: Add the preference interface**

Create `app/src/main/java/com/example/ar_control/gemma/GemmaSubtitlePreferences.kt`:

```kotlin
package com.example.ar_control.gemma

interface GemmaSubtitlePreferences {
    fun isGemmaSubtitlesEnabled(): Boolean

    fun setGemmaSubtitlesEnabled(enabled: Boolean)

    fun getModelPath(): String?

    fun getModelDisplayName(): String?

    fun setModel(path: String, displayName: String?)

    fun clearModel()
}
```

- [ ] **Step 4: Add the SharedPreferences implementation**

Create `app/src/main/java/com/example/ar_control/gemma/SharedPreferencesGemmaSubtitlePreferences.kt`:

```kotlin
package com.example.ar_control.gemma

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesGemmaSubtitlePreferences internal constructor(
    private val preferences: SharedPreferences
) : GemmaSubtitlePreferences {

    constructor(
        context: Context,
        fileName: String = DEFAULT_FILE_NAME
    ) : this(
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    )

    override fun isGemmaSubtitlesEnabled(): Boolean {
        return preferences.getBoolean(KEY_ENABLED, false)
    }

    override fun setGemmaSubtitlesEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun getModelPath(): String? {
        return preferences.getString(KEY_MODEL_PATH, null)
    }

    override fun getModelDisplayName(): String? {
        return preferences.getString(KEY_MODEL_DISPLAY_NAME, null)
    }

    override fun setModel(path: String, displayName: String?) {
        preferences.edit()
            .putString(KEY_MODEL_PATH, path)
            .putString(KEY_MODEL_DISPLAY_NAME, displayName)
            .apply()
    }

    override fun clearModel() {
        preferences.edit()
            .remove(KEY_MODEL_PATH)
            .remove(KEY_MODEL_DISPLAY_NAME)
            .apply()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "gemma_subtitle_prefs"
        const val KEY_ENABLED = "gemma_subtitles_enabled"
        const val KEY_MODEL_PATH = "gemma_model_path"
        const val KEY_MODEL_DISPLAY_NAME = "gemma_model_display_name"
    }
}
```

- [ ] **Step 5: Run the test and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.gemma.SharedPreferencesGemmaSubtitlePreferencesTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/example/ar_control/gemma/GemmaSubtitlePreferences.kt app/src/main/java/com/example/ar_control/gemma/SharedPreferencesGemmaSubtitlePreferences.kt app/src/test/java/com/example/ar_control/gemma/SharedPreferencesGemmaSubtitlePreferencesTest.kt
git commit -m "feat: add Gemma subtitle preferences"
```

---

### Task 3: Add Private Model Importer

**Files:**
- Create: `app/src/main/java/com/example/ar_control/gemma/GemmaModelImporter.kt`
- Create: `app/src/test/java/com/example/ar_control/gemma/GemmaModelImporterTest.kt`

- [ ] **Step 1: Write the failing importer tests**

Create `app/src/test/java/com/example/ar_control/gemma/GemmaModelImporterTest.kt`:

```kotlin
package com.example.ar_control.gemma

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelImporterTest {

    @Test
    fun importModelCopiesContentAndPersistsMetadata() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-test").toFile()
        val preferences = FakeGemmaSubtitlePreferences()
        val sourceBytes = byteArrayOf(1, 2, 3, 4)
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { ByteArrayInputStream(sourceBytes) }
        )

        val result = importer.importModel(Uri.parse("content://models/gemma"), "gemma-4.litertlm")

        assertTrue(result is GemmaModelImportResult.Imported)
        val imported = result as GemmaModelImportResult.Imported
        assertEquals("gemma-4.litertlm", imported.displayName)
        assertTrue(File(imported.path).exists())
        assertArrayEquals(sourceBytes, File(imported.path).readBytes())
        assertEquals(imported.path, preferences.getModelPath())
        assertEquals("gemma-4.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun importModelDeletesPreviousPrivateModelAfterSuccessfulImport() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-replace-test").toFile()
        val previous = File(targetDirectory, "previous.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(previous.absolutePath, "previous.litertlm")
        }
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { ByteArrayInputStream(byteArrayOf(5, 6)) }
        )

        val result = importer.importModel(Uri.parse("content://models/new"), "new.litertlm")

        assertTrue(result is GemmaModelImportResult.Imported)
        assertFalse(previous.exists())
        assertEquals("new.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun importModelReturnsFailureWhenSourceCannotBeOpened() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-failure-test").toFile()
        val preferences = FakeGemmaSubtitlePreferences()
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { null }
        )

        val result = importer.importModel(Uri.parse("content://models/missing"), "missing.litertlm")

        assertEquals(GemmaModelImportResult.Failed("Could not open selected Gemma model"), result)
        assertEquals(emptyList<String>(), targetDirectory.list()?.toList().orEmpty())
        assertEquals(null, preferences.getModelPath())
    }
}

private class FakeGemmaSubtitlePreferences : GemmaSubtitlePreferences {
    private var enabled = false
    private var modelPath: String? = null
    private var modelDisplayName: String? = null

    override fun isGemmaSubtitlesEnabled(): Boolean = enabled

    override fun setGemmaSubtitlesEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun getModelPath(): String? = modelPath

    override fun getModelDisplayName(): String? = modelDisplayName

    override fun setModel(path: String, displayName: String?) {
        modelPath = path
        modelDisplayName = displayName
    }

    override fun clearModel() {
        modelPath = null
        modelDisplayName = null
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.gemma.GemmaModelImporterTest"
```

Expected: FAIL because `GemmaModelImporter` does not exist.

- [ ] **Step 3: Add the importer implementation**

Create `app/src/main/java/com/example/ar_control/gemma/GemmaModelImporter.kt`:

```kotlin
package com.example.ar_control.gemma

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface GemmaModelImportResult {
    data class Imported(
        val path: String,
        val displayName: String?
    ) : GemmaModelImportResult

    data class Failed(val reason: String) : GemmaModelImportResult
}

class GemmaModelImporter internal constructor(
    private val targetDirectory: File,
    private val preferences: GemmaSubtitlePreferences,
    private val openInputStream: (Uri) -> InputStream?
) {

    constructor(
        context: Context,
        preferences: GemmaSubtitlePreferences
    ) : this(
        targetDirectory = File(context.applicationContext.filesDir, "models"),
        preferences = preferences,
        openInputStream = { uri -> context.applicationContext.contentResolver.openInputStream(uri) }
    )

    suspend fun importModel(uri: Uri, displayName: String?): GemmaModelImportResult {
        return withContext(Dispatchers.IO) {
            runCatching { importModelBlocking(uri, displayName) }
                .getOrElse { error ->
                    GemmaModelImportResult.Failed(error.message ?: "Failed to import Gemma model")
                }
        }
    }

    private fun importModelBlocking(uri: Uri, displayName: String?): GemmaModelImportResult {
        targetDirectory.mkdirs()
        val source = openInputStream(uri)
            ?: return GemmaModelImportResult.Failed("Could not open selected Gemma model")

        val safeDisplayName = displayName
            ?.takeIf(String::isNotBlank)
            ?: "gemma-subtitles.litertlm"
        val tempFile = File(targetDirectory, "gemma-${UUID.randomUUID()}.tmp")
        val targetFile = File(targetDirectory, "gemma-subtitles.litertlm")
        source.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val previousPath = preferences.getModelPath()
        if (targetFile.exists()) {
            targetFile.delete()
        }
        check(tempFile.renameTo(targetFile)) {
            "Failed to move imported Gemma model into private storage"
        }
        if (previousPath != null && previousPath != targetFile.absolutePath) {
            File(previousPath).delete()
        }
        preferences.setModel(targetFile.absolutePath, safeDisplayName)
        return GemmaModelImportResult.Imported(
            path = targetFile.absolutePath,
            displayName = safeDisplayName
        )
    }
}
```

- [ ] **Step 4: Run importer tests and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.gemma.GemmaModelImporterTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/ar_control/gemma/GemmaModelImporter.kt app/src/test/java/com/example/ar_control/gemma/GemmaModelImporterTest.kt
git commit -m "feat: import Gemma model into private storage"
```

---

### Task 4: Add Frame Caption Contracts And Image Encoding

**Files:**
- Create: `app/src/main/java/com/example/ar_control/gemma/GemmaFrameCaptioner.kt`
- Create: `app/src/main/java/com/example/ar_control/gemma/Yuv420SpImageEncoder.kt`
- Create: `app/src/test/java/com/example/ar_control/gemma/Yuv420SpImageEncoderTest.kt`

- [ ] **Step 1: Write failing encoder tests**

Create `app/src/test/java/com/example/ar_control/gemma/Yuv420SpImageEncoderTest.kt`:

```kotlin
package com.example.ar_control.gemma

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.ArControlApp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class Yuv420SpImageEncoderTest {

    @Test
    fun convertUvOrderYuv420SpToNv21_swapsChromaPairs() {
        val frame = byteArrayOf(
            10, 11, 12, 13,
            20, 30
        )

        val nv21 = Yuv420SpImageEncoder.convertUvOrderYuv420SpToNv21(
            frameBytes = frame,
            previewSize = PreviewSize(width = 2, height = 2)
        )

        assertArrayEquals(
            byteArrayOf(
                10, 11, 12, 13,
                30, 20
            ),
            nv21
        )
    }

    @Test
    fun encodeJpeg_returnsJpegBytes() {
        val frame = ByteArray(2 * 2 * 3 / 2) { 128.toByte() }

        val jpeg = Yuv420SpImageEncoder.encodeJpeg(
            frameBytes = frame,
            previewSize = PreviewSize(width = 2, height = 2),
            quality = 70
        )

        assertTrue(jpeg.isNotEmpty())
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), jpeg.take(2).toByteArray())
    }
}
```

- [ ] **Step 2: Run the encoder tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.gemma.Yuv420SpImageEncoderTest"
```

Expected: FAIL because `Yuv420SpImageEncoder` does not exist.

- [ ] **Step 3: Add captioning interfaces**

Create `app/src/main/java/com/example/ar_control/gemma/GemmaFrameCaptioner.kt`:

```kotlin
package com.example.ar_control.gemma

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat

interface GemmaFrameCaptioner {
    fun start(
        modelPath: String,
        previewSize: PreviewSize,
        onCaptionUpdated: (String) -> Unit,
        onError: (String) -> Unit
    ): GemmaCaptionSession
}

interface GemmaCaptionSession : AutoCloseable {
    val inputTarget: RecordingInputTarget.FrameCallbackTarget

    override fun close()
}

object NoOpGemmaFrameCaptioner : GemmaFrameCaptioner {
    override fun start(
        modelPath: String,
        previewSize: PreviewSize,
        onCaptionUpdated: (String) -> Unit,
        onError: (String) -> Unit
    ): GemmaCaptionSession {
        return object : GemmaCaptionSession {
            override val inputTarget = RecordingInputTarget.FrameCallbackTarget(
                pixelFormat = VideoFramePixelFormat.YUV420SP,
                frameConsumer = VideoFrameConsumer { _, _ -> }
            )

            override fun close() = Unit
        }
    }
}
```

- [ ] **Step 4: Add YUV to JPEG encoder**

Create `app/src/main/java/com/example/ar_control/gemma/Yuv420SpImageEncoder.kt`:

```kotlin
package com.example.ar_control.gemma

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.example.ar_control.camera.PreviewSize
import java.io.ByteArrayOutputStream

object Yuv420SpImageEncoder {

    fun convertUvOrderYuv420SpToNv21(
        frameBytes: ByteArray,
        previewSize: PreviewSize
    ): ByteArray {
        val expectedSize = previewSize.width * previewSize.height * 3 / 2
        require(frameBytes.size >= expectedSize) {
            "Unexpected frame size ${frameBytes.size} for ${previewSize.width}x${previewSize.height}"
        }
        val frameSize = previewSize.width * previewSize.height
        val nv21 = frameBytes.copyOf(expectedSize)
        var index = frameSize
        while (index + 1 < expectedSize) {
            val u = frameBytes[index]
            val v = frameBytes[index + 1]
            nv21[index] = v
            nv21[index + 1] = u
            index += 2
        }
        return nv21
    }

    fun encodeJpeg(
        frameBytes: ByteArray,
        previewSize: PreviewSize,
        quality: Int = DEFAULT_QUALITY
    ): ByteArray {
        val nv21 = convertUvOrderYuv420SpToNv21(frameBytes, previewSize)
        val image = YuvImage(
            nv21,
            ImageFormat.NV21,
            previewSize.width,
            previewSize.height,
            null
        )
        return ByteArrayOutputStream().use { output ->
            check(image.compressToJpeg(Rect(0, 0, previewSize.width, previewSize.height), quality, output)) {
                "Failed to encode preview frame as JPEG"
            }
            output.toByteArray()
        }
    }

    private const val DEFAULT_QUALITY = 70
}
```

- [ ] **Step 5: Run encoder tests and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.gemma.Yuv420SpImageEncoderTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/example/ar_control/gemma/GemmaFrameCaptioner.kt app/src/main/java/com/example/ar_control/gemma/Yuv420SpImageEncoder.kt app/src/test/java/com/example/ar_control/gemma/Yuv420SpImageEncoderTest.kt
git commit -m "feat: add Gemma frame caption contracts"
```

---

### Task 5: Add LiteRT-LM Frame Captioner

**Files:**
- Create: `app/src/main/java/com/example/ar_control/gemma/LiteRtGemmaFrameCaptioner.kt`
- Create: `app/src/test/java/com/example/ar_control/gemma/LiteRtGemmaFrameCaptionerTest.kt`

- [ ] **Step 1: Write failing captioner tests with a fake model**

Create `app/src/test/java/com/example/ar_control/gemma/LiteRtGemmaFrameCaptionerTest.kt`:

```kotlin
package com.example.ar_control.gemma

import com.example.ar_control.camera.PreviewSize
import java.nio.ByteBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiteRtGemmaFrameCaptionerTest {

    @Test
    fun sessionDropsFramesInsideSampleInterval() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = FakeGemmaCaptionModel("a desk with a monitor")
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = dispatcher,
            minCaptionIntervalMillis = 1_000L,
            clock = { testScheduler.currentTime }
        )
        val captions = mutableListOf<String>()
        val session = captioner.start(
            modelPath = "/models/gemma.litertlm",
            previewSize = PreviewSize(width = 2, height = 2),
            onCaptionUpdated = captions::add,
            onError = {}
        )

        session.inputTarget.frameConsumer.onFrame(ByteBuffer.wrap(ByteArray(6) { 128.toByte() }), 1L)
        session.inputTarget.frameConsumer.onFrame(ByteBuffer.wrap(ByteArray(6) { 128.toByte() }), 2L)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("a desk with a monitor"), captions)
        assertEquals(1, model.captionCalls)
    }

    @Test
    fun sessionReportsModelFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { FailingGemmaCaptionModel("model_failed") },
            dispatcher = dispatcher,
            minCaptionIntervalMillis = 0L,
            clock = { testScheduler.currentTime }
        )
        val errors = mutableListOf<String>()
        val session = captioner.start(
            modelPath = "/models/gemma.litertlm",
            previewSize = PreviewSize(width = 2, height = 2),
            onCaptionUpdated = {},
            onError = errors::add
        )

        session.inputTarget.frameConsumer.onFrame(ByteBuffer.wrap(ByteArray(6) { 128.toByte() }), 1L)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("model_failed"), errors)
    }

    @Test
    fun sanitizeCaptionKeepsSingleShortLine() {
        assertEquals(
            "a person at a desk",
            LiteRtGemmaFrameCaptioner.sanitizeCaption("A person at a desk.\nExtra detail.")
        )
        assertTrue(LiteRtGemmaFrameCaptioner.sanitizeCaption("   ").isEmpty())
    }
}

private class FakeGemmaCaptionModel(
    private val caption: String
) : GemmaCaptionModel {
    var captionCalls = 0

    override suspend fun caption(jpegBytes: ByteArray): String {
        captionCalls += 1
        return caption
    }

    override fun close() = Unit
}

private class FailingGemmaCaptionModel(
    private val reason: String
) : GemmaCaptionModel {
    override suspend fun caption(jpegBytes: ByteArray): String {
        error(reason)
    }

    override fun close() = Unit
}
```

- [ ] **Step 2: Run the captioner tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.gemma.LiteRtGemmaFrameCaptionerTest"
```

Expected: FAIL because `LiteRtGemmaFrameCaptioner` does not exist.

- [ ] **Step 3: Add the LiteRT-LM captioner**

Create `app/src/main/java/com/example/ar_control/gemma/LiteRtGemmaFrameCaptioner.kt`:

```kotlin
package com.example.ar_control.gemma

import android.content.Context
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface GemmaCaptionModel : AutoCloseable {
    suspend fun caption(jpegBytes: ByteArray): String

    override fun close()
}

class LiteRtGemmaFrameCaptioner internal constructor(
    private val modelFactory: (String) -> GemmaCaptionModel,
    private val dispatcher: CoroutineDispatcher,
    private val minCaptionIntervalMillis: Long = DEFAULT_CAPTION_INTERVAL_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sessionLog: SessionLog = NoOpSessionLog
) : GemmaFrameCaptioner {

    constructor(
        context: Context,
        sessionLog: SessionLog = NoOpSessionLog
    ) : this(
        modelFactory = { modelPath ->
            LiteRtGemmaCaptionModel(
                modelPath = modelPath,
                cacheDir = context.applicationContext.cacheDir.absolutePath
            )
        },
        dispatcher = Dispatchers.Default,
        sessionLog = sessionLog
    )

    override fun start(
        modelPath: String,
        previewSize: PreviewSize,
        onCaptionUpdated: (String) -> Unit,
        onError: (String) -> Unit
    ): GemmaCaptionSession {
        return BackgroundGemmaCaptionSession(
            model = modelFactory(modelPath),
            previewSize = previewSize,
            dispatcher = dispatcher,
            minCaptionIntervalMillis = minCaptionIntervalMillis,
            clock = clock,
            onCaptionUpdated = onCaptionUpdated,
            onError = onError,
            sessionLog = sessionLog
        )
    }

    internal companion object {
        const val DEFAULT_CAPTION_INTERVAL_MILLIS = 3_000L
        const val PROMPT =
            "Describe the visible scene in one short subtitle, max 12 words. Return only the subtitle."

        fun sanitizeCaption(rawCaption: String): String {
            return rawCaption
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
                .trim('"')
                .trimEnd('.')
                .replaceFirstChar { char -> char.lowercase() }
                .take(96)
        }
    }
}

private class BackgroundGemmaCaptionSession(
    private val model: GemmaCaptionModel,
    private val previewSize: PreviewSize,
    private val dispatcher: CoroutineDispatcher,
    private val minCaptionIntervalMillis: Long,
    private val clock: () -> Long,
    private val onCaptionUpdated: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val sessionLog: SessionLog
) : GemmaCaptionSession {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)
    private val inferenceInFlight = AtomicBoolean(false)
    private var lastCaptionStartedAtMillis = Long.MIN_VALUE

    override val inputTarget = RecordingInputTarget.FrameCallbackTarget(
        pixelFormat = VideoFramePixelFormat.YUV420SP,
        frameConsumer = VideoFrameConsumer { frame, _ -> enqueueFrame(frame) }
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        runCatching { model.close() }
    }

    private fun enqueueFrame(frame: ByteBuffer) {
        if (closed.get() || inferenceInFlight.get()) {
            return
        }
        val now = clock()
        if (lastCaptionStartedAtMillis != Long.MIN_VALUE &&
            now - lastCaptionStartedAtMillis < minCaptionIntervalMillis
        ) {
            return
        }
        val frameBytes = frame.asReadOnlyBuffer().let { buffer ->
            ByteArray(buffer.remaining()).also(buffer::get)
        }
        lastCaptionStartedAtMillis = now
        inferenceInFlight.set(true)
        scope.launch {
            try {
                val jpegBytes = Yuv420SpImageEncoder.encodeJpeg(frameBytes, previewSize)
                val caption = LiteRtGemmaFrameCaptioner.sanitizeCaption(model.caption(jpegBytes))
                if (!closed.get() && caption.isNotBlank()) {
                    onCaptionUpdated(caption)
                }
            } catch (error: Exception) {
                val reason = error.message ?: "Gemma caption inference failed"
                sessionLog.record("LiteRtGemmaFrameCaptioner", "Caption failed: $reason")
                if (!closed.get()) {
                    onError(reason)
                }
            } finally {
                inferenceInFlight.set(false)
            }
        }
    }
}

private class LiteRtGemmaCaptionModel(
    private val modelPath: String,
    private val cacheDir: String
) : GemmaCaptionModel {
    private val engine: Engine by lazy {
        Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                cacheDir = cacheDir
            )
        ).also { it.initialize() }
    }

    override suspend fun caption(jpegBytes: ByteArray): String {
        return withContext(Dispatchers.Default) {
            engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 1,
                        topP = 0.95,
                        temperature = 0.2
                    )
                )
            ).use { conversation ->
                val response = conversation.sendMessage(
                    Contents.of(
                        Content.ImageBytes(jpegBytes),
                        Content.Text(LiteRtGemmaFrameCaptioner.PROMPT)
                    )
                )
                response.toString()
            }
        }
    }

    override fun close() {
        if (engine.isInitialized()) {
            engine.close()
        }
    }
}
```

- [ ] **Step 4: Run captioner tests and fix compile mismatches**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.gemma.LiteRtGemmaFrameCaptionerTest"
```

Expected: PASS. If `EngineConfig` or `SamplerConfig` constructor defaults differ in Kotlin compilation, adjust only the named arguments shown by the compiler while preserving CPU backend, `visionBackend`, and the prompt.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/ar_control/gemma/LiteRtGemmaFrameCaptioner.kt app/src/test/java/com/example/ar_control/gemma/LiteRtGemmaFrameCaptionerTest.kt
git commit -m "feat: add LiteRT Gemma frame captioner"
```

---

### Task 6: Integrate Gemma State Into PreviewViewModel

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`
- Modify: `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Add these tests to `PreviewViewModelTest`:

```kotlin
@Test
fun init_loadsGemmaSubtitlePreferenceAndModelName() = runTest {
    val gemmaPreferences = FakeGemmaSubtitlePreferences(
        enabled = true,
        modelPath = "/models/gemma.litertlm",
        displayName = "gemma.litertlm"
    )
    val viewModel = buildViewModel(
        gemmaSubtitlePreferences = gemmaPreferences,
        cleanupScope = cleanupScope
    )

    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.gemmaSubtitlesEnabled)
    assertEquals("gemma.litertlm", viewModel.uiState.value.gemmaModelDisplayName)
}

@Test
fun setGemmaSubtitlesEnabled_updatesUiStateAndPreferenceStore() = runTest {
    val gemmaPreferences = FakeGemmaSubtitlePreferences(enabled = false)
    val viewModel = buildViewModel(
        gemmaSubtitlePreferences = gemmaPreferences,
        cleanupScope = cleanupScope
    )

    viewModel.setGemmaSubtitlesEnabled(true)

    assertTrue(viewModel.uiState.value.gemmaSubtitlesEnabled)
    assertTrue(gemmaPreferences.isGemmaSubtitlesEnabled())
}

@Test
fun startPreviewWithGemmaEnabledButNoModel_keepsPreviewRunningAndShowsError() = runTest {
    val viewModel = buildViewModel(
        cameraSource = FakeCameraSource(startResult = CameraSource.StartResult.Started(PreviewSize(640, 480))),
        gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(enabled = true),
        cleanupScope = cleanupScope
    )
    viewModel.enableCamera()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.startPreview(FakeSurfaceToken())
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.isPreviewRunning)
    assertEquals("Gemma model is not configured", viewModel.uiState.value.errorMessage)
    assertEquals("", viewModel.uiState.value.gemmaSubtitleText)
}

@Test
fun startPreviewWithGemmaEnabled_startsCaptionSessionAndUpdatesSubtitle() = runTest {
    val gemmaCaptioner = FakeGemmaFrameCaptioner()
    val viewModel = buildViewModel(
        cameraSource = FakeCameraSource(startResult = CameraSource.StartResult.Started(PreviewSize(640, 480))),
        gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
            enabled = true,
            modelPath = "/models/gemma.litertlm",
            displayName = "gemma.litertlm"
        ),
        gemmaFrameCaptioner = gemmaCaptioner,
        cleanupScope = cleanupScope
    )
    viewModel.enableCamera()
    dispatcher.scheduler.advanceUntilIdle()

    viewModel.startPreview(FakeSurfaceToken())
    dispatcher.scheduler.advanceUntilIdle()
    gemmaCaptioner.activeSession?.emitCaption("a desk with a monitor")
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals("/models/gemma.litertlm", gemmaCaptioner.startedModelPath)
    assertEquals("a desk with a monitor", viewModel.uiState.value.gemmaSubtitleText)
}

@Test
fun stopPreview_closesGemmaSessionAndClearsSubtitle() = runTest {
    val gemmaCaptioner = FakeGemmaFrameCaptioner()
    val cameraSource = FakeCameraSource(startResult = CameraSource.StartResult.Started(PreviewSize(640, 480)))
    val viewModel = buildViewModel(
        cameraSource = cameraSource,
        gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
            enabled = true,
            modelPath = "/models/gemma.litertlm",
            displayName = "gemma.litertlm"
        ),
        gemmaFrameCaptioner = gemmaCaptioner,
        cleanupScope = cleanupScope
    )
    viewModel.enableCamera()
    dispatcher.scheduler.advanceUntilIdle()
    viewModel.startPreview(FakeSurfaceToken())
    dispatcher.scheduler.advanceUntilIdle()
    gemmaCaptioner.activeSession?.emitCaption("a desk")

    viewModel.stopPreview()
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(gemmaCaptioner.activeSession?.closed == true)
    assertEquals("", viewModel.uiState.value.gemmaSubtitleText)
}
```

Add fakes near existing test fakes:

```kotlin
private class FakeGemmaSubtitlePreferences(
    private var enabled: Boolean = false,
    private var modelPath: String? = null,
    private var displayName: String? = null
) : GemmaSubtitlePreferences {
    override fun isGemmaSubtitlesEnabled(): Boolean = enabled
    override fun setGemmaSubtitlesEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
    override fun getModelPath(): String? = modelPath
    override fun getModelDisplayName(): String? = displayName
    override fun setModel(path: String, displayName: String?) {
        modelPath = path
        this.displayName = displayName
    }
    override fun clearModel() {
        modelPath = null
        displayName = null
    }
}

private class FakeGemmaFrameCaptioner : GemmaFrameCaptioner {
    var startedModelPath: String? = null
    var activeSession: FakeGemmaCaptionSession? = null

    override fun start(
        modelPath: String,
        previewSize: PreviewSize,
        onCaptionUpdated: (String) -> Unit,
        onError: (String) -> Unit
    ): GemmaCaptionSession {
        startedModelPath = modelPath
        return FakeGemmaCaptionSession(onCaptionUpdated).also {
            activeSession = it
        }
    }
}

private class FakeGemmaCaptionSession(
    private val onCaptionUpdated: (String) -> Unit
) : GemmaCaptionSession {
    var closed = false
    override val inputTarget = RecordingInputTarget.FrameCallbackTarget(
        pixelFormat = VideoFramePixelFormat.YUV420SP,
        frameConsumer = VideoFrameConsumer { _, _ -> }
    )

    fun emitCaption(caption: String) {
        onCaptionUpdated(caption)
    }

    override fun close() {
        closed = true
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest"
```

Expected: FAIL because Gemma fields and constructor parameters do not exist.

- [ ] **Step 3: Extend `PreviewUiState`**

Add these properties to `PreviewUiState` after `objectDetectionEnabled`:

```kotlin
val gemmaSubtitlesEnabled: Boolean = false,
val gemmaModelDisplayName: String? = null,
val gemmaSubtitleText: String = "",
```

Add this property after `canChangeObjectDetection`:

```kotlin
val canChangeGemmaSubtitles: Boolean = true
```

- [ ] **Step 4: Extend factory injection**

In `PreviewViewModelFactory`, add constructor properties:

```kotlin
private val gemmaSubtitlePreferences: GemmaSubtitlePreferences,
private val gemmaModelImporter: GemmaModelImporter,
private val gemmaFrameCaptioner: GemmaFrameCaptioner,
```

Pass them into `PreviewViewModel`:

```kotlin
gemmaSubtitlePreferences = gemmaSubtitlePreferences,
gemmaModelImporter = gemmaModelImporter,
gemmaFrameCaptioner = gemmaFrameCaptioner,
```

- [ ] **Step 5: Extend ViewModel constructor and state loading**

Add imports:

```kotlin
import android.net.Uri
import com.example.ar_control.gemma.GemmaFrameCaptioner
import com.example.ar_control.gemma.GemmaCaptionSession
import com.example.ar_control.gemma.GemmaModelImportResult
import com.example.ar_control.gemma.GemmaModelImporter
import com.example.ar_control.gemma.GemmaSubtitlePreferences
import com.example.ar_control.gemma.NoOpGemmaFrameCaptioner
```

Add constructor parameters after detection dependencies:

```kotlin
private val gemmaSubtitlePreferences: GemmaSubtitlePreferences = object : GemmaSubtitlePreferences {
    override fun isGemmaSubtitlesEnabled(): Boolean = false
    override fun setGemmaSubtitlesEnabled(enabled: Boolean) = Unit
    override fun getModelPath(): String? = null
    override fun getModelDisplayName(): String? = null
    override fun setModel(path: String, displayName: String?) = Unit
    override fun clearModel() = Unit
},
private val gemmaModelImporter: GemmaModelImporter? = null,
private val gemmaFrameCaptioner: GemmaFrameCaptioner = NoOpGemmaFrameCaptioner,
```

Add a field:

```kotlin
private var activeGemmaCaptionSession: GemmaCaptionSession? = null
```

Rename `loadInitialRecordingState()` to `loadInitialPreferencesAndClips()` and include:

```kotlin
val gemmaSubtitlesEnabled = gemmaSubtitlePreferences.isGemmaSubtitlesEnabled()
val gemmaModelDisplayName = gemmaSubtitlePreferences.getModelDisplayName()
_uiState.value = applyRecoveryState(_uiState.value.copy(
    recordVideoEnabled = recordVideoEnabled,
    objectDetectionEnabled = objectDetectionEnabled,
    gemmaSubtitlesEnabled = gemmaSubtitlesEnabled,
    gemmaModelDisplayName = gemmaModelDisplayName
))
```

- [ ] **Step 6: Add ViewModel actions**

Add public functions:

```kotlin
fun setGemmaSubtitlesEnabled(enabled: Boolean) {
    if (recoverySnapshot.isSafeMode) {
        sessionLog.record("PreviewViewModel", "Gemma subtitles preference change ignored because safe mode is active")
        return
    }
    gemmaSubtitlePreferences.setGemmaSubtitlesEnabled(enabled)
    sessionLog.record("PreviewViewModel", "Gemma subtitles preference changed: $enabled")
    _uiState.value = applyRecoveryState(_uiState.value.copy(gemmaSubtitlesEnabled = enabled))
}

fun importGemmaModel(uri: Uri, displayName: String?) {
    val importer = gemmaModelImporter ?: return
    viewModelScope.launch {
        sessionLog.record("PreviewViewModel", "Importing Gemma model: ${displayName ?: "unknown"}")
        when (val result = importer.importModel(uri, displayName)) {
            is GemmaModelImportResult.Imported -> {
                _uiState.value = applyRecoveryState(_uiState.value.copy(
                    gemmaModelDisplayName = result.displayName,
                    errorMessage = null
                ))
            }
            is GemmaModelImportResult.Failed -> {
                _uiState.value = applyRecoveryState(_uiState.value.copy(
                    errorMessage = result.reason
                ))
            }
        }
    }
}
```

- [ ] **Step 7: Add Gemma startup to frame pipeline**

In `maybeStartFramePipeline()`, compute:

```kotlin
val shouldRunGemma = _uiState.value.gemmaSubtitlesEnabled
val gemmaModelPath = gemmaSubtitlePreferences.getModelPath()
if (shouldRunGemma && gemmaModelPath.isNullOrBlank()) {
    _uiState.value = applyRecoveryState(_uiState.value.copy(
        errorMessage = "Gemma model is not configured",
        gemmaSubtitleText = ""
    ))
}
```

Create a `gemmaSession` only when `shouldRunGemma && !gemmaModelPath.isNullOrBlank()`:

```kotlin
val gemmaSession = if (shouldRunGemma && !gemmaModelPath.isNullOrBlank()) {
    gemmaFrameCaptioner.start(
        modelPath = gemmaModelPath,
        previewSize = previewSize,
        onCaptionUpdated = { caption ->
            viewModelScope.launch {
                if (_uiState.value.isPreviewRunning) {
                    _uiState.value = applyRecoveryState(_uiState.value.copy(
                        gemmaSubtitleText = caption
                    ))
                }
            }
        },
        onError = { reason ->
            viewModelScope.launch {
                closeGemmaCaptionSession(clearSubtitle = true)
                _uiState.value = applyRecoveryState(_uiState.value.copy(errorMessage = reason))
            }
        }
    ).also { activeGemmaCaptionSession = it }
} else {
    null
}
```

When building a frame callback target, combine detection, Gemma, and recorder frame targets:

```kotlin
private fun combineFrameTargets(
    recorderTarget: RecordingInputTarget?,
    detectionSession: ObjectDetectionSession?,
    gemmaSession: GemmaCaptionSession?
): RecordingInputTarget? {
    val frameTargets = mutableListOf<RecordingInputTarget.FrameCallbackTarget>()
    val recordingCallbackTarget = recorderTarget as? RecordingInputTarget.FrameCallbackTarget
    if (recordingCallbackTarget != null) {
        frameTargets += recordingCallbackTarget
    } else if (recorderTarget != null && (detectionSession != null || gemmaSession != null)) {
        return null
    }
    detectionSession?.inputTarget?.let(frameTargets::add)
    gemmaSession?.inputTarget?.let(frameTargets::add)
    return when {
        frameTargets.isNotEmpty() -> FrameCallbackTargetFanOut.combine(frameTargets)
        recorderTarget != null -> recorderTarget
        else -> null
    }
}
```

- [ ] **Step 8: Close Gemma sessions during cleanup**

Add:

```kotlin
private fun closeGemmaCaptionSession(clearSubtitle: Boolean) {
    activeGemmaCaptionSession?.close()
    activeGemmaCaptionSession = null
    if (clearSubtitle) {
        _uiState.value = applyRecoveryState(_uiState.value.copy(gemmaSubtitleText = ""))
    }
}
```

Call it in `onCleared()`, `stopPreviewAndFinalizeRecording()`, stale generation cleanup paths, and any path currently calling `closeDetectionSession(clearDetections = true)` for preview teardown.

- [ ] **Step 9: Extend recovery masking**

In safe mode branch of `applyRecoveryState()`, add:

```kotlin
gemmaSubtitlesEnabled = false,
gemmaSubtitleText = "",
canChangeGemmaSubtitles = false
```

In normal branch, add:

```kotlin
canChangeGemmaSubtitles = true
```

- [ ] **Step 10: Run ViewModel tests and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest"
```

Expected: PASS.

- [ ] **Step 11: Commit**

```powershell
git add app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt
git commit -m "feat: manage Gemma subtitle preview state"
```

---

### Task 7: Add Control Screen And Floating Pill UI

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/dimens.xml`
- Modify: `app/src/main/java/com/example/ar_control/MainActivity.kt`
- Modify: `app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt`

- [ ] **Step 1: Write failing UI tests**

In `MainActivityTest`, add:

```kotlin
@Test
fun gemmaControlsAreVisibleOnControlScreen() {
    ActivityScenario.launch(MainActivity::class.java).use {
        onView(withId(R.id.gemmaSubtitlesCheckbox)).check(matches(isDisplayed()))
        onView(withId(R.id.importGemmaModelButton)).check(matches(isDisplayed()))
        onView(withId(R.id.gemmaModelStatusText)).check(matches(isDisplayed()))
    }
}

@Test
fun gemmaSubtitlePillIsHiddenUntilPreviewCaptionExists() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        onView(withId(R.id.gemmaSubtitleText)).check(matches(withEffectiveVisibility(Visibility.GONE)))

        scenario.onActivity { activity ->
            val state = PreviewUiState(
                isPreviewRunning = true,
                previewSize = PreviewSize(width = 640, height = 480),
                gemmaSubtitleText = "a desk with a monitor"
            )
            activity.renderForTest(state)
        }

        onView(withId(R.id.gemmaSubtitleText)).check(matches(isDisplayed()))
        onView(withId(R.id.gemmaSubtitleText)).check(matches(withText("a desk with a monitor")))
    }
}
```

If `renderForTest` does not exist, add this internal helper to `MainActivity` in the implementation step:

```kotlin
internal fun renderForTest(uiState: PreviewUiState) {
    render(uiState)
}
```

- [ ] **Step 2: Run UI tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: FAIL because the new IDs do not exist. If no device is connected, run `.\gradlew.bat :app:assembleAndroidTest` and keep this test for device verification later.

- [ ] **Step 3: Add strings and dimension**

Add to `app/src/main/res/values/strings.xml`:

```xml
<string name="gemma_subtitles">Gemma subtitles</string>
<string name="import_gemma_model">Import Gemma model</string>
<string name="gemma_model_not_configured">Gemma model: not configured</string>
<string name="gemma_model_configured">Gemma model: %1$s</string>
```

Add to `app/src/main/res/values/dimens.xml`:

```xml
<dimen name="gemma_subtitle_margin_bottom">40dp</dimen>
<dimen name="gemma_subtitle_padding_horizontal">18dp</dimen>
<dimen name="gemma_subtitle_padding_vertical">10dp</dimen>
```

- [ ] **Step 4: Add controls and floating pill to layout**

In the existing checkbox row in `activity_main.xml`, add:

```xml
<com.google.android.material.checkbox.MaterialCheckBox
    android:id="@+id/gemmaSubtitlesCheckbox"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginStart="@dimen/control_item_spacing"
    android:text="@string/gemma_subtitles" />
```

Below the checkbox row, add:

```xml
<TextView
    android:id="@+id/gemmaModelStatusText"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="@dimen/control_item_spacing"
    android:text="@string/gemma_model_not_configured"
    android:textAppearance="?attr/textAppearanceBodyMedium" />

<Button
    android:id="@+id/importGemmaModelButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="@dimen/control_item_spacing"
    android:text="@string/import_gemma_model" />
```

Inside `previewContainer`, add this TextView after `detectionOverlayView` and before `previewBackButton`:

```xml
<TextView
    android:id="@+id/gemmaSubtitleText"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="bottom|center_horizontal"
    android:layout_marginStart="@dimen/control_item_spacing"
    android:layout_marginEnd="@dimen/control_item_spacing"
    android:layout_marginBottom="@dimen/gemma_subtitle_margin_bottom"
    android:background="#C0000000"
    android:maxLines="2"
    android:paddingStart="@dimen/gemma_subtitle_padding_horizontal"
    android:paddingTop="@dimen/gemma_subtitle_padding_vertical"
    android:paddingEnd="@dimen/gemma_subtitle_padding_horizontal"
    android:paddingBottom="@dimen/gemma_subtitle_padding_vertical"
    android:textAppearance="?attr/textAppearanceBodyLarge"
    android:textColor="@android:color/white"
    android:visibility="gone" />
```

- [ ] **Step 5: Wire MainActivity UI state and file picker**

Add a launcher property:

```kotlin
private val gemmaModelPickerLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri ->
    if (uri != null) {
        previewViewModel.importGemmaModel(uri, uri.lastPathSegment)
    }
}
```

Add a listener field:

```kotlin
private lateinit var gemmaSubtitlesCheckedChangeListener: CompoundButton.OnCheckedChangeListener
```

Initialize it in `onCreate()`:

```kotlin
gemmaSubtitlesCheckedChangeListener =
    CompoundButton.OnCheckedChangeListener { _, isChecked ->
        appContainer.sessionLog.record(
            "MainActivity",
            "Gemma subtitles checkbox changed: $isChecked"
        )
        previewViewModel.setGemmaSubtitlesEnabled(isChecked)
    }
```

Add listeners:

```kotlin
binding.gemmaSubtitlesCheckbox.setOnCheckedChangeListener(gemmaSubtitlesCheckedChangeListener)
binding.importGemmaModelButton.setOnClickListener {
    gemmaModelPickerLauncher.launch(arrayOf("*/*"))
}
```

In `render()`, add:

```kotlin
renderGemmaSubtitlesCheckbox(uiState.gemmaSubtitlesEnabled)
binding.gemmaSubtitlesCheckbox.isEnabled = uiState.canChangeGemmaSubtitles
binding.gemmaModelStatusText.text = uiState.gemmaModelDisplayName?.let { name ->
    getString(R.string.gemma_model_configured, name)
} ?: getString(R.string.gemma_model_not_configured)
binding.gemmaSubtitleText.text = uiState.gemmaSubtitleText
binding.gemmaSubtitleText.visibility =
    if (uiState.isPreviewRunning && uiState.gemmaSubtitleText.isNotBlank()) View.VISIBLE else View.GONE
```

Add helper:

```kotlin
private fun renderGemmaSubtitlesCheckbox(gemmaSubtitlesEnabled: Boolean) {
    if (binding.gemmaSubtitlesCheckbox.isChecked == gemmaSubtitlesEnabled) {
        return
    }
    binding.gemmaSubtitlesCheckbox.setOnCheckedChangeListener(null)
    binding.gemmaSubtitlesCheckbox.isChecked = gemmaSubtitlesEnabled
    binding.gemmaSubtitlesCheckbox.setOnCheckedChangeListener(gemmaSubtitlesCheckedChangeListener)
}
```

- [ ] **Step 6: Run UI/build verification**

Run:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleAndroidTest
```

Expected: PASS. If a device is connected, also run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/res/values/dimens.xml app/src/main/java/com/example/ar_control/MainActivity.kt app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt
git commit -m "feat: add Gemma subtitle controls"
```

---

### Task 8: Wire Production Dependencies

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`
- Modify: `app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt`

- [ ] **Step 1: Update fake app container compile path first**

In `MainActivityTest`, update the `PreviewViewModelFactory` construction with fakes:

```kotlin
gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(),
gemmaModelImporter = GemmaModelImporter(
    targetDirectory = context.filesDir.resolve("test-gemma-models"),
    preferences = FakeGemmaSubtitlePreferences(),
    openInputStream = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
),
gemmaFrameCaptioner = NoOpGemmaFrameCaptioner,
```

Add imports:

```kotlin
import com.example.ar_control.gemma.GemmaModelImporter
import com.example.ar_control.gemma.GemmaSubtitlePreferences
import com.example.ar_control.gemma.NoOpGemmaFrameCaptioner
import java.io.ByteArrayInputStream
```

Add this local fake to `MainActivityTest`:

```kotlin
private class FakeGemmaSubtitlePreferences : GemmaSubtitlePreferences {
    private var enabled = false
    private var modelPath: String? = null
    private var displayName: String? = null

    override fun isGemmaSubtitlesEnabled(): Boolean = enabled

    override fun setGemmaSubtitlesEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun getModelPath(): String? = modelPath

    override fun getModelDisplayName(): String? = displayName

    override fun setModel(path: String, displayName: String?) {
        modelPath = path
        this.displayName = displayName
    }

    override fun clearModel() {
        modelPath = null
        displayName = null
    }
}
```

- [ ] **Step 2: Run compile and verify RED**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: FAIL until `DefaultAppContainer` supplies the new factory dependencies.

- [ ] **Step 3: Wire DefaultAppContainer**

Add imports:

```kotlin
import com.example.ar_control.gemma.GemmaFrameCaptioner
import com.example.ar_control.gemma.GemmaModelImporter
import com.example.ar_control.gemma.GemmaSubtitlePreferences
import com.example.ar_control.gemma.LiteRtGemmaFrameCaptioner
import com.example.ar_control.gemma.SharedPreferencesGemmaSubtitlePreferences
```

Add lazy properties:

```kotlin
private val gemmaSubtitlePreferences: GemmaSubtitlePreferences by lazy {
    SharedPreferencesGemmaSubtitlePreferences(appContext)
}

private val gemmaModelImporter: GemmaModelImporter by lazy {
    GemmaModelImporter(
        context = appContext,
        preferences = gemmaSubtitlePreferences
    )
}

private val gemmaFrameCaptioner: GemmaFrameCaptioner by lazy {
    LiteRtGemmaFrameCaptioner(
        context = appContext,
        sessionLog = sessionLog
    )
}
```

Pass these into `PreviewViewModelFactory`:

```kotlin
gemmaSubtitlePreferences = gemmaSubtitlePreferences,
gemmaModelImporter = gemmaModelImporter,
gemmaFrameCaptioner = gemmaFrameCaptioner,
```

- [ ] **Step 4: Run compile and unit tests**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt
git commit -m "feat: wire Gemma subtitle dependencies"
```

---

### Task 9: Final Verification And Docs

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md` if the file is intentionally kept in the repository

- [ ] **Step 1: Add README usage note**

Add this section under the README build/run or developer notes area:

```markdown
## Gemma subtitles

`Gemma subtitles` uses an on-device `.litertlm` model imported from device storage. Do not commit model files to this repository. On a device, tap `Import Gemma model`, select a compatible Gemma 4 multimodal LiteRT-LM model, enable `Gemma subtitles`, then start preview. Captions appear as a floating pill over fullscreen preview.
```

- [ ] **Step 2: Run full CI-equivalent verification**

Run:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:assembleRelease
```

Expected: PASS.

- [ ] **Step 3: Run Android lint**

Run:

```powershell
.\gradlew.bat :app:lintDebug
```

Expected: PASS or only existing unrelated warnings. Investigate any new Gemma-related lint failures before proceeding.

- [ ] **Step 4: Commit docs**

```powershell
git add README.md AGENTS.md
git commit -m "docs: document Gemma subtitles setup"
```

- [ ] **Step 5: Manual hardware validation**

On Huawei P60 Pro with XREAL One Pro:

```text
1. Install debug APK.
2. Tap Import Gemma model and select a compatible Gemma 4 .litertlm file.
3. Enable Gemma subtitles.
4. Enable camera.
5. Start preview.
6. Confirm a floating pill caption appears after a few seconds.
7. Confirm Stop Preview clears the caption and releases the preview.
8. Repeat with Object detection enabled to verify frame fan-out coexistence.
9. Repeat with Record video enabled to verify recording still starts and stops.
```

Expected: preview remains responsive, captions update at the sampling interval, and failures show a status message without crashing preview.

---

## Self-Review Notes

- Spec coverage: checkbox, sideloaded model import, private model path persistence, frame-caption lifecycle, floating pill UI, failure handling, safe mode masking, and tests are covered by Tasks 2 through 9.
- Dependency source: Google Maven metadata on April 24, 2026 reports `com.google.ai.edge.litertlm:litertlm-android:0.10.2` as latest/release. Google AI Edge docs show `EngineConfig`, `Engine.initialize()`, `createConversation()`, `Content.ImageBytes`, and `Content.Text` for multimodal LiteRT-LM.
- Main risk: Gemma 4 model availability and device performance are hardware/model dependent. The implementation samples frames every 3 seconds and uses CPU backend first to maximize compatibility; GPU/NPU acceleration should be a separate tuning change after this feature is correct.
