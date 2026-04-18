package com.example.ar_control.recording

interface ClipRepository {
    /**
     * Returns the visible clip list for the catalog.
     *
     * Implementations may preserve malformed on-disk catalog content instead of repairing it;
     * in that case callers should observe an empty visible list rather than a rewritten file.
     */
    suspend fun load(): List<RecordedClip>
    suspend fun insert(clip: RecordedClip)
    suspend fun delete(clipId: String): Boolean
}
