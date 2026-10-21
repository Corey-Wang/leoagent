#!/bin/bash
# Build and install leoagent-server as a per-user launchd agent (macOS).
#   ./deploy/install-macos.sh              server only
#   ./deploy/install-macos.sh --with-stt   also set up local voice input (downloads ~1 GB of models)
#   ./deploy/install-macos.sh --desktop-sync   build with the experimental desktop-sync feature
#   ./deploy/install-macos.sh --no-start     stage files/config without loading or restarting launchd
#   ./deploy/install-macos.sh --uninstall
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN_DIR="${LEOAGENT_BIN_DIR:-$HOME/.local/bin}"; BIN="$BIN_DIR/leoagent-server"
LA="$HOME/Library/LaunchAgents"; UID_N="$(id -u)"
WITH_STT=0; FEATURES=""; START=1
for a in "$@"; do case "$a" in
  --with-stt) WITH_STT=1 ;;
  --no-start) START=0 ;;
  --desktop-sync) FEATURES="--features desktop-sync" ;;
  --uninstall)
    for l in io.leoagent.server io.leoagent.stt; do launchctl bootout "gui/$UID_N/$l" 2>/dev/null || true; rm -f "$LA/$l.plist"; done
    echo "uninstalled launchd agents (config and data were kept)"; exit 0 ;;
  *) echo "unknown option $a"; exit 2 ;;
esac; done

[[ "$(uname -s)" == Darwin ]] || { echo "This installer supports macOS; use the server binary directly on other platforms." >&2; exit 2; }
command -v python3 >/dev/null || { echo "Python 3.11+ is required." >&2; exit 1; }
python3 -c 'import tomllib' || { echo "Python 3.11+ is required." >&2; exit 1; }
if [[ "$WITH_STT" == 1 && ! -f "$ROOT/stt/stt.py" ]]; then echo "Voice input requires a source checkout; this binary archive does not contain STT." >&2; exit 2; fi
if [[ -f "$ROOT/server/Cargo.toml" ]]; then
  command -v cargo >/dev/null || { echo "cargo not found — install Rust from https://rustup.rs"; exit 1; }
  echo "==> building server"; (cd "$ROOT/server" && cargo build --locked --release $FEATURES)
  SOURCE_BIN="$ROOT/server/target/release/leoagent-server"
else
  [[ -z "$FEATURES" ]] || { echo "--desktop-sync requires a source checkout." >&2; exit 2; }
  SOURCE_BIN="$ROOT/leoagent-server"
  [[ -x "$SOURCE_BIN" ]] || { echo "Missing executable: $SOURCE_BIN" >&2; exit 1; }
fi
# Validate the platform before changing the installed executable. No Agent is started.
"$SOURCE_BIN" --version
mkdir -p "$BIN_DIR"
if [[ -e "$BIN" ]]; then
  PREVIOUS="$BIN.previous-$(date +%Y%m%d%H%M%S)-$$"
  cp -p "$BIN" "$PREVIOUS"
  echo "previous binary: $PREVIOUS"
fi
install -m 755 "$SOURCE_BIN" "$BIN.new-$$"
mv -f "$BIN.new-$$" "$BIN"
"$BIN" init >/dev/null
CFG="$(python3 -c 'import os,sys;print(os.path.abspath(sys.argv[1]))' "$("$BIN" config-path)")"
DATA="$(python3 -c 'import tomllib,os,sys;print(os.path.expanduser(tomllib.load(open(sys.argv[1],"rb")).get("data_dir","~/.local/share/leoagent")))' "$CFG")"
mkdir -p "$DATA/bin"
if [[ -f "$ROOT/server/helpers/frontwin.swift" ]] && command -v swiftc >/dev/null; then echo "==> building frontwin helper"; swiftc -O "$ROOT/server/helpers/frontwin.swift" -o "$DATA/bin/frontwin"; else echo "swiftc not found: front-app screen preview disabled"; fi

render() {
  python3 - "$1" "$2" "$BIN" "$HOME" "$DATA" "${PY:-}" "$ROOT/stt/stt.py" "$ROOT/stt/models" "$CFG" <<'PYRENDER'
import pathlib, plistlib, sys
from xml.sax.saxutils import escape
source, destination, *values = sys.argv[1:]
text = pathlib.Path(source).read_text()
for key, value in zip(("BIN", "HOME", "DATA", "PY", "STT", "MODELS", "CFG"), values):
    text = text.replace("__" + key + "__", escape(value))
plistlib.loads(text.encode())
pathlib.Path(destination).write_text(text)
PYRENDER
}
load() { launchctl bootout "gui/$UID_N/$1" 2>/dev/null || true; launchctl bootstrap "gui/$UID_N" "$LA/$1.plist"; }
mkdir -p "$LA"
render "$ROOT/deploy/io.leoagent.server.plist.in" "$LA/io.leoagent.server.plist"
if [[ "$START" == 1 ]]; then load io.leoagent.server; fi

if [ "$WITH_STT" = 1 ]; then
  echo "==> setting up STT"
  command -v ffmpeg >/dev/null || echo "WARNING: ffmpeg not found (brew install ffmpeg) — needed for non-PCM audio"
  python3 -m venv "$ROOT/stt/venv"; "$ROOT/stt/venv/bin/pip" install -q -r "$ROOT/stt/requirements.txt"
  "$ROOT/stt/download-models.sh"
  PY="$ROOT/stt/venv/bin/python"; render "$ROOT/deploy/io.leoagent.stt.plist.in" "$LA/io.leoagent.stt.plist"
  if [[ "$START" == 1 ]]; then load io.leoagent.stt; fi
  grep -q '^stt_url = "http' "$CFG" || echo "NOTE: set stt_url = \"http://127.0.0.1:8791\" in $CFG and restart: launchctl kickstart -k gui/$UID_N/io.leoagent.server"
fi
echo
if [[ "$START" == 0 ]]; then echo "Staged only: launchd was not loaded or restarted. Review configuration before starting."; fi
echo "installed: $BIN"
echo "config:    $CFG   (set public_url, then run: $BIN pair)"
echo "logs:      $DATA/server.log"
echo "Screen preview (PiP) needs: System Settings → Privacy & Security → Screen Recording → allow $BIN"
