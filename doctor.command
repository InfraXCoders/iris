#!/bin/bash
# Checks Xcode, XcodeGen, simulator and API key, then builds + tests a tiny app. Uses no AI.
cd "$(dirname "$0")" || exit 1
source .venv/bin/activate || { echo "Run setup.command first."; exit 1; }
python doctor.py --smoke
