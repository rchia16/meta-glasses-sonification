package com.meta.wearable.dat.externalsampleapps.cameraaccess.detector

data class RawCameraFrame(
    val width: Int,
    val height: Int,
    val i420: ByteArray,
)

data class DetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class DetectedObject(
    val label: String,
    val score: Float,
    val box: DetectionBox,
)
