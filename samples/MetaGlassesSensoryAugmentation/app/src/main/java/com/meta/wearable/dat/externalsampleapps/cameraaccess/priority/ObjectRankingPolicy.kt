package com.meta.wearable.dat.externalsampleapps.cameraaccess.priority

import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.DetectionBox
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tracking.TrackedObject
import kotlin.math.abs

class ObjectRankingPolicy {
    companion object {
        private const val MIN_SONIFICATION_CONFIDENCE = 0.65f
    }

    fun rankTrackedObjects(
        trackedObjects: List<TrackedObject>,
        frameWidth: Int,
        frameHeight: Int,
    ): List<RankedObjectCandidate> {
        if (trackedObjects.isEmpty() || frameWidth <= 0 || frameHeight <= 0) {
            return emptyList()
        }

        return trackedObjects
            .asSequence()
            .filter { tracked -> tracked.score > MIN_SONIFICATION_CONFIDENCE }
            .map { tracked ->
                val rank = computeRank(tracked, frameWidth, frameHeight)
                RankedObjectCandidate(
                    trackId = tracked.trackId,
                    label = ObjectCueSoundMap.normalizeLabel(tracked.label),
                    soundAssetPath = ObjectCueSoundMap.soundForLabel(tracked.label),
                    score = tracked.score,
                    box = tracked.box,
                    rank = rank,
                )
            }
            .toList()
            .sortedByDescending { it.rank }
    }

    fun selectTopRanked(
        trackedObjects: List<TrackedObject>,
        frameWidth: Int,
        frameHeight: Int,
    ): RankedObjectCandidate? {
        return rankTrackedObjects(trackedObjects, frameWidth, frameHeight).firstOrNull()
    }

    private fun computeRank(
        tracked: TrackedObject,
        frameWidth: Int,
        frameHeight: Int,
    ): Float {
        val classPriority = classPriority(ObjectCueSoundMap.normalizeLabel(tracked.label))
        val boxWidth = (tracked.box.right - tracked.box.left).coerceAtLeast(0f)
        val boxHeight = (tracked.box.bottom - tracked.box.top).coerceAtLeast(0f)
        val areaNorm = (boxWidth * boxHeight).coerceIn(0f, 1f)

        val centerX = (tracked.box.left + tracked.box.right) / 2f
        val centerY = (tracked.box.top + tracked.box.bottom) / 2f
        val dx = abs(centerX - 0.5f)
        val dy = abs(centerY - 0.5f)
        val centerProximity = (1f - ((dx + dy) / 1f)).coerceIn(0f, 1f)

        return (classPriority * 0.50f) +
            (areaNorm * 0.25f) +
            (centerProximity * 0.15f) +
            (tracked.score.coerceIn(0f, 1f) * 0.10f)
    }

    private fun classPriority(label: String): Float {
        return when (label) {
            "person" -> 1.00f
            "door" -> 0.90f
            "chair" -> 0.85f
            "table" -> 0.80f
            "phone" -> 0.70f
            "cup" -> 0.65f
            else -> 0.20f
        }
    }
}

data class RankedObjectCandidate(
    val trackId: Long,
    val label: String,
    val soundAssetPath: String,
    val score: Float,
    val box: DetectionBox,
    val rank: Float,
)
