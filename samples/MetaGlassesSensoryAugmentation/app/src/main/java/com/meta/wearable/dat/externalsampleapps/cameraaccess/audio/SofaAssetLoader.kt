package com.meta.wearable.dat.externalsampleapps.cameraaccess.audio

import android.content.Context
import android.util.Log
import java.security.MessageDigest

data class SofaAssetInfo(
    val assetPath: String,
    val byteCount: Int,
    val sha256: String,
)

object SofaAssetLoader {
    private const val TAG = "SofaAssetLoader"

    fun load(context: Context, assetPath: String): SofaAssetInfo? {
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sha256 = digest.joinToString("") { "%02x".format(it) }
            val info = SofaAssetInfo(assetPath = assetPath, byteCount = bytes.size, sha256 = sha256)
            Log.i(TAG, "Loaded SOFA asset path=${info.assetPath} bytes=${info.byteCount} sha256=${info.sha256}")
            info
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load SOFA asset path=$assetPath", t)
            null
        }
    }
}

