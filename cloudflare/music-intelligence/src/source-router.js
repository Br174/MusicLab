const DEFAULT_SOURCES = Object.freeze(['d1', 'musicbrainz', 'wikidata']);

export function buildSourcePlan(options = {}) {
  const plan = [...DEFAULT_SOURCES];
  if (options.discogs === true) plan.push('discogs');
  if (options.lastfm === true) plan.push('lastfm');
  if (options.secondHandSongs === true) plan.push('secondhandsongs');
  if (options.webGap === true) plan.push('web_gap');
  return plan;
}

export function buildWorkSignature(input = {}) {
  return {
    canonicalTitle: clean(input.title),
    originalArtist: clean(input.artist),
    aliases: uniqueDisplay(input.aliases),
    translatedTitles: uniqueDisplay(input.translatedTitles),
    writers: uniqueDisplay(input.writers),
    composers: uniqueDisplay(input.composers),
    lyricists: uniqueDisplay(input.lyricists),
    publishers: uniqueDisplay(input.publishers),
    iswc: clean(input.iswc) || null,
    musicbrainzWorkId: clean(input.musicbrainzWorkId) || null,
    year: validYear(input.year),
    language: clean(input.language) || null,
  };
}

export function normalizeSourceResult(source, result = {}) {
  const normalizedSource = clean(source).toLowerCase() || 'unknown';
  const status = normalizeStatus(result.status);
  const candidates = Array.isArray(result.candidates) ? result.candidates.filter(Boolean) : [];

  // A source that is unavailable, returns no match, or simply has no row is
  // evidence of absence only. It is never evidence that the musical work is
  // different. Only an explicit, concrete contradiction may become negative
  // evidence elsewhere in the Brain.
  const neutral = status !== 'ok' || candidates.length === 0;
  return {
    source: normalizedSource,
    status,
    neutral,
    candidates,
    positiveEvidence: Array.isArray(result.positiveEvidence) ? result.positiveEvidence.filter(Boolean) : [],
    negativeEvidence: explicitNegativeEvidence(result.negativeEvidence),
    error: clean(result.error) || null,
  };
}

export function evidenceSignalsForCandidate(candidate = {}) {
  const source = clean(candidate.source).toLowerCase() || 'unknown';
  const signals = [];
  const add = (condition, kind, strength) => {
    if (condition === true) signals.push({ kind, strength, source });
  };

  add(candidate.workIdMatches, 'musicbrainz_work_match', 'very_strong');
  add(candidate.iswcMatches, 'iswc_match', 'very_strong');
  add(candidate.writerMatches, 'writer_match', 'strong');
  add(candidate.composerMatches, 'composer_match', 'strong');
  add(candidate.lyricistMatches, 'lyricist_match', 'strong');
  add(candidate.aliasMatches, 'alias_match', 'medium');
  add(candidate.translatedTitleMatches, 'translated_title_match', 'medium');
  add(candidate.releaseMatches, 'release_match', 'medium');
  return signals;
}

function explicitNegativeEvidence(value) {
  if (!Array.isArray(value)) return [];
  return value.filter(item => {
    if (!item || typeof item !== 'object') return false;
    // Never synthesize a contradiction from a missing source. The caller must
    // explicitly provide a confirmed contradiction kind.
    return ['different_work_confirmed', 'junk_confirmed', 'duplicate_confirmed'].includes(String(item.kind || ''));
  });
}

function normalizeStatus(value) {
  const status = clean(value).toLowerCase();
  if (status === 'ok') return 'ok';
  if (['no_match', 'nomatch', 'empty'].includes(status)) return 'no_match';
  if (['unavailable', 'network_error', 'timeout', 'error'].includes(status)) return 'unavailable';
  return 'unavailable';
}

function uniqueDisplay(values) {
  const seen = new Set();
  const output = [];
  for (const raw of Array.isArray(values) ? values : []) {
    const value = clean(raw);
    if (!value) continue;
    const key = canonical(value);
    if (seen.has(key)) continue;
    seen.add(key);
    output.push(value);
  }
  return output;
}

function validYear(value) {
  const year = Number(value);
  if (!Number.isInteger(year) || year < 1800 || year > 2100) return null;
  return year;
}

function clean(value) {
  return String(value ?? '').trim().replace(/\s+/g, ' ');
}

function canonical(value) {
  return clean(value)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLocaleLowerCase('it-IT');
}
