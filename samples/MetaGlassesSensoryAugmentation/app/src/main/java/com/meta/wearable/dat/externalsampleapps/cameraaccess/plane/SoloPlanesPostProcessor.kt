package com.meta.wearable.dat.externalsampleapps.cameraaccess.plane

import com.meta.wearable.dat.externalsampleapps.cameraaccess.scene.SceneClass
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object SoloPlanesPostProcessor {
    private const val SCORE_THRESHOLD = 0.10f
    private const val MASK_THRESHOLD = 0.50f
    private const val UPDATE_THRESHOLD = 0.05f
    private const val NMS_PRE = 500
    private const val MAX_PER_IMAGE = 40
    private const val MATRIX_NMS_SIGMA = 2.0f
    private const val MIN_COMPONENT_PIXELS = 24

    private val nyu40ToSceneLabel =
        mapOf(
            1 to SceneClass.WALL.label,
            2 to SceneClass.FLOOR.label,
            5 to SceneClass.CHAIR.label,
            6 to SceneClass.SOFA.label,
            7 to SceneClass.TABLE.label,
            8 to SceneClass.DOOR.label,
            9 to SceneClass.WINDOWPANE.label,
            22 to SceneClass.CEILING.label,
        )

    fun decode(
        raw: SoloPlanesHeadOutputs,
        contract: SoloPlanesOutputContract,
    ): List<PlaneInstance> {
        if (raw.maskFeature.isEmpty() || raw.planeParams.isEmpty()) {
            return emptyList()
        }

        val candidates = mutableListOf<MaskCandidate>()
        val categoryLevels =
            listOf(
                raw.categoryLevel0,
                raw.categoryLevel1,
                raw.categoryLevel2,
            )
        val kernelLevels =
            listOf(
                raw.kernelLevel0,
                raw.kernelLevel1,
                raw.kernelLevel2,
            )

        contract.soloGridSizes.forEachIndexed { levelIndex, gridSize ->
            val processedScores = pointsNmsSigmoid(categoryLevels[levelIndex], contract.numClasses, gridSize, gridSize)
            val kernels = kernelLevels[levelIndex]
            val candidateCount = gridSize * gridSize
            val stride = contract.soloStrides[levelIndex]

            for (cellIndex in 0 until candidateCount) {
                var bestScore = Float.NEGATIVE_INFINITY
                var bestClass = -1
                for (classIndex in 0 until contract.numClasses) {
                    val score = processedScores[(classIndex * candidateCount) + cellIndex]
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = classIndex
                    }
                }
                if (bestScore <= SCORE_THRESHOLD || bestClass <= 0) {
                    continue
                }

                val kernel = FloatArray(contract.kernelChannels)
                val kernelBase = cellIndex
                for (channel in 0 until contract.kernelChannels) {
                    kernel[channel] = kernels[(channel * candidateCount) + kernelBase]
                }

                val softMask = applyDynamicKernel(raw.maskFeature, kernel, contract)
                val binaryMask = thresholdMask(softMask, MASK_THRESHOLD)
                val sumMask = binaryMask.count { it }
                if (sumMask <= stride) {
                    continue
                }

                val segScore = maskedAverage(softMask, binaryMask)
                val weightedScore = bestScore * segScore
                if (weightedScore <= 0f) {
                    continue
                }

                val component = largestComponent(binaryMask, raw.planeParamWidth, raw.planeParamHeight) ?: continue
                if (component.areaPixels < MIN_COMPONENT_PIXELS) {
                    continue
                }

                candidates +=
                    MaskCandidate(
                        classId = bestClass,
                        score = weightedScore,
                        softMask = softMask,
                        binaryMask = component.mask,
                        areaPixels = component.areaPixels,
                        centroidX = component.centroidX,
                        centroidY = component.centroidY,
                        minX = component.minX,
                        minY = component.minY,
                        maxX = component.maxX,
                        maxY = component.maxY,
                    )
            }
        }

        if (candidates.isEmpty()) {
            return emptyList()
        }

        val topCandidates = candidates.sortedByDescending { it.score }.take(NMS_PRE).toMutableList()
        val updatedScores = matrixNms(topCandidates)
        val kept =
            topCandidates
                .mapIndexedNotNull { index, candidate ->
                    val score = updatedScores[index]
                    if (score < UPDATE_THRESHOLD) {
                        null
                    } else {
                        candidate.copy(score = score)
                    }
                }.sortedByDescending { it.score }
                .take(MAX_PER_IMAGE)

        return kept.mapIndexed { index, candidate ->
            buildPlaneInstance(index, candidate, raw)
        }
    }

    private fun buildPlaneInstance(
        index: Int,
        candidate: MaskCandidate,
        raw: SoloPlanesHeadOutputs,
    ): PlaneInstance {
        val pooledPlane = poolPlaneParameters(candidate.softMask, candidate.binaryMask, raw)
        val offset = sqrt((pooledPlane[0] * pooledPlane[0]) + (pooledPlane[1] * pooledPlane[1]) + (pooledPlane[2] * pooledPlane[2]))
        val scale = if (offset > 1e-6f) 1f / offset else 0f
        val width = raw.planeParamWidth
        val height = raw.planeParamHeight
        return PlaneInstance(
            id = "plane:$index:${candidate.classId}",
            score = candidate.score.coerceIn(0f, 1f),
            semanticClassId = candidate.classId,
            semanticLabel = nyu40ToSceneLabel[candidate.classId],
            normalX = pooledPlane[0] * scale,
            normalY = pooledPlane[1] * scale,
            normalZ = pooledPlane[2] * scale,
            offset = offset,
            centroidXNorm = candidate.centroidX / width.toFloat(),
            centroidYNorm = candidate.centroidY / height.toFloat(),
            widthNorm = ((candidate.maxX - candidate.minX + 1).toFloat() / width.toFloat()).coerceIn(0f, 1f),
            heightNorm = ((candidate.maxY - candidate.minY + 1).toFloat() / height.toFloat()).coerceIn(0f, 1f),
            areaRatio = candidate.areaPixels.toFloat() / (width * height).toFloat(),
            maskWidth = width,
            maskHeight = height,
            mask = candidate.binaryMask,
        )
    }

    private fun poolPlaneParameters(
        softMask: FloatArray,
        binaryMask: BooleanArray,
        raw: SoloPlanesHeadOutputs,
    ): FloatArray {
        val width = raw.planeParamWidth
        val height = raw.planeParamHeight
        var sumWeight = 0f
        val pooled = FloatArray(3)
        val planeArea = width * height
        for (index in 0 until planeArea) {
            if (!binaryMask[index]) continue
            val weight = softMask[index]
            sumWeight += weight
            pooled[0] += raw.planeParams[index] * weight
            pooled[1] += raw.planeParams[planeArea + index] * weight
            pooled[2] += raw.planeParams[(planeArea * 2) + index] * weight
        }
        if (sumWeight > 1e-6f) {
            pooled[0] /= sumWeight
            pooled[1] /= sumWeight
            pooled[2] /= sumWeight
        }
        return pooled
    }

    private fun applyDynamicKernel(
        maskFeature: FloatArray,
        kernel: FloatArray,
        contract: SoloPlanesOutputContract,
    ): FloatArray {
        val area = contract.planeParamWidth * contract.planeParamHeight
        val out = FloatArray(area)
        for (pixelIndex in 0 until area) {
            var value = 0f
            var base = pixelIndex
            for (channel in 0 until contract.kernelChannels) {
                value += maskFeature[base] * kernel[channel]
                base += area
            }
            out[pixelIndex] = sigmoid(value)
        }
        return out
    }

    private fun thresholdMask(
        softMask: FloatArray,
        threshold: Float,
    ): BooleanArray {
        val mask = BooleanArray(softMask.size)
        for (index in softMask.indices) {
            mask[index] = softMask[index] > threshold
        }
        return mask
    }

    private fun maskedAverage(
        values: FloatArray,
        mask: BooleanArray,
    ): Float {
        var sum = 0f
        var count = 0
        for (index in values.indices) {
            if (!mask[index]) continue
            sum += values[index]
            count += 1
        }
        return if (count > 0) sum / count.toFloat() else 0f
    }

    private fun pointsNmsSigmoid(
        logits: FloatArray,
        classes: Int,
        width: Int,
        height: Int,
    ): FloatArray {
        val out = FloatArray(logits.size)
        val area = width * height
        for (classIndex in 0 until classes) {
            val classBase = classIndex * area
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = classBase + (y * width) + x
                    val value = sigmoid(logits[idx])
                    var localMax = value
                    for (dy in 0..1) {
                        val yy = y + dy - 1
                        if (yy !in 0 until height) continue
                        for (dx in 0..1) {
                            val xx = x + dx - 1
                            if (xx !in 0 until width) continue
                            localMax = max(localMax, sigmoid(logits[classBase + (yy * width) + xx]))
                        }
                    }
                    out[idx] = if (abs(localMax - value) < 1e-6f) value else 0f
                }
            }
        }
        return out
    }

    private fun matrixNms(candidates: List<MaskCandidate>): FloatArray {
        val count = candidates.size
        if (count == 0) {
            return FloatArray(0)
        }
        val compensate = FloatArray(count)
        val decay = Array(count) { FloatArray(count) }

        for (i in 0 until count) {
            for (j in (i + 1) until count) {
                if (candidates[i].classId != candidates[j].classId) {
                    continue
                }
                val iou = maskIoU(candidates[i].binaryMask, candidates[j].binaryMask)
                decay[i][j] = iou
                if (iou > compensate[j]) {
                    compensate[j] = iou
                }
            }
        }

        val updated = FloatArray(count)
        for (j in 0 until count) {
            var coefficient = 1f
            var initialized = false
            for (i in 0 until j) {
                val iou = decay[i][j]
                if (iou <= 0f) continue
                val decayValue = exp((-MATRIX_NMS_SIGMA * iou * iou).toDouble()).toFloat()
                val compensateValue = exp((-MATRIX_NMS_SIGMA * compensate[j] * compensate[j]).toDouble()).toFloat()
                val ratio = if (compensateValue > 1e-6f) decayValue / compensateValue else decayValue
                if (!initialized || ratio < coefficient) {
                    coefficient = ratio
                    initialized = true
                }
            }
            updated[j] = candidates[j].score * coefficient
        }
        return updated
    }

    private fun maskIoU(
        a: BooleanArray,
        b: BooleanArray,
    ): Float {
        var intersection = 0
        var union = 0
        for (index in a.indices) {
            val av = a[index]
            val bv = b[index]
            if (av && bv) {
                intersection += 1
            }
            if (av || bv) {
                union += 1
            }
        }
        return if (union > 0) intersection.toFloat() / union.toFloat() else 0f
    }

    private fun largestComponent(
        mask: BooleanArray,
        width: Int,
        height: Int,
    ): ComponentStats? {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var best: ComponentStats? = null

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            val componentMask = BooleanArray(mask.size)
            var area = 0
            var sumX = 0f
            var sumY = 0f
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1

            while (head < tail) {
                val index = queue[head++]
                componentMask[index] = true
                val x = index % width
                val y = index / width
                area += 1
                sumX += x.toFloat()
                sumY += y.toFloat()
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)

                if (x > 0) {
                    enqueue(index - 1, mask, visited, queue, tail).also { tail = it }
                }
                if (x + 1 < width) {
                    enqueue(index + 1, mask, visited, queue, tail).also { tail = it }
                }
                if (y > 0) {
                    enqueue(index - width, mask, visited, queue, tail).also { tail = it }
                }
                if (y + 1 < height) {
                    enqueue(index + width, mask, visited, queue, tail).also { tail = it }
                }
            }

            if (area > (best?.areaPixels ?: -1)) {
                best =
                    ComponentStats(
                        mask = componentMask,
                        areaPixels = area,
                        centroidX = sumX / area.toFloat(),
                        centroidY = sumY / area.toFloat(),
                        minX = minX,
                        minY = minY,
                        maxX = maxX,
                        maxY = maxY,
                    )
            }
        }

        return best
    }

    private fun enqueue(
        index: Int,
        mask: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (!mask[index] || visited[index]) {
            return tail
        }
        visited[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun sigmoid(value: Float): Float {
        if (value >= 0f) {
            val z = exp((-value).toDouble()).toFloat()
            return 1f / (1f + z)
        }
        val z = exp(value.toDouble()).toFloat()
        return z / (1f + z)
    }

    private data class MaskCandidate(
        val classId: Int,
        val score: Float,
        val softMask: FloatArray,
        val binaryMask: BooleanArray,
        val areaPixels: Int,
        val centroidX: Float,
        val centroidY: Float,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
    )

    private data class ComponentStats(
        val mask: BooleanArray,
        val areaPixels: Int,
        val centroidX: Float,
        val centroidY: Float,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
    )
}
