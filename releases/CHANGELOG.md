# Changelog

## v1.8 — 2026-04-10

### Mobile (`Omi4wOS_Mobile_v1.8.apk`)

**Changed**
- Home screen title is now the omi4wearOS logo image instead of plain text.
- Watch connection card redesigned: "Watch Connected / Disconnected" status moved to the top of the card as a title; icon resized to 80dp; icon and Start/Stop button share a clean row below the title.

---

## v1.7 — 2026-04-10

### Mobile (`Omi4wOS_Mobile_v1.7.apk`)

**Fixed**
- Batch sync fragmentation (race condition): audio chunks on the `/audio/speech/` Data Layer path can arrive and be fully assembled before the `CMD_SYNC_START` control message on `/audio/control/` is processed, because the Wear Data Layer does not guarantee cross-path ordering. When this happened, segments received with an empty syncId fell through to the realtime upload path and each became its own independent Omi conversation. Now all segments in batch mode are buffered regardless of whether the syncId has arrived yet; segments that raced ahead are absorbed into the correct syncId at flush time.
- Batch flush delay increased from 500ms → 2000ms to give in-flight segment coroutines more time to complete before the buffer is snapshotted.
- Home screen title trimmed to "omi4wOS", watch card icon updated to the custom omi4wearOS asset, misleading (non-realtime) battery level removed from the card.

### Wear (`Omi4wOS_Wear_v1.7.apk`)

**Fixed**
- Natural speech pauses (breaths, mid-sentence thinking pauses) were ending the current recording segment prematurely. The silence offset threshold was raised from 3 → 6 consecutive 960ms VAD windows (≈2.9s → 5.8s), giving the speaker time to breathe or pause without fragmenting a single conversation into many short clips.

---

## v1.6 — 2026-04-10

### Mobile (`Omi4wOS_Mobile_v1.6.apk`)

**Changed**
- App icon: bezel ring tightened to radius 30 (was 40) and mic rescaled to 1.8× so all artwork sits safely inside the launcher mask's clip boundary on all Android icon shapes.

---

### Wear (`Omi4wOS_Wear_v1.6.apk`)

**Changed**
- Home screen redesigned around a single circular 72dp toggle button with three visual states: dark-slate (idle), indigo pulse (recording / silence), green pulse (speech detected), deep-red (permission missing). Pulse speed and amplitude are keyed to state — fast and large during speech, slow and subtle while listening.
- Haptic tick on every recording toggle.
- Phone-connected indicator (dot + label) replaces the old Chip row.
- App icon: same safe-zone fix as mobile.

---

## v1.5 — 2026-04-10

### Mobile (`Omi4wOS_Mobile_v1.5.apk`)

**Fixed**
- Conversation fragmentation in batch mode: segments from the same sync session were each uploaded as independent Omi jobs, causing one hour of audio to appear as dozens of separate conversations in the app. Batch mode now buffers all segments for a sync session and flushes them as a single multipart `POST /v2/sync-local-files` when the session ends, matching how the Omi pendant itself uploads.

**New**
- Temporal session grouping: within a batch flush, segments separated by more than 5 minutes are split into separate uploads (genuinely different conversations), matching the gap-detection logic used by `sync_omi_cloud.py`.

---

### Wear (`Omi4wOS_Wear_v1.5.apk`)

**New**
- Sends `CMD_SYNC_END` after `syncPendingChunks()` completes, signaling the phone to flush its batch buffer. Previously the phone had no way to know when a sync session was finished.

---

## v1.4 — 2026-04-10

### Mobile (`Omi4wOS_Mobile_v1.4.apk`)

**New**
- Realtime Stream mode: each completed speech segment is synced to the phone and uploaded to Omi immediately after it ends, minimising latency between speech and cloud availability.
- Batch Stream mode with a user-configurable interval — choose from `:00`, `:30`, 5, 10, 15, 30, 60, 90, or 120 minutes (default 60). Setting is persisted in DataStore and pushed to the watch over the Data Layer automatically.
- Sync Mode card on the home screen with a Realtime/Batch toggle and an interval dropdown (batch mode only).
- When Realtime Stream is active the Force Sync button is replaced with a "● Realtime Mode" indicator — manual triggers are unnecessary when every segment already syncs on completion.

**Changed**
- Batch interval is now a dropdown picker instead of a free-text field, preventing a command storm to the watch on every keystroke.

---

### Wear (`Omi4wOS_Wear_v1.4.apk`)

**New**
- Receives `SET_STREAM_MODE` command from phone and stores the selected mode and batch interval in SharedPreferences (survives service restarts).
- In Realtime mode: triggers an immediate `performSync()` after every finalized speech segment.
- In Batch mode: scheduled sync loop now reads the configured interval from prefs instead of using the hardcoded 60-minute constant.
- Clock-aligned sync intervals: `:00` syncs at the top of each hour; `:30` syncs at :00 and :30 of each hour. Both modes compare the last sync time against the most recently passed clock boundary rather than counting down from last sync.

**Fixed**
- Service crash on OS kill (overnight battery optimization): `START_STICKY` was restarting the service with `intent=null`, but the null branch fell through without calling `startForeground()`, causing an immediate re-kill. Now the null-intent restart reads `PREF_RECORDING_ENABLED` from SharedPreferences — if recording was active when killed, it resumes automatically; otherwise the service shuts down cleanly.

---

## v1.2 — 2026-04-09

### Mobile (`Omi4wOS_Mobile_v1.2.apk`)

**New**
- Upload tracking via Room database (`UploadRepository`) — every segment upload is persisted so failures survive app restarts.
- `AudioUploadService` — dedicated background service handles Omi Cloud uploads and marks each record uploaded/failed in the local DB.
- `WatchCommandReceiver` on the wear side — phone can now send Force Sync, Start Recording, and Stop Recording commands directly to the watch.
- Home screen live status card: watch connection state, battery level, "Receiving audio…" indicator, and a scrollable history of recent syncs with byte count, segment count, upload success/failure, and time span.
- Upload Failures counter with one-tap retry and a Force Sync button.

**Fixed**
- "Receiving audio" indicator stuck on after transfer completes — a 5-second debounce timeout now guarantees the flag clears even if the final chunk is lost or dropped by the dual-listener dedup logic.
- Status card height no longer shifts when the receiving-audio row appears/disappears — space is always reserved, visibility is toggled via color transparency.

---

### Wear (`Omi4wOS_Wear_v1.2_Silero.apk`)

**Changed**
- Pre-roll buffer increased from 1.5s → 2.5s to prevent first-word clipping when transitioning from idle to active conversation mode.

---

## v1.1 — 2026-04-08

### Wear (`Omi4wOS_Wear_v1.1_Silero.apk`)

- Replaced WebRTC GMM VAD with Silero LSTM (OnnxRuntime 1.14.0, pinned to avoid armeabi-v7a alignment bug in 1.15+).
- Dynamic Conversational Hysteresis: idle mode requires 2 consecutive positive VAD windows; active conversation mode requires only 1.
- 1.5s pre-roll buffer to capture sentence openers.
- Idle Power Throttling: classification interval doubles to 1920ms after 5 minutes of silence; connectivity polling reduced from 30s to 2 minutes.
- Store-and-forward caching: up to 500MB of Opus audio retained on disk if Bluetooth drops, synced chronologically on reconnection.
- BLE Batch Transfer: transmits the completed payload at phrase end instead of streaming every second.

---

## v1.0 — 2026-04-08

### Mobile (`Omi4wOS_Mobile_v1.0.apk`) · Wear (`Omi4wOS_Wear_v1.0.apk`)

- Initial release.
- WebRTC GMM VAD on the watch.
- Opus 16kbps encoding, Data Layer streaming to phone companion.
- Phone assembles `.bin` archive and uploads to Omi Cloud `/v2/sync-local-files`.
- Firebase token auto-refresh.
