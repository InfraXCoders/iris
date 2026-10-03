# Changelog

## 2.0.0: iris (Mac-first)

Renamed from `ai_dev_brain`. Builds **iPhone + Mac** apps from one SwiftUI codebase.

### Fixed
- **The build → fix → retest loop crashed on the first failed build** (`TypeError: 'str' object is not callable`). The fail → debug → retry cycle had never run before.
- Generated code could read the API key from `.env` (the workspace was inside the app folder).
- The web UI accepted any platform name and used it as a folder name (path escape).
- The OmniRoute "local only" check accepted `http://localhost.attacker.example`.
- Failed build attempts were saved to memory as `verified`. They are now saved as `failed`.
- Results text in the web UI wasn't HTML-escaped.
- Paths starting with `~` are now rejected instead of creating a folder named `~`.

### Added
- **Apple build pipeline:** iris scaffolds an XcodeGen project (iPhone + Mac app targets and test targets) without AI, then runs `xcodegen generate` → `xcodebuild test`. It auto-detects the scheme and the newest iPhone simulator. A zero exit code without `** TEST SUCCEEDED **` counts as a failure, never a pass.
- Build logs put compiler errors and failed tests first, for the Debug agent.
- **API keys in the macOS Keychain** (service `iris`). `.env` still works, for backward compatibility.
- Data in `~/Library/Application Support/iris`, projects in `~/iris-workspace`. The old `brain_memory.db` is copied over automatically.
- `doctor.py` / `doctor.command`: checks the Mac and builds a sample app with no AI.
- Mac scripts: `setup.command`, `start.command`, `start_cli.command`, `doctor.command`.
- **Test suite:** 116 tests, including attack tests for blocked commands, prompt injection, forged/unsigned/tampered updates, secret leakage and web UI hardening.
- `SECURITY.md`.

### Changed
- Default platforms are `ios` and `macos`. Android is optional.
- Agent prompts are written for SwiftUI / iOS 17 / macOS 14.
- New installs default to `AUTO_UPDATE=check` (notify only).
- Removed the Windows `.bat` scripts and `README_WINDOWS.txt` (they remain in git history).

## 1.0.0
- `ai_dev_brain` as received.
