import legacyWorker from './index.js';
import {
  admitCoverCandidate,
  evaluateEvidence,
  buildDualLanguageQueries,
  buildLanguageMissions,
  statusForScore,
  thresholdForCoverageMode,
} from './brain.js';
import { buildSourcePlan, buildWorkSignature } from './source-router.js';

const DEFAULT_LANGUAGES = ['inglese', 'spagnolo', 'francese', 'portoghese', 'tedesco', 'italiano'];
const MEMORY_RESOLVER_VERSION = 12;
const MAX_MEMORY_RESULTS = 150;

export function buildBrainFocus({ title, artist, mode = 'cover', focus = '', languages = DEFAULT_LANGUAGES } = {}) {
  const queries = buildDualLanguageQueries({ title, artist }).slice(0, 10);
  const missions = buildLanguageMissions({ title, artist, targetLanguages: languages }).map(m => m.language);
  const base = mode === 'originals'
    ? 'Cerca tutte le registrazioni realmente differenti dello stesso interprete: studio, rerecording, live, acoustic, duet, TV/radio, altra lingua e remix ufficiali. Non contare la ristampa dello stesso master come nuova registrazione.'
    : 'Cerca la stessa composizione con performer diverso. Raccogli largo: in dubbio conserva il candidato come UNCERTAIN invece di eliminarlo.';
  return [
    focus ? `Richiesta utente: ${focus}` : '',
    base,
    `Titolo originale da preservare: ${title}. Non tradurre letteralmente il titolo come unica strategia.`,
    'Usa terminologia di ricerca italiana e inglese; per gli adattamenti cerca anche titoli reali diversi, opere collegate, autori, compositori e parolieri.',
    'Ogni fonte e una evidenza, non un verdetto: una fonte assente non e motivo sufficiente per rifiutare; l AI puo contraddire una fonte quando il quadro complessivo lo giustifica.',
    'Per una Cover, performer differente + due segnali coerenti (almeno uno medio/forte) sono sufficienti per ammettere il candidato al pool; la decisione finale resta all AI.',
    `Lingue prioritarie: ${missions.join(', ')}.`,
    `Piste bilingui suggerite: ${queries.join(' | ')}.`,
  ].filter(Boolean).join('\n');
}

export function buildBrainPlanPayload(input = {}) {
  const title = String(input?.title || '').trim();
  const artist = String(input?.artist || '').trim();
  const languages = Array.isArray(input?.languages) && input.languages.length
    ? input.languages
    : DEFAULT_LANGUAGES;
  const workSignature = buildWorkSignature({
    title,
    artist,
    aliases: input?.aliases,
    translatedTitles: input?.translatedTitles,
    writers: input?.writers,
    composers: input?.composers,
    lyricists: input?.lyricists,
    publishers: input?.publishers,
    iswc: input?.iswc,
    musicbrainzWorkId: input?.musicbrainzWorkId,
    year: input?.year,
    language: input?.language,
  });
  const sourceOptions = input?.sources && typeof input.sources === 'object' ? input.sources : {};

  return {
    title,
    artist,
    mode: input?.mode === 'originals' ? 'originals' : 'cover',
    workSignature,
    sourcePlan: buildSourcePlan(sourceOptions),
    dualLanguageQueries: buildDualLanguageQueries({
      title,
      artist,
      writers: workSignature.writers,
      composers: workSignature.composers,
    }),
    languageMissions: buildLanguageMissions({
      title,
      artist,
      targetLanguages: languages,
      writers: workSignature.writers,
      composers: workSignature.composers,
    }),
  };
}

export function decorateDiscoveryPayload(payload, mode = 'cover') {
  if (!payload || typeof payload !== 'object') return payload;
  const original = payload.original || null;
  const versions = Array.isArray(payload.versions) ? payload.versions : [];
  return {
    ...payload,
    versions: versions.map(version => decorateVersion(original, version, mode)),
    brain: {
      policy: 'wide-net-smart-filter',
      sourceVeto: false,
      coverageThresholds: {
        precisa: thresholdForCoverageMode('precisa'),
        selezionata: thresholdForCoverageMode('selezionata'),
        ampia: thresholdForCoverageMode('ampia'),
        esplora: thresholdForCoverageMode('esplora'),
        tutto: thresholdForCoverageMode('tutto'),
      },
    },
  };
}

function decorateVersion(original, version, mode) {
  const signals = collectSignals(original, version);
  const evidence = evaluateEvidence({ positives: signals, negatives: [] });
  let sameWorkScore = numericScore(version?.sameWorkScore) ?? evidence.sameWorkScore;
  let versionTypeScore = numericScore(version?.versionTypeScore) ?? 50;
  let admission = { admitted: true, reason: version?.brainAdmission || 'originals_lane' };

  if (mode === 'cover' && original?.artist && version?.artist) {
    admission = admitCoverCandidate({
      originalArtist: original.artist,
      candidateArtist: version.artist,
      signals,
    });
    if (numericScore(version?.versionTypeScore) == null) {
      if (admission.admitted) versionTypeScore = 75;
      else if (admission.reason === 'same_performer') versionTypeScore = 25;
      else versionTypeScore = 35;
    }
  } else if (mode === 'originals') {
    const sameArtist = normalize(original?.artist) && normalize(original?.artist) === normalize(version?.artist);
    if (numericScore(version?.versionTypeScore) == null) versionTypeScore = sameArtist ? 75 : 30;
    if (sameArtist && sameWorkScore < 50) sameWorkScore = 50;
  }

  const combined = Math.min(sameWorkScore, versionTypeScore);
  const storedStatus = normalizeDecisionStatus(version?.brainStatus);
  const brainStatus = storedStatus || statusForScore(combined);
  return {
    ...version,
    sameWorkScore,
    versionTypeScore,
    brainStatus,
    brainAdmission: version?.brainAdmission || admission.reason,
    brainSignals: Array.isArray(version?.brainSignals) && version.brainSignals.length ? version.brainSignals : signals,
  };
}

function collectSignals(original, version) {
  const signals = [];
  if (normalize(original?.title) && normalize(original?.title) === normalize(version?.title)) {
    signals.push({ kind: 'title_match', strength: 'medium' });
  }
  if (hasCreditOverlap(original?.credits?.composers, version?.credits?.composers)) {
    signals.push({ kind: 'composer_match', strength: 'strong' });
  }
  if (hasCreditOverlap(original?.credits?.songwriters, version?.credits?.songwriters)) {
    signals.push({ kind: 'writer_match', strength: 'strong' });
  }
  if (hasCreditOverlap(original?.credits?.lyricists, version?.credits?.lyricists)) {
    signals.push({ kind: 'lyricist_match', strength: 'strong' });
  }
  if (['straniera', 'adattamento'].includes(normalize(version?.category))) {
    signals.push({ kind: 'adaptation_hint', strength: 'weak' });
  }
  return signals;
}

function hasCreditOverlap(a, b) {
  const left = new Set((Array.isArray(a) ? a : []).map(normalize).filter(Boolean));
  return (Array.isArray(b) ? b : []).map(normalize).some(v => v && left.has(v));
}

function normalize(value) {
  return String(value || '').trim().toLocaleLowerCase('it-IT').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/\s+/g, ' ');
}

function canonical(value) {
  return String(value ?? '')
    .trim()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

function searchKey(title, artist) {
  return `${canonical(title)}|${canonical(artist)}`;
}

function versionIdentity(version) {
  return `${canonical(version?.title)}|${canonical(version?.artist)}`;
}

function normalizeStorageCategory(value) {
  const category = canonical(value || 'cover');
  if (category.includes('remix') || category.includes('rework')) return 'remix';
  if (category.includes('live') || category.includes('dal vivo')) return 'live';
  if (category.includes('stran') || category.includes('adapt')) return 'straniera';
  if (category.includes('original')) return 'originale';
  return 'cover';
}

function versionStorageKey(version) {
  return `${normalizeStorageCategory(version?.category)}|${canonical(version?.artist)}|${canonical(version?.title)}|${canonical(version?.language || '')}`;
}

function numericScore(value) {
  const score = Number(value);
  return Number.isInteger(score) && score >= 0 && score <= 100 ? score : null;
}

function normalizeDecisionStatus(value) {
  const status = String(value || '').trim().toUpperCase();
  return ['APPROVED', 'PROBABLE', 'UNCERTAIN', 'REJECTED'].includes(status) ? status : null;
}

function safeJson(value) {
  try {
    return value ? JSON.parse(value) : {};
  } catch {
    return {};
  }
}

function normalizeSignalStrength(value) {
  const strength = String(value || '').trim().toLowerCase();
  return ['very_strong', 'strong', 'medium', 'weak', 'none'].includes(strength) ? strength : 'medium';
}

function normalizeSignalDirection(value) {
  const direction = String(value || '').trim().toLowerCase();
  return ['positive', 'negative', 'neutral'].includes(direction) ? direction : 'positive';
}

export async function persistWorkSignature(db, signature) {
  if (!db || !signature) return;
  const title = String(signature?.canonicalTitle || '').trim();
  const artist = String(signature?.originalArtist || '').trim();
  if (!title || !artist) return;

  const work = await db.prepare(
    'SELECT id FROM works WHERE search_key=?1 AND COALESCE(resolver_version,0)>=?2 LIMIT 1',
  ).bind(searchKey(title, artist), MEMORY_RESOLVER_VERSION).first();
  if (!work?.id) return;

  const iswc = String(signature?.iswc || '').trim() || null;
  const musicbrainzWorkId = String(signature?.musicbrainzWorkId || '').trim() || null;
  await db.prepare(`
    UPDATE works
    SET work_signature_json=?1,
        iswc=COALESCE(?2,iswc),
        musicbrainz_work_id=COALESCE(?3,musicbrainz_work_id),
        updated_at=CURRENT_TIMESTAMP
    WHERE id=?4
  `).bind(JSON.stringify(signature), iswc, musicbrainzWorkId, work.id).run();

  const aliases = [
    ...(Array.isArray(signature?.aliases) ? signature.aliases.map(alias => ({ alias, kind: 'alternate' })) : []),
    ...(Array.isArray(signature?.translatedTitles) ? signature.translatedTitles.map(alias => ({ alias, kind: 'translated' })) : []),
  ];
  const seen = new Set();
  for (const entry of aliases) {
    const alias = String(entry.alias || '').trim();
    const key = `${canonical(alias)}|${entry.kind}`;
    if (!alias || seen.has(key)) continue;
    seen.add(key);
    await db.prepare(`
      INSERT OR IGNORE INTO work_aliases(work_id,alias,language,alias_kind,source,confidence)
      VALUES(?1,?2,?3,?4,?5,?6)
    `).bind(work.id, alias, null, entry.kind, 'brain', entry.kind === 'translated' ? 75 : 70).run();
  }
}

export async function persistBrainAnnotations(db, input, payload) {
  if (!db || !payload || !Array.isArray(payload?.versions) || payload.versions.length === 0) return;

  const originalTitle = String(payload?.original?.title || input?.title || '').trim();
  const originalArtist = String(payload?.original?.artist || input?.artist || '').trim();
  if (!originalTitle || !originalArtist) return;

  const work = await db.prepare(
    'SELECT id FROM works WHERE search_key=?1 AND COALESCE(resolver_version,0)>=?2 LIMIT 1',
  ).bind(searchKey(originalTitle, originalArtist), MEMORY_RESOLVER_VERSION).first();
  if (!work?.id) return;

  const rows = await db.prepare(
    'SELECT id,version_key,same_work_score,version_type_score,decision_status FROM versions WHERE work_id=?1',
  ).bind(work.id).all();
  const storedByKey = new Map((rows?.results || []).map(row => [String(row.version_key || ''), row]));

  for (const version of payload.versions) {
    const stored = storedByKey.get(versionStorageKey(version));
    if (!stored?.id) continue;

    const sameWorkScore = numericScore(version?.sameWorkScore) ?? numericScore(stored.same_work_score) ?? 50;
    const versionTypeScore = numericScore(version?.versionTypeScore) ?? numericScore(stored.version_type_score) ?? 50;
    const decisionStatus = normalizeDecisionStatus(version?.brainStatus) || normalizeDecisionStatus(stored.decision_status) || 'UNCERTAIN';
    const reason = String(version?.brainAdmission || version?.aiReason || '').trim();
    const signals = Array.isArray(version?.brainSignals) ? version.brainSignals : [];

    await db.prepare(`
      UPDATE versions
      SET same_work_score=?1,version_type_score=?2,decision_status=?3,ai_reason=?4,updated_at=CURRENT_TIMESTAMP
      WHERE id=?5
    `).bind(sameWorkScore, versionTypeScore, decisionStatus, reason || null, stored.id).run();

    const decisionChanged =
      sameWorkScore !== numericScore(stored.same_work_score) ||
      versionTypeScore !== numericScore(stored.version_type_score) ||
      decisionStatus !== normalizeDecisionStatus(stored.decision_status);
    if (decisionChanged) {
      await db.prepare(`
        INSERT INTO decision_history(
          version_id,work_id,decision_status,same_work_score,version_type_score,decided_by,reason,evidence_snapshot_json
        ) VALUES(?1,?2,?3,?4,?5,'ai',?6,?7)
      `).bind(
        stored.id,
        work.id,
        decisionStatus,
        sameWorkScore,
        versionTypeScore,
        reason || null,
        JSON.stringify(signals),
      ).run();
    }

    for (const signal of signals) {
      const kind = String(signal?.kind || '').trim();
      if (!kind) continue;
      const strength = normalizeSignalStrength(signal?.strength);
      const direction = normalizeSignalDirection(signal?.direction);
      await db.prepare(`
        INSERT INTO version_evidence(
          version_id,work_id,source,signal_kind,strength,direction,note,payload_json
        )
        SELECT ?1,?2,?3,?4,?5,?6,?7,?8
        WHERE NOT EXISTS(
          SELECT 1 FROM version_evidence
          WHERE version_id=?1 AND source=?3 AND signal_kind=?4 AND strength=?5 AND direction=?6
        )
      `).bind(
        stored.id,
        work.id,
        'brain',
        kind,
        strength,
        direction,
        reason || null,
        JSON.stringify(signal),
      ).run();
    }
  }
}

export async function persistDiscoveryBrainMemory(db, input, payload) {
  if (!db || !payload) return;
  const original = payload?.original || {};
  const credits = original?.credits && typeof original.credits === 'object' ? original.credits : {};
  const signature = buildWorkSignature({
    title: original?.title || input?.title,
    artist: original?.artist || input?.artist,
    aliases: input?.aliases,
    translatedTitles: input?.translatedTitles,
    writers: credits?.songwriters?.length ? credits.songwriters : input?.writers,
    composers: credits?.composers?.length ? credits.composers : input?.composers,
    lyricists: credits?.lyricists?.length ? credits.lyricists : input?.lyricists,
    publishers: input?.publishers,
    iswc: input?.iswc,
    musicbrainzWorkId: input?.musicbrainzWorkId,
    year: original?.year ?? input?.year,
    language: original?.language ?? input?.language,
  });
  await persistWorkSignature(db, signature);
  await persistBrainAnnotations(db, input, payload);
}

async function forwardDiscovery(request, env, ctx, phase) {
  const input = await request.clone().json();
  const mode = input?.mode === 'originals' ? 'originals' : 'cover';

  if (phase === 'expand' && input?.useMemory !== false && env.DB) {
    const memory = await buildMemoryPayload(input, env);
    if (!memory?.errore && Array.isArray(memory?.versions) && memory.versions.length) {
      const existing = new Set(
        (Array.isArray(input?.existing) ? input.existing : [])
          .map(versionIdentity)
          .filter(Boolean),
      );
      const unseen = memory.versions.filter(version => !existing.has(versionIdentity(version)));
      if (unseen.length) {
        const payload = decorateDiscoveryPayload({
          ...memory,
          fase: 'expand',
          provenienza: 'memoria',
          versions: unseen,
        }, mode);
        return json(payload);
      }
    }
  }

  if (phase === 'expand') {
    input.focus = buildBrainFocus({
      title: String(input?.title || '').trim(),
      artist: String(input?.artist || '').trim(),
      mode,
      focus: String(input?.focus || '').trim(),
    });
  }
  const forwarded = new Request(request.url, {
    method: request.method,
    headers: request.headers,
    body: JSON.stringify(input),
  });
  const response = await legacyWorker.fetch(forwarded, env, ctx);
  if (!response.ok) return response;
  const payload = await response.json();
  const decorated = decorateDiscoveryPayload(payload, mode);

  if (env.DB) {
    const persistence = persistDiscoveryBrainMemory(env.DB, input, decorated).catch(() => undefined);
    if (typeof ctx?.waitUntil === 'function') ctx.waitUntil(persistence);
    else await persistence;
  }

  return new Response(JSON.stringify(decorated), { status: response.status, headers: response.headers });
}

async function buildMemoryPayload(input, env) {
  const title = String(input?.title || '').trim();
  const artist = String(input?.artist || '').trim();
  const mode = input?.mode === 'originals' ? 'originals' : 'cover';
  if (!title || !artist) return { errore: 'title e artist obbligatori', status: 400 };

  const requestedLimit = Number(input?.limit);
  const limit = Number.isFinite(requestedLimit)
    ? Math.max(1, Math.min(Math.trunc(requestedLimit), MAX_MEMORY_RESULTS))
    : MAX_MEMORY_RESULTS;
  const empty = {
    stato: 'pronto',
    fase: 'memory',
    provenienza: 'memoria-vuota',
    seed: { title, artist },
    original: null,
    versions: [],
  };
  if (!env.DB) return empty;

  const work = await env.DB.prepare(
    'SELECT * FROM works WHERE search_key=?1 AND COALESCE(resolver_version,0)>=?2 LIMIT 1',
  ).bind(searchKey(title, artist), MEMORY_RESOLVER_VERSION).first();
  if (!work?.id) return empty;

  const queryLimit = Math.min(MAX_MEMORY_RESULTS * 2, Math.max(MAX_MEMORY_RESULTS, limit * 2));
  const rows = await env.DB.prepare(`
    SELECT canonical_title, canonical_artist, category, language, year, album, credits_json,
           same_work_score, version_type_score, decision_status, ai_reason
    FROM versions
    WHERE work_id=?1
    ORDER BY user_verified DESC,
             CASE WHEN decision_status='APPROVED' THEN 0 WHEN decision_status='PROBABLE' THEN 1 WHEN decision_status='UNCERTAIN' THEN 2 ELSE 3 END,
             CASE WHEN year IS NULL THEN 1 ELSE 0 END, year ASC, canonical_artist ASC
    LIMIT ?2
  `).bind(work.id, queryLimit).all();

  const originalArtistKey = canonical(work.original_artist);
  const versions = (rows?.results || [])
    .filter(row => {
      const samePerformer = originalArtistKey && canonical(row.canonical_artist) === originalArtistKey;
      return mode === 'originals' ? samePerformer : !samePerformer;
    })
    .slice(0, limit)
    .map(row => ({
      title: row.canonical_title,
      artist: row.canonical_artist,
      category: row.category,
      language: row.language,
      year: row.year,
      album: row.album,
      credits: safeJson(row.credits_json),
      sameWorkScore: numericScore(row.same_work_score),
      versionTypeScore: numericScore(row.version_type_score),
      brainStatus: normalizeDecisionStatus(row.decision_status),
      brainAdmission: null,
      brainSignals: [],
      aiReason: row.ai_reason || null,
    }));

  return {
    stato: 'pronto',
    fase: 'memory',
    provenienza: versions.length ? 'memoria' : 'memoria-vuota',
    seed: { title, artist },
    original: {
      title: work.canonical_title,
      artist: work.original_artist,
      year: work.original_year,
      language: work.original_language,
      album: null,
      credits: safeJson(work.credits_json),
    },
    versions,
  };
}

async function memoryDiscovery(request, env) {
  const payload = await buildMemoryPayload(await request.json(), env);
  if (payload?.errore) return json({ errore: payload.errore }, payload.status || 400);
  return json(payload);
}

async function brainPlan(request) {
  const input = await request.json();
  const plan = buildBrainPlanPayload(input);
  if (!plan.title) return json({ errore: 'title obbligatorio' }, 400);
  return json(plan);
}

async function saveDecision(request, env) {
  if (!env.DB) return json({ stato: 'ignorato', motivo: 'D1 non configurato' });
  const input = await request.json();
  const versionId = String(input?.versionId || '').trim();
  const workId = String(input?.workId || '').trim();
  const status = String(input?.status || '').trim().toUpperCase();
  if (!versionId || !workId || !['APPROVED', 'PROBABLE', 'UNCERTAIN', 'REJECTED'].includes(status)) {
    return json({ errore: 'decisione non valida' }, 400);
  }
  const userVerified = status === 'APPROVED' ? 1 : 0;
  const userRejected = status === 'REJECTED' ? 1 : 0;
  await env.DB.prepare('UPDATE versions SET decision_status=?1,user_verified=?2,user_rejected=?3,updated_at=CURRENT_TIMESTAMP WHERE id=?4 AND work_id=?5')
    .bind(status, userVerified, userRejected, versionId, workId).run();
  await env.DB.prepare(`INSERT INTO decision_history(version_id,work_id,decision_status,same_work_score,version_type_score,decided_by,reason,evidence_snapshot_json)
    VALUES(?1,?2,?3,?4,?5,'user',?6,?7)`)
    .bind(versionId, workId, status, input?.sameWorkScore ?? null, input?.versionTypeScore ?? null,
      String(input?.reason || ''), JSON.stringify(input?.evidence || [])).run();
  return json({ stato: 'salvato', status });
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'access-control-allow-origin': '*',
    },
  });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === 'POST' && url.pathname === '/api/v1/brain/plan') return brainPlan(request);
    if (request.method === 'POST' && url.pathname === '/api/v1/brain/decision') return saveDecision(request, env);
    if (request.method === 'POST' && url.pathname === '/api/v1/memory/discover') return memoryDiscovery(request, env);
    if (request.method === 'POST' && url.pathname === '/api/v1/discover/initial') return forwardDiscovery(request, env, ctx, 'initial');
    if (request.method === 'POST' && url.pathname === '/api/v1/discover/expand') return forwardDiscovery(request, env, ctx, 'expand');
    return legacyWorker.fetch(request, env, ctx);
  },
};
