#!/bin/bash
# Builds and opens the Mac version of BMPCC Control.  Run:  bash run_mac.command
cd "$(dirname "$0")" || exit 1
mkdir -p logs
command -v xcodegen >/dev/null || { echo "Run test.command first (it installs XcodeGen)."; exit 1; }
xcodegen generate >/dev/null || exit 1
DD=".build/DerivedData"
echo "Building…"
if xcodebuild build -project BMPCCControl.xcodeproj -scheme BMPCCControl-macOS -configuration Debug \
     -destination "platform=macOS" -derivedDataPath "$DD" \
     CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
     > logs/run-mac.log 2>&1; then
  open "$DD/Build/Products/Debug/BMPCCControl.app"
else
  echo "Build failed:"; grep -E "error:" logs/run-mac.log | head -30
  exit 1
fi
