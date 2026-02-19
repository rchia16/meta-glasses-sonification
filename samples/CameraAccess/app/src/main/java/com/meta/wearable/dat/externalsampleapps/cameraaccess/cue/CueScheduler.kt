package com.meta.wearable.dat.externalsampleapps.cameraaccess.cue

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class CueScheduler {
    private val _events = MutableSharedFlow<CueEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<CueEvent> = _events.asSharedFlow()

    fun requestNorthPing() {
        _events.tryEmit(
            CueEvent(
                type = CueType.NORTH,
                target = "north",
                message = "North ping requested",
            )
        )
    }

    fun requestLandmarkTracked(name: String) {
        _events.tryEmit(
            CueEvent(
                type = CueType.LANDMARK,
                target = name,
                message = "Landmark tracked: $name",
            )
        )
    }

    fun requestLandmarkForget(name: String) {
        _events.tryEmit(
            CueEvent(
                type = CueType.LANDMARK,
                target = name,
                message = "Landmark forgotten: $name",
            )
        )
    }

    fun requestLandmarkCue(
        name: String,
        relativeAzimuthDeg: Float,
        distanceMeters: Float,
    ) {
        _events.tryEmit(
            CueEvent(
                type = CueType.LANDMARK,
                target = name,
                elevationDeg = 0f,
                message =
                    "Landmark cue name=$name az=${"%.1f".format(relativeAzimuthDeg)} distance=${"%.1f".format(distanceMeters)}m",
            )
        )
    }

    fun requestObjectCue(
        trackId: Long,
        label: String,
        soundAssetPath: String,
        rank: Float,
        elevationDeg: Float,
    ) {
        _events.tryEmit(
            CueEvent(
                type = CueType.OBJECT,
                target = "$trackId:$label",
                soundAssetPath = soundAssetPath,
                objectLabel = label,
                elevationDeg = elevationDeg,
                message =
                    "Object cue track=$trackId label=$label rank=${"%.3f".format(rank)} elev=${"%.1f".format(elevationDeg)} sound=$soundAssetPath",
            )
        )
    }
}

enum class CueType {
    OBJECT,
    LANDMARK,
    NORTH,
}

data class CueEvent(
    val type: CueType,
    val target: String,
    val message: String,
    val soundAssetPath: String? = null,
    val objectLabel: String? = null,
    val elevationDeg: Float? = null,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)
