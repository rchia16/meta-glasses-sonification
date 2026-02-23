package com.meta.wearable.dat.externalsampleapps.cameraaccess.segmentation

object IndoorLabelMap {
    // Placeholder ids for scaffolding. Replace with model-specific ids once TFLite export is finalized.
    private val idsByLabel =
        linkedMapOf(
            "wall" to 1,
            "floor" to 2,
            "ceiling" to 3,
            "door" to 4,
            "windowpane" to 5,
            "table" to 6,
            "chair" to 7,
            "sofa" to 8,
        )

    val labels: Set<String> = idsByLabel.keys
    val idToLabel: Map<Int, String> = idsByLabel.entries.associate { (k, v) -> v to k }

    fun idForLabel(label: String): Int? = idsByLabel[label.trim().lowercase()]
}
