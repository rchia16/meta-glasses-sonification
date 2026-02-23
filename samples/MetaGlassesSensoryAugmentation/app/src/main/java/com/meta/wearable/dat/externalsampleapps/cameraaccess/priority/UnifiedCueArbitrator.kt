package com.meta.wearable.dat.externalsampleapps.cameraaccess.priority

import com.meta.wearable.dat.externalsampleapps.cameraaccess.scene.SceneCueCandidate

class UnifiedCueArbitrator {
    fun mergeCandidates(
        objectCandidates: List<RankedObjectCandidate>,
        sceneCandidates: List<SceneCueCandidate>,
    ): List<UnifiedCueCandidate> {
        if (objectCandidates.isEmpty() && sceneCandidates.isEmpty()) {
            return emptyList()
        }

        val objectQueue =
            ArrayDeque(
                objectCandidates
                    .sortedByDescending { it.rank }
                    .map { candidate ->
                        UnifiedCueCandidate(
                            source = CueSource.OBJECT,
                            key = "obj:${candidate.trackId}",
                            label = candidate.label,
                            soundAssetPath = candidate.soundAssetPath,
                            rank = candidate.rank,
                            trackId = candidate.trackId,
                            relativeAzimuthDeg = null,
                            relativeElevationDeg = null,
                        )
                    },
            )
        val sceneQueue =
            ArrayDeque(
                sceneCandidates
                    .sortedByDescending { it.rank }
                    .map { candidate ->
                        UnifiedCueCandidate(
                            source = CueSource.SEGMENTATION,
                            key = "seg:${candidate.segmentId}",
                            label = candidate.label,
                            soundAssetPath = candidate.soundAssetPath,
                            rank = candidate.rank,
                            trackId = null,
                            relativeAzimuthDeg = candidate.relativeAzimuthDeg,
                            relativeElevationDeg = candidate.relativeElevationDeg,
                        )
                    },
            )

        val merged = mutableListOf<UnifiedCueCandidate>()
        var lastSource: CueSource? = null

        while (objectQueue.isNotEmpty() || sceneQueue.isNotEmpty()) {
            val preferred =
                when {
                    objectQueue.isEmpty() -> CueSource.SEGMENTATION
                    sceneQueue.isEmpty() -> CueSource.OBJECT
                    lastSource == CueSource.OBJECT -> CueSource.SEGMENTATION
                    lastSource == CueSource.SEGMENTATION -> CueSource.OBJECT
                    else -> {
                        if (objectQueue.first().rank >= sceneQueue.first().rank) CueSource.OBJECT else CueSource.SEGMENTATION
                    }
                }

            val next =
                when (preferred) {
                    CueSource.OBJECT -> if (objectQueue.isNotEmpty()) objectQueue.removeFirst() else sceneQueue.removeFirst()
                    CueSource.SEGMENTATION -> if (sceneQueue.isNotEmpty()) sceneQueue.removeFirst() else objectQueue.removeFirst()
                }
            merged += next
            lastSource = next.source
        }

        return merged
    }
}

enum class CueSource {
    OBJECT,
    SEGMENTATION,
}

data class UnifiedCueCandidate(
    val source: CueSource,
    val key: String,
    val label: String,
    val soundAssetPath: String,
    val rank: Float,
    val trackId: Long?,
    val relativeAzimuthDeg: Float?,
    val relativeElevationDeg: Float?,
)
