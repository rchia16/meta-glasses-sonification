package com.meta.wearable.dat.externalsampleapps.cameraaccess.detector

private val allowedClassAliases =
    mapOf(
        "person" to setOf("person"),
        "table" to setOf("table", "dining table"),
        "chair" to setOf("chair"),
        "door" to setOf("door"),
        "cup" to setOf("cup"),
        "phone" to setOf("phone", "cell phone", "mobile phone"),
    )

fun filterToNavigationClasses(detections: List<DetectedObject>): List<DetectedObject> {
    return detections.filter { detection ->
        val label = detection.label.trim().lowercase()
        allowedClassAliases.values.any { aliases -> label in aliases }
    }
}
