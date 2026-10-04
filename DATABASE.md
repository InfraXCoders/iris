# Lens & camera database

The app's lens database, camera database and coverage tool read two plain CSV files:

- `RecceKit/Sources/RecceKit/Data/lenses.csv`: 294 lenses (anamorphic primes, phase 1)
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

**lenses.csv**: `id, manufacturer, series, focal_mm, t_stop, squeeze, close_focus_m, image_circle_mm, length_mm,
weight_g, front_diameter_mm, mounts (separated by ;), format (MFT/APS-C/S35/FF/LF/65), source_url, notes`

**cameras.csv**: one row per recording mode; the first row of each camera is its largest area.
`camera_id, manufacturer, model, mount, mode_id, mode_name, sensor_w_mm, sensor_h_mm, res_w, res_h, source_url, notes`

Use a new unique `id` for a new lens. Keep existing ids: saved shots refer to them.

## Known gaps (from the research)

- Xelmus Apollo: maker site blocked; no rows. Vazen and Great Joy 1.8x: no reachable official spec pages.
- ARRI Rental ALFA 72–190 mm: only focal length and squeeze published.
- Laowa: most per-lens specs are images on the maker's site, so many cells are empty.
- Sony FX6/FX9 and DJI Ronin 4D: no per-mode sizes published; the FX6 keeps its original built-in sensor size.
- Some crop-mode sizes are computed from pixel pitch (noted per row), e.g. all Blackmagic modes.

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

## Next phases

Spherical cine primes, then cine zooms, then the photo lenses commonly used on Pocket cameras.
