# iris

**One task in, a tested iPhone + Mac app out.** iris is a team of AI agents that research, design, code, build, test and fix a SwiftUI app until its tests pass, then remember what worked.

```
Task ─► Orchestrator ─► Research (parallel) ─► Architect ─► scaffold (no AI) ─► Coding + Testing
                                                                                     │
        ┌──────────── FAIL ◄── Build + Test (Xcode: iPhone simulator, then Mac) ◄─────┘
        ▼
   Debug + Research ─► fix ─► retest ... ─► PASS ─► save verified knowledge + reusable skill
```

## Set up your Mac (5 steps)

1. **Install Xcode** from the App Store. Open it once, accept the licence, and let it install the iOS simulator.
2. **Install Python 3.10+** from [python.org](https://www.python.org/downloads/macos/) (the built-in macOS Python is too old) and **Homebrew** from [brew.sh](https://brew.sh).
3. **Get an Anthropic API key** at [console.anthropic.com](https://console.anthropic.com). Set a **monthly spend limit** there first.
4. In Terminal, in this folder, run:
   ```
   bash setup.command
   ```
   This installs packages and XcodeGen, stores your API key in the **macOS Keychain** (never in a file), and runs a check.
5. Prove the Xcode pipeline works, with **no AI and no cost**:
   ```
   bash doctor.command
   ```
   It builds and tests a tiny app on the iPhone simulator and on your Mac. You want to see `ios=PASS  macos=PASS`.

Then start iris:

```
bash start.command          # opens http://localhost:8765
bash start_cli.command      # or the text version
```

> **Tip:** run the scripts with `bash …` the first time. Double-clicking a downloaded `.command` file triggers a macOS security warning.

## What you get

Each run creates a project folder in **`~/iris-workspace/<name>-<time>/`**:

```
ARCHITECTURE.md      the plan
apple/project.yml    XcodeGen spec (iris generates the .xcodeproj from it)
apple/Shared/        SwiftUI code for iPhone AND Mac
apple/iOS/, macOS/   platform-only code
apple/Tests/         XCTest unit tests (run on both)
skills/              the reusable skill, saved when everything passed
```

To open it in Xcode: `cd ~/iris-workspace/<project>/apple && xcodegen generate && open *.xcodeproj`.

**Run it on your iPhone:** in Xcode, pick the `<Name>-iOS` scheme, select your iPhone, and under *Signing & Capabilities* choose your Apple ID team. A free Apple ID works, but the app must be re-installed every 7 days. TestFlight and the App Store need the paid Apple Developer Program.

## Where things live

| What | Where |
|---|---|
| iris code | this folder |
| Settings (no secrets) | `.env` in this folder |
| API keys | macOS Keychain, service `iris` |
| Memory + state | `~/Library/Application Support/iris/` |
| Generated apps | `~/iris-workspace/` |

## Settings (`.env`)

| Setting | Default | Meaning |
|---|---|---|
| `BRAIN_TOKEN_BUDGET` | 2000000 | Stop a run before it spends more tokens than this |
| `BRAIN_MODEL` | newest Sonnet | Pin a model, e.g. `claude-sonnet-5-5` |
| `AUTO_UPDATE` | `check` | `auto`, `check` or `off` |
| `AUTO_MODEL_FAMILY` | `sonnet` | `sonnet`, `haiku` (cheaper) or `opus` (strongest) |
| `IRIS_WORKSPACE` | `~/iris-workspace` | Where generated apps go |
| `IRIS_IOS_DESTINATION` | newest iPhone simulator | e.g. `platform=iOS Simulator,name=iPhone 16` |
| `BRAIN_BACKEND` | `claude` | `openrouter`, `omniroute` or `airllm` (optional, off by default) |

Optional keys (GitHub, YouTube, OpenRouter) also go in the Keychain:
`security add-generic-password -U -s iris -a GITHUB_TOKEN -w`

## For developers

```
pip install -r requirements-dev.txt
python selftest.py          # fast sanity check (also run before every update)
python -m pytest            # full test suite, including attack tests
```

Android is still supported (`--platforms android`) but is off by default. See [SECURITY.md](SECURITY.md) and [CHANGELOG.md](CHANGELOG.md).
