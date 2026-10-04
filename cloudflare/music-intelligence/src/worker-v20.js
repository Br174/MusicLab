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
    'Per Cover/Live/Remix nella stessa lingua: titolo canonico completo come frase autonoma + almeno una evidenza indipendente della stessa opera. Per una straniera il titolo puo essere diverso e basta una evidenza medio/forte della stessa opera.',
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
      category: version.category,
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
  if (titleAutonomousMatch(original?.title, version?.title)) {
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

function titleAutonomousMatch(baseTitle, candidateTitle) {
  const base = canonical(baseTitle);
  const candidate = canonical(candidateTitle);
  if (!base || !candidate) return false;
  if (base === candidate) return true;

  const segments = String(candidateTitle || '')
    .split(/[()[\]{}]|\s*[-–—:|·]\s*/)
    .map(canonical)
    .filter(Boolean);
  if (segments.includes(base)) {
    const continuation = new Set([
      'che', 'chi', 'cui', 'quando', 'dove', 'come', 'perche',
      'that', 'which', 'who', 'when', 'where', 'because',
      'que', 'quien', 'cuando', 'donde', 'como', 'porque',
    ]);
    const extras = segments.filter(segment => segment !== base);
    if (!extras.some(segment => continuation.has(segment.split(' ')[0]))) return true;
  }

  const technical = new Set([
    'official', 'music', 'video', 'audio', 'lyrics', 'lyric', 'visualizer',
    'remaster', 'remastered', 'version', 'versione', 'cover', 'live', 'dal',
    'vivo', 'concert', 'concerto', 'performance', 'session', 'festival',
    'remix', 'mix', 'rework', 'radio', 'edit', 'extended', 'club', 'acoustic',
    'unplugged', 'studio', 'mono', 'stereo', 'hd', 'hq', '4k', 'feat', 'ft',
    'featuring', 'duet', 'duetto',
  ]);
  const residual = candidate.startsWith(base + ' ')
    ? candidate.slice(base.length).trim()
    : candidate.endsWith(' ' + base)
      ? candidate.slice(0, candidate.length - base.length).trim()
      : '';
  if (!residual) return false;
  return residual.split(' ').every(token => technical.has(token) || /^(?:18|19|20)\d{2}$/.test(token));
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

export function decisionIdentityKeys(input = {}) {
  const candidate = input?.candidate && typeof input.candidate === 'object' ? input.candidate : {};
  const originalTitle = String(input?.originalTitle || '').trim();
  const originalArtist = String(input?.originalArtist || '').trim();
  const candidateTitle = String(candidate?.title || '').trim();
  const candidateArtist = String(candidate?.artist || '').trim();
  return {
    workSearchKey: originalTitle && originalArtist ? searchKey(originalTitle, originalArtist) : '',
    versionKey: candidateTitle && candidateArtist ? versionStorageKey(candidate) : '',
  };
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

function coverageMissionText(input, family, dimensionKey) {
  const title = String(input?.title || '').trim();
  const artist = String(input?.artist || '').trim();
  const mode = input?.mode === 'originals' ? 'originals' : 'cover';
  const target = `${title}${artist ? ` — ${artist}` : ''}`;
  if (family === 'era') return `Esplora ${target} nel decennio ${dimensionKey}. Cerca registrazioni/versioni reali non ancora note.`;
  if (family === 'language_adaptation') return `Esplora ${target} in ${dimensionKey}: adattamenti reali, titoli alternativi e opere collegate; non limitarti alla traduzione letterale.`;
  if (family === 'release_context') return `Esplora ${target} nel contesto ${dimensionKey.replaceAll('_', ' ')} e cerca risultati reali non ancora noti.`;
  if (mode === 'originals') return `Esplora ${target} come ${dimensionKey.replaceAll('_', ' ')} dello stesso interprete originale, distinguendo registrazioni realmente diverse dalle semplici ristampe.`;
  return `Esplora ${target} come ${dimensionKey.replaceAll('_', ' ')} della stessa composizione con performer diverso.`;
}

function desiredCoverageCells(input) {
  const mode = input?.mode === 'originals' ? 'originals' : 'cover';
  const year = Number(input?.year);
  const startYear = Number.isInteger(year) && year >= 1800 && year <= 2100 ? year : 1960;
  const startDecade = Math.floor(startYear / 10) * 10;
  const currentDecade = Math.floor(new Date().getUTCFullYear() / 10) * 10;
  const eraCells = [];
  for (let decade = startDecade; decade <= currentDecade; decade += 10) {
    eraCells.push({ family: 'era', dimensionKey: `${decade}s`, strategy: 'decade_sweep' });
  }

  const versionTypes = mode === 'originals'
    ? ['studio', 'rerecording', 'live', 'acoustic', 'duet', 'tv_radio', 'foreign_language', 'remix']
    : ['studio_cover', 'foreign_adaptation', 'acoustic', 'live', 'remix', 'tribute'];
  const versionCells = versionTypes.map(dimensionKey => ({ family: 'version_type', dimensionKey, strategy: 'version_type_sweep' }));
  const languageCells = DEFAULT_LANGUAGES.map(dimensionKey => ({
    family: 'language_adaptation',
    dimensionKey,
    strategy: 'adaptation_sweep',
    targetLanguage: dimensionKey,
  }));
  const releaseCells = ['singles', 'albums', 'compilations', 'tv_radio', 'festivals'].map(dimensionKey => ({
    family: 'release_context',
    dimensionKey,
    strategy: 'release_context_sweep',
  }));

  const interleaved = [];
  if (eraCells.length) interleaved.push(eraCells.shift());
  while (versionCells.length || languageCells.length || eraCells.length || releaseCells.length) {
    if (versionCells.length) interleaved.push(versionCells.shift());
    if (languageCells.length) interleaved.push(languageCells.shift());
    if (eraCells.length) interleaved.push(eraCells.shift());
    if (releaseCells.length) interleaved.push(releaseCells.shift());
  }
  return interleaved;
}

export async function planCoverageMissions(db, input, limit = 6) {
  if (!db) return [];
  const title = String(input?.title || '').trim();
  const artist = String(input?.artist || '').trim();
  if (!title || !artist) return [];

  const work = await db.prepare(
    'SELECT id FROM works WHERE search_key=?1 AND COALESCE(resolver_version,0)>=?2 LIMIT 1',
  ).bind(searchKey(title, artist), MEMORY_RESOLVER_VERSION).first();
  if (!work?.id) return [];

  const existing = await db.prepare(
    'SELECT family,dimension_key,state FROM coverage_cells WHERE work_id=?1',
  ).bind(work.id).all();
  const unavailable = new Set(
    (existing?.results || [])
      .filter(row => ['searched', 'empty', 'in_flight'].includes(String(row?.state || '')))
      .map(row => `${row.family}|${row.dimension_key}`),
  );
  const boundedLimit = Math.max(1, Math.min(Number(limit) || 6, 12));
  const selected = desiredCoverageCells(input)
    .filter(cell => !unavailable.has(`${cell.family}|${cell.dimensionKey}`))
    .slice(0, boundedLimit);

  const missions = [];
  for (const cell of selected) {
    const id = crypto.randomUUID();
    const queryText = coverageMissionText(input, cell.family, cell.dimensionKey);
    const mission = {
      id,
      workId: work.id,
      family: cell.family,
      dimensionKey: cell.dimensionKey,
      strategy: cell.strategy,
      targetLanguage: cell.targetLanguage || null,
      queryText,
    };
    await db.prepare(`
      INSERT INTO search_missions(
        id,work_id,family,dimension_key,target_language,strategy,query_text,status
      ) VALUES(?1,?2,?3,?4,?5,?6,?7,'running')
    `).bind(id, work.id, cell.family, cell.dimensionKey, cell.targetLanguage || null, cell.strategy, queryText).run();
    await db.prepare(`
      INSERT INTO coverage_cells(work_id,family,dimension_key,state,result_count,last_mission_id,updated_at)
      VALUES(?1,?2,?3,'in_flight',0,?4,CURRENT_TIMESTAMP)
      ON CONFLICT(work_id,family,dimension_key) DO UPDATE SET
        state='in_flight',last_mission_id=excluded.last_mission_id,updated_at=CURRENT_TIMESTAMP
    `).bind(work.id, cell.family, cell.dimensionKey, id).run();
    missions.push(mission);
  }
  return missions;
}

export async function completeCoverageMissions(db, missions, resultCount = 0) {
  if (!db || !Array.isArray(missions) || missions.length === 0) return;
  const count = Math.max(0, Number(resultCount) || 0);
  const missionStatus = count > 0 ? 'completed' : 'empty';
  const cellState = count > 0 ? 'searched' : 'empty';
  for (const mission of missions) {
    if (!mission?.id || !mission?.workId || !mission?.family || !mission?.dimensionKey) continue;
    await db.prepare(`
      UPDATE search_missions
      SET status=?1,result_count=?2,unique_result_count=?3,updated_at=CURRENT_TIMESTAMP
      WHERE id=?4
    `).bind(missionStatus, count, count, mission.id).run();
    await db.prepare(`
      UPDATE coverage_cells
      SET state=?1,result_count=?2,last_mission_id=?3,updated_at=CURRENT_TIMESTAMP
      WHERE work_id=?4 AND family=?5 AND dimension_key=?6
    `).bind(cellState, count, mission.id, mission.workId, mission.family, mission.dimensionKey).run();
  }
}

export async function failCoverageMissions(db, missions) {
  if (!db || !Array.isArray(missions) || missions.length === 0) return;
  for (const mission of missions) {
    if (!mission?.id || !mission?.workId || !mission?.family || !mission?.dimensionKey) continue;
    await db.prepare(`
      UPDATE search_missions
      SET status=?1,result_count=0,unique_result_count=0,updated_at=CURRENT_TIMESTAMP
      WHERE id=?2
    `).bind('failed', mission.id).run();
    await db.prepare(`
      UPDATE coverage_cells
      SET state=?1,result_count=0,last_mission_id=?2,updated_at=CURRENT_TIMESTAMP
      WHERE work_id=?3 AND family=?4 AND dimension_key=?5
    `).bind('unsearched', mission.id, mission.workId, mission.family, mission.dimensionKey).run();
  }
}

async function forwardDiscovery(request, env, ctx, phase) {
  const input = await request.clone().json();
  const mode = input?.mode === 'originals' ? 'originals' : 'cover';
  let memory = null;
  let coverageMissions = [];

  if (phase === 'expand' && input?.useMemory !== false && env.DB) {
    memory = await buildMemoryPayload(input, env);
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
    if (env.DB) {
      coverageMissions = await planCoverageMissions(env.DB, {
        ...input,
        year: input?.year ?? memory?.original?.year ?? null,
        language: input?.language ?? memory?.original?.language ?? null,
      }, 6).catch(() => []);
    }
    const missionFocus = coverageMissions
      .map(mission => mission.queryText)
      .filter(Boolean)
      .join('\n');
    const requestedFocus = [
      String(input?.focus || '').trim(),
      missionFocus ? `Missioni Coverage Map nuove:\n${missionFocus}` : '',
    ].filter(Boolean).join('\n');
    input.focus = buildBrainFocus({
      title: String(input?.title || '').trim(),
      artist: String(input?.artist || '').trim(),
      mode,
      focus: requestedFocus,
    });
  }
  const forwarded = new Request(request.url, {
    method: request.method,
    headers: request.headers,
    body: JSON.stringify(input),
  });
  let response;
  try {
    response = await legacyWorker.fetch(forwarded, env, ctx);
  } catch (error) {
    if (env.DB && coverageMissions.length) {
      await failCoverageMissions(env.DB, coverageMissions).catch(() => undefined);
    }
    throw error;
  }
  if (!response.ok) {
    if (env.DB && coverageMissions.length) {
      await failCoverageMissions(env.DB, coverageMissions).catch(() => undefined);
    }
    return response;
  }
  const payload = await response.json();
  const decorated = decorateDiscoveryPayload(payload, mode);

  if (env.DB) {
    const tasks = [persistDiscoveryBrainMemory(env.DB, input, decorated)];
    if (coverageMissions.length) {
      tasks.push(completeCoverageMissions(env.DB, coverageMissions, decorated.versions.length));
    }
    const persistence = Promise.all(tasks).catch(() => undefined);
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

  let work = await env.DB.prepare(
    'SELECT * FROM works WHERE search_key=?1 AND COALESCE(resolver_version,0)>=?2 LIMIT 1',
  ).bind(searchKey(title, artist), MEMORY_RESOLVER_VERSION).first();

  // LAB38B Work Identity fallback: if the entry point is a cover performer,
  // reuse the canonical work only when this exact canonical title identifies
  // one unambiguous learned work. This makes the same song converge on the
  // same cloud archive without guessing across homonymous compositions.
  if (!work?.id) {
    const titlePrefix = `${canonical(title)}|%`;
    const byTitle = await env.DB.prepare(`
      SELECT * FROM works
      WHERE search_key LIKE ?1
        AND COALESCE(resolver_version,0)>=?2
      ORDER BY updated_at DESC
      LIMIT 2
    `).bind(titlePrefix, MEMORY_RESOLVER_VERSION).all();
    const matches = byTitle?.results || [];
    if (matches.length === 1) work = matches[0];
  }
  if (!work?.id) return empty;

  const queryLimit = Math.min(MAX_MEMORY_RESULTS * 2, Math.max(MAX_MEMORY_RESULTS, limit * 2));
  const rows = await env.DB.prepare(`
    SELECT canonical_title, canonical_artist, category, language, year, album, credits_json,
           same_work_score, version_type_score, decision_status, ai_reason,
           user_verified, user_rejected
    FROM versions
    WHERE work_id=?1
    ORDER BY user_verified DESC,
             CASE WHEN decision_status='APPROVED' THEN 0 WHEN decision_status='PROBABLE' THEN 1 WHEN decision_status='UNCERTAIN' THEN 2 ELSE 3 END,
             CASE WHEN year IS NULL THEN 1 ELSE 0 END, year ASC, canonical_artist ASC
    LIMIT ?2
  `).bind(work.id, queryLimit).all();

  const originalArtistKey = canonical(work.original_artist);
  const modeMatches = row => {
    const samePerformer = originalArtistKey && canonical(row.canonical_artist) === originalArtistKey;
    return mode === 'originals' ? samePerformer : !samePerformer;
  };
  const toVersion = row => ({
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
    brainAdmission: row.user_verified ? 'cloud_user_approved' : 'cloud_memory',
    brainSignals: [],
    aiReason: row.ai_reason || null,
  });
  const modeRows = (rows?.results || []).filter(modeMatches);
  const versions = modeRows
    .filter(row => Number(row.user_rejected || 0) !== 1)
    .slice(0, limit)
    .map(toVersion);
  const rejectedVersions = modeRows
    .filter(row => Number(row.user_rejected || 0) === 1)
    .map(row => ({
      title: row.canonical_title,
      artist: row.canonical_artist,
      category: row.category,
      language: row.language,
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
    rejectedVersions,
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

export async function saveBrainDecision(db, input = {}) {
  if (!db) return { httpStatus: 200, body: { stato: 'ignorato', motivo: 'D1 non configurato' } };

  const status = String(input?.status || '').trim().toUpperCase();
  if (!['APPROVED', 'PROBABLE', 'UNCERTAIN', 'REJECTED'].includes(status)) {
    return { httpStatus: 400, body: { errore: 'decisione non valida' } };
  }

  const candidate = input?.candidate && typeof input.candidate === 'object' ? input.candidate : {};
  const originalTitle = String(input?.originalTitle || '').trim();
  const originalArtist = String(input?.originalArtist || '').trim();
  const candidateTitle = String(candidate?.title || '').trim();
  const candidateArtist = String(candidate?.artist || '').trim();
  if (!originalTitle || !originalArtist || !candidateTitle || !candidateArtist) {
    return { httpStatus: 400, body: { errore: 'opera e candidato obbligatori' } };
  }

  const keys = decisionIdentityKeys(input);
  let workId = String(input?.workId || '').trim();
  if (!workId && keys.workSearchKey) {
    const work = await db.prepare(
      'SELECT id FROM works WHERE search_key=?1 AND COALESCE(resolver_version,0)>=?2 LIMIT 1',
    ).bind(keys.workSearchKey, MEMORY_RESOLVER_VERSION).first();
    workId = String(work?.id || '').trim();
  }

  // LAB38B manual decisions are allowed to create the cloud-memory row.
  // This closes the old gap where a newly discovered Discogs/provider candidate
  // could not be approved because it had never been persisted by the AI lane.
  if (!workId) {
    const proposedWorkId = crypto.randomUUID();
    await db.prepare(`
      INSERT INTO works(
        id,search_key,canonical_title,original_artist,original_year,original_language,
        credits_json,ai_model,resolver_version
      ) VALUES(?1,?2,?3,?4,NULL,NULL,'{}','manual-user',?5)
      ON CONFLICT(search_key) DO UPDATE SET
        canonical_title=excluded.canonical_title,
        original_artist=excluded.original_artist,
        resolver_version=MAX(COALESCE(works.resolver_version,0),excluded.resolver_version),
        updated_at=CURRENT_TIMESTAMP
    `).bind(
      proposedWorkId,
      searchKey(originalTitle, originalArtist),
      originalTitle,
      originalArtist,
      MEMORY_RESOLVER_VERSION,
    ).run();
    const created = await db.prepare(
      'SELECT id FROM works WHERE search_key=?1 LIMIT 1',
    ).bind(searchKey(originalTitle, originalArtist)).first();
    workId = String(created?.id || '').trim();
  }
  if (!workId) return { httpStatus: 500, body: { errore: 'opera non salvabile' } };

  let versionId = String(input?.versionId || '').trim();
  if (!versionId && keys.versionKey) {
    const exact = await db.prepare(
      'SELECT id FROM versions WHERE work_id=?1 AND version_key=?2 LIMIT 1',
    ).bind(workId, keys.versionKey).first();
    versionId = String(exact?.id || '').trim();
  }

  if (!versionId) {
    const proposedVersionId = crypto.randomUUID();
    const sameWorkScore = numericScore(input?.sameWorkScore) ?? 50;
    const versionTypeScore = numericScore(input?.versionTypeScore) ?? 50;
    const category = normalizeStorageCategory(candidate?.category);
    const language = String(candidate?.language || '').trim() || null;
    const year = Number(candidate?.year);
    const validYear = Number.isInteger(year) && year >= 1800 && year <= 2100 ? year : null;
    const album = String(candidate?.album || '').trim() || null;
    const credits = candidate?.credits && typeof candidate.credits === 'object' ? candidate.credits : {};
    await db.prepare(`
      INSERT INTO versions(
        id,work_id,version_key,canonical_title,canonical_artist,category,language,year,album,
        credits_json,ai_model,same_work_score,version_type_score,decision_status,ai_reason,
        user_verified,user_rejected
      ) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,'manual-user',?11,?12,'UNCERTAIN',NULL,0,0)
      ON CONFLICT(work_id,version_key) DO UPDATE SET
        canonical_title=excluded.canonical_title,
        canonical_artist=excluded.canonical_artist,
        language=COALESCE(excluded.language,versions.language),
        year=COALESCE(excluded.year,versions.year),
        album=COALESCE(excluded.album,versions.album),
        credits_json=CASE WHEN excluded.credits_json<>'{}' THEN excluded.credits_json ELSE versions.credits_json END,
        same_work_score=MAX(versions.same_work_score,excluded.same_work_score),
        version_type_score=MAX(versions.version_type_score,excluded.version_type_score),
        updated_at=CURRENT_TIMESTAMP
    `).bind(
      proposedVersionId,
      workId,
      versionStorageKey(candidate),
      candidateTitle,
      candidateArtist,
      category,
      language,
      validYear,
      album,
      JSON.stringify(credits),
      sameWorkScore,
      versionTypeScore,
    ).run();
    const stored = await db.prepare(
      'SELECT id FROM versions WHERE work_id=?1 AND version_key=?2 LIMIT 1',
    ).bind(workId, versionStorageKey(candidate)).first();
    versionId = String(stored?.id || '').trim();
  }
  if (!versionId) return { httpStatus: 500, body: { errore: 'versione non salvabile' } };

  const userVerified = status === 'APPROVED' ? 1 : 0;
  const userRejected = status === 'REJECTED' ? 1 : 0;
  await db.prepare(`
    UPDATE versions
    SET decision_status=?1,user_verified=?2,user_rejected=?3,
        same_work_score=COALESCE(?4,same_work_score),
        version_type_score=COALESCE(?5,version_type_score),
        updated_at=CURRENT_TIMESTAMP
    WHERE id=?6 AND work_id=?7
  `).bind(
    status,
    userVerified,
    userRejected,
    numericScore(input?.sameWorkScore),
    numericScore(input?.versionTypeScore),
    versionId,
    workId,
  ).run();

  const evidence = Array.isArray(input?.evidence) ? input.evidence : [];
  await db.prepare(`
    INSERT INTO decision_history(
      version_id,work_id,decision_status,same_work_score,version_type_score,decided_by,reason,evidence_snapshot_json
    ) VALUES(?1,?2,?3,?4,?5,'user',?6,?7)
  `).bind(
    versionId,
    workId,
    status,
    input?.sameWorkScore ?? null,
    input?.versionTypeScore ?? null,
    String(input?.reason || 'manual_android_lab38b'),
    JSON.stringify(evidence),
  ).run();

  for (const signal of evidence) {
    const source = String(signal?.source || signal?.kind || 'manual').trim() || 'manual';
    const kind = String(signal?.kind || 'provider_evidence').trim() || 'provider_evidence';
    await db.prepare(`
      INSERT INTO version_evidence(
        version_id,work_id,source,signal_kind,strength,direction,source_url,note,payload_json
      ) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)
    `).bind(
      versionId,
      workId,
      source,
      kind,
      normalizeSignalStrength(signal?.strength || 'medium'),
      normalizeSignalDirection(signal?.direction || 'positive'),
      String(signal?.sourceUrl || '').trim() || null,
      String(signal?.note || '').trim() || null,
      JSON.stringify(signal),
    ).run();
  }

  return { httpStatus: 200, body: { stato: 'salvato', status, workId, versionId } };
}

async function archiveSearch(request, env) {
  if (!env.DB) return json({ stato: 'pronto', query: '', items: [] });
  const input = await request.json().catch(() => ({}));
  const query = String(input?.query || '').trim();
  const requestedLimit = Number(input?.limit);
  const limit = Number.isFinite(requestedLimit)
    ? Math.max(1, Math.min(Math.trunc(requestedLimit), 100))
    : 60;
  const like = `%${query}%`;
  const rows = await env.DB.prepare(`
    SELECT
      v.canonical_title AS title,
      v.canonical_artist AS artist,
      v.category AS category,
      v.language AS language,
      v.year AS year,
      v.album AS album,
      v.decision_status AS decision_status,
      v.same_work_score AS same_work_score,
      v.version_type_score AS version_type_score,
      w.canonical_title AS work_title,
      w.original_artist AS original_artist
    FROM versions v
    JOIN works w ON w.id=v.work_id
    WHERE v.user_rejected=0
      AND (v.user_verified=1 OR v.decision_status='APPROVED')
      AND (
        ?1='' OR
        v.canonical_title LIKE ?2 COLLATE NOCASE OR
        v.canonical_artist LIKE ?2 COLLATE NOCASE OR
        w.canonical_title LIKE ?2 COLLATE NOCASE OR
        w.original_artist LIKE ?2 COLLATE NOCASE
      )
    ORDER BY v.updated_at DESC, v.canonical_artist ASC
    LIMIT ?3
  `).bind(query, like, limit).all();

  return json({
    stato: 'pronto',
    query,
    items: (rows?.results || []).map(row => ({
      title: row.title,
      artist: row.artist,
      category: row.category,
      language: row.language,
      year: row.year,
      album: row.album,
      decisionStatus: row.decision_status,
      sameWorkScore: numericScore(row.same_work_score),
      versionTypeScore: numericScore(row.version_type_score),
      workTitle: row.work_title,
      originalArtist: row.original_artist,
    })),
  });
}

async function saveDecision(request, env) {
  const result = await saveBrainDecision(env.DB, await request.json());
  return json(result.body, result.httpStatus);
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
    if (request.method === 'POST' && url.pathname === '/api/v1/archive/search') return archiveSearch(request, env);
    if (request.method === 'POST' && url.pathname === '/api/v1/discover/initial') return forwardDiscovery(request, env, ctx, 'initial');
    if (request.method === 'POST' && url.pathname === '/api/v1/discover/expand') return forwardDiscovery(request, env, ctx, 'expand');
    return legacyWorker.fetch(request, env, ctx);
  },
};
