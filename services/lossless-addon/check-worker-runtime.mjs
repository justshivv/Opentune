// CI-only: generated test certificates are trusted only by this isolated local Worker.
// No test root, route, fixture or key is added to the deployed worker.mjs.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { canonical } from './auth.mjs';
const modules = process.env.RUNTIME_MODULES;
const { build } = await import(pathToFileURL(join(modules, 'esbuild/lib/main.js')));
const { Miniflare } = await import(pathToFileURL(join(modules, 'miniflare/dist/src/index.js')));
const source = `
import { X509Certificate } from 'node:crypto';
import { googleTrust, verifyAttestation } from './attestation.mjs';
import { AddonAuthState, withAppVerification } from './auth.mjs';
import { sha256 } from './attestation.mjs';
export { AddonAuthState };
const protectedHandler = withAppVerification(() => Response.json({ accepted: true }));
export default { async fetch(request, env) {
  const path = new URL(request.url).pathname;
  if (path === '/test/session') {
    const input = await request.json();
    const id = env.AUTH.idFromName('session:' + sha256(input.session));
    return env.AUTH.get(id).fetch('https://state/create', { method: 'POST', body: JSON.stringify({ key: input.key, kind: input.kind, expires: Date.now() + 3600000 }) });
  }
  if (path !== '/') return protectedHandler(request, env);
  const fixture = await request.json();
  try {
    await googleTrust();
    const pem = verifyAttestation(fixture.input, { roots: [new X509Certificate(fixture.root)], revoked: {} }, [fixture.signer]);
    return Response.json({ verified: pem.includes('PUBLIC KEY') });
  } catch(e) { return Response.json({ verified:false, error:e.message }, {status:403}); }
} };`;
const bundle = await build({ stdin: { contents: source, resolveDir: resolve('services/lossless-addon'), sourcefile: 'runtime-check-only.mjs' },
  bundle: true, write: false, format: 'esm', platform: 'neutral', external: ['node:*'] });
// The published local workerd binary currently supports dates through 2026-08-06.
// Production keeps its own compatibility date; this checks the Workers crypto APIs.
const fixture = JSON.parse(await readFile(process.env.ATTESTATION_TEST_FIXTURE, 'utf8'));
const worker = new Miniflare({ modules: true, script: bundle.outputFiles[0].text, compatibilityDate: '2026-08-06', compatibilityFlags: ['nodejs_compat'],
  durableObjects: { AUTH: { className: 'AddonAuthState', useSQLite: true } },
  bindings: { VERIFICATION_MODE: 'strict' },
  outboundService: request => {
    const url = new URL(request.url);
    if (url.origin !== 'https://android.googleapis.com') return new Response('Unexpected test URL', { status: 500 });
    return Response.json(url.pathname.endsWith('/root') ? [fixture.root] : { entries: {} });
  } });
try {
  const send = data => worker.dispatchFetch('https://runtime-check.invalid/', { method: 'POST', body: JSON.stringify(data) });
  const valid = await send(fixture); const body = await valid.json();
  assert.equal(valid.status, 200, JSON.stringify(body)); assert.equal(body.verified, true);
  const wrongSigner = await send({ ...fixture, signer: 'cd'.repeat(32) }); assert.equal(wrongSigner.status, 403);
  const wrongProof = await send({ ...fixture, input: { ...fixture.input, proof: 'A'.repeat(86) } }); assert.equal(wrongProof.status, 403);
  console.log('Cloudflare runtime: valid attested fixture accepted; wrong signer and proof rejected.');
  for (const kind of ['native', 'web']) {
    const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const session = randomBytes(32).toString('base64url');
    const created = await worker.dispatchFetch('https://runtime-check.invalid/test/session', { method: 'POST', body: JSON.stringify({
      session, kind, key: keys.publicKey.export({ type: 'spki', format: 'pem' }).toString(),
    }) });
    assert.equal((await created.json()).ok, true);
    const path = '/monochrome/search?q=295%20Sidhu%20Moose%20Wala&quality=best';
    const time = String(Date.now()), nonce = randomBytes(24).toString('base64url');
    const signature = sign('sha256', Buffer.from(canonical(session, time, nonce, 'GET', path)), {
      key: keys.privateKey, dsaEncoding: kind === 'web' ? 'ieee-p1363' : 'der',
    }).toString('base64url');
    const headers = { Authorization: 'Bearer ' + session, 'X-OpenTune-Time': time, 'X-OpenTune-Nonce': nonce, 'X-OpenTune-Signature': signature };
    const accepted = await worker.dispatchFetch('https://runtime-check.invalid' + path, { headers });
    assert.equal(accepted.status, 200, `${kind} signed request: ${await accepted.text()}`);
    const replay = await worker.dispatchFetch('https://runtime-check.invalid' + path, { headers });
    assert.equal(replay.status, 401);
    const tampered = await worker.dispatchFetch('https://runtime-check.invalid' + path + '&changed=1', { headers });
    assert.equal(tampered.status, 401);
    console.log(`Cloudflare runtime: ${kind} signed request accepted; replay and tampering rejected through real Durable Objects.`);
  }
} finally { await worker.dispose(); }
