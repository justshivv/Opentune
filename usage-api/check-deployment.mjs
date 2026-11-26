// Read-only checks: never send synthetic activity reports or print credentials.
import assert from 'node:assert/strict';
const base='https://opentune-usage.create-a-new-site-with-sites.workers.dev';
const read=(path,options={})=>fetch(base+path,{redirect:'manual',signal:AbortSignal.timeout(20000),...options});
// Secret updates propagate separately from code. Retry only temporary 503s;
// incorrect credentials and rate limits must still fail immediately.
async function signIn(headers) {
  for(let attempt=0;;attempt++) {
    const response=await read('/auth/login',{method:'POST',headers:{...headers,'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:process.env.USAGE_ADMIN_USERNAME,password:process.env.USAGE_ADMIN_PASSWORD})});
    if(response.status!==503 || attempt===5) return response;
    await response.arrayBuffer();
    await new Promise(resolve=>setTimeout(resolve,5000));
  }
}
// Allow brief edge propagation after publishing, but never accept an open API.
for(let attempt=0;;attempt++) {
  try {
    for(const path of ['/usage/','/usage/index.html','/usage/assets.json','/v1/summary','/usage/v1/summary','/v1/analytics?from=2026-01-01&to=2026-01-01']) {
      const response=await read(path);
      if(['/usage/','/usage/index.html'].includes(path)) {
        assert.equal(response.status,303);assert.equal(response.headers.get('location'),'/login');
      } else assert.equal(response.status,401,`Unauthenticated data route must be locked: ${path}`);
      assert.match(response.headers.get('cache-control'),/no-store/);
    }
    const page=await read('/login');assert.equal(page.status,200);
    assert.ok((await page.text()).includes('name="password"'));
    break;
  } catch(error) { if(attempt===5) throw error;await new Promise(resolve=>setTimeout(resolve,5000)); }
}
const pagesPreflight=await read('/usage/v1/summary',{method:'OPTIONS',headers:{Origin:'https://justshivv.github.io','Access-Control-Request-Headers':'authorization'}});
assert.equal(pagesPreflight.status,204);
assert.match(pagesPreflight.headers.get('Access-Control-Allow-Headers'),/Authorization/);
const blocked=await read('/usage/v1/summary',{headers:{Origin:'https://justshivv.github.io'}});
assert.equal(blocked.status,401);
assert.equal(blocked.headers.get('Access-Control-Allow-Origin'),'https://justshivv.github.io');
const preflight=await read('/v1/activity',{method:'OPTIONS',headers:{Origin:'https://justshivv.github.io'}});
assert.equal(preflight.status,204);
if(process.env.USAGE_ADMIN_USERNAME && process.env.USAGE_ADMIN_PASSWORD) {
  const login=await signIn({Origin:base});
  assert.equal(login.status,303);
  const cookie=login.headers.get('set-cookie');assert.ok(cookie?.includes('HttpOnly'));
  const headers={Cookie:cookie.split(';')[0]};
  // ?probe=1 answers once the session and the route's parameters check out, without counting
  // anything: a deploy costs no database reads (the dashboard's figures are tested in CI).
  assert.equal((await read('/usage/',{headers})).status,200);
  const cookieProbe=await read('/usage/v1/summary?probe=1',{headers});
  assert.equal(cookieProbe.status,200);assert.equal((await cookieProbe.json()).probe,true);
  const logout=await read('/auth/logout',{method:'POST',headers:{...headers,Origin:base}});
  assert.match(logout.headers.get('set-cookie'),/Max-Age=0/);
  const pagesLogin=await signIn({Origin:'https://justshivv.github.io',Accept:'application/json'});
  assert.equal(pagesLogin.status,200);
  assert.equal(pagesLogin.headers.get('set-cookie'),null);
  const {token}=await pagesLogin.json();
  const bearer={Origin:'https://justshivv.github.io',Authorization:'Bearer '+token};
  const pagesRead=await read('/usage/v1/summary?timeZone=Asia%2FKolkata&probe=1',{headers:bearer});
  assert.equal(pagesRead.status,200);
  assert.equal(pagesRead.headers.get('Access-Control-Allow-Origin'),'https://justshivv.github.io');
  assert.deepEqual(await pagesRead.json(),{probe:true,timeZone:'Asia/Kolkata'});
  const todayIst = new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
  const istRead=await read(`/usage/v1/analytics?from=${todayIst}&to=${todayIst}&timeZone=Asia%2FKolkata&probe=1`,{headers:bearer});
  assert.equal(istRead.status,200);
  assert.equal((await istRead.json()).timeZone,'Asia/Kolkata');
  // A bad range is still refused before anything is read.
  assert.equal((await read('/usage/v1/analytics?from=bad&probe=1',{headers:bearer})).status,400);
  console.log('Verified Pages bearer login, Worker cookie login, private routes (probed, no database reads) and anonymous rejection.');
} else console.log('Verified login form and anonymous rejection. Add owner credentials to enable sign-in.');
