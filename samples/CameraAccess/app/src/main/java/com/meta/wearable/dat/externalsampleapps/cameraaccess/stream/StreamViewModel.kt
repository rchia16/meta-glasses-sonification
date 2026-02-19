/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> NV21 conversion)

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Application
import android.content.Intent
import android.location.Location
import android.hardware.SensorManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.BinauralSpatialAudioEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.ObjectSonificationProfile
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.ObjectSonificationProfiles
import com.meta.wearable.dat.externalsampleapps.cameraaccess.cue.CueType
import com.meta.wearable.dat.externalsampleapps.cameraaccess.cue.CueScheduler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.DetectedObject
import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.filterToNavigationClasses
import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.RawCameraFrame
import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.SsdMobileNetTfliteDetector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.Yolo26NanoTfliteDetector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks.LandmarkDatabase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks.LandmarkLocationProvider
import com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks.LandmarkManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks.LandmarkForgetResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks.Landmark
import com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks.LandmarkRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks.LandmarkTrackResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.priority.ObjectRankingPolicy
import com.meta.wearable.dat.externalsampleapps.cameraaccess.priority.ObjectCueSoundMap
import com.meta.wearable.dat.externalsampleapps.cameraaccess.priority.SceneCueScheduler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.sensors.MagneticHeadingService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.spatial.normalize360
import com.meta.wearable.dat.externalsampleapps.cameraaccess.spatial.normalizeSigned180
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tracking.ObjectTracker
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tracking.TrackedObject
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceCommand
import com.meta.wearable.dat.externalsampleapps.cameraaccess.voice.VoiceCommandParser
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "StreamViewModel"
        private val INITIAL_STATE = StreamUiState()
        private const val MIN_SCENE_REFRESH_RATE_HZ = 0.3f
        private const val MAX_SCENE_REFRESH_RATE_HZ = 3.0f
        private const val SSD_MOBILENET_MODEL_ASSET_PATH = ""
        //  "models/ssd_mobilenet_v3_large_coco_2020_01_14/model.tflite"
        private const val YOLO26_NANO_MODEL_ASSET_PATH = "models/yolo12n_saved_model/yolo12n_float16.tflite"
        private const val CAMERA_HORIZONTAL_FOV_DEGREES = 58f
        private const val CAMERA_VERTICAL_FOV_DEGREES = 45f
        private const val SOFA_ASSET_PATH = "sofa/BRIR_HATS_3degree_for_glasses.sofa"
        private const val COMPACT_HRIR_ASSET_PATH = "hrir/hrir_compact_v1.bin"
        private const val NORTH_CUE_SOUND_ASSET_PATH = "audio/retro.wav"
        private const val NORTH_CUE_COOLDOWN_MS = 5_000L
        private const val LANDMARK_CUE_SOUND_ASSET_PATH = "audio/bling.wav"
        private const val LANDMARK_CUE_COOLDOWN_MS = 6_000L
    }

    private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
    private var streamSession: StreamSession? = null

    private val _uiState = MutableStateFlow(INITIAL_STATE)
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    private val streamTimer = StreamTimer()
    private val voiceCommandParser = VoiceCommandParser()
    private val sensorManager: SensorManager =
        getApplication<Application>().getSystemService(SensorManager::class.java)
    private val headingService = MagneticHeadingService(sensorManager)
    private val landmarkManager =
        LandmarkManager(
            repository = LandmarkRepository(LandmarkDatabase.getInstance(getApplication()).landmarkDao()),
            locationProvider = LandmarkLocationProvider(getApplication()),
        )
    private val cueScheduler = CueScheduler()
    private val objectTracker = ObjectTracker(maxTracks = 5)
    private val rankingPolicy = ObjectRankingPolicy()
    private val sceneCueScheduler = SceneCueScheduler(getApplication())
    private val spatialAudioEngine =
        BinauralSpatialAudioEngine(
            context = getApplication(),
            sofaAssetPath = SOFA_ASSET_PATH,
            compactHrirAssetPath = COMPACT_HRIR_ASSET_PATH,
        )
    private val mobileNetDetector =
        SsdMobileNetTfliteDetector.createFromAssets(
            context = getApplication(),
            modelAssetPath = SSD_MOBILENET_MODEL_ASSET_PATH,
        )
    private val yoloDetector =
        if (mobileNetDetector == null) {
            Log.w(TAG, "MobileNet model not found; falling back to YOLO detector")
            Yolo26NanoTfliteDetector.createFromAssets(
                context = getApplication(),
                modelAssetPath = YOLO26_NANO_MODEL_ASSET_PATH,
            )
        } else {
            null
        }

    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private var timerJob: Job? = null
    private var objectCueJob: Job? = null
    private val trackAzimuthById = ConcurrentHashMap<Long, Float>()
    private var activeCueWorldBearingDeg: Float? = null
    private var latestHeadingDegrees: Float? = null
    private var currentLandmarks: List<Landmark> = emptyList()
    private var lastNorthCueAtMs: Long = 0L
    private var lastLandmarkCueAtMs: Long = 0L
    private var audioPlaybackToken: Long = 0L

    init {
        _uiState.update {
            it.copy(sonificationProfiles = ObjectSonificationProfiles.snapshotProfiles())
        }
        spatialAudioEngine.setRouteStatusListener { routeStatus ->
            _uiState.update {
                it.copy(
                    isBluetoothAudioRouteActive = routeStatus.isBluetoothActive,
                    audioRouteLabel = routeStatus.routeLabel,
                )
            }
        }
        // Collect timer state
        timerJob = viewModelScope.launch {
            launch {
                streamTimer.timerMode.collect { mode -> _uiState.update { it.copy(timerMode = mode) } }
            }

            launch {
                streamTimer.remainingTimeSeconds.collect { seconds ->
                    _uiState.update { it.copy(remainingTimeSeconds = seconds) }
                }
            }

            launch {
                streamTimer.isTimerExpired.collect { expired ->
                    if (expired) {
                        // Stop streaming and navigate back
                        stopStream()
                        wearablesViewModel.navigateToDeviceSelection()
                    }
                }
            }

            launch {
                landmarkManager.landmarks.collect { landmarks ->
                    currentLandmarks = landmarks
                    _uiState.update { state ->
                        state.copy(trackedLandmarks = landmarks.map { it.name })
                    }
                }
            }

            launch {
                headingService.heading.collect { reading ->
                    val headingDeg = reading?.headingDegrees ?: return@collect
                    latestHeadingDegrees = headingDeg
                    val spatialAzimuth =
                        activeCueWorldBearingDeg?.let { world -> normalizeSigned180(world - headingDeg) }
                    _uiState.update {
                        it.copy(
                            headingDegrees = headingDeg,
                            activeObjectWorldBearingDeg = activeCueWorldBearingDeg,
                            activeObjectSpatialAzimuthDeg = spatialAzimuth,
                        )
                    }
                }
            }

            launch {
                cueScheduler.events.collect { event ->
                    _uiState.update { state ->
                        if (event.type == CueType.OBJECT) {
                            val parts = event.target.split(":", limit = 2)
                            val trackId = parts.firstOrNull()?.toLongOrNull()
                            val label = parts.getOrNull(1)
                            state.copy(
                                lastCueEvent = event.message,
                                activeObjectCueTrackId = trackId,
                                activeObjectCueLabel = label,
                                activeObjectCueSoundAsset = event.soundAssetPath,
                            )
                        } else {
                            state.copy(lastCueEvent = event.message)
                        }
                    }
                }
            }
        }
    }

    fun handleVoiceTranscript(transcript: String) {
        val command = voiceCommandParser.parse(transcript)
        _uiState.update { it.copy(lastVoiceCommand = transcript) }

        when (command) {
            is VoiceCommand.Track -> {
                viewModelScope.launch {
                    when (val result = landmarkManager.track(command.name)) {
                        is LandmarkTrackResult.Tracked -> {
                            cueScheduler.requestLandmarkTracked(result.name)
                            _uiState.update {
                                it.copy(lastVoiceStatus = "Tracking landmark '${result.name}'")
                            }
                        }

                        is LandmarkTrackResult.AlreadyTracked -> {
                            _uiState.update {
                                it.copy(lastVoiceStatus = "Landmark '${result.name}' is already tracked")
                            }
                        }

                        LandmarkTrackResult.LocationUnavailable -> {
                            _uiState.update {
                                it.copy(lastVoiceStatus = "Location unavailable. Move outdoors and try again")
                            }
                        }

                        LandmarkTrackResult.InvalidName -> {
                            _uiState.update { it.copy(lastVoiceStatus = "Invalid landmark name") }
                        }
                    }
                }
            }

            is VoiceCommand.SaveLandmark -> {
                saveLandmarkManually(command.name, statusPrefix = "Saved")
            }

            is VoiceCommand.Forget -> {
                viewModelScope.launch {
                    when (val result = landmarkManager.forget(command.name)) {
                        is LandmarkForgetResult.Forgot -> {
                            cueScheduler.requestLandmarkForget(result.name)
                            _uiState.update {
                                it.copy(lastVoiceStatus = "Stopped tracking landmark '${result.name}'")
                            }
                        }

                        is LandmarkForgetResult.NotFound -> {
                            _uiState.update {
                                it.copy(lastVoiceStatus = "Landmark '${result.name}' was not tracked")
                            }
                        }

                        LandmarkForgetResult.InvalidName -> {
                            _uiState.update { it.copy(lastVoiceStatus = "Invalid landmark name") }
                        }
                    }
                }
            }

            is VoiceCommand.Ping -> {
                viewModelScope.launch {
                    if (command.target.equals("north", ignoreCase = true)) {
                        val emitted = emitNorthCue(trigger = "voice")
                        _uiState.update {
                            it.copy(
                                lastVoiceStatus =
                                    if (emitted) "North ping requested" else "North cue unavailable without heading",
                            )
                        }
                        return@launch
                    }

                    val landmark = findTrackedLandmarkByName(command.target)
                    if (landmark == null) {
                        _uiState.update {
                            it.copy(
                                lastVoiceStatus =
                                    "Unknown ping target '${command.target}'. Use 'ping north' or 'ping <saved landmark>'",
                            )
                        }
                        return@launch
                    }

                    val emitted = emitLandmarkCue(landmark = landmark, trigger = "voice")
                    _uiState.update {
                        it.copy(
                            lastVoiceStatus =
                                if (emitted) {
                                    "Ping requested for landmark '${landmark.name}'"
                                } else {
                                    "Landmark cue unavailable without heading or location"
                                },
                        )
                    }
                }
            }

            is VoiceCommand.Only -> {
                val label = resolveOnlyClassLabel(command.className)
                if (label == null) {
                    val supported = ObjectSonificationProfiles.supportedLabels().joinToString()
                    _uiState.update {
                        it.copy(
                            lastVoiceStatus =
                                "Unknown class '${command.className}'. Supported: $supported",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            onlySonificationLabel = label,
                            lastVoiceStatus = "Only sonifying '$label'",
                        )
                    }
                }
            }

            VoiceCommand.EnableNorthCueMode -> {
                _uiState.update { it.copy(isNorthCueEnabled = true, lastVoiceStatus = "North mode enabled") }
            }

            VoiceCommand.DisableNorthCueMode -> {
                _uiState.update { it.copy(isNorthCueEnabled = false, lastVoiceStatus = "North mode disabled") }
            }

            VoiceCommand.ActivateObjectSonification -> {
                _uiState.update {
                    it.copy(
                        isObjectSonificationEnabled = true,
                        onlySonificationLabel = null,
                        lastVoiceStatus = "Object sonification activated for all classes",
                    )
                }
            }

            VoiceCommand.DeactivateObjectSonification -> {
                _uiState.update {
                    it.copy(
                        isObjectSonificationEnabled = false,
                        lastVoiceStatus = "Object sonification deactivated",
                    )
                }
            }

            is VoiceCommand.Unknown -> {
                _uiState.update {
                    it.copy(
                        lastVoiceStatus =
                            "Unknown command. Supported: track/save/forget <name>, ping <saved landmark|north>, only <class> or <class> only, north mode on/off, activate, deactivate",
                    )
                }
            }
        }
    }

    fun saveLandmarkManually(name: String, statusPrefix: String = "Tracking") {
        viewModelScope.launch {
            when (val result = landmarkManager.saveManual(name)) {
                is LandmarkTrackResult.Tracked -> {
                    cueScheduler.requestLandmarkTracked(result.name)
                    _uiState.update { it.copy(lastVoiceStatus = "$statusPrefix landmark '${result.name}'") }
                }
                is LandmarkTrackResult.AlreadyTracked -> {
                    _uiState.update { it.copy(lastVoiceStatus = "Landmark '${result.name}' is already tracked") }
                }
                LandmarkTrackResult.LocationUnavailable -> {
                    _uiState.update {
                        it.copy(lastVoiceStatus = "Location unavailable. Wait for GPS fix and try again")
                    }
                }
                LandmarkTrackResult.InvalidName -> {
                    _uiState.update { it.copy(lastVoiceStatus = "Invalid landmark name") }
                }
            }
        }
    }

    // --- FPS state (inside the class) ---
    private var framesInWindow = 0
    private var lastWindowStartMs = 0L
    private var lastFrameArrivalMs = 0L

    private var processedFrames = 0
    private var processedWindowStartMs = 0L
    private var detectionFrameIndex = 0L

    private fun onFrameArrived(videoFrame: VideoFrame) {
        val now = SystemClock.elapsedRealtime()
        if (lastWindowStartMs == 0L) lastWindowStartMs = now
        lastFrameArrivalMs = now
        framesInWindow++

        val windowMs = now - lastWindowStartMs
        if (windowMs >= 1_000) {
            val fps = framesInWindow * 1000f / windowMs
            Log.d(
                TAG,
                "STREAM FPS (arrival): %.1f (${videoFrame.width}x${videoFrame.height})".format(fps)
            )
            _uiState.update { it.copy(streamFps = fps) } // add streamFps to StreamUiState
            framesInWindow = 0
            lastWindowStartMs = now
        }
    }

    private fun reportProcessedFps() {
        val now = SystemClock.elapsedRealtime()
        if (processedWindowStartMs == 0L) processedWindowStartMs = now
        processedFrames++

        val dt = now - processedWindowStartMs
        if (dt >= 1_000) {
            val fps = processedFrames * 1000f / dt
            Log.d(TAG, "PIPELINE FPS (decoded+posted): %.1f".format(fps))
            _uiState.update { it.copy(renderFps = fps) } // add renderFps to StreamUiState
            processedFrames = 0
            processedWindowStartMs = now
        }
    }

    private fun extractI420Bytes(videoFrame: VideoFrame): ByteArray {
        val buffer = videoFrame.buffer
        val dataSize = buffer.remaining()
        val bytes = ByteArray(dataSize)
        val originalPosition = buffer.position()
        buffer.get(bytes)
        buffer.position(originalPosition)
        return bytes
    }

    private fun decodeI420FrameToBitmap(i420: ByteArray, width: Int, height: Int): Bitmap {
        val nv21 = convertI420toNV21(i420, width, height)
        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)

        val out = ByteArrayOutputStream().use { stream ->
            image.compressToJpeg(Rect(0, 0, width, height), 50, stream)
            stream.toByteArray()
        }

        return BitmapFactory.decodeByteArray(out, 0, out.size)
    }

    fun startStream() {
        resetTimer()
        streamTimer.startTimer()
        videoJob?.cancel()
        stateJob?.cancel()
        objectCueJob?.cancel()
        startHeadingMonitoring()

        val session =
            try {
                Wearables.startStreamSession(
                    getApplication(),
                    deviceSelector,
                    StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24),
                ).also { streamSession = it }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start stream session", t)
                _uiState.update {
                    it.copy(
                        lastCueEvent = "Stream start failed: ${t.message ?: t::class.java.simpleName}",
                    )
                }
                return
            }

        videoJob = viewModelScope.launch {
            session.videoStream.collect { frame ->
                try {
                    // 1) measure arrival ASAP (cheap)
                    onFrameArrived(frame)

                    val i420 = extractI420Bytes(frame)
                    val rawFrame = RawCameraFrame(width = frame.width, height = frame.height, i420 = i420)

                    val (filteredDetections, trackedObjects, bitmap) = withContext(Dispatchers.Default) {
                        coroutineScope {
                            val bitmapDeferred =
                                async {
                                    decodeI420FrameToBitmap(
                                        i420 = i420,
                                        width = frame.width,
                                        height = frame.height,
                                    )
                                }
                            val rawDetections = detectObjects(rawFrame)
                            val filtered = filterToNavigationClasses(rawDetections)
                            val tracked = objectTracker.update(filtered, SystemClock.elapsedRealtime())
                            Triple(filtered, tracked, bitmapDeferred.await())
                        }
                    }
                    logDetectionsForFrame(frame, filteredDetections)

                    logFrameAndBitmap(frame, bitmap)

                    // 4) post UI update on main (we're already on main in viewModelScope)
                    _uiState.update {
                        it.copy(
                            videoFrame = bitmap,
                            detectedObjects = filteredDetections,
                            trackedObjects = trackedObjects,
                            previewFrameWidth = frame.width,
                            previewFrameHeight = frame.height,
                        )
                    }
                    updateTrackRelativeAzimuths(trackedObjects, frame.width)
                    reportProcessedFps()
                } catch (t: Throwable) {
                    Log.e(TAG, "Frame processing failed; keeping stream alive", t)
                    runCatching { handleVideoFrame(frame) }
                    _uiState.update {
                        it.copy(
                            previewFrameWidth = frame.width,
                            previewFrameHeight = frame.height,
                            lastCueEvent = "Frame processing error: ${t.message ?: t::class.java.simpleName}",
                        )
                    }
                }
            }
        }

        stateJob = viewModelScope.launch {
            session.state.collect { currentState ->
                val prevState = _uiState.value.streamSessionState
                if (currentState != prevState) {
                    Log.d(TAG, "streamSessionState $prevState -> $currentState")
                }
                _uiState.update { it.copy(streamSessionState = currentState) }
                if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
                    stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                }
            }
        }

        objectCueJob = viewModelScope.launch {
            while (isActive) {
                emitSceneCues()
            }
        }
    }

    fun setSceneRefreshRateHz(refreshRateHz: Float) {
        _uiState.update { state ->
            state.copy(
                sceneRefreshRateHz =
                    refreshRateHz.coerceIn(
                        MIN_SCENE_REFRESH_RATE_HZ,
                        MAX_SCENE_REFRESH_RATE_HZ,
                    ),
            )
        }
    }

    fun updateSonificationProfile(
        label: String,
        gain: Float? = null,
        playbackRateScale: Float? = null,
        tiltEq: Float? = null,
    ) {
        val current = ObjectSonificationProfiles.forLabel(label)
        val updated =
            ObjectSonificationProfile(
                gain = (gain ?: current.gain).coerceIn(0.5f, 1.4f),
                playbackRateScale = (playbackRateScale ?: current.playbackRateScale).coerceIn(0.8f, 1.3f),
                tiltEq = (tiltEq ?: current.tiltEq).coerceIn(-0.5f, 0.5f),
            )
        ObjectSonificationProfiles.setProfileForLabel(label, updated)
        _uiState.update {
            it.copy(sonificationProfiles = ObjectSonificationProfiles.snapshotProfiles())
        }
    }

    fun resetSonificationProfiles() {
        ObjectSonificationProfiles.resetAll()
        _uiState.update {
            it.copy(sonificationProfiles = ObjectSonificationProfiles.snapshotProfiles())
        }
    }

    fun playTestSound(label: String) {
        val normalizedLabel = ObjectCueSoundMap.normalizeLabel(label)
        val soundAssetPath = ObjectCueSoundMap.soundForLabel(normalizedLabel)
        val durationMs =
            spatialAudioEngine.playSpatialCue(
                soundAssetPath = soundAssetPath,
                objectLabel = normalizedLabel,
                azimuthDeg = 0f,
                elevationDeg = 0f,
            )
        val debugSummary = spatialAudioEngine.lastDebugSummary() ?: "no-debug"
        audioPlaybackToken += 1L
        val token = audioPlaybackToken
        val indicatorDurationMs = if (durationMs > 0L) durationMs else 700L
        _uiState.update {
            it.copy(
                isAudioPlayingNow = true,
                audioPlayingNowLabel =
                    if (durationMs > 0L) "test-$normalizedLabel" else "test-$normalizedLabel (scheduled)",
                lastCueEvent =
                    "Test sound label=$normalizedLabel route=${_uiState.value.audioRouteLabel} durationMs=$indicatorDurationMs debug=$debugSummary",
            )
        }
        viewModelScope.launch {
            delay(indicatorDurationMs)
            if (audioPlaybackToken == token) {
                _uiState.update {
                    it.copy(
                        isAudioPlayingNow = false,
                        audioPlayingNowLabel = null,
                    )
                }
            }
        }
    }

    private fun startHeadingMonitoring() {
        headingService.start()
    }

    private fun stopHeadingMonitoring() {
        headingService.stop()
    }

    private fun shouldEmitContextNorthCue(
        state: StreamUiState,
        hasRankedObjects: Boolean,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (!state.isNorthCueEnabled) return false
        if (latestHeadingDegrees == null) return false
        if ((nowMs - lastNorthCueAtMs) < NORTH_CUE_COOLDOWN_MS) return false
        return !hasRankedObjects || !state.isObjectSonificationEnabled
    }

    private suspend fun emitNorthCue(trigger: String): Boolean {
        val heading = latestHeadingDegrees ?: return false
        cueScheduler.requestNorthPing()
        val northAzimuth = normalizeSigned180(0f - heading)
        val durationMs =
            spatialAudioEngine.playSpatialCue(
                soundAssetPath = NORTH_CUE_SOUND_ASSET_PATH,
                objectLabel = "north",
                azimuthDeg = northAzimuth,
                elevationDeg = 0f,
            )
        val indicatorDurationMs = if (durationMs > 0L) durationMs else 700L
        audioPlaybackToken += 1L
        val token = audioPlaybackToken
        _uiState.update {
            it.copy(
                isAudioPlayingNow = true,
                audioPlayingNowLabel = "north",
                lastCueEvent = "North cue ($trigger) heading=${"%.1f".format(heading)}",
            )
        }
        viewModelScope.launch {
            delay(indicatorDurationMs)
            if (audioPlaybackToken == token) {
                _uiState.update { state ->
                    state.copy(
                        isAudioPlayingNow = false,
                        audioPlayingNowLabel = null,
                    )
                }
            }
        }
        lastNorthCueAtMs = SystemClock.elapsedRealtime()
        return true
    }

    private fun findTrackedLandmarkByName(name: String): Landmark? {
        val normalizedTarget = name.trim().lowercase()
        if (normalizedTarget.isEmpty()) return null
        return currentLandmarks.firstOrNull { it.name.trim().lowercase() == normalizedTarget }
    }

    private fun resolveOnlyClassLabel(raw: String): String? {
        val cleaned =
            raw.trim()
                .lowercase()
                .removePrefix("the ")
                .removePrefix("a ")
                .removePrefix("an ")
        if (cleaned.isEmpty()) return null
        val normalized = ObjectCueSoundMap.normalizeLabel(cleaned)
        return if (normalized in ObjectSonificationProfiles.supportedLabels()) normalized else null
    }

    private data class LandmarkCueTarget(
        val landmark: Landmark,
        val distanceMeters: Float,
        val relativeBearingDeg: Float,
    )

    private suspend fun emitLandmarkCue(
        landmark: Landmark,
        trigger: String,
    ): Boolean {
        val heading = latestHeadingDegrees ?: return false
        val currentLocation = landmarkManager.currentLocation() ?: return false
        val results = FloatArray(2)
        Location.distanceBetween(
            currentLocation.latitude,
            currentLocation.longitude,
            landmark.latitude,
            landmark.longitude,
            results,
        )
        val distanceMeters = results[0]
        val absoluteBearingDeg = normalize360(results[1])
        val relativeBearingDeg = normalizeSigned180(absoluteBearingDeg - heading)

        cueScheduler.requestLandmarkCue(
            name = landmark.name,
            relativeAzimuthDeg = relativeBearingDeg,
            distanceMeters = distanceMeters,
        )
        val durationMs =
            spatialAudioEngine.playSpatialCue(
                soundAssetPath = LANDMARK_CUE_SOUND_ASSET_PATH,
                objectLabel = landmark.name,
                azimuthDeg = relativeBearingDeg,
                elevationDeg = 0f,
            )
        val indicatorDurationMs = if (durationMs > 0L) durationMs else 700L
        audioPlaybackToken += 1L
        val token = audioPlaybackToken
        _uiState.update {
            it.copy(
                isAudioPlayingNow = true,
                audioPlayingNowLabel = "landmark:${landmark.name}",
                lastCueEvent =
                    "Landmark cue (${trigger}) ${landmark.name} az=${"%.1f".format(relativeBearingDeg)} dist=${"%.1f".format(distanceMeters)}m",
            )
        }
        viewModelScope.launch {
            delay(indicatorDurationMs)
            if (audioPlaybackToken == token) {
                _uiState.update { ui -> ui.copy(isAudioPlayingNow = false, audioPlayingNowLabel = null) }
            }
        }
        lastLandmarkCueAtMs = SystemClock.elapsedRealtime()
        return true
    }

    private suspend fun maybeEmitLandmarkCue(
        state: StreamUiState,
        hasRankedObjects: Boolean,
    ): Boolean {
        if (!state.isLandmarkCueEnabled) return false
        if (currentLandmarks.isEmpty()) return false
        if (hasRankedObjects && state.isObjectSonificationEnabled) return false
        val heading = latestHeadingDegrees ?: return false
        val nowMs = SystemClock.elapsedRealtime()
        if ((nowMs - lastLandmarkCueAtMs) < LANDMARK_CUE_COOLDOWN_MS) return false

        val currentLocation = landmarkManager.currentLocation() ?: return false
        val target =
            selectNearestLandmarkTarget(
                latitude = currentLocation.latitude,
                longitude = currentLocation.longitude,
                headingDegrees = heading,
            ) ?: return false

        return emitLandmarkCue(landmark = target.landmark, trigger = "context")
    }

    private fun selectNearestLandmarkTarget(
        latitude: Double,
        longitude: Double,
        headingDegrees: Float,
    ): LandmarkCueTarget? {
        var best: LandmarkCueTarget? = null
        for (landmark in currentLandmarks) {
            val results = FloatArray(2)
            Location.distanceBetween(
                latitude,
                longitude,
                landmark.latitude,
                landmark.longitude,
                results,
            )
            val distance = results[0]
            val absoluteBearingDeg = normalize360(results[1])
            val relativeBearingDeg = normalizeSigned180(absoluteBearingDeg - headingDegrees)
            val candidate =
                LandmarkCueTarget(
                    landmark = landmark,
                    distanceMeters = distance,
                    relativeBearingDeg = relativeBearingDeg,
                )
            if (best == null || candidate.distanceMeters < best.distanceMeters) {
                best = candidate
            }
        }
        return best
    }

    private fun updateTrackRelativeAzimuths(
        trackedObjects: List<TrackedObject>,
        frameWidth: Int,
    ) {
        if (frameWidth <= 0) {
            trackAzimuthById.clear()
            return
        }
        trackAzimuthById.clear()
        trackedObjects.forEach { tracked ->
            val centerXNorm = (tracked.box.left + tracked.box.right) / 2f
            val relativeAzimuth = (centerXNorm - 0.5f) * CAMERA_HORIZONTAL_FOV_DEGREES
            trackAzimuthById[tracked.trackId] = relativeAzimuth
        }
    }

    private suspend fun emitSceneCues() {
        val state = _uiState.value
        val sceneWindowMs = sceneCueScheduler.sceneWindowMs(state.sceneRefreshRateHz)
        val sceneStartMs = SystemClock.elapsedRealtime()
        val sonificationCandidates =
            state.onlySonificationLabel?.let { onlyLabel ->
                state.trackedObjects.filter { tracked ->
                    ObjectCueSoundMap.normalizeLabel(tracked.label) == onlyLabel
                }
            } ?: state.trackedObjects
        val ranked =
            if (state.isObjectSonificationEnabled) {
                rankingPolicy.rankTrackedObjects(
                    trackedObjects = sonificationCandidates,
                    frameWidth = state.previewFrameWidth,
                    frameHeight = state.previewFrameHeight,
                )
            } else {
                emptyList()
            }
        val plan =
            if (state.isObjectSonificationEnabled) {
                sceneCueScheduler.buildScenePlan(ranked, state.sceneRefreshRateHz)
            } else {
                sceneCueScheduler.buildScenePlan(emptyList(), state.sceneRefreshRateHz)
            }

        _uiState.update {
            it.copy(
                sceneWindowMs = plan.sceneWindowMs,
                maxCommunicableObjectsThisScene = plan.maxCommunicableObjects,
                plannedCueCountThisScene = plan.entries.size,
            )
        }

        if (shouldEmitContextNorthCue(state = state, hasRankedObjects = ranked.isNotEmpty())) {
            emitNorthCue(trigger = "context")
            delay(300L)
        }
        if (maybeEmitLandmarkCue(state = state, hasRankedObjects = ranked.isNotEmpty())) {
            delay(300L)
        }

        if (!state.isObjectSonificationEnabled) {
            val elapsedMs = SystemClock.elapsedRealtime() - sceneStartMs
            val remainingMs = (sceneWindowMs - elapsedMs).coerceAtLeast(0L)
            if (remainingMs > 0) {
                delay(remainingMs)
            }
            return
        }

        // Emit scene cues in spatial order from left to right.
        val orderedEntries =
            plan.entries.sortedBy { entry ->
                trackAzimuthById[entry.candidate.trackId] ?: 0f
            }

        orderedEntries.forEachIndexed { index, entry ->
            val candidate = entry.candidate
            val relativeAzimuth = trackAzimuthById[candidate.trackId] ?: 0f
            val relativeElevation =
                computeRelativeElevationDegrees(
                    boxTopNorm = candidate.box.top,
                    boxBottomNorm = candidate.box.bottom,
                )
            val heading = latestHeadingDegrees ?: 0f
            val worldBearing = normalize360(heading + relativeAzimuth)
            val spatialAzimuth = normalizeSigned180(worldBearing - heading)
            val spatialElevation = relativeElevation
            activeCueWorldBearingDeg = worldBearing
            cueScheduler.requestObjectCue(
                trackId = candidate.trackId,
                label = candidate.label,
                soundAssetPath = candidate.soundAssetPath,
                rank = candidate.rank,
                elevationDeg = spatialElevation,
            )
            val playedDurationMs =
                spatialAudioEngine.playSpatialCue(
                    soundAssetPath = candidate.soundAssetPath,
                    objectLabel = candidate.label,
                    azimuthDeg = spatialAzimuth,
                    elevationDeg = spatialElevation,
                )
            val indicatorDurationMs = if (playedDurationMs > 0L) playedDurationMs else entry.durationMs
            audioPlaybackToken += 1L
            val token = audioPlaybackToken
            _uiState.update {
                it.copy(
                    isAudioPlayingNow = true,
                    audioPlayingNowLabel =
                        if (playedDurationMs > 0L) candidate.label else "${candidate.label} (scheduled)",
                )
            }
            viewModelScope.launch {
                delay(indicatorDurationMs)
                if (audioPlaybackToken == token) {
                    _uiState.update {
                        it.copy(
                            isAudioPlayingNow = false,
                            audioPlayingNowLabel = null,
                        )
                    }
                }
            }
            _uiState.update {
                it.copy(
                    activeObjectCueTrackId = candidate.trackId,
                    activeObjectCueLabel = candidate.label,
                    activeObjectCueSoundAsset = candidate.soundAssetPath,
                    activeObjectRelativeAzimuthDeg = relativeAzimuth,
                    activeObjectRelativeElevationDeg = relativeElevation,
                    activeObjectWorldBearingDeg = worldBearing,
                    activeObjectSpatialAzimuthDeg = spatialAzimuth,
                    activeObjectSpatialElevationDeg = spatialElevation,
                    headingDegrees = heading,
                )
            }
            val hasMore = index < orderedEntries.lastIndex
            if (hasMore) {
                val waitMs = indicatorDurationMs + plan.interCueGapMs
                delay(waitMs)
            }
        }

        val elapsedMs = SystemClock.elapsedRealtime() - sceneStartMs
        val remainingMs = (plan.sceneWindowMs - elapsedMs).coerceAtLeast(0L)
        if (remainingMs > 0) {
            delay(remainingMs)
        }
    }

    private fun computeRelativeElevationDegrees(
        boxTopNorm: Float,
        boxBottomNorm: Float,
    ): Float {
        val centerY = ((boxTopNorm + boxBottomNorm) * 0.5f).coerceIn(0f, 1f)
        val yFromCenter = 0.5f - centerY
        return (yFromCenter * CAMERA_VERTICAL_FOV_DEGREES).coerceIn(-45f, 45f)
    }

    private fun detectObjects(frame: RawCameraFrame): List<DetectedObject> {
        mobileNetDetector?.let { return it.detect(frame) }
        return yoloDetector?.detect(frame).orEmpty()
    }

    private fun logDetectionsForFrame(frame: VideoFrame, detections: List<DetectedObject>) {
        detectionFrameIndex += 1
        val timestamp = Instant.now().toString()
        if (detections.isEmpty()) {
            Log.d(
                TAG,
                "DETECTIONS ts=$timestamp frameIndex=$detectionFrameIndex frame=${frame.width}x${frame.height} count=0",
            )
            return
        }

        val entries =
            detections.joinToString(separator = " | ") { detection ->
                val leftPx = (detection.box.left * frame.width).toInt()
                val topPx = (detection.box.top * frame.height).toInt()
                val rightPx = (detection.box.right * frame.width).toInt()
                val bottomPx = (detection.box.bottom * frame.height).toInt()
                "${detection.label} score=${"%.4f".format(detection.score)} logit=${"%.4f".format(detection.score)} px=[$leftPx,$topPx,$rightPx,$bottomPx]"
            }

        Log.d(
            TAG,
            "DETECTIONS ts=$timestamp frameIndex=$detectionFrameIndex frame=${frame.width}x${frame.height} count=${detections.size} $entries",
        )
    }


//    fun startStream() {
//        resetTimer()
//        streamTimer.startTimer()
//        videoJob?.cancel()
//        stateJob?.cancel()
//        val streamSession = Wearables.startStreamSession(
//            getApplication(),
//            deviceSelector,
//            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24),
//        ).also { streamSession = it }
//        videoJob =
//            viewModelScope.launch { streamSession.videoStream.collect { handleVideoFrame(it) } }
//        stateJob = viewModelScope.launch {
//            streamSession.state.collect { currentState ->
//                val prevState = _uiState.value.streamSessionState
//                _uiState.update { it.copy(streamSessionState = currentState) }
//
//                // navigate back when state transitioned to STOPPED
//                if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
//                    stopStream()
//                    wearablesViewModel.navigateToDeviceSelection()
//                }
//            }
//        }
//    }

    fun stopStream() {
        videoJob?.cancel()
        videoJob = null
        stateJob?.cancel()
        stateJob = null
        objectCueJob?.cancel()
        objectCueJob = null
        stopHeadingMonitoring()
        trackAzimuthById.clear()
        activeCueWorldBearingDeg = null
        latestHeadingDegrees = null
        streamSession?.close()
        streamSession = null
        streamTimer.stopTimer()
        _uiState.update { INITIAL_STATE }
    }

    fun capturePhoto() {
        if (uiState.value.isCapturing) {
            Log.d(TAG, "Photo capture already in progress, ignoring request")
            return
        }

        if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
            Log.d(TAG, "Starting photo capture")
            _uiState.update { it.copy(isCapturing = true) }

            viewModelScope.launch {
                streamSession?.capturePhoto()?.onSuccess { photoData ->
                        Log.d(TAG, "Photo capture successful")
                        handlePhotoData(photoData)
                        _uiState.update { it.copy(isCapturing = false) }
                    }?.onFailure {
                        Log.e(TAG, "Photo capture failed")
                        _uiState.update { it.copy(isCapturing = false) }
                    }
            }
        } else {
            Log.w(
                TAG,
                "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
            )
        }
    }

    fun showShareDialog() {
        _uiState.update { it.copy(isShareDialogVisible = true) }
    }

    fun hideShareDialog() {
        _uiState.update { it.copy(isShareDialogVisible = false) }
    }

    private fun Bitmap.byteCountSafe(): Int {
        // allocationByteCount is the most truthful for memory actually allocated (API 19+)
        return try { allocationByteCount } catch (_: Throwable) { byteCount }
    }

    private fun bytesToMiB(bytes: Long): String =
        String.format("%.2f MiB", bytes / (1024.0 * 1024.0))

    private fun logFrameAndBitmap(frame: Any, bitmap: Bitmap?) {
        // --- Frame logging (works best if frame has width/height or buffer) ---
        val frameClass = frame::class.java.name

        // Try common patterns without knowing your exact frame type
        val frameWidth = runCatching { frame.javaClass.getMethod("getWidth").invoke(frame) as Int }.getOrNull()
        val frameHeight = runCatching { frame.javaClass.getMethod("getHeight").invoke(frame) as Int }.getOrNull()

        // If it has a `buffer` property or method (e.g., ByteBuffer), log remaining/capacity.
        val bufferObj = runCatching {
            // getBuffer()
            frame.javaClass.getMethod("getBuffer").invoke(frame)
        }.getOrNull() ?: runCatching {
            // buffer()
            frame.javaClass.getMethod("buffer").invoke(frame)
        }.getOrNull()

        val bufferInfo = when (bufferObj) {
            is java.nio.ByteBuffer -> "ByteBuffer cap=${bufferObj.capacity()} rem=${bufferObj.remaining()} (${bytesToMiB(bufferObj.capacity().toLong())})"
            is ByteArray -> "ByteArray size=${bufferObj.size} (${bytesToMiB(bufferObj.size.toLong())})"
            else -> bufferObj?.let { "bufferType=${it::class.java.name}" } ?: "noBuffer"
        }

        val frameDim = if (frameWidth != null && frameHeight != null) "${frameWidth}x${frameHeight}" else "unknownDim"

        // --- Bitmap logging ---
        val bmpInfo = if (bitmap == null) {
            "bitmap=null"
        } else {
            val w = bitmap.width
            val h = bitmap.height
            val cfg = bitmap.config
            val bytes = bitmap.byteCountSafe()
            "bitmap=${w}x${h} cfg=$cfg bytes=$bytes (${bytesToMiB(bytes.toLong())})"
        }

        Log.d(TAG, "frameType=$frameClass frameDim=$frameDim $bufferInfo | $bmpInfo")
    }

    fun sharePhoto(bitmap: Bitmap) {
        val context = getApplication<Application>()
        val imagesFolder = File(context.cacheDir, "images")
        try {
            imagesFolder.mkdirs()
            val file = File(imagesFolder, "shared_image.png")
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            }

            val uri =
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.type = "image/png"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val chooser = Intent.createChooser(intent, "Share Image")
            chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(chooser)
        } catch (e: IOException) {
            Log.e("StreamViewModel", "Failed to share photo", e)
        }
    }

    fun cycleTimerMode() {
        streamTimer.cycleTimerMode()
        if (_uiState.value.streamSessionState == StreamSessionState.STREAMING) {
            streamTimer.startTimer()
        }
    }

    fun resetTimer() {
        streamTimer.resetTimer()
    }

    private fun handleVideoFrame(videoFrame: VideoFrame) {
        // VideoFrame contains raw I420 video data in a ByteBuffer
        val buffer = videoFrame.buffer
        val dataSize = buffer.remaining()
        val byteArray = ByteArray(dataSize)

        // Save current position
        val originalPosition = buffer.position()
        buffer.get(byteArray)
        // Restore position
        buffer.position(originalPosition)

        // Convert I420 to NV21 format which is supported by Android's YuvImage
        val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
        val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
        val out = ByteArrayOutputStream().use { stream ->
            image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, stream)
            stream.toByteArray()
        }

        val bitmap = BitmapFactory.decodeByteArray(out, 0, out.size)
        _uiState.update { it.copy(videoFrame = bitmap) }
    }

    // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
    private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(input.size)
        val size = width * height
        val quarter = size / 4

        input.copyInto(output, 0, 0, size) // Y is the same

        for (n in 0 until quarter) {
            output[size + n * 2] = input[size + quarter + n] // V first
            output[size + n * 2 + 1] = input[size + n] // U second
        }
        return output
    }

    private fun handlePhotoData(photo: PhotoData) {
        val capturedPhoto = when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> {
                val byteArray = ByteArray(photo.data.remaining())
                photo.data.get(byteArray)

                // Extract EXIF transformation matrix and apply to bitmap
                val exifInfo = getExifInfo(byteArray)
                val transform = getTransform(exifInfo)
                decodeHeic(byteArray, transform)
            }
        }
        _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
    }

    // HEIC Decoding with EXIF transformation
    private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
        return applyTransform(bitmap, transform)
    }

    private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
        return try {
            ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read EXIF from HEIC", e)
            null
        }
    }

    private fun getTransform(exifInfo: ExifInterface?): Matrix {
        val matrix = Matrix()

        if (exifInfo == null) {
            return matrix // Identity matrix (no transformation)
        }

        when (exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.postRotate(180f)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postScale(1f, -1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.postRotate(90f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.postRotate(270f)
            }

            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> {
                // No transformation needed
            }
        }

        return matrix
    }

    private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
        if (matrix.isIdentity) {
            return bitmap
        }

        return try {
            val transformed =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed != bitmap) {
                bitmap.recycle()
            }
            transformed
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Failed to apply transformation due to memory", e)
            bitmap
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
        headingService.close()
        mobileNetDetector?.close()
        yoloDetector?.close()
        spatialAudioEngine.close()
        stateJob?.cancel()
        timerJob?.cancel()
        streamTimer.cleanup()
    }

    class Factory(
        private val application: Application,
        private val wearablesViewModel: WearablesViewModel,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST", "KotlinGenericsCast") return StreamViewModel(
                    application = application,
                    wearablesViewModel = wearablesViewModel,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
