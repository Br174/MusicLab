/**
 * LAB46 isolated-staging sync receipt coordinator.
 *
 * The staging Cloud gate authenticates before this handler is reached. This
 * module never authenticates a user, creates a production binding, or writes
 * outside the caller's already verified isolated D1.
 *
 * A committed revision is idempotent. An interrupted, still-PENDING revision
 * is deliberately blocked for forensic recovery, rather than blindly
 * re-applying a possibly committed editorial decision.
 */
const REVISION = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const VIDEO = /^[a-zA-Z0-9_-]{11}$/;

function reply(body,status=200){
  return new Response(JSON.stringify(body),{status,headers:{
    'content-type':'application/json; charset=utf-8',
    'cache-control':'no-store',
  }});
}

export async function syncLab46Decision(request,db,saveDecision){
  if(!db) return reply({stato:'bloccato',reason:'db_unavailable'},503);
  const text=await request.text().catch(()=>null);
  if(!text || text.length>65536) return reply({stato:'rifiutato',reason:'invalid_payload_size'},413);
  let data;
  try{data=JSON.parse(text);}catch{return reply({stato:'rifiutato',reason:'invalid_json'},400);}
  const revision=String(data?.revision||'').trim();
  const fingerprint=String(data?.fingerprint||'').trim();
  const payload=data?.payload;
  const c=payload?.candidate;
  if(!REVISION.test(revision) || fingerprint.length<4 || fingerprint.length>256 ||
    !c || typeof c!=='object' || c.versionFingerprint!==fingerprint ||
    !String(c.title||'').trim() || !String(c.artist||'').trim() ||
    !String(payload.originalTitle||'').trim() || !String(payload.originalArtist||'').trim() ||
    !['APPROVED','REJECTED'].includes(payload.status) ||
    (c.playbackVideoId && !VIDEO.test(String(c.playbackVideoId)))) {
    return reply({stato:'rifiutato',reason:'invalid_decision_identity'},422);
  }
  const content=new TextEncoder().encode(JSON.stringify(payload));
  const digest=await crypto.subtle.digest('SHA-256',content);
  const hash=Array.from(new Uint8Array(digest),v=>v.toString(16).padStart(2,'0')).join('');
  const lookup=()=>db.prepare(
    'SELECT revision,fingerprint,payload_hash,state,version_id FROM lab46_sync_receipts WHERE revision=?1 LIMIT 1'
  ).bind(revision).first();
  let existing;
  try{existing=await lookup();}catch{
    return reply({stato:'bloccato',reason:'receipt_table_missing'},503);
  }
  if(existing && (existing.payload_hash!==hash || existing.fingerprint!==fingerprint)){
    return reply({stato:'conflitto',reason:'revision_payload_mismatch'},409);
  }
  if(existing?.state==='committed'){
    return reply({stato:'salvato',revision,fingerprint,versionId:existing.version_id,
      receipt:'staging_verified_committed',replayed:true});
  }
  if(existing?.state==='pending'){
    return reply({stato:'bloccato',reason:'pending_requires_recovery',revision},409);
  }
  try {
    if(existing?.state==='failed'){
      const changed=await db.prepare(
        "UPDATE lab46_sync_receipts SET state='pending',updated_at=CURRENT_TIMESTAMP WHERE revision=?1 AND state='failed' AND payload_hash=?2"
      ).bind(revision,hash).run();
      if(changed?.meta?.changes!==1) return reply({stato:'conflitto',reason:'claim_failed'},409);
    } else {
      const claimed=await db.prepare(
        "INSERT OR IGNORE INTO lab46_sync_receipts(revision,fingerprint,payload_hash,state) VALUES(?1,?2,?3,'pending')"
      ).bind(revision,fingerprint,hash).run();
      if(claimed?.meta?.changes!==1) return reply({stato:'conflitto',reason:'claim_in_use'},409);
    }
    const saved=await saveDecision(db,payload);
    if(saved?.httpStatus!==200 || saved?.body?.stato!=='salvato' ||
      !saved?.body?.versionId) {
      await db.prepare(
        "UPDATE lab46_sync_receipts SET state='failed',updated_at=CURRENT_TIMESTAMP WHERE revision=?1 AND state='pending'"
      ).bind(revision).run();
      return reply({stato:'non_salvato',reason:'decision_persistence_failed'},502);
    }
    const updated=await db.prepare(
      "UPDATE lab46_sync_receipts SET state='committed',version_id=?1,updated_at=CURRENT_TIMESTAMP WHERE revision=?2 AND state='pending'"
    ).bind(saved.body.versionId,revision).run();
    if(updated?.meta?.changes!==1){
      return reply({stato:'bloccato',reason:'receipt_commit_uncertain',revision},503);
    }
    return reply({stato:'salvato',revision,fingerprint,versionId:saved.body.versionId,
      receipt:'staging_verified_committed',replayed:false});
  } catch {
    // Unknown side effects may have happened. Never return a success receipt
    // and never clear the user's local outbox on an uncertain commit.
    return reply({stato:'bloccato',reason:'sync_uncertain_requires_recovery',revision},503);
  }
}
