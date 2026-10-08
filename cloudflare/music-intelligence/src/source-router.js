const DEFAULT_SOURCES = Object.freeze(['d1', 'musicbrainz', 'wikidata']);
const MUSICBRAINZ_USER_AGENT = 'MusicLab/20 (https://github.com/Br174/MusicLab)';
const SOURCE_TIMEOUT_MS = 4500;

export function buildSourcePlan(options = {}) {
  const plan = [...DEFAULT_SOURCES];
  if (options.discogs === true) plan.push('discogs');
  if (options.lastfm === true) plan.push('lastfm');
  if (options.coverInfo === true) plan.push('coverinfo');
  if (options.secondHandSongs === true) plan.push('secondhandsongs');
  if (options.webGap === true) plan.push('web_gap');
  if (options.youtubeFallback === true) plan.push('youtube_ytm');
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

/**
 * Runtime evidence collection for the NORMAL/DEEP Brain lanes.
 * D1 is consumed by the worker before this point; here we query only providers
 * that can add independent evidence. Provider failures are deliberately neutral.
 */
export async function collectSourceEvidence({
  title,
  artist,
  env = {},
  fetchImpl = globalThis.fetch,
  options = {},
} = {}) {
  const canonicalTitle = clean(title);
  const canonicalArtist = clean(artist);
  if (!canonicalTitle || typeof fetchImpl !== 'function') return [];

  const tasks = [
    queryMusicBrainz(canonicalTitle, canonicalArtist, fetchImpl),
    options?.disableWikidata === true
      ? Promise.resolve(unavailable('wikidata', 'Disattivato in LAB60'))
      : queryWikidata(canonicalTitle, canonicalArtist, fetchImpl),
  ];
  const [musicbrainz, wikidata] = await Promise.all(tasks);

  const results = [musicbrainz, wikidata];
  results.push(env?.DISCOGS_TOKEN
    ? await queryDiscogs(canonicalTitle, canonicalArtist, env.DISCOGS_TOKEN, fetchImpl)
    : unavailable('discogs', 'DISCOGS_TOKEN non configurato'));
  results.push(options?.disableLastFm === true
    ? unavailable('lastfm', 'Disattivato in LAB60')
    : env?.LASTFM_API_KEY
      ? await queryLastFm(canonicalTitle, canonicalArtist, env.LASTFM_API_KEY, fetchImpl)
      : unavailable('lastfm', 'LASTFM_API_KEY non configurata'));

  // COVER.INFO is intentionally best-effort. There is no hard-coded scraper:
  // an operator may provide a stable JSON endpoint, otherwise the lane remains neutral.
  results.push(env?.COVER_INFO_API_URL
    ? await queryConfiguredJsonSource('coverinfo', env.COVER_INFO_API_URL, canonicalTitle, canonicalArtist, fetchImpl)
    : unavailable('coverinfo', 'endpoint API stabile non configurato'));

  // SecondHandSongs automated access is enabled only when an explicitly
  // authorized endpoint/key is configured. We never scrape the website.
  results.push(env?.SECONDHANDSONGS_API_URL && env?.SECONDHANDSONGS_API_KEY
    ? await queryConfiguredJsonSource(
      'secondhandsongs',
      env.SECONDHANDSONGS_API_URL,
      canonicalTitle,
      canonicalArtist,
      fetchImpl,
      { Authorization: `Bearer ${env.SECONDHANDSONGS_API_KEY}` },
    )
    : unavailable('secondhandsongs', 'API autorizzata non configurata'));

  if (options?.deep === true) {
    results.push(env?.TAVILY_API_KEY
      ? await queryTavily(canonicalTitle, canonicalArtist, env.TAVILY_API_KEY, fetchImpl)
      : unavailable('web_gap', 'TAVILY_API_KEY non configurata'));
  }

  return results;
}

export function formatSourceEvidenceForBrain(results = []) {
  const rows = Array.isArray(results) ? results : [];
  if (rows.length === 0) return '';
  const labels = {
    musicbrainz: 'MusicBrainz',
    wikidata: 'Wikidata',
    discogs: 'Discogs',
    lastfm: 'Last.fm',
    coverinfo: 'COVER.INFO',
    secondhandsongs: 'SecondHandSongs',
    web_gap: 'Web/Tavily',
    youtube_ytm: 'YouTube/YTM',
  };
  const lines = rows.map(result => {
    const source = labels[result?.source] || clean(result?.source) || 'Fonte';
    if (result?.status !== 'ok') {
      return `- ${source}: ${result?.status || 'unavailable'}; assenza/errore neutrale, nessun veto.`;
    }
    const candidates = (Array.isArray(result?.candidates) ? result.candidates : [])
      .slice(0, 8)
      .map(candidate => {
        const title = clean(candidate?.title || candidate?.name || candidate?.label);
        const artist = clean(candidate?.artist);
        const id = clean(candidate?.id);
        return [title, artist ? `— ${artist}` : '', id ? `[${id}]` : ''].filter(Boolean).join(' ');
      })
      .filter(Boolean);
    return `- ${source}: ${candidates.length ? candidates.join(' | ') : 'nessun candidato'}; evidenza, non veto.`;
  });
  return [
    'EVIDENZE DA FONTI STRUTTURATE (non sono autorità di veto):',
    ...lines,
    'Una fonte assente o senza risultati resta neutrale: non rifiutare un candidato solo per mancata conferma.',
  ].join('\n');
}

async function queryMusicBrainz(title, artist, fetchImpl) {
  const query = `work:${quoteQuery(title)}${artist ? ` AND artist:${quoteQuery(artist)}` : ''}`;
  const url = `https://musicbrainz.org/ws/2/work/?query=${encodeURIComponent(query)}&fmt=json&limit=8`;
  return fetchJsonSource('musicbrainz', url, fetchImpl, {
    Accept: 'application/json',
    'User-Agent': MUSICBRAINZ_USER_AGENT,
  }, root => (Array.isArray(root?.works) ? root.works : []).map(work => ({
    source: 'musicbrainz',
    id: clean(work?.id),
    title: clean(work?.title),
    score: Number(work?.score) || null,
    language: clean(work?.language) || null,
    iswcs: Array.isArray(work?.iswcs) ? work.iswcs.map(clean).filter(Boolean) : [],
  })).filter(candidate => candidate.id || candidate.title));
}

async function queryWikidata(title, artist, fetchImpl) {
  const escapedTitle = sparqlString(title);
  const escapedArtist = sparqlString(artist);
  const artistClause = artist
    ? `OPTIONAL { ?item ?artistProp ?artistEntity . VALUES ?artistProp { wdt:P175 wdt:P86 wdt:P676 } ?artistEntity rdfs:label ?artistLabel . FILTER(LANG(?artistLabel) IN ("en","it")) }\nFILTER(!BOUND(?artistLabel) || CONTAINS(LCASE(STR(?artistLabel)), LCASE("${escapedArtist}")))`
    : '';
  const sparql = `SELECT DISTINCT ?item ?itemLabel WHERE {\n?item rdfs:label ?label .\nFILTER(LANG(?label) IN ("en","it"))\nFILTER(LCASE(STR(?label)) = LCASE("${escapedTitle}"))\n${artistClause}\nSERVICE wikibase:label { bd:serviceParam wikibase:language "it,en". }\n} LIMIT 8`;
  const url = `https://query.wikidata.org/sparql?query=${encodeURIComponent(sparql)}&format=json`;
  return fetchJsonSource('wikidata', url, fetchImpl, {
    Accept: 'application/sparql-results+json, application/json',
    'User-Agent': MUSICBRAINZ_USER_AGENT,
  }, root => (root?.results?.bindings || []).map(binding => ({
    source: 'wikidata',
    id: wikidataId(binding?.item?.value),
    title: clean(binding?.itemLabel?.value || title),
  })).filter(candidate => candidate.id || candidate.title));
}

async function queryDiscogs(title, artist, token, fetchImpl) {
  const query = [title, artist].filter(Boolean).join(' ');
  const url = `https://api.discogs.com/database/search?q=${encodeURIComponent(query)}&type=release&per_page=8`;
  return fetchJsonSource('discogs', url, fetchImpl, {
    Accept: 'application/json',
    Authorization: `Discogs token=${token}`,
    'User-Agent': MUSICBRAINZ_USER_AGENT,
  }, root => (Array.isArray(root?.results) ? root.results : []).map(item => ({
    source: 'discogs',
    id: String(item?.id || ''),
    title: clean(item?.title),
    year: validYear(item?.year),
  })).filter(candidate => candidate.id || candidate.title));
}

async function queryLastFm(title, artist, apiKey, fetchImpl) {
  const url = `https://ws.audioscrobbler.com/2.0/?method=track.getInfo&api_key=${encodeURIComponent(apiKey)}&artist=${encodeURIComponent(artist)}&track=${encodeURIComponent(title)}&format=json&autocorrect=1`;
  return fetchJsonSource('lastfm', url, fetchImpl, { Accept: 'application/json' }, root => {
    const track = root?.track;
    if (!track) return [];
    return [{
      source: 'lastfm',
      id: clean(track?.mbid),
      title: clean(track?.name || title),
      artist: clean(track?.artist?.name || artist),
      url: clean(track?.url) || null,
    }];
  });
}

async function queryConfiguredJsonSource(source, endpoint, title, artist, fetchImpl, headers = {}) {
  let url;
  try {
    url = new URL(endpoint);
    url.searchParams.set('title', title);
    if (artist) url.searchParams.set('artist', artist);
  } catch {
    return unavailable(source, 'endpoint non valido');
  }
  return fetchJsonSource(source, url.toString(), fetchImpl, { Accept: 'application/json', ...headers }, root => {
    const values = Array.isArray(root) ? root : (root?.results || root?.candidates || root?.items || []);
    return (Array.isArray(values) ? values : []).slice(0, 12).map(item => ({
      source,
      id: clean(item?.id || item?.workId || item?.url),
      title: clean(item?.title || item?.name),
      artist: clean(item?.artist || item?.performer),
      url: clean(item?.url) || null,
    })).filter(candidate => candidate.id || candidate.title);
  });
}

async function queryTavily(title, artist, apiKey, fetchImpl) {
  const url = 'https://api.tavily.com/search';
  const body = JSON.stringify({
    api_key: apiKey,
    query: `"${title}" "${artist}" cover version adaptation recorded by`,
    search_depth: 'basic',
    max_results: 8,
    include_answer: false,
  });
  return fetchJsonSource('web_gap', url, fetchImpl, {
    Accept: 'application/json',
    'Content-Type': 'application/json',
  }, root => (Array.isArray(root?.results) ? root.results : []).map(item => ({
    source: 'web_gap',
    id: clean(item?.url),
    title: clean(item?.title),
    url: clean(item?.url) || null,
  })).filter(candidate => candidate.id || candidate.title), { method: 'POST', body });
}

async function fetchJsonSource(source, url, fetchImpl, headers, mapCandidates, init = {}) {
  const controller = typeof AbortController === 'function' ? new AbortController() : null;
  const timer = controller ? setTimeout(() => controller.abort(), SOURCE_TIMEOUT_MS) : null;
  try {
    const response = await fetchImpl(url, {
      method: init.method || 'GET',
      headers,
      body: init.body,
      signal: controller?.signal,
    });
    if (!response?.ok) return unavailable(source, `HTTP ${response?.status || 'error'}`);
    const root = await response.json();
    const candidates = mapCandidates(root);
    return normalizeSourceResult(source, {
      status: candidates.length ? 'ok' : 'no_match',
      candidates,
    });
  } catch (error) {
    return unavailable(source, clean(error?.name === 'AbortError' ? 'timeout' : error?.message || error));
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function unavailable(source, error) {
  return normalizeSourceResult(source, { status: 'unavailable', error });
}

function quoteQuery(value) {
  return `"${clean(value).replaceAll('"', '\\"')}"`;
}

function sparqlString(value) {
  return clean(value).replaceAll('\\', '\\\\').replaceAll('"', '\\"');
}

function wikidataId(value) {
  const match = clean(value).match(/\/entity\/(Q\d+)$/i);
  return match?.[1] || clean(value);
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
