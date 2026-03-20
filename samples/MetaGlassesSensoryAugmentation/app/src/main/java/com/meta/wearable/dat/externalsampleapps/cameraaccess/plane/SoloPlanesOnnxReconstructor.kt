package com.meta.wearable.dat.externalsampleapps.cameraaccess.plane

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.RawCameraFrame
import com.meta.wearable.dat.externalsampleapps.cameraaccess.priority.ObjectCueSoundMap
import com.meta.wearable.dat.externalsampleapps.cameraaccess.scene.SceneCueCandidate
import java.nio.FloatBuffer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

class SoloPlanesOnnxReconstructor private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val contract: SoloPlanesOutputContract,
) : AutoCloseable {
    companion object {
        private const val TAG = "SoloPlanesOnnx"
        private val RGB_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val RGB_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        fun createFromAssets(
            context: Context,
            modelAssetPath: String = "models/soloplanes_saved_model/solopmv_mobile.onnx",
            contract: SoloPlanesOutputContract = SoloPlanesOutputContract(),
        ): SoloPlanesOnnxReconstructor? {
            return try {
                val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
                val environment = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }
                val session = environment.createSession(modelBytes, sessionOptions)
                Log.i(TAG, "Loaded SOLOPlanes ONNX model from assets/$modelAssetPath")
                SoloPlanesOnnxReconstructor(
                    environment = environment,
                    session = session,
                    contract = contract,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "SOLOPlanes ONNX model unavailable at assets/$modelAssetPath", t)
                null
            }
        }
    }

    fun reconstruct(frame: RawCameraFrame): PlaneReconstructionResult {
        val inputTensor = buildInputTensor(frame)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(inputTensor), longArrayOf(1, 3, contract.inputHeight.toLong(), contract.inputWidth.toLong())).use { imageTensor ->
            session.run(mapOf("image" to imageTensor)).use { outputs ->
                val raw =
                    SoloPlanesHeadOutputs(
                        planeParams = outputFloatArray(outputs, "plane_params"),
                        planeParamWidth = contract.planeParamWidth,
                        planeParamHeight = contract.planeParamHeight,
                        planeFeature = outputFloatArray(outputs, "plane_feat"),
                        maskFeature = outputFloatArray(outputs, "mask_feat"),
                        categoryLevel0 = outputFloatArray(outputs, "cate_l0"),
                        categoryLevel1 = outputFloatArray(outputs, "cate_l1"),
                        categoryLevel2 = outputFloatArray(outputs, "cate_l2"),
                        kernelLevel0 = outputFloatArray(outputs, "kernel_l0"),
                        kernelLevel1 = outputFloatArray(outputs, "kernel_l1"),
                        kernelLevel2 = outputFloatArray(outputs, "kernel_l2"),
                    )
                val decodedPlanes = SoloPlanesPostProcessor.decode(raw, contract)
                return PlaneReconstructionResult(
                    width = frame.width,
                    height = frame.height,
                    planes = decodedPlanes,
                    dominantPlane = dominantPlaneFrom(decodedPlanes),
                    raw = raw,
                )
            }
        }
    }

    fun buildSceneCueCandidates(result: PlaneReconstructionResult): List<SceneCueCandidate> {
        val labeledCandidates =
            result.planes
            .asSequence()
            .filter { it.semanticLabel != null }
            .map { plane ->
                val relativeAzimuth = (plane.centroidXNorm - 0.5f) * 58f
                val relativeElevation = (0.5f - plane.centroidYNorm) * 45f
                val label = plane.semanticLabel ?: "wall"
                val classPriority =
                    when (label) {
                        "door", "windowpane" -> 0.90f
                        "table", "chair", "sofa" -> 0.80f
                        "wall", "floor", "ceiling" -> 0.65f
                        else -> 0.40f
                    }
                val rank = ((plane.areaRatio.coerceIn(0f, 0.35f) / 0.35f) * 0.45f) + (plane.score * 0.30f) + (classPriority * 0.25f)
                SceneCueCandidate(
                    segmentId = plane.id,
                    label = label,
                    soundAssetPath = ObjectCueSoundMap.soundForLabel(label),
                    rank = rank.coerceIn(0f, 1f),
                    relativeAzimuthDeg = relativeAzimuth,
                    relativeElevationDeg = relativeElevation.coerceIn(-45f, 45f),
                )
            }.sortedByDescending { it.rank }
            .toList()
        if (labeledCandidates.isNotEmpty()) {
            return labeledCandidates
        }

        val dominantPlane = result.dominantPlane ?: return emptyList()
        return listOf(
            SceneCueCandidate(
                segmentId = dominantPlane.id,
                label = "wall",
                soundAssetPath = ObjectCueSoundMap.soundForLabel("wall"),
                rank = (dominantPlane.score + (dominantPlane.areaRatio * 0.5f)).coerceIn(0f, 1f),
                relativeAzimuthDeg = (dominantPlane.centroidXNorm - 0.5f) * 58f,
                relativeElevationDeg = ((0.5f - dominantPlane.centroidYNorm) * 45f).coerceIn(-45f, 45f),
            )
        )
    }

    private fun dominantPlaneFrom(planes: List<PlaneInstance>): PlaneInstance? {
        return planes.maxByOrNull { plane ->
            val sceneBonus =
                when (plane.semanticLabel) {
                    "wall", "floor", "ceiling" -> 0.20f
                    "door", "windowpane" -> 0.15f
                    "table", "chair", "sofa" -> 0.10f
                    else -> 0f
                }
            plane.score + (plane.areaRatio * 0.75f) + sceneBonus
        }
    }

    private fun buildInputTensor(frame: RawCameraFrame): FloatArray {
        val dstWidth = contract.inputWidth
        val dstHeight = contract.inputHeight
        val output = FloatArray(3 * dstWidth * dstHeight)

        val srcW = frame.width
        val srcH = frame.height
        val ySize = srcW * srcH
        val uvW = srcW / 2
        val uvH = srcH / 2
        val uOffset = ySize
        val vOffset = ySize + (uvW * uvH)

        for (y in 0 until dstHeight) {
            val srcY = (y * srcH) / dstHeight
            for (x in 0 until dstWidth) {
                val srcX = (x * srcW) / dstWidth
                val yIndex = srcY * srcW + srcX
                val uvIndex = (srcY / 2) * uvW + (srcX / 2)

                val yy = frame.i420[yIndex].toInt() and 0xFF
                val uu = frame.i420[uOffset + uvIndex].toInt() and 0xFF
                val vv = frame.i420[vOffset + uvIndex].toInt() and 0xFF

                val yF = maxOf(0f, yy.toFloat() - 16f)
                val uF = uu.toFloat() - 128f
                val vF = vv.toFloat() - 128f

                val r = (((1.164f * yF) + (1.596f * vF)).coerceIn(0f, 255f) / 255f - RGB_MEAN[0]) / RGB_STD[0]
                val g = (((1.164f * yF) - (0.813f * vF) - (0.391f * uF)).coerceIn(0f, 255f) / 255f - RGB_MEAN[1]) / RGB_STD[1]
                val b = (((1.164f * yF) + (2.018f * uF)).coerceIn(0f, 255f) / 255f - RGB_MEAN[2]) / RGB_STD[2]

                val dstIndex = y * dstWidth + x
                output[dstIndex] = r
                output[dstWidth * dstHeight + dstIndex] = g
                output[2 * dstWidth * dstHeight + dstIndex] = b
            }
        }

        return output
    }

    private fun outputFloatArray(outputs: OrtSession.Result, name: String): FloatArray {
        val onnxValue = outputs.get(name).orElse(null)
            ?: error("Missing ONNX output tensor: $name")
        val value = onnxValue.value
        return when (value) {
            is Array<*> -> flattenToFloatArray(value)
            is FloatArray -> value
            else -> error("Unsupported ONNX output type for $name: ${value::class.java.name}")
        }
    }

    private fun flattenToFloatArray(value: Any): FloatArray {
        val out = ArrayList<Float>()
        fun visit(node: Any?) {
            when (node) {
                null -> Unit
                is FloatArray -> node.forEach { out += it }
                is Array<*> -> node.forEach { visit(it) }
                else -> error("Unsupported nested tensor node: ${node::class.java.name}")
            }
        }
        visit(value)
        return out.toFloatArray()
    }

    override fun close() {
        session.close()
        environment.close()
    }
}
