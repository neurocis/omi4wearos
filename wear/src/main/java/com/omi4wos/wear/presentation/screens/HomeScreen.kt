package com.omi4wos.wear.presentation.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.scale
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
import androidx.wear.compose.material.TimeText
import com.omi4wos.wear.R
import com.omi4wos.wear.service.AudioCaptureService

// State colours — drive both the button and the status label
private val ColorIdle    = Color(0xFF3A3A4A) // Dark slate — not recording
private val ColorListen  = Color(0xFF5C6BC0) // Indigo — recording, silence
private val ColorSpeech  = Color(0xFF43A047) // Green — speech detected
private val ColorNoPerms = Color(0xFFB71C1C) // Deep red — permission missing

@Composable
fun HomeScreen(onAboutClick: () -> Unit = {}) {
    val context = LocalContext.current
    val haptic  = LocalHapticFeedback.current

    val isRecording     by AudioCaptureService.isRecording.collectAsState()
    val isSpeechDetected by AudioCaptureService.isSpeechDetected.collectAsState()
    val phoneConnected  by AudioCaptureService.isPhoneConnected.collectAsState()

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
    val buttonColor by animateColorAsState(
        targetValue = when {
            !hasPermission   -> ColorNoPerms
            isSpeechDetected -> ColorSpeech
            isRecording      -> ColorListen
            else             -> ColorIdle
        },
        animationSpec = tween(400),
        label = "button_color"
    )

    // Halo fades in when recording starts
    val haloAlpha by animateFloatAsState(
        targetValue = if (isRecording) 0.20f else 0f,
        animationSpec = tween(600),
        label = "halo_alpha"
    )

    // Pulse: fast + strong during speech, slow + subtle while listening, still at idle
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue  = when {
            isSpeechDetected -> 1.18f
            isRecording      -> 1.06f
            else             -> 1.00f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isSpeechDetected) 380 else 900,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // ── Layout ─────────────────────────────────────────────────────────────
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
                // top=28dp pushes the centre of the content below the TimeText
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // Mic button with outer glow halo
            Box(contentAlignment = Alignment.Center) {

                // Halo ring — soft glow that appears when recording
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .background(buttonColor.copy(alpha = haloAlpha), CircleShape)
                )

                // Main button — pulsing circle with mic icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(pulseScale)
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

            Spacer(modifier = Modifier.height(14.dp))

            // Primary status label — colour-matched to button state when active
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

            Spacer(modifier = Modifier.height(6.dp))

            // Phone connection — small coloured dot + compact text
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = if (phoneConnected) Color(0xFF4CAF50) else Color(0xFF555555),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (phoneConnected) "Phone connected" else "Phone disconnected",
                    fontSize = 10.sp,
                    color = if (phoneConnected)
                        Color(0xFF4CAF50)
                    else
                        MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                )
                // Mirror the dot+spacer width so the text itself is optically centred
                Spacer(modifier = Modifier.width(11.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // About — subtle tap target, no chrome
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
