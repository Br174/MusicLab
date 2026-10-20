# MotorLab MusicLab pipeline stabilization LAB
- Date: 2026-10-20 (Europe/Rome)
- Operation: musiclab-pipeline-stabilization-20261020
- Scope: isolated branch `motorlab/musiclab-pipeline-stabilization-lab-20261020`
- Base source SHA: `d49ecba0f670579b1f1c3fb9a4e9fb24113aae4e`; immutable original LAB27 branch untouched.
- CORE: MotorLab r37. Project satellite: r37 verified.
- Preflight: last LAB27 APK+ZIP workflow 39471277566 succeeded. No reason to rebuild unchanged Android source just for diagnostic tooling.
- Root cause: old UAB Gradle failure block sequentially overwrote compiler classification with broad "Keystore|signing" match from non-failing setup lines.
- Remediation: one deterministic Python classifier linked to existing UAB build script; no app code changes; original Gradle status preserved.
- Workflow convergence: introduce one read-only reusable validation workflow and a single isolated LAB caller, preserving project-specific existing Turbo workflow. No modification of historical approved workflows, main or MADRE.
- Tests: Python failure-classification & negative guard matrix plus 49 existing Turbo Node contracts on exact LAB27 source. GitHub CI evidence must be recorded before calling gate verified.
- Build decision: Android source, package identity, player, signing and runtime untouched, so full APK+ZIP recompilation is not required for this LAB control-plane-only validation. A future APK needs a separately approved build scope only when meaningful.
- No production deploy, paid service, new secret, release, merge, or promotion.
- Next: independent GitHub Actions readback of new LAB validation, verify positive/negative counts and exact changed-file paths, then report limits.

## Verified LAB gate — 2026-10-20
- GitHub Actions run: https://github.com/Br174/MusicLab/actions/runs/39477529937
- Validated branch SHA: e6435d5afdbac1880b80e791c297aee3dd579ca5
- Python UAB diagnostic/security regressions: 73/73 success, 0 failures.
- Existing Turbo LAB24–LAB27 behavioral contracts: 49/49 success, 0 failures.
- Total: 122/122 checks successful. Exact changed-file guard: seven authorized files; player source, app runtime, Android package and Gradle signing configuration were not modified.
- First validation failure run 39477419165 identified misplaced Python multiline regex flags (35 errors/73 tests), corrected in LAB commit e6435d5afdbac1880b80e791c297aee3dd579ca5 and fully retested.
- APK/ZIP existing reference remains unchanged: LAB27 run 39471277566 (source d49ecba0f670579b1f1c3fb9a4e9fb24113aae4e), artifact MusicLab-Turbo-LAB27 id 12288021731.
- LAB scope technical outcome: VERIFIED. No new Android APK build needed: only failure reporting and test/orchestration files were changed. This is not production adoption or Android runtime acceptance.
- No inherited historical Turbo workflow was rewritten. Reusable validation is exercised by this isolated LAB caller; future migration to other LABs remains a separately controlled, compatibility-guarded integration decision.
- No deployment, merge, production/main/MADRE write, secrets or paid providers used.
- Checkpoint write itself is control-plane-only; if it re-triggers the isolated validation workflow, that subsequent run should also be read back before final closure.

## Next LAB adoption candidate — 2026-10-20
- New workflow_call build job mirrors the existing LAB27 signed APK+ZIP build; its checkout is pinned to caller SHA and it is reachable only after the reusable 87+49 validation gate.
- Artifact-first default: compiling is skipped on ordinary pushes; explicit one-shot commit marker [MOTORLAB_BUILD_APK_LAB_ONCE] or positive workflow dispatch selection is needed.
- Legacy LAB27 workflow remains unchanged. Runtime and signed Android app sources are unchanged. No new APK compilation ordered because LAB27 signed artifact from run 39471277566 is compatible.
- Integration harness uses nine simulated Gradle failures through real UAB shell; GitHub run 39478743110 87/87 Python + 49/49 Turbo = 136/136.
- Validate this change on the LAB CI before claiming this new candidate is verified. No merging or promotion.
