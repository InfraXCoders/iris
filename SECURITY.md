# iris security model

iris lets AI agents write code and then **builds and runs that code on your Mac**. Treat every generated project as untrusted. The rules below limit what can go wrong, and each is covered by a test in `tests/`.

## What is enforced

| Rule | How | Test |
|---|---|---|
| Agents can only run approved commands | Allowlist: `xcodegen generate`, `xcodebuild`, `swift`, `gradlew`, safe `git` subcommands, `python -m pytest/unittest/py_compile`. No shell, no `; & \| > < $ \``, no absolute paths, no `..`, no `~` | `test_tools.py` |
| Agents' file access stays in the project | Paths are resolved (symlinks too). Writing into `.git` is blocked. 2 MB limit | `test_tools.py` |
| API keys stay out of builds | Keys are in the macOS Keychain, not in a file. Builds get a scrubbed environment with only `PATH`, `HOME`, `TMPDIR` and toolchain variables | `test_security.py`, `test_runner.py` |
| Generated apps can't casually read iris's files | Projects go to `~/iris-workspace`, data to `~/Library/Application Support/iris`, both outside the iris folder | `test_orchestrator.py` |
| Web content is data, not instructions | Agents are told so, and even a fully hijacked agent hits the allowlist | `test_security.py` (prompt injection) |
| Local web UI | Bound to 127.0.0.1, Host-header check (DNS rebinding), per-session token on every POST, input validation, security headers, all dynamic text escaped | `test_security.py` |
| Remote AI gateway | Plain `http` only to `localhost`/`127.0.0.1`/`::1`; everything else must be `https` | `test_security.py` |
| Self-updates | HTTPS only, Ed25519-signed manifest, SHA-256 check, no zip-slip or symlinks, no executable file types, new dependencies must be in `trusted_packages.txt`, selftest before install, backup + rollback. `.env`, memory and projects are never overwritten | `test_updater.py` |
| Secrets in logs | Known key formats and the actual key values are redacted from the log and the UI | `test_security.py` |
| Spending | `BRAIN_TOKEN_BUDGET` stops a run. Also set a monthly limit in the Anthropic console | — |

## Known risks (not solved yet)

1. **No sandbox yet.** `xcodebuild` runs build scripts and test code that the AI wrote, as **your user**. That code could read your files, use the network, or ask the Keychain for the iris key via the `security` tool. *Planned:* run builds in a macOS sandbox profile or a separate user account. Until then, review generated `project.yml` files for new "run script" build phases, and use a Mac you're comfortable experimenting on.
2. **Auto-rollback covers a failed install, not a broken start-up.** If an update installs but iris then won't start, use the **Rollback** button or `python -c "import updater; print(updater.rollback())"`.
3. **No human approval step yet** before the first build of generated code.

## Reporting

Found a problem? Don't open a public issue with exploit details. Contact the repo owner privately.
