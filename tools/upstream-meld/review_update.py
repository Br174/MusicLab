#!/usr/bin/env python3
import argparse
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / ".upstream" / "meld.json"
UPSTREAM_URL = "https://github.com/FrancescoGrazioso/Meld.git"
UPSTREAM_API = "https://api.github.com/repos/FrancescoGrazioso/Meld"


def run(*args, check=True, text=True):
    proc = subprocess.run(args, cwd=ROOT, capture_output=True, text=text)
    if check and proc.returncode != 0:
        raise RuntimeError(f"command failed ({proc.returncode}): {' '.join(args)}\n{proc.stdout}\n{proc.stderr}")
    return proc


def git(*args, check=True):
    return run("git", *args, check=check)


def load_state():
    return json.loads(STATE.read_text(encoding="utf-8"))


def latest_release_tag():
    req = urllib.request.Request(
        f"{UPSTREAM_API}/releases/latest",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "MusicLab-Upstream-Guardian"},
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        data = json.load(resp)
    tag = (data.get("tag_name") or "").strip()
    if not tag:
        raise RuntimeError("latest Meld release has no tag_name")
    return tag


def ensure_remote():
    remotes = git("remote").stdout.split()
    if "meld" in remotes:
        git("remote", "set-url", "meld", UPSTREAM_URL)
    else:
        git("remote", "add", "meld", UPSTREAM_URL)
    git("fetch", "--force", "--tags", "meld", "+refs/heads/main:refs/remotes/meld/main")


def resolve_tag(tag):
    candidates = [f"refs/tags/{tag}^{{}}", f"refs/tags/{tag}", tag]
    for ref in candidates:
        proc = git("rev-parse", "--verify", ref, check=False)
        if proc.returncode == 0:
            return proc.stdout.strip()
    raise RuntimeError(f"cannot resolve upstream tag {tag}")


def blob_at(commit, path):
    proc = git("rev-parse", f"{commit}:{path}", check=False)
    return proc.stdout.strip() if proc.returncode == 0 else None


def parse_name_status(base, target):
    rows = []
    out = git("diff", "--name-status", "-M", base, target).stdout
    for raw in out.splitlines():
        if not raw.strip():
            continue
        parts = raw.split("\t")
        status = parts[0]
        if status.startswith("R") and len(parts) >= 3:
            rows.append({"status": status, "old_path": parts[1], "path": parts[2]})
        elif len(parts) >= 2:
            rows.append({"status": status, "old_path": None, "path": parts[1]})
    return rows


def classify(base, head, target, change):
    status = change["status"]
    path = change["path"]
    old_path = change.get("old_path")
    if status.startswith("R"):
        return "REVIEW_RENAME"

    base_blob = blob_at(base, path)
    ours_blob = blob_at(head, path)
    target_blob = blob_at(target, path)

    if ours_blob == target_blob and ours_blob is not None:
        return "ALREADY_PRESENT"
    if status.startswith("A"):
        if ours_blob is None:
            return "SAFE_ADD"
        return "CONFLICT_REVIEW"
    if status.startswith("D"):
        if ours_blob == base_blob:
            return "SAFE_DELETE"
        if ours_blob is None:
            return "ALREADY_PRESENT"
        return "CONFLICT_REVIEW"
    if ours_blob == base_blob:
        return "SAFE_DIRECT"
    return "CONFLICT_REVIEW"


def merge_tree_status(head, target):
    proc = git("merge-tree", "--write-tree", head, target, check=False)
    return {
        "clean": proc.returncode == 0,
        "exit_code": proc.returncode,
        "output": (proc.stdout + "\n" + proc.stderr).strip(),
    }


def commit_list(base, target):
    fmt = "%H%x09%ad%x09%an%x09%s"
    out = git("log", "--reverse", "--date=short", f"--pretty=format:{fmt}", f"{base}..{target}").stdout
    commits = []
    for line in out.splitlines():
        sha, date, author, subject = (line.split("\t", 3) + ["", "", "", ""])[:4]
        commits.append({"sha": sha, "date": date, "author": author, "subject": subject})
    return commits


def main():
    parser = argparse.ArgumentParser(description="Review Meld upstream changes against MusicLab without modifying source files")
    parser.add_argument("--from-tag", default="", help="Meld baseline tag. Default: last_imported_tag from .upstream/meld.json")
    parser.add_argument("--to-tag", default="", help="Meld target tag. Default: latest GitHub release")
    parser.add_argument("--out-dir", default="dist/upstream-meld-review")
    args = parser.parse_args()

    state = load_state()
    from_tag = args.from_tag.strip() or state["last_imported_tag"]
    to_tag = args.to_tag.strip() or latest_release_tag()

    ensure_remote()
    base = resolve_tag(from_tag)
    target = resolve_tag(to_tag)
    head = git("rev-parse", "HEAD").stdout.strip()

    if git("merge-base", "--is-ancestor", base, head, check=False).returncode != 0:
        raise RuntimeError(f"MusicLab HEAD is not descended from Meld {from_tag} ({base}); manual baseline recovery required")
    if git("merge-base", "--is-ancestor", base, target, check=False).returncode != 0:
        raise RuntimeError(f"Meld target {to_tag} is not descended from {from_tag}")

    changes = parse_name_status(base, target)
    counts = {}
    for change in changes:
        category = classify(base, head, target, change)
        change["classification"] = category
        counts[category] = counts.get(category, 0) + 1

    commits = commit_list(base, target)
    merge_probe = merge_tree_status(head, target)

    out_dir = ROOT / args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    safe_target = to_tag.replace("/", "_")

    patch_path = out_dir / f"meld-{from_tag}-to-{safe_target}.patch"
    patch = git("diff", "--binary", base, target).stdout
    patch_path.write_text(patch, encoding="utf-8")

    report = {
        "upstream_repo": state["upstream_repo"],
        "musiclab_head": head,
        "from_tag": from_tag,
        "from_sha": base,
        "to_tag": to_tag,
        "to_sha": target,
        "commit_count": len(commits),
        "file_count": len(changes),
        "classifications": counts,
        "three_way_merge_clean": merge_probe["clean"],
        "three_way_merge_exit_code": merge_probe["exit_code"],
        "changes": changes,
        "commits": commits,
        "policy": {
            "direct_upstream_apk_install": False,
            "auto_apply": False,
            "auto_promote_madre": False,
            "exact_import": "cherry-pick -x preferred; three-way patch when appropriate",
        },
    }
    (out_dir / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    md = []
    md.append(f"# MusicLab upstream review: Meld {from_tag} → {to_tag}")
    md.append("")
    md.append(f"- MusicLab HEAD: `{head}`")
    md.append(f"- Meld baseline: `{from_tag}` / `{base}`")
    md.append(f"- Meld target: `{to_tag}` / `{target}`")
    md.append(f"- Commits upstream: **{len(commits)}**")
    md.append(f"- Files changed upstream: **{len(changes)}**")
    md.append(f"- Three-way merge probe: **{'CLEAN' if merge_probe['clean'] else 'CONFLICTS/REVIEW'}**")
    md.append("")
    md.append("## Classification")
    md.append("")
    for key in ["SAFE_DIRECT", "SAFE_ADD", "SAFE_DELETE", "ALREADY_PRESENT", "REVIEW_RENAME", "CONFLICT_REVIEW"]:
        md.append(f"- {key}: **{counts.get(key, 0)}**")
    md.append("")
    md.append("## Changed files")
    md.append("")
    md.append("| Class | Status | Path |")
    md.append("|---|---|---|")
    for c in changes:
        path = c["path"] if not c.get("old_path") else f"{c['old_path']} → {c['path']}"
        md.append(f"| {c['classification']} | {c['status']} | `{path}` |")
    md.append("")
    md.append("## Upstream commits")
    md.append("")
    for c in commits:
        md.append(f"- `{c['sha'][:12]}` {c['date']} — {c['subject']} ({c['author']})")
    md.append("")
    md.append("## Rules")
    md.append("")
    md.append("- This review never writes to MADRE and never changes application source.")
    md.append("- Meld APKs are not installable updates for MusicLab; only source changes may be integrated.")
    md.append("- SAFE_* means the upstream file can be imported exactly relative to the stored baseline.")
    md.append("- CONFLICT_REVIEW means MusicLab changed the same file since the baseline; no silent choice is allowed.")
    md.append("- Approved upstream commits should be imported with `git cherry-pick -x` when possible to preserve authorship/provenance.")
    md.append("- After import: LAB build + regression tests + Bruno approval before any candidate/promotion.")

    (out_dir / "REPORT.md").write_text("\n".join(md) + "\n", encoding="utf-8")

    print(f"UPSTREAM_REVIEW_FROM={from_tag}")
    print(f"UPSTREAM_REVIEW_TO={to_tag}")
    print(f"UPSTREAM_COMMITS={len(commits)}")
    print(f"UPSTREAM_FILES={len(changes)}")
    print(f"UPSTREAM_CONFLICT_REVIEW={counts.get('CONFLICT_REVIEW', 0) + counts.get('REVIEW_RENAME', 0)}")
    print(f"UPSTREAM_THREE_WAY_CLEAN={str(merge_probe['clean']).lower()}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
