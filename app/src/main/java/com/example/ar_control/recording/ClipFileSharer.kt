package com.example.ar_control.recording

import android.content.Intent

interface ClipFileSharer {
    fun buildOpenIntent(clip: RecordedClip): Intent
    fun buildShareIntent(clip: RecordedClip): Intent
}
