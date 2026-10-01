#!/usr/bin/env python3
import json
import os
import subprocess
import sys
from pathlib import Path

MAX_CHARS_PER_FILE = 24000
REQUIRED = (
    "MOTORLAB_PROJECT_HOOK.txt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
    ".motorlab/MOTORLAB_LOCAL_CORE.txt",
)


def repo_root(cwd: str) -> Path:
    try:
        result = subprocess.run(
            ["git", "-C", cwd, "rev-parse", "--show-toplevel"],
            text=True,
            capture_output=True,
            timeout=1,
            check=False,
        )
        if result.returncode == 0 and result.stdout.strip():
            return Path(result.stdout.strip())
    except Exception:
        pass
    return Path(cwd).resolve()


def read_bounded(path: Path) -> str:
    try:
        text = path.read_text(encoding="utf-8")
    except Exception:
        return ""
    return text[:MAX_CHARS_PER_FILE]


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0

    if payload.get("hook_event_name") != "SessionStart":
        return 0

    cwd = str(payload.get("cwd") or os.getcwd())
    root = repo_root(cwd)
    loaded = {}
    missing = []
    for relative in REQUIRED:
        content = read_bounded(root / relative)
        if content:
            loaded[relative] = content
        else:
            missing.append(relative)

    if missing:
        context = (
            "MotorLab bootstrap detected a control-plane defect. "
            f"Missing/unreadable: {', '.join(missing)}. "
            "Use only safe read-only inspection until a verified MotorLab local fallback is restored. "
            "Do not invent MotorLab policy or silently continue write-capable project changes."
        )
    else:
        parts = [
            "MotorLab bootstrap is mandatory for this MusicLab session.",
            'First user-visible line: "⚙️ MotorLab attivo".',
            "MotorLab coexists with and preserves the native MusicLab engine; it never replaces project/domain behavior.",
            "Apply the verified local MotorLab contract below before substantive work. Preserve current operation/checkpoint/progress and single-writer safety.",
        ]
        for relative in REQUIRED:
            parts.append(f"\n--- {relative} ---\n{loaded[relative]}")
        context = "\n".join(parts)

    output = {
        "hookSpecificOutput": {
            "hookEventName": "SessionStart",
            "additionalContext": context,
        }
    }
    sys.stdout.write(json.dumps(output, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
