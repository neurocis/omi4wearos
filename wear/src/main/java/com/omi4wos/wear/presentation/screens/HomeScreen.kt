package com.omi4wos.wear.presentation.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.omi4wos.wear.R
import com.omi4wos.wear.service.StandaloneAudioCaptureService

// State colours — drive both the button and the status label
private val ColorIdle    = Color(0xFF3A3A4A) // Dark slate — not recording
private val ColorListen  = Color(0xFF5C6BC0) // Indigo — recording, silence
private val ColorSpeech  = Color(0xFF43A047) // Green — speech detected
private val ColorNoPerms = Color(0xFFB71C1C) // Deep red — permission missing

@Composable
fun HomeScreen(
    onAboutClick: () -> Unit = {},
    onSetupClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic  = LocalHapticFeedback.current

    val isRecording      by StandaloneAudioCaptureService.isRecording.collectAsState()
    val isSpeechDetected by StandaloneAudioCaptureService.isSpeechDetected.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Derived state ──────────────────────────────────────────────────────
    val buttonColor = when {
        !hasPermission   -> ColorNoPerms
        isSpeechDetected -> ColorSpeech
        isRecording      -> ColorListen
        else             -> ColorIdle
    }

    val haloAlpha = if (isRecording) 0.20f else 0f

    // ── Layout ─────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // omi4wearOS logo
            Image(
                painter = painterResource(R.drawable.omi4wearos_logo_title),
                contentDescription = "omi4wearOS",
                modifier = Modifier
                    .height(22.dp)
                    .padding(bottom = 2.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Mic button with outer glow halo
            Box(contentAlignment = Alignment.Center) {

                // Halo ring — soft glow when recording
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .background(buttonColor.copy(alpha = haloAlpha), CircleShape)
                )

                // Main button — circle with mic icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(buttonColor, CircleShape)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            if (hasPermission) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                toggleRecording(context, isRecording)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = if (isRecording) "Stop recording" else "Start recording",
                        modifier = Modifier.size(32.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary status label
            Text(
                text = when {
                    !hasPermission   -> "No permission"
                    isSpeechDetected -> "Recording"
                    isRecording      -> "Listening…"
                    else             -> "Tap to start"
                },
                style = MaterialTheme.typography.body1,
                fontWeight = if (isSpeechDetected) FontWeight.Bold else FontWeight.Normal,
                color = if (isRecording || isSpeechDetected)
                    buttonColor
                else
                    MaterialTheme.colors.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom links — Setup | About
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Setup",
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onSetupClick() }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "About",
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onAboutClick() }
                )
            }
        }
    }
}

private fun toggleRecording(context: Context, currentlyRecording: Boolean) {
    val intent = Intent(context, StandaloneAudioCaptureService::class.java)
    if (currentlyRecording) {
        intent.action = StandaloneAudioCaptureService.ACTION_STOP
        context.startService(intent)
    } else {
        intent.action = StandaloneAudioCaptureService.ACTION_START
        ContextCompat.startForegroundService(context, intent)
    }
}
