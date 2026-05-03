package com.example.ar_control.face

import com.example.ar_control.detection.DetectionBoundingBox

class FaceBoxStabilizer(
    private val requiredHits: Int = 2,
    private val maxMisses: Int = 1,
    private val minIou: Float = 0.35f
) {
    private val tracks = mutableListOf<Track>()

    fun update(rawBoxes: List<FaceBoundingBox>): List<FaceBoundingBox> {
        tracks.forEach { track -> track.matchedThisFrame = false }
        for (box in rawBoxes) {
            val track = tracks
                .filter { !it.matchedThisFrame }
                .maxByOrNull { iou(it.box.boundingBox, box.boundingBox) }
                ?.takeIf { iou(it.box.boundingBox, box.boundingBox) >= minIou }
            if (track == null) {
                tracks += Track(box = box, consecutiveHits = 1, misses = 0, matchedThisFrame = true)
            } else {
                track.box = box
                track.consecutiveHits += 1
                track.misses = 0
                track.matchedThisFrame = true
            }
        }
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (!track.matchedThisFrame) {
                track.misses += 1
                track.consecutiveHits = 0
            }
            if (track.misses > maxMisses) {
                iterator.remove()
            }
        }
        return tracks
            .filter { track -> track.consecutiveHits >= requiredHits && track.misses == 0 }
            .map { track -> track.box }
    }

    private data class Track(
        var box: FaceBoundingBox,
        var consecutiveHits: Int,
        var misses: Int,
        var matchedThisFrame: Boolean
    )

    companion object {
        fun iou(first: DetectionBoundingBox, second: DetectionBoundingBox): Float {
            val left = maxOf(first.left, second.left)
            val top = maxOf(first.top, second.top)
            val right = minOf(first.right, second.right)
            val bottom = minOf(first.bottom, second.bottom)
            val intersectionWidth = (right - left).coerceAtLeast(0f)
            val intersectionHeight = (bottom - top).coerceAtLeast(0f)
            val intersection = intersectionWidth * intersectionHeight
            val union = (first.width * first.height) + (second.width * second.height) - intersection
            if (union <= 0f) {
                return 0f
            }
            return intersection / union
        }
    }
}
