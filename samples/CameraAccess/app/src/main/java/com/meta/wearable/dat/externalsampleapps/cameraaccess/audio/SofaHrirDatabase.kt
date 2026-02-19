package com.meta.wearable.dat.externalsampleapps.cameraaccess.audio

import android.content.Context
import android.util.Log
import io.jhdf.HdfFile
import io.jhdf.api.Dataset
import java.io.File
import kotlin.math.abs

data class SofaHrir(
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val left: FloatArray,
    val right: FloatArray,
)


fun normalizeSigned180(deg: Float): Float {
    var value = deg % 360f
    if (value > 180f) value -= 360f
    if (value < -180f) value += 360f
    if (abs(value) < 1e-4f) return 0f
    return value
}

data class SofaHrirDatabase(
    val sampleRateHz: Int,
    val irLength: Int,
    val entries: List<SofaHrir>,
) {
    fun nearest(azimuthDeg: Float, elevationDeg: Float): SofaHrir? {
        if (entries.isEmpty()) return null
        var best: SofaHrir? = null
        var bestScore = Float.MAX_VALUE
        for (entry in entries) {
            val daz: Float = normalizeSigned180(entry.azimuthDeg - azimuthDeg)
            val del: Float = entry.elevationDeg - elevationDeg
            val score: Float = (daz * daz) + (del * del)
            if (score < bestScore) {
                bestScore = score
                best = entry
            }
        }
        return best
    }
}

object SofaHrirDatabaseLoader {
    private const val TAG = "SofaHrirDbLoader"
    private const val MAX_ESTIMATED_IR_BYTES = 96L * 1024L * 1024L
    @Volatile
    private var lastErrorSummary: String? = null

    fun lastErrorSummary(): String? = lastErrorSummary

    fun load(context: Context, assetPath: String): SofaHrirDatabase? {
        val tempFile = copyAssetToCache(context, assetPath) ?: return null
        return try {
            HdfFile(tempFile).use { hdf ->
                val sourcePosDataset = hdf.getByPath("/SourcePosition") as Dataset
                val dataIrDataset = hdf.getByPath("/Data.IR") as Dataset
                val samplingRateDataset = hdf.getByPath("/Data.SamplingRate") as Dataset

                val sourcePosShape = sourcePosDataset.dimensions.map { it.toInt() }
                val dataIrShape = dataIrDataset.dimensions.map { it.toInt() }

                if (sourcePosShape.size != 2 || sourcePosShape[1] < 2) {
                    Log.e(TAG, "Unexpected SourcePosition shape=$sourcePosShape")
                    lastErrorSummary = "Unexpected SourcePosition shape=$sourcePosShape"
                    return@use null
                }
                if (dataIrShape.size != 3 || dataIrShape[1] < 2) {
                    Log.e(TAG, "Unexpected Data.IR shape=$dataIrShape")
                    lastErrorSummary = "Unexpected Data.IR shape=$dataIrShape"
                    return@use null
                }
                val estimatedIrBytes =
                    dataIrShape.fold(1L) { acc, dim -> acc * dim.toLong().coerceAtLeast(1L) } * 4L
                if (estimatedIrBytes > MAX_ESTIMATED_IR_BYTES) {
                    lastErrorSummary =
                        "SOFA Data.IR too large (${estimatedIrBytes / (1024 * 1024)}MB) for in-app parse limit ${MAX_ESTIMATED_IR_BYTES / (1024 * 1024)}MB"
                    Log.e(TAG, lastErrorSummary!!)
                    return@use null
                }

                val sourcePosRaw = readDatasetAsFloatArray(sourcePosDataset)
                val dataIrRaw = readDatasetAsFloatArray(dataIrDataset)
                val srRaw = readDatasetAsFloatArray(samplingRateDataset)

                val m = dataIrShape[0]
                val r = dataIrShape[1]
                val n = dataIrShape[2]
                if (m <= 0 || r < 2 || n <= 0) {
                    Log.e(TAG, "Invalid Data.IR dimensions M=$m R=$r N=$n")
                    lastErrorSummary = "Invalid Data.IR dimensions M=$m R=$r N=$n"
                    return@use null
                }
                val sampleRate = srRaw.firstOrNull()?.toInt()?.coerceAtLeast(8_000) ?: 48_000

                val entries = ArrayList<SofaHrir>(m)
                for (measurement in 0 until m) {
                    val posBase = measurement * sourcePosShape[1]
                    val azimuth = sourcePosRaw.getOrNull(posBase) ?: 0f
                    val elevation = sourcePosRaw.getOrNull(posBase + 1) ?: 0f
                    val left = FloatArray(n)
                    val right = FloatArray(n)
                    val leftBase = ((measurement * r) + 0) * n
                    val rightBase = ((measurement * r) + 1) * n
                    for (i in 0 until n) {
                        left[i] = dataIrRaw.getOrElse(leftBase + i) { 0f }
                        right[i] = dataIrRaw.getOrElse(rightBase + i) { 0f }
                    }
                    entries.add(
                        SofaHrir(
                            azimuthDeg = azimuth,
                            elevationDeg = elevation,
                            left = left,
                            right = right,
                        )
                    )
                }

                Log.i(
                    TAG,
                    "Loaded SOFA HRIRs asset=$assetPath sampleRate=$sampleRate M=$m R=$r N=$n",
                )
                lastErrorSummary = null
                SofaHrirDatabase(
                    sampleRateHz = sampleRate,
                    irLength = n,
                    entries = entries,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse SOFA HRIR database asset=$assetPath", t)
            lastErrorSummary = "${t::class.java.simpleName}: ${t.message ?: "unknown"}"
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun copyAssetToCache(context: Context, assetPath: String): File? {
        return try {
            val out = File(context.cacheDir, "sofa_temp_${assetPath.substringAfterLast('/')}").apply {
                parentFile?.mkdirs()
            }
            context.assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        } catch (t: Throwable) {
            Log.e(TAG, "Failed copying SOFA asset to cache: $assetPath", t)
            lastErrorSummary = "copyAsset failed: ${t::class.java.simpleName}: ${t.message ?: "unknown"}"
            null
        }
    }

    private fun readDatasetAsFloatArray(dataset: Dataset): FloatArray {
        val raw = dataset.data
        val out = ArrayList<Float>(1024)
        flattenNumeric(raw, out)
        return out.toFloatArray()
    }

    private fun flattenNumeric(value: Any?, out: MutableList<Float>) {
        when (value) {
            null -> Unit
            is FloatArray -> value.forEach { out.add(it) }
            is DoubleArray -> value.forEach { out.add(it.toFloat()) }
            is IntArray -> value.forEach { out.add(it.toFloat()) }
            is LongArray -> value.forEach { out.add(it.toFloat()) }
            is ShortArray -> value.forEach { out.add(it.toFloat()) }
            is Array<*> -> value.forEach { flattenNumeric(it, out) }
            is Number -> out.add(value.toFloat())
            else -> {
                val text = value.toString()
                if (text.isNotBlank()) {
                    runCatching { text.toFloat() }.getOrNull()?.let { out.add(it) }
                }
            }
        }
    }

}
