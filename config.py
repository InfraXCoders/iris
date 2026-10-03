"""Loads settings: .env (optional, for non-secret settings), then API keys from the macOS Keychain.
Also forces UTF-8 console output."""
import os, sys
from pathlib import Path
for s in (sys.stdout, sys.stderr):
    try:
        s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
_env = Path(__file__).parent / ".env"
if _env.exists():
    for line in _env.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"'))

import keychain  # noqa: E402
keychain.load_into_env()
