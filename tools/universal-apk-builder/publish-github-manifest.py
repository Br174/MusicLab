#!/usr/bin/env python3
"""Publish latest UAB APK/ZIP metadata to a central GitHub manifest registry.

Project-neutral bridge:
Codemagic artifacts -> temporary public Codemagic URLs -> central GitHub latest.json.
No GitHub Actions are used.
"""

from __future__ import annotations

import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def request_json(url: str, *, method: str = "GET", headers=None, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def create_public_url(secure_url: str, token: str, expires_at: int) -> str:
    result = request_json(
        secure_url.rstrip("/") + "/public-url",
        method="POST",
        headers={"Content-Type": "application/json", "x-auth-token": token},
        payload={"expiresAt": expires_at},
    )
    public_url = str(result.get("url", "")).strip()
    if not public_url:
        raise RuntimeError("Codemagic did not return a public artifact URL")
    return public_url


def github_headers(token: str) -> dict[str, str]:
    return {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": "application/json",
        "User-Agent": "UAB-GitHub-Manifest",
    }


def current_manifest_sha(api_url: str, headers: dict[str, str], branch: str) -> str | None:
    try:
        result = request_json(
            f"{api_url}?ref={urllib.parse.quote(branch)}",
            headers=headers,
        )
        return str(result.get("sha", "")).strip() or None
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        raise


def safe_project_slug(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-._")
    return cleaned or "project"


def main() -> int:
    github_token = env("UAB_GITHUB_MANIFEST_TOKEN")
    codemagic_token = env("CODEMAGIC_API_TOKEN")
    if not github_token or not codemagic_token:
        missing = []
        if not github_token:
            missing.append("UAB_GITHUB_MANIFEST_TOKEN")
        if not codemagic_token:
            missing.append("CODEMAGIC_API_TOKEN")
        print("[UAB] Central manifest publish skipped; missing secret(s): " + ", ".join(missing))
        return 0

    raw_links = env("CM_ARTIFACT_LINKS", "[]")
    try:
        source_links = json.loads(raw_links)
    except json.JSONDecodeError as exc:
        raise RuntimeError("CM_ARTIFACT_LINKS is not valid JSON") from exc
    if not isinstance(source_links, list):
        raise RuntimeError("CM_ARTIFACT_LINKS must be a JSON list")

    source_repo = env("CM_REPO_SLUG") or env("UAB_REPOSITORY")
    if "/" not in source_repo:
        raise RuntimeError("Cannot determine source GitHub owner/repository")
    _, source_repo_name = source_repo.split("/", 1)

    target_repo = env("UAB_MANIFEST_REPOSITORY")
    if "/" not in target_repo:
        raise RuntimeError("UAB_MANIFEST_REPOSITORY must be owner/repository")
    target_owner, target_name = target_repo.split("/", 1)

    project = env("UAB_PROJECT_NAME") or source_repo_name
    project_slug = safe_project_slug(project)
    manifest_branch = env("UAB_MANIFEST_BRANCH", "main")
    path_template = env("UAB_MANIFEST_PATH", "uab-artifacts/{project}/latest.json")
    manifest_path = path_template.replace("{project}", project_slug)
    ttl_days = max(1, min(int(env("UAB_ARTIFACT_PUBLIC_TTL_DAYS", "30")), 30))
    expires_at = int(time.time()) + ttl_days * 24 * 60 * 60

    artifacts = []
    for item in source_links:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name", "")).strip()
        secure_url = str(item.get("url", "")).strip()
        lower = name.lower()
        if not secure_url or not (lower.endswith(".apk") or lower.endswith(".zip")):
            continue
        artifacts.append(
            {
                "name": name,
                "kind": "apk" if lower.endswith(".apk") else "zip",
                "url": create_public_url(secure_url, codemagic_token, expires_at),
                "expiresAt": expires_at,
                "md5": str(item.get("md5", "")).strip() or None,
            }
        )

    if not artifacts:
        print("[UAB] No APK/ZIP artifacts found; central manifest not updated")
        return 0

    manifest = {
        "schema": 1,
        "project": project,
        "sourceRepository": source_repo,
        "status": "ready",
        "branch": env("CM_BRANCH", "unknown"),
        "buildNumber": env("PROJECT_BUILD_NUMBER") or env("BUILD_NUMBER") or "unknown",
        "commit": env("CM_COMMIT"),
        "generatedAt": int(time.time()),
        "publicUrlTtlDays": ttl_days,
        "artifacts": artifacts,
    }
    manifest_bytes = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")

    encoded_path = "/".join(urllib.parse.quote(part, safe="") for part in manifest_path.split("/"))
    api_url = (
        "https://api.github.com/repos/"
        f"{urllib.parse.quote(target_owner)}/{urllib.parse.quote(target_name)}/contents/{encoded_path}"
    )
    headers = github_headers(github_token)
    sha = current_manifest_sha(api_url, headers, manifest_branch)
    payload = {
        "message": f"chore(uab): publish {project} build {manifest['buildNumber']}",
        "content": base64.b64encode(manifest_bytes).decode("ascii"),
        "branch": manifest_branch,
    }
    if sha:
        payload["sha"] = sha

    request_json(api_url, method="PUT", headers=headers, payload=payload)
    print(f"[UAB] Central manifest updated: {target_repo}@{manifest_branch}:{manifest_path}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"[UAB] Central manifest publish failed: {exc}", file=sys.stderr)
        sys.exit(1)
