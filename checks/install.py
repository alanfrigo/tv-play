#!/usr/bin/env python3
"""Exercita primeira instalação, repetição e entrada inválida sem publicar portas."""

import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
real_docker = shutil.which("docker")
assert real_docker, "Docker necessário para validar configuração MediaMTX"

with tempfile.TemporaryDirectory(prefix="tvplay-install-") as temp:
    target = Path(temp)
    (target / "server").mkdir()
    (target / "bin").mkdir()
    for name in ("install.sh", "compose.yaml", "server/mediamtx.yml"):
        shutil.copy2(root / name, target / name)
    fake_docker = target / "bin/docker"
    fake_docker.write_text(
        '#!/bin/sh\n'
        'if [ "$1" = compose ] && [ "$2" = up ]; then\n'
        '  printf "started\\n" >> "$INSTALL_CHECK_LOG"\n'
        'else\n'
        f'  exec "{real_docker}" "$@"\n'
        'fi\n'
    )
    fake_docker.chmod(0o700)
    env = os.environ.copy()
    env.update(PATH=f"{target / 'bin'}:{env['PATH']}", INSTALL_CHECK_LOG=str(target / "starts"))
    for name in ("NAS_IP", "TV_PUBLISH_PASSWORD", "TV_VIEW_PASSWORD"):
        env.pop(name, None)

    def install(*args):
        return subprocess.run(
            ["sh", str(target / "install.sh"), *args], cwd=target, env=env,
            text=True, capture_output=True, timeout=120,
        )

    for bad in ("127.0.0.1", "192.168.3.035", "hostname", "192.168.3.255", "192.168.3.35;id"):
        result = install(bad)
        assert result.returncode != 0 and not (target / ".env").exists(), bad

    first = install("192.168.3.35")
    assert first.returncode == 0, first.stderr
    settings = dict(line.split("=", 1) for line in (target / ".env").read_text().splitlines())
    assert settings["NAS_IP"] == "192.168.3.35"
    assert settings["TV_PUBLISH_PASSWORD"] == settings["TV_VIEW_PASSWORD"] == "123"
    assert stat.S_IMODE((target / ".env").stat().st_mode) == 0o600
    assert settings["TV_PUBLISH_PASSWORD"] in first.stdout
    assert settings["TV_VIEW_PASSWORD"] in first.stdout

    again = install()
    assert again.returncode == 0, again.stderr
    assert dict(line.split("=", 1) for line in (target / ".env").read_text().splitlines()) == settings
    assert settings["TV_PUBLISH_PASSWORD"] not in again.stdout
    assert (target / "starts").read_text().splitlines() == ["started", "started"]

print("PASS: IP privado, senha padrão compartilhada, permissões e reinstalação sem troca de credenciais")
