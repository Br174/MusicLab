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


# Worker: modello primario attuale + alias stabile, con failover automatico.
path = 'cloudflare/music-intelligence/src/index.js'
text = read(path)

new_ask = r'''let preferredGeminiModel = null;

function geminiModelCandidates(env) {
  const configured = clean(env.GEMINI_MODEL);
  return [
    preferredGeminiModel,
    configured,
    'gemini-3.5-flash-lite',
    'gemini-flash-lite-latest',
  ].filter((value, index, array) => value && array.indexOf(value) === index);
}

async function askGemini(prompt, env, maxOutputTokens, useSearch) {
  if (!env.GEMINI_API_KEY) throw new Error('GEMINI_API_KEY non configurata nel Worker.');

  const baseBody = {
    contents: [{ role: 'user', parts: [{ text: prompt }] }],
    generationConfig: { temperature: 0.12, maxOutputTokens, responseMimeType: 'application/json' },
  };

  const failures = [];
  for (const model of geminiModelCandidates(env)) {
    const attempts = useSearch ? [true, false] : [false];
    for (const searchEnabled of attempts) {
      const body = JSON.parse(JSON.stringify(baseBody));
      if (searchEnabled) body.tools = [{ google_search: {} }];

      let response;
      try {
        response = await fetch(
          `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
          {
            method: 'POST',
            headers: { 'content-type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
            body: JSON.stringify(body),
          },
        );
      } catch (error) {
        failures.push(`${model}:${searchEnabled ? 'search' : 'plain'}:network`);
        continue;
      }

      if (!response.ok) {
        const detail = await response.text().catch(() => '');
        failures.push(`${model}:${searchEnabled ? 'search' : 'plain'}:${response.status}`);

        // Se il grounding non è supportato dal modello, prova subito lo stesso
        // modello senza Search. Per modello ritirato, sovraccarico o errore
        // temporaneo passa al candidato successivo invece di fermare MusicLab.
        if (searchEnabled) continue;
        if ([400, 404, 408, 429, 500, 502, 503, 504].includes(response.status)) break;
        throw new Error(`Gemini HTTP ${response.status}: ${detail.slice(0, 240)}`);
      }

      const root = await response.json();
      const output = root?.candidates?.[0]?.content?.parts?.map(p => p.text || '').join('\n').trim();
      if (!output) {
        failures.push(`${model}:${searchEnabled ? 'search' : 'plain'}:empty`);
        continue;
      }

      preferredGeminiModel = model;
      return parseJsonObject(output);
    }
  }

  throw new Error(`Gemini non disponibile dopo failover (${failures.join(', ')})`);
}'''
text = replace_section(
    text,
    'async function askGemini(prompt, env, maxOutputTokens, useSearch) {',
    'function normalizeMetadata(ai, playbackId) {',
    new_ask,
    'Worker askGemini',
)
write(path, text)

# Config: modello principale confermato dal probe reale.
path = 'cloudflare/music-intelligence/wrangler.jsonc'
text = read(path)
text = replace_once(text, '"ENGINE_VERSION": "1.1.0"', '"ENGINE_VERSION": "1.1.1"', 'ENGINE_VERSION')
text = replace_once(text, '"GEMINI_MODEL": "gemini-2.5-flash-lite"', '"GEMINI_MODEL": "gemini-3.5-flash-lite"', 'GEMINI_MODEL')
write(path, text)

# Fallback Android del resolver globale: non deve restare legato al modello ritirato.
path = 'app/src/main/kotlin/com/metrolist/music/intelligence/MusicIntelligence.kt'
text = read(path)
text = replace_once(
    text,
    'val models = listOfNotNull(configuredModel, "gemini-2.5-flash-lite").distinct()',
    'val models = listOfNotNull(configuredModel, "gemini-3.5-flash-lite", "gemini-flash-lite-latest").distinct()',
    'Android resolver models',
)
write(path, text)

print('LAB12 model failover patch applicata')
