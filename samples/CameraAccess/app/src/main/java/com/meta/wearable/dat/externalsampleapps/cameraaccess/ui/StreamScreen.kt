/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices and handle photo capture.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tracking.TrackedObject
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlin.math.max
import kotlin.math.min

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val recognitionAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
  val speechRecognizer = remember(recognitionAvailable) {
    if (recognitionAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
  }
  val hasAudioPermission =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED
  var isListening by remember { mutableStateOf(false) }
  var speechStatus by remember { mutableStateOf<String?>(null) }
  var isClassDropdownExpanded by remember { mutableStateOf(false) }
  var selectedClassLabel by remember { mutableStateOf<String?>(null) }
  var manualLandmarkName by remember { mutableStateOf("") }
  var isLetterboxPreview by remember { mutableStateOf(true) }

  LaunchedEffect(streamUiState.sonificationProfiles.keys) {
    val labels = streamUiState.sonificationProfiles.keys.toList()
    if (labels.isEmpty()) {
      selectedClassLabel = null
    } else if (selectedClassLabel == null || selectedClassLabel !in labels) {
      selectedClassLabel = labels.first()
    }
  }

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  DisposableEffect(speechRecognizer) {
    val listener =
        object : RecognitionListener {
          override fun onReadyForSpeech(params: android.os.Bundle?) {
            speechStatus = context.getString(R.string.listening_for_command)
          }

          override fun onBeginningOfSpeech() {}

          override fun onRmsChanged(rmsdB: Float) {}

          override fun onBufferReceived(buffer: ByteArray?) {}

          override fun onEndOfSpeech() {
            isListening = false
          }

          override fun onError(error: Int) {
            isListening = false
            speechStatus =
                when (error) {
                  SpeechRecognizer.ERROR_NETWORK,
                  SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                      context.getString(R.string.offline_speech_unavailable)
                  SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                      context.getString(R.string.record_audio_permission_required)
                  SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.speech_no_match)
                  else -> context.getString(R.string.speech_error_code, error)
                }
          }

          override fun onResults(results: android.os.Bundle?) {
            isListening = false
            val transcript =
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (transcript.isNullOrBlank()) {
              speechStatus = context.getString(R.string.speech_no_match)
              return
            }
            speechStatus = context.getString(R.string.heard_command, transcript)
            streamViewModel.handleVoiceTranscript(transcript)
          }

          override fun onPartialResults(partialResults: android.os.Bundle?) {}

          override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        }

    speechRecognizer?.setRecognitionListener(listener)
    onDispose {
      speechRecognizer?.setRecognitionListener(null)
      speechRecognizer?.destroy()
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val density = LocalDensity.current
      val viewWidthPx = with(density) { maxWidth.toPx() }
      val viewHeightPx = with(density) { maxHeight.toPx() }

      streamUiState.videoFrame?.let { videoFrame ->
        Image(
            bitmap = videoFrame.asImageBitmap(),
            contentDescription = stringResource(R.string.live_stream),
            modifier = Modifier.fillMaxSize(),
            contentScale = if (isLetterboxPreview) ContentScale.Fit else ContentScale.Crop,
        )
      }

      if (streamUiState.previewFrameWidth > 0 && streamUiState.previewFrameHeight > 0) {
        DetectionOverlay(
            trackedObjects = streamUiState.trackedObjects,
            frameWidth = streamUiState.previewFrameWidth,
            frameHeight = streamUiState.previewFrameHeight,
            viewWidthPx = viewWidthPx,
            viewHeightPx = viewHeightPx,
            isLetterboxPreview = isLetterboxPreview,
            modifier = Modifier.fillMaxSize(),
        )
      }
    }

    if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
      Column(
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .fillMaxWidth()
                  .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val voiceIndicatorText =
            when {
              !recognitionAvailable -> stringResource(R.string.voice_indicator_offline_unavailable)
              !hasAudioPermission -> stringResource(R.string.voice_indicator_permission_required)
              isListening -> stringResource(R.string.voice_indicator_listening)
              else -> stringResource(R.string.voice_indicator_ready)
            }
        val voiceIndicatorColor =
            when {
              !recognitionAvailable -> Color.Red
              !hasAudioPermission -> Color.Yellow
              isListening -> Color.Green
              else -> Color.White
            }
        Text(text = voiceIndicatorText, color = voiceIndicatorColor)

        Button(
            onClick = {
              if (!recognitionAvailable) {
                speechStatus = context.getString(R.string.offline_speech_unavailable)
                return@Button
              }

              val hasAudioPermission =
                  ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                      PackageManager.PERMISSION_GRANTED
              if (!hasAudioPermission) {
                speechStatus = context.getString(R.string.record_audio_permission_required)
                return@Button
              }

              if (isListening) {
                speechRecognizer?.stopListening()
                isListening = false
                return@Button
              }

              val intent =
                  Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                  }
              isListening = true
              speechRecognizer?.startListening(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
              text =
                  if (isListening) {
                    stringResource(R.string.stop_listening)
                  } else {
                    stringResource(R.string.listen_for_command)
                  }
          )
        }

        speechStatus?.let { status ->
          Text(text = status, color = Color.White)
        }
        streamUiState.lastVoiceStatus?.let { status ->
          Text(text = status, color = Color.White)
        }
        streamUiState.lastCueEvent?.let { cue ->
          Text(text = cue, color = Color.White)
        }
        Text(
            text =
                "Stream=${streamUiState.streamSessionState} " +
                    "Frame=${streamUiState.previewFrameWidth}x${streamUiState.previewFrameHeight} " +
                    "FPS in/out=${"%.1f".format(streamUiState.streamFps)}/${"%.1f".format(streamUiState.renderFps)}",
            color = Color.White,
        )
        Text(
            text =
                "Sonification enabled=${streamUiState.isObjectSonificationEnabled} " +
                    "Only=${streamUiState.onlySonificationLabel ?: "all"} " +
                    "Tracked=${streamUiState.trackedObjects.size}",
            color = Color.White,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Button(
              onClick = {
                streamViewModel.setSceneRefreshRateHz(streamUiState.sceneRefreshRateHz - 0.1f)
              }
          ) {
            Text("-")
          }
          Text(
              text =
                  "Scene Hz: ${"%.1f".format(streamUiState.sceneRefreshRateHz)} (${streamUiState.sceneWindowMs}ms)",
              color = Color.White,
          )
          Button(
              onClick = {
                streamViewModel.setSceneRefreshRateHz(streamUiState.sceneRefreshRateHz + 0.1f)
              }
          ) {
            Text("+")
          }
        }
        Text(
            text =
                "Scene capacity: ${streamUiState.maxCommunicableObjectsThisScene}, planned cues: ${streamUiState.plannedCueCountThisScene}",
            color = Color.White,
        )
        Text(
            text =
                "Audio route: ${streamUiState.audioRouteLabel} " +
                    if (streamUiState.isBluetoothAudioRouteActive) "(BT active)" else "(BT inactive)",
            color = if (streamUiState.isBluetoothAudioRouteActive) Color.Green else Color.Yellow,
        )
        Button(
            onClick = { isLetterboxPreview = !isLetterboxPreview },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
              text =
                  if (isLetterboxPreview) {
                    "Preview Mode: Full View (Letterbox)"
                  } else {
                    "Preview Mode: Fill Screen (Crop)"
                  },
          )
        }
        Text(
            text =
                if (streamUiState.isAudioPlayingNow) {
                  "Audio playing now: ${streamUiState.audioPlayingNowLabel ?: "object"}"
                } else {
                  "Audio playing now: idle"
                },
            color = if (streamUiState.isAudioPlayingNow) Color.Green else Color.White,
        )
        Text(text = "Sonification Profiles", color = Color.White)
        Button(
            onClick = { isClassDropdownExpanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
              text =
                  selectedClassLabel?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                  } ?: "Select class",
          )
        }
        DropdownMenu(
            expanded = isClassDropdownExpanded,
            onDismissRequest = { isClassDropdownExpanded = false },
        ) {
          streamUiState.sonificationProfiles.keys.forEach { label ->
            DropdownMenuItem(
                text = {
                  Text(
                      text =
                          label.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase() else it.toString()
                          },
                  )
                },
                onClick = {
                  selectedClassLabel = label
                  isClassDropdownExpanded = false
                },
            )
          }
        }

        val selectedProfile =
            selectedClassLabel?.let { label -> streamUiState.sonificationProfiles[label]?.let { label to it } }
        selectedProfile?.let { (label, profile) ->
          Text(
              text = label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
              color = Color.White,
          )
          Text(text = "Gain: ${"%.2f".format(profile.gain)}", color = Color.White)
          Slider(
              value = profile.gain,
              onValueChange = { value ->
                streamViewModel.updateSonificationProfile(label = label, gain = value)
              },
              valueRange = 0.5f..1.4f,
              modifier = Modifier.fillMaxWidth(),
          )
          Text(text = "Rate: ${"%.2f".format(profile.playbackRateScale)}", color = Color.White)
          Slider(
              value = profile.playbackRateScale,
              onValueChange = { value ->
                streamViewModel.updateSonificationProfile(
                    label = label,
                    playbackRateScale = value,
                )
              },
              valueRange = 0.8f..1.3f,
              modifier = Modifier.fillMaxWidth(),
          )
          Text(text = "Tilt EQ: ${"%.2f".format(profile.tiltEq)}", color = Color.White)
          Slider(
              value = profile.tiltEq,
              onValueChange = { value ->
                streamViewModel.updateSonificationProfile(label = label, tiltEq = value)
              },
              valueRange = -0.5f..0.5f,
              modifier = Modifier.fillMaxWidth(),
          )
        } ?: Text(text = "No class profile available", color = Color.White)
        Button(
            onClick = { streamViewModel.resetSonificationProfiles() },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(text = "Reset Sound Profiles")
        }
        Button(
            onClick = { selectedClassLabel?.let { streamViewModel.playTestSound(it) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(text = "Test Sound")
        }
        streamUiState.activeObjectCueTrackId?.let { trackId ->
          val label = streamUiState.activeObjectCueLabel ?: "unknown"
          val sound = streamUiState.activeObjectCueSoundAsset ?: "n/a"
          Text(text = "Active cue: #$trackId $label -> $sound", color = Color.White)
          Text(
              text =
                  "Heading=${streamUiState.headingDegrees?.let { "%.1f".format(it) } ?: "n/a"} deg, " +
                      "RelAz=${streamUiState.activeObjectRelativeAzimuthDeg?.let { "%.1f".format(it) } ?: "n/a"} deg, " +
                      "SpatialAz=${streamUiState.activeObjectSpatialAzimuthDeg?.let { "%.1f".format(it) } ?: "n/a"} deg",
              color = Color.White,
          )
        }
        if (streamUiState.trackedLandmarks.isNotEmpty()) {
          Text(
              text =
                  stringResource(
                      R.string.tracked_landmarks_label,
                      streamUiState.trackedLandmarks.joinToString(),
                  ),
              color = Color.White,
          )
        }
        OutlinedTextField(
            value = manualLandmarkName,
            onValueChange = { manualLandmarkName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.save_landmark_label)) },
            singleLine = true,
        )
        Button(
            onClick = {
              val name = manualLandmarkName.trim()
              if (name.isNotEmpty()) {
                streamViewModel.saveLandmarkManually(name)
                manualLandmarkName = ""
              }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.save_landmark_button))
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          SwitchButton(
              label = stringResource(R.string.stop_stream_button_title),
              onClick = {
                streamViewModel.stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              },
              isDestructive = true,
              modifier = Modifier.weight(1f),
          )

          // Timer button
          TimerButton(
              timerMode = streamUiState.timerMode,
              onClick = { streamViewModel.cycleTimerMode() },
          )
          // Photo capture button
          CaptureButton(
              onClick = { streamViewModel.capturePhoto() },
          )
        }
      }
    }

    // Countdown timer display
    streamUiState.remainingTimeSeconds?.let { seconds ->
      val minutes = seconds / 60
      val remainingSeconds = seconds % 60
      Text(
          text = stringResource(id = R.string.time_remaining, minutes, remainingSeconds),
          color = Color.White,
          modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
          textAlign = TextAlign.Center,
      )
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
}

@Composable
private fun DetectionOverlay(
    trackedObjects: List<TrackedObject>,
    frameWidth: Int,
    frameHeight: Int,
    viewWidthPx: Float,
    viewHeightPx: Float,
    isLetterboxPreview: Boolean,
    modifier: Modifier = Modifier,
) {
  val paint = remember {
    android.graphics.Paint().apply {
      color = android.graphics.Color.WHITE
      textSize = 34f
      isAntiAlias = true
      style = android.graphics.Paint.Style.FILL
    }
  }

  val scale =
      if (isLetterboxPreview) {
        min(viewWidthPx / frameWidth.toFloat(), viewHeightPx / frameHeight.toFloat())
      } else {
        max(viewWidthPx / frameWidth.toFloat(), viewHeightPx / frameHeight.toFloat())
      }
  val scaledFrameWidth = frameWidth * scale
  val scaledFrameHeight = frameHeight * scale
  val xOffset = (viewWidthPx - scaledFrameWidth) / 2f
  val yOffset = (viewHeightPx - scaledFrameHeight) / 2f

  Canvas(modifier = modifier) {
    trackedObjects.forEach { tracked ->
      val left = (tracked.box.left * scaledFrameWidth) + xOffset
      val top = (tracked.box.top * scaledFrameHeight) + yOffset
      val right = (tracked.box.right * scaledFrameWidth) + xOffset
      val bottom = (tracked.box.bottom * scaledFrameHeight) + yOffset

      drawRect(
          color = Color.Cyan,
          topLeft = androidx.compose.ui.geometry.Offset(left, top),
          size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
          style = Stroke(width = 3f),
      )

      drawContext.canvas.nativeCanvas.drawText(
          "#${tracked.trackId} ${tracked.label}",
          left + 6f,
          top + 34f,
          paint,
      )
    }
  }
}
