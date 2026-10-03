#!/bin/bash
# iris without the browser.  Run:  bash start_cli.command
cd "$(dirname "$0")" || exit 1
source .venv/bin/activate || { echo "Run setup.command first."; exit 1; }
read -r -p "Describe the app to build: " TASK
[ -n "$TASK" ] && python main.py "$TASK" --platforms ios macos
