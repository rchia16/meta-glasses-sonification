package com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LandmarkRepository(
    private val dao: LandmarkDao,
) {
    fun observeLandmarks(): Flow<List<Landmark>> {
        return dao.observeAll().map { entities ->
            entities.map { entity ->
                Landmark(
                    name = entity.name,
                    createdAtEpochMs = entity.createdAtEpochMs,
                    latitude = entity.latitude,
                    longitude = entity.longitude,
                    accuracyMeters = entity.accuracyMeters,
                )
            }
        }
    }

    suspend fun save(
        rawName: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): LandmarkSaveResult {
        val cleaned = rawName.trim()
        if (cleaned.isEmpty()) return LandmarkSaveResult.InvalidName
        val normalized = cleaned.lowercase()

        dao.findByNormalizedName(normalized)?.let {
            return LandmarkSaveResult.AlreadyTracked(it.name)
        }

        val insertedId =
            dao.insert(
                LandmarkEntity(
                    name = cleaned,
                    nameNormalized = normalized,
                    createdAtEpochMs = nowEpochMs,
                    latitude = latitude,
                    longitude = longitude,
                    accuracyMeters = accuracyMeters,
                )
            )
        return if (insertedId > 0L) {
            LandmarkSaveResult.Tracked(cleaned)
        } else {
            LandmarkSaveResult.AlreadyTracked(cleaned)
        }
    }

    suspend fun delete(rawName: String): LandmarkDeleteResult {
        val cleaned = rawName.trim()
        if (cleaned.isEmpty()) return LandmarkDeleteResult.InvalidName
        val removed = dao.deleteByNormalizedName(cleaned.lowercase())
        return if (removed > 0) {
            LandmarkDeleteResult.Forgot(cleaned)
        } else {
            LandmarkDeleteResult.NotFound(cleaned)
        }
    }
}

sealed interface LandmarkSaveResult {
    data class Tracked(val name: String) : LandmarkSaveResult
    data class AlreadyTracked(val name: String) : LandmarkSaveResult
    data object InvalidName : LandmarkSaveResult
}

sealed interface LandmarkDeleteResult {
    data class Forgot(val name: String) : LandmarkDeleteResult
    data class NotFound(val name: String) : LandmarkDeleteResult
    data object InvalidName : LandmarkDeleteResult
}

