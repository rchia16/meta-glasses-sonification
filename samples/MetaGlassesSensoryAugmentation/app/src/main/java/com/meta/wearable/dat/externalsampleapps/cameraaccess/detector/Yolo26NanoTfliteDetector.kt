package com.meta.wearable.dat.externalsampleapps.cameraaccess.detector

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

class Yolo26NanoTfliteDetector private constructor(
    private val interpreter: Interpreter,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val inputDataType: DataType,
) : AutoCloseable {

    companion object {
        private const val TAG = "Yolo26NanoDetector"

        fun createFromAssets(
            context: Context,
            modelAssetPath: String = "models/yolo26n_saved_model/yolo26n_float16.tflite",
        ): Yolo26NanoTfliteDetector? {
            return try {
                val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
                val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(modelBytes)
                    rewind()
                }
                val options = Interpreter.Options().apply { setNumThreads(4) }
                val interpreter = Interpreter(modelBuffer, options)
                val inputTensor = interpreter.getInputTensor(0)
                val inputShape = inputTensor.shape() // [1, h, w, 3]
                val outputShapeSummary =
                    (0 until interpreter.outputTensorCount)
                        .joinToString(separator = "; ") { idx ->
                            val tensor = interpreter.getOutputTensor(idx)
                            "out$idx=${tensor.shape().contentToString()}:${tensor.dataType()}"
                        }
                Log.i(
                    TAG,
                    "Loaded model assets/$modelAssetPath bytes=${modelBytes.size} input=${inputShape.contentToString()}:${inputTensor.dataType()} $outputShapeSummary",
                )
                Yolo26NanoTfliteDetector(
                    interpreter = interpreter,
                    inputWidth = inputShape[2],
                    inputHeight = inputShape[1],
                    inputDataType = inputTensor.dataType(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Detector model unavailable at assets/$modelAssetPath", e)
                null
            }
        }
    }

    fun detect(
        frame: RawCameraFrame,
        scoreThreshold: Float = 0.35f,
        maxDetections: Int = 5,
        iouThreshold: Float = 0.45f,
    ): List<DetectedObject> {
        val input = createInputBuffer()
        fillInputFromI420(input, frame)

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        if (outputShape.size < 3) {
            Log.w(TAG, "Unexpected YOLO output shape: ${outputShape.contentToString()}")
            return emptyList()
        }

        val predictions = when {
            outputShape[0] == 1 && outputShape.size == 3 && outputShape[2] > outputShape[1] -> {
                // [1, attrs, boxes]
                val attrs = outputShape[1]
                val boxes = outputShape[2]
                val output = Array(1) { Array(attrs) { FloatArray(boxes) } }
                interpreter.run(input, output)
                if (attrs in 6..8) {
                    parseNmsOutputAttrsFirst(output[0], scoreThreshold)
                } else {
                    parseOutputAttrsFirst(output[0], scoreThreshold)
                }
            }

            outputShape[0] == 1 && outputShape.size == 3 -> {
                // [1, boxes, attrs]
                val boxes = outputShape[1]
                val attrs = outputShape[2]
                val output = Array(1) { Array(boxes) { FloatArray(attrs) } }
                interpreter.run(input, output)
                if (attrs in 6..8) {
                    parseNmsOutputBoxesFirst(output[0], scoreThreshold)
                } else {
                    parseOutputBoxesFirst(output[0], scoreThreshold)
                }
            }

            else -> {
                Log.w(TAG, "Unsupported YOLO output shape: ${outputShape.contentToString()}")
                emptyList()
            }
        }

        return nonMaxSuppression(predictions, iouThreshold, maxDetections)
    }

    private fun createInputBuffer(): ByteBuffer {
        val bytesPerChannel = if (inputDataType == DataType.UINT8) 1 else 4
        val input = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * bytesPerChannel)
        input.order(ByteOrder.nativeOrder())
        return input
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

                val r = ((1.164f * yF) + (1.596f * vF)).coerceIn(0f, 255f)
                val g = ((1.164f * yF) - (0.813f * vF) - (0.391f * uF)).coerceIn(0f, 255f)
                val b = ((1.164f * yF) + (2.018f * uF)).coerceIn(0f, 255f)

                if (inputDataType == DataType.UINT8) {
                    input.put(r.toInt().toByte())
                    input.put(g.toInt().toByte())
                    input.put(b.toInt().toByte())
                } else {
                    input.putFloat(r / 255f)
                    input.putFloat(g / 255f)
                    input.putFloat(b / 255f)
                }
            }
        }
        input.rewind()
    }

    private fun parseOutputBoxesFirst(
        output: Array<FloatArray>,
        scoreThreshold: Float,
    ): List<DetectedObject> {
        val results = ArrayList<DetectedObject>(output.size)
        for (prediction in output) {
            parsePrediction(prediction, scoreThreshold)?.let { results += it }
        }
        return results
    }

    private fun parseOutputAttrsFirst(
        output: Array<FloatArray>,
        scoreThreshold: Float,
    ): List<DetectedObject> {
        val attrs = output.size
        val boxes = output.firstOrNull()?.size ?: 0
        val results = ArrayList<DetectedObject>(boxes)
        for (boxIndex in 0 until boxes) {
            val prediction = FloatArray(attrs) { attrIndex -> output[attrIndex][boxIndex] }
            parsePrediction(prediction, scoreThreshold)?.let { results += it }
        }
        return results
    }

    private fun parsePrediction(
        prediction: FloatArray,
        scoreThreshold: Float,
    ): DetectedObject? {
        if (prediction.size < 6) {
            return null
        }

        val attrs = prediction.size
        val classStart = if ((attrs - 4) == cocoLabels.size) 4 else 5
        if (attrs <= classStart) {
            return null
        }

        var bestClassIndex = -1
        var bestClassScore = 0f
        for (i in classStart until attrs) {
            val s = prediction[i]
            if (s > bestClassScore) {
                bestClassScore = s
                bestClassIndex = i - classStart
            }
        }
        if (bestClassIndex < 0) {
            return null
        }

        val objectness = if (classStart == 5) prediction[4] else 1f
        val score = objectness * bestClassScore
        if (score < scoreThreshold) {
            return null
        }

        val cxRaw = prediction[0]
        val cyRaw = prediction[1]
        val wRaw = prediction[2]
        val hRaw = prediction[3]

        val cx = if (cxRaw > 1f) cxRaw / inputWidth else cxRaw
        val cy = if (cyRaw > 1f) cyRaw / inputHeight else cyRaw
        val bw = if (wRaw > 1f) wRaw / inputWidth else wRaw
        val bh = if (hRaw > 1f) hRaw / inputHeight else hRaw

        val left = (cx - (bw / 2f)).coerceIn(0f, 1f)
        val top = (cy - (bh / 2f)).coerceIn(0f, 1f)
        val right = (cx + (bw / 2f)).coerceIn(0f, 1f)
        val bottom = (cy + (bh / 2f)).coerceIn(0f, 1f)

        if (right <= left || bottom <= top) {
            return null
        }

        val label = cocoLabels.getOrElse(bestClassIndex) { "class_$bestClassIndex" }
        return DetectedObject(
            label = label,
            score = score,
            box = DetectionBox(left = left, top = top, right = right, bottom = bottom),
        )
    }

    private fun parseNmsOutputBoxesFirst(
        output: Array<FloatArray>,
        scoreThreshold: Float,
    ): List<DetectedObject> {
        val results = ArrayList<DetectedObject>(output.size)
        for (prediction in output) {
            parseNmsPrediction(prediction, scoreThreshold)?.let { results += it }
        }
        return results
    }

    private fun parseNmsOutputAttrsFirst(
        output: Array<FloatArray>,
        scoreThreshold: Float,
    ): List<DetectedObject> {
        val attrs = output.size
        val boxes = output.firstOrNull()?.size ?: 0
        val results = ArrayList<DetectedObject>(boxes)
        for (boxIndex in 0 until boxes) {
            val prediction = FloatArray(attrs) { attrIndex -> output[attrIndex][boxIndex] }
            parseNmsPrediction(prediction, scoreThreshold)?.let { results += it }
        }
        return results
    }

    private fun parseNmsPrediction(
        prediction: FloatArray,
        scoreThreshold: Float,
    ): DetectedObject? {
        if (prediction.size < 6) {
            return null
        }

        val x1 = normalizeX(prediction[0])
        val y1 = normalizeY(prediction[1])
        val x2 = normalizeX(prediction[2])
        val y2 = normalizeY(prediction[3])
        val score = prediction[4]
        val classId = prediction[5].toInt()

        if (score < scoreThreshold) {
            return null
        }

        val left = min(x1, x2).coerceIn(0f, 1f)
        val right = max(x1, x2).coerceIn(0f, 1f)
        val top = min(y1, y2).coerceIn(0f, 1f)
        val bottom = max(y1, y2).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) {
            return null
        }

        val label = cocoLabels.getOrElse(classId) { "class_$classId" }
        return DetectedObject(
            label = label,
            score = score,
            box = DetectionBox(left = left, top = top, right = right, bottom = bottom),
        )
    }

    private fun normalizeX(value: Float): Float {
        return if (value > 1f) value / inputWidth else value
    }

    private fun normalizeY(value: Float): Float {
        return if (value > 1f) value / inputHeight else value
    }

    private fun nonMaxSuppression(
        detections: List<DetectedObject>,
        iouThreshold: Float,
        maxDetections: Int,
    ): List<DetectedObject> {
        val sorted = detections.sortedByDescending { it.score }
        val selected = ArrayList<DetectedObject>(maxDetections)

        for (candidate in sorted) {
            if (selected.size >= maxDetections) {
                break
            }
            val overlaps =
                selected.any { existing ->
                    existing.label == candidate.label &&
                        iou(existing.box, candidate.box) > iouThreshold
                }
            if (!overlaps) {
                selected += candidate
            }
        }
        return selected
    }

    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) {
            return 0f
        }

        val areaA = (a.right - a.left).coerceAtLeast(0f) * (a.bottom - a.top).coerceAtLeast(0f)
        val areaB = (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    override fun close() {
        interpreter.close()
    }
}
