# FB Photo Fixer

Moves new files out of `DCIM/Facebook/` into `Facebook_Saved/` (and deletes the
originals) so Google Photos stops backing up Facebook's saved images.

## How it works

- **`MainActivity`** — onboarding screen. Requests `MANAGE_EXTERNAL_STORAGE`
  ("All files access"), offers to exempt the app from battery optimization,
  and deep-links into MIUI's Security app for Autostart.
- **`FileMoveWorker`** — a `CoroutineWorker` that scans
  `/storage/emulated/0/DCIM/Facebook/`, copies qualifying media files to
  `/storage/emulated/0/Facebook_Saved/`, verifies the copy, then deletes the
  original. Skips files younger than 20 seconds so it never grabs a file
  Facebook is still writing.
- **`WorkScheduler`** — enqueues the worker as unique periodic work every
  15 minutes (the shortest interval Android's WorkManager allows).
- **`BootReceiver`** — re-enqueues the periodic work after a reboot.

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
   settings screen that opens → back out to the app.
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

- WorkManager's minimum periodic interval is 15 minutes; Android does not
  allow shorter periodic background intervals for battery reasons.
- If you want near-instant detection instead of a 15-minute poll, that
  requires a persistent foreground service with `FileObserver`, which shows
  an ongoing notification the whole time — a reasonable tradeoff to make
  explicitly if you want it, but not the default here since it's more
  intrusive.
- The worker only moves recognized media extensions (jpg, jpeg, png, gif,
  webp, heic, heif, mp4, mov, 3gp, mkv). Add to `MEDIA_EXTENSIONS` in
  `FileMoveWorker.kt` if Facebook saves something else on your device.
