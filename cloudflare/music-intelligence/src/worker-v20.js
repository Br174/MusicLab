import legacyWorker from './index.js';
import {
  admitCoverCandidate,
  evaluateEvidence,
  buildDualLanguageQueries,
  buildLanguageMissions,
  statusForScore,
  thresholdForCoverageMode,
} from './brain.js';

const DEFAULT_LANGUAGES = ['inglese', 'spagnolo', 'francese', 'portoghese', 'tedesco', 'italiano'];

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
  let sameWorkScore = evidence.sameWorkScore;
  let versionTypeScore = 50;
  let admission = { admitted: true, reason: 'originals_lane' };

  if (mode === 'cover' && original?.artist && version?.artist) {
    admission = admitCoverCandidate({
      originalArtist: original.artist,
      candidateArtist: version.artist,
      signals,
    });
    if (admission.admitted) versionTypeScore = 75;
    else if (admission.reason === 'same_performer') versionTypeScore = 25;
    else versionTypeScore = 35;
  } else if (mode === 'originals') {
    const sameArtist = normalize(original?.artist) && normalize(original?.artist) === normalize(version?.artist);
    versionTypeScore = sameArtist ? 75 : 30;
    if (sameArtist && sameWorkScore < 50) sameWorkScore = 50;
  }

  const combined = Math.min(sameWorkScore, versionTypeScore);
  const brainStatus = statusForScore(combined);
  return {
    ...version,
    sameWorkScore,
    versionTypeScore,
    brainStatus,
    brainAdmission: admission.reason,
    brainSignals: signals,
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

async function forwardDiscovery(request, env, ctx, phase) {
  const input = await request.clone().json();
  const mode = input?.mode === 'originals' ? 'originals' : 'cover';
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
  return new Response(JSON.stringify(decorated), { status: response.status, headers: response.headers });
}

async function brainPlan(request) {
  const input = await request.json();
  const title = String(input?.title || '').trim();
  const artist = String(input?.artist || '').trim();
  if (!title) return json({ errore: 'title obbligatorio' }, 400);
  const languages = Array.isArray(input?.languages) && input.languages.length ? input.languages : DEFAULT_LANGUAGES;
  return json({
    title,
    artist,
    mode: input?.mode === 'originals' ? 'originals' : 'cover',
    dualLanguageQueries: buildDualLanguageQueries({ title, artist, writers: input?.writers || [], composers: input?.composers || [] }),
    languageMissions: buildLanguageMissions({ title, artist, targetLanguages: languages, writers: input?.writers || [], composers: input?.composers || [] }),
  });
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
    if (request.method === 'POST' && url.pathname === '/api/v1/discover/initial') return forwardDiscovery(request, env, ctx, 'initial');
    if (request.method === 'POST' && url.pathname === '/api/v1/discover/expand') return forwardDiscovery(request, env, ctx, 'expand');
    return legacyWorker.fetch(request, env, ctx);
  },
};
