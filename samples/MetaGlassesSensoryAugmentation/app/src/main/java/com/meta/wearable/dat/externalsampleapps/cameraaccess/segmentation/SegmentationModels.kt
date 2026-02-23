package com.meta.wearable.dat.externalsampleapps.cameraaccess.segmentation

data class SegmentationTensorShape(
    val width: Int,
    val height: Int,
    val classes: Int,
)

data class ClassMask(
    val label: String,
    val width: Int,
    val height: Int,
    val data: BooleanArray,
) {
    val areaPixels: Int
        get() = data.count { it }
}

data class SegmentationOutput(
    val width: Int,
    val height: Int,
    val classMap: IntArray,
    val confidenceMap: FloatArray?,
    val perClassMasks: Map<String, ClassMask>,
    val idToLabel: Map<Int, String>,
) {
    companion object {
        fun empty(width: Int, height: Int): SegmentationOutput {
            return SegmentationOutput(
                width = width,
                height = height,
                classMap = IntArray(width * height),
                confidenceMap = null,
                perClassMasks = emptyMap(),
                idToLabel = emptyMap(),
            )
        }
    }
}
