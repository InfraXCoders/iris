#!/bin/bash
# Builds and tests BMPCC Control: the RecceKit maths, the iPhone app (in the Simulator) and the Mac app.
# Run:  bash test.command        Logs go to the logs/ folder.
cd "$(dirname "$0")" || exit 1
mkdir -p logs
DD=".build/DerivedData"
FAIL=0

say() { printf '\n=== %s ===\n' "$1"; }
show_errors() { grep -E "error:|failed|FAILED|XCTAssert" "$1" | grep -v "^$" | head -40; }

# 0. Tools
command -v xcodebuild >/dev/null || { echo "Xcode is missing. Install it from the App Store, open it once, then retry."; exit 1; }
if ! command -v xcodegen >/dev/null; then
  if command -v brew >/dev/null; then brew install xcodegen || exit 1
  else echo "XcodeGen is missing. Install Homebrew (https://brew.sh) then: brew install xcodegen"; exit 1; fi
fi

# 1. RecceKit (optics, sun, notes, data format)
say "1/4 RecceKit tests"
if (cd RecceKit && swift test) > logs/1-reccekit.log 2>&1; then
  echo "RecceKit: PASS ($(grep -Eo 'Executed [0-9]+ tests' logs/1-reccekit.log | tail -1))"
else
  echo "RecceKit: FAIL"; show_errors logs/1-reccekit.log; FAIL=1
fi

# 2. Xcode project
say "2/4 Generate Xcode project"
xcodegen generate > logs/2-xcodegen.log 2>&1 && echo "BMPCCControl.xcodeproj: OK" || { echo "xcodegen: FAIL"; cat logs/2-xcodegen.log; exit 1; }

# 3. iPhone app in the Simulator
say "3/4 iPhone app (Simulator)"
UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
data = json.load(sys.stdin)["devices"]
best = None
for runtime, devs in data.items():
    if "iOS" not in runtime: continue
    ver = tuple(int(x) for x in runtime.split("iOS-")[-1].split("-") if x.isdigit())
    for d in devs:
        if d.get("isAvailable") and d["name"].startswith("iPhone"):
            if best is None or ver > best[0]: best = (ver, d["udid"], d["name"])
print(best[1] if best else "")')
if [ -z "$UDID" ]; then
  echo "No iPhone simulator found. In Xcode: Settings > Components, install an iOS simulator, then retry."; FAIL=1
else
  if xcodebuild test -project BMPCCControl.xcodeproj -scheme BMPCCControl-iOS \
       -destination "id=$UDID" -derivedDataPath "$DD" > logs/3-ios.log 2>&1; then
    echo "iPhone app: PASS"
  else
    echo "iPhone app: FAIL"; show_errors logs/3-ios.log; FAIL=1
  fi
fi

# 4. Mac app
say "4/4 Mac app"
if xcodebuild test -project BMPCCControl.xcodeproj -scheme BMPCCControl-macOS \
     -destination "platform=macOS" -derivedDataPath "$DD" \
     CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
     > logs/4-macos.log 2>&1; then
  echo "Mac app: PASS"
else
  echo "Mac app: FAIL"; show_errors logs/4-macos.log; FAIL=1
fi

echo
if [ $FAIL = 0 ]; then echo "ALL PASS. Open the Mac app with: bash run_mac.command"
else echo "Something failed. Full logs are in the logs/ folder. Paste the lines above to Claude."; fi
exit $FAIL
