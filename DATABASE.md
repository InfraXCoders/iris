# Lens & camera database

The app's lens database, camera database and coverage tool read two plain CSV files:

- `RecceKit/Sources/RecceKit/Data/lenses.csv`: 765 lenses: 294 anamorphic primes (phase 1), 332 spherical cine
  primes, 84 cine zooms and 55 photo lenses (phase 2)
- `android/core/src/main/resources/data/distortion.csv` (Android): distortion profiles for 40 photo lenses, from
  the Lensfun database (CC BY-SA 3.0, see "Lens distortion" below)
- `RecceKit/Sources/RecceKit/Data/cameras.csv`: 54 cameras, 285 recording modes

Open them in Numbers or Excel, edit, save as CSV (UTF-8), then run `bash test.command`.
The Android app reads copies in `android/core/src/main/resources/data/`: copy edited files there too.
The tests check every row (ranges, unique ids, square photosites, a source link on each row).

## Where the numbers come from

Every row has a `source_url`: the maker's own spec page or datasheet (or the rental house that makes the lens,
e.g. Vantage for Hawk, ARRI Rental for ALFA). Aggregator sites were not used as sources. `notes` says when a
value was converted from imperial, computed (e.g. a crop mode from pixel pitch), or when the maker's own
pages disagree. Empty cells mean the maker doesn't publish that value; nothing is guessed.

Where a value comes from general knowledge rather than the spec page, the note says so:
Panavision rows (2x squeeze, Panavision PV mount) and Hawk rows (PL mount).

When a lens has no published image circle, coverage uses a typical circle for its format
(MFT 21.6, APS-C 28.4, S35 31.1, FF 43.3, LF 46.3, 65 60 mm) and the app marks the result as nominal.

## Columns

**lenses.csv**: `id, manufacturer, series, focal_mm, focal_max_mm, t_stop, f_stop, squeeze, close_focus_m,
image_circle_mm, length_mm, weight_g, front_diameter_mm, mounts (separated by ;), format (MFT/APS-C/S35/FF/LF/65),
source_url, notes`

- `focal_max_mm`: the long end of a zoom (empty for primes). The app names zooms "<series> <min>-<max>mm T<stop>".
- `t_stop` for cine lenses, `f_stop` for photo lenses (makers publish f-numbers for those); the name shows "T…" or "f/…".

**cameras.csv**: one row per recording mode; the first row of each camera is its largest area.
`camera_id, manufacturer, model, mount, mode_id, mode_name, sensor_w_mm, sensor_h_mm, res_w, res_h, source_url, notes`

Use a new unique `id` for a new lens. Keep existing ids: saved shots refer to them.

## Known gaps (from the research)

- Xelmus Apollo: maker site blocked; no rows. Vazen and Great Joy 1.8x: no reachable official spec pages.
- ARRI Rental ALFA 72–190 mm: only focal length and squeeze published.
- Laowa: most per-lens specs are images on the maker's site, so many cells are empty.
- Sony FX6/FX9 and DJI Ronin 4D: no per-mode sizes published; the FX6 keeps its original built-in sensor size.
- Some crop-mode sizes are computed from pixel pitch (noted per row), e.g. all Blackmagic modes.
- Angenieux: most product-sheet PDFs are blocked to robots, so rows come from the HTML product pages. No usable
  source for Optimo Style 30-76, Optimo 15-40 / 28-340 / older 25-250; legacy pages often name no mount.
  Optimo Prime weights are published as upper limits ("<"). ARRI Alura and Fujinon Duvo not researched. Cooke Panchro/i Classic (S35) and S4/i 65SF not included.
- Phase 2: many makers publish no numeric image circle (Master/Ultra Prime, Supreme, CP.3, photo lenses); coverage
  then uses the nominal circle for the format. Signature Prime 46 mm is ARRI's "LPL covers up to 46 mm" statement.
- Phase 2: DZOFilm spec tables merge cells, so several close-focus/length values and the Arles T-stops are empty.
- Phase 2: Sigma DG HSM sizes/weights are Sigma's SA-mount figures (EF not published); noted per row.

## Lenses (phase 1: anamorphic primes)

| Maker | Series | Squeeze | Focal lengths (mm) |
|---|---|---|---|
| Ancient Optics | Statera | 1.5x | 35, 40, 50, 75, 95, 135 |
| ARRI Rental | ALFA | 2x | 32, 40, 47, 60, 72, 90, 108, 145, 190 |
| ARRI/ZEISS | Master Anamorphic | 2x | 28, 35, 40, 50, 60, 75, 100, 135, 180 |
| Atlas Lens Co. | Kaizen | 1.5x | 35, 40, 50, 75, 100 |
| Atlas Lens Co. | Mercury | 1.5x | 24, 27, 32, 36, 42, 54, 72, 95, 138 |
| Atlas Lens Co. | Orion | 2x | 18, 21, 25, 28, 32, 40, 50, 65, 80, 100, 135, 200 |
| Atlas Lens Co. | Orion Silver Edition | 2x | 18, 21, 25, 28, 32, 40, 50, 65, 80, 100, 135, 200 |
| Blazar | Cato 2x | 2x | 40, 55, 85, 125 |
| Blazar | Mantis 1.33x | 1.33x | 25, 35, 50, 75, 100, 135 |
| Blazar | Remus 1.5x | 1.5x | 33, 35, 45, 50, 65, 85, 100, 125 |
| Caldwell | Chameleon SC | 1.79x | 25, 32, 40, 50, 60, 75, 100, 150 |
| Caldwell | Chameleon UW | 1.79x | 32, 40 |
| Caldwell | Chameleon XLC | 1.79x | 45, 56, 70, 84, 105, 140 |
| Cooke | Anamorphic/i Full Frame Plus | 1.8x | 32, 40, 50, 75, 85, 100, 135, 180 |
| Cooke | Anamorphic/i S35 | 2x | 25, 32, 40, 50, 65, 75, 100, 135, 180, 300 |
| DZOFilm | Pavo 2x | 2x | 28, 32, 40, 55, 65, 75, 100, 135, 180 |
| Hawk (Vantage) | Class-X | 2x | 28, 35, 45, 55, 55, 65, 80, 110, 140 |
| Hawk (Vantage) | Hawk65 | 1.3x | 35, 40, 45, 50, 60, 65, 70, 80, 95, 120, 150 |
| Hawk (Vantage) | V-Lite | 2x | 28, 35, 45, 55, 55, 65, 80, 110, 140 |
| Hawk (Vantage) | V-Lite 1.3x | 1.3x | 20, 24, 28, 35, 45, 55, 55, 65, 80, 110, 140 |
| Hawk (Vantage) | V-Lite Vintage'74 | 2x | 28, 35, 45, 55, 55, 65, 80, 110, 140 |
| Hawk (Vantage) | V-Plus | 2x | 30, 35, 40, 50, 65, 75, 85, 100, 120, 135, 150 |
| Laowa | Nanomorph 1.5x S35 | 1.5x | 20, 27, 35, 50, 60, 65, 80 |
| Laowa | Nanomorph LF 1.5x | 1.5x | 32, 42, 55, 85 |
| Laowa | Proteus 2x | 2x | 20, 28, 35, 45, 60, 85, 100 |
| P+S Technik | Evolution 2X | 2x | 32, 40, 50, 75, 100, 135 |
| P+S Technik | Technovision 1.5X | 1.5x | 40, 50, 75, 100, 135, 150, 200 |
| Panavision | C Series | 2x | 35, 35, 40, 50, 60, 75, 100, 150, 180 |
| Panavision | E Series | 2x | 28, 35, 40, 50, 75, 100, 135, 180 |
| Panavision | G Series | 2x | 25, 30, 35, 40, 50, 60, 75, 100 |
| Panavision | T Series | 2x | 28, 35, 40, 50, 60, 75, 100, 135, 150, 180 |
| Servicevision | Scorpio Anamorphic FFA 2x | 2x | 20, 25, 35, 40, 50, 60, 75, 100, 135, 150 |
| SIRUI | 1.33x APS-C anamorphic | 1.33x | 24, 35, 50, 75 |
| SIRUI | 1.33x S35 AF anamorphic | 1.33x | 20, 40 |
| SIRUI | Astra 1.33x AF | 1.33x | 50, 75, 100 |
| SIRUI | IronStar 1.5x | 1.5x | 35, 45, 60, 75, 100, 135 |
| SIRUI | MARS 1.33x | 1.33x | 24, 35, 50, 75 |
| SIRUI | Saturn 1.6x | 1.6x | 35, 50, 75 |
| SIRUI | Venus 1.6x | 1.6x/1.8x | 35, 50, 75, 100, 135, 150 |
| Viltrox | EPIC 1.33x | 1.33x | 25, 35, 50, 65, 75, 100, 135 |

## Lenses (phase 2: spherical cine primes)

| Maker | Series | Focal lengths (mm) |
|---|---|---|
| ARRI | Signature Prime | 12, 15, 18, 21, 25, 29, 35, 40, 47, 58, 75, 95, 125, 150, 200, 280 |
| ARRI/ZEISS | Master Prime | 12, 14, 16, 18, 21, 25, 27, 32, 35, 40, 50, 65, 75, 100, 135, 150 |
| ARRI/ZEISS | Ultra Prime | 12, 14, 16, 20, 24, 28, 32, 40, 50, 65, 85, 100, 135, 180 |
| ARRI/ZEISS | Ultra Prime 8R | 8 |
| Cooke | S4/i | 12, 14, 16, 18, 21, 25, 27, 32, 35, 40, 50, 65, 75, 100, 135, 150, 180, 300 |
| Cooke | S7/i | 16, 18, 21, 25, 27, 32, 40, 50, 65, 75, 100, 135, 180, 300 |
| Cooke | Panchro/i Classic FF | 18, 21, 25, 27, 32, 40, 50, 65, 75, 100, 135, 152 |
| Cooke | SP3 | 18, 25, 32, 50, 75, 100 |
| ZEISS | Supreme Prime | 15, 18, 21, 25, 29, 35, 40, 50, 65, 85, 100, 135, 150, 200 |
| ZEISS | Supreme Prime Radiance | 18, 21, 25, 29, 35, 40, 50, 65, 85, 100, 135 |
| ZEISS | CP.3 | 15, 18, 21, 25, 28, 35, 50, 85, 100, 135 |
| Leitz | Summilux-C | 16, 18, 21, 25, 29, 35, 40, 50, 65, 75, 100, 135 |
| Leitz | Summicron-C | 15, 18, 21, 25, 29, 35, 40, 50, 75, 100, 135 |
| Leitz | Leitz Prime | 18, 21, 25, 29, 35, 40, 50, 65, 75, 100, 135, 180, 350 |
| Leitz | Elsie | 15, 18, 21, 25, 29, 35, 40, 50, 65, 75, 100, 125, 150 |
| Leitz | Hektor | 18, 25, 35, 50, 73, 100 |
| Sigma | Cine FF High Speed | 14, 20, 24, 28, 35, 40, 50, 65, 85, 105, 135 |
| Sigma | Cine FF Classic Prime | 14, 20, 24, 28, 35, 40, 50, 65, 85, 105, 135 |
| DZOFilm | Vespid | 12, 16, 21, 25, 35, 40, 50, 75, 90, 100, 125 |
| DZOFilm | Vespid Retro | 16, 25, 35, 50, 75, 100, 125 |
| DZOFilm | Vespid Prime 2 | 18, 24, 35, 50, 85, 105 |
| DZOFilm | Arles | 14, 18, 21, 25, 35, 40, 50, 75, 100, 135, 180 |
| DZOFilm | Gnosis Macro | 24, 32, 65, 90 |
| XEEN | XEEN CF | 16, 24, 35, 50, 85, 135 |
| XEEN | XEEN Meister | 14, 24, 35, 50, 85 |
| Samyang | VDSLR MK2 | 14, 24, 35, 50, 85, 135 |
| Samyang | V-AF | 20, 24, 35, 45, 75, 100 |
| Tokina | Vista Prime | 18, 21, 25, 29, 35, 40, 50, 65, 85, 105, 135 |
| Irix | Irix Cine | 11, 15, 21, 30, 45, 65, 150, 150 |
| SIRUI | Nightwalker | 16, 24, 35, 55, 75 |
| SIRUI | Jupiter | 24, 35, 50, 75, 100 |
| SIRUI | Vision Prime | 15, 24, 35, 50, 75, 150 |
| Meike | FF Prime Cine | 16, 24, 35, 50, 85, 105, 135 |
| Meike | S35 Prime Cine | 12, 18, 25, 35, 50, 75, 100 |

| Angenieux | Optimo Prime | 18, 21, 24, 28, 32, 40, 50, 60, 75, 100, 135, 200 |

## Lenses (phase 2: cine zooms)

| Maker | Series | Focal range (mm) | Stop |
|---|---|---|---|
| Angenieux | Optimo Ultra 12x S35 | 24–290 | T2.8 |
| Angenieux | Optimo Ultra 12x U35 | 26–320 | T3.1 |
| Angenieux | Optimo Ultra 12x FF/VV | 36–435 | T4.2 |
| Angenieux | Optimo Ultra Compact FF/VV | 37–102 | T2.9 |
| Angenieux | Optimo Ultra Compact U35 | 28–76 | T2.2 |
| Angenieux | Optimo Ultra Compact FF/VV | 21–56 | T2.9 |
| Angenieux | Optimo Ultra Compact U35 | 16–42 | T2.2 |
| Angenieux | Type EZ-1 FF/VV | 45–135 | T3.0 |
| Angenieux | Type EZ-1 S35 | 30–90 | T2.0 |
| Angenieux | Type EZ-2 FF/VV | 22–60 | T3.0 |
| Angenieux | Type EZ-2 S35 | 15–40 | T2.0 |
| Angenieux | Type EZ-3 FF/VV | 68–250 | T3.5 |
| Angenieux | Type EZ-3 S35 | 45–165 | T2.3 |
| Angenieux | Optimo Style | 25–250 | T3.5 |
| Angenieux | Optimo Style | 16–40 | T2.8 |
| Angenieux | Optimo Style | 48–130 | T3.0 |
| Angenieux | Optimo | 24–290 | T2.8 |
| Angenieux | Optimo | 28–76 | T2.6 |
| Angenieux | Optimo | 45–120 | T2.8 |
| Angenieux | Optimo | 19.5–94 | T2.6 |
| Angenieux | Optimo Anamorphic A2S | 56–152 | T4.0 (2x anamorphic) |
| Angenieux | Optimo Anamorphic A2S | 42–420 | T4.5 (2x anamorphic) |
| Angenieux | Optimo Anamorphic A2S | 44–440 | T4.5 (2x anamorphic) |
| Angenieux | Optimo Anamorphic A2S | 30–72 | T4.0 (2x anamorphic) |
| ARRI | Signature Zoom | 16–32 | T2.8 |
| ARRI | Signature Zoom | 24–75 | T2.8 |
| ARRI | Signature Zoom | 45–135 | T2.8 |
| ARRI | Signature Zoom | 65–300 | T2.8 |
| Fujinon | Premista | 19–45 | T2.9 |
| Fujinon | Premista | 28–100 | T2.9 |
| Fujinon | Premista | 80–250 | T2.9 |
| Fujinon | Cabrio | 14–35 | T2.9 |
| Fujinon | Cabrio | 19–90 | T2.9 |
| Fujinon | Cabrio | 85–300 | T2.9 |
| Fujinon | Cabrio | 25–300 | T3.5 |
| Fujinon | Cabrio | 20–120 | T3.5 |
| Fujinon | MK | 18–55 | T2.9 |
| Fujinon | MK | 50–135 | T2.9 |
| ZEISS | Lightweight Zoom LWZ.3 | 21–100 | T2.9 |
| ZEISS | Compact Zoom CZ.2 | 15–30 | T2.9 |
| ZEISS | Compact Zoom CZ.2 | 28–80 | T2.9 |
| ZEISS | Compact Zoom CZ.2 | 70–200 | T2.9 |
| ZEISS | Supreme Zoom Radiance | 15–30 | T2.9 |
| ZEISS | Supreme Zoom Radiance | 28–80 | T2.9 |
| ZEISS | Supreme Zoom Radiance | 70–200 | T2.9 |
| Sigma | Cine High Speed Zoom | 18–35 | T2 |
| Sigma | Cine High Speed Zoom | 50–100 | T2 |
| Sigma | Cine FF Zoom | 24–35 | T2.2 |
| Sigma | AF Cine | 28–45 | T2 |
| Sigma | AF Cine | 28–105 | T3 |
| DZOFilm | Pictor Zoom | 12–25 | T2.8 |
| DZOFilm | Pictor Zoom | 14–30 | T2.8 |
| DZOFilm | Pictor Zoom | 20–55 | T2.8 |
| DZOFilm | Pictor Zoom | 50–125 | T2.8 |
| DZOFilm | Catta Zoom | 18–35 | T2.9 |
| DZOFilm | Catta Zoom | 35–80 | T2.9 |
| DZOFilm | Catta Zoom | 70–135 | T2.9 |
| DZOFilm | Catta Ace | 18–35 | T2.9 |
| DZOFilm | Catta Ace | 35–80 | T2.9 |
| DZOFilm | Catta Ace | 70–135 | T2.9 |
| DZOFilm | Tango Zoom | 18–90 | T2.9 |
| DZOFilm | Tango Zoom | 65–280 | T2.9 |
| DZOFilm | Ling Lung | 10–24 | T2.9 |
| DZOFilm | Ling Lung | 20–70 | T2.9 |
| Canon | CN-E Compact-Servo | 18–80 | T4.4 |
| Canon | CN-E Compact-Servo | 70–200 | T4.4 |
| Canon | CN-E Flex Zoom | 20–50 | T2.4 |
| Canon | CN-E Flex Zoom | 45–135 | T2.4 |
| Canon | CN-E Flex Zoom | 14–35 | T1.7 |
| Canon | CN-E Flex Zoom | 31.5–95 | T1.7 |
| Canon | CN-E Compact Zoom | 30–105 | T2.8 |
| Canon | CN-E Top End Zoom | 14.5–60 | T2.6 |
| Canon | CN-E Top End Zoom | 30–300 | T2.95 |
| Canon | Cine-Servo CN7x17 KAS T | 17–120 | T2.95 |
| Canon | Cine-Servo CN5x11 IAS T | 11–55 | T2.95 |
| Canon | Cine-Servo CN5x11 IAS T | 16.5–82.5 | T4.4 |
| Canon | Cine-Servo CN30x40 IAS J | 40–1200 | T5.0 |
| Canon | Cine-Servo CN30x40 IAS J | 60–1800 | T7.5 |
| Laowa | Ranger FF | 16–30 | T2.9 |
| Laowa | Ranger FF | 28–75 | T2.9 |
| Laowa | Ranger FF | 75–180 | T2.9 |
| Laowa | Ranger S35 | 11–18 | T2.9 |
| Laowa | Ranger S35 | 17–50 | T2.9 |
| Laowa | Ranger S35 | 50–130 | T2.9 |

## Lenses (phase 2: photo lenses)

| Maker | Series | Focal range (mm) | Stop |
|---|---|---|---|
| Sigma | Art DC HSM | 18–35 | f/1.8 |
| Sigma | Art DC HSM | 50–100 | f/1.8 |
| Sigma | Art DG OS HSM | 24–70 | f/2.8 |
| Sigma | Art DG HSM | 14–24 | f/2.8 |
| Sigma | Art DG OS HSM | 24–105 | f/4 |
| Sigma | Art DG HSM | 20 | f/1.4 |
| Sigma | Art DG HSM | 24 | f/1.4 |
| Sigma | Art DG HSM | 28 | f/1.4 |
| Sigma | Art DG HSM | 35 | f/1.4 |
| Sigma | Art DG HSM | 40 | f/1.4 |
| Sigma | Art DG HSM | 50 | f/1.4 |
| Sigma | Art DG HSM | 85 | f/1.4 |
| Sigma | Art DG HSM | 105 | f/1.4 |
| Sigma | Art DG HSM | 135 | f/1.8 |
| Sigma | Art DG DN | 24–70 | f/2.8 |
| Sigma | Art DG DN II | 24–70 | f/2.8 |
| Sigma | Contemporary DG DN | 28–70 | f/2.8 |
| Sigma | Contemporary DG DN | 16–28 | f/2.8 |
| Sigma | Art DG DN | 35 | f/1.4 |
| Sigma | Art DG DN | 50 | f/1.4 |
| Sigma | Contemporary DC DN | 18–50 | f/2.8 |
| Canon | EF L | 16–35 | f/2.8 |
| Canon | EF L | 16–35 | f/4 |
| Canon | EF L | 17–40 | f/4 |
| Canon | EF L | 24–70 | f/2.8 |
| Canon | EF L | 24–105 | f/4 |
| Canon | EF L | 70–200 | f/2.8 |
| Canon | EF L | 35 | f/1.4 |
| Canon | EF L | 50 | f/1.2 |
| Canon | EF L | 85 | f/1.4 |
| Canon | EF L Macro | 100 | f/2.8 |
| Canon | EF-S | 17–55 | f/2.8 |
| Panasonic | Leica DG Vario-Summilux | 10–25 | f/1.7 |
| Panasonic | Leica DG Vario-Summilux | 25–50 | f/1.7 |
| Panasonic | Leica DG Vario-Elmarit | 12–60 | f/2.8 |
| Panasonic | Lumix G X Vario II | 12–35 | f/2.8 |
| Panasonic | Lumix G X Vario II | 35–100 | f/2.8 |
| Panasonic | Leica DG Summilux | 12 | f/1.4 |
| Panasonic | Leica DG Summilux | 15 | f/1.7 |
| Panasonic | Leica DG Summilux | 25 | f/1.4 |
| OM System | M.Zuiko PRO | 12–40 | f/2.8 |
| Olympus | M.Zuiko PRO | 12–100 | f/4 |
| Olympus | M.Zuiko PRO | 7–14 | f/2.8 |
| Olympus | M.Zuiko PRO | 8–25 | f/4 |
| Olympus | M.Zuiko PRO | 17 | f/1.2 |
| Olympus | M.Zuiko PRO | 25 | f/1.2 |
| Olympus | M.Zuiko PRO | 45 | f/1.2 |
| Sony | G Master II | 24–70 | f/2.8 |
| Sony | G Master II | 16–35 | f/2.8 |
| Sony | G Master II | 70–200 | f/2.8 |
| Sony | G Master | 24 | f/1.4 |
| Sony | G Master | 35 | f/1.4 |
| Sony | G Master | 50 | f/1.2 |
| Sony | G Master | 50 | f/1.4 |
| Sony | G Master | 85 | f/1.4 |

## Cameras

| Maker | Model | Modes with sizes |
|---|---|---|
| Blackmagic Design | Pocket Cinema Camera 4K | 5 |
| Blackmagic Design | Pocket Cinema Camera 6K | 5 |
| Blackmagic Design | Pocket Cinema Camera 6K G2 | 5 |
| Blackmagic Design | Pocket Cinema Camera 6K Pro | 5 |
| Blackmagic Design | Cinema Camera 6K | 7 |
| Blackmagic Design | PYXIS 6K | 8 |
| Blackmagic Design | URSA Cine 12K LF | 8 |
| Blackmagic Design | PYXIS 12K | 8 |
| Blackmagic Design | URSA Cine 17K 65 | 8 |
| Blackmagic Design | URSA Mini Pro 12K OLPF | 7 |
| Blackmagic Design | URSA Mini Pro 4.6K G2 | 5 |
| ARRI | ALEXA 35 | 8 |
| ARRI | ALEXA 35 Xtreme | 8 |
| ARRI | ALEXA Mini LF | 7 |
| ARRI | ALEXA LF | 3 |
| ARRI | ALEXA 265 | 3 |
| ARRI | ALEXA Mini | 5 |
| ARRI | AMIRA | 4 |
| RED | V-RAPTOR 8K VV | 8 |
| RED | V-RAPTOR [X] 8K VV | 8 |
| RED | V-RAPTOR XL 8K VV | 8 |
| RED | V-RAPTOR XL [X] 8K VV | 8 |
| RED | V-RAPTOR 8K S35 | 6 |
| RED | KOMODO 6K | 8 |
| RED | KOMODO-X 6K | 8 |
| Sony | FX3 (ILME-FX3) | 2 |
| Sony | FX30 (ILME-FX30) | 4 |
| Sony | FX2 (ILME-FX2) | 2 |
| Sony | FX6 (ILME-FX6) | 0 (no published sizes, hidden) |
| Sony | PXW-FX9 | 0 (no published sizes, hidden) |
| Sony | BURANO (MPC-2610) | 6 |
| Sony | VENICE (MPC-3610) | 6 |
| Sony | VENICE 2 (8K sensor block) | 6 |
| Sony | VENICE 2 (6K sensor block) | 6 |
| Canon | EOS C70 | 3 |
| Canon | EOS C300 Mark III | 3 |
| Canon | EOS C500 Mark II | 5 |
| Canon | EOS C400 | 5 |
| Canon | EOS C80 | 4 |
| Canon | EOS R5 C | 5 |
| Panasonic | LUMIX GH6 | 4 |
| Panasonic | LUMIX GH7 | 4 |
| Panasonic | LUMIX BGH1 | 1 |
| Panasonic | LUMIX S1H | 3 |
| Panasonic | LUMIX BS1H | 2 |
| Panasonic | LUMIX S5IIX | 4 |
| Nikon | Z9 | 4 |
| Nikon | ZR | 4 |
| Fujifilm | X-H2S | 4 |
| Fujifilm | GFX ETERNA 55 | 1 |
| Kinefinity | MAVO Edge 6K | 6 |
| Kinefinity | MAVO Edge 8K | 6 |
| DJI | Ronin 4D Zenmuse X9-6K | 0 (no published sizes, hidden) |
| DJI | Ronin 4D Zenmuse X9-8K | 0 (no published sizes, hidden) |

## Lens distortion

Distortion profiles come from the [Lensfun](https://github.com/lensfun/lensfun) database (measured calibrations,
licensed CC BY-SA 3.0; this derived file `distortion.csv` is under the same licence). Only lenses with the same
optical design are matched (e.g. Lensfun's "Canon EF 24-70mm f/2.8L II USM" for our `canon_ef_24_70_f28l_ii`);
lenses without an exact match have no profile. Coefficients are kept as published (ptlens a, b, c or poly3 k1) with
the calibration crop factor and aspect ratio; the app rescales them to focal-length units the same way Lensfun does.
Cine lens makers don't publish distortion data, so cine lenses have no profile. Refresh: `tools/lensfun_extract.py`.

## Focus and depth of field

Thin-lens formulas with a circle of confusion of sensor-area diagonal / 1500 (0.018 mm on a Pocket 6K, 0.029 mm
full frame). T-stops are used as the stop (slightly deeper than the true f-number result). The close-focus
distance from the lens data warns when the subject is closer than the lens can focus.

## Next phases

Angenieux zooms (when the maker's site can be read), more photo lenses (Nikon, Fujifilm, more MFT), and
speed boosters / adapters as focal-length multipliers.
