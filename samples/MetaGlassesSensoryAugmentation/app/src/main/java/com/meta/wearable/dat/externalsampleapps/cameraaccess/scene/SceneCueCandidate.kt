package com.meta.wearable.dat.externalsampleapps.cameraaccess.scene

data class SceneCueCandidate(
    val segmentId: String,
    val label: String,
    val soundAssetPath: String,
    val rank: Float,
    val relativeAzimuthDeg: Float,
    val relativeElevationDeg: Float,
)
