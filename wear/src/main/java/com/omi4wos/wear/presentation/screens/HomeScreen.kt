package com.omi4wos.wear.presentation.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.omi4wos.wear.service.AudioCaptureService

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val isRecording by AudioCaptureService.isRecording.collectAsState()
    val isSpeechDetected by AudioCaptureService.isSpeechDetected.collectAsState()
    val phoneConnected by AudioCaptureService.isPhoneConnected.collectAsState()

    val hasPermission = remember {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        TimeText()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Speech detection indicator
            SpeechIndicator(
                isRecording = isRecording,
                isSpeechDetected = isSpeechDetected
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status text
            Text(
                text = when {
                    !hasPermission -> "No mic permission"
                    !isRecording -> "Tap to start"
                    isSpeechDetected -> "Speech detected"
                    else -> "Listening…"
                },
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Phone connection status
            Text(
                text = if (phoneConnected) "📱 Connected" else "📱 Disconnected",
                style = MaterialTheme.typography.caption3,
                color = if (phoneConnected) Color(0xFF4CAF50) else Color(0xFF757575),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Start/Stop button
            Button(
                onClick = {
                    if (hasPermission) {
                        toggleRecording(context, isRecording)
                    }
                },
                modifier = Modifier.size(ButtonDefaults.LargeButtonSize),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isRecording)
                        MaterialTheme.colors.error
                    else
                        MaterialTheme.colors.primary
                )
            ) {
                Text(
                    text = if (isRecording) "Stop" else "Start",
                    style = MaterialTheme.typography.button
                )
            }
        }
    }
}

@Composable
private fun SpeechIndicator(
    isRecording: Boolean,
    isSpeechDetected: Boolean
) {
    val targetColor by animateColorAsState(
        targetValue = when {
            isSpeechDetected -> Color(0xFF4CAF50) // Green for speech
            isRecording -> Color(0xFF6C63FF) // Purple for listening
            else -> Color(0xFF424242) // Gray for idle
        },
        animationSpec = tween(300),
        label = "indicator_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isSpeechDetected) 1.3f else if (isRecording) 1.1f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = Modifier
            .size(24.dp)
            .scale(scale)
            .background(targetColor, CircleShape)
    )
}

private fun toggleRecording(context: Context, currentlyRecording: Boolean) {
    val intent = Intent(context, AudioCaptureService::class.java)
    if (currentlyRecording) {
        intent.action = AudioCaptureService.ACTION_STOP
        context.startService(intent)
    } else {
        intent.action = AudioCaptureService.ACTION_START
        ContextCompat.startForegroundService(context, intent)
    }
}
