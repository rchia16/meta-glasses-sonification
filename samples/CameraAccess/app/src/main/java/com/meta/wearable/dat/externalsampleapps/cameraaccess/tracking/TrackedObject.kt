package com.meta.wearable.dat.externalsampleapps.cameraaccess.tracking

import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.DetectionBox

data class TrackedObject(
    val trackId: Long,
    val label: String,
    val score: Float,
    val box: DetectionBox,
    val lastSeenAtMs: Long,
)
