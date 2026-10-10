# BMPCC Control (iPhone, Mac and Android)

A location-recce app for filmmakers shooting on Blackmagic Pocket Cinema cameras, ported from the Android
app *BMPCC Control*. This first version is **Recce mode**, on iPhone, Mac and Android. Camera control over
Bluetooth comes later.

Everything here is free: no accounts, no servers, no paid APIs. All data stays on your device.

## What it does

- **Director's viewfinder.** Point your iPhone (or the Mac's camera) and see the exact frame your cinema camera
  and lens will capture: frame lines, aspect masks (2.39, 1.85, 16:9…), anamorphic desqueeze, thirds, safe
  areas and horizon. The phone zooms itself so the frame fits. You can capture reference photos with the frame
  lines saved, and drop markers for subject, camera, key light and so on.
- **Recces → scenes → shots.** Shot type, movement, camera, lens, focal length, T-stop, aspect, frame rate,
  shutter, ISO, ND, white balance, camera height and distance, plus notes.
- **Lens database.** About 300 anamorphic primes from 15 makers (ARRI/ZEISS, Cooke, Hawk, Panavision, Atlas,
  SIRUI, Laowa, Blazar, DZOFilm and more): focal length, T-stop, squeeze, close focus, image circle, length,
  weight, front diameter and mounts, with filters and a link to the maker's spec sheet for every lens.
- **Camera database.** About 50 cinema cameras with their recording modes and the sensor area each one uses.
- **Lens coverage tool.** The image circle drawn over the sensor for any camera, mode and lens, with a verdict,
  field of view and mount check. Shots also have a recording mode, so frame lines follow the crop.
- **Sun.** Sunrise, sunset, solar noon, golden hour and blue hour for the recce's location and date, and where
  the sun is now.
- **Voice notes** in English, Hindi or Hinglish, turned into text and auto-sorted into composition, lighting
  and movement.
- **Share.** A PDF report, and JSON export/import that the Android app can read.

## Requirements

- A Mac with **Xcode** (free from the App Store). Open it once after installing so it can finish setup.
- An **Apple ID** (a free one is fine) to install on an iPhone.
- iPhone with **iOS 17** or later, or a Mac with **macOS 14** or later.
- XcodeGen is installed automatically with [Homebrew](https://brew.sh) the first time you run `test.command`.

## Get started

Open Terminal in the project folder (in Finder: right-click the folder → **New Terminal at Folder**), then:

```bash
bash test.command      # builds and tests everything; should end with "ALL PASS"
bash run_mac.command   # opens the Mac app
```

If `test.command` fails, the last lines it prints say why; the full logs are in the `logs/` folder.

### iPhone Simulator

```bash
bash open_xcode.command
```
At the top of Xcode choose the scheme **BMPCCControl-iOS** and any iPhone simulator, then press ▶.
The Simulator has no camera, so the viewfinder shows a message there; everything else works.

### Your iPhone

1. In Xcode: **Settings → Accounts → +**, sign in with your Apple ID (a free one is fine).
2. `bash set_team.command` saves your Team ID to `Config/Local.xcconfig` (that file is not uploaded to GitHub).
3. Connect the iPhone with a cable, unlock it and tap **Trust**.
   On the iPhone turn on **Settings → Privacy & Security → Developer Mode** (it restarts).
4. `bash open_xcode.command`, choose **BMPCCControl-iOS** and your iPhone at the top, press ▶.
5. First time only: on the iPhone go to **Settings → General → VPN & Device Management**, tap your Apple ID and
   **Trust**.

With a free Apple ID the app stops opening after 7 days. Just press ▶ in Xcode again; your recces are kept.

If Xcode says the bundle ID is not available (common when a second person builds it), add a line
`BUNDLE_ID_PREFIX = com.yourname` to `Config/Local.xcconfig` and press ▶ again.

## Testing

### What to check

| Area | Try this | Expected |
|---|---|---|
| Viewfinder | Quick recce → point at something → change focal length and aspect | Frame lines resize; the phone zooms to fit the frame |
| Reference photo | Capture in the viewfinder, then open the shot | Photo saved with the frame lines drawn on it |
| Recce | New recce → **Use my location** → add a scene and shots | Location saved; Sun card shows sunrise, sunset, golden and blue hour |
| Voice note | Add a note, dictate in English, Hindi or Hinglish, edit, save | Text appears with Composition / Lighting / Movement tags |
| Lens database | Databases & tools → Lens database → use the filters | Lenses filter by maker, squeeze, format, mount; badges show coverage |
| Camera database | Open a camera | Recording modes with sensor sizes |
| Coverage tool | ALEXA 35 + Master Anamorphic 50mm → switch recording modes | Open gate: corners vignette; 4K 16:9 and 6:5: covers |
| Recording mode | On a shot, pick a camera with modes, change **Recording mode** | Viewfinder frame changes with the crop |
| Share | Recce → Create PDF report, Export JSON | PDF opens with one page per shot; JSON can be imported again |
| Mac | `bash run_mac.command` | Same app on the Mac; set the webcam angle with the gear button in the viewfinder |

### Reporting a problem

Send: what you tapped, what you expected, what happened, the device and iOS/macOS version, and a screenshot.
For a build problem, send the last lines `test.command` printed (or the matching file in `logs/`).

### Sharing a test build (for the project owner)

For the iPhone app a tester needs a Mac with Xcode; there is no free way to send an iPhone app to someone remote
(that needs the paid Apple Developer Program and TestFlight). For Android, just send the APK (see **Android** above).

Make a clean zip of the project (source only: no build files, no Team ID):

```bash
git add -A && git commit -m "Version for testing"
git archive --format=zip -o ~/Desktop/BMPCC-Control-iOS.zip HEAD
```

Send `BMPCC-Control-iOS.zip`. The tester unzips it and follows **Get started** and **Your iPhone** above.
(Don't send the original `BMPCC-Control.zip`: that is the old Android app's source, not this one.)

Mac-only testers can also get the built app:

```bash
bash run_mac.command
cd .build/DerivedData/Build/Products/Debug && zip -r ~/Desktop/BMPCC-Control-Mac.zip BMPCCControl.app
```

It isn't notarised, so the first time the tester right-clicks the app → **Open** → **Open**
(or allows it in **System Settings → Privacy & Security → Open Anyway**).

### Android

The Android app is in the `android/` folder (same features, same lens/camera data). Get the APK either way:

- **GitHub builds it:** push, then GitHub → **Actions** → **Android APK** → the latest run → **Artifacts**.
- **Your Mac builds it:** `bash build_android.command` (installs Java 17 and the Android tools with Homebrew the
  first time) → `BMPCC-Control-v<version>-b<build>.apk` in this folder (e.g. `BMPCC-Control-v0.3.0-b2.apk`). The same
  version and build number show at the bottom of the app's home screen, so you can tell which copy a phone has.

Send the APK to a tester (WhatsApp, Drive, email). On the phone: tap it, allow installing from that app, tap
**Install** (if Play Protect warns, **More details → Install anyway**). No Mac or account needed on their side.
Details: [android/README.md](android/README.md).

### Mac camera field of view

iPhones report their camera's field of view, so frame lines are exact. Mac webcams don't, so the Mac
viewfinder has a **Camera field of view** setting (gear button). For a Blackmagic camera's USB-C webcam output,
set it to the angle of the lens on that camera.

## Project layout

| Folder | What it is |
|---|---|
| `RecceKit/` | All the maths and data formats (optics, framing, sun, note sorting, Android JSON), with tests checked against the Android app's own code. No UI; runs anywhere Swift runs. |
| `BMPCCControl/` | The SwiftUI app, one code base for iPhone, iPad and Mac. |
| `BMPCCControlTests/` | App tests: saving, JSON import/export, recording modes, PDF report. |
| `*.command` | `test` (build + test everything), `run_mac`, `open_xcode`, `set_team` (Apple ID for iPhone installs), `build_android` (APK). |
| `project.yml` | The Xcode project definition (XcodeGen). The `.xcodeproj` is generated, not committed. |
| `android/` | The Android app (Kotlin, Jetpack Compose) and its `core` module with the same maths and data. |
| `.github/workflows/android.yml` | Builds the Android APK on GitHub on every push. |
| `DATABASE.md` | Where the lens and camera data come from, how to edit it, known gaps. |
| `PORTING_NOTES.md` | What changed from the Android app and why, including bugs found. |

## Privacy

Camera, microphone, speech recognition and location are used only when you tap the feature that needs them.
Speech recognition runs on the device where the language supports it. Nothing is uploaded.
