#!/data/data/com.termux/files/usr/bin/bash
# Reels Wait: download the newest successful debug build and open the
# installer. Run from Termux on the phone:  bash scripts/update-reels-wait.sh
set -euo pipefail

REPO="langloisgabriel03/EstiDoomscroll"
BRANCH="claude/awaydoomscrollin-wait-timer-gumckz"
WORK="$HOME/.cache/reels-wait"
DEST="$HOME/storage/downloads/reels-wait.apk"

command -v gh >/dev/null || { echo "Install gh first:  pkg install gh"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "Log in first:  gh auth login"; exit 1; }
[ -d "$HOME/storage/downloads" ] || { echo "Run termux-setup-storage, tap Allow, then try again."; exit 1; }

RUN_ID=$(gh run list -R "$REPO" -w build-debug.yml -b "$BRANCH" -s success -L 1 \
  --json databaseId -q '.[0].databaseId')
[ -n "$RUN_ID" ] || { echo "No successful build found."; exit 1; }
echo "Latest build: run $RUN_ID"

rm -rf "$WORK"
gh run download "$RUN_ID" -R "$REPO" -D "$WORK"
APK=$(find "$WORK" -name '*.apk' | head -n 1)
[ -n "$APK" ] || { echo "The build has no APK."; exit 1; }
cp "$APK" "$DEST"
echo "Copied to Downloads/reels-wait.apk"

# Opens Android's installer. If nothing appears, open Files > Downloads >
# reels-wait.apk instead (or allow Termux to install apps when asked).
termux-open "$DEST" 2>/dev/null || echo "Open Files > Downloads > reels-wait.apk to install."
