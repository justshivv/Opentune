import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker, { currentVersionsSql, tooSoon, REPORT_GAP_MS, rememberFor, ingest, summary, downloadSql, validateActivity, publicCounts, shortCount, badge } from './worker.mjs';

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
const currentNow=async db=>(await db.batch([db.prepare(currentVersionsSql)]))[0].results;
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
  assert.equal((await currentNow(db))[0].version,'0.4.13');
});
test('explicit version revisions handle upgrades, downgrades, offline backlog and delayed retries',async()=>{
  const db=database();
  const report=(version,revision)=>({...body(3,'0.4.10'),currentVersion:version,versionRevision:revision});
  await ingest(db,report('0.4.14',1),now);
  await ingest(db,report('0.4.15',2),now);
  await ingest(db,report('0.4.14',1),now);
  // Current versions are counted nightly; between reports, read them as that count does.
  assert.equal((await currentNow(db))[0].version,'0.4.15');
  await ingest(db,report('0.4.14',3),now);
  await ingest(db,body(1,'0.4.16'),now); // Legacy retry cannot overwrite explicit state.
  assert.deepEqual((await currentNow(db)).map(r=>[r.version,r.installations,r.inferred]),[['0.4.14',1,0]]);
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
  assert.deepEqual(Object.keys(counts).sort(),['asOf','daily','installations','monthly','timeZone','weekly','yesterday']);
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
  // The badge endpoint counts this month by the real clock, so its data is dated today.
  const live=database(), today=new Date(), istToday=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).format(today);
  for (const n of [1,2,3]) await ingest(live,{installationId:id.replace(/1$/,String(n)),days:[{day:istToday,version:'1.0.1',plays:1,timeZone:'Asia/Kolkata'}]},today);
  const shield=await (await worker.fetch(new Request('https://example.test/v1/badge'),{DB:live})).json();
  assert.equal(shield.message,'3');
});

test('the public count and badge endpoints need no sign-in',async()=>{
  // Dated today: the endpoints count this month by the real clock.
  const today=new Date(), istToday=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).format(today);
  const db=database(); await ingest(db,{installationId:id,days:[{day:istToday,version:'1.0.1',plays:1,timeZone:'Asia/Kolkata'}]},today);
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

test('reports right after the last one from the same installation are turned away unwritten',async()=>{
  const env={DB:database()},day=new Date().toISOString().slice(0,10);
  const post=plays=>worker.fetch(new Request('https://example.test/v1/activity',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({installationId:id.replace(/1$/,'9'),days:[{day,version:'0.4.13',plays}]})}),env);
  assert.equal((await post(1)).status,200);
  const again=await post(2);
  assert.equal(again.status,429);assert.equal(again.headers.get('Retry-After'),String(REPORT_GAP_MS/1000));
  const db={};
  assert.equal(tooSoon(db,'a',0),false);assert.equal(tooSoon(db,'a',REPORT_GAP_MS-1),true);assert.equal(tooSoon(db,'a',REPORT_GAP_MS),false);
  assert.equal(tooSoon(db,'b',1),false);
});
test('a report with no new plays writes nothing',async()=>{
  const db=database();
  await ingest(db,body(4),now);await ingest(db,body(4),now);await ingest(db,body(3),now);
  assert.equal((await summary(db,now)).totals.plays,4);
});
test('public counts are read once in five minutes',async()=>{
  let reads=0;const db={};
  const read=async()=>++reads;
  assert.equal(await rememberFor(db,'k',300000,read,0),1);
  assert.equal(await rememberFor(db,'k',300000,read,299999),1);
  assert.equal(await rememberFor(db,'k',300000,read,300000),2);
});

test('cohorts count upgrades once and distinguish new and returning listening', async () => {
  const db = database();
  await ingest(db, {installationId:id, days:[{day:'2026-10-08',version:'0.4.2',plays:2}]}, now);
  await ingest(db, body(3), now);
  await ingest(db, body(2, '0.4.3'), now);
  await ingest(db, {...body(7), installationId:id.replace(/1$/, '2')}, now);
  await ingest(db, {...body(7), installationId:id.replace(/1$/, '2')}, now);
  const result = await summary(db, now);
  const row = result.cohorts.find(x => x.day === '2026-10-09');
  assert.deepEqual({...row}, {day:'2026-10-09',newInstallations:1,returningInstallations:1,newPlays:7,returningPlays:5,listeners:2});
  assert.equal(row.newPlays + row.returningPlays, result.today.plays);
});

test('the period splits its active installations into new and returning, each counted once', async () => {
  const db = database();
  const two = id.replace(/1$/, '2'), three = id.replace(/1$/, '3');
  // One first seen before the period, one first seen inside it on two days, one upgraded inside it.
  await ingest(db, {installationId:id, days:[{day:'2026-10-01',version:'0.4.2',plays:1}]}, now);
  await ingest(db, {installationId:id, days:[{day:'2026-10-08',version:'0.4.2',plays:2}]}, now);
  await ingest(db, {installationId:two, days:[{day:'2026-10-08',version:'0.4.2',plays:3}]}, now);
  await ingest(db, {installationId:two, days:[{day:'2026-10-09',version:'0.4.2',plays:4}]}, now);
  await ingest(db, {installationId:three, days:[{day:'2026-10-09',version:'0.4.2',plays:1}]}, now);
  await ingest(db, {installationId:three, days:[{day:'2026-10-09',version:'0.4.3',plays:1}]}, now);
  const result = await analytics(db, {from:'2026-10-08',to:'2026-10-09'}, now);
  assert.deepEqual({...result.split}, {newInstallations:2, returningInstallations:1, newPlays:9, returningPlays:2});
  assert.equal(result.split.newInstallations + result.split.returningInstallations, result.totals.active);
  assert.equal(result.split.newPlays + result.split.returningPlays, result.totals.plays);
});

const { sharedCounts, countDay } = await import('./worker.mjs');
test('summary tables built from existing history match a full count, and running the schema again changes nothing', async () => {
  const schema = readFileSync(new URL('./schema.sql', import.meta.url), 'utf8');
  const before = schema.slice(0, schema.indexOf('-- Summaries kept'));
  const sql = new DatabaseSync(':memory:'); sql.exec(before);
  const add = (table, inst, day, version, plays) => sql.prepare(`INSERT INTO ${table} VALUES(?,?,?,?,?)`).run(inst, day, version, plays, 'x');
  add('activity', id, '2026-09-28', '0.4.2', 3);                     // a Monday
  add('activity', id, '2026-10-04', '0.4.2', 1);                     // the Sunday of that week
  add('activity_ist', id, '2026-10-09', '1.0.1', 2);
  add('activity_ist', id.replace(/1$/, '2'), '2026-10-05', '1.0.1', 5);
  sql.exec(schema); sql.exec(schema);
  const one = q => sql.prepare(q).get();
  assert.deepEqual({...one('SELECT installations, plays FROM totals')}, {installations: 2, plays: 11});
  assert.equal(one(`SELECT first_day FROM installations WHERE installation='${id}'`).first_day, '2026-09-28');
  assert.equal(one("SELECT COUNT(*) AS n FROM active_weeks WHERE week='2026-09-28'").n, 1);
  assert.equal(one("SELECT COUNT(*) AS n FROM active_weeks WHERE week='2026-10-05'").n, 2);
  assert.equal(one("SELECT COUNT(*) AS n FROM active_months WHERE month='2026-10'").n, 2);
  assert.equal(one("SELECT last_day FROM versions_seen WHERE version='1.0.1'").last_day, '2026-10-09');
});

test('reports keep the summary tables in step with the activity they record', async () => {
  const db = database();
  await ingest(db, {installationId:id, days:[{day:'2026-10-05',version:'1.0.1',plays:2,timeZone:'Asia/Kolkata'}]}, now);
  await ingest(db, {installationId:id, days:[{day:'2026-10-05',version:'1.0.1',plays:6,timeZone:'Asia/Kolkata'}]}, now);
  await ingest(db, {installationId:id, days:[{day:'2026-10-09',version:'0.4.2',plays:1}]}, now);
  await ingest(db, {installationId:id.replace(/1$/, '2'), days:[{day:'2026-10-11',version:'1.0.1',plays:1,timeZone:'Asia/Kolkata'}]}, new Date('2026-10-11T12:00:00Z'));
  const s = await summary(db, now);
  assert.equal(s.totals.installs, 2); assert.equal(s.totals.plays, 8); assert.equal(s.totals.since, '2026-10-05');
  assert.deepEqual(s.versions.map(v => [v.version, v.installations]), [['1.0.1', 2], ['0.4.2', 1]]);
  // 2026-10-11 is a Sunday: the same week as the 5th and the 9th.
  const counts = await publicCounts(db, new Date('2026-10-11T12:00:00Z'));
  assert.deepEqual([counts.installations, counts.daily, counts.weekly, counts.monthly], [2, 1, 2, 2]);
});

test('the shared public copy is counted once a day, after 06:00 India time', async () => {
  const db = database();
  const report = (n, day) => ingest(db, {installationId:id.replace(/1$/, String(n)), days:[{day,version:'1.0.1',plays:1,timeZone:'Asia/Kolkata'}]}, new Date('2026-10-10T12:00:00Z'));
  await report(1, '2026-10-09');
  const morning = Date.parse('2026-10-10T01:00:00Z');                         // 06:30 IST on the 10th
  assert.equal((await sharedCounts(db, 'Asia/Kolkata', morning)).yesterday, 1);
  await report(2, '2026-10-09');                                              // a late report for the 9th
  // The rest of the day every instance gets the saved copy without counting again.
  assert.equal((await sharedCounts(db, 'Asia/Kolkata', Date.parse('2026-10-10T23:59:00Z'))).yesterday, 1);
  // Before 06:00 IST on the 11th it's still the 10th's copy.
  assert.equal((await sharedCounts(db, 'Asia/Kolkata', Date.parse('2026-10-11T00:20:00Z'))).yesterday, 1);
  // After it, counted again: the 10th, which nobody listened on.
  const next = await sharedCounts(db, 'Asia/Kolkata', Date.parse('2026-10-11T00:31:00Z'));
  assert.equal(next.yesterday, 0); assert.equal(next.installations, 2);
  assert.equal(countDay(Date.parse('2026-10-11T00:29:00Z'), 'Asia/Kolkata'), '2026-10-10');
});

test('the yesterday badge shows the whole of the day before', async () => {
  const shield = badge({yesterday: 1234, daily: 5}, new URLSearchParams('metric=yesterday&label=listening%20yesterday'));
  assert.equal(shield.message, '1.2k'); assert.equal(shield.label, 'listening yesterday'); assert.equal(shield.cacheSeconds, 3600);
});

const { housekeeping, KEEP_DAYS } = await import('./worker.mjs');
test('a day row counts an installation once, even when it updates that day, and keeps its plays', async () => {
  const db = database();
  await ingest(db, body(3, '0.4.2'), now);
  await ingest(db, body(2, '0.4.3'), now);                         // updated mid-day: same installation
  await ingest(db, body(5, '0.4.2'), now);                         // more plays on the first version
  await ingest(db, {...body(1), installationId: id.replace(/1$/, '2')}, now);
  const s = await summary(db, now);
  assert.deepEqual({...s.today}, {active: 2, plays: 8});
  assert.deepEqual(s.daily.map(d => [d.day, d.active, d.plays]), [['2026-10-09', 2, 8]]);
});

test('the nightly clean-up drops rows past the retention but keeps the lifetime and daily figures', async () => {
  const db = database();
  const old = '2025-01-01';
  await db.prepare('INSERT INTO activity_ist VALUES(?,?,?,?,?)').bind(id, old, '0.4.0', 7, 'x').run();
  await ingest(db, {installationId:id, days:[{day:'2026-10-09',version:'1.0.1',plays:2,timeZone:'Asia/Kolkata'}]}, now);
  const done = await housekeeping(db, now);
  assert.equal(done.removed, 1);
  assert.equal(KEEP_DAYS, 400);
  const s = await summary(db, now, 'Asia/Kolkata');
  assert.equal(s.totals.installs, 1); assert.equal(s.totals.plays, 9);
  assert.equal(s.totals.since, old);                                // the daily row outlives the detail
  const [{results: left}] = await db.batch([db.prepare('SELECT COUNT(*) AS n FROM activity_ist').bind()]);
  assert.equal(left[0].n, 1);
  // Running it again finds nothing more to remove.
  assert.equal((await housekeeping(db, now)).removed, 0);
});

test('when D1 refuses writes, the public counts serve the last saved copy instead of failing',async()=>{
  const db=database();
  const kept={asOf:'2026-11-26T00:07:21.762Z',timeZone:'Asia/Kolkata',installations:23932,daily:204,yesterday:2072,weekly:12012,monthly:21698};
  const yesterdayAt=Date.parse('2026-11-26T00:07:21.762Z');
  await db.prepare('INSERT INTO public_snapshot(zone,payload,computed_at) VALUES(?,?,?)').bind('Asia/Kolkata',JSON.stringify(kept),yesterdayAt).run();
  // Every write throws, as D1 does once the daily write limit is spent; reads still work.
  const blocked={prepare(q){const p=db.prepare(q);return {bind(...a){const b=p.bind(...a);return {...b,run:async()=>{throw new Error('D1_ERROR: write limit exceeded');}};}};},batch:(s)=>db.batch(s)};
  const later=Date.parse('2026-11-26T00:46:00Z'); // past 06:00 in India: a new day's count is due
  assert.deepEqual(await sharedCounts(blocked,'Asia/Kolkata',later),kept);
});
test('a report raising a day\'s plays writes the day row and its day total, nothing lifetime',async()=>{
  const sql=new DatabaseSync(':memory:'); sql.exec(readFileSync(new URL('./schema.sql',import.meta.url),'utf8'));
  const up=sql.prepare(`INSERT INTO activity_ist(installation,day,version,plays,received_at) VALUES(?,?,?,?,?)
    ON CONFLICT(installation,day,version) DO UPDATE SET plays=excluded.plays,received_at=excluded.received_at WHERE excluded.plays > activity_ist.plays`);
  up.run(id,'2026-11-25','1.1.0',3,'t');
  const before=sql.prepare('SELECT plays FROM totals WHERE id=1').get().plays;
  up.run(id,'2026-11-25','1.1.0',9,'t');
  assert.equal(sql.prepare('SELECT plays FROM totals WHERE id=1').get().plays,before,'the lifetime row is not written on a plays update');
  assert.equal(sql.prepare("SELECT plays FROM daily_totals WHERE zone='Asia/Kolkata' AND day='2026-11-25'").get().plays,9);
  const names=sql.prepare("SELECT name FROM sqlite_master WHERE type IN ('index','trigger')").all().map(r=>r.name);
  assert.ok(!names.includes('activity_ist_version') && !names.includes('activity_version'),'no per-version index');
  assert.ok(!names.includes('summarize_activity_ist_plays'),'no lifetime plays trigger');
});

// ---------------------------------------------------------------------------
// The dashboard's nightly counts against the queries they replaced (reference-queries.mjs), on a
// synthetic history shaped like production: installations first seen over 60 days (more of them
// lately), most active a few days a month, older clients reporting UTC days and some of them
// moving to the India-time table, releases taken up with a lag (some mid-day), explicit current
// versions, plays of zero, downloads.
const { oldSummary, oldAnalytics } = await import('./reference-queries.mjs');
const { reuse } = await import('./worker.mjs');
const schemaText=readFileSync(new URL('./schema.sql',import.meta.url),'utf8');
function wrapped(sql, refuseWrites=false) {
  const log=[], write=q=>/^\s*(INSERT|UPDATE|DELETE)/i.test(q);
  const refuse=q=>{ if(refuseWrites&&write(q)) throw new Error('D1_ERROR: write limit exceeded'); };
  return { log, prepare(query) { return { bind(...args) { return { query,args,run:async()=>{log.push(query);refuse(query);return sql.prepare(query).run(...args);} }; }, query,args:[] }; },
    async batch(statements) {
      for(const s of statements) { log.push(s.query); refuse(s.query); }
      sql.exec('BEGIN');
      try { const results=statements.map(s=>({results:sql.prepare(s.query).all(...s.args)})); sql.exec('COMMIT'); return results; }
      catch(e) { sql.exec('ROLLBACK'); throw e; }
    } };
}
function history(sql, {installs=3000, days=60, end='2026-11-26', seed=11}={}) {
  let s=seed; const rnd=()=>(s=(s*1103515245+12345)%2147483648)/2147483648;
  const dayOf=i=>new Date(Date.parse(end)-(days-1-i)*86400000).toISOString().slice(0,10);
  const uuid=n=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
  const releases=[[0,'1.0.0'],[Math.floor(days*0.35),'1.1.0'],[Math.floor(days*0.7),'1.2.0'],[days-3,'1.2.1']];
  const add={activity:sql.prepare('INSERT INTO activity VALUES(?,?,?,?,?)'),activity_ist:sql.prepare('INSERT INTO activity_ist VALUES(?,?,?,?,?)')};
  const current=sql.prepare(`INSERT INTO installation_versions(installation,version,revision,received_at) VALUES(?,?,?,?)
    ON CONFLICT(installation) DO UPDATE SET version=excluded.version,revision=excluded.revision,received_at=excluded.received_at WHERE excluded.revision>installation_versions.revision`);
  const people=[];
  for(let n=1;n<=installs;n++) {
    const r=rnd(),k=rnd();
    people.push({id:uuid(n),n,first:Math.min(days-1,Math.floor(Math.pow(rnd(),0.6)*days)),rate:r<0.08?0.55:r<0.3?0.12:0.035,
      kind:k<0.12?'legacy':k<0.2?'migrated':k<0.85?'explicit':'inferred',lag:Math.floor(rnd()*12),rev:0,version:null});
  }
  const byDay=Array.from({length:days},()=>[]);
  for(const p of people) { byDay[p.first].push(p); for(let d=p.first+1;d<days;d++) if(rnd()<p.rate) byDay[d].push(p); }
  sql.exec('BEGIN');
  for(let d=0;d<days;d++) for(const p of byDay[d]) {
    const day=dayOf(d),at=day+'T20:00:00.000Z',legacy=p.kind==='legacy'||(p.kind==='migrated'&&d<p.first+10);
    const table=legacy?'activity':'activity_ist';
    const version=legacy?['0.4.9','0.4.13','0.4.16'][Math.min(2,Math.floor((d-p.first)/30)+(p.n%2))]:releases.filter(([from])=>from+p.lag<=d).at(-1)?.[1]||'1.0.0';
    if(p.version&&p.version!==version&&rnd()<0.5) add[table].run(p.id,day,p.version,Math.floor(rnd()*5),at);
    add[table].run(p.id,day,version,rnd()<0.1?0:Math.floor(rnd()*40),at);
    if(!legacy&&p.kind!=='inferred'&&p.version!==version) current.run(p.id,version,++p.rev,at);
    p.version=version;
  }
  const download=sql.prepare('INSERT OR IGNORE INTO downloads VALUES(?,?,?)');
  for(let b=1;b<=installs/4;b++) for(const [from,release] of releases) if(rnd()<0.5)
    download.run(`00000000-0000-4000-9000-${String(b).padStart(12,'0')}`,release,dayOf(Math.min(days-1,from+Math.floor(rnd()*10)))+'T10:00:00.000Z');
  sql.exec('COMMIT');
  return people;
}
function built(schema=schemaText) { const sql=new DatabaseSync(':memory:'); sql.exec(schema); return {sql, people:history(sql)}; }
// The same page, apart from when it was made; versions that were last seen the same day come in
// either order, as they did before.
const figures=page=>{
  const out=JSON.parse(JSON.stringify(page)); delete out.asOf; delete out.currentVersionsAsOf;
  if(!out.range) out.versions.sort((a,b)=>a.version<b.version?-1:1);
  if(out.downloads) out.downloads.sort((a,b)=>a.release<b.release?-1:1);
  return out;
};
const evening=new Date('2026-11-26T14:00:00Z'), nightly=new Date('2026-11-26T00:35:00Z');   // 19:30 and 06:05 India time
const lastDays=(n,to='2026-11-26')=>({from:new Date(Date.parse(to)-(n-1)*86400000).toISOString().slice(0,10),to});
const ranges=[lastDays(1),lastDays(7),lastDays(30),lastDays(90),lastDays(21),lastDays(60),lastDays(366),
  lastDays(11,'2026-10-31'),lastDays(10,'2026-11-25'),lastDays(1,'2026-11-25'),{from:'2026-11-17',to:'2026-11-26'}];
async function sameAsBefore(db, now, label, {skip=[]}={}) {
  for(const zone of ['Asia/Kolkata','UTC']) {
    const page=figures(await summary(db,now,zone,{shared:true})), before=figures(await oldSummary(db,now,zone));
    for(const key of skip) { delete page[key]; delete before[key]; }
    assert.deepEqual(page,before,`${label}: summary ${zone}`);
    for(const range of ranges.filter(r=>r.to<=new Intl.DateTimeFormat('en-CA',{timeZone:zone}).format(now)))
      assert.deepEqual(figures(await analytics(db,range,now,zone,{shared:true})),figures(await oldAnalytics(db,range,now,zone)),`${label}: ${zone} ${range.from}..${range.to}`);
  }
}
test('the dashboard from the nightly counts matches the queries it replaced, figure for figure', async () => {
  const {sql}=built(), db=wrapped(sql);
  await housekeeping(db,nightly);
  await sameAsBefore(db,evening,'after the nightly count');
  // The days before the window are now kept, and read back the same in another instance.
  assert.ok(sql.prepare("SELECT COUNT(*) AS n FROM daily_cohorts WHERE zone='Asia/Kolkata'").get().n>=25);
  await sameAsBefore(wrapped(sql),evening,'read back from daily_cohorts');
  // Without the cron, the first request counts the window itself.
  await sameAsBefore(wrapped(built().sql),evening,'counted on first use');
  // Between midnight and the nightly count, the count is a day old and two days are read live.
  const night=wrapped(built().sql);
  await housekeeping(night,nightly);
  await sameAsBefore(night,new Date('2026-11-26T20:00:00Z'),'after midnight');   // 01:30 on the 27th in India
});
test('reports after the nightly count show in the next load; current versions follow the next night', async () => {
  const {sql,people}=built(), db=wrapped(sql);
  await housekeeping(db,nightly);
  const counted=figures(await oldSummary(db,evening,'Asia/Kolkata')).currentVersions;
  const at=new Date('2026-11-26T15:00:00Z');
  for(let n=0;n<40;n++) await ingest(db,{installationId:`00000000-0000-4000-8000-9${String(n).padStart(11,'0')}`,currentVersion:'1.3.0',versionRevision:1,
    days:[{day:'2026-11-26',version:'1.3.0',plays:n%5,timeZone:'Asia/Kolkata'}]},at);
  for(const p of people.filter(p=>p.kind==='explicit').slice(0,60))
    await ingest(db,{installationId:p.id,currentVersion:'1.3.0',versionRevision:p.rev+1,days:[{day:'2026-11-26',version:'1.3.0',plays:7,timeZone:'Asia/Kolkata'}]},at);
  for(const p of people.filter(p=>p.kind==='legacy').slice(0,20))
    await ingest(db,{installationId:p.id,days:[{day:'2026-11-26',version:'0.4.17',plays:3}]},at);
  for(const browser of ['00000000-0000-4000-9000-999999999999','00000000-0000-4000-9000-000000000001'])
    await db.prepare(downloadSql).bind(browser,'1.3.0',at.toISOString()).run();
  const now=new Date('2026-11-26T16:00:00Z');
  await sameAsBefore(db,now,'with today\'s new reports',{skip:['currentVersions']});
  assert.deepEqual(figures(await summary(db,now,'Asia/Kolkata')).currentVersions,counted,'as of the nightly count');
  await housekeeping(db,new Date('2026-11-27T00:35:00Z'));
  const next=new Date('2026-11-27T01:00:00Z');
  assert.deepEqual(figures(await summary(db,next,'Asia/Kolkata')).currentVersions,figures(await oldSummary(db,next,'Asia/Kolkata')).currentVersions);
});
test('a load reads a few rows per installation active today; the nightly count does the rest', async () => {
  const {sql}=built(), db=wrapped(sql);
  await housekeeping(db,nightly);
  db.log.length=0;
  await summary(db,evening,'Asia/Kolkata',{shared:true}); await analytics(db,lastDays(30),evening,'Asia/Kolkata',{shared:true});
  // Each activity query reads today's rows only (day BETWEEN today AND today), never a range.
  const heavy=db.log.filter(q=>/FROM activity/.test(q));
  assert.equal(heavy.length,1,heavy.join('\n'));
  assert.ok(!db.log.some(q=>q.includes('WITH legacy')),'current versions are not counted per load');
});

// The fetch handler counts by the real clock: a little history ending today.
async function recentHistory(db) {
  const zone='Asia/Kolkata', today=new Intl.DateTimeFormat('en-CA',{timeZone:zone}).format(new Date());
  const ago=n=>new Date(Date.parse(today)-n*86400000).toISOString().slice(0,10);
  for(let n=10;n<40;n++) await ingest(db,{installationId:`00000000-0000-4000-8000-0000000000${n}`,currentVersion:'1.1.0',versionRevision:1,
    days:[{day:ago(n%9+1),version:'1.1.0',plays:n,timeZone:zone},{day:today,version:'1.1.0',plays:n%4,timeZone:zone}]},new Date());
  return [`/v1/summary?timeZone=Asia%2FKolkata`,`/v1/analytics?from=${ago(29)}&to=${today}&timeZone=Asia%2FKolkata`,
    `/v1/summary`,`/v1/analytics?from=${ago(6)}&to=${today}`];
}
const getPage=(DB,path)=>worker.fetch(new Request('https://example.test'+path,{headers:auth}),{...loginEnv,DB});
test('opening the dashboard again reads one saved row per request, or nothing in the same instance', async () => {
  const sql=new DatabaseSync(':memory:'); sql.exec(schemaText);
  const first=wrapped(sql), paths=await recentHistory(first), pages=[];
  for(const path of paths) { const r=await getPage(first,path); assert.equal(r.status,200); pages.push(await r.json()); }
  first.log.length=0;
  for(const [i,path] of paths.entries()) assert.deepEqual(await (await getPage(first,path)).json(),pages[i]);
  assert.deepEqual(first.log,[],'same instance: from memory');
  const other=wrapped(sql);   // another instance, with its own memory
  for(const [i,path] of paths.entries()) assert.deepEqual(await (await getPage(other,path)).json(),pages[i]);
  assert.equal(other.log.length,paths.length);
  assert.ok(other.log.every(q=>q.includes('FROM report_cache')),other.log.join('\n'));
  // Past the half hour it is worked out again.
  let counted=0; const later=Date.now()+31*60_000;
  await reuse(other,'summary:Asia/Kolkata:'+new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata'}).format(new Date()),e=>later-e.at<30*60_000,async()=>++counted,{now:later});
  assert.equal(counted,1);
});
test('when D1 refuses writes, the dashboard still answers, with the same figures', async () => {
  const {sql}=built(), refused=wrapped(sql,true);
  await housekeeping(refused,nightly);                              // nothing kept, nothing thrown
  assert.equal(sql.prepare('SELECT COUNT(*) AS n FROM report_cache').get().n,0);
  await sameAsBefore(refused,evening,'writes refused');
  const live=new DatabaseSync(':memory:'); live.exec(schemaText);
  const paths=await recentHistory(wrapped(live)), blocked=wrapped(live,true);
  for(const path of paths) {
    const r=await getPage(blocked,path); assert.equal(r.status,200);
    const page=await r.json(); delete page.asOf; delete page.currentVersionsAsOf;
    const fresh=await (await getPage(wrapped(live),path)).json(); delete fresh.asOf; delete fresh.currentVersionsAsOf;
    assert.deepEqual(page,fresh);
  }
});
test('before schema.sql adds the dashboard tables, the same figures are counted directly', async () => {
  const {sql}=built(schemaText.slice(0,schemaText.indexOf("-- For the owner's dashboard"))), db=wrapped(sql);
  assert.throws(()=>sql.prepare('SELECT 1 FROM report_cache').get());
  await housekeeping(db,nightly);
  await sameAsBefore(db,evening,'old schema');
});
test('the deploy check\'s probe signs in and checks the route without reading the database', async () => {
  const untouchable={prepare(){throw new Error('must not query');},batch(){throw new Error('must not query');}};
  for(const path of ['/v1/summary?probe=1','/usage/v1/summary?timeZone=Asia%2FKolkata&probe=1','/v1/analytics?from=2026-01-01&to=2026-01-01&probe=1']) {
    const r=await getPage(untouchable,path); assert.equal(r.status,200); assert.equal((await r.json()).probe,true);
  }
  assert.equal((await getPage(untouchable,'/v1/analytics?from=bad&probe=1')).status,400);
  assert.equal((await worker.fetch(new Request('https://example.test/v1/summary?probe=1'),{...loginEnv,DB:untouchable})).status,401);
});
test('with more versions than fit in columns, the nightly count sorts by version instead, with the same figures', async () => {
  const {sql}=built(), add=sql.prepare('INSERT INTO activity_ist VALUES(?,?,?,?,?)');
  for(let n=0;n<14;n++) add.run(`00000000-0000-4000-8000-${String(n+1).padStart(12,'0')}`,'2026-11-20',`2.0.${n}`,n,'x');
  const db=wrapped(sql);
  await housekeeping(db,nightly);
  assert.ok(db.log.some(q=>/GROUP BY version/.test(q)));
  await sameAsBefore(db,evening,'many versions');
});
