# Porting notes: Android → iPhone/Mac

The Android source (Kotlin, Jetpack Compose) was read in full. RecceKit reproduces its maths, and the tests in
`RecceKit/Tests` compare against values produced by running the original Kotlin code (`android_golden.json`).
Where the Android maths was wrong, the iOS version is correct and the test says so.

## Included in v1 (Recce mode)

Recce sessions, scenes and shots; director's viewfinder with frame lines, aspect masks and markers; reference
photos; camera and lens library; sun times; voice notes; PDF report; JSON export/import compatible with Android.

## Left for later

- **Camera control over Bluetooth**: done in the Android app (`android/`, with the protocol fixes below); iPhone next.
- **Shot "AI analysis"**: the Android version returned fixed, made-up values (not real analysis), so it was
  not ported rather than show false data.

## Bugs found in the Android app (fixed here)

| Area | Android | iOS |
|---|---|---|
| Viewfinder frame lines | Preview scale = phone FOV / reference FOV, capped at 1.0, so the frame never got smaller than the screen; the phone zoom (focal/18) was then applied on top, counting the zoom twice. | Frame size from the ratio of tan(half-angles) of the reference and the actual phone view, which is exact for rectilinear lenses. Auto zoom picks the widest zoom that still fits the frame. |
| Anamorphic view | Angle multiplied by the squeeze: ALEXA 35 + 2x 40 mm = 77.13°. | Desqueezed width: 2·atan(squeeze·W / 2f) = 69.96°. |
| Diagonal view | √(h² + v²) of the angles: 76.47°. | From the sensor diagonal: 72.72°. |
| Sun times | Ignored the equation of time; golden hour was "sunset − 1 hour". Delhi 21 June: golden hour 6:15–7:15 PM. | NOAA algorithm; golden hour is sun 6° above to 4° below the horizon. Delhi 21 June: 18:47–19:21. Checked against the astral library within a minute. |
| Voice notes | Transcribed and sorted, then never saved. | Saved with the session, exported in JSON (`voiceNotes`, optional so Android files still import). |

## Bluetooth camera-control protocol (for the next phase)

Checked against Blackmagic's published *Camera Control* protocol. The Android code had these wrong:

| Item | Android | Correct |
|---|---|---|
| Fixed-point (fixed16) data type | 12 | 128 |
| Shutter angle command | 1.7 | 1.11 (shutter angle ×100, int32) |
| Record | transport mode 1 (that is *Play*) | transport mode 2 |
| LUT | 1.24 | 1.15, int8[2] |
| Video mode | 1.0 as a single value | 1.0, int8[5] |
| Characteristic labels | outgoing/incoming swapped | `5DD3465F-…` outgoing camera control (write), `B864E140-…` incoming camera control (notify), `6D8F2110-…` timecode, `7FE8691D-…` camera status |

Service UUID: `291D567A-6D75-11E6-8B77-86F30CA893D3`.
