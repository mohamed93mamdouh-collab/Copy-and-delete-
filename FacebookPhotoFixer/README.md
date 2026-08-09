# FB Photo Fixer

Moves new files out of `DCIM/Facebook/` into `Facebook_Saved/` (and deletes
the originals) so Google Photos stops backing up Facebook's saved images —
in real time, before Google Photos' own sync gets to them.

## How it works

- **`MainActivity`** — onboarding screen. Requests `MANAGE_EXTERNAL_STORAGE`
  ("All files access"), requests the `POST_NOTIFICATIONS` permission
  (Android 13+), starts the real-time watcher service, offers to exempt the
  app from battery optimization, and deep-links into MIUI's Security app for
  Autostart.
- **`MediaMover`** — the single shared "copy → verify → delete" routine used
  by both the instant path and the backup sweep, so there's one place that
  actually touches the filesystem.
- **`FileWatcherService`** — a foreground service holding a `FileObserver`
  on `DCIM/Facebook`, triggered on `CLOSE_WRITE` (a file finished being
  written) and `MOVED_TO` (a file was renamed into place, which is how some
  apps finalize writes). Reacts within milliseconds — well before Google
  Photos' own MediaStore scan. Runs a persistent low-priority notification
  ("Watching for new Facebook photos…") so MIUI/HyperOS is far less likely
  to kill it, and requests `START_STICKY` so Android tries to restart it if
  it does get killed. If `DCIM/Facebook` doesn't exist yet, it watches
  `DCIM/` itself until Facebook creates that folder, then switches targets.
- **`FileMoveWorker`** — a `CoroutineWorker` that runs every 15 minutes as a
  **safety net only**, sweeping for anything left behind in case the
  watcher service was ever killed and hadn't restarted yet. Not the primary
  mechanism anymore.
- **`WorkScheduler`** — enqueues that backup sweep as unique periodic work.
- **`BootReceiver`** — after a reboot, restarts the foreground watcher
  service and re-enqueues the backup sweep.

## Opening the project

1. Open this folder in Android Studio (Hedgehog/2023.1+ recommended, AGP 8.5).
2. Let it sync — Android Studio will generate the Gradle wrapper jar
   automatically on first sync if it's missing.
3. Build & run on a device (minSdk 26, targets Android 14/SDK 34).

If you'd rather build from the command line, run
`gradle wrapper --gradle-version 8.7` once inside the project folder to
materialize `gradlew`/`gradlew.bat`, then `./gradlew assembleDebug`.

## First-run checklist on the phone

1. Launch the app → tap **Grant All Files Access** → toggle "Allow" on the
   settings screen that opens → back out to the app. The watcher service
   starts automatically once this is granted (you'll get a notification
   permission prompt first on Android 13+ — allow it so the persistent
   "watching" notification can show).
2. Tap **Disable Battery Optimization For This App** → confirm "Allow" on the
   system dialog.
3. Tap **Open MIUI Security App** and, once there:
   - **App Battery Saver** → find this app → set to **No restrictions**.
   - **Permissions → Autostart** → enable Autostart for this app.
4. Open Recent Apps, find this app's card, and lock it (swipe down on the
   card, or tap the lock icon) so MIUI's memory cleaner won't sweep it.

None of step 3/4 can be triggered programmatically — MIUI doesn't expose an
API for them, which is exactly why apps get silently killed on Xiaomi phones
even after Android's own permission is granted. The in-app instructions exist
because a human has to flip those switches once.

## Notes / limitations

- The persistent notification is required by Android for any foreground
  service — it can't be hidden. It's intentionally low priority and silent.
- `MediaMover` only moves recognized media extensions (jpg, jpeg, png, gif,
  webp, heic, heif, mp4, mov, 3gp, mkv). Add to `MEDIA_EXTENSIONS` in
  `MediaMover.kt` if Facebook saves something else on your device.
- If MIUI kills the service despite all the above, the 15-minute
  `FileMoveWorker` sweep will still catch and move anything left behind —
  it just won't beat Google Photos' sync in that scenario, only clean up
  after.
