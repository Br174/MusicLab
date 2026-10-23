/**
 * MusicLab LAB46 staging-only ingress. Every route, including archive/memory
 * reads, is authenticated BEFORE dispatch. Never deploy this entrypoint to
 * production or store its bearer credential in an Android APK.
 *
 * Creating this file does NOT deploy a Worker or configure a secret.
 */
import runtimeWorker from '../src/worker-v20-runtime.js';
import { stagingCloudWriteGate } from '../src/lab46-staging-write-guard.js';

const STAGING_PATHS = new Set([
  '/api/v1/brain/plan',
  '/api/v1/brain/decision',
  '/api/v1/playback/binding',
  '/api/v1/memory/discover',
  '/api/v1/archive/search',
  '/api/v1/discover/initial',
  '/api/v1/discover/expand',
  '/api/v1/turbo/decision-sync',
]);

function deny(code, reason) {
  return new Response(JSON.stringify({stato:'bloccato',reason}), {
    status:code,
    headers:{
      'content-type':'application/json; charset=utf-8',
      'cache-control':'no-store',
      'x-content-type-options':'nosniff',
    }
  });
}

export function createLab46StagingWorker(delegate=runtimeWorker) {
  return {
    async fetch(request, env, ctx) {
      const url=new URL(request.url);
      // No uncontrolled fallback to legacy endpoints or public read routes.
      if (!STAGING_PATHS.has(url.pathname)) return deny(404,'staging_route_disabled');
      if (request.method !== 'POST') return deny(405,'staging_method_disabled');
      const gate=await stagingCloudWriteGate(request,env);
      if(!gate.allowed) return deny(gate.status,gate.reason);
      return delegate.fetch(request,env,ctx);
    }
  };
}

export default createLab46StagingWorker();
