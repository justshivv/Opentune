import test from 'node:test';
import assert from 'node:assert/strict';
import { X509Certificate, randomBytes, sign, createPrivateKey } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { Buffer } from 'node:buffer';
import { der, validateDescription, verifyAttestation } from './attestation.mjs';

const bytes = s => Buffer.from(s), concat = list => Buffer.concat(list);
function tlv(tag, data) {
  const n = data.length;
  return concat([Buffer.from(Array.isArray(tag) ? tag : [tag]), Buffer.from(n < 128 ? [n] : n < 256 ? [129, n] : [130, n >> 8, n & 255]), data]);
}
const seq = (...n) => tlv(48, concat(n)), set = (...n) => tlv(49, concat(n)), octet = n => tlv(4, n), int = n => tlv(2, Buffer.from([n])), en = n => tlv(10, Buffer.from([n]));
const tagged = (tag, value) => tlv(tag < 31 ? [160 + tag] : [191, 128 + (tag >> 7), tag & 127], value);
const challenge = randomBytes(32).toString('base64url'), signer = 'ab'.repeat(32);
function description({ security = 1, locked = true, boot = 0, packageName = 'com.opentune', digest = signer, c = challenge, origin = 0 } = {}) {
  return seq(int(100), en(security), int(100), en(security), octet(Buffer.from(c, 'base64url')), octet(Buffer.alloc(0)),
    seq(tagged(709, octet(seq(set(seq(octet(bytes(packageName)), int(44))), set(octet(Buffer.from(digest, 'hex'))))))),
    seq(tagged(1, set(int(2))), tagged(702, int(origin)), tagged(704, seq(octet(Buffer.alloc(32)), tlv(1, Buffer.from([locked ? 255 : 0])), en(boot), octet(Buffer.alloc(32))))));
}
test('description requires exact app/signer, fresh challenge, hardware key and locked verified boot', () => {
  validateDescription(description(), challenge, [signer]);
  for (const change of [{ security: 0 }, { locked: false }, { boot: 1 }, { boot: 2 }, { packageName: 'com.fake.opentune' }, { digest: 'cd'.repeat(32) }, { origin: 2 }, { c: randomBytes(32).toString('base64url') }]) {
    assert.throws(() => validateDescription(description(change), challenge, [signer]));
  }
  assert.throws(() => validateDescription(description(), challenge, []));
});
test('bounded DER rejects malformed/trailing/indefinite/truncated data', () => {
  for (const b of [[48, 128, 0, 0], [48, 3, 2, 1], [48, 0, 0], [191, 255, 255, 255, 255, 127, 0], [48, 255]]) assert.throws(() => der(Buffer.from(b)));
});
test('real certificate chain/proof checks reject wrong roots, revocation, expired certificates and altered signatures', () => {
  const dir = mkdtempSync(join(tmpdir(), 'opentune-attestation-test-'));
  const run = args => execFileSync('openssl', args, { cwd: dir, stdio: 'pipe' });
  try {
    run(['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'root.key', '-out', 'root.pem', '-days', '2', '-subj', '/CN=Test only root', '-addext', 'basicConstraints=critical,CA:TRUE']);
    run(['req', '-new', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes', '-keyout', 'leaf.key', '-out', 'leaf.csr', '-subj', '/CN=Android Keystore Key']);
    writeFileSync(join(dir, 'extensions'), 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n1.3.6.1.4.1.11129.2.1.17=DER:' + description().toString('hex').match(/../g).join(':'));
    run(['x509', '-req', '-in', 'leaf.csr', '-CA', 'root.pem', '-CAkey', 'root.key', '-CAcreateserial', '-out', 'leaf.pem', '-days', '1', '-extfile', 'extensions']);
    const root = new X509Certificate(readFileSync(join(dir, 'root.pem'))), leaf = new X509Certificate(readFileSync(join(dir, 'leaf.pem')));
    const input = { challenge, chain: [leaf, root].map(c => c.raw.toString('base64')), proof: sign('sha256', bytes('OpenTune-Enrollment-v1\n' + challenge), createPrivateKey(readFileSync(join(dir, 'leaf.key')))).toString('base64url') };
    const trust = { roots: [root], revoked: {} };
    assert.match(verifyAttestation(input, trust, [signer]), /PUBLIC KEY/);
    assert.throws(() => verifyAttestation(input, { roots: [], revoked: {} }, [signer]));
    assert.throws(() => verifyAttestation(input, { ...trust, revoked: { [leaf.serialNumber.toLowerCase()]: { status: 'REVOKED' } } }, [signer]));
    assert.throws(() => verifyAttestation(input, trust, [signer], Date.now() + 3 * 86400000));
    assert.throws(() => verifyAttestation({ ...input, proof: randomBytes(64).toString('base64url') }, trust, [signer]));
    assert.throws(() => verifyAttestation({ ...input, chain: [input.chain[0], input.chain[0]] }, trust, [signer]));
    assert.throws(() => verifyAttestation({ ...input, challenge: randomBytes(32).toString('base64url') }, trust, [signer]));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
