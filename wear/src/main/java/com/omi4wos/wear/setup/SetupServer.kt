package com.omi4wos.wear.setup

import android.content.Context
import android.util.Log
import com.omi4wos.wear.network.StandaloneOmiConfig
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * Local HTTP server that runs on the watch during credential setup.
 *
 * Flow:
 *   1. Watch starts this server on port 8080 and shows a QR code with its WiFi URL.
 *   2. User scans the QR on any phone — browser opens the setup form.
 *   3. User pastes the same 3 credentials they use in the companion app settings
 *      (Firebase Web API Key, Firebase Token, Refresh Token — all from app.omi.me DevTools).
 *   4. Form POSTs them to this server; watch stores them and calls [onCredentialsReceived].
 *
 * If the watch already has credentials baked in via BuildConfig/local.properties those are
 * used automatically and setup is only needed to rotate them.
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
    body{font-family:sans-serif;max-width:480px;margin:32px auto;padding:0 20px;background:#111;color:#eee}
    h1{font-size:1.4em;color:#7c9eff;margin-bottom:4px}
    p{color:#aaa;font-size:.88em;margin-top:4px}
    details{margin-top:14px;border:1px solid #333;border-radius:6px;padding:10px 12px}
    summary{cursor:pointer;font-size:.85em;color:#7c9eff}
    ol{margin:8px 0 0 16px;padding:0;font-size:.8em;color:#888;line-height:1.7}
    code{background:#222;padding:1px 4px;border-radius:3px;font-size:.95em}
    label{display:block;margin-top:18px;font-size:.85em;color:#aaa}
    input{width:100%;padding:10px;margin-top:5px;border:1px solid #444;border-radius:6px;
          background:#222;color:#eee;font-size:.9em;box-sizing:border-box;font-family:monospace}
    button{margin-top:24px;width:100%;padding:13px;background:#5c6bc0;color:#fff;
           border:none;border-radius:8px;font-size:1em;cursor:pointer}
    button:disabled{background:#333;color:#666;cursor:default}
    #status{margin-top:14px;font-size:.9em;min-height:1.5em}
  </style>
</head>
<body>
  <h1>omi4wearOS Setup</h1>
  <p>Paste the same credentials used in the omi4wearOS companion app.</p>

  <details>
    <summary>How to get these values</summary>
    <ol>
      <li>Open <strong>app.omi.me</strong> in Chrome and sign in.</li>
      <li>Open DevTools (<code>F12</code>) → <strong>Network</strong> tab.</li>
      <li><strong>Firebase Web API Key</strong> — filter for <code>googleapis.com</code>, copy the <code>key=</code> value from any request URL.</li>
      <li><strong>Firebase Token</strong> — click any request → Headers → copy the value after <code>Authorization: Bearer </code>.</li>
      <li><strong>Refresh Token</strong> — DevTools → <strong>Application</strong> tab → IndexedDB or Local Storage → find <code>refreshToken</code>.</li>
    </ol>
  </details>

  <label>Firebase Web API Key</label>
  <input type="text" id="apiKey" placeholder="AIzaSy..." autocomplete="off" spellcheck="false">

  <label>Firebase Token <span style="color:#555;font-size:.85em">(expires ~1 h — refresh token auto-renews it)</span></label>
  <input type="text" id="idToken" placeholder="eyJ..." autocomplete="off" spellcheck="false">

  <label>Refresh Token</label>
  <input type="text" id="refreshToken" placeholder="AMf..." autocomplete="off" spellcheck="false">

  <button id="btn" onclick="doSetup()">Connect Watch</button>
  <div id="status"></div>

<script>
async function doSetup() {
  const btn          = document.getElementById('btn');
  const status       = document.getElementById('status');
  const apiKey       = document.getElementById('apiKey').value.trim();
  const idToken      = document.getElementById('idToken').value.trim();
  const refreshToken = document.getElementById('refreshToken').value.trim();

  if (!apiKey || !idToken || !refreshToken) {
    status.textContent = 'Please fill in all three fields.';
    return;
  }

  btn.disabled = true;
  status.textContent = 'Sending to watch…';

  try {
    const res = await fetch('/credentials', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        firebaseWebApiKey: apiKey,
        idToken:           idToken,
        refreshToken:      refreshToken,
        expiresIn:         3600
      })
    });

    if (res.ok) {
      status.innerHTML = '&#x2705; <strong>Watch connected!</strong> You can close this page.';
    } else {
      status.textContent = 'Error — please retry.';
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
            val json = JSONObject(bodyMap["postData"] ?: return error("Empty body"))

            val apiKey       = json.optString("firebaseWebApiKey")
            val idToken      = json.optString("idToken")
            val refreshToken = json.optString("refreshToken")
            val expiresIn    = json.optLong("expiresIn", 3600L)

            if (apiKey.isBlank() || idToken.isBlank() || refreshToken.isBlank()) {
                return error("Missing required fields")
            }

            // Set expiresAt=0 so the first upload always triggers a token refresh via
            // the refresh token. The idToken pasted by the user may already be expired.
            config.saveFromSetup(
                firebaseWebApiKey  = apiKey,
                idToken            = idToken,
                refreshToken       = refreshToken,
                tokenExpiresAtSecs = 0L
            )
            Log.i(TAG, "Credentials received and stored via web setup")
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
        private const val TAG = "SetupServer"
        const val PORT        = 8080

        /** Returns the device's current WiFi IPv4 address, or null if not on WiFi. */
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
