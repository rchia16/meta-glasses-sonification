package com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks

data class Landmark(
    val name: String,
    val createdAtEpochMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
)
