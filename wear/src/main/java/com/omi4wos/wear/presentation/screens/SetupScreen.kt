package com.omi4wos.wear.presentation.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.omi4wos.wear.setup.QrCodeGenerator
import com.omi4wos.wear.setup.SetupServer

private enum class SetupState { STARTING, WAITING_FOR_SCAN, SUCCESS, NO_WIFI }

/**
 * Displays a QR code pointing to the watch's local NanoHTTPD setup server.
 *
 * Flow:
 *   1. Watch starts [SetupServer] on port 8080.
 *   2. QR code shows `http://<watch-wifi-ip>:8080`.
 *   3. User scans on any phone → pastes Firebase Web API Key, Firebase Token,
 *      and Refresh Token (same values as the companion app Settings screen).
 *   4. Credentials are stored on the watch; screen shows success.
 */
@Composable
fun SetupScreen(onDone: () -> Unit = {}) {
    val context = LocalContext.current

    var state    by remember { mutableStateOf(SetupState.STARTING) }
    var watchUrl by remember { mutableStateOf("") }
    var server   by remember { mutableStateOf<SetupServer?>(null) }

    DisposableEffect(Unit) {
        val ip = SetupServer.getWifiIpAddress()
        if (ip == null) {
            state = SetupState.NO_WIFI
        } else {
            watchUrl = "http://$ip:${SetupServer.PORT}"
            val s = SetupServer(context) { state = SetupState.SUCCESS }
            s.start()
            server = s
            state = SetupState.WAITING_FOR_SCAN
        }
        onDispose {
            server?.stop()
            server = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        when (state) {

            SetupState.STARTING -> CircularProgressIndicator()

            SetupState.NO_WIFI -> Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Connect to WiFi first, then open Setup again.",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDone, modifier = Modifier.size(48.dp)) {
                    Text("OK", fontSize = 11.sp)
                }
            }

            SetupState.WAITING_FOR_SCAN -> {
                val qrBitmap = remember(watchUrl) { QrCodeGenerator.generate(watchUrl, 180) }
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "Scan to setup", fontSize = 12.sp, color = MaterialTheme.colors.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Setup QR code",
                        modifier = Modifier.size(150.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Open on your phone",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            SetupState.SUCCESS -> Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "✓ Connected!",
                    fontSize = 14.sp,
                    color = androidx.compose.ui.graphics.Color(0xFF43A047),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Watch uploads\ndirectly to Omi.",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDone, modifier = Modifier.size(48.dp)) {
                    Text("OK", fontSize = 11.sp)
                }
            }
        }
    }
}
