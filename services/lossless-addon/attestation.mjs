import { X509Certificate, createHash, verify } from 'node:crypto';
import { Buffer } from 'node:buffer';

export class AttestationFailure extends Error {
  constructor(code) { super('Official hardware-backed OpenTune verification failed'); this.code = code; }
}
const fail = (code = 'ATTESTATION_INVALID') => { throw new AttestationFailure(code); };
const hex = bytes => Buffer.from(bytes).toString('hex');
export const sha256 = value => createHash('sha256').update(value).digest('hex');

// Bounded DER reader for the Android extension, not a replacement X.509 verifier.
// Certificate signatures and keys are handled by node:crypto/OpenSSL in Workers.
export function der(bytes) {
  const b = Buffer.from(bytes);
  function read(offset, end, depth) {
    if (depth > 16 || offset >= end) fail();
    let p = offset, tag = b[p++], cls = tag >> 6, constructed = !!(tag & 32), number = tag & 31;
    if (number === 31) {
      number = 0; let n = 0, v;
      do { if (p >= end || ++n > 4) fail(); v = b[p++]; number = number * 128 + (v & 127); } while (v & 128);
      if (number < 31) fail();
    }
    if (p >= end) fail();
    let length = b[p++];
    if (length & 128) {
      const count = length & 127; if (!count || count > 4 || p + count > end || !b[p]) fail();
      length = 0; for (let n = 0; n < count; n++) length = length * 256 + b[p++];
      if (length < 128) fail();
    }
    const stop = p + length; if (stop > end) fail();
    const node = { cls, number, constructed, value: b.subarray(p, stop), children: [], end: stop };
    if (constructed) while (p < stop) { const child = read(p, stop, depth + 1); node.children.push(child); p = child.end; }
    return node;
  }
  const root = read(0, b.length, 0); if (root.end !== b.length) fail(); return root;
}
function typed(n, tag) { if (!n || n.cls !== 0 || n.number !== tag) fail(); return n; }
function integer(n) { if (!n || n.cls || ![2, 10].includes(n.number) || !n.value.length || n.value.length > 6 || (n.value[0] & 128)) fail(); return [...n.value].reduce((a, b) => a * 256 + b, 0); }
function authorization(n, tag) {
  const matches = typed(n, 16).children.filter(v => v.cls === 2 && v.number === tag);
  if (matches.length !== 1 || matches[0].children.length !== 1) fail(); return matches[0].children[0];
}
export function attestationExtension(certificate) {
  const tbs = typed(der(certificate), 16).children[0];
  const containers = typed(tbs, 16).children.filter(n => n.cls === 2 && n.number === 3);
  if (!containers.length) return null;
  if (containers.length !== 1 || containers[0].children.length !== 1) fail();
  const matches = typed(containers[0].children[0], 16).children.filter(n =>
    hex(typed(typed(n, 16).children[0], 6).value) === '2b06010401d679020111');
  if (!matches.length) return null;
  if (matches.length !== 1) fail();
  return typed(matches[0].children.at(-1), 4).value;
}
export function validateDescription(bytes, challenge, signerDigests) {
  const values = typed(der(bytes), 16).children;
  if (values.length !== 8) fail();
  if (![1, 2].includes(integer(values[1])) || ![1, 2].includes(integer(values[3]))) fail('HARDWARE_KEY_REQUIRED');
  if (typed(values[4], 4).value.toString('base64url') !== challenge) fail('CHALLENGE_MISMATCH');
  const software = values[6], hardware = values[7];
  const root = typed(authorization(hardware, 704), 16).children;
  const locked = typed(root[1], 1).value;
  if (locked.length !== 1 || locked[0] !== 255 || integer(root[2]) !== 0) fail('LOCKED_VERIFIED_BOOT_REQUIRED');
  // Origin GENERATED (0); the private key must be created inside the hardware.
  if (integer(authorization(hardware, 702)) !== 0) fail();
  const purposes = typed(authorization(hardware, 1), 17).children.map(integer);
  if (!purposes.includes(2)) fail();
  const app = typed(der(typed(authorization(software, 709), 4).value), 16).children;
  if (app.length !== 2) fail();
  const packages = typed(app[0], 17).children, signers = typed(app[1], 17).children;
  if (packages.length !== 1 || signers.length !== 1) fail();
  const pkg = typed(packages[0], 16).children;
  if (pkg.length !== 2 || typed(pkg[0], 4).value.toString('utf8') !== 'com.opentune') fail('APP_ID_MISMATCH');
  integer(pkg[1]);
  if (!signerDigests.includes(hex(typed(signers[0], 4).value))) fail('APP_SIGNER_MISMATCH');
}
let trustCache;
async function boundedJson(url, fetcher) {
  const part = url.endsWith('/root') ? 'ROOTS' : 'REVOCATIONS';
  let r;
  // Workers fetch does not support redirect:'error'. Manual mode plus the status
  // check below refuses redirects without rejecting every trust request locally.
  try { r = await fetcher(url, { redirect: 'manual', signal: AbortSignal.timeout(7000) }); }
  catch { throw new Error(`ATTESTATION_${part}_NETWORK`); }
  if (!r.ok || !r.body) throw new Error(`ATTESTATION_${part}_HTTP_${r.status}`);
  const reader = r.body.getReader(), parts = []; let size = 0;
  try { for (;;) { const { value, done } = await reader.read(); if (done) break; size += value.length; if (size > 2_097_152) throw new Error(`ATTESTATION_${part}_TOO_LARGE`); parts.push(Buffer.from(value)); } }
  finally { await reader.cancel().catch(() => {}); }
  try { return JSON.parse(Buffer.concat(parts).toString('utf8')); }
  catch { throw new Error(`ATTESTATION_${part}_INVALID_JSON`); }
}
export async function googleTrust(fetcher = fetch) {
  if (trustCache?.until > Date.now()) return trustCache;
  const [roots, revocations] = await Promise.all([
    boundedJson('https://android.googleapis.com/attestation/root', fetcher),
    boundedJson('https://android.googleapis.com/attestation/status', fetcher),
  ]);
  if (!Array.isArray(roots) || !roots.length || !revocations?.entries || typeof revocations.entries !== 'object') throw new Error('ATTESTATION_TRUST_INVALID_SCHEMA');
  let certificates;
  try { certificates = roots.map(pem => new X509Certificate(pem)); } catch { throw new Error('ATTESTATION_ROOTS_INVALID_CERTIFICATE'); }
  trustCache = { roots: certificates, revoked: revocations.entries, until: Date.now() + 3600_000 };
  return trustCache;
}
/** trust is passed by the server, never from enrollment JSON. */
export function verifyAttestation(input, { roots, revoked }, signerDigests, now = Date.now()) {
  if (!Array.isArray(input.chain) || input.chain.length < 2 || input.chain.length > 6 || !/^[A-Za-z0-9_-]{43}$/.test(input.challenge || '') || !signerDigests.length) fail();
  const chain = input.chain.map(value => {
    if (typeof value !== 'string' || value.length > 12000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) fail();
    return new X509Certificate(Buffer.from(value, 'base64'));
  });
  const anchor = chain.at(-1);
  if (!roots.some(root => root.raw.equals(anchor.raw))) fail('UNTRUSTED_ATTESTATION_ROOT');
  for (let i = 0; i < chain.length; i++) {
    const cert = chain[i];
    if (!(Date.parse(cert.validFrom) <= now && now <= Date.parse(cert.validTo))) fail('ATTESTATION_CERTIFICATE_EXPIRED');
    const encodedSerial = cert.serialNumber.toLowerCase();
    const serial = encodedSerial.replace(/^0+/, '') || '0';
    if (revoked[encodedSerial] || revoked[serial] || revoked[BigInt('0x' + serial).toString()]) fail('ATTESTATION_CERTIFICATE_REVOKED');
    if (i > 0 && !cert.ca) fail();
    const parent = chain[Math.min(i + 1, chain.length - 1)];
    if (cert.issuer !== parent.subject || !cert.verify(parent.publicKey)) fail();
  }
  // Reject delegated/software leaves: the first attestation seen FROM THE ROOT
  // must describe the actual enrollment key, not an ancestor's key.
  const descriptions = chain.map(c => attestationExtension(c.raw));
  if (descriptions.findLastIndex(Boolean) !== 0 || chain[0].ca) fail();
  validateDescription(descriptions[0], input.challenge, signerDigests);
  const key = chain[0].publicKey;
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') fail();
  if (typeof input.proof !== 'string' || input.proof.length > 128 || !verify('sha256', Buffer.from('OpenTune-Enrollment-v1\n' + input.challenge), key, Buffer.from(input.proof, 'base64url'))) fail();
  return key.export({ type: 'spki', format: 'pem' }).toString();
}
