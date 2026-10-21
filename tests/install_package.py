#!/usr/bin/env python3
"""Verify a built archive by staging into temporary HOME; never call real launchctl."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import socket
import subprocess
import sys
import tarfile
import tempfile
import tomllib
import urllib.request
from integration import wait


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    archive = Path(sys.argv[1]).resolve()
    assert archive.is_file()
    with tempfile.TemporaryDirectory(prefix="leoagent-package-") as folder:
        root = Path(folder).resolve()
        distribution = root / "distribution"
        distribution.mkdir()
        with tarfile.open(archive) as tar:
            tar.extractall(distribution, filter="data")
        package = distribution / "leoagent"
        installer = package / "deploy/install-macos.sh"
        assert installer.is_file() and (package / "START-HERE.md").is_file()
        assert not (package / "server/Cargo.toml").exists(), "Must validate the binary path without source compilation"
        home = root / "home with & spaces"
        home.mkdir()
        data = home / "private data & history"
        config = home / "custom config & settings.toml"
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        config.write_text(f'listen="127.0.0.1:{port}"\ndata_dir={json.dumps(str(data))}\ntoken="package-test-legacy"\nlegacy_token_enabled=true\n')
        config_before = config.read_bytes()
        tools = root / "tools"
        tools.mkdir()
        for name in ("launchctl", "cargo"):
            (tools / name).write_text("#!/bin/sh\necho 'Unexpected launchctl/Cargo invocation in staged binary installation' >&2\nexit 97\n")
            (tools / name).chmod(0o700)
        env = dict(os.environ, HOME=str(home), LEOAGENT_CONFIG=str(config), XDG_CONFIG_HOME=str(home / ".config"),
                   PATH=str(tools) + os.pathsep + os.environ["PATH"], LEOAGENT_NOPROBE="1")
        # A new user's first staged installation creates private configuration without weakening pairing.
        fresh = root / "fresh home"
        fresh.mkdir()
        fresh_config = fresh / ".config/leoagent/config.toml"
        fresh_env = dict(env, HOME=str(fresh), LEOAGENT_CONFIG=str(fresh_config), XDG_CONFIG_HOME=str(fresh / ".config"))
        initial = subprocess.run(["bash", str(installer), "--no-start"], cwd=package, env=fresh_env, capture_output=True, text=True)
        assert initial.returncode == 0, initial.stdout + initial.stderr
        defaults = tomllib.loads(fresh_config.read_text())
        assert defaults["legacy_token_enabled"] is False and defaults["token"]
        assert fresh_config.stat().st_mode & 0o777 == 0o600
        for attempt in range(2):
            result = subprocess.run(["bash", str(installer), "--no-start"], cwd=package, env=env, capture_output=True, text=True)
            assert result.returncode == 0, result.stdout + result.stderr
            assert "Staged only" in result.stdout
            assert config.read_bytes() == config_before, "Installer rewrote old config/token/legacy switch"
            assert tomllib.loads(config.read_text())["legacy_token_enabled"]
            installed = home / ".local/bin/leoagent-server"
            assert digest(installed) == digest(package / "leoagent-server")
            plist = home / "Library/LaunchAgents/io.leoagent.server.plist"
            values = plistlib.loads(plist.read_bytes())
            assert values["EnvironmentVariables"]["HOME"] == str(home)
            assert values["WorkingDirectory"] == str(data)
            assert values["ProgramArguments"] == [str(installed), "serve", "--config", str(config)]
            if attempt:
                previous = list(installed.parent.glob("leoagent-server.previous-*"))
                assert len(previous) == 1 and digest(previous[0]) == digest(installed)
        # Exercise the actual staged binary and generated launchd arguments, without registration.
        with open(root / "server.log", "w") as log:
            process = subprocess.Popen(values["ProgramArguments"], cwd=values["WorkingDirectory"], env=env, stdout=log, stderr=log)
            try:
                def health():
                    try:
                        request = urllib.request.Request(f"http://127.0.0.1:{port}/api/health", headers={"X-Token": "package-test-legacy"})
                        with urllib.request.urlopen(request, timeout=2) as response:
                            return json.load(response)
                    except OSError:
                        return None
                assert wait(health)["ok"]
                assert config.read_bytes() == config_before
            finally:
                process.terminate(); process.wait(timeout=10)
        print("PASS: actual binary archive staged install/upgrade, XML-special paths, custom config, unchanged legacy token, previous binary, generated service arguments and isolated HTTP startup; no launchd registration")


if __name__ == "__main__":
    main()
