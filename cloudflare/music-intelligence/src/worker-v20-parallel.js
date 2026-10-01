import legacyWorker from './index.js';
import brainWorker, {
  buildBrainFocus,
  completeCoverageMissions,
  decorateDiscoveryPayload,
  failCoverageMissions,
  persistDiscoveryBrainMemory,
  planCoverageMissions,
} from './worker-v20.js';

const MAX_PARALLEL_LANES = 3;
const MISSIONS_PER_LANE = 2;

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

function identity(version) {
  return `${canonical(version?.title)}|${canonical(version?.artist)}`;
}

function versionKey(version) {
  return [
    canonical(version?.title),
    canonical(version?.artist),
    canonical(version?.category),
    canonical(version?.language),
  ].join('|');
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

async function readMemory(request, env, ctx, input) {
  const url = new URL('/api/v1/memory/discover', request.url);
  const memoryRequest = new Request(url, {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify({ ...input, limit: 150 }),
  });
  const response = await brainWorker.fetch(memoryRequest, env, ctx);
  if (!response.ok) return null;
  return response.json().catch(() => null);
}

function hasUnseenMemory(memory, input) {
  if (!Array.isArray(memory?.versions) || memory.versions.length === 0) return false;
  const seen = new Set((Array.isArray(input?.existing) ? input.existing : []).map(identity));
  return memory.versions.some(version => !seen.has(identity(version)));
}

function groupMissions(missions) {
  const groups = [];
  for (let index = 0; index < missions.length && groups.length < MAX_PARALLEL_LANES; index += MISSIONS_PER_LANE) {
    groups.push(missions.slice(index, index + MISSIONS_PER_LANE));
  }
  return groups.filter(group => group.length > 0);
}

function laneFocus(input, canonicalSeed, missions, laneIndex) {
  const missionText = missions
    .map(mission => mission?.queryText)
    .filter(Boolean)
    .join('\n');
  const focus = [
    String(input?.focus || '').trim(),
    `Corsia parallela ${laneIndex + 1}/${MAX_PARALLEL_LANES}. Lavora SOLO sulle missioni assegnate a questa corsia.`,
    missionText ? `Missioni assegnate:\n${missionText}` : '',
    'Non ripetere ricerche di altre corsie. Privilegia candidati nuovi e mantieni anche quelli plausibili ma incerti.',
  ].filter(Boolean).join('\n');

  return buildBrainFocus({
    title: canonicalSeed.title,
    artist: canonicalSeed.artist,
    mode: 'cover',
    focus,
  });
}

function laneRequest(request, input, canonicalSeed, missions, laneIndex, memory) {
  const known = [
    ...(Array.isArray(input?.existing) ? input.existing : []),
    ...(Array.isArray(memory?.versions) ? memory.versions : []),
  ];
  const dedupedKnown = [];
  const seen = new Set();
  for (const version of known) {
    const key = versionKey(version);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    dedupedKnown.push(version);
  }

  const body = {
    ...input,
    title: canonicalSeed.title,
    artist: canonicalSeed.artist,
    year: input?.year ?? canonicalSeed.year ?? null,
    language: input?.language ?? canonicalSeed.language ?? null,
    mode: 'cover',
    useMemory: true,
    existing: dedupedKnown.slice(0, 160),
    focus: laneFocus(input, canonicalSeed, missions, laneIndex),
  };

  return new Request(request.url, {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(body),
  });
}

async function executeLane(request, env, ctx, input, canonicalSeed, missions, laneIndex, memory) {
  const response = await legacyWorker.fetch(
    laneRequest(request, input, canonicalSeed, missions, laneIndex, memory),
    env,
    ctx,
  );
  if (!response.ok) {
    return { ok: false, response, missions, payload: null };
  }
  const payload = await response.json().catch(() => null);
  return { ok: Boolean(payload), response, missions, payload };
}

function mergePayloads(results, memory, canonicalSeed) {
  const versions = new Map();
  let original = null;

  for (const result of results) {
    if (!result?.ok || !result?.payload) continue;
    if (!original && result.payload.original) original = result.payload.original;
    for (const version of Array.isArray(result.payload.versions) ? result.payload.versions : []) {
      const key = versionKey(version);
      if (!key) continue;
      const current = versions.get(key);
      if (!current) {
        versions.set(key, version);
        continue;
      }
      versions.set(key, {
        ...current,
        ...version,
        year: current.year ?? version.year ?? null,
        album: current.album ?? version.album ?? null,
        credits: {
          ...(current.credits || {}),
          ...(version.credits || {}),
        },
      });
    }
  }

  return {
    stato: 'pronto',
    fase: 'expand',
    provenienza: 'ai-parallel',
    seed: { title: canonicalSeed.title, artist: canonicalSeed.artist },
    original: original || memory?.original || {
      title: canonicalSeed.title,
      artist: canonicalSeed.artist,
      year: canonicalSeed.year ?? null,
      language: canonicalSeed.language ?? null,
      album: null,
      credits: {},
    },
    versions: [...versions.values()].slice(0, 150),
  };
}

async function parallelCoverExpand(request, env, ctx, input) {
  if (!env?.DB || !env?.GEMINI_API_KEY) {
    return brainWorker.fetch(request, env, ctx);
  }

  const memory = await readMemory(request, env, ctx, input);
  if (!memory || hasUnseenMemory(memory, input)) {
    // Preserve the already-tested memory-first lane. AI fan-out starts only
    // after learned D1 candidates have been served/exhausted.
    return brainWorker.fetch(request, env, ctx);
  }

  const canonicalSeed = {
    title: String(memory?.original?.title || input?.title || '').trim(),
    artist: String(memory?.original?.artist || input?.artist || '').trim(),
    year: memory?.original?.year ?? input?.year ?? null,
    language: memory?.original?.language ?? input?.language ?? null,
  };
  if (!canonicalSeed.title || !canonicalSeed.artist) {
    return brainWorker.fetch(request, env, ctx);
  }

  const missions = await planCoverageMissions(env.DB, {
    ...input,
    title: canonicalSeed.title,
    artist: canonicalSeed.artist,
    year: canonicalSeed.year,
    language: canonicalSeed.language,
  }, MAX_PARALLEL_LANES * MISSIONS_PER_LANE).catch(() => []);

  const groups = groupMissions(missions);
  if (groups.length < 2) {
    // Near the end of Coverage Map there may be only one small gap left; the
    // stable single-lane worker is cheaper and already verified for that case.
    return brainWorker.fetch(request, env, ctx);
  }

  const settled = await Promise.allSettled(
    groups.map((group, laneIndex) =>
      executeLane(request, env, ctx, input, canonicalSeed, group, laneIndex, memory)),
  );

  const laneResults = settled.map((item, index) => {
    if (item.status === 'fulfilled') return item.value;
    return { ok: false, response: null, missions: groups[index], payload: null, error: item.reason };
  });

  const successful = laneResults.filter(result => result.ok && result.payload);
  for (const result of laneResults) {
    if (result.ok && result.payload) {
      await completeCoverageMissions(env.DB, result.missions, result.payload.versions?.length || 0).catch(() => undefined);
    } else {
      await failCoverageMissions(env.DB, result.missions).catch(() => undefined);
    }
  }

  if (successful.length === 0) {
    // Release missions above, then fall back to the stable lane so a temporary
    // parallel-provider problem does not become a user-visible dead end.
    return brainWorker.fetch(request, env, ctx);
  }

  const merged = mergePayloads(successful, memory, canonicalSeed);
  const decorated = decorateDiscoveryPayload(merged, 'cover');
  const persistence = persistDiscoveryBrainMemory(env.DB, input, decorated).catch(() => undefined);
  if (typeof ctx?.waitUntil === 'function') ctx.waitUntil(persistence);
  else await persistence;

  return json(decorated);
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method !== 'POST' || url.pathname !== '/api/v1/discover/expand') {
      return brainWorker.fetch(request, env, ctx);
    }

    const input = await request.clone().json().catch(() => ({}));
    if (input?.mode !== 'cover') return brainWorker.fetch(request, env, ctx);
    return parallelCoverExpand(request, env, ctx, input);
  },
};
