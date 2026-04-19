package com.example.ar_control

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.recyclerview.widget.RecyclerView
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectionPreferences
import com.example.ar_control.detection.NoOpObjectDetector
import com.example.ar_control.di.AppContainer
import com.example.ar_control.diagnostics.DiagnosticsReportBuilder
import com.example.ar_control.diagnostics.InMemorySessionLog
import com.example.ar_control.recording.ClipFileSharer
import com.example.ar_control.recording.ClipRepository
import com.example.ar_control.recording.RecordedClip
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.RecordingPreferences
import com.example.ar_control.recording.VideoRecorder
import com.example.ar_control.recovery.RecoveryManager
import com.example.ar_control.recovery.RecoverySnapshot
import com.example.ar_control.ui.preview.PreviewViewModelFactory
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val cameraPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private lateinit var app: ArControlApp
    private lateinit var originalContainer: AppContainer
    private lateinit var fakeAppContainer: FakeAppContainer

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        originalContainer = app.appContainer
        fakeAppContainer = FakeAppContainer(app)
        app.replaceAppContainerForTesting(fakeAppContainer)
    }

    @After
    fun tearDown() {
        app.replaceAppContainerForTesting(originalContainer)
    }

    @Test
    fun enableAndStartPreview_updatesButtonStateFromFakeContainer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.enableCameraButton)).check(matches(isDisplayed()))
            onView(withId(R.id.startPreviewButton)).check(matches(isDisplayed()))
            onView(withId(R.id.startPreviewButton)).check(matches(not(isEnabled())))
            onView(withId(R.id.stopPreviewButton)).check(matches(isDisplayed()))
            onView(withId(R.id.stopPreviewButton)).check(matches(not(isEnabled())))
            onView(withId(R.id.shareLogsButton)).check(matches(isDisplayed()))
            onView(withId(R.id.controlsContainer)).check(matches(isDisplayed()))
            onView(withId(R.id.previewBackButton)).check(
                matches(withEffectiveVisibility(Visibility.INVISIBLE))
            )

            onView(withId(R.id.enableCameraButton)).perform(click())

            onView(withId(R.id.cameraStatusText)).check(matches(withText("Camera enabled")))
            onView(withId(R.id.startPreviewButton)).check(matches(isEnabled()))
            onView(withId(R.id.stopPreviewButton)).check(matches(not(isEnabled())))

            onView(withId(R.id.startPreviewButton)).perform(click())

            onView(withId(R.id.controlsContainer)).check(
                matches(withEffectiveVisibility(Visibility.GONE))
            )
            onView(withId(R.id.previewContainer)).check(matches(isDisplayed()))
            onView(withId(R.id.previewBackButton)).check(matches(isDisplayed()))

            onView(withId(R.id.previewBackButton)).perform(click())

            onView(withId(R.id.controlsContainer)).check(matches(isDisplayed()))
            onView(withId(R.id.previewBackButton)).check(
                matches(withEffectiveVisibility(Visibility.INVISIBLE))
            )
            onView(withId(R.id.cameraStatusText)).check(matches(withText("Camera enabled")))
            onView(withId(R.id.startPreviewButton)).check(matches(isEnabled()))
            onView(withId(R.id.stopPreviewButton)).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun hardwareBack_stopsPreviewAndRestoresControls() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.enableCameraButton)).perform(click())
            onView(withId(R.id.startPreviewButton)).perform(click())

            onView(withId(R.id.previewContainer)).check(matches(isDisplayed()))
            pressBack()

            onView(withId(R.id.controlsContainer)).check(matches(isDisplayed()))
            onView(withId(R.id.previewBackButton)).check(
                matches(withEffectiveVisibility(Visibility.INVISIBLE))
            )
            onView(withId(R.id.startPreviewButton)).check(matches(isEnabled()))
        }
    }

    @Test
    fun volumeButtons_zoomPreviewWithoutLeavingFullscreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.enableCameraButton)).perform(click())
            onView(withId(R.id.startPreviewButton)).perform(click())

            scenario.onActivity { activity ->
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
                assertEquals(1.15f, activity.findViewById<com.example.ar_control.preview.AspectRatioTextureView>(R.id.previewTextureView).scaleX)
            }

            onView(withId(R.id.previewContainer)).check(matches(isDisplayed()))
            onView(withId(R.id.previewBackButton)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun controlScreen_showsRecordCheckboxAndDisabledClipActions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Record video")).check(matches(isDisplayed()))
            onView(withText("Recorded clips")).check(matches(isDisplayed()))
            onView(withText("Open")).check(matches(not(isEnabled())))
            onView(withText("Share")).check(matches(not(isEnabled())))
            onView(withText("Delete")).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun controlScreen_selectingClipUpdatesCheckboxAndEnablesClipActions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Record video")).check(matches(not(isChecked())))
            onView(withText("Record video")).perform(click())

            assertTrue(fakeAppContainer.recordingPreferences.enabled)
            assertEquals(1, fakeAppContainer.recordingPreferences.setCalls)

            onView(isAssignableFrom(RecyclerView::class.java)).perform(clickRecyclerViewItemAtPosition(0))

            onView(withText("Open")).check(matches(isEnabled()))
            onView(withText("Share")).check(matches(isEnabled()))
            onView(withText("Delete")).check(matches(isEnabled()))
        }
    }

    @Test
    fun controlScreen_openShareAndDeleteOperateOnSelectedClip() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(isAssignableFrom(RecyclerView::class.java)).perform(clickRecyclerViewItemAtPosition(0))

            val monitor = blockStartedActivities()
            try {
                onView(withText("Open")).perform(click())
                onView(withText("Share")).perform(click())
            } finally {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .removeMonitor(monitor)
            }

            assertEquals(listOf("clip-alpha"), fakeAppContainer.clipFileSharer.openedClipIds)
            assertEquals(listOf("clip-alpha"), fakeAppContainer.clipFileSharer.sharedClipIds)

            onView(withText("Delete")).perform(click())
            onView(allOf(withId(android.R.id.button1), withText("Delete"))).perform(click())

            assertEquals(listOf("clip-alpha"), fakeAppContainer.clipRepository.deletedClipIds)
            onView(withText("Open")).check(matches(not(isEnabled())))
            onView(withText("Share")).check(matches(not(isEnabled())))
            onView(withText("Delete")).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun safeMode_showsRecoveryCard_disablesCameraActions_andRequiresConfirmationToExit() {
        fakeAppContainer = FakeAppContainer(
            app,
            recoveryManager = FakeRecoveryManager(
                snapshot = RecoverySnapshot(
                    isSafeMode = true,
                    safeModeReason = "Recovered after abnormal termination during recording"
                )
            )
        )
        app.replaceAppContainerForTesting(fakeAppContainer)

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.recoveryCard)).check(matches(isDisplayed()))
            onView(withId(R.id.enableCameraButton)).check(matches(not(isEnabled())))
            onView(withId(R.id.startPreviewButton)).check(matches(not(isEnabled())))
            onView(withText("Record video")).check(matches(not(isEnabled())))

            onView(withId(R.id.reEnableCameraTestingButton)).perform(click())
            onView(withText(R.string.re_enable_camera_testing_title)).check(matches(isDisplayed()))
            onView(withId(android.R.id.button1)).perform(click())

            assertEquals(1, fakeAppContainer.recoveryManager.clearSafeModeCalls)
            onView(withId(R.id.recoveryCard)).check(
                matches(withEffectiveVisibility(Visibility.GONE))
            )
            onView(withId(R.id.enableCameraButton)).check(matches(isEnabled()))
        }
    }
}

private class FakeAppContainer(
    context: Context,
    val recoveryManager: FakeRecoveryManager = FakeRecoveryManager()
) : AppContainer {
    override val sessionLog = InMemorySessionLog()
    override val diagnosticsReportBuilder = DiagnosticsReportBuilder(
        appVersionName = "test",
        appVersionCode = 1
    )
    val recordingPreferences = FakeRecordingPreferences()
    val clipRepository = FakeClipRepository(
        mutableListOf(
            RecordedClip(
                id = "clip-alpha",
                filePath = context.cacheDir.resolve("clip-alpha.mp4").apply {
                    writeBytes(byteArrayOf(1))
                }.absolutePath,
                createdAtEpochMillis = 2_000L,
                durationMillis = 15_000L,
                width = 1920,
                height = 1080,
                fileSizeBytes = 1L,
                mimeType = "video/mp4"
            ),
            RecordedClip(
                id = "clip-beta",
                filePath = context.cacheDir.resolve("clip-beta.mp4").apply {
                    writeBytes(byteArrayOf(2))
                }.absolutePath,
                createdAtEpochMillis = 1_000L,
                durationMillis = 5_000L,
                width = 1280,
                height = 720,
                fileSizeBytes = 1L,
                mimeType = "video/mp4"
            )
        )
    )
    val detectionPreferences = FakeDetectionPreferences()
    override val clipFileSharer = FakeClipFileSharer(context)
    override val previewViewModelFactory = PreviewViewModelFactory(
        glassesSession = FakeGlassesSession(),
        eyeUsbConfigurator = FakeEyeUsbConfigurator(),
        usbPermissionGateway = FakeUsbPermissionGateway(),
        cameraSource = FakeCameraSource(),
        recordingPreferences = recordingPreferences,
        detectionPreferences = detectionPreferences,
        objectDetector = NoOpObjectDetector,
        clipRepository = clipRepository,
        videoRecorder = FakeVideoRecorder(),
        recoveryManager = recoveryManager,
        sessionLog = sessionLog
    )
}

private class FakeGlassesSession : GlassesSession {
    override val state = MutableStateFlow(GlassesSession.State.Available)

    override suspend fun start() = Unit

    override suspend fun stop() = Unit
}

private class FakeEyeUsbConfigurator : EyeUsbConfigurator {
    override suspend fun enableCamera() = EyeUsbConfigurator.Result.Enabled
}

private class FakeUsbPermissionGateway : UsbPermissionGateway {
    override suspend fun ensureControlPermission() = true
}

private class FakeCameraSource : CameraSource {
    override suspend fun start(surfaceToken: CameraSource.SurfaceToken) =
        CameraSource.StartResult.Started(PreviewSize(width = 1920, height = 1080))

    override suspend fun stop() = Unit

    override suspend fun startRecording(target: RecordingInputTarget) =
        CameraSource.RecordingStartResult.Started

    override suspend fun stopRecording() = Unit
}

private class FakeRecordingPreferences(
    var enabled: Boolean = false
) : RecordingPreferences {
    var setCalls = 0

    override fun isRecordingEnabled(): Boolean = enabled

    override fun setRecordingEnabled(enabled: Boolean) {
        this.enabled = enabled
        setCalls += 1
    }
}

private class FakeDetectionPreferences(
    var enabled: Boolean = false
) : DetectionPreferences {
    var setCalls = 0

    override fun isObjectDetectionEnabled(): Boolean = enabled

    override fun setObjectDetectionEnabled(enabled: Boolean) {
        this.enabled = enabled
        setCalls += 1
    }
}

private class FakeClipRepository(
    private val clips: MutableList<RecordedClip>
) : ClipRepository {
    val deletedClipIds = mutableListOf<String>()

    override suspend fun load(): List<RecordedClip> = clips.toList()

    override suspend fun insert(clip: RecordedClip) {
        clips.add(0, clip)
    }

    override suspend fun delete(clipId: String): Boolean {
        deletedClipIds += clipId
        return clips.removeAll { it.id == clipId }
    }
}

private class FakeVideoRecorder : VideoRecorder {
    override suspend fun start(previewSize: PreviewSize): VideoRecorder.StartResult =
        VideoRecorder.StartResult.Failed("not_used")

    override suspend fun stop(): VideoRecorder.StopResult =
        VideoRecorder.StopResult.Failed("not_used")

    override suspend fun cancel(): VideoRecorder.CancelResult =
        VideoRecorder.CancelResult.Cancelled
}

private class FakeClipFileSharer(
    private val context: Context
) : ClipFileSharer {
    val openedClipIds = mutableListOf<String>()
    val sharedClipIds = mutableListOf<String>()

    override fun buildOpenIntent(clip: RecordedClip): Intent {
        openedClipIds += clip.id
        return Intent(context, MainActivity::class.java)
    }

    override fun buildShareIntent(clip: RecordedClip): Intent {
        sharedClipIds += clip.id
        return Intent(context, MainActivity::class.java)
    }
}

private class FakeRecoveryManager(
    private var snapshot: RecoverySnapshot = RecoverySnapshot()
) : RecoveryManager {
    var clearSafeModeCalls = 0
        private set

    override fun snapshot(): RecoverySnapshot = snapshot

    override fun markPreviewStarted() = Unit

    override fun markRecordingStarted(outputFilePath: String, width: Int?, height: Int?, mimeType: String?) = Unit

    override fun markCameraIdle() = Unit

    override fun clearSafeMode() {
        clearSafeModeCalls += 1
        snapshot = snapshot.copy(
            isSafeMode = false,
            safeModeReason = null
        )
    }
}

private fun clickRecyclerViewItemAtPosition(position: Int): ViewAction {
    return object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(RecyclerView::class.java)

        override fun getDescription(): String = "click RecyclerView item at position $position"

        override fun perform(
            uiController: androidx.test.espresso.UiController,
            view: View
        ) {
            val recyclerView = view as RecyclerView
            recyclerView.scrollToPosition(position)
            uiController.loopMainThreadUntilIdle()
            val viewHolder = checkNotNull(recyclerView.findViewHolderForAdapterPosition(position)) {
                "No ViewHolder at position $position"
            }
            viewHolder.itemView.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }
}

private fun blockStartedActivities(): Instrumentation.ActivityMonitor {
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val monitor = Instrumentation.ActivityMonitor(
        null as String?,
        Instrumentation.ActivityResult(Activity.RESULT_OK, null),
        true
    )
    instrumentation.addMonitor(monitor)
    return monitor
}
