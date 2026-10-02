#!/usr/bin/env python3
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path

KNOWN_TYPES = {
    "FORCE_PROGRESS_DISPLAY",
    "PROGRESS_PRESENCE",
    "STATUS_DETAIL_LEVEL",
    "FORCE_CHECKPOINT_STATUS",
}
SUPPORTED_EVENTS = {"SessionStart", "PostToolUse"}
DEFAULT_ENDPOINT = "https://motorlab-state-bridge.brunoverlezza.workers.dev/control/v1/directives"


def _repo_root(cwd: str) -> Path:
    try:
        out = subprocess.check_output(
            ["git", "-C", cwd, "rev-parse", "--show-toplevel"],
            text=True,
            stderr=subprocess.DEVNULL,
            timeout=2,
        ).strip()
        if out:
            return Path(out)
    except Exception:
        pass
    return Path(cwd).resolve()


def _project_id(cwd: str) -> str:
    override = os.environ.get("MOTORLAB_PROJECT_ID", "").strip()
    if override:
        return override
    try:
        remote = subprocess.check_output(
            ["git", "-C", cwd, "remote", "get-url", "origin"],
            text=True,
            stderr=subprocess.DEVNULL,
            timeout=2,
        ).strip()
        tail = remote.rstrip("/").split("/")[-1]
        if ":" in tail:
            tail = tail.split(":")[-1]
        if tail.endswith(".git"):
            tail = tail[:-4]
        if tail:
            return tail
    except Exception:
        pass
    return _repo_root(cwd).name


def _state_path(session_id: str) -> Path:
    base = Path(os.environ.get("MOTORLAB_HOOK_STATE_DIR") or (Path(tempfile.gettempdir()) / "motorlab-live-control-hooks"))
    base.mkdir(parents=True, exist_ok=True)
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", session_id or "unknown")
    return base / f"{safe}.json"


def _load_seen(session_id: str) -> set[str]:
    path = _state_path(session_id)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return set(data.get("seen_directive_ids", []))
    except Exception:
        return set()


def _save_seen(session_id: str, seen: set[str]) -> None:
    path = _state_path(session_id)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps({"seen_directive_ids": sorted(seen)}), encoding="utf-8")
    tmp.replace(path)


def _is_active(directive: dict, project_id: str) -> bool:
    if not isinstance(directive, dict):
        return False
    if directive.get("directive_type") not in KNOWN_TYPES:
        return False
    if directive.get("status", "pending") not in {"pending", "applied"}:
        return False
    scoped_project = directive.get("project_id")
    if scoped_project and scoped_project != project_id:
        return False
    if not directive.get("durable", False):
        expires_at = directive.get("expires_at")
        if not expires_at:
            return False
        try:
            from datetime import datetime, timezone
            expiry = datetime.fromisoformat(expires_at.replace("Z", "+00:00"))
            if expiry.tzinfo is None:
                expiry = expiry.replace(tzinfo=timezone.utc)
            if expiry.timestamp() <= time.time():
                return False
        except Exception:
            return False
    return True


def _fetch_central(project_id: str, session_id: str) -> list[dict]:
    endpoint = os.environ.get("MOTORLAB_CONTROL_ENDPOINT", DEFAULT_ENDPOINT).strip()
    query = {"project_id": project_id, "session_id": session_id}
    operation_id = os.environ.get("MOTORLAB_OPERATION_ID", "").strip()
    if operation_id:
        query["operation_id"] = operation_id
    url = endpoint + ("&" if "?" in endpoint else "?") + urllib.parse.urlencode(query)
    request = urllib.request.Request(url, headers={"accept": "application/json", "user-agent": "MotorLab-Codex-Hook/1.0"})
    with urllib.request.urlopen(request, timeout=2) as response:
        payload = json.loads(response.read().decode("utf-8"))
    directives = payload.get("directives", [])
    return directives if isinstance(directives, list) else []


def _fetch_fallback(repo_root: Path) -> list[dict]:
    path = repo_root / ".motorlab" / "LIVE_CONTROL.json"
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return []
    directives = payload.get("directives", []) if isinstance(payload, dict) else []
    return directives if isinstance(directives, list) else []


def _format_context(directives: list[dict]) -> str:
    pairs = [f"{d['directive_type']}={d.get('value', '')}" for d in directives]
    return (
        "MotorLab Live Control directives now active: "
        + "; ".join(pairs)
        + ". Preserve the current task, checkpoint, writer identity and verified progress floor. "
          "Apply these as presentation/orchestration directives only; do not restart the task or treat them as write approval."
    )


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0
    event = str(payload.get("hook_event_name") or "")
    if event not in SUPPORTED_EVENTS:
        return 0
    cwd = str(payload.get("cwd") or os.getcwd())
    session_id = str(payload.get("session_id") or "unknown")
    project_id = _project_id(cwd)
    repo_root = _repo_root(cwd)

    try:
        directives = _fetch_central(project_id, session_id)
    except Exception:
        directives = _fetch_fallback(repo_root)

    active = [d for d in directives if _is_active(d, project_id)]
    if not active:
        return 0
    seen = _load_seen(session_id)
    fresh = [d for d in active if d.get("directive_id") and d.get("directive_id") not in seen]
    if not fresh:
        return 0
    seen.update(str(d["directive_id"]) for d in fresh)
    _save_seen(session_id, seen)

    context = _format_context(fresh)
    result = {
        "systemMessage": "MotorLab Live Control: directive applied at safe point.",
        "hookSpecificOutput": {
            "hookEventName": event,
            "additionalContext": context,
        },
    }
    sys.stdout.write(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
