# STATO ATTUALE E PERCORSO SENZA OPERAZIONI MANUALI DUPLICATE (23 ottobre 2026)

**QUESTO RIQUADRO SOSTITUISCE LE ISTRUZIONI DI PROVISIONING RIPORTATE PIÙ SOTTO, ORMAI STORICHE. NON RICREARE D1 E NON RIESEGUIRE SQL.**

- **Base unica:** Turbo LAB46, PR #10 DRAFT, head di riferimento `2838e91138a35b3e6621e452f0f55d46989e1e4b`. Tutto il codice delle fasi LAB precedenti resta nella genealogia Turbo; NON fare reset, merge ciechi, doppie copie o downgrade.
- **Database esistente, già migrato:** `musiclab-turbo-lab46-staging` UUID `e66bd500-cdaa-453c-a81c-c624a9d2611e`; 15/15 tabelle musicali, indici e sentinel verificati da interrogazioni D1 REALI in sola lettura. Il bootstrap e `music_schema_additive_once.sql` NON vanno ripetuti. La configurazione di produzione `wrangler.jsonc` e il D1 `musiclab-intelligence-db` sono estranei e intoccabili.
- **Senza aprire GitHub:** GitHub Actions già compila APK+ZIP e firma/verifica identità aggiornamento all'arrivo di modifiche LAB. [APK LAB46 al commit attuale](https://github.com/Br174/MusicLab/actions/runs/39916005305): SUCCESS. Non richiedere all'owner un nuovo workflow né una nuova build manuale, salvo reale guasto.
- **Senza aprire Cloudflare per nuovi controlli D1:** [read-only CI 39916005322](https://github.com/Br174/MusicLab/actions/runs/39916005322): SUCCESS, 34/34 test integrati, D1 remoto 15/15, Wrangler staging `--dry-run`, 0 scritture remote, 0 deploy. I test riusano `worker-entry.mjs` + `src/lab46-staging-sync.js`, gli archivi e il client `CloudMusicDiscovery.kt` già ereditati. NESSUN nuovo database, Worker parallelo, client Cloud alternativo o secondo sistema di ricevute.
- **Codice pronto MA NON LIVE:** ingresso staging `lab46-staging-only/worker-entry.mjs`, configurazione `wrangler.staging.verified.jsonc` con `workers_dev=false` e nessuna route, chiave privata P-256 in Android Keystore, chiave pubblica ancora NON associata al Worker, trasporto Android firmato ancora dormiente. Non dire che la sincronizzazione Android remota è completata.
- **Riutilizzo autorizzato da vecchi rami:** moduli/contratti solo se già ereditati o compatibili, sempre senza regressioni. I vecchi workflow `lab20-cloudflare-deploy.yml` e `lab38b-cloud-archive-coverinfo-gate.yml` eseguivano deploy, migrazioni e gestione Gemini sul Worker/D1 di PRODUZIONE; riutilizzare solo la sequenza di verifiche locali, MAI i loro target o comandi remoti nel Turbo.
- **Uniche operazioni remote future da autorizzare separatamente:** associare e verificare fuori banda la chiave pubblica del telefono; predisporre una credenziale Cloudflare con privilegi limitati allo staging (i permessi di sola lettura attuali NON consentono deploy); configurare la chiave pubblica e il segreto SOLO nel Worker isolato; approvare un eventuale deploy dell'unico Worker staging e un URL HTTPS dedicato; prova end-to-end Android+ricevuta D1 con rollback e verifica senza regressioni. La configurazione non va scritta nel repository o nell'APK. Non presupporre di poter riutilizzare un vecchio token con privilegi sulla produzione.
- **Sicurezza/costo:** nessun deploy, nessuna modifica segreti, nessuna scrittura Cloudflare, main/MADRE e LAST_GOOD preservati; verifica quota Workers Free/costo extra €0 e permessi reali prima di qualsiasi attivazione. Se una credenziale/staging route non può essere autorizzata tramite il connettore, richiedere soltanto il passaggio manuale indispensabile, non rifare tutte le operazioni storiche.

---

## Archivio storico della preparazione iniziale (non ripetere)

# MusicLab Turbo LAB46 — isolated D1 staging preparation and approval gate

Status: **PREPARED ONLY, NOT PROVISIONED**.
Checkpoint: 2026-10-23, `Br174/MusicLab` PR #10, LAB46 branch
`uab-build/musiclab-turbo-lab46-cloud-behavior-progressive-family-02`.

## Confirmed evidence and hard boundaries

- The account owner confirmed **Workers Free** for MusicLab on 2026-10-23.
  This is an **owner attestation**, not independently provable from
  Cloudflare subscriptions API output (which returned an empty list).
- Cloudflare account ID: `5781b597517f6f7fe25751170a99b9ab` (owner-confirmed).
- Actual read-only D1 inventory in
  [workflow run 39867718270](https://github.com/Br174/MusicLab/actions/runs/39867718270):
  **3 databases**, existing production
  `musiclab-intelligence-db` present, target isolated staging
  `musiclab-turbo-lab46-staging` absent.
- Official Workers Free D1 database cap: 10 per account (recheck live immediately
  before any proposed create). Free limits also apply to operations and total
  storage. Free plan is not permission to upgrade or incur charges.
- Repository token `MUSICLAB_LAB46_CF_PREFLIGHT_TOKEN` is **READ-ONLY**.
  Do not modify it, replace `CLOUDFLARE_API_TOKEN`, copy secrets into the
  repository or Android package, or use production tokens for staging.
- LAB46 remains DRAFT; main, production D1, existing Worker, native engine,
  APK signing, playback and LAST_GOOD are protected.

## Reusable local safety gate

Run in repo root:

```sh
node --check cloudflare/music-intelligence/lab46-staging-only/staging_preparation_guard.mjs
node cloudflare/music-intelligence/lab46-staging-only/staging_preparation_guard.mjs
```

This script has no network/API calls. It asserts:

1. The target Worker name and `LAB46_ISOLATED_STAGING=1`.
2. Exactly one `DB` binding with the **staging** D1 name.
3. The template contains only a placeholder D1 UUID, never a production ID.
4. The existing production binding remains distinct.
5. Bootstrap SQL creates only LAB46 staging tables, receipts and sentinel.
6. A positive test and five tampering/negative tests pass.

An actual D1 identifier must be supplied **only after a separately authorized
staging D1 is created and positively reidentified**, and it must not be inserted
into the shared production Wrangler config.

## Proposed remote create (NOT authorized by this document)

**One and only one** D1 resource:
`musiclab-turbo-lab46-staging` on account
`5781b597517f6f7fe25751170a99b9ab`.

### Mandatory checks immediately before creation

1. Confirm exact LAB46 branch HEAD, PR #10 DRAFT, isolated config, and LAST_GOOD.
2. Re-run D1 GET inventory and verify production remains present and the staging
   target is absent; if target exists, **stop** and inspect before further actions.
3. Verify account Workers Free (owner attestation), free D1 capacity, no billing
   upgrade, and expected total storage/usage headroom.
4. Require a **new explicit user authorization scoped to one D1 create**;
   never use an implicit blanket approval for deploy/migrations or production.
5. Confirm a real authenticated Cloudflare action path. API create requires
   account-scoped **D1 Write**, which is NOT granted to the existing read-only
   token. If no safe connected executor can execute it, owner creates the
   named database using the Cloudflare dashboard; no secret shared in chat.
6. Read back the new name and UUID via read-only inventory, and ensure its UUID
   differs from all production DB IDs. Persist only the non-secret UUID and
   checkpoint, not a token or plaintext secret.

### Separate stages requiring separate authorizations

- Apply `lab46-staging-only/bootstrap.sql` **only** to the verified new D1.
  Check sentinel and receipts through isolated reads, test replay/idempotency.
- Prepare authenticated staging Worker with the isolated D1 binding, server-side
  secrets, and explicit non-production route; a static privileged API token
  must **never be embedded in Android**.
- Deploy staging Worker only after a separate scoped approval and negative
  tests for missing/wrong secret, bad binding and missing sentinel.
- End-to-end Android remote transport and physical-device audio/playback
  regressions must pass before any promotion. Never use production D1 fallback.

### Strict stop conditions

Stop on a changed HEAD, lost branch or scope, any paid-plan/upgrade request,
uncertain cost, wrong database ID, staging name collision, insufficient D1 free
quota, unavailable auth mechanism, or requirement to modify production/main.

**Current state:** preflight-only token usable; staging D1 NOT CREATED;
no D1 schema writes or Cloudflare deploy authorized; global LAB46 NOT COMPLETE.
