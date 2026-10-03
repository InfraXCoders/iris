# BMPCC Control (iPhone + Mac)

A location-recce app for filmmakers shooting on Blackmagic Pocket Cinema cameras, ported from the Android
app *BMPCC Control*. This first version is **Recce mode**. Camera control over Bluetooth comes later.

Everything here is free: no accounts, no servers, no paid APIs. All data stays on your device.

## What it does

- **Director's viewfinder.** Point your iPhone (or the Mac's camera) and see the exact frame your cinema camera
  and lens will capture: frame lines, aspect masks (2.39, 1.85, 16:9…), anamorphic desqueeze, thirds, safe
  areas and horizon. The phone zooms itself so the frame fits. You can capture reference photos with the frame
  lines saved, and drop markers for subject, camera, key light and so on.
- **Recces → scenes → shots.** Shot type, movement, camera, lens, focal length, T-stop, aspect, frame rate,
  shutter, ISO, ND, white balance, camera height and distance, plus notes.
- **Camera & lens library.** 13 cameras and 12 lenses (the same list as the Android app), with field-of-view
  tables, lens/sensor coverage checks, favourites and defaults for new shots.
- **Sun.** Sunrise, sunset, solar noon, golden hour and blue hour for the recce's location and date, and where
  the sun is now.
- **Voice notes** in English, Hindi or Hinglish, turned into text and auto-sorted into composition, lighting
  and movement.
- **Share.** A PDF report, and JSON export/import that the Android app can read.

## Get started (on your Mac)

You need Xcode (App Store). XcodeGen is installed automatically with Homebrew.

```bash
cd ~/Desktop/Project-Websites/iris
bash test.command      # builds and tests everything; should end with "ALL PASS"
bash run_mac.command   # opens the Mac app
```

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

If Xcode says the bundle ID is not available, add `BUNDLE_ID_PREFIX = com.yourname` to `Config/Local.xcconfig`.

### Mac camera field of view

iPhones report their camera's field of view, so frame lines are exact. Mac webcams don't, so the Mac
viewfinder has a **Camera field of view** setting (gear button). For a Blackmagic camera's USB-C webcam output,
set it to the angle of the lens on that camera.

## Project layout

| Folder | What it is |
|---|---|
| `RecceKit/` | All the maths and data formats (optics, framing, sun, note sorting, Android JSON), with tests checked against the Android app's own code. No UI; runs anywhere Swift runs. |
| `BMPCCControl/` | The SwiftUI app, one code base for iPhone, iPad and Mac. |
| `BMPCCControlTests/` | App tests: saving, JSON import/export, PDF report. |
| `project.yml` | The Xcode project definition (XcodeGen). The `.xcodeproj` is generated, not committed. |
| `PORTING_NOTES.md` | What changed from the Android app and why, including bugs found. |

## Privacy

Camera, microphone, speech recognition and location are used only when you tap the feature that needs them.
Speech recognition runs on the device where the language supports it. Nothing is uploaded.
