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

### Finding recces: search, filters, map, place names

- **Search** (My recces): words are matched across project and location names, director/DoP and location notes,
  scene headings and notes, shot notes, shot type, camera/lens, LUT and voice notes (accents and case ignored; all
  words must match). Each result says where it was found ("Found in: Shot 1A notes").
- **Filters:** last 7 / 30 days / 12 months, INT. / EXT. / Day / Night scenes, with GPS only; sort by recently
  changed, recce date or project name.
- **Map** (Home → Recce map, the map button in My recces, or "Show on map" in a recce): every recce with a GPS
  position as a pin on [OpenStreetMap](https://www.openstreetmap.org/copyright) (© OpenStreetMap contributors);
  tap a pin → open the recce or **Directions** (opens the phone's maps app). Map tiles need internet and are cached;
  pins work offline.
- **Place name from GPS:** after "Use my location" (new recce or a recce page) the phone's own geocoder looks up a
  short name such as "Hauz Khas, New Delhi". It fills an empty Location field, or is offered as "Use as name".
  Needs internet; free (Android's built-in service).

### Sun planner and sun in the camera (AR)

Open it from a recce (Sun → **Sun planner**), a shot (**Planned time**), the viewfinder (Guides → Sun planner)
or Home → **Sun planner**.

- **Date and time:** pick a date (tap it for a calendar, or ‹ ›) and slide the time; quick buttons for blue hour,
  sunrise, golden hour, noon, sunset, now. Shows the sun's direction and height, shadow length and direction,
  and which way to face for backlight.
- **Sun path:** a dial of the day's path (centre = straight up, edge = horizon, north up), hour marks, golden
  hour in orange, sunrise/sunset points.
- **AR:** "See the sun in the camera" draws the horizon with compass points, the day's path with hours and the sun
  at the chosen time over the live picture. Uses the phone's rotation sensor and compass, corrected to true north.
  A warning appears when the compass reports poor accuracy (move the phone in a figure-8). To remove the remaining
  error, put the crosshair on the real sun and tap **Align on the sun** (corrects the heading exactly), or nudge
  **−1° / +1°**. The correction lasts while the app runs.
- **Buildings and hills (skyline):** in AR, tap **Record skyline** and sweep the crosshair along the tops of
  buildings, trees and hills, standing where the camera will be. It's saved with the recce; the planner then shows
  **Direct sun here** (e.g. 07:42–16:55), "behind the skyline" for the chosen time, the skyline in grey on the dial
  and in blue in AR. Directions not recorded count as open sky; gaps up to 15° are bridged.
- **Cloud forecast (optional):** Weather → "Get the cloud forecast": hourly cloud cover, low cloud and rain chance
  for the next 16 days from [Open-Meteo](https://open-meteo.com) (free, no account; CC BY 4.0), with a plain read
  ("direct sun likely / may come and go / unlikely"). Off until you turn it on, because it sends the location to
  Open-Meteo and needs internet (the app's only internet use).
- **When will the sun reach this spot:** in AR, aim the crosshair at a window, a gap or a rooftop edge and tap
  **Mark this spot**: when the sun passes through it (or how close it gets), when it is in that compass direction
  (and how far above/below), and from when to when the sun is higher than that spot (e.g. clears the building).
- **Saved with the shot:** "Use 16:30 for shot 3" stores the time; the shot page and the PDF report show it with
  the sun's position (needs the recce's location).

Sun maths: NOAA algorithm; refraction (Sæmundsson) and the sun's half-disc are allowed for against the skyline.
Terrain is only what you record (no elevation maps). Forecasts are forecasts. Times are in the phone's time zone.
The planned time and skyline are saved in Android recces (the iPhone app ignores them for now).

### Lens character: focus and distortion

- **Focus distance and depth of field:** tap the exposure line → Focus distance. The viewfinder shows
  "Focus 3.0 m · DoF 2.7–3.3 m" and warns when the subject is closer than the lens can focus (maker's close-focus
  figure). Thin-lens formulas, circle of confusion = sensor diagonal / 1500, T-stop used as the stop.
  The shot page and PDF report show it too.
- **Distortion:** 40 photo lenses (Sigma Art, Canon EF L, Sony GM, Olympus/OM PRO, Panasonic) have measured profiles
  from the [Lensfun](https://github.com/lensfun/lensfun) database (CC BY-SA 3.0). The viewfinder draws where the
  frame edges really fall (dotted orange line; Guides → Lens distortion) and the shot page shows e.g.
  "−1.7 % barrel at the corners". Cine lens makers don't publish distortion, so for any lens without a profile you
  can enter your own measurement: Guides → **Enter this lens's distortion…** (% at the corners of the frame, from a
  grid chart or a straight wall; − = barrel). It's saved per lens and focal length, drawn the same way, and always
  labelled "your measurement".
- **Phone calibration reminder:** until the phone's angle of view is calibrated, the viewfinder shows a reminder
  for a few seconds (tap it to calibrate).

### LUT preview

- **Library:** Home → LUTs. Six built-in looks (Warm, Cool, Teal & Orange, Bleach bypass, High contrast,
  Black & white) plus your own `.cube` files (3D up to 65³, or 1D; max 20 MB), imported from Files, Drive or
  WhatsApp. Each LUT shows a colour-chart preview and its **input**: Rec.709, or Blackmagic Film Gen 5 for
  log-to-Rec.709 LUTs (picked automatically when the file name says film / log / gen5 / BRAW / BMD).
- **Live in the viewfinder:** the LUT button picks a look; "Before / after" adds a split you drag sideways.
  Runs on the GPU on Android 13 and newer; on older phones (or if the GPU effect fails) a lower-resolution CPU
  version (~480 px, ~15 fps) is shown instead.
- **Per shot:** the LUT is saved with each shot (shot page → Exposure → LUT). Saved frames and Compare have a
  Neutral / Graded switch, and the PDF report lists the LUT.
- **Accuracy:** a monitoring preview, not a colour-managed pipeline. For Gen 5 LUTs the phone's picture is
  converted display → linear Rec.709 → Blackmagic Wide Gamut → Film Gen 5 curve (Blackmagic's published Gen 5
  colour science: primaries and curve). The phone picture is already tone-mapped and has less dynamic range than the
  camera, so highlights and the extremes won't match a real BRAW grade. Use it to judge mood, not final colour.

### Exposure and focus tools

Viewfinder → **Expo** button. Settings are remembered.

- **False colour:** ARRI's published exposure bands (ALEXA manual, "False Color Exposure Check"): purple 0–2.5 %
  black clipping, blue 2.5–4 %, green 38–42 % (18 % grey), pink 52–56 % (grey + 1 stop, skin), yellow 97–99 %,
  red 99–100 % clipping; the rest in grey. A key is shown at the top. (Blackmagic doesn't publish its band values.)
- **Zebras:** stripes at or above 70–100 % (tap the level line to change it; 100 % means ≥ 99 %).
- **Focus peaking:** edges coloured red / green / blue / yellow / white, sensitivity low / medium / high.
- **Scopes:** waveform, RGB parade or histogram of the picture **inside the frame lines**, ~10 times a second,
  with the share of clipped (any channel ≥ 99 %) and crushed (luma < 2.5 %) pixels. Tap the scope to enlarge it.
- False colour and zebras work on the graded picture (after the shot's LUT) — what you see. Peaking uses the
  ungraded picture's edges. Full quality on the GPU on Android 13+; older phones get a lower-resolution CPU
  version. The scopes work on every phone.
- **Phone picture exposure** (Exposure sheet): **Match exactly** (default) sets the phone's own ISO and shutter so
  its picture gets the same exposure as the cinema camera: exposure ∝ shutter time × ISO ÷ (T-stop² × ND), solved
  for the phone's fixed aperture, keeping the shutter at or under 1/30 s and the ISO as low as possible. A note
  appears when the scene is beyond what the phone can show. **Follow** shifts the phone's auto-exposure by the
  shot's stops; **Phone auto** leaves it alone. Phones without manual exposure (Camera2 MANUAL_SENSOR) follow.

**Accuracy:** these measure the *phone's* picture. With Match exactly, false colour and the scopes show roughly
where the scene sits at the planned settings (phone and camera ISO ratings and tone curves differ, so treat it as
± ½–1 stop) — a guide for lighting the scene, not the BMPCC's own signal. Phone sensors have less dynamic range than
the camera, so they clip earlier. Peaking shows what is sharp for the phone's small lens, which has far more depth
of field than a cine lens: use the Focus / DoF line for the cinema lens.

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
