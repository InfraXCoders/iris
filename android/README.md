# BMPCC Control for Android

The Android version of the iPhone/Mac app in this repo: **Recce mode** with the same maths and data.

- Director's viewfinder (CameraX) with frame lines, aspect masks, anamorphic desqueeze, auto zoom,
  guides, markers and reference photos.
- Recces → scenes → shots, with camera, lens, recording mode, exposure and notes.
- Lens database (anamorphic primes from 15 makers), camera database with recording modes, lens coverage tool.
- Sun times (NOAA), voice notes (English, Hindi, Hinglish) saved with the recce, PDF report, JSON export that
  the iPhone app opens (and the other way round).

## Layout

| Folder | What it is |
|---|---|
| `core/` | Plain Kotlin: optics, framing, lens/camera database (`src/main/resources/data/*.csv`), sun, note sorting, JSON. Same results as the iPhone app's RecceKit, tested against the same reference files (`./gradlew :core:test`). |
| `app/` | The Android app (Jetpack Compose). |
| `keystore/testing.keystore` | Signing key for **test builds only**, so every APK (from GitHub or a Mac) installs over the last one. Make a private key before publishing to the Play Store. |

The lens and camera CSV files are copies of `RecceKit/Sources/RecceKit/Data/`; keep both in sync when editing.

## Build

- **GitHub (nothing to install):** push the repo; GitHub → **Actions** → **Android APK** → latest run →
  **Artifacts** → `BMPCC-Control-android` (a zip with the APK inside).
- **Mac:** `bash build_android.command` in the repo folder. It installs Java 17 and the Android command-line tools
  with Homebrew the first time, then writes `BMPCC-Control-android.apk`.
- **Android Studio:** open the `android` folder and press Run.

## Install on a phone (tester)

1. Send the `.apk` (WhatsApp, Google Drive, email).
2. On the phone, tap the file. Android asks to allow installing from that app (Files, Chrome or WhatsApp):
   **Settings → Allow from this source**, go back, tap **Install**.
3. If Play Protect warns about an unknown developer, tap **More details → Install anyway** (it's a test build
   that isn't on the Play Store).
4. New versions install over the old one; recces are kept.

Needs Android 8.0 or later. Voice notes use the phone's speech recognition (Google app), which may need a connection.
