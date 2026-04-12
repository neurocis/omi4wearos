package com.omi4wos.wear.setup

import android.content.Context
import android.util.Log
import com.omi4wos.wear.network.StandaloneOmiConfig
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * Local HTTP server that runs on the watch during the one-time credential setup.
 *
 * Flow:
 *   1. Watch starts this server on port 8080 and shows a QR code with its WiFi URL.
 *   2. User scans the QR code on any phone — browser opens the setup form.
 *   3. The form calls Firebase REST APIs directly (signInWithPassword), then POSTs
 *      the resulting tokens back to this server.
 *   4. Watch stores the tokens via [StandaloneOmiConfig] and calls [onCredentialsReceived].
 *
 * The Firebase Web API Key, email, and password never leave the local network —
 * they go directly from the browser to Firebase, and the tokens come back to this server.
 */
class SetupServer(
    private val context: Context,
    private val onCredentialsReceived: () -> Unit
) : NanoHTTPD(PORT) {

    private val config = StandaloneOmiConfig(context)

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET  && session.uri == "/" -> serveForm()
            session.method == Method.POST && session.uri == "/credentials" -> handleCredentials(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    // ── Setup form ────────────────────────────────────────────────────────────

    private fun serveForm(): Response {
        val html = """
<!DOCTYPE html>
<html><head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>omi4wearOS Setup</title>
  <style>
    body{font-family:sans-serif;max-width:440px;margin:40px auto;padding:0 20px;background:#111;color:#eee}
    h1{font-size:1.4em;color:#7c9eff;margin-bottom:4px}
    p{color:#aaa;font-size:.9em;margin-top:4px}
    label{display:block;margin-top:18px;font-size:.85em;color:#aaa}
    .hint{font-size:.75em;color:#666;margin-top:3px;line-height:1.4}
    input{width:100%;padding:10px;margin-top:5px;border:1px solid #444;border-radius:6px;
          background:#222;color:#eee;font-size:1em;box-sizing:border-box}
    button{margin-top:26px;width:100%;padding:13px;background:#5c6bc0;color:#fff;
           border:none;border-radius:8px;font-size:1em;cursor:pointer}
    button:disabled{background:#333;color:#777;cursor:default}
    #status{margin-top:14px;font-size:.9em;min-height:1.5em}
  </style>
</head>
<body>
  <h1>omi4wearOS Setup</h1>
  <p>Connect your watch to your Omi account. Credentials are sent directly to Firebase — they never touch a third-party server.</p>

  <label>Firebase Web API Key
    <div class="hint">How to find it: open <strong>app.omi.me</strong> → DevTools (F12) → Network tab → filter <code>googleapis.com</code> → copy the <code>key=</code> value from any request URL.</div>
  </label>
  <input type="text" id="apiKey" placeholder="AIzaSy..." autocomplete="off" spellcheck="false">

  <label>Omi Email</label>
  <input type="email" id="email" placeholder="you@example.com">

  <label>Omi Password</label>
  <input type="password" id="password" placeholder="••••••••">

  <button id="btn" onclick="doSetup()">Connect Watch</button>
  <div id="status"></div>

<script>
async function doSetup() {
  const btn    = document.getElementById('btn');
  const status = document.getElementById('status');
  const apiKey = document.getElementById('apiKey').value.trim();
  const email  = document.getElementById('email').value.trim();
  const pass   = document.getElementById('password').value;

  if (!apiKey || !email || !pass) { status.textContent = 'Please fill in all fields.'; return; }

  btn.disabled = true;
  status.textContent = 'Signing in to Omi…';

  try {
    const signRes = await fetch(
      'https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=' + encodeURIComponent(apiKey),
      {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({email, password: pass, returnSecureToken: true})
      }
    );
    const signData = await signRes.json();
    if (!signRes.ok) {
      status.textContent = 'Sign-in failed: ' + (signData.error?.message || 'Unknown error');
      btn.disabled = false;
      return;
    }

    status.textContent = 'Sending credentials to watch…';
    const sendRes = await fetch('/credentials', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        firebaseWebApiKey: apiKey,
        idToken:           signData.idToken,
        refreshToken:      signData.refreshToken,
        userId:            signData.localId,
        expiresIn:         signData.expiresIn || '3600'
      })
    });

    if (sendRes.ok) {
      status.innerHTML = '✅ <strong>Watch connected!</strong> You can close this page.';
    } else {
      status.textContent = 'Error sending to watch — please retry.';
      btn.disabled = false;
    }
  } catch(e) {
    status.textContent = 'Network error: ' + e.message;
    btn.disabled = false;
  }
}
</script>
</body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    // ── Credential receiver ───────────────────────────────────────────────────

    private fun handleCredentials(session: IHTTPSession): Response {
        return try {
            val bodyMap = mutableMapOf<String, String>()
            session.parseBody(bodyMap)
            val raw = bodyMap["postData"] ?: return error("Empty body")
            val json = JSONObject(raw)

            val apiKey       = json.optString("firebaseWebApiKey")
            val idToken      = json.optString("idToken")
            val refreshToken = json.optString("refreshToken")
            val userId       = json.optString("userId")
            val expiresIn    = json.optLong("expiresIn", 3600L)

            if (apiKey.isBlank() || idToken.isBlank() || userId.isBlank()) {
                return error("Missing required fields")
            }

            config.save(
                StandaloneOmiConfig.Credentials(
                    firebaseWebApiKey  = apiKey,
                    idToken            = idToken,
                    refreshToken       = refreshToken,
                    userId             = userId,
                    tokenExpiresAtSecs = System.currentTimeMillis() / 1000L + expiresIn
                )
            )
            Log.i(TAG, "Credentials received and stored for userId=$userId")
            onCredentialsReceived()
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            Log.e(TAG, "handleCredentials error", e)
            error(e.message ?: "Internal error")
        }
    }

    private fun error(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)

    companion object {
        private const val TAG  = "SetupServer"
        const val PORT         = 8080

        /**
         * Returns the device's current WiFi IPv4 address, or null if not connected to WiFi.
         */
        fun getWifiIpAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { addr ->
                    !addr.isLoopbackAddress &&
                    addr.hostAddress?.contains('.') == true &&
                    addr.hostAddress?.startsWith("192.168.49") != true // exclude hotspot/P2P
                }
                ?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Could not read network interfaces", e)
            null
        }
    }
}
