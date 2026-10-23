import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker, { ingest, summary, downloadSql, validateActivity, publicCounts, shortCount, badge } from './worker.mjs';

function database() {
  const sql = new DatabaseSync(':memory:'); sql.exec(readFileSync(new URL('./schema.sql',import.meta.url),'utf8'));
  return { prepare(query) { return { bind(...args) { return { query,args,run:async()=>sql.prepare(query).run(...args) }; }, query,args:[] }; },
    async batch(statements) {
      sql.exec('BEGIN');
      try { const results=statements.map(s=>({results:sql.prepare(s.query).all(...s.args)})); sql.exec('COMMIT'); return results; }
      catch(e) { sql.exec('ROLLBACK'); throw e; }
    }
  };
}
const loginEnv={USAGE_ADMIN_USERNAME:'owner',USAGE_ADMIN_PASSWORD:'test-password-only-12345',USAGE_SESSION_SECRET:'a'.repeat(64),LOGIN_LIMITER:{limit:async()=>({success:true})}};
function loginRequest(user='owner',password=loginEnv.USAGE_ADMIN_PASSWORD,origin='https://example.test') {
  return new Request('https://example.test/auth/login',{method:'POST',headers:{Origin:origin,'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:user,password})});
}
const signedIn=await worker.fetch(loginRequest(),loginEnv);
const auth={Cookie:signedIn.headers.get('set-cookie').split(';')[0]};
const id='00000000-0000-4000-8000-000000000001';
const now=new Date('2026-10-09T12:00:00Z');
const body=(plays=0,version='0.4.2')=>({installationId:id,days:[{day:'2026-10-09',version,plays}]});
test('retries, reordering and upgrades preserve one active installation and exact play aggregates',async()=>{
  const db=database();
  await ingest(db,body(4),now); await ingest(db,body(4),now); await ingest(db,body(2),now);
  await ingest(db,body(1,'0.4.3'),now);
  const result=await summary(db,now);
  assert.equal(result.totals.installs,1); assert.equal(result.today.active,1); assert.equal(result.totals.plays,5);
  assert.equal(result.versions.length,2);
});
test('different installations count separately; week/month use distinct IDs across days',async()=>{
  const db=database(); await ingest(db,body(1),now);
  await ingest(db,{installationId:id,days:[{day:'2026-10-08',version:'0.4.2',plays:2}]},now);
  await ingest(db,{...body(3),installationId:id.replace(/1$/,'2')},now);
  const result=await summary(db,now); assert.equal(result.week,2); assert.equal(result.month,2); assert.equal(result.totals.plays,6);
});
test('upgrading one of 20 installations moves exactly one current count and preserves history',async()=>{
  const db=database();
  for(let i=1;i<=20;i++) await ingest(db,{...body(2,'0.4.10'),installationId:`00000000-0000-4000-8000-${String(i).padStart(12,'0')}`},now);
  await ingest(db,body(1,'0.4.13'),now);
  // Replayed old reports must not move the installation back.
  await ingest(db,body(2,'0.4.10'),new Date('2026-10-09T13:00:00Z'));
  const result=await summary(db,now);
  assert.deepEqual(result.currentVersions.map(r=>[r.version,r.installations]),[['0.4.10',19],['0.4.13',1]]);
  assert.equal(result.currentVersions.reduce((sum,r)=>sum+r.installations,0),20);
  assert.equal(result.totals.installs,20);assert.equal(result.totals.plays,41);
  assert.equal(result.versions.find(r=>r.version==='0.4.10').installations,20);
});
test('legacy current versions use newest activity day and numeric version order',async()=>{
  const db=database();
  await ingest(db,body(1,'0.4.9'),now);await ingest(db,body(1,'0.4.13'),now);
  assert.equal((await summary(db,now)).currentVersions[0].version,'0.4.13');
  await ingest(db,{installationId:id,days:[{day:'2026-10-08',version:'1.0.0',plays:1}]},now);
  assert.equal((await summary(db,now)).currentVersions[0].version,'0.4.13');
});
test('explicit version revisions handle upgrades, downgrades, offline backlog and delayed retries',async()=>{
  const db=database();
  const report=(version,revision)=>({...body(3,'0.4.10'),currentVersion:version,versionRevision:revision});
  await ingest(db,report('0.4.14',1),now);
  await ingest(db,report('0.4.15',2),now);
  await ingest(db,report('0.4.14',1),now);
  assert.equal((await summary(db,now)).currentVersions[0].version,'0.4.15');
  await ingest(db,report('0.4.14',3),now);
  await ingest(db,body(1,'0.4.16'),now); // Legacy retry cannot overwrite explicit state.
  const result=await summary(db,now);
  assert.deepEqual(result.currentVersions.map(r=>[r.version,r.installations,r.inferred]),[['0.4.14',1,0]]);
  assert.equal(result.totals.plays,4);
});
test('invalid current version metadata cannot partially write activity or version state',async()=>{
  const db=database();
  for(const extra of [{currentVersion:'bad',versionRevision:1},{currentVersion:'0.4.14'},{versionRevision:1},
    {currentVersion:'0.4.14',versionRevision:0},{currentVersion:'0.4.14',versionRevision:1.5}])
    await assert.rejects(ingest(db,{...body(),...extra},now));
  assert.equal((await summary(db,now)).totals.installs,0);
});
test('four downloads from one browser count once per release',async()=>{
  const db=database(); for(let i=0;i<4;i++) await db.prepare(downloadSql).bind(id,'0.4.2',now.toISOString()).run();
  await db.prepare(downloadSql).bind(id,'0.4.3',now.toISOString()).run();
  const result=await summary(db,now); assert.equal(result.downloadBrowsers,1); assert.equal(result.downloads.length,2);
  assert.ok(result.downloads.every(d=>d.browsers===1));
});
test('reject invalid IDs, negative counts, invalid dates and future/backdated batches',()=>{
  for(const change of [{installationId:'phone'}, {days:[{day:'2026-10-10',version:'0.4.2',plays:1}]},
    {days:[{day:'2026-02-30',version:'0.4.2',plays:1}]}, {days:[{day:'2026-10-09',version:'0.4.2',plays:-1}]}])
    assert.throws(()=>validateActivity({...body(),...change},now));
});
test('API rejects foreign origins and oversized bodies without touching database',async()=>{
  const rejected=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{Origin:'https://foreign.test'}}),{});
  assert.equal(rejected.status,403);
  const large=await worker.fetch(new Request('https://example.test/v1/activity',{method:'POST',headers:{'Content-Type':'application/json'},body:' '.repeat(17000)}),{});
  assert.equal(large.status,413);
});

test('UTC year boundary counts this ISO week once without carrying last month into this month',async()=>{
  const db=database(), jan=new Date('2027-01-01T00:01:00Z');
  await ingest(db,{installationId:id,days:[{day:'2026-12-31',version:'0.4.2',plays:2},{day:'2027-01-01',version:'0.4.2',plays:1}]},jan);
  const result=await summary(db,jan);
  assert.equal(result.week,1); assert.equal(result.month,1); assert.equal(result.today.plays,1);
});

test('invalid batch does not partially write valid rows',async()=>{
  const db=database();
  await assert.rejects(ingest(db,{...body(),days:[...body().days,{day:'invalid',version:'0.4.2',plays:1}]},now));
  assert.equal((await summary(db,now)).totals.installs,0);
});


const { analytics, validateRange } = await import('./worker.mjs');
test('IST midnight accepts the next calendar day without changing legacy UTC totals',async()=>{
  const db=database(), midnight=new Date('2026-10-09T18:30:00Z');
  const mixed={installationId:id,currentVersion:'0.4.14',versionRevision:1,days:[
    {day:'2026-10-09',version:'0.4.13',plays:4},
    {day:'2026-10-10',version:'0.4.14',plays:1,timeZone:'Asia/Kolkata'}
  ]};
  await ingest(db,mixed,midnight);await ingest(db,mixed,midnight);
  const ist=await analytics(db,{from:'2026-10-10',to:'2026-10-10'},midnight,'Asia/Kolkata');
  assert.equal(ist.timeZone,'Asia/Kolkata');assert.equal(ist.totals.plays,1);assert.equal(ist.totals.active,1);
  assert.equal(ist.coverageSince,'2026-10-10');
  const utc=await analytics(db,{from:'2026-10-09',to:'2026-10-09'},midnight,'UTC');
  assert.equal(utc.totals.plays,4);
  const all=await summary(db,midnight,'Asia/Kolkata');
  assert.equal(all.totals.plays,5);assert.equal(all.totals.installs,1);assert.equal(all.today.plays,1);
  assert.doesNotThrow(()=>validateRange(new URLSearchParams('from=2026-10-10&to=2026-10-10'),midnight,'Asia/Kolkata'));
  assert.throws(()=>validateRange(new URLSearchParams('from=2026-10-10&to=2026-10-10'),midnight,'UTC'));
  assert.throws(()=>validateActivity({...body(),days:[{...body().days[0],timeZone:'unknown'}]},midnight));
});
test('date range uses inclusive UTC endpoints and distinct installations across days and versions', async () => {
  const db = database();
  await ingest(db, { installationId:id, days:[
    {day:'2026-10-07',version:'0.4.2',plays:90},
    {day:'2026-10-08',version:'0.4.2',plays:2},
    {day:'2026-10-09',version:'0.4.2',plays:3},
    {day:'2026-10-09',version:'0.4.3',plays:1},
  ] }, now);
  await ingest(db, {...body(4),installationId:id.replace(/1$/,'2')}, now);
  const result = await analytics(db, {from:'2026-10-08',to:'2026-10-09'}, now);
  assert.deepEqual({...result.totals}, {active:2,plays:10});
  assert.equal(result.coverageSince,'2026-10-07');
  assert.deepEqual(result.daily.map(row => [row.day,row.active,row.plays]), [['2026-10-08',1,2],['2026-10-09',2,8]]);
  assert.deepEqual(result.versions.map(row => [row.version,row.installations,row.plays]), [['0.4.2',2,9],['0.4.3',1,1]]);
  const day = await analytics(db,{from:'2026-10-08',to:'2026-10-08'},now);
  assert.deepEqual({...day.totals},{active:1,plays:2});
});
test('historical activity remains queryable beyond the summary 30 day window', async () => {
  const db=database();
  await ingest(db,{installationId:id,days:[{day:'2026-01-01',version:'0.4.2',plays:7}]},new Date('2026-01-01T12:00:00Z'));
  assert.equal((await summary(db,now)).daily.length,0);
  assert.equal((await analytics(db,{from:'2026-01-01',to:'2026-01-01'},now)).totals.plays,7);
  const empty = await analytics(database(),{from:'2026-10-01',to:'2026-10-09'},now);
  assert.equal(empty.coverageSince,null); assert.deepEqual(empty.daily,[]);
});
test('range validation rejects impossible, future, reversed, missing and oversized dates', async () => {
  for (const query of ['from=2026-02-30&to=2026-03-01','from=2026-10-09&to=2026-10-10','from=2026-10-09&to=2026-10-08','from=2025-01-01&to=2026-10-09','from=bad','']) {
    assert.throws(()=>validateRange(new URLSearchParams(query),now));
  }
  assert.deepEqual(validateRange(new URLSearchParams('from=2026-10-09&to=2026-10-09'),now),{from:'2026-10-09',to:'2026-10-09'});
  assert.equal((await worker.fetch(new Request('https://example.test/v1/analytics?from=bad',{headers:auth}),loginEnv)).status,400);
  const result = await worker.fetch(new Request('https://example.test/v1/analytics?from=2020-01-01&to=2020-01-02',{headers:auth}),{DB:database(),...loginEnv});
  assert.equal(result.status,200); assert.equal((await result.json()).totals.active,0);
});


test('anonymous dashboard redirects to login while APIs and assets stay inaccessible',async()=>{
  for(const path of ['/v1/summary','/usage/v1/summary','/v1/analytics','/usage/dashboard.js']) {
    for(const headers of [{},{'Cf-Access-Authenticated-User-Email':'owner@example.com'},{Authorization:'Basic '+btoa('owner:password')},{Cookie:'__Host-opentune_session=forged'}]) {
      assert.equal((await worker.fetch(new Request('https://example.test'+path,{headers}),loginEnv)).status,401);
    }
    assert.equal((await worker.fetch(new Request('https://example.test'+path,{headers:auth}),{})).status,401);
  }
  const result=await worker.fetch(new Request('https://example.test/usage/'),loginEnv);
  assert.equal(result.status,303);assert.equal(result.headers.get('location'),'/login');
});
test('login form sets a secure session and permits private data and assets',async()=>{
  assert.equal(signedIn.status,303);
  const cookie=signedIn.headers.get('set-cookie');
  for(const flag of ['HttpOnly','Secure','SameSite=Strict','Path=/','Max-Age=28800']) assert.ok(cookie.includes(flag));
  const env={...loginEnv,DB:database(),ASSETS:{fetch:async()=>new Response('private dashboard')}};
  for(const path of ['/v1/summary','/usage/v1/summary','/usage/']) {
    const result=await worker.fetch(new Request('https://example.test'+path,{headers:auth}),env);
    assert.equal(result.status,200);assert.match(result.headers.get('cache-control'),/no-store/);
  }
  const cross=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{...auth,Origin:'https://evil.test'}}),env);
  assert.equal(cross.status,403);
});
test('bad credentials, cross-site login/logout, malformed and oversized submissions are rejected',async()=>{
  for(const req of [loginRequest('other'),loginRequest('owner','wrong')]) {
    const res=await worker.fetch(req,loginEnv);assert.equal(res.status,401);assert.equal(res.headers.get('set-cookie'),null);
  }
  assert.equal((await worker.fetch(loginRequest('owner',loginEnv.USAGE_ADMIN_PASSWORD,'https://evil.test'),loginEnv)).status,403);
  assert.equal((await worker.fetch(new Request('https://example.test/auth/logout',{method:'POST'}),loginEnv)).status,403);
  const big=new Request('https://example.test/auth/login',{method:'POST',headers:{Origin:'https://example.test','Content-Type':'application/x-www-form-urlencoded'},body:'x'.repeat(5000)});
  assert.equal((await worker.fetch(big,loginEnv)).status,413);
});
test('login limits guesses and fails closed without credentials or limiter',async()=>{
  assert.equal((await worker.fetch(loginRequest(),{...loginEnv,LOGIN_LIMITER:{limit:async()=>({success:false})}})).status,429);
  assert.equal((await worker.fetch(loginRequest(),{...loginEnv,LOGIN_LIMITER:undefined})).status,503);
  assert.equal((await worker.fetch(loginRequest(),{})).status,503);
  assert.equal((await worker.fetch(new Request('https://example.test/v1/summary',{headers:auth}),{...loginEnv,USAGE_ADMIN_PASSWORD:'short'})).status,401);
});
test('tampering, expired sessions and credential rotation cannot authorize reads',async()=>{
  const req=cookie=>new Request('https://example.test/v1/summary',{headers:{Cookie:cookie}});
  assert.equal((await worker.fetch(req(auth.Cookie+'tampered'),loginEnv)).status,401);
  assert.equal((await worker.fetch(req(auth.Cookie),{...loginEnv,USAGE_ADMIN_PASSWORD:'different-password-12345'})).status,401);
  const payload=Buffer.from(JSON.stringify({user:'owner',exp:1})).toString('base64url');
  const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(JSON.stringify([loginEnv.USAGE_SESSION_SECRET,loginEnv.USAGE_ADMIN_USERNAME,loginEnv.USAGE_ADMIN_PASSWORD])),{name:'HMAC',hash:'SHA-256'},false,['sign']);
  const sig=Buffer.from(await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(payload))).toString('base64url');
  assert.equal((await worker.fetch(req('__Host-opentune_session='+payload+'.'+sig),loginEnv)).status,401);
});
test('logout clears the browser session and login uses no third-party identity scripts',async()=>{
  const result=await worker.fetch(new Request('https://example.test/auth/logout',{method:'POST',headers:{...auth,Origin:'https://example.test'}}),loginEnv);
  assert.equal(result.status,303);assert.match(result.headers.get('set-cookie'),/Max-Age=0/);
  const page=await worker.fetch(new Request('https://example.test/login'),loginEnv);
  const html=await page.text();assert.ok(html.includes('autocomplete="current-password"'));assert.ok(!html.includes('Google'));
});
test('anonymous reporting remains write-only and compatible',async()=>{
  const env={DB:database()},day=new Date().toISOString().slice(0,10);
  const result=await worker.fetch(new Request('https://example.test/v1/activity',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({installationId:id,days:[{day,version:'0.4.13',plays:3}]})}),env);
  assert.equal(result.status,200);assert.deepEqual(await result.json(),{accepted:1});
  assert.equal((await worker.fetch(new Request('https://example.test/v1/summary'),env)).status,401);
});

test('GitHub Pages login returns a bearer session without third-party cookies',async()=>{
  const origin='https://justshivv.github.io';
  const request=new Request('https://example.test/auth/login',{method:'POST',headers:{Origin:origin,Accept:'application/json','Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:'owner',password:loginEnv.USAGE_ADMIN_PASSWORD})});
  const login=await worker.fetch(request,loginEnv);
  assert.equal(login.status,200);assert.equal(login.headers.get('set-cookie'),null);assert.equal(login.headers.get('Access-Control-Allow-Origin'),origin);
  const {token}=await login.json();
  const result=await worker.fetch(new Request('https://example.test/usage/v1/summary',{headers:{Origin:origin,Authorization:'Bearer '+token}}),{...loginEnv,DB:database()});
  assert.equal(result.status,200);assert.equal(result.headers.get('Access-Control-Allow-Origin'),origin);
  const unauthenticated=await worker.fetch(new Request('https://example.test/usage/v1/summary',{headers:{Origin:origin}}),loginEnv);
  assert.equal(unauthenticated.status,401);assert.equal(unauthenticated.headers.get('Access-Control-Allow-Origin'),origin);
  const preflight=await worker.fetch(new Request('https://example.test/usage/v1/summary',{method:'OPTIONS',headers:{Origin:origin,'Access-Control-Request-Headers':'authorization'}}),loginEnv);
  assert.equal(preflight.status,204);assert.match(preflight.headers.get('Access-Control-Allow-Headers'),/Authorization/);
});
test('foreign origins cannot obtain or use a bearer session',async()=>{
  const login=new Request('https://example.test/auth/login',{method:'POST',headers:{Origin:'https://evil.test',Accept:'application/json','Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:'owner',password:loginEnv.USAGE_ADMIN_PASSWORD})});
  assert.equal((await worker.fetch(login,loginEnv)).status,403);
  const result=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{Origin:'https://evil.test',Authorization:'Bearer '+auth.Cookie.split('=')[1]}}),loginEnv);
  assert.equal(result.status,403);assert.equal(result.headers.get('Access-Control-Allow-Origin'),null);
});

test('an email address can be the owner username',async()=>{
  const env={...loginEnv,USAGE_ADMIN_USERNAME:'owner@example.com'};
  const result=await worker.fetch(loginRequest('owner@example.com'),env);
  assert.equal(result.status,303);
});

test('public counts are aggregate only and match the dashboard figures',async()=>{
  const db=database();
  await ingest(db,body(3),now);
  await ingest(db,{...body(1),installationId:id.replace(/1$/,'2')},now);
  const counts=await publicCounts(db,now);
  assert.equal(counts.installations,2);
  assert.equal(counts.daily,2);
  assert.equal(counts.monthly,2);
  // No per-installation or version detail leaks out.
  assert.deepEqual(Object.keys(counts).sort(),['asOf','daily','installations','monthly','timeZone','weekly']);
});

test('public counts include current apps (India time) and older ones (UTC), each installation once',async()=>{
  const db=database();
  const ist=(n,plays=1)=>({installationId:id.replace(/1$/,String(n)),days:[{day:'2026-10-09',version:'1.0.1',plays,timeZone:'Asia/Kolkata'}]});
  await ingest(db,body(1),now);                                   // 1: an old client, UTC only
  await ingest(db,ist(2),now);                                    // 2: a current client, India time
  await ingest(db,ist(3),now);
  await ingest(db,{...ist(1),installationId:id},now);             // 1 again, after updating
  const counts=await publicCounts(db,now);
  assert.equal(counts.timeZone,'Asia/Kolkata');
  assert.equal(counts.monthly,3);
  assert.equal(counts.daily,3);
  assert.equal(counts.installations,3);
  const shield=await (await worker.fetch(new Request('https://example.test/v1/badge'),{DB:db})).json();
  assert.equal(shield.message,'3');
});

test('the public count and badge endpoints need no sign-in',async()=>{
  const db=database(); await ingest(db,body(1),now);
  const env={DB:db};
  const open=await worker.fetch(new Request('https://example.test/v1/public'),env);
  assert.equal(open.status,200);
  assert.equal((await open.json()).installations,1);
  const pill=await worker.fetch(new Request('https://example.test/v1/badge'),env);
  assert.equal(pill.status,200);
  const shield=await pill.json();
  assert.equal(shield.schemaVersion,1);
  assert.equal(shield.label,'active');
  assert.equal(shield.message,'1');
});

test('the private dashboard data still needs a sign-in',async()=>{
  const db=database(); await ingest(db,body(1),now);
  const locked=await worker.fetch(new Request('https://example.test/v1/summary'),{DB:db});
  assert.notEqual(locked.status,200);
});

test('updating an installation records one version change, never a new install',async()=>{
  const db=database();
  const install=(ver,rev)=>({installationId:id,days:[{day:'2026-10-09',version:ver,plays:1}],currentVersion:ver,versionRevision:rev});
  await ingest(db,install('0.4.17',1),now);
  await ingest(db,install('0.4.17',1),now);   // retry at the same revision: no change
  await ingest(db,install('0.4.18',2),now);   // the update
  const result=await summary(db,now);
  assert.equal(result.totals.installs,1);                 // still one installation, not two
  assert.equal(result.updates.total,1);                   // one update counted
  assert.equal(result.updates.byVersion.length,1);
  assert.equal(result.updates.byVersion[0].version,'0.4.18');
  assert.equal(result.updates.byVersion[0].updates,1);
  const current=Object.fromEntries(result.currentVersions.map(r=>[r.version,r.installations]));
  assert.equal(current['0.4.18'],1);                      // the count moved to the new version
  assert.equal(current['0.4.17']??0,0);
});

test('badge counts are shortened for display',()=>{
  assert.equal(shortCount(42),'42');
  assert.equal(shortCount(1234),'1.2k');
  assert.equal(shortCount(42000),'42k');
  assert.equal(shortCount(1_200_000),'1.2M');
  assert.equal(badge({monthly:5300}).message,'5.3k');
  assert.equal(badge({installations:900},new URLSearchParams('metric=installations')).label,'installs');
});
