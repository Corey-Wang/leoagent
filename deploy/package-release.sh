#!/usr/bin/env bash
# Build local release artifacts. This script never installs, deploys or publishes.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
destination="${1:?Usage: package-release.sh NEW_OUTPUT_DIRECTORY [SIGNED_APK]}"
if [[ -e "$destination" ]]; then
  echo "Output directory already exists; select a new directory." >&2
  exit 2
fi
# Compiler diagnostics/panic strings must not disclose the maintainer's home or checkout.
python3 - "$repo" <<'PY'
import os
from pathlib import Path
import shlex
import subprocess
import sys

env = dict(os.environ)
encoded = env.get("CARGO_ENCODED_RUSTFLAGS")
flags = encoded.split("\x1f") if encoded else shlex.split(env.get("RUSTFLAGS", ""))
flags += [f"--remap-path-prefix={Path.home()}=/build", f"--remap-path-prefix={sys.argv[1]}=/source"]
env["CARGO_ENCODED_RUSTFLAGS"] = "\x1f".join(flags)
subprocess.run(["cargo", "build", "--locked", "--release", "--manifest-path", str(Path(sys.argv[1]) / "server/Cargo.toml")], env=env, check=True)
PY
mkdir -p "$destination"
destination="$(cd "$destination" && pwd)"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/leoagent/docs" "$stage/leoagent/deploy"
cp "$repo/server/target/release/leoagent-server" "$stage/leoagent/"
cp "$repo/LICENSE" "$repo/NOTICE" "$repo/README.md" "$stage/leoagent/"
cp "$repo"/docs/*.md "$repo"/docs/*.json "$stage/leoagent/docs/"
cp "$repo/docs/icon.png" "$stage/leoagent/docs/"
cp -R "$repo/docs/img" "$stage/leoagent/docs/"
cp "$repo/deploy/config.example.toml" "$repo/deploy/install-macos.sh" "$repo/deploy/io.leoagent.server.plist.in" "$stage/leoagent/deploy/"
cp "$repo/deploy/README.binary.md" "$stage/leoagent/START-HERE.md"
mkdir -p "$stage/leoagent/server/helpers"
cp "$repo/server/helpers/frontwin.swift" "$stage/leoagent/server/helpers/"
platform="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
archive="leoagent-server-$platform.tar.gz"
# Normalize ownership and omit filesystem extended attributes on every host platform.
python3 - "$stage" "$destination/$archive" <<'PY'
from pathlib import Path
import sys
import tarfile

def public_metadata(member):
    member.uid = member.gid = 0
    member.uname = member.gname = "root"
    member.pax_headers = {}
    return member

with tarfile.open(sys.argv[2], "w:gz", format=tarfile.USTAR_FORMAT) as archive:
    archive.add(Path(sys.argv[1]) / "leoagent", arcname="leoagent", filter=public_metadata)
PY
if [[ -n "${2:-}" ]]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$sdk" ]]; then
    echo "ANDROID_HOME is required to verify the supplied APK." >&2
    exit 2
  fi
  signer=""
  for candidate in "$sdk"/build-tools/*/apksigner; do
    if [[ -f "$candidate" ]]; then signer="$candidate"; fi
  done
  if [[ -z "$signer" ]]; then echo "No Android apksigner found." >&2; exit 2; fi
  aapt="$(dirname "$signer")/aapt"
  "$signer" verify --print-certs "$2" > "$destination/APK-SIGNING.txt"
  "$aapt" dump badging "$2" > "$stage/apk.txt"
  if ! head -1 "$stage/apk.txt" | grep -q "package: name='io.leoagent.app' "; then
    echo "Refusing an APK with the wrong application ID." >&2
    exit 2
  fi
  if grep -qi 'CN=Android Debug' "$destination/APK-SIGNING.txt"; then
    echo "Refusing an APK signed with an Android debug certificate." >&2
    exit 2
  fi
  cp "$2" "$destination/leoagent.apk"
fi
(
  cd "$destination"
  for file in "$archive" leoagent.apk APK-SIGNING.txt; do
    if [[ -f "$file" ]]; then
      if command -v sha256sum >/dev/null; then sha256sum "$file"; else shasum -a 256 "$file"; fi
    fi
  done
) > "$destination/SHA256SUMS"
echo "Local artifacts: $destination"
