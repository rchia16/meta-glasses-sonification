package com.meta.wearable.dat.externalsampleapps.cameraaccess.plane

data class SoloPlanesOutputContract(
    val inputWidth: Int = 640,
    val inputHeight: Int = 480,
    val planeParamWidth: Int = 160,
    val planeParamHeight: Int = 120,
    val maskFeatureChannels: Int = 128,
    val planeFeatureChannels: Int = 64,
    val numClasses: Int = 41,
    val soloGridSizes: List<Int> = listOf(36, 24, 16),
    val soloStrides: List<Int> = listOf(8, 32, 32),
    val kernelChannels: Int = 128,
)

data class SoloPlanesHeadOutputs(
    val planeParams: FloatArray,
    val planeParamWidth: Int,
    val planeParamHeight: Int,
    val planeFeature: FloatArray,
    val maskFeature: FloatArray,
    val categoryLevel0: FloatArray,
    val categoryLevel1: FloatArray,
    val categoryLevel2: FloatArray,
    val kernelLevel0: FloatArray,
    val kernelLevel1: FloatArray,
    val kernelLevel2: FloatArray,
)

data class PlaneInstance(
    val id: String,
    val score: Float,
    val semanticClassId: Int,
    val semanticLabel: String?,
    val normalX: Float,
    val normalY: Float,
    val normalZ: Float,
    val offset: Float,
    val centroidXNorm: Float,
    val centroidYNorm: Float,
    val widthNorm: Float,
    val heightNorm: Float,
    val areaRatio: Float,
    val maskWidth: Int,
    val maskHeight: Int,
    val mask: BooleanArray,
)

data class PlaneReconstructionResult(
    val width: Int,
    val height: Int,
    val planes: List<PlaneInstance>,
    val dominantPlane: PlaneInstance?,
    val raw: SoloPlanesHeadOutputs,
) {
    companion object {
        fun empty(
            width: Int,
            height: Int,
            raw: SoloPlanesHeadOutputs? = null,
        ): PlaneReconstructionResult {
            return PlaneReconstructionResult(
                width = width,
                height = height,
                planes = emptyList(),
                dominantPlane = null,
                raw =
                    raw ?: SoloPlanesHeadOutputs(
                        planeParams = FloatArray(0),
                        planeParamWidth = 0,
                        planeParamHeight = 0,
                        planeFeature = FloatArray(0),
                        maskFeature = FloatArray(0),
                        categoryLevel0 = FloatArray(0),
                        categoryLevel1 = FloatArray(0),
                        categoryLevel2 = FloatArray(0),
                        kernelLevel0 = FloatArray(0),
                        kernelLevel1 = FloatArray(0),
                        kernelLevel2 = FloatArray(0),
                    ),
            )
        }
    }
}
