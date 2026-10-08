import parallelWorker from './worker-v20-parallel.js';
import brainWorker from './worker-v20.js';
import { collectSourceEvidence, formatSourceEvidenceForBrain } from './source-router.js';

const MEMORY_LIMIT = 150;

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

async function readMemory(request, env, ctx, input) {
  if (!env?.DB || input?.useMemory === false) return null;
  const url = new URL('/api/v1/memory/discover', request.url);
  const memoryRequest = new Request(url, {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify({ ...input, limit: MEMORY_LIMIT }),
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

function shouldUseDeepLane(input) {
  return input?.deep === true ||
    input?.verifyBetter === true ||
    /verifica\s+meglio|deep/i.test(String(input?.focus || ''));
}

async function enrichExpansionWithStructuredEvidence(request, env, ctx, input) {
  // D1 remains the first lane. If learned candidates are still unseen, serve
  // those without spending network calls on external evidence providers.
  const memory = await readMemory(request, env, ctx, input);
  if (memory && hasUnseenMemory(memory, input)) return request;

  const title = String(memory?.original?.title || input?.title || '').trim();
  const artist = String(memory?.original?.artist || input?.artist || '').trim();
  if (!title || !artist) return request;

  const evidence = await collectSourceEvidence({
    title,
    artist,
    env,
    fetchImpl: globalThis.fetch,
    // LAB60 Pollicino: Cover must not spend Worker CPU/network on Last.fm or Wikidata.
    options: {
      deep: shouldUseDeepLane(input),
      disableLastFm: input?.mode === 'cover',
      disableWikidata: input?.mode === 'cover',
    },
  }).catch(() => []);
  const evidenceFocus = formatSourceEvidenceForBrain(evidence);
  if (!evidenceFocus) return request;

  const focus = [String(input?.focus || '').trim(), evidenceFocus]
    .filter(Boolean)
    .join('\n\n');
  return new Request(request.url, {
    method: request.method,
    headers: request.headers,
    body: JSON.stringify({ ...input, focus }),
  });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method !== 'POST' || url.pathname !== '/api/v1/discover/expand') {
      return parallelWorker.fetch(request, env, ctx);
    }

    const input = await request.clone().json().catch(() => ({}));
    const enriched = await enrichExpansionWithStructuredEvidence(request, env, ctx, input);
    return parallelWorker.fetch(enriched, env, ctx);
  },
};
