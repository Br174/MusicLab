#!/usr/bin/env python3
"""Single authoritative, read-only classifier for UAB Gradle failures.

The full log and Gradle exit status remain authoritative. This helper emits one
short, non-secret diagnostic. It does not change signing, package identity,
Gradle tasks, APK generation or retry behavior.
"""
import pathlib
import re
import sys

ANSI = re.compile(r"\x1b\[[0-9;]*m")
KOTLIN_LOCATION = re.compile(
    r"(?:e:\s*)?file:/+[^\s:]+/([A-Za-z0-9_.-]+\.kt):([0-9]+):[0-9]+\b",
    re.IGNORECASE,
)
JAVA_LOCATION = re.compile(r"([A-Za-z0-9_.-]+\.java):([0-9]+):\s*error:", re.I)

# Match failure EVIDENCE, not setup terms. Evaluated in specificity order.
SIGNALS = (
    ("KOTLIN", r"\bUnresolved reference\b|\bCompilation error\b|"
     r"\be:\s*file:/+[^\s:]+\.kt:[0-9]+:[0-9]+|"
     r"> Task :[^\n]*compile[^\n]*Kotlin FAILED|"
     r"\be:\s*[^\n]*\.kt:[0-9]+:[0-9]+"),
    ("JAVA", r"\bjava:[0-9]+:\s*error:|\b\.java:[0-9]+:\s*error:|"
     r"> Task :[^\n]*compile[^\n]*JavaWithJavac FAILED"),
    ("RESOURCE", r"Android resource linking failed|AAPT:\s*error:|"
     r"> Task :[^\n]*process[^\n]*Resources FAILED"),
    ("TEST", r"\bThere were failing tests\b|\bTests? failed\b|"
     r"\bAssertionError\b|\btest[A-Za-z0-9_]* FAILED\b|"
     r"^# fail [1-9][0-9]*\b|"
     r"^.+Test > .+ FAILED$"),
    ("SIGNING", r"\bKeystore was tampered with\b|"
     r"\bCannot recover key\b|\bFailed to read key\b|"
     r"\bKeystore file\b[^\n]*\bnot found\b|"
     r"\bFailed to sign\b|"
     r"> Task :[^\n]*validateSigning[^\n]* FAILED|"
     r"\bExecution failed for task\b[^\n]*validateSigning|"
     r"\bNo key with alias\b"),
    ("SDK", r"\bSDK location not found\b|\bAndroid SDK not found\b|"
     r"\bFailed to find Build Tools revision\b|"
     r"\bPlatform SDK\b[^\n]*\bnot found\b|"
     r"\bANDROID_HOME\b[^\n]*(?:not set|not configured)"),
    ("NETWORK", r"\bCould not resolve\b|\bCould not GET\b|"
     r"\bCould not HEAD\b|\bUnknownHostException\b|"
     r"\bConnection timed out\b|\bRead timed out\b"),
    ("RESOURCES", r"\bNo space left on device\b|\bOutOfMemoryError\b|"
     r"\bJava heap space\b|\bGC overhead limit exceeded\b"),
)
LABELS = {
    "KOTLIN": "errore di compilazione Kotlin",
    "JAVA": "errore di compilazione Java",
    "RESOURCE": "errore delle risorse Android",
    "TEST": "test automatici falliti",
    "SIGNING": "problema reale di firma/keystore",
    "SDK": "Android SDK non configurato",
    "NETWORK": "dipendenza o rete non disponibile",
    "RESOURCES": "risorse del runner insufficienti",
    "UNKNOWN": "errore Gradle non classificato (consultare log)",
}

def classify(log: str) -> tuple[str, str]:
    """Return category and user-facing reason. No arbitrary log text is echoed."""
    clean = ANSI.sub("", log)[-250_000:]
    for code, expression in SIGNALS:
        if re.search(expression, clean, re.I | re.M):
            detail = ""
            if code in ("KOTLIN", "JAVA"):
                match = (KOTLIN_LOCATION if code == "KOTLIN" else JAVA_LOCATION).search(clean)
                if match:
                    detail = " ({}:{})".format(match.group(1), match.group(2))
            return code, LABELS[code] + detail
    return "UNKNOWN", LABELS["UNKNOWN"]

def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Uso: classify-gradle-failure.py LOG_FILE", file=sys.stderr)
        return 2
    try:
        log = pathlib.Path(argv[1]).read_text(encoding="utf-8", errors="replace")
    except OSError:
        print(LABELS["UNKNOWN"])
        return 0
    print(classify(log)[1])
    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
