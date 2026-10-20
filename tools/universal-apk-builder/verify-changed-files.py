#!/usr/bin/env python3
"""MusicLab LAB-only reusable exact-path change guard (fail closed)."""
import re
import subprocess
import sys

SHA = re.compile(r"^[0-9a-fA-F]{40}$")

def allowed_paths(raw: str) -> set[str]:
    result = set()
    for path in (line.strip() for line in raw.splitlines()):
        if not path or path.startswith("#"):
            continue
        if (path.startswith("/") or "\\" in path or "*" in path or
                any(part in ("", ".", "..") for part in path.split("/"))):
            raise ValueError("Percorso autorizzato non valido: " + path)
        result.add(path)
    if not result:
        raise ValueError("Allowlist vuota")
    return result

def unexpected_paths(changed: set[str], allowed: set[str]) -> set[str]:
    return changed - allowed

def changed_files(base: str) -> set[str]:
    if not SHA.fullmatch(base):
        raise ValueError("Baseline SHA non valida")
    result = subprocess.run(
        ["git", "diff", "--name-only", "-z", base, "HEAD", "--"],
        check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    return {p.decode("utf-8", errors="replace") for p in result.stdout.split(b"\0") if p}

def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Uso: verify-changed-files.py BASELINE_SHA < allowed-paths.txt", file=sys.stderr)
        return 2
    try:
        allowed = allowed_paths(sys.stdin.read())
        changed = changed_files(argv[1])
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print("BLOCCO guard: " + str(exc), file=sys.stderr)
        return 2
    unexpected = unexpected_paths(changed, allowed)
    if unexpected:
        print("FORBIDDEN CHANGE: " + ", ".join(sorted(unexpected)), file=sys.stderr)
        return 1
    print("[MOTORLAB][GUARD] {} file modificati, tutti autorizzati".format(len(changed)))
    for path in sorted(changed):
        print("[MOTORLAB][GUARD] " + path)
    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
