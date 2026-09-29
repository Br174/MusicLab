#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save(path, payload):
    Path(path).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--ledger", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--upstream-commit", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--musiclab-head", required=True)
    parser.add_argument("--build-run-id", required=True)
    parser.add_argument("--artifact-name", required=True)
    args = parser.parse_args()

    config = load(args.config)
    ledger = load(args.ledger)

    if config.get("source_repo") != "FrancescoGrazioso/Meld":
        raise SystemExit("Unexpected upstream repository")
    if config.get("direct_metrolist_enabled") is not False:
        raise SystemExit("Direct MetroList must remain disabled")

    config["accepted_baseline_tag"] = args.tag
    config["accepted_baseline_commit"] = args.upstream_commit
    config["accepted_baseline_musiclab_branch"] = args.branch
    config["accepted_baseline_musiclab_head"] = args.musiclab_head

    try:
        run_id = int(args.build_run_id)
    except ValueError:
        run_id = args.build_run_id

    record = {
        "tag": args.tag,
        "upstream_commit": args.upstream_commit,
        "musiclab_branch": args.branch,
        "musiclab_head": args.musiclab_head,
        "status": "integrated_lab_build_verified",
        "build": {
            "run_id": run_id,
            "result": "success",
            "artifact_name": args.artifact_name,
        },
        "promotion": {
            "candidate": False,
            "mother": False,
            "requires_bruno_approval": True,
        },
        "notes": "Baseline avanzata solo dopo integrazione nella LAB, test/regressioni, build e packaging verificati. Nessuna promozione CANDIDATA/MADRE implicita.",
    }

    integrations = ledger.setdefault("integrations", [])
    existing = next(
        (entry for entry in integrations if entry.get("upstream_commit") == args.upstream_commit),
        None,
    )
    if existing is None:
        integrations.append(record)
    else:
        preserved = {
            key: value
            for key, value in existing.items()
            if key not in record
        }
        existing.clear()
        existing.update(preserved)
        existing.update(record)

    save(args.config, config)
    save(args.ledger, ledger)
    print(f"recorded {args.tag} {args.upstream_commit} on {args.branch} @ {args.musiclab_head}")


if __name__ == "__main__":
    main()
