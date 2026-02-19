package com.meta.wearable.dat.externalsampleapps.cameraaccess.audio

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CompactHrirDatabaseLoader {
    private const val TAG = "CompactHrirLoader"
    private const val MAGIC = "HRIRBIN1"
    private const val VERSION = 1

    @Volatile
    private var lastErrorSummary: String? = null

    fun lastErrorSummary(): String? = lastErrorSummary

    fun load(context: Context, assetPath: String): SofaHrirDatabase? {
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            parse(bytes, assetPath)
        } catch (t: Throwable) {
            lastErrorSummary = "${t::class.java.simpleName}: ${t.message ?: "unknown"}"
            Log.e(TAG, "Failed to load compact HRIR asset=$assetPath", t)
            null
        }
    }

    private fun parse(bytes: ByteArray, assetPath: String): SofaHrirDatabase? {
        if (bytes.size < 24) {
            lastErrorSummary = "File too small (${bytes.size} bytes)"
            return null
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = ByteArray(8)
        bb.get(magicBytes)
        val magic = magicBytes.toString(Charsets.US_ASCII)
        if (magic != MAGIC) {
            lastErrorSummary = "Bad magic '$magic' expected '$MAGIC'"
            return null
        }
        val version = bb.int
        if (version != VERSION) {
            lastErrorSummary = "Unsupported version=$version expected=$VERSION"
            return null
        }
        val sampleRate = bb.int
        val tapCount = bb.int
        val entryCount = bb.int
        if (sampleRate <= 0 || tapCount <= 0 || entryCount <= 0) {
            lastErrorSummary = "Invalid header sr=$sampleRate taps=$tapCount entries=$entryCount"
            return null
        }

        val bytesPerEntry = 8 + (tapCount * 2 * 2)
        val requiredSize = 24L + (bytesPerEntry.toLong() * entryCount.toLong())
        if (bytes.size.toLong() < requiredSize) {
            lastErrorSummary = "Truncated file bytes=${bytes.size} required=$requiredSize"
            return null
        }

        val entries = ArrayList<SofaHrir>(entryCount)
        repeat(entryCount) {
            val azimuth = bb.float
            val elevation = bb.float
            val left = FloatArray(tapCount)
            val right = FloatArray(tapCount)
            for (i in 0 until tapCount) {
                left[i] = bb.short / 32768f
            }
            for (i in 0 until tapCount) {
                right[i] = bb.short / 32768f
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

        val db =
            SofaHrirDatabase(
                sampleRateHz = sampleRate,
                irLength = tapCount,
                entries = entries,
            )
        lastErrorSummary = null
        Log.i(
            TAG,
            "Loaded compact HRIR asset=$assetPath sr=${db.sampleRateHz} taps=${db.irLength} entries=${db.entries.size}",
        )
        return db
    }
}
