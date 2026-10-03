#!/bin/bash
# iris setup for macOS.  Run once:  bash setup.command
cd "$(dirname "$0")" || exit 1
echo "=== iris setup ==="

# 1. Python 3.10+ (macOS's built-in python3 is 3.9, which is too old)
PY=""
for c in python3.13 python3.12 python3.11 python3.10 /opt/homebrew/bin/python3 /usr/local/bin/python3 python3; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
    PY="$(command -v "$c")"; break
  fi
done
if [ -z "$PY" ]; then
  echo "Python 3.10 or newer is needed. Install it from https://www.python.org/downloads/macos/"
  echo "(or with Homebrew:  brew install python@3.12)  then run this again."
  exit 1
fi
echo "Python: $("$PY" --version)  ($PY)"

# 2. Private Python environment + packages
[ -d .venv ] || "$PY" -m venv .venv || exit 1
source .venv/bin/activate
python -m pip install -q --upgrade pip
pip install -q -r requirements.txt || { echo "Package install failed."; exit 1; }

# 3. Settings file (no secrets in it)
if [ ! -f .env ]; then
  cat > .env <<'ENV'
# iris settings. API keys do NOT go here: they live in the macOS Keychain (setup.command stores them).
BRAIN_BACKEND=claude
# Stop a run before it spends more than this many tokens:
BRAIN_TOKEN_BUDGET=2000000
# auto = install updates automatically, check = only tell me, off = never
AUTO_UPDATE=check
AUTO_MODEL_FAMILY=sonnet
# UPDATE_URL=https://github.com/<you>/iris/releases/latest/download/version.json
# IRIS_WORKSPACE=~/iris-workspace
# IRIS_IOS_DESTINATION=platform=iOS Simulator,name=iPhone 16
# GITHUB_TOKEN and YOUTUBE_API_KEY (optional) also go in the Keychain.
ENV
  echo "Created .env (settings only, no keys)."
fi

# 4. Anthropic API key -> macOS Keychain
if security find-generic-password -s iris -a ANTHROPIC_API_KEY >/dev/null 2>&1; then
  echo "Anthropic API key: already in the Keychain."
else
  if grep -q '^ANTHROPIC_API_KEY=' .env 2>/dev/null; then
    echo "Your API key is stored in plain text in .env. It will be moved to the Keychain."
  fi
  echo
  echo "Paste your Anthropic API key when asked (it stays hidden). You will be asked twice."
  if security add-generic-password -U -s iris -a ANTHROPIC_API_KEY -l "iris: Anthropic API key" -w; then
    echo "Saved to the Keychain (service 'iris')."
    if grep -q '^ANTHROPIC_API_KEY=' .env 2>/dev/null; then
      sed -i '' '/^ANTHROPIC_API_KEY=/d' .env && echo "Removed the plain-text key from .env."
    fi
  else
    echo "Could not save the key. Run setup.command again."
  fi
fi

# 5. Xcode + XcodeGen
if ! xcodebuild -version >/dev/null 2>&1; then
  echo
  echo "Xcode not found. Install Xcode from the App Store, open it once, then run:"
  echo "  sudo xcode-select -s /Applications/Xcode.app"
fi
if ! command -v xcodegen >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    read -r -p "XcodeGen is needed to create Xcode projects. Install it now with Homebrew? [y/N] " a
    [[ "$a" =~ ^[Yy] ]] && brew install xcodegen
  else
    echo "XcodeGen is needed. Install Homebrew (https://brew.sh), then run:  brew install xcodegen"
  fi
fi

echo
python doctor.py
echo
echo "Next:  bash doctor.command   (builds a tiny test app on your Mac, no AI, no cost)"
echo "Then:  bash start.command    (opens iris in your browser)"
