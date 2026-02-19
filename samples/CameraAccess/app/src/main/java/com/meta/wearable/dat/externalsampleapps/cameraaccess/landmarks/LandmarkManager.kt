package com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks

import kotlinx.coroutines.flow.Flow

class LandmarkManager(
    private val repository: LandmarkRepository,
    private val locationProvider: LandmarkLocationProvider,
) {
    val landmarks: Flow<List<Landmark>> = repository.observeLandmarks()

    suspend fun currentLocation(): LandmarkLocation? = locationProvider.currentLocation()

    suspend fun track(name: String): LandmarkTrackResult {
        val location = locationProvider.currentLocation() ?: return LandmarkTrackResult.LocationUnavailable
        return when (
            val result =
                repository.save(
                    rawName = name,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracyMeters,
                )
        ) {
            is LandmarkSaveResult.Tracked -> LandmarkTrackResult.Tracked(result.name)
            is LandmarkSaveResult.AlreadyTracked -> LandmarkTrackResult.AlreadyTracked(result.name)
            LandmarkSaveResult.InvalidName -> LandmarkTrackResult.InvalidName
        }
    }

    suspend fun saveManual(name: String): LandmarkTrackResult {
        return track(name)
    }

    suspend fun forget(name: String): LandmarkForgetResult {
        return when (val result = repository.delete(name)) {
            is LandmarkDeleteResult.Forgot -> LandmarkForgetResult.Forgot(result.name)
            is LandmarkDeleteResult.NotFound -> LandmarkForgetResult.NotFound(result.name)
            LandmarkDeleteResult.InvalidName -> LandmarkForgetResult.InvalidName
        }
    }
}

sealed interface LandmarkTrackResult {
    data class Tracked(val name: String) : LandmarkTrackResult

    data class AlreadyTracked(val name: String) : LandmarkTrackResult

    data object LocationUnavailable : LandmarkTrackResult

    data object InvalidName : LandmarkTrackResult
}

sealed interface LandmarkForgetResult {
    data class Forgot(val name: String) : LandmarkForgetResult

    data class NotFound(val name: String) : LandmarkForgetResult

    data object InvalidName : LandmarkForgetResult
}
