const SCORE_WEIGHTS = Object.freeze({
  very_strong: 45,
  strong: 35,
  medium: 20,
  weak: 8,
  none: 0,
});

const HARD_NEGATIVES = new Set(['different_work_confirmed', 'junk_confirmed', 'duplicate_confirmed']);

export function normalizeName(value) {
  return String(value || '').trim().toLocaleLowerCase('it-IT').replace(/\s+/g, ' ');
}

export function statusForScore(score) {
  const value = clampScore(score);
  if (value >= 85) return 'APPROVED';
  if (value >= 50) return 'PROBABLE';
  if (value >= 15) return 'UNCERTAIN';
  return 'REJECTED';
}

export function thresholdForCoverageMode(mode) {
  const thresholds = { precisa: 85, selezionata: 70, ampia: 50, esplora: 30, tutto: 15 };
  return thresholds[String(mode || '').toLowerCase()] ?? thresholds.ampia;
}

export function evaluateEvidence({ positives = [], negatives = [] } = {}) {
  let score = 15;
  for (const evidence of positives) score += SCORE_WEIGHTS[evidence?.strength] ?? 0;

  let hardNegative = false;
  for (const evidence of negatives) {
    if (!evidence) continue;
    // Missing/unknown source data is neutral by design; absence is not a veto.
    if (String(evidence.kind || '').endsWith('_missing') || evidence.strength === 'none') continue;
    if (HARD_NEGATIVES.has(evidence.kind)) hardNegative = true;
    score -= SCORE_WEIGHTS[evidence.strength] ?? 0;
  }

  score = clampScore(score);
  return {
    sameWorkScore: score,
    status: hardNegative ? 'REJECTED' : statusForScore(score),
    hardNegative,
  };
}

export function admitCoverCandidate({ originalArtist, candidateArtist, category = 'cover', signals = [] } = {}) {
  if (!normalizeName(originalArtist) || !normalizeName(candidateArtist)) {
    return { admitted: false, reason: 'missing_performer' };
  }
  if (normalizeName(originalArtist) === normalizeName(candidateArtist)) {
    return { admitted: false, reason: 'same_performer' };
  }

  const coherent = signals.filter(signal => ['very_strong', 'strong', 'medium', 'weak'].includes(signal?.strength));
  const mediumOrStrong = coherent.filter(signal => ['very_strong', 'strong', 'medium'].includes(signal.strength));
  const normalizedCategory = normalizeName(category);
  const isForeign = ['straniera', 'foreign', 'adaptation', 'adattamento'].includes(normalizedCategory);

  if (isForeign) {
    const independent = mediumOrStrong.filter(signal => signalFamily(signal.kind) !== 'title');
    const admitted = independent.length >= 1;
    return {
      admitted,
      reason: admitted ? 'foreign_one_strong_evidence' : 'foreign_insufficient_evidence',
    };
  }

  const hasTitle = mediumOrStrong.some(signal => signalFamily(signal.kind) === 'title');
  const hasIndependentEvidence = mediumOrStrong.some(signal => signalFamily(signal.kind) !== 'title');
  const admitted = hasTitle && hasIndependentEvidence;

  return {
    admitted,
    reason: admitted ? 'title_plus_same_work_evidence' : 'insufficient_evidence',
  };
}

function signalFamily(kind = '') {
  if (kind.includes('title') || kind.includes('alias')) return 'title';
  if (kind.includes('composer') || kind.includes('writer') || kind.includes('lyricist') || kind.includes('ipi')) return 'credit';
  if (kind.includes('iswc') || kind.includes('musicbrainz') || kind.includes('work')) return 'work_id';
  if (kind.includes('album') || kind.includes('release') || kind.includes('label')) return 'release';
  return kind || 'other';
}

export function buildDualLanguageQueries({ title, artist, writers = [], composers = [] } = {}) {
  const safeTitle = String(title || '').trim();
  if (!safeTitle) return [];
  const quoted = `"${safeTitle}"`;
  const safeArtist = String(artist || '').trim();
  const creditNames = [...writers, ...composers].map(v => String(v || '').trim()).filter(Boolean).slice(0, 4);
  const queries = [
    `${quoted} cover`,
    `${quoted} versione`,
    `${quoted} reinterpretazione`,
    `${quoted} inciso da`,
    `${quoted} cover version`,
    `${quoted} recorded by`,
    `${quoted} rendition`,
    `${quoted} adaptation`,
  ];
  if (safeArtist) {
    queries.push(`${quoted} ${safeArtist} cover versions`);
    queries.push(`${quoted} ${safeArtist} adattamenti`);
  }
  for (const credit of creditNames) {
    queries.push(`${quoted} "${credit}" cover`);
    queries.push(`${quoted} "${credit}" adaptation`);
  }
  return [...new Set(queries)];
}

export function buildLanguageMissions({ title, artist, targetLanguages = [], writers = [], composers = [] } = {}) {
  const languages = [...new Set(targetLanguages.map(v => String(v || '').trim()).filter(Boolean))];
  return languages.map(language => ({
    id: `language:${language.toLowerCase()}`,
    family: 'language_adaptation',
    language,
    title: String(title || '').trim(),
    originalArtist: String(artist || '').trim(),
    credits: [...writers, ...composers].map(v => String(v || '').trim()).filter(Boolean),
    // Literal translation is deliberately late/auxiliary, never the only strategy.
    strategies: ['known_adaptation', 'related_work', 'writer_credit', 'composer_credit', 'alternate_title', 'local_language_terms', 'literal_translation'],
  }));
}

export function selectCoverageGaps({ desired = [], completed = [], inFlight = [] } = {}) {
  const unavailable = new Set([...completed, ...inFlight].map(v => String(v)));
  return desired.filter(item => !unavailable.has(String(item)));
}

export function filterByCoverageMode(candidates = [], mode = 'ampia') {
  const threshold = thresholdForCoverageMode(mode);
  return candidates.filter(candidate => {
    if (candidate?.junk === true || candidate?.duplicateConfirmed === true) return false;
    const score = Math.min(
      clampScore(candidate?.sameWorkScore ?? candidate?.same_work_score ?? 0),
      clampScore(candidate?.versionTypeScore ?? candidate?.version_type_score ?? 0),
    );
    return score >= threshold;
  });
}

function clampScore(score) {
  const value = Number(score);
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, Math.round(value)));
}
