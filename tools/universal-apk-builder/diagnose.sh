#!/usr/bin/env bash
set -u
PROJECT_ROOT="${1:-$PWD}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"

ok=0
warn=0
bad=0
line(){ printf '%-24s %s\n' "$1" "$2"; }
pass(){ line "$1" "OK - $2"; ((ok++)); }
warning(){ line "$1" "ATTENZIONE - $2"; ((warn++)); }
error(){ line "$1" "ERRORE - $2"; ((bad++)); }

printf 'Universal APK Builder - diagnostica\nProgetto: %s\n\n' "$PROJECT_ROOT"

if command -v java >/dev/null 2>&1; then
  jv="$(java -version 2>&1 | head -n1)"; pass "Java/JDK" "$jv"
else
  error "Java/JDK" "non trovato"
fi

if [[ -f "$PROJECT_ROOT/gradlew" ]]; then
  pass "Gradle Wrapper" "presente"
  grep -q 'distributionUrl=' "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null && \
    pass "Gradle versione" "$(grep 'distributionUrl=' "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties" | sed 's/.*gradle-//;s/-bin.zip.*//;s/-all.zip.*//')"
else
  error "Gradle Wrapper" "gradlew non trovato"
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]]; then
  pass "Android SDK" "$SDK_ROOT"
else
  warning "Android SDK" "non configurato nell'host; usare run-container.sh"
fi

if [[ -f "$PROJECT_ROOT/settings.gradle" || -f "$PROJECT_ROOT/settings.gradle.kts" ]]; then
  pass "Settings Gradle" "presente"
else
  error "Settings Gradle" "mancante"
fi

mods="$(find "$PROJECT_ROOT" -maxdepth 3 \( -name build.gradle -o -name build.gradle.kts \) -type f 2>/dev/null | wc -l | tr -d ' ')"
((mods>0)) && pass "Moduli Gradle" "$mods file di build" || error "Moduli Gradle" "nessun build.gradle trovato"

if grep -RqsE 'com\.android\.application' "$PROJECT_ROOT" --include='build.gradle' --include='build.gradle.kts' 2>/dev/null; then
  pass "Modulo Android app" "rilevato"
else
  warning "Modulo Android app" "non rilevato automaticamente"
fi

if [[ -f "$PROJECT_ROOT/uab-project.env" ]]; then
  pass "Configurazione UAB" "uab-project.env presente"
else
  warning "Configurazione UAB" "assente; verrà usato il rilevamento automatico"
fi

free_kb="$(df -Pk "$PROJECT_ROOT" | awk 'NR==2{print $4}')"
if [[ -n "$free_kb" && "$free_kb" -gt 5242880 ]]; then
  pass "Spazio disco" "$((free_kb/1024/1024)) GB liberi"
else
  warning "Spazio disco" "meno di 5 GB liberi"
fi

printf '\nRisultato: %s OK, %s avvisi, %s errori.\n' "$ok" "$warn" "$bad"
((bad==0))
