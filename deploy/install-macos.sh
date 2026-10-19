#!/bin/bash
# Build and install leoagent-server as a per-user launchd agent (macOS).
#   ./deploy/install-macos.sh              server only
#   ./deploy/install-macos.sh --with-stt   also set up local voice input (downloads ~1 GB of models)
#   ./deploy/install-macos.sh --desktop-sync   build with the experimental desktop-sync feature
#   ./deploy/install-macos.sh --uninstall
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN_DIR="${LEOAGENT_BIN_DIR:-$HOME/.local/bin}"; BIN="$BIN_DIR/leoagent-server"
LA="$HOME/Library/LaunchAgents"; UID_N="$(id -u)"
WITH_STT=0; FEATURES=""
for a in "$@"; do case "$a" in
  --with-stt) WITH_STT=1 ;;
  --desktop-sync) FEATURES="--features desktop-sync" ;;
  --uninstall)
    for l in io.leoagent.server io.leoagent.stt; do launchctl bootout "gui/$UID_N/$l" 2>/dev/null || true; rm -f "$LA/$l.plist"; done
    echo "uninstalled launchd agents (config and data were kept)"; exit 0 ;;
  *) echo "unknown option $a"; exit 2 ;;
esac; done

command -v cargo >/dev/null || { echo "cargo not found — install Rust from https://rustup.rs"; exit 1; }
echo "==> building server"; (cd "$ROOT/server" && cargo build --release $FEATURES)
mkdir -p "$BIN_DIR"; install -m 755 "$ROOT/server/target/release/leoagent-server" "$BIN"
"$BIN" init >/dev/null
CFG="$("$BIN" config-path)"
DATA="$(python3 -c 'import tomllib,os,sys;print(os.path.expanduser(tomllib.load(open(sys.argv[1],"rb")).get("data_dir","~/.local/share/leoagent")))' "$CFG")"
mkdir -p "$DATA/bin"
if command -v swiftc >/dev/null; then echo "==> building frontwin helper"; swiftc -O "$ROOT/server/helpers/frontwin.swift" -o "$DATA/bin/frontwin"; else echo "swiftc not found: front-app screen preview disabled"; fi

render() { sed -e "s#__BIN__#$BIN#g" -e "s#__HOME__#$HOME#g" -e "s#__DATA__#$DATA#g" -e "s#__PY__#${PY:-}#g" -e "s#__STT__#$ROOT/stt/stt.py#g" -e "s#__MODELS__#$ROOT/stt/models#g" "$1" > "$2"; }
load() { launchctl bootout "gui/$UID_N/$1" 2>/dev/null || true; launchctl bootstrap "gui/$UID_N" "$LA/$1.plist"; }
mkdir -p "$LA"
render "$ROOT/deploy/io.leoagent.server.plist.in" "$LA/io.leoagent.server.plist"; load io.leoagent.server

if [ "$WITH_STT" = 1 ]; then
  echo "==> setting up STT"
  command -v ffmpeg >/dev/null || echo "WARNING: ffmpeg not found (brew install ffmpeg) — needed for non-PCM audio"
  python3 -m venv "$ROOT/stt/venv"; "$ROOT/stt/venv/bin/pip" install -q -r "$ROOT/stt/requirements.txt"
  "$ROOT/stt/download-models.sh"
  PY="$ROOT/stt/venv/bin/python"; render "$ROOT/deploy/io.leoagent.stt.plist.in" "$LA/io.leoagent.stt.plist"; load io.leoagent.stt
  grep -q '^stt_url = "http' "$CFG" || echo "NOTE: set stt_url = \"http://127.0.0.1:8791\" in $CFG and restart: launchctl kickstart -k gui/$UID_N/io.leoagent.server"
fi
echo
echo "installed: $BIN"
echo "config:    $CFG   (set public_url, then run: $BIN pair)"
echo "logs:      $DATA/server.log"
echo "Screen preview (PiP) needs: System Settings → Privacy & Security → Screen Recording → allow $BIN"
