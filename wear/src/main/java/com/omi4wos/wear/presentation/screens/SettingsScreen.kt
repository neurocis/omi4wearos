package com.omi4wos.wear.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.omi4wos.wear.network.StandaloneOmiConfig
import com.omi4wos.wear.network.UploadLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    onSetupClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val config  = remember { StandaloneOmiConfig(context) }
    val log     = remember { UploadLog(context) }

    val creds   = remember { config.load() }
    val entries = remember { log.getEntries() }

    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Auth status ──────────────────────────────────────────────
            item {
                Text(
                    text = "Auth",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            item {
                Text(
                    text = if (creds.isConfigured) "✓  Credentials set" else "✗  Not configured",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (creds.isConfigured) Color(0xFF43A047) else Color(0xFFE53935),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Button(
                    onClick = onSetupClick,
                    modifier = Modifier.size(width = 120.dp, height = 36.dp)
                ) {
                    Text(
                        text = if (creds.isConfigured) "Re-run Setup" else "Setup",
                        fontSize = 11.sp
                    )
                }
            }

            // ── Upload log ───────────────────────────────────────────────
            item {
                Text(
                    text = "Upload Log",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    textAlign = TextAlign.Center
                )
            }

            if (entries.isEmpty()) {
                item {
                    Text(
                        text = "No uploads yet",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                items(entries) { entry ->
                    val time = timeFmt.format(Date(entry.timestampMs))
                    val kb   = entry.bytes / 1024
                    val icon = if (entry.success) "↑" else "✗"
                    val color = if (entry.success) Color(0xFF43A047) else Color(0xFFE53935)
                    Text(
                        text = "$icon  $time  ${kb}KB  (${entry.sessions}s)",
                        fontSize = 11.sp,
                        color = color,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Instructions ─────────────────────────────────────────────
            item {
                Text(
                    text = "How to get credentials",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
            item {
                Text(
                    text = "1. On a computer, open app.omi.me\n" +
                           "2. Log out, then sign in with Google\n" +
                           "3. Open DevTools (F12)\n" +
                           "4. Application → Storage → IndexedDB → firebaseLocalStorageDb → FirebaseLocalStorage → expand firebase:authUser value\n\n" +
                           "From stsTokenManager:\n" +
                           "  • accessToken → Firebase Token (eyJ...)\n" +
                           "  • refreshToken → Refresh Token (AMf...)\n\n" +
                           "Also in firebase:authUser:\n" +
                           "  • apiKey → Web API Key (AIzaSy...)\n\n" +
                           "Tap 'Setup' above, scan the QR or type the IP:port into any browser on the same WiFi.",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}
