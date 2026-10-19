#!/bin/bash
# Publish an APK for in-app updates: copies it to <data_dir>/app/leoagent.apk and writes version.json.
#   ./deploy/publish-apk.sh path/to/app.apk "release notes"
# versionCode/versionName are read from android/version.properties (bump them before building).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; APK="${1:?usage: publish-apk.sh APK [notes]}"; NOTES="${2:-}"
BIN="${LEOAGENT_BIN:-$(command -v leoagent-server || echo "$HOME/.local/bin/leoagent-server")}"
CFG="$("$BIN" config-path)"
DATA="$(python3 -c 'import tomllib,os,sys;print(os.path.expanduser(tomllib.load(open(sys.argv[1],"rb")).get("data_dir","~/.local/share/leoagent")))' "$CFG")"
VC=$(grep versionCode "$ROOT/android/version.properties" | cut -d= -f2); VN=$(grep versionName "$ROOT/android/version.properties" | cut -d= -f2)
mkdir -p "$DATA/app"; cp "$APK" "$DATA/app/leoagent.apk.tmp" && mv "$DATA/app/leoagent.apk.tmp" "$DATA/app/leoagent.apk"
python3 -c 'import json,sys;json.dump(dict(versionCode=int(sys.argv[1]),versionName=sys.argv[2],notes=sys.argv[3]),open(sys.argv[4],"w"),ensure_ascii=False)' "$VC" "$VN" "$NOTES" "$DATA/app/version.json"
echo "published v$VN ($VC) -> $DATA/app"
