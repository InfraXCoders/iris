#!/bin/bash
# Starts iris and opens http://localhost:8765.  Run:  bash start.command
cd "$(dirname "$0")" || exit 1
[ -d .venv ] || bash setup.command || exit 1
source .venv/bin/activate
while true; do
  python ui.py
  code=$?
  [ "$code" -eq 3 ] || break        # exit code 3 = "restart to apply update"
  echo "Restarting iris..."
done
