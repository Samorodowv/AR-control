package com.example.ar_control.startup

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.ar_control.MainActivity
import com.example.ar_control.R
import com.example.ar_control.databinding.ActivityLaunchGuardBinding
import com.example.ar_control.diagnostics.PersistentSessionLog
import com.example.ar_control.recovery.FileRecoveryStateStore
import java.io.File

class LaunchGuardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLaunchGuardBinding
    private lateinit var diagnosticsSnapshot: LaunchDiagnosticsSnapshot
    private val fatalCrashMarkerFile by lazy {
        File(filesDir, "recovery/fatal-crash.txt")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        diagnosticsSnapshot = buildDiagnosticsSnapshot()
        if (!diagnosticsSnapshot.shouldInterceptLaunch) {
            openMainApp()
            return
        }

        binding = ActivityLaunchGuardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.launchGuardMessageText.text = diagnosticsSnapshot.displayMessage
        binding.launchGuardShareLogsButton.setOnClickListener { shareDiagnostics() }
        binding.launchGuardOpenAppButton.setOnClickListener { openMainApp() }
        binding.launchGuardCloseButton.setOnClickListener { finish() }
    }

    private fun buildDiagnosticsSnapshot(): LaunchDiagnosticsSnapshot {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val loader = LaunchDiagnosticsLoader(
            appVersionName = packageInfo.versionName ?: "unknown",
            appVersionCode = packageInfo.longVersionCode.toInt(),
            recoveryStateProvider = {
                FileRecoveryStateStore(
                    File(filesDir, "recovery/state.properties")
                ).load()
            },
            fatalCrashReportProvider = {
                fatalCrashMarkerFile.takeIf(File::exists)?.readText(Charsets.UTF_8)
            },
            sessionLogProvider = {
                PersistentSessionLog(
                    file = File(filesDir, "diagnostics/session-log.txt")
                ).snapshot()
            }
        )
        return loader.load()
    }

    private fun shareDiagnostics() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_logs_subject))
                    putExtra(Intent.EXTRA_TEXT, diagnosticsSnapshot.report)
                },
                getString(R.string.share_logs)
            )
        )
    }

    private fun openMainApp() {
        runCatching { fatalCrashMarkerFile.delete() }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
