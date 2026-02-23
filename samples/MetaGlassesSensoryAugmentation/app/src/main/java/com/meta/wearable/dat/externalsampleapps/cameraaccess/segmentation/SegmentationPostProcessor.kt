package com.meta.wearable.dat.externalsampleapps.cameraaccess.segmentation

class SegmentationPostProcessor(
    private val minConfidence: Float = 0.45f,
    private val morphologyKernelRadius: Int = 1,
) {
    fun fromLogits(
        logits: FloatArray,
        shape: SegmentationTensorShape,
        targetWidth: Int,
        targetHeight: Int,
        idToLabel: Map<Int, String>,
        requestedLabels: Set<String>,
    ): SegmentationOutput {
        val classMapSmall = IntArray(shape.width * shape.height)
        val confidenceSmall = FloatArray(shape.width * shape.height)
        argmax(logits, shape, classMapSmall, confidenceSmall)

        val classMap = resizeIntNearest(classMapSmall, shape.width, shape.height, targetWidth, targetHeight)
        val confidenceMap =
            resizeFloatNearest(confidenceSmall, shape.width, shape.height, targetWidth, targetHeight)

        val cleanedClassMap = classMap.copyOf()
        val masks =
            buildPerClassMasks(
                classMap = cleanedClassMap,
                confidenceMap = confidenceMap,
                width = targetWidth,
                height = targetHeight,
                idToLabel = idToLabel,
                requestedLabels = requestedLabels,
            )

        return SegmentationOutput(
            width = targetWidth,
            height = targetHeight,
            classMap = cleanedClassMap,
            confidenceMap = confidenceMap,
            perClassMasks = masks,
            idToLabel = idToLabel,
        )
    }

    private fun argmax(
        logits: FloatArray,
        shape: SegmentationTensorShape,
        classMapOut: IntArray,
        confidenceOut: FloatArray,
    ) {
        val pixelCount = shape.width * shape.height
        require(logits.size == pixelCount * shape.classes) {
            "Unexpected logits size=${logits.size}, expected=${pixelCount * shape.classes}"
        }
        for (pixelIdx in 0 until pixelCount) {
            var bestClass = 0
            var bestLogit = Float.NEGATIVE_INFINITY
            var secondBestLogit = Float.NEGATIVE_INFINITY
            val base = pixelIdx * shape.classes
            for (c in 0 until shape.classes) {
                val value = logits[base + c]
                if (value > bestLogit) {
                    secondBestLogit = bestLogit
                    bestLogit = value
                    bestClass = c
                } else if (value > secondBestLogit) {
                    secondBestLogit = value
                }
            }
            classMapOut[pixelIdx] = bestClass
            confidenceOut[pixelIdx] = sigmoidMargin(bestLogit - secondBestLogit)
        }
    }

    private fun buildPerClassMasks(
        classMap: IntArray,
        confidenceMap: FloatArray,
        width: Int,
        height: Int,
        idToLabel: Map<Int, String>,
        requestedLabels: Set<String>,
    ): Map<String, ClassMask> {
        val labelsToBuild = requestedLabels.mapTo(linkedSetOf()) { it.trim().lowercase() }
        val result = linkedMapOf<String, ClassMask>()
        for ((classId, rawLabel) in idToLabel) {
            val label = rawLabel.trim().lowercase()
            if (label !in labelsToBuild) continue
            val mask = BooleanArray(width * height)
            for (i in classMap.indices) {
                if (classMap[i] == classId && confidenceMap[i] >= minConfidence) {
                    mask[i] = true
                }
            }
            val opened = erode(mask, width, height, morphologyKernelRadius).let {
                dilate(it, width, height, morphologyKernelRadius)
            }
            val cleaned = dilate(opened, width, height, morphologyKernelRadius).let {
                erode(it, width, height, morphologyKernelRadius)
            }
            result[label] = ClassMask(label = label, width = width, height = height, data = cleaned)
        }
        return result
    }

    private fun erode(
        src: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ): BooleanArray {
        if (radius <= 0) return src.copyOf()
        val dst = BooleanArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var keep = true
                loop@ for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy !in 0 until height) {
                        keep = false
                        break
                    }
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx !in 0 until width || !src[yy * width + xx]) {
                            keep = false
                            break@loop
                        }
                    }
                }
                dst[y * width + x] = keep
            }
        }
        return dst
    }

    private fun dilate(
        src: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ): BooleanArray {
        if (radius <= 0) return src.copyOf()
        val dst = BooleanArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var on = false
                loop@ for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy !in 0 until height) continue
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx !in 0 until width) continue
                        if (src[yy * width + xx]) {
                            on = true
                            break@loop
                        }
                    }
                }
                dst[y * width + x] = on
            }
        }
        return dst
    }

    private fun resizeIntNearest(
        src: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): IntArray {
        if (srcWidth == dstWidth && srcHeight == dstHeight) return src.copyOf()
        val dst = IntArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val yy = (y * srcHeight) / dstHeight
            for (x in 0 until dstWidth) {
                val xx = (x * srcWidth) / dstWidth
                dst[y * dstWidth + x] = src[yy * srcWidth + xx]
            }
        }
        return dst
    }

    private fun resizeFloatNearest(
        src: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): FloatArray {
        if (srcWidth == dstWidth && srcHeight == dstHeight) return src.copyOf()
        val dst = FloatArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val yy = (y * srcHeight) / dstHeight
            for (x in 0 until dstWidth) {
                val xx = (x * srcWidth) / dstWidth
                dst[y * dstWidth + x] = src[yy * srcWidth + xx]
            }
        }
        return dst
    }

    private fun sigmoidMargin(margin: Float): Float {
        return (1.0 / (1.0 + kotlin.math.exp((-margin).toDouble()))).toFloat()
    }
}
