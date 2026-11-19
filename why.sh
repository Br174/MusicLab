#!/usr/bin/env bash
# MusicLab R70: read-only explanation of the most recent native Scudo360 result.
# Does not rerun tests, upload logs, change files, or use GitHub Actions.
set -Eeuo pipefail
cd "$(dirname "$0")"
python3 - <<'PY'
import json
import pathlib
import re

file = pathlib.Path(".motorlab/scudo360-report.json")
if not file.is_file():
    raise SystemExit("[R70 DIAGNOSI] Rapporto Scudo360 non trovato; nessun test riavviato.")
report = json.loads(file.read_text())
if report.get("gate") != "MotorLab-Scudo360" or report.get("project") != "musiclab":
    raise SystemExit("[R70 DIAGNOSI] Rapporto inatteso: non mostro dati non verificati.")
print("[R70 DIAGNOSI] Scudo360:", "PASS" if report.get("passed") else "BLOCCATO")
print("Preflight:", "PASS" if report.get("preflight",{}).get("valid") else "FAILED")
for suite in report.get("suites", []):
    print("Suite:", suite.get("id","?"))
    print("Stato:", suite.get("status","?"), "Gradle exit:", suite.get("exitCode","?"))
    print("Test eseguiti:", suite.get("tests",0), "JUnit XML:", suite.get("files",0))
    if suite.get("error"):
        print("Errore avvio:", str(suite["error"])[:250])
    log = str(suite.get("logTail") or "")
    significant = re.compile(
        r"FAILURE|What went wrong|failed|error|could not|not found|exception|"
        r"SDK|license|plugin|gradle|dependency|compile|repository|KSP|"
        r"java|kotlin|Android|transform|timeout|JDK|network|task|build",
        re.I
    )
    confidential = re.compile(
        r"(?i)(authorization:|bearer\s|api[_-]?key|access[_-]?token|"
        r"secret|password|private[_-]?key|keystore[_-]?password|"
        r"signing[_-]?key|github[_-]?token|ghp_|sk-proj-)"
    )
    selected = []
    for line in log.splitlines():
        if confidential.search(line):
            continue
        if significant.search(line):
            selected.append(line.strip()[:240])
    print("--- Ultime righe diagnostiche, senza credenziali ---")
    for line in selected[-24:]:
        print(line)
    if not selected:
        print("Il log sintetico non contiene una causa riconoscibile.")
print("[R70 DIAGNOSI] Nessuna modifica o nuova compilazione eseguita.")
PY
