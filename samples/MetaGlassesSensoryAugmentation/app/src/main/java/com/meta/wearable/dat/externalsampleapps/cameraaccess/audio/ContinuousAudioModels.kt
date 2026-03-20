package com.meta.wearable.dat.externalsampleapps.cameraaccess.audio

enum class ContinuousChannelId {
    OBJECT,
    NORTH,
}

data class ContinuousAudioChannelState(
    val channelId: ContinuousChannelId,
    val enabled: Boolean,
    val soundAssetPath: String,
    val objectLabel: String? = null,
    val azimuthDeg: Float = 0f,
    val elevationDeg: Float = 0f,
    val gain: Float = 0f,
    val tiltEq: Float = 0f,
    val beatRateHz: Float = 0f,
)
