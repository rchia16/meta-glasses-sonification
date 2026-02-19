/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamUiState - DAT Camera Streaming UI State
//
// This data class manages UI state for camera streaming operations using the DAT API.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.ObjectSonificationProfile
import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.DetectedObject
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tracking.TrackedObject

data class StreamUiState(
    val streamSessionState: StreamSessionState = StreamSessionState.STOPPED,
    val videoFrame: Bitmap? = null,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val isCapturing: Boolean = false,
    val timerMode: TimerMode = TimerMode.UNLIMITED,
    val remainingTimeSeconds: Long? = null,
    val streamFps: Float = 0f,
    val renderFps: Float = 0f,
    val trackedLandmarks: List<String> = emptyList(),
    val detectedObjects: List<DetectedObject> = emptyList(),
    val trackedObjects: List<TrackedObject> = emptyList(),
    val activeObjectCueTrackId: Long? = null,
    val activeObjectCueLabel: String? = null,
    val activeObjectCueSoundAsset: String? = null,
    val headingDegrees: Float? = null,
    val activeObjectRelativeAzimuthDeg: Float? = null,
    val activeObjectRelativeElevationDeg: Float? = null,
    val activeObjectWorldBearingDeg: Float? = null,
    val activeObjectSpatialAzimuthDeg: Float? = null,
    val activeObjectSpatialElevationDeg: Float? = null,
    val previewFrameWidth: Int = 0,
    val previewFrameHeight: Int = 0,
    val lastVoiceCommand: String? = null,
    val lastVoiceStatus: String? = null,
    val lastCueEvent: String? = null,
    val isObjectSonificationEnabled: Boolean = true,
    val onlySonificationLabel: String? = null,
    val isNorthCueEnabled: Boolean = false,
    val isLandmarkCueEnabled: Boolean = true,
    val isHapticsEnabled: Boolean = true,
    val isVoiceLandmarkInputEnabled: Boolean = true,
    val sceneRefreshRateHz: Float = 1.0f,
    val sceneWindowMs: Long = 1000L,
    val maxCommunicableObjectsThisScene: Int = 0,
    val plannedCueCountThisScene: Int = 0,
    val isObjectFirstPreemptionEnabled: Boolean = true,
    val sonificationProfiles: Map<String, ObjectSonificationProfile> = emptyMap(),
    val isBluetoothAudioRouteActive: Boolean = false,
    val audioRouteLabel: String = "Unknown route",
    val isAudioPlayingNow: Boolean = false,
    val audioPlayingNowLabel: String? = null,
)
