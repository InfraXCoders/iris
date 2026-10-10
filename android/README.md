# BMPCC Control for Android

The Android version of the iPhone/Mac app in this repo: **Recce mode** with the same maths and data, plus
**Shoot mode** to control a Blackmagic camera over Bluetooth.

- Director's viewfinder (CameraX) with frame lines, aspect masks, anamorphic desqueeze, auto zoom,
  guides, markers and reference photos.
- Recces → scenes → shots, with camera, lens, recording mode, exposure and notes.
- Lens database (anamorphic primes from 15 makers), camera database with recording modes, lens coverage tool.
- Sun times (NOAA), voice notes (English, Hindi, Hinglish) saved with the recce, PDF report, JSON export that
  the iPhone app opens (and the other way round).

## Using the app

The home screen asks how you want to work: **Shoot mode** or **Recce mode**. Below them are the lens and camera
library, lens coverage and **My recces** (projects, scenes, shots, notes, PDF).

### Shoot mode (Bluetooth camera control)

1. On the camera: **Settings → Setup → Bluetooth: On**.
2. Tap **Shoot mode**. The app scans; tap **Connect** next to the camera. Android asks for the 6-digit PIN the camera
   shows; type it. Once connected, Shoot mode opens.
3. Shoot mode (full screen, portrait or landscape):
   - Top: **STBY / REC**, live **timecode**, and battery (when the camera reports it) with the recording format.
   - Right: **FPS, SHTR, IRIS, ISO, WB, ND**. Tap one and use the slider at the bottom; WB and IRIS also have **Auto**.
     Frame rate can be changed once the camera has reported its recording format.
   - Bottom: **Menu** (focus slider, auto focus / iris / WB, grid, phone picture, all settings, disconnect),
     the **record** button, and **LUT** (the camera's monitor LUT: off, Film to Video, Film to Extended Video, custom).
   - Behind the controls is the phone's own camera as a framing aid ("PHONE VIEW"); the cinema camera's picture
     can't be sent over Bluetooth. Turn it off in Menu.

Works with Blackmagic cameras that have Bluetooth camera control (Pocket 4K / 6K / 6K G2 / 6K Pro, Cinema Camera 6K,
URSA Mini Pro G2/12K, URSA Cine…). Iris and focus need an electronic lens; ND needs a camera with built-in ND.
The protocol code is in `core/.../Bmd.kt`, tested against the examples in Blackmagic's Camera Control documentation.

### Recce mode (director's viewfinder)

1. Tap **Recce mode**. The **New recce** sheet opens over the camera picture:
   **1 · Camera** (Pocket 4K / 6K / 6K G2 / 6K Pro, or **More…** for every camera, plus the recording mode),
   **2 · Lens** (**Prime set**, **Zoom** or **Anamorphic**; **Change** picks a particular lens or set from the database),
   **3 · Frame** (16:9, 17:9, 2.39:1, 4:3 or More…). Tap **Open viewfinder**.
   "Prime set" and "Zoom" are maker-neutral: a spherical lens's view depends only on its focal length and the sensor.
2. The viewfinder zooms the phone so the frame shows what that camera and lens see.
   - Top: camera (tap to change), focal length with horizontal field of view, frame (tap to change).
   - Focal-length chips at the bottom change the lens; the phone zoom follows. **W / M / C** on the left jump to wide,
     medium and close.
   - Right: **Grid**, **Level** (horizon line, green when level; tilt shows in the line above the chips), **Guides**
     (centre mark, safe areas, outside-the-frame view, lock zoom, place markers) and **Notes** (typed or voice).
   - Tap the exposure line (T-stop · ISO · fps · shutter · tilt) for frame rate, shutter, ISO, WB, ND, iris and
     recording mode; the phone preview gets brighter or darker to match. When a camera is connected these are sent to
     it too, and a REC / timecode pill records on the camera.
   - The white button saves a frame to the shot; the thumbnail opens the shot. **Setup** changes camera, lens and frame.
   - **Compare focal lengths** (Guides): the other focal lengths of the set show as dashed, labelled frame lines; the
     phone zooms out so the widest fits. **+** on the focal chips types any focal length (zooms, generic sets).
   - **Saved kits**: in the New recce / Setup sheet, "Save this camera + lens as a kit", then one tap next time.
   - **Compare frames** (scene screen): every saved frame of the scene side by side, cropped to its frame lines.
3. Frames, shots and notes are saved under **My recces → Quick Recce** (or the project you opened), with PDF export.

### Accuracy: calibrate the phone once

Frame lines are only as accurate as the phone camera's angle of view. By default the app uses what the phone
reports (lens focal length and sensor size), which can be a few percent off. **Home → Calibrate phone**: put a flat
object of known width (a door, a table, tape between two marks) square to the phone 2–5 m away, measure the
distance, drag the two blue lines onto its edges, type width and distance, Save. Guides in the viewfinder shows
which figure is in use ("calibrated" or "as reported, not calibrated"). The phone shows the cinema lens's *angle of
view*; perspective, depth of field, distortion and lens character still differ from the real lens.

The viewfinder, Shoot mode and the New recce sheet turn with the phone (portrait or landscape, even with
auto-rotate off). In landscape the tools move to the sides and the exposure line sits under the focal length.
When a lens sees wider than the phone camera, the frame keeps its real shape and the app says so.

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
  with Homebrew the first time, then writes `BMPCC-Control-v<version>-b<build>.apk`. Each build raises the build
  number in `android/version.properties`; change `versionName` there for a new release. The home screen shows
  "v0.3.0 · build N · date" at the bottom.
- **Android Studio:** open the `android` folder and press Run.

## Install on a phone (tester)

1. Send the `.apk` (WhatsApp, Google Drive, email).
2. On the phone, tap the file. Android asks to allow installing from that app (Files, Chrome or WhatsApp):
   **Settings → Allow from this source**, go back, tap **Install**.
3. If Play Protect warns about an unknown developer, tap **More details → Install anyway** (it's a test build
   that isn't on the Play Store).
4. New versions install over the old one; recces are kept.

Needs Android 8.0 or later. Voice notes use the phone's speech recognition (Google app), which may need a connection.
