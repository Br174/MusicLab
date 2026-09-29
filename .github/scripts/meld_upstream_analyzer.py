#!/usr/bin/env python3
import argparse
import json
import subprocess
from collections import Counter
from pathlib import Path


def git(*args, check=True):
    proc = subprocess.run(
        ["git", *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and proc.returncode != 0:
        raise RuntimeError(
            f"git {' '.join(args)} failed ({proc.returncode}): {proc.stderr.strip()}"
        )
    return proc


def ref_has_path(ref, path):
    return git("cat-file", "-e", f"{ref}:{path}", check=False).returncode == 0


def refs_equal_for_path(left, right, path):
    proc = git("diff", "--quiet", left, right, "--", path, check=False)
    return proc.returncode == 0


def changed_paths(base_ref, target_ref):
    proc = git("diff", "--name-status", "-M", base_ref, target_ref)
    items = []
    for raw in proc.stdout.splitlines():
        if not raw.strip():
            continue
        parts = raw.split("\t")
        status = parts[0]
        if status.startswith(("R", "C")) and len(parts) >= 3:
            old_path, path = parts[1], parts[2]
        else:
            old_path = None
            path = parts[1]
        items.append({"status": status, "path": path, "old_path": old_path})
    return items


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--target-ref", required=True)
    parser.add_argument("--head-ref", default="HEAD")
    parser.add_argument("--config", default=".github/upstream/meld.json")
    parser.add_argument("--conflicts-file")
    parser.add_argument("--out-dir", default="upstream-review")
    args = parser.parse_args()

    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    protected = tuple(config.get("protected_path_prefixes", []))
    conflicts = set()
    if args.conflicts_file:
        p = Path(args.conflicts_file)
        if p.exists():
            conflicts = {
                line.strip()
                for line in p.read_text(encoding="utf-8").splitlines()
                if line.strip()
            }

    upstream = changed_paths(args.base_ref, args.target_ref)
    musiclab_changed = {
        line.strip()
        for line in git("diff", "--name-only", args.base_ref, args.head_ref).stdout.splitlines()
        if line.strip()
    }

    entries = []
    counts = Counter()
    for item in upstream:
        path = item["path"]
        status = item["status"]
        is_conflict = path in conflicts or (
            item["old_path"] and item["old_path"] in conflicts
        )
        protected_path = any(path.startswith(prefix) for prefix in protected)
        touched_by_musiclab = path in musiclab_changed or (
            item["old_path"] is not None and item["old_path"] in musiclab_changed
        )

        target_exists = ref_has_path(args.target_ref, path)
        head_exists = ref_has_path(args.head_ref, path)
        already_present = False
        if target_exists == head_exists:
            already_present = refs_equal_for_path(args.head_ref, args.target_ref, path)

        if is_conflict:
            category = "real_conflict"
            reason = "La simulazione Git segnala un conflitto reale sul file."
        elif already_present:
            category = "already_present"
            reason = "Il contenuto target per questo percorso e' gia' equivalente a MusicLab."
        elif protected_path:
            category = "possible_regression"
            reason = "Il percorso appartiene a un'area MusicLab protetta/critica e richiede regressione mirata."
        elif touched_by_musiclab:
            category = "compatible_with_adaptation"
            reason = "Upstream e MusicLab hanno entrambi modificato il percorso, ma senza conflitto Git diretto."
        else:
            category = "safe"
            reason = "Il percorso e' cambiato solo upstream rispetto alla baseline e non ricade in aree protette."

        counts[category] += 1
        entries.append(
            {
                "path": path,
                "old_path": item["old_path"],
                "git_status": status,
                "category": category,
                "reason": reason,
                "touched_by_musiclab": touched_by_musiclab,
                "protected": protected_path,
            }
        )

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    ordered_categories = [
        "safe",
        "already_present",
        "compatible_with_adaptation",
        "real_conflict",
        "possible_regression",
    ]
    result = {
        "schema": 1,
        "source_repo": config["source_repo"],
        "baseline_tag": config["accepted_baseline_tag"],
        "baseline_commit": config["accepted_baseline_commit"],
        "base_ref": args.base_ref,
        "target_ref": args.target_ref,
        "head_ref": args.head_ref,
        "counts": {key: counts.get(key, 0) for key in ordered_categories},
        "auto_apply_allowed": (
            counts.get("compatible_with_adaptation", 0) == 0
            and counts.get("real_conflict", 0) == 0
            and counts.get("possible_regression", 0) == 0
        ),
        "files": entries,
    }
    (out_dir / "classification.json").write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    labels = {
        "safe": "SAFE",
        "already_present": "GIA' PRESENTE",
        "compatible_with_adaptation": "COMPATIBILE CON ADATTAMENTO",
        "real_conflict": "CONFLITTO REALE",
        "possible_regression": "POSSIBILE REGRESSIONE",
    }
    lines = [
        "# Classificazione Meld → MusicLab",
        "",
        f"- Baseline: {config['accepted_baseline_tag']} ({config['accepted_baseline_commit']})",
        f"- Target ref: {args.target_ref}",
        f"- Auto-applicazione SAFE consentita: {'SI' if result['auto_apply_allowed'] else 'NO'}",
        "",
        "## Riepilogo",
    ]
    for key in ordered_categories:
        lines.append(f"- {labels[key]}: {result['counts'][key]}")
    lines.extend(["", "## File"])
    for entry in entries:
        lines.append(
            f"- **{labels[entry['category']]}** — `{entry['path']}` — {entry['reason']}"
        )
    (out_dir / "CLASSIFICATION.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )

    print(json.dumps(result["counts"], sort_keys=True))
    print(f"auto_apply_allowed={'true' if result['auto_apply_allowed'] else 'false'}")


if __name__ == "__main__":
    main()
