#!/usr/bin/env bash
# MusicLab Turbo R70: use the EXISTING StudyBook Codespace, not GitHub Actions.
# Run only inside the dedicated MusicLab checkout; StudyBook files stay untouched.
set -Eeuo pipefail
umask 077
REPO='Br174/MusicLab'
BRANCH='lab/musiclab-r70-spotify-login-session-recovery-33150-20261118'
BASE='f1332f76a6e52fff040b7d8c2d1dd79eaaef3ea9'
APP_ID='it.verlezza.musiclab.turbo01'
VERSION='33150'
CERT='9A2F67CFB3C199836813ADDBF7BDFB0FA65EDC765FFACE4B6DF9490BB0962749'
NAME='MusicLab-Turbo-LAB79-Revisione-70-Spotify-Accesso-Stabile'
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
STATE="$HOME/.local/share/musiclab-r70-codespaces"
OUT="$ROOT/APK-R70-CODESPACES"
TOOLS="$HOME/.local/share/studybook-lab125"
mkdir -p "$STATE" "$OUT"
# Preserve the same 7 GB free-disk floor used by the working StudyBook Codespace route.
FREE_KB="$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')"
[[ "$FREE_KB" =~ ^[0-9]+$ ]] && (( FREE_KB >= 7 * 1024 * 1024 )) || {
  printf '[MUSICLAB R70 BLOCCATO] Spazio libero insufficiente nel Codespace; nessun upgrade automatico.\n' >&2
  exit 1
}
fail() { printf '[MUSICLAB R70 BLOCCATO] %s\n' "$*" >&2; exit 1; }
say() { printf '\n[MUSICLAB R70] %s\n' "$*"; }
for bin in git bash python3 node gh java sha256sum unzip zip flock; do
  command -v "$bin" >/dev/null 2>&1 || {
    if [[ "$bin" == node && -x "$TOOLS/node22/bin/node" ]]; then
      export PATH="$TOOLS/node22/bin:$PATH"
    else
      fail "Strumento necessario non disponibile: $bin"
    fi
  }
done
exec 9>"$STATE/build.lock"
flock -n 9 || fail 'Un altra compilazione MusicLab R70 è già in esecuzione; non ne avvio una seconda.'
# Short alias ml70 makes execution possible from Android without clipboard access.
# Still require identical SHA to the canonical, verified LAB branch below.
CURRENT_BRANCH="$(git branch --show-current)"
[[ "$CURRENT_BRANCH" == "$BRANCH" || "$CURRENT_BRANCH" == "ml70" ]] || fail 'Ramo non autorizzato: usare MusicLab R70 o ml70.'
case "$(git remote get-url origin)" in
  https://github.com/Br174/MusicLab|https://github.com/Br174/MusicLab.git|git@github.com:Br174/MusicLab.git|ssh://git@github.com/Br174/MusicLab.git) ;;
  *) fail 'Origine GitHub diversa da MusicLab.' ;;
esac
git diff --quiet && git diff --cached --quiet || fail 'File sorgente già modificati; non li sovrascrivo.'
git merge-base --is-ancestor "$BASE" HEAD || fail 'R70 non discende dalla baseline R69 verificata.'
git fetch --quiet --no-tags origin "refs/heads/$BRANCH:refs/remotes/origin/$BRANCH" || fail 'Impossibile verificare la revisione remota.'
SHA="$(git rev-parse HEAD)"
[[ "$SHA" == "$(git rev-parse "refs/remotes/origin/$BRANCH")" ]] || fail 'Branch locale non allineato al remoto: eseguire git pull --ff-only.'
SHORT="$(printf '%.12s' "$SHA")"
say "Sorgente esatta: $SHORT — StudyBook non viene modificato."
gh auth status -h github.com >/dev/null 2>&1 || fail 'GitHub CLI non autenticata nel Codespace.'
GITHUB_REPOSITORY="$REPO" node scripts/motorlab-active-guard.mjs --self-test
GITHUB_REPOSITORY="$REPO" node scripts/motorlab-active-guard.mjs
python3 tools/universal-apk-builder/verify-r70-spotify-login-scope.py
node --test \
  cloudflare/music-intelligence/test/musiclab-r70-spotify-login.test.mjs \
  cloudflare/music-intelligence/test/turbo-r25-library-spotify-first.test.mjs \
  cloudflare/music-intelligence/test/musiclab-r59-global-audio-antirestart.test.mjs \
  cloudflare/music-intelligence/test/musiclab-r62-universal-swipe-autoplay.test.mjs \
  cloudflare/music-intelligence/test/musiclab-r68-compilation-clean-subtitle.test.mjs
# Reuse StudyBook's installed JDK 21 and Android SDK. Never create a paid runner.
if [[ -x "$TOOLS/temurin-21/bin/java" ]]; then
  export JAVA_HOME="$TOOLS/temurin-21"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java -version 2>&1 | head -n 1 | grep -Eq '"21[.]' || fail 'Serve Java 21; controllare il JDK già installato dal percorso StudyBook.'
if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT:-}" ]]; then
  SDK="$ANDROID_SDK_ROOT"
elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME:-}" ]]; then
  SDK="$ANDROID_HOME"
else
  SDK="$TOOLS/android-sdk"
fi
[[ -d "$SDK" ]] || fail 'Android SDK non disponibile nel Codespace esistente di StudyBook.'
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
[[ -x "$SDKMANAGER" ]] || fail 'sdkmanager StudyBook assente: non cambio il Codespace o creo servizi a pagamento.'
# Starting with API 37, Google publishes minor-versioned SDK platform package IDs.
# "platforms;android-37" is not a valid package; compileSdk=37 uses 37.0 by default.
PLATFORM_JAR="$SDK/platforms/android-37.0/android.jar"
if [[ ! -s "$PLATFORM_JAR" ]]; then
  say 'Aggiungo Android SDK Platform 37.0 (nome pacchetto ufficiale) nel Codespace esistente.'
  "$SDKMANAGER" --sdk_root="$SDK" 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools' ||
    fail 'Installazione Android 37.0 non riuscita: controllare catalogo SDK, rete e disponibilità disco (non è necessariamente quota esaurita).'
fi
[[ -s "$PLATFORM_JAR" ]] ||
  fail 'Pacchetto Android 37.0 non verificato: manca android-37.0/android.jar. Non compilo con SDK errato.'
AAPT="$(find "$SDK/build-tools" -type f -name aapt | sort -V | tail -n1)"
APKSIGNER="$(find "$SDK/build-tools" -type f -name apksigner | sort -V | tail -n1)"
[[ -x "$AAPT" && -x "$APKSIGNER" ]] || fail 'aapt/apksigner Android SDK mancanti.'
if [[ ! -f metroproto/listentogether.proto ]]; then
  git submodule update --init --recursive || fail 'Submodulo metroproto indisponibile.'
fi
[[ -s .uab/signing/musiclab-lab-family-01.jks.b64 ]] ||
  fail 'Chiave della famiglia Turbo non presente: non creo una nuova firma.'
BACKUP="$(mktemp "$STATE/uab-original-XXXXXXXX")"
GRADLE_BACKUP="$(mktemp "$STATE/gradle-original-XXXXXXXX")"
cp uab-project.env "$BACKUP"
cp gradle.properties "$GRADLE_BACKUP"
restore_config() {
  [[ -s "$BACKUP" ]] && cp "$BACKUP" "$ROOT/uab-project.env"
  [[ -s "$GRADLE_BACKUP" ]] && cp "$GRADLE_BACKUP" "$ROOT/gradle.properties"
  rm -f "$BACKUP" "$GRADLE_BACKUP"
  # Only the decoded, transient test signing file from this build; never the source key.
  rm -f "$ROOT/dist/uab/.signing/turbo01.jks"
}
trap restore_config EXIT
python3 tools/universal-apk-builder/prepare-turbo-r70-33150-env.py
set -a
source uab-project.env
set +a
[[ "$UAB_UPDATE_FAMILY_APPLICATION_ID" == "$APP_ID" ]] || fail 'Identità famiglia cambiata.'
[[ "$UAB_UPDATE_FAMILY_VERSION_CODE" == "$VERSION" ]] || fail 'VersionCode diverso dalla R70.'
# MusicLab's 4096M Gradle JVM plus 4096M Kotlin daemon, with parallel builds,
# can exceed this existing 8GB Codespace. Change only temporary LAB build
# preferences, restored by the EXIT trap. Preserve ALL native Scudo360 tests.
python3 - <<'PY'
from pathlib import Path
import re
p=Path("gradle.properties")
original=p.read_text()
jvm=re.findall(r"(?m)^org[.]gradle[.]jvmargs=.*$", original)
if len(jvm)!=1 or not jvm[0].startswith("org.gradle.jvmargs=-Xmx4096M"):
    raise SystemExit("R70: configurazione Gradle cambiata: nessun override della memoria applicato")
if original.count("org.gradle.parallel=true")!=1:
    raise SystemExit("R70: impostazione Gradle parallel inattesa")
tuned=original.replace(jvm[0],
    "org.gradle.jvmargs=-Xmx2304m -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8",1)
tuned=tuned.replace("org.gradle.parallel=true","org.gradle.parallel=false",1)
if "org.gradle.workers.max=" in tuned or "kotlin.compiler.execution.strategy=" in tuned:
    raise SystemExit("R70: il progetto ha già controlli delle risorse: non li sovrascrivo")
tuned=tuned.rstrip()+"\norg.gradle.workers.max=1\nkotlin.compiler.execution.strategy=in-process\n"
p.write_text(tuned)
print("[R70] Gradle Codespaces: heap 2304 MB, 1 worker, Kotlin in-process, no parallelismo; ripristino automatico")
PY
# Quality r40 requires an exact source SHA on its native Scudo360 report.
export GITHUB_SHA="$SHA"
oom_kills() {
  if [[ -r /sys/fs/cgroup/memory.events ]]; then
    awk '$1=="oom_kill" {print $2}' /sys/fs/cgroup/memory.events
  else
    printf '0\n'
  fi
}
OOM_BEFORE="$(oom_kills)"
say 'Eseguo Scudo360 nativo e regressioni Java/Kotlin prima della build.'
if ! node scripts/scudo360-gate.mjs .motorlab/scudo360-contracts.json lab .motorlab/scudo360-report.json; then
  OOM_AFTER="$(oom_kills)"
  if [[ "$OOM_BEFORE" =~ ^[0-9]+$ && "$OOM_AFTER" =~ ^[0-9]+$ ]] && (( OOM_AFTER > OOM_BEFORE )); then
    fail "Scudo360 fallito: il sistema ha terminato un processo per memoria esaurita (oom_kill +$((OOM_AFTER-OOM_BEFORE))); leggere bash why.sh."
  fi
  fail 'Scudo360 fallito, senza nuove prove di OOM nel cgroup: leggere bash why.sh. Nessuna APK distribuita.'
fi
SCUDO360_R40_PROMOTION=1 node .motorlab/regression/scudo360-quality-r40.mjs \
  .motorlab/scudo360-contracts.json .motorlab/scudo360-report.json \
  .motorlab/scudo360-r40-plan.json .motorlab/scudo360-r40-quality.json ||
  fail 'Scudo360 Quality r40 non superato.'
say 'Compilo direttamente nel Codespace esistente, senza GitHub Actions.'
bash tools/universal-apk-builder/diagnose.sh .
bash tools/universal-apk-builder/build-apk.sh . || fail 'Compilazione Android fallita.'
APK="$ROOT/dist/uab/apk/$NAME.apk"
ZIP="$ROOT/dist/uab/$NAME-APK.zip"
[[ -s "$APK" && -s "$ZIP" ]] || fail 'APK oppure ZIP non generati.'
unzip -tq "$ZIP" >/dev/null || fail 'Archivio ZIP non integro.'
BADGING="$("$AAPT" dump badging "$APK")"
printf '%s\n' "$BADGING" | grep -Fq "package: name='$APP_ID'" || fail 'Package Android incompatibile.'
printf '%s\n' "$BADGING" | grep -Fq "versionCode='$VERSION'" || fail 'VersionCode Android incompatibile.'
SIGNERS="$("$APKSIGNER" verify --verbose --print-certs "$APK")" || fail 'Firma APK non verificabile.'
ACTUAL_CERT="$(printf '%s\n' "$SIGNERS" | sed -n '/certificate SHA-256 digest:/I{s/.*digest:[[:space:]]*//Ip;q}' | tr '[:lower:]' '[:upper:]' | tr -d ':[:space:]')"
[[ "$ACTUAL_CERT" == "$CERT" ]] || fail 'Firma APK diversa dalla famiglia Turbo. Blocco la consegna.'
cp "$APK" "$OUT/$NAME.apk"
cp "$ZIP" "$OUT/$NAME-APK.zip"
(
  cd "$OUT"
  sha256sum "$NAME.apk" "$NAME-APK.zip" > "$NAME.sha256"
  sha256sum -c "$NAME.sha256" >/dev/null
)
printf 'source=%s\nappId=%s\nversionCode=%s\nsignerSha256=%s\n' "$SHA" "$APP_ID" "$VERSION" "$CERT" \
  > "$OUT/$NAME-IDENTITY.txt"
chmod 0644 "$OUT/$NAME.apk" "$OUT/$NAME-APK.zip" "$OUT/$NAME.sha256" "$OUT/$NAME-IDENTITY.txt"
say "APK e ZIP verificati nel Codespace: $OUT"
# Fail closed on release/tag-triggered GitHub Actions: quota Actions is exhausted.
if ! python3 - <<'PY'
from pathlib import Path
import re
for p in Path(".github/workflows").glob("*.y*ml"):
    s=p.read_text(errors="replace")
    lines=s.splitlines()
    indexes=[i for i,ln in enumerate(lines) if re.match(r"^on\s*:",ln)]
    for i in indexes:
        header=lines[i].split(":",1)[1].strip()
        if header:
            if re.search(r"\b(release|create|push)\b",header):
                raise SystemExit(f"Workflow non sicuro per tag: {p}")
            continue
        block=[]
        for ln in lines[i+1:]:
            if ln and not ln[0].isspace() and not ln.lstrip().startswith("#"):
                break
            block.append(ln)
        body="\n".join(block)
        if re.search(r"(?m)^  (release|create)\s*:",body):
            raise SystemExit(f"Workflow release/create: {p}")
        if re.search(r"(?m)^  push\s*:",body):
            if re.search(r"(?m)^    tags\s*:",body) or not re.search(r"(?m)^    branches\s*:",body):
                raise SystemExit(f"Workflow push potrebbe attivarsi da tag: {p}")
PY
then
  say 'Pubblicazione Release sospesa: un tag potrebbe consumare GitHub Actions. File locali disponibili.'
  exit 0
fi
TAG="musiclab-turbo-r70-codespaces-$SHORT"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  REF_SHA="$(gh api "repos/$REPO/git/ref/tags/$TAG" --jq '.object.sha' 2>/dev/null || true)"
  [[ "$REF_SHA" == "$SHA" ]] || fail 'Tag Release esistente ma legato a un commit differente.'
  ASSETS="$(gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name')"
  for f in "$NAME.apk" "$NAME-APK.zip" "$NAME.sha256" "$NAME-IDENTITY.txt"; do
    printf '%s\n' "$ASSETS" | grep -Fxq "$f" || fail "Release incompleta, senza sovrascrivere: $f"
  done
  say "Release già verificata: https://github.com/$REPO/releases/tag/$TAG"
elif gh release create "$TAG" \
    "$OUT/$NAME.apk" "$OUT/$NAME-APK.zip" "$OUT/$NAME.sha256" "$OUT/$NAME-IDENTITY.txt" \
    --repo "$REPO" --target "$SHA" --prerelease --latest=false \
    --title 'MusicLab Turbo R70 — Spotify Login — Codespaces' \
    --notes "Compilato nel Codespace esistente, senza GitHub Actions. Source $SHA. Scudo360 e firma Turbo verificate. Login Spotify da collaudare su Android."; then
  say "Release APK/ZIP: https://github.com/$REPO/releases/tag/$TAG"
else
  say 'GitHub Releases non autorizzata nel Codespace StudyBook: APK e ZIP restano nella cartella locale.'
fi
say 'Build e verifica completate, prova di accesso Spotify sul telefono ancora necessaria.'
