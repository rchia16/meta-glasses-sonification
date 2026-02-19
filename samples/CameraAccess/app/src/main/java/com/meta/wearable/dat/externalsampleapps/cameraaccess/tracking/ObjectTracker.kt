package com.meta.wearable.dat.externalsampleapps.cameraaccess.tracking

import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.DetectedObject
import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.DetectionBox
import kotlin.math.max
import kotlin.math.min

class ObjectTracker(
    private val maxTracks: Int = 5,
    private val minIouForMatch: Float = 0.3f,
    private val staleTrackTimeoutMs: Long = 1_500L,
) {
    private var nextTrackId = 1L
    private val tracks = mutableListOf<TrackedObject>()

    fun update(detections: List<DetectedObject>, nowMs: Long): List<TrackedObject> {
        val activeTracks = tracks.filter { nowMs - it.lastSeenAtMs <= staleTrackTimeoutMs }.toMutableList()
        val updated = mutableListOf<TrackedObject>()
        val usedTrackIds = mutableSetOf<Long>()

        for (detection in detections.sortedByDescending { it.score }) {
            val match =
                activeTracks
                    .asSequence()
                    .filter { it.trackId !in usedTrackIds && it.label == detection.label }
                    .map { it to iou(it.box, detection.box) }
                    .filter { (_, overlap) -> overlap >= minIouForMatch }
                    .maxByOrNull { (_, overlap) -> overlap }
                    ?.first

            if (match != null) {
                usedTrackIds += match.trackId
                updated +=
                    match.copy(
                        score = detection.score,
                        box = detection.box,
                        lastSeenAtMs = nowMs,
                    )
            } else if (updated.size < maxTracks) {
                updated +=
                    TrackedObject(
                        trackId = nextTrackId++,
                        label = detection.label,
                        score = detection.score,
                        box = detection.box,
                        lastSeenAtMs = nowMs,
                    )
            }
        }

        val carryOver =
            activeTracks.filter { track ->
                track.trackId !in usedTrackIds && updated.none { it.trackId == track.trackId }
            }
        updated += carryOver

        val pruned = updated.sortedByDescending { it.score }.take(maxTracks)
        tracks.clear()
        tracks += pruned
        return tracks.toList()
    }

    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) {
            return 0f
        }

        val areaA = (a.right - a.left).coerceAtLeast(0f) * (a.bottom - a.top).coerceAtLeast(0f)
        val areaB = (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }
}
