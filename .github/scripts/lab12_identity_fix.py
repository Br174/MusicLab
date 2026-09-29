from pathlib import Path


def read(path):
    return Path(path).read_text(encoding='utf-8')


def write(path, text):
    Path(path).write_text(text, encoding='utf-8')


def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise RuntimeError(f'{label}: attesa 1 occorrenza, trovate {n}')
    return text.replace(old, new, 1)


def replace_section(text, start, end, replacement, label):
    i = text.find(start)
    if i < 0:
        raise RuntimeError(f'{label}: inizio non trovato')
    j = text.find(end, i)
    if j < 0:
        raise RuntimeError(f'{label}: fine non trovata')
    return text[:i] + replacement.rstrip() + '\n\n' + text[j:]


worker_path = 'cloudflare/music-intelligence/src/index.js'
worker = read(worker_path)

resolve = r'''async function resolveMetadata(input, env, ctx) {
  const started = Date.now();
  const title = clean(input?.title);
  const artist = clean(input?.artist);
  const playbackId = clean(input?.playbackId);
  if (!title || !artist) throw new Error('Titolo e artista sono obbligatori.');
  const key = searchKey(title, artist);

  if (input?.useMemory !== false && env.DB) {
    const cached = await cachedResolution(env.DB, key, playbackId);
    if (cached) {
      ctx?.waitUntil(logRequest(env.DB, key, 'resolve', true, 1, Date.now() - started, env));
      return { stato: 'pronto', provenienza: 'memoria', metadata: cached };
    }
  }

  const titleSegments = extractTitleSegments(title);
  const segmentsText = titleSegments.length > 1
    ? titleSegments.map((value, index) => `${index + 1}. ${value}`).join('\n')
    : '(titolo non segmentabile con sicurezza)';

  const prompt = `Sei il resolver musicale canonico di MusicLab. L'AI è l'unica autorità editoriale. ` +
    `YouTube/YouTube Music sono solo il mezzo tecnico di riproduzione: i loro campi NON sono metadati affidabili.\n\n` +
    `Devi identificare la SPECIFICA REGISTRAZIONE riprodotta, non necessariamente l'originale della composizione.\n` +
    `Titolo osservato del video: ${title}\n` +
    `Nome osservato del canale/uploader: ${artist}\n` +
    (clean(input?.albumHint) ? `Album osservato (solo indizio, non autorità): ${clean(input.albumHint)}\n` : '') +
    `Segmenti ricavati dal titolo:\n${segmentsText}\n\n` +
    `Decidi con la tua conoscenza musicale quale nome è l'interprete reale della registrazione. ` +
    `Il nome del canale va considerato uploader finché non hai una ragione musicale indipendente per identificarlo come performer. ` +
    `Se il titolo contiene un artista musicale riconoscibile separato dal titolo della canzone, è un forte indizio ma la decisione finale resta tua. ` +
    `Non confondere l'interprete di questa registrazione con l'autore o l'interprete originale della composizione.\n\n` +
    `Restituisci titolo canonico, artista reale della registrazione, album se realmente noto, anno, lingua, categoria ` +
    `(originale|cover|live|remix|adattamento), crediti, confidence da 0 a 1 e ruolo del nome osservato ` +
    `(performer|uploader|channel|label|unknown). Non inventare.\n` +
    `JSON: {"title":"","artist":"","album":null,"year":null,"language":null,"category":null,` +
    `"confidence":0.0,"observedArtistRole":"performer|uploader|channel|label|unknown",` +
    `"credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}}`;

  let ai = await askGemini(prompt, env, 1800, false);
  let metadata = normalizeMetadata(ai, playbackId);

  if (needsCanonicalArbitration(titleSegments, artist, ai, metadata)) {
    const firstArtist = clean(metadata?.artist) || '(nessuno)';
    const arbitrationPrompt = `Sei l'arbitro canonico finale di MusicLab. Devi risolvere una contraddizione senza usare il nome del canale come autorità editoriale.\n\n` +
      `Titolo completo: ${title}\nCanale/uploader osservato: ${artist}\nSegmenti del titolo:\n${segmentsText}\n` +
      `Prima decisione AI: ${firstArtist}\n\n` +
      `Identifica con la tua conoscenza musicale quale segmento rappresenta la canzone e quale rappresenta l'artista, se presente. ` +
      `Quando il canale non compare nel titolo e il titolo contiene il nome riconoscibile di un artista, non assumere che il canale sia il performer. ` +
      `Scegli l'interprete della SPECIFICA registrazione; non sostituirlo con l'originale della composizione. ` +
      `La decisione resta interamente dell'AI.\n\n` +
      `Restituisci SOLO JSON completo: {"title":"","artist":"","album":null,"year":null,"language":null,"category":null,` +
      `"confidence":0.0,"observedArtistRole":"performer|uploader|channel|label|unknown",` +
      `"credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}}`;

    const adjudicatedAi = await askGemini(arbitrationPrompt, env, 1800, false);
    const adjudicatedMetadata = normalizeMetadata(adjudicatedAi, playbackId);
    if (adjudicatedMetadata && !needsCanonicalArbitration(titleSegments, artist, adjudicatedAi, adjudicatedMetadata)) {
      ai = adjudicatedAi;
      metadata = adjudicatedMetadata;
    }
  }

  if (!metadata || needsCanonicalArbitration(titleSegments, artist, ai, metadata)) {
    throw new Error('L’AI non ha restituito un’identità canonica coerente con gli indizi musicali.');
  }

  if (env.DB) {
    const workId = crypto.randomUUID();
    await env.DB.prepare(`
      INSERT INTO works(id, search_key, canonical_title, original_artist, original_year, original_language, credits_json, ai_model, resolver_version)
      VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
      ON CONFLICT(search_key) DO UPDATE SET
        canonical_title=excluded.canonical_title,
        original_artist=excluded.original_artist,
        original_year=COALESCE(excluded.original_year, works.original_year),
        original_language=COALESCE(excluded.original_language, works.original_language),
        credits_json=excluded.credits_json,
        ai_model=excluded.ai_model,
        resolver_version=excluded.resolver_version,
        updated_at=CURRENT_TIMESTAMP
    `).bind(
      workId, key, metadata.title, metadata.artist, metadata.year, metadata.language,
      JSON.stringify(metadata.credits || {}), preferredGeminiModel || env.GEMINI_MODEL || 'gemini-3.5-flash-lite', CURRENT_RESOLVER_VERSION
    ).run();

    const work = await env.DB.prepare(
      'SELECT id FROM works WHERE search_key=?1 AND resolver_version>=?2 LIMIT 1'
    ).bind(key, CURRENT_RESOLVER_VERSION).first();
    if (playbackId && work?.id) {
      await env.DB.prepare(`
        INSERT INTO playback_bindings(playback_id, work_id, source_kind, last_verified_at)
        VALUES(?1, ?2, 'youtube', CURRENT_TIMESTAMP)
        ON CONFLICT(playback_id) DO UPDATE SET work_id=excluded.work_id,last_verified_at=CURRENT_TIMESTAMP
      `).bind(playbackId, work.id).run();
    }
    ctx?.waitUntil(logRequest(env.DB, key, 'resolve', false, 1, Date.now() - started, env));
  }

  return { stato: 'pronto', provenienza: 'ai', metadata };
}'''

worker = replace_section(
    worker,
    'async function resolveMetadata(input, env, ctx) {',
    'async function cachedResolution(db, key, playbackId) {',
    resolve,
    'resolveMetadata v12.2',
)

# Rimpiazza i vecchi helper troppo aggressivi con arbitraggio basato sui segmenti.
helpers = r'''function extractTitleSegments(title) {
  const parts = String(title || '')
    .split(/\s+(?:-|–|—|\|)\s+/)
    .map(clean)
    .filter(Boolean)
    .slice(0, 5);
  return parts.length > 1 ? parts : [];
}

function segmentMatchesName(segment, name) {
  const a = canonical(segment);
  const b = canonical(name);
  if (!a || !b) return false;
  return a === b || a.startsWith(`${b} `) || b.startsWith(`${a} `);
}

function needsCanonicalArbitration(titleSegments, observedArtist, ai, metadata) {
  if (!metadata || !clean(metadata.title) || !clean(metadata.artist)) return true;

  const role = canonical(ai?.observedArtistRole || '');
  const resolvedArtist = canonical(metadata.artist);
  const observed = canonical(observedArtist);
  const channelDeclaredNonPerformer = role.includes('uploader') || role.includes('channel') || role.includes('label');
  if (channelDeclaredNonPerformer && resolvedArtist === observed) return true;

  if (titleSegments.length > 1) {
    const observedAppearsInTitle = titleSegments.some(segment => segmentMatchesName(segment, observedArtist));
    const resolvedAppearsInTitle = titleSegments.some(segment => segmentMatchesName(segment, metadata.artist));

    // Caso tipico del bug: il canale/uploader non compare nel titolo, mentre nel
    // titolo è presente un altro artista. Non accettiamo automaticamente il canale.
    if (resolvedArtist === observed && !observedAppearsInTitle) return true;

    // Se l'AI ha scelto un artista presente esplicitamente nel titolo e diverso dal
    // canale, l'indizio è coerente e non serve penalizzarlo per una confidence prudente.
    if (resolvedArtist !== observed && resolvedAppearsInTitle) return false;
  }

  const confidence = Number(ai?.confidence);
  if (Number.isFinite(confidence) && confidence < 0.45) return true;
  return false;
}
'''

start = 'function needsGroundedVerification(observedTitle, observedArtist, ai, metadata, requireConfidence) {'
end = 'function versionKey(title, artist, category, language) {'
worker = replace_section(worker, start, end, helpers, 'canonical helpers v12.2')
write(worker_path, worker)

# Versione Worker incrementata per rendere verificabile il deploy.
wrangler_path = 'cloudflare/music-intelligence/wrangler.jsonc'
wrangler = read(wrangler_path)
wrangler = replace_once(wrangler, '"ENGINE_VERSION": "1.1.1"', '"ENGINE_VERSION": "1.2.0"', 'engine 1.2.0')
write(wrangler_path, wrangler)

# Anche il fallback Android riceve la stessa indicazione: i segmenti del titolo sono
# indizi per l'AI, mai metadati imposti dall'app.
android_path = 'app/src/main/kotlin/com/metrolist/music/intelligence/MusicIntelligence.kt'
android = read(android_path)
old = '''        val prompt = """Sei il resolver musicale canonico di MusicLab.
L'AI è l'unica autorità editoriale. Il nome osservato può provenire da un uploader YouTube e NON va assunto automaticamente come artista reale.

Playback tecnico: $playbackId
Titolo osservato: $title
Artista osservato: $artist
Album osservato: ${album.orEmpty().ifBlank { "non disponibile" }}

Identifica la specifica registrazione musicale reale. Restituisci titolo canonico, artista reale, album reale se esiste, anno, lingua e categoria (originale, cover, live, remix o adattamento).
$creditsRule
Se un dato non è noto usa null o []. Non inventare una pagina YouTube né un browse id.
Rispondi SOLO JSON:
{"title":"","artist":"","album":null,"year":null,"language":null,"category":null,"credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}}
"""'''
new = '''        val titleSegments = title
            .split(Regex("\\s+(?:-|–|—|\\|)\\s+"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(5)
        val titleSegmentsText = titleSegments.takeIf { it.size > 1 }
            ?.joinToString(" | ")
            .orEmpty()
        val prompt = """Sei il resolver musicale canonico di MusicLab.
L'AI è l'unica autorità editoriale. YouTube/YouTube Music sono solo playback tecnico: il nome osservato può essere un uploader/canale e NON va assunto automaticamente come artista reale.

Playback tecnico: $playbackId
Titolo osservato: $title
Canale/artista osservato: $artist
Segmenti del titolo: ${titleSegmentsText.ifBlank { "non separabili con sicurezza" }}
Album osservato: ${album.orEmpty().ifBlank { "non disponibile" }}

Decidi con la tua conoscenza musicale l'interprete reale della specifica registrazione. Se il titolo contiene un artista riconoscibile separato dal titolo della canzone, consideralo un forte indizio; il canale osservato resta un uploader finché non hai una ragione musicale indipendente per identificarlo come performer. Non sostituire una cover/live con l'interprete originale della composizione.
Restituisci titolo canonico, artista reale, album reale se esiste, anno, lingua e categoria (originale, cover, live, remix o adattamento).
$creditsRule
Se un dato non è noto usa null o []. Non inventare una pagina YouTube né un browse id.
Rispondi SOLO JSON:
{"title":"","artist":"","album":null,"year":null,"language":null,"category":null,"credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}}
"""'''
android = replace_once(android, old, new, 'Android canonical prompt')
write(android_path, android)

print('LAB12 identity arbitration patch applicata')
