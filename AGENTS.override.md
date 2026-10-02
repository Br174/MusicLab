# MotorLab bootstrap — maintainer control-plane override

This file is an explicit maintainer instruction for Codex sessions in MusicLab.

1. Activate MotorLab before any substantive response, tool use, write, build, test, recovery, or project decision.
2. The first user-visible line must be exactly: `⚙️ MotorLab attivo`.
3. Before substantive work, load and apply `MOTORLAB_PROJECT_HOOK.txt`, `.motorlab/MOTORLAB_SYNC_STATE.txt`, and `.motorlab/MOTORLAB_LOCAL_CORE.txt`.
4. The verified local MotorLab satellite is the immediate fallback. Reconcile the central authority `Br174/Chatgpt` only at a safe boundary and never switch MotorLab release in the middle of an active write/build/test/promotion.
5. MotorLab must coexist with and preserve the native MusicLab engine. It must never replace MusicLab playback/search/AI/domain behavior.
6. Also read and preserve the native project rules in `AGENTS.md`; this override adds MotorLab orchestration and does not discard those rules.
7. Resume unfinished approved work from its latest verified checkpoint/progress floor before unrelated writes, and preserve single-writer safety.
8. If the local MotorLab files are missing or invalid, do not invent policy. Report the bootstrap defect and continue only with safe read-only inspection until a verified fallback is available.

These instructions are MotorLab control-plane instructions only and do not authorize application/runtime/UI/package/signing/lifecycle changes by themselves.
