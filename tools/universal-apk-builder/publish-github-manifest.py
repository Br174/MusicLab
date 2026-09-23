#!/usr/bin/env python3
"""Publish UAB APK/ZIP files directly to a central GitHub Release and latest.json.

Project-neutral delivery bridge:
local Codemagic build artifacts -> central GitHub Release assets -> central latest.json.
No Codemagic API, email, AppDeploy, or GitHub Actions are required for delivery.
"""

from __future__ import annotations

import base64
import hashlib
import http.client
import json
import mimetypes
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def http_label(exc: urllib.error.HTTPError) -> str:
    return f"HTTP {exc.code} {exc.reason}"


def github_headers(token: str, *, content_type: str = "application/json") -> dict[str, str]:
    return {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": content_type,
        "User-Agent": "UAB-GitHub-Delivery",
    }


def request_json(url: str, *, method: str = "GET", headers=None, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    with urllib.request.urlopen(req, timeout=45) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def safe_project_slug(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-._")
    return cleaned or "project"


def validate_github_token(token: str, owner: str, repo: str) -> None:
    url = f"https://api.github.com/repos/{urllib.parse.quote(owner, safe='')}/{urllib.parse.quote(repo, safe='')}"
    try:
        result = request_json(url, headers=github_headers(token))
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"GitHub delivery token rejected for {owner}/{repo}: {http_label(exc)}") from exc

    permissions = result.get("permissions") if isinstance(result, dict) else None
    if isinstance(permissions, dict) and permissions.get("push") is False:
        raise RuntimeError(f"GitHub delivery token can read {owner}/{repo} but does not have write access")


def resolve_output_dir() -> Path:
    build_root = Path(env("CM_BUILD_DIR", os.getcwd())).expanduser().resolve()
    configured = env("UAB_OUTPUT_DIR")
    if configured:
        output = Path(configured).expanduser()
        if not output.is_absolute():
            output = build_root / output
        return output.resolve()
    return (build_root / "dist" / "uab").resolve()


def collect_artifacts(output_dir: Path) -> list[Path]:
    files: list[Path] = []
    apk_dir = output_dir / "apk"
    if apk_dir.is_dir():
        files.extend(sorted(p for p in apk_dir.glob("*.apk") if p.is_file()))
    files.extend(sorted(p for p in output_dir.glob("*.zip") if p.is_file()))
    return files


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def release_by_tag(token: str, owner: str, repo: str, tag: str):
    url = (
        f"https://api.github.com/repos/{urllib.parse.quote(owner, safe='')}/"
        f"{urllib.parse.quote(repo, safe='')}/releases/tags/{urllib.parse.quote(tag, safe='')}"
    )
    try:
        return request_json(url, headers=github_headers(token))
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        raise RuntimeError(f"GitHub release lookup rejected: {http_label(exc)}") from exc


def ensure_release(
    token: str,
    owner: str,
    repo: str,
    tag: str,
    branch: str,
    name: str,
    body: str,
):
    existing = release_by_tag(token, owner, repo, tag)
    base = f"https://api.github.com/repos/{urllib.parse.quote(owner, safe='')}/{urllib.parse.quote(repo, safe='')}"
    payload = {
        "tag_name": tag,
        "target_commitish": branch,
        "name": name,
        "body": body,
        "draft": False,
        "prerelease": True,
    }
    try:
        if existing:
            release_id = int(existing["id"])
            return request_json(
                f"{base}/releases/{release_id}",
                method="PATCH",
                headers=github_headers(token),
                payload={"name": name, "body": body, "draft": False, "prerelease": True},
            )
        return request_json(
            f"{base}/releases",
            method="POST",
            headers=github_headers(token),
            payload=payload,
        )
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"GitHub release create/update rejected: {http_label(exc)}") from exc


def list_release_assets(token: str, owner: str, repo: str, release_id: int) -> list[dict]:
    url = (
        f"https://api.github.com/repos/{urllib.parse.quote(owner, safe='')}/"
        f"{urllib.parse.quote(repo, safe='')}/releases/{release_id}/assets?per_page=100"
    )
    try:
        result = request_json(url, headers=github_headers(token))
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"GitHub release asset listing rejected: {http_label(exc)}") from exc
    return result if isinstance(result, list) else []


def delete_release_asset(token: str, owner: str, repo: str, asset_id: int) -> None:
    url = (
        f"https://api.github.com/repos/{urllib.parse.quote(owner, safe='')}/"
        f"{urllib.parse.quote(repo, safe='')}/releases/assets/{asset_id}"
    )
    req = urllib.request.Request(url, headers=github_headers(token), method="DELETE")
    try:
        with urllib.request.urlopen(req, timeout=45):
            return
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"GitHub release asset deletion rejected: {http_label(exc)}") from exc


def upload_release_asset(token: str, owner: str, repo: str, release_id: int, path: Path) -> dict:
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    if path.suffix.lower() == ".apk":
        mime = "application/vnd.android.package-archive"
    elif path.suffix.lower() == ".zip":
        mime = "application/zip"

    query = urllib.parse.urlencode({"name": path.name, "label": path.name})
    request_path = (
        f"/repos/{urllib.parse.quote(owner, safe='')}/{urllib.parse.quote(repo, safe='')}/"
        f"releases/{release_id}/assets?{query}"
    )
    size = path.stat().st_size
    conn = http.client.HTTPSConnection("uploads.github.com", timeout=120)
    try:
        conn.putrequest("POST", request_path)
        for key, value in github_headers(token, content_type=mime).items():
            conn.putheader(key, value)
        conn.putheader("Content-Length", str(size))
        conn.endheaders()
        with path.open("rb") as handle:
            while True:
                chunk = handle.read(1024 * 1024)
                if not chunk:
                    break
                conn.send(chunk)
        response = conn.getresponse()
        raw = response.read().decode("utf-8", errors="replace")
        if response.status != 201:
            raise RuntimeError(f"GitHub release asset upload rejected: HTTP {response.status} {response.reason}")
        return json.loads(raw) if raw else {}
    finally:
        conn.close()


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
        raise RuntimeError(f"GitHub manifest read rejected: {http_label(exc)}") from exc


def write_manifest(
    token: str,
    owner: str,
    repo: str,
    branch: str,
    path: str,
    project: str,
    build_number: str,
    manifest: dict,
) -> None:
    encoded_path = "/".join(urllib.parse.quote(part, safe="") for part in path.split("/"))
    api_url = (
        f"https://api.github.com/repos/{urllib.parse.quote(owner, safe='')}/"
        f"{urllib.parse.quote(repo, safe='')}/contents/{encoded_path}"
    )
    headers = github_headers(token)
    sha = current_manifest_sha(api_url, headers, branch)
    data = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    payload = {
        "message": f"chore(uab): publish {project} build {build_number}",
        "content": base64.b64encode(data).decode("ascii"),
        "branch": branch,
    }
    if sha:
        payload["sha"] = sha
    try:
        request_json(api_url, method="PUT", headers=headers, payload=payload)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"GitHub manifest write rejected: {http_label(exc)}") from exc


def main() -> int:
    github_token = env("UAB_GITHUB_MANIFEST_TOKEN")
    if not github_token:
        raise RuntimeError("missing secret UAB_GITHUB_MANIFEST_TOKEN")

    target_repo = env("UAB_MANIFEST_REPOSITORY")
    if "/" not in target_repo:
        raise RuntimeError("UAB_MANIFEST_REPOSITORY must be owner/repository")
    target_owner, target_name = target_repo.split("/", 1)
    manifest_branch = env("UAB_MANIFEST_BRANCH", "main")

    validate_github_token(github_token, target_owner, target_name)
    if "--preflight" in sys.argv:
        print(f"[UAB] Delivery preflight OK: GitHub {target_repo} is readable/writable")
        return 0

    source_repo = env("CM_REPO_SLUG") or env("UAB_REPOSITORY")
    if "/" not in source_repo:
        raise RuntimeError("Cannot determine source GitHub owner/repository")
    _, source_repo_name = source_repo.split("/", 1)
    project = env("UAB_PROJECT_NAME") or source_repo_name
    project_slug = safe_project_slug(project)
    build_number = env("PROJECT_BUILD_NUMBER") or env("BUILD_NUMBER") or "unknown"

    output_dir = resolve_output_dir()
    local_artifacts = collect_artifacts(output_dir)
    if not local_artifacts:
        raise RuntimeError(f"No APK/ZIP files found under {output_dir}")

    release_tag = env("UAB_RELEASE_TAG", f"uab-{project_slug.lower()}-latest")
    release_name = f"UAB {project} - latest build"
    release_body = (
        f"Automatically managed by Universal APK Builder.\n\n"
        f"Project: {project}\n"
        f"Source: {source_repo}\n"
        f"Branch: {env('CM_BRANCH', 'unknown')}\n"
        f"Build: {build_number}\n"
        f"Updated: {time.strftime('%Y-%m-%d %H:%M:%S UTC', time.gmtime())}\n"
    )
    release = ensure_release(
        github_token,
        target_owner,
        target_name,
        release_tag,
        manifest_branch,
        release_name,
        release_body,
    )
    release_id = int(release["id"])

    for asset in list_release_assets(github_token, target_owner, target_name, release_id):
        asset_id = asset.get("id")
        if asset_id is not None:
            delete_release_asset(github_token, target_owner, target_name, int(asset_id))

    artifacts = []
    for path in local_artifacts:
        uploaded = upload_release_asset(github_token, target_owner, target_name, release_id, path)
        browser_url = str(uploaded.get("browser_download_url", "")).strip()
        if not browser_url:
            raise RuntimeError(f"GitHub did not return browser_download_url for {path.name}")
        artifacts.append(
            {
                "name": path.name,
                "kind": "apk" if path.suffix.lower() == ".apk" else "zip",
                "url": browser_url,
                "size": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
        print(f"[UAB] Uploaded release asset: {path.name}")

    path_template = env("UAB_MANIFEST_PATH", "uab-artifacts/{project}/latest.json")
    manifest_path = path_template.replace("{project}", project_slug)
    manifest = {
        "schema": 2,
        "project": project,
        "sourceRepository": source_repo,
        "status": "ready",
        "branch": env("CM_BRANCH", "unknown"),
        "buildNumber": build_number,
        "commit": env("CM_COMMIT"),
        "generatedAt": int(time.time()),
        "delivery": "github-release",
        "releaseTag": release_tag,
        "releaseUrl": str(release.get("html_url", "")),
        "artifacts": artifacts,
    }
    write_manifest(
        github_token,
        target_owner,
        target_name,
        manifest_branch,
        manifest_path,
        project,
        build_number,
        manifest,
    )
    print(f"[UAB] Central release + manifest updated: {target_repo}:{manifest_path}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"[UAB] Central GitHub delivery failed: {exc}", file=sys.stderr)
        sys.exit(1)
