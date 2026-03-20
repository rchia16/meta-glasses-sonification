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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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

@OptIn(ExperimentalFoundationApi::class)
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
  val haptic = LocalHapticFeedback.current
  val recognitionAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
  val speechRecognizer = remember(recognitionAvailable) {
    if (recognitionAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
  }
  val audioInteractionSource = remember { MutableInteractionSource() }

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  DisposableEffect(speechRecognizer) {
    val listener =
        object : RecognitionListener {
          override fun onReadyForSpeech(params: android.os.Bundle?) {}

          override fun onBeginningOfSpeech() {}

          override fun onRmsChanged(rmsdB: Float) {}

          override fun onBufferReceived(buffer: ByteArray?) {}

          override fun onEndOfSpeech() {}

          override fun onError(error: Int) {}

          override fun onResults(results: android.os.Bundle?) {
            val transcript =
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (transcript.isNullOrBlank()) return
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
    Column(modifier = Modifier.fillMaxSize()) {
      Surface(
          modifier =
              Modifier.weight(1f)
                  .fillMaxWidth()
                  .combinedClickable(
                      interactionSource = audioInteractionSource,
                      indication = null,
                      onClick = {
                        if (streamUiState.isHapticsEnabled) {
                          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        streamViewModel.setNorthCueEnabled(!streamUiState.isNorthCueEnabled)
                      },
                      onLongClick = {
                        if (streamUiState.isHapticsEnabled) {
                          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        streamViewModel.toggleNorthAudioMode()
                      },
                  ),
          color = if (streamUiState.isNorthCueEnabled) Color(0xFF214F36) else Color(0xFF2B2B2B),
      ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
          Text(
              text =
                  "Audio Compass\n" +
                      if (streamUiState.isNorthCueEnabled) "On" else "Off" +
                      "\nMode: ${streamUiState.northAudioMode.name.lowercase().replaceFirstChar { it.titlecase() }}",
              color = Color.White,
              textAlign = TextAlign.Center,
          )
        }
      }
      Surface(
          modifier =
              Modifier.weight(1f)
                  .fillMaxWidth()
                  .combinedClickable(
                      onClick = {
                        if (streamUiState.isHapticsEnabled) {
                          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        streamViewModel.setNorthHapticEnabled(!streamUiState.isNorthHapticEnabled)
                      },
                      onLongClick = {
                        if (streamUiState.isHapticsEnabled) {
                          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        streamViewModel.toggleNorthHapticMode()
                      }),
          color = if (streamUiState.isNorthHapticEnabled) Color(0xFF5A3E1B) else Color(0xFF1E3A5F),
      ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
          Text(
              text =
                  "North Haptics\n" +
                      if (streamUiState.isNorthHapticEnabled) "On" else "Off" +
                      "\nMode: ${streamUiState.northHapticMode.name.lowercase().replaceFirstChar { it.titlecase() }}" +
                      "\nErratic away from north\nCalm near north",
              color = Color.White,
              textAlign = TextAlign.Center,
          )
        }
      }
    }
    if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
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
