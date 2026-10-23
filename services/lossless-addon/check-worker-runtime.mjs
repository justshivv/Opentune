// CI-only: generated test certificates are trusted only by this isolated local Worker.
// No test root, route, fixture or key is added to the deployed worker.mjs.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
const modules = process.env.RUNTIME_MODULES;
const { build } = await import(pathToFileURL(join(modules, 'esbuild/lib/main.js')));
const { Miniflare } = await import(pathToFileURL(join(modules, 'miniflare/dist/src/index.js')));
const source = `
import { X509Certificate } from 'node:crypto';
import { verifyAttestation } from './attestation.mjs';
export default { async fetch(request) {
  const fixture = await request.json();
  try {
    const pem = verifyAttestation(fixture.input, { roots: [new X509Certificate(fixture.root)], revoked: {} }, [fixture.signer]);
    return Response.json({ verified: pem.includes('PUBLIC KEY') });
  } catch(e) { return Response.json({ verified:false, error:e.message }, {status:403}); }
} };`;
const bundle = await build({ stdin: { contents: source, resolveDir: resolve('services/lossless-addon'), sourcefile: 'runtime-check-only.mjs' },
  bundle: true, write: false, format: 'esm', platform: 'neutral', external: ['node:*'] });
const worker = new Miniflare({ modules: true, script: bundle.outputFiles[0].text, compatibilityDate: '2026-10-23', compatibilityFlags: ['nodejs_compat'] });
try {
  const fixture = JSON.parse(await readFile(process.env.ATTESTATION_TEST_FIXTURE, 'utf8'));
  const send = data => worker.dispatchFetch('https://runtime-check.invalid/', { method: 'POST', body: JSON.stringify(data) });
  const valid = await send(fixture); const body = await valid.json();
  assert.equal(valid.status, 200, JSON.stringify(body)); assert.equal(body.verified, true);
  const wrongSigner = await send({ ...fixture, signer: 'cd'.repeat(32) }); assert.equal(wrongSigner.status, 403);
  const wrongProof = await send({ ...fixture, input: { ...fixture.input, proof: 'A'.repeat(86) } }); assert.equal(wrongProof.status, 403);
  console.log('Cloudflare runtime: valid attested fixture accepted; wrong signer and proof rejected.');
} finally { await worker.dispose(); }
