package com.meta.wearable.dat.externalsampleapps.cameraaccess.priority

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlin.math.max

class SceneCueScheduler(
    private val context: Context,
    private val minInterCueGapMs: Long = 60L,
) {
    companion object {
        private const val TAG = "SceneCueScheduler"
    }

    private val fallbackDurationsMs =
        mapOf(
            "audio/unlock.wav" to 700L,
            "audio/shortz.wav" to 500L,
            "audio/punch.wav" to 550L,
            "audio/retro.wav" to 800L,
            "audio/bling.wav" to 450L,
            "audio/scifi.wav" to 650L,
        )

    private val assetDurationCache = mutableMapOf<String, Long>()

    init {
        fallbackDurationsMs.keys.forEach { path ->
            assetDurationCache[path] = resolveDurationMs(path)
        }
    }

    fun sceneWindowMs(sceneRefreshRateHz: Float): Long {
        val clamped = sceneRefreshRateHz.coerceIn(0.3f, 3.0f)
        return (1000f / clamped).toLong().coerceAtLeast(1L)
    }

    fun buildScenePlan(
        rankedCandidates: List<RankedObjectCandidate>,
        sceneRefreshRateHz: Float,
    ): SceneCuePlan {
        if (rankedCandidates.isEmpty()) {
            return SceneCuePlan(
                entries = emptyList(),
                sceneWindowMs = sceneWindowMs(sceneRefreshRateHz),
                maxCommunicableObjects = 0,
                interCueGapMs = minInterCueGapMs,
            )
        }

        val windowMs = sceneWindowMs(sceneRefreshRateHz)
        val entries = mutableListOf<SceneCueEntry>()
        var consumedMs = 0L

        for (candidate in rankedCandidates) {
            val durationMs = durationMsFor(candidate.soundAssetPath)
            val extra = if (entries.isEmpty()) durationMs else durationMs + minInterCueGapMs
            if (consumedMs + extra > windowMs) {
                continue
            }
            entries += SceneCueEntry(candidate = candidate, durationMs = durationMs)
            consumedMs += extra
        }

        if (entries.isEmpty()) {
            val first = rankedCandidates.first()
            entries += SceneCueEntry(candidate = first, durationMs = durationMsFor(first.soundAssetPath))
        }

        return SceneCuePlan(
            entries = entries,
            sceneWindowMs = windowMs,
            maxCommunicableObjects = entries.size,
            interCueGapMs = minInterCueGapMs,
        )
    }

    fun durationMsFor(soundAssetPath: String): Long {
        return assetDurationCache[soundAssetPath] ?: resolveDurationMs(soundAssetPath)
    }

    private fun resolveDurationMs(soundAssetPath: String): Long {
        val fallback = fallbackDurationsMs[soundAssetPath] ?: 600L
        return try {
            context.assets.openFd(soundAssetPath).use { afd ->
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                val duration =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                retriever.release()
                val resolved = max(duration ?: fallback, 50L)
                Log.d(TAG, "audioDuration path=$soundAssetPath durationMs=$resolved")
                assetDurationCache[soundAssetPath] = resolved
                resolved
            }
        } catch (t: Throwable) {
            Log.w(TAG, "audioDuration fallback path=$soundAssetPath durationMs=$fallback", t)
            assetDurationCache[soundAssetPath] = fallback
            fallback
        }
    }
}

data class SceneCueEntry(
    val candidate: RankedObjectCandidate,
    val durationMs: Long,
)

data class SceneCuePlan(
    val entries: List<SceneCueEntry>,
    val sceneWindowMs: Long,
    val maxCommunicableObjects: Int,
    val interCueGapMs: Long,
)
