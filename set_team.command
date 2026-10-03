#!/bin/bash
# Saves your Apple Team ID so Xcode can install the app on your iPhone.  Run:  bash set_team.command
# First add your Apple ID in Xcode: Xcode > Settings > Accounts > "+" > Apple ID (a free one is fine).
cd "$(dirname "$0")" || exit 1
TEAMS=$(defaults export com.apple.dt.Xcode - 2>/dev/null | python3 -c '
import plistlib, sys
try: data = plistlib.loads(sys.stdin.buffer.read())
except Exception: sys.exit(0)
seen = {}
def walk(x):
    if isinstance(x, dict):
        if "teamID" in x: seen[x["teamID"]] = (x.get("teamName", "?"), x.get("teamType", ""))
        for v in x.values(): walk(v)
    elif isinstance(x, list):
        for v in x: walk(v)
walk(data)
for tid, (name, kind) in seen.items(): print(f"{tid}\t{name}\t{kind}")')
if [ -z "$TEAMS" ]; then
  echo "No Apple ID found in Xcode yet."
  echo "Open Xcode > Settings > Accounts, click + and sign in with your Apple ID, then run this again."
  exit 1
fi
echo "Teams found:"; echo "$TEAMS" | nl -w2 -s') '
COUNT=$(echo "$TEAMS" | wc -l | tr -d ' ')
N=1
[ "$COUNT" -gt 1 ] && read -r -p "Choose a number: " N
TEAM=$(echo "$TEAMS" | sed -n "${N}p" | cut -f1)
[ -z "$TEAM" ] && { echo "No change."; exit 1; }
printf 'DEVELOPMENT_TEAM = %s\n' "$TEAM" > Config/Local.xcconfig
echo "Saved Team ID $TEAM to Config/Local.xcconfig (not uploaded to GitHub)."
echo "Next: bash open_xcode.command, pick your iPhone at the top, press Run (▶)."
