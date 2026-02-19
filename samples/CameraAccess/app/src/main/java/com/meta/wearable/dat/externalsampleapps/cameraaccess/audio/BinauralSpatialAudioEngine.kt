package com.meta.wearable.dat.externalsampleapps.cameraaccess.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class AudioRouteStatus(
    val isBluetoothActive: Boolean,
    val routeLabel: String,
)

data class SpatialPlaybackDebugInfo(
    val summary: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

class BinauralSpatialAudioEngine(
    private val context: Context,
    sofaAssetPath: String,
    compactHrirAssetPath: String? = null,
) : AutoCloseable {
    companion object {
    private const val TAG = "BinauralAudioEngine"
}

    private val sofaInfo: SofaAssetInfo? = SofaAssetLoader.load(context, sofaAssetPath)
    private val hrirDb: SofaHrirDatabase? =
        compactHrirAssetPath?.let { CompactHrirDatabaseLoader.load(context, it) }
            ?: SofaHrirDatabaseLoader.load(context, sofaAssetPath)
    private val hrirSourceLabel: String =
        if (compactHrirAssetPath != null && hrirDb != null) {
            "compact:${compactHrirAssetPath}"
        } else if (hrirDb != null) {
            "sofa:$sofaAssetPath"
        } else {
            "none"
        }
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val bluetoothOutputTypes =
        setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )
    private val decodedCueCache = mutableMapOf<String, MonoPcm>()
    private var activeTrack: AudioTrack? = null
    private var preferredOutputDevice: AudioDeviceInfo? = null
    private var routeStatusListener: ((AudioRouteStatus) -> Unit)? = null
    private var lastRenderedPcm16: ByteArray? = null
    private var lastRenderedSampleRateHz: Int = 0
    private var lastRouteRebindMs: Long = 0L
    @Volatile
    private var lastPlaybackDebugInfo: SpatialPlaybackDebugInfo? = null
    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                onAudioRouteChanged("added")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                onAudioRouteChanged("removed")
            }
        }

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, callbackHandler)
        refreshPreferredOutputDevice()
        Log.i(
            TAG,
            "Spatial audio engine initialized sofaLoaded=${sofaInfo != null} hrirLoaded=${hrirDb != null} source=$hrirSourceLabel sofaPath=${sofaInfo?.assetPath ?: "n/a"}",
        )
    }

    fun setRouteStatusListener(listener: ((AudioRouteStatus) -> Unit)?) {
        routeStatusListener = listener
        emitRouteStatus()
    }

    fun lastDebugSummary(): String? = lastPlaybackDebugInfo?.summary

    fun playSpatialCue(
        soundAssetPath: String,
        objectLabel: String? = null,
        azimuthDeg: Float,
        elevationDeg: Float = 0f,
    ): Long {
        val debug = mutableListOf<String>()
        debug += "request path=$soundAssetPath label=${objectLabel ?: "n/a"} az=${"%.1f".format(azimuthDeg)} el=${"%.1f".format(elevationDeg)}"
        val profile = ObjectSonificationProfiles.forLabel(objectLabel)
        val monoPcm = getOrDecodeMonoPcm(soundAssetPath)
        if (monoPcm == null) {
            publishDebug(debug + "fail: wav decode failed (expected PCM16 WAV)")
            return 0L
        }
        debug += "decoded wav sr=${monoPcm.sampleRateHz} frames=${monoPcm.samples.size}"
        val db = hrirDb
        val baseSampleRate = db?.sampleRateHz ?: monoPcm.sampleRateHz
        if (db == null) {
            debug += "warn: hrirDb unavailable, using stereo pan fallback"
            debug += "sofaInfoLoaded=${sofaInfo != null}"
            debug += "hrirSource=$hrirSourceLabel"
            debug += "compactLoadError=${CompactHrirDatabaseLoader.lastErrorSummary() ?: "n/a"}"
            debug += "sofaLoadError=${SofaHrirDatabaseLoader.lastErrorSummary() ?: "n/a"}"
        } else {
            debug += "hrirDb sr=${db.sampleRateHz} ir=${db.irLength} entries=${db.entries.size}"
            debug += "hrirSource=$hrirSourceLabel"
        }
        val targetSampleRate =
            (baseSampleRate.toFloat() * profile.playbackRateScale).toInt().coerceIn(8_000, 96_000)
        val mono = resampleLinear(monoPcm.samples, monoPcm.sampleRateHz, targetSampleRate)
        debug += "resampled sr=$targetSampleRate frames=${mono.size}"
        val shaped = applySimpleTiltEqAndGain(mono, profile)
        val (shapedLeft, shapedRight) =
            if (db != null) {
                val hrir = db.nearest(azimuthDeg = azimuthDeg, elevationDeg = elevationDeg)
                if (hrir == null) {
                    debug += "warn: nearest HRIR missing, using stereo pan fallback"
                    renderStereoPanFallback(shaped, azimuthDeg, elevationDeg)
                } else {
                    debug += "hrir nearest az=${"%.1f".format(hrir.azimuthDeg)} el=${"%.1f".format(hrir.elevationDeg)} taps=${hrir.left.size}"
                    convolveStereo(shaped, hrir.left, hrir.right)
                }
            } else {
                renderStereoPanFallback(shaped, azimuthDeg, elevationDeg)
            }
        val pcm16 = interleaveStereoPcm16(shapedLeft, shapedRight)
        debug += "convolved frames=${shapedLeft.size} bytes=${pcm16.size}"
        val playResult = playPcm16Stereo(pcm16, targetSampleRate)
        debug +=
            "play write=${playResult.writtenBytes} state=${playResult.trackState} playState=${playResult.playState} routed=${playResult.routed}"
        val streamVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val streamMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        debug += "musicVol=$streamVol/$streamMax route=${currentRouteLabel()}"
        val frameCount = pcm16.size / 4
        val durationMs = ((frameCount.toDouble() * 1000.0) / targetSampleRate.toDouble()).toLong()
        publishDebug(debug + "ok durationMs=$durationMs")
        return durationMs.coerceAtLeast(1L)
    }

    private fun renderStereoPanFallback(
        mono: FloatArray,
        azimuthDeg: Float,
        elevationDeg: Float,
    ): Pair<FloatArray, FloatArray> {
        if (mono.isEmpty()) return FloatArray(0) to FloatArray(0)
        val pan = (azimuthDeg / 90f).coerceIn(-1f, 1f)
        val angle = (pan + 1f) * (PI.toFloat() / 4f)
        val leftGain = cos(angle).coerceIn(0f, 1f)
        val rightGain = sin(angle).coerceIn(0f, 1f)
        val elevationAttn = (1f - (abs(elevationDeg) / 90f) * 0.1f).coerceIn(0.85f, 1f)
        val left = FloatArray(mono.size)
        val right = FloatArray(mono.size)
        for (i in mono.indices) {
            val s = mono[i] * elevationAttn
            left[i] = (s * leftGain).coerceIn(-1f, 1f)
            right[i] = (s * rightGain).coerceIn(-1f, 1f)
        }
        return left to right
    }

    private fun getOrDecodeMonoPcm(soundAssetPath: String): MonoPcm? {
        decodedCueCache[soundAssetPath]?.let { return it }
        val decoded = decodeWavMonoFromAssets(soundAssetPath) ?: return null
        decodedCueCache[soundAssetPath] = decoded
        return decoded
    }

    private fun decodeWavMonoFromAssets(assetPath: String): MonoPcm? {
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            parseWavMonoPcm16(bytes)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed reading WAV asset path=$assetPath", t)
            null
        }
    }

    private fun parseWavMonoPcm16(bytes: ByteArray): MonoPcm? {
        if (bytes.size < 44) {
            Log.e(TAG, "WAV too small: ${bytes.size} bytes")
            return null
        }
        fun readU16(offset: Int): Int {
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            return b0 or (b1 shl 8)
        }
        fun readU32(offset: Int): Int {
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            val b2 = bytes[offset + 2].toInt() and 0xFF
            val b3 = bytes[offset + 3].toInt() and 0xFF
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
        fun readStr(offset: Int, len: Int): String =
            bytes.copyOfRange(offset, offset + len).toString(Charsets.US_ASCII)

        if (readStr(0, 4) != "RIFF" || readStr(8, 4) != "WAVE") {
            Log.e(TAG, "Not a RIFF/WAVE file")
            return null
        }

        var offset = 12
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val chunkId = readStr(offset, 4)
            val chunkSize = readU32(offset + 4)
            val chunkDataStart = offset + 8
            val chunkDataEnd = (chunkDataStart + chunkSize).coerceAtMost(bytes.size)
            when (chunkId) {
                "fmt " -> {
                    if (chunkDataStart + 16 <= bytes.size) {
                        audioFormat = readU16(chunkDataStart)
                        channels = readU16(chunkDataStart + 2)
                        sampleRate = readU32(chunkDataStart + 4)
                        bitsPerSample = readU16(chunkDataStart + 14)
                    }
                }
                "data" -> {
                    dataOffset = chunkDataStart
                    dataSize = chunkDataEnd - chunkDataStart
                }
            }
            val advance = 8 + chunkSize + (chunkSize and 1)
            offset += advance
        }

        if (audioFormat != 1 || bitsPerSample != 16 || channels !in 1..2 || dataOffset < 0 || dataSize <= 0) {
            Log.e(
                TAG,
                "Unsupported WAV format format=$audioFormat channels=$channels bits=$bitsPerSample dataSize=$dataSize",
            )
            return null
        }

        val totalSamples = dataSize / 2
        val frameCount = totalSamples / channels
        val mono = FloatArray(frameCount)
        var i = 0
        var outIndex = 0
        while (i + (channels * 2) <= dataSize && outIndex < frameCount) {
            val base = dataOffset + i
            if (channels == 1) {
                val s = ((bytes[base + 1].toInt() shl 8) or (bytes[base].toInt() and 0xFF)).toShort()
                mono[outIndex] = (s / 32768f).coerceIn(-1f, 1f)
            } else {
                val l = ((bytes[base + 1].toInt() shl 8) or (bytes[base].toInt() and 0xFF)).toShort()
                val r = ((bytes[base + 3].toInt() shl 8) or (bytes[base + 2].toInt() and 0xFF)).toShort()
                mono[outIndex] = (((l / 32768f) + (r / 32768f)) * 0.5f).coerceIn(-1f, 1f)
            }
            i += channels * 2
            outIndex++
        }
        return MonoPcm(sampleRateHz = sampleRate, samples = mono)
    }

    private fun resampleLinear(input: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        if (input.isEmpty() || fromHz <= 0 || toHz <= 0 || fromHz == toHz) return input
        val ratio = toHz.toDouble() / fromHz.toDouble()
        val outLen = max(1, (input.size * ratio).toInt())
        val out = FloatArray(outLen)
        for (i in out.indices) {
            val src = i / ratio
            val x0 = src.toInt().coerceIn(0, input.lastIndex)
            val x1 = (x0 + 1).coerceIn(0, input.lastIndex)
            val t = (src - x0).toFloat()
            out[i] = ((1f - t) * input[x0]) + (t * input[x1])
        }
        return out
    }

    private fun convolveStereo(
        monoInput: FloatArray,
        hrirLeft: FloatArray,
        hrirRight: FloatArray,
    ): Pair<FloatArray, FloatArray> {
        val n = monoInput.size
        val m = hrirLeft.size.coerceAtMost(hrirRight.size)
        if (n == 0 || m == 0) return FloatArray(0) to FloatArray(0)
        val outLen = n + m - 1
        val outL = FloatArray(outLen)
        val outR = FloatArray(outLen)
        for (i in 0 until n) {
            val x = monoInput[i]
            if (abs(x) < 1e-8f) continue
            var j = 0
            while (j < m) {
                val idx = i + j
                outL[idx] += x * hrirLeft[j]
                outR[idx] += x * hrirRight[j]
                j++
            }
        }
        var peak = 0f
        for (i in outL.indices) {
            peak = max(peak, abs(outL[i]))
            peak = max(peak, abs(outR[i]))
        }
        if (peak > 1f) {
            val g = 1f / peak
            for (i in outL.indices) {
                outL[i] *= g
                outR[i] *= g
            }
        }
        return outL to outR
    }

    private fun applySimpleTiltEqAndGain(
        input: FloatArray,
        profile: ObjectSonificationProfile,
    ): FloatArray {
        if (input.isEmpty()) return input
        val out = FloatArray(input.size)
        var prev = input[0]
        for (i in input.indices) {
            val dry = input[i]
            val hp = dry - prev
            prev = dry
            val mixed = dry + (hp * profile.tiltEq)
            out[i] = (mixed * profile.gain).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun interleaveStereoPcm16(left: FloatArray, right: FloatArray): ByteArray {
        val frames = minOf(left.size, right.size)
        val out = ByteArray(frames * 4)
        var bi = 0
        for (i in 0 until frames) {
            val ls = (left[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            val rs = (right[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            out[bi++] = (ls.toInt() and 0xFF).toByte()
            out[bi++] = ((ls.toInt() ushr 8) and 0xFF).toByte()
            out[bi++] = (rs.toInt() and 0xFF).toByte()
            out[bi++] = ((rs.toInt() ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun playPcm16Stereo(stereoPcm16: ByteArray, sampleRateHz: Int): PlayResult {
        if (stereoPcm16.isEmpty()) {
            return PlayResult(writtenBytes = 0, routed = null, trackState = -1, playState = -1)
        }
        lastRenderedPcm16 = stereoPcm16
        lastRenderedSampleRateHz = sampleRateHz
        activeTrack?.runCatching {
            stop()
            flush()
            release()
        }
        val audioTrack =
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                stereoPcm16.size,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        var routed: Boolean? = null
        preferredOutputDevice?.let { routeDevice ->
            routed = audioTrack.setPreferredDevice(routeDevice)
            Log.d(TAG, "Applying preferred output device id=${routeDevice.id} routed=$routed")
        }
        val written = audioTrack.write(stereoPcm16, 0, stereoPcm16.size)
        if (written <= 0) {
            Log.e(TAG, "AudioTrack write failed bytes=$written")
            audioTrack.release()
            return PlayResult(
                writtenBytes = written,
                routed = routed,
                trackState = audioTrack.state,
                playState = audioTrack.playState,
            )
        }
        activeTrack = audioTrack
        audioTrack.play()
        return PlayResult(
            writtenBytes = written,
            routed = routed,
            trackState = audioTrack.state,
            playState = audioTrack.playState,
        )
    }

    override fun close() {
        routeStatusListener = null
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        activeTrack?.runCatching {
            stop()
            flush()
            release()
        }
        activeTrack = null
        preferredOutputDevice = null
        lastRenderedPcm16 = null
        lastRenderedSampleRateHz = 0
        decodedCueCache.clear()
    }

    private fun onAudioRouteChanged(reason: String) {
        val previousId = preferredOutputDevice?.id
        val previousBluetooth = preferredOutputDevice?.type?.let { it in bluetoothOutputTypes } == true
        refreshPreferredOutputDevice()
        val currentId = preferredOutputDevice?.id
        val currentBluetooth = preferredOutputDevice?.type?.let { it in bluetoothOutputTypes } == true
        if (previousId != currentId || previousBluetooth != currentBluetooth) {
            Log.i(
                TAG,
                "Audio route changed reason=$reason prevId=$previousId newId=$currentId bt=$currentBluetooth",
            )
            emitRouteStatus()
            rebindPlaybackAfterRouteChange()
        }
    }

    private fun refreshPreferredOutputDevice() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        preferredOutputDevice = outputs.firstOrNull { it.type in bluetoothOutputTypes }
    }

    private fun emitRouteStatus() {
        val route = preferredOutputDevice
        val status =
            if (route != null) {
                AudioRouteStatus(
                    isBluetoothActive = true,
                    routeLabel = "Bluetooth: ${route.productName ?: "unknown"}",
                )
            } else {
                AudioRouteStatus(
                    isBluetoothActive = false,
                    routeLabel = "Device speaker/other route",
                )
            }
        routeStatusListener?.invoke(status)
    }

    private fun rebindPlaybackAfterRouteChange() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRouteRebindMs < 250L) return
        lastRouteRebindMs = now
        val active = activeTrack ?: return
        val pcm = lastRenderedPcm16 ?: return
        val sr = lastRenderedSampleRateHz
        if (sr <= 0) return
        active.runCatching {
            stop()
            flush()
            release()
        }
        activeTrack = null
        playPcm16Stereo(pcm, sr)
    }

    private fun publishDebug(parts: List<String>) {
        val summary = parts.joinToString(" | ")
        lastPlaybackDebugInfo = SpatialPlaybackDebugInfo(summary = summary)
        Log.d(TAG, summary)
    }

    private fun currentRouteLabel(): String {
        return preferredOutputDevice?.let { "bt:${it.productName ?: "unknown"}#${it.id}" } ?: "non-bt/default"
    }

    private data class MonoPcm(
        val sampleRateHz: Int,
        val samples: FloatArray,
    )

    private data class PlayResult(
        val writtenBytes: Int,
        val routed: Boolean?,
        val trackState: Int,
        val playState: Int,
    )
}
