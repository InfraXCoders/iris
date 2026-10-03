#!/bin/bash
# Opens the project in Xcode (to run on your iPhone or the Simulator).  Run:  bash open_xcode.command
cd "$(dirname "$0")" || exit 1
command -v xcodegen >/dev/null || { echo "Run test.command first (it installs XcodeGen)."; exit 1; }
if [ ! -f Config/Local.xcconfig ]; then
  echo "Tip: to run on your iPhone, copy Config/Local.xcconfig.example to Config/Local.xcconfig"
  echo "     and put your Team ID in it (see README)."
fi
xcodegen generate && open BMPCCControl.xcodeproj
