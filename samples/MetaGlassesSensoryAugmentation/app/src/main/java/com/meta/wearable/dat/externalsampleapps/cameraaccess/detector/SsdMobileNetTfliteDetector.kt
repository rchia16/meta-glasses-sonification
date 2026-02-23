package com.meta.wearable.dat.externalsampleapps.cameraaccess.detector

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

class SsdMobileNetTfliteDetector private constructor(
    private val interpreter: Interpreter,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val inputDataType: DataType,
) : AutoCloseable {

    companion object {
        private const val TAG = "SsdMobileNetDetector"

        fun createFromAssets(
            context: Context,
            modelAssetPath: String = "models/ssd_mobilenet.tflite",
        ): SsdMobileNetTfliteDetector? {
            return try {
                val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
                val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(modelBytes)
                    rewind()
                }
                val options = Interpreter.Options().apply { setNumThreads(4) }
                val interpreter = Interpreter(modelBuffer, options)
                val inputShape = interpreter.getInputTensor(0).shape() // [1,h,w,3]
                val inputHeight = inputShape[1]
                val inputWidth = inputShape[2]
                val inputDataType = interpreter.getInputTensor(0).dataType()
                Log.i(
                    TAG,
                    "Loaded SSD MobileNet model=$modelAssetPath input=${inputWidth}x$inputHeight type=$inputDataType",
                )
                SsdMobileNetTfliteDetector(interpreter, inputWidth, inputHeight, inputDataType)
            } catch (e: Exception) {
                Log.w(TAG, "Detector model unavailable at assets/$modelAssetPath", e)
                null
            }
        }
    }

    fun detect(
        frame: RawCameraFrame,
        scoreThreshold: Float = 0.45f,
        maxDetections: Int = 5,
    ): List<DetectedObject> {
        val bytesPerChannel =
            when (inputDataType) {
                DataType.FLOAT32 -> 4
                DataType.UINT8, DataType.INT8 -> 1
                else -> {
                    Log.w(TAG, "Unsupported input tensor dataType=$inputDataType")
                    return emptyList()
                }
            }
        val input = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * bytesPerChannel)
        input.order(ByteOrder.nativeOrder())
        fillInputFromI420(input, frame)

        val outputTensorShape = interpreter.getOutputTensor(0).shape()
        val modelDetectionCount = outputTensorShape.getOrNull(1)?.coerceAtLeast(1) ?: 10
        val outputLocations = Array(1) { Array(modelDetectionCount) { FloatArray(4) } }
        val outputClasses = Array(1) { FloatArray(modelDetectionCount) }
        val outputScores = Array(1) { FloatArray(modelDetectionCount) }
        val numDetections = FloatArray(1)
        val outputs = hashMapOf<Int, Any>(
            0 to outputLocations,
            1 to outputClasses,
            2 to outputScores,
            3 to numDetections,
        )
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val count = min(maxDetections, min(numDetections[0].toInt(), modelDetectionCount))
        val detected = ArrayList<DetectedObject>(count)
        for (i in 0 until count) {
            val score = outputScores[0][i]
            if (score < scoreThreshold) {
                continue
            }
            val classIndex = outputClasses[0][i].toInt()
            val label = labelFor(classIndex)
            val box = outputLocations[0][i]
            detected +=
                DetectedObject(
                    label = label,
                    score = score,
                    box =
                        DetectionBox(
                            left = box[1].coerceIn(0f, 1f),
                            top = box[0].coerceIn(0f, 1f),
                            right = box[3].coerceIn(0f, 1f),
                            bottom = box[2].coerceIn(0f, 1f),
                        ),
                )
        }
        return detected
    }

    private fun labelFor(classIndex: Int): String {
        if (classIndex in cocoLabels.indices) {
            return cocoLabels[classIndex]
        }
        val shifted = classIndex - 1 // Some SSD exports are 1-based.
        if (shifted in cocoLabels.indices) {
            return cocoLabels[shifted]
        }
        return "class_$classIndex"
    }

    private fun fillInputFromI420(input: ByteBuffer, frame: RawCameraFrame) {
        val srcW = frame.width
        val srcH = frame.height
        val ySize = srcW * srcH
        val uvW = srcW / 2
        val uvH = srcH / 2
        val uOffset = ySize
        val vOffset = ySize + (uvW * uvH)

        for (y in 0 until inputHeight) {
            val srcY = (y * srcH) / inputHeight
            for (x in 0 until inputWidth) {
                val srcX = (x * srcW) / inputWidth
                val yIndex = srcY * srcW + srcX
                val uvIndex = (srcY / 2) * uvW + (srcX / 2)

                val yy = frame.i420[yIndex].toInt() and 0xFF
                val uu = frame.i420[uOffset + uvIndex].toInt() and 0xFF
                val vv = frame.i420[vOffset + uvIndex].toInt() and 0xFF

                val yF = max(0f, yy.toFloat() - 16f)
                val uF = uu.toFloat() - 128f
                val vF = vv.toFloat() - 128f

                val r255 = ((1.164f * yF) + (1.596f * vF)).coerceIn(0f, 255f)
                val g255 = ((1.164f * yF) - (0.813f * vF) - (0.391f * uF)).coerceIn(0f, 255f)
                val b255 = ((1.164f * yF) + (2.018f * uF)).coerceIn(0f, 255f)
                when (inputDataType) {
                    DataType.FLOAT32 -> {
                        input.putFloat(r255 / 255f)
                        input.putFloat(g255 / 255f)
                        input.putFloat(b255 / 255f)
                    }
                    DataType.UINT8 -> {
                        input.put(r255.roundToInt().toByte())
                        input.put(g255.roundToInt().toByte())
                        input.put(b255.roundToInt().toByte())
                    }
                    DataType.INT8 -> {
                        val q = interpreter.getInputTensor(0).quantizationParams()
                        val scale = if (q.scale > 0f) q.scale else 1f
                        val zeroPoint = q.zeroPoint
                        val rQ = ((r255 / 255f) / scale + zeroPoint).roundToInt().coerceIn(-128, 127)
                        val gQ = ((g255 / 255f) / scale + zeroPoint).roundToInt().coerceIn(-128, 127)
                        val bQ = ((b255 / 255f) / scale + zeroPoint).roundToInt().coerceIn(-128, 127)
                        input.put(rQ.toByte())
                        input.put(gQ.toByte())
                        input.put(bQ.toByte())
                    }
                    else -> return
                }
            }
        }
        input.rewind()
    }

    override fun close() {
        interpreter.close()
    }
}
