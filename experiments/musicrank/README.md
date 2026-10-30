# MusicRank Independent V1 — experimental build contract

Status: isolated experiment scaffold; NOT an APK and NOT yet implemented.

## Isolation
- Source branch: experiment/musicrank-independent-v1-20261030, based on motorlab/active-musiclab-turbo.
- Android applicationId: it.verlezza.musiclab.musicrank (distinct from MusicLab Turbo).
- Display name: MusicLab MusicRank.
- Independent app data/cache/database; do not reuse Turbo cloud mutations or storage.
- Never merge into main, MADRE or production without separate explicit approval.
- Free services only. Preserve signing, player and unrelated UI.

## Discovery
1. Resolve musical work identity with titles, documented adaptations, authors and recording credits. The original performer is a reference, not a global performer filter.
2. Gather recording candidates from multiple documentary sources. Keep each rendition's own title, artist, release, source and identifiers.
3. Generate YouTube queries PER rendition: rendition title + rendition artist, alternate documented titles, official/live/studio variants. Add broad discovery in parallel.
4. Never discard a documented adaptation merely because its video title differs from the original language.
5. Verify video correspondence against independent evidence and reject only wrong work, wrong attribution, karaoke, and duplicate recording/video.

## MusicRank score (ranking only, not admission)
- recording authority 30
- performer significance 20
- attribution reliability 25
- video quality 15
- musical/historical significance 10
- unknown evidence contributes 0 to that dimension; no invented confidence percentage.
- stable deterministic tie-breaker, provisional status for uncertain candidates.
- late high-ranked arrivals may rise, but avoid shifting the row being played or interacted with.

## Runtime behavior
- Concurrent bounded work queues; deadlines defer candidates rather than erase them.
- Append verified unique candidates, reprioritize display without losing found items.
- UI never waits for network or ranking.
- Compare side-by-side on identical songs: Il mondo, Sapore di sale, Soli.
- Log discovery counts, per-rendition YouTube queries, videos returned, reasons rejected, duplicates, displayed and latency.

## Release gates
1. Implement independent discovery/ranking code and tests on this branch.
2. Set unique applicationId and app label in build workflow for this branch.
3. Unit tests + end-to-end regression + Scudo360; compare quantity and relevance with current Turbo.
4. Produce separately installable APK and ZIP only after green build, with direct verified links.

No changes have been made to production in this scaffold commit.
