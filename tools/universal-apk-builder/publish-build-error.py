#!/usr/bin/env python3
"""Temporary diagnostic: publish a redacted Gradle failure tail to central repo."""
from __future__ import annotations

import base64
import glob
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

TOKEN = os.environ.get("UAB_GITHUB_MANIFEST_TOKEN", "").strip()
TARGET = os.environ.get("UAB_MANIFEST_REPOSITORY", "Br174/Chatgpt").strip()
BRANCH = os.environ.get("UAB_MANIFEST_BRANCH", "main").strip() or "main"
SOURCE = os.environ.get("CM_REPO_SLUG", "Br174/MusicLab").strip()
COMMIT = os.environ.get("CM_COMMIT", "").strip()
PROJECT = os.environ.get("UAB_PROJECT_NAME", "MusicLab").strip() or "MusicLab"


def headers():
    return {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {TOKEN}",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": "application/json",
        "User-Agent": "UAB-Build-Diagnostic",
    }


def request(url: str, method: str = "GET", payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers(), method=method)
    with urllib.request.urlopen(req, timeout=45) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def redact(text: str) -> str:
    patterns = [
        r"AIza[A-Za-z0-9_-]{20,}",
        r"github_pat_[A-Za-z0-9_]{20,}",
        r"gh[pousr]_[A-Za-z0-9]{20,}",
        r"sk-[A-Za-z0-9_-]{20,}",
    ]
    for pattern in patterns:
        text = re.sub(pattern, "[REDACTED]", text)
    return text


def main() -> int:
    if not TOKEN or "/" not in TARGET:
        return 0
    root = Path(os.environ.get("CM_BUILD_DIR", os.getcwd()))
    logs = sorted(glob.glob(str(root / "dist/uab/logs/build-*.log")))
    if not logs:
        return 0
    lines = Path(logs[-1]).read_text(encoding="utf-8", errors="replace").splitlines()
    interesting = [
        line for line in lines
        if ("e: " in line or "error:" in line.lower() or "FAILURE:" in line or "What went wrong:" in line or ":compile" in line)
    ]
    tail = interesting[-160:] if interesting else lines[-160:]
    body = redact(
        f"Project: {PROJECT}\nSource: {SOURCE}\nCommit: {COMMIT}\n\n" + "\n".join(tail) + "\n"
    )

    owner, repo = TARGET.split("/", 1)
    path = f"uab-artifacts/{PROJECT}/last-build-error.txt"
    encoded_path = "/".join(urllib.parse.quote(p, safe="") for p in path.split("/"))
    api = f"https://api.github.com/repos/{owner}/{repo}/contents/{encoded_path}"
    sha = None
    try:
        current = request(f"{api}?ref={urllib.parse.quote(BRANCH)}")
        sha = current.get("sha")
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise

    payload = {
        "message": f"chore(uab): capture {PROJECT} build error",
        "content": base64.b64encode(body.encode("utf-8")).decode("ascii"),
        "branch": BRANCH,
    }
    if sha:
        payload["sha"] = sha
    request(api, method="PUT", payload=payload)
    print("[UAB] Redacted build diagnostic published")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[UAB] Diagnostic upload skipped: {exc}")
        raise SystemExit(0)
