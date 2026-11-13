import { test } from 'node:test';
import assert from 'node:assert/strict';
import { presetRange, rangeError, dailyRows, legacyAnalytics } from './model.mjs';
test('UTC presets cross year and leap-day boundaries inclusively', () => {
  assert.deepEqual(presetRange(7,new Date('2027-01-02T23:59:00Z'),'UTC'),{from:'2026-12-27',to:'2027-01-02'});
  assert.deepEqual(presetRange(2,new Date('2024-03-01T00:01:00Z'),'UTC'),{from:'2024-02-29',to:'2024-03-01'});
  assert.ok(rangeError({from:'2026-02-30',to:'2026-03-01'}));
  assert.ok(rangeError({from:'2026-03-01',to:'2026-02-28'}));
});
test('missing reports during collection are zero; prehistory is unavailable', () => {
  const rows = dailyRows({range:{from:'2026-10-01',to:'2026-10-04'},coverageSince:'2026-10-02',daily:[{day:'2026-10-02',active:2,plays:3}]});
  assert.deepEqual(rows.map(row=>row.active),[null,2,0,0]);
  assert.deepEqual(rows.map(row=>row.plays),[null,3,0,0]);
});
test('legacy fallback never fabricates period unique users or older history', () => {
  const summary = {asOf:'2026-10-09T12:00:00Z',totals:{since:'2026-09-01'},daily:[{day:'2026-10-08',active:1,plays:3},{day:'2026-10-09',active:1,plays:4}]};
  const data=legacyAnalytics(summary,{from:'2026-10-08',to:'2026-10-09'});
  assert.equal(data.totals.active,null); assert.equal(data.totals.plays,7); assert.equal(data.versions,null);
  assert.equal(legacyAnalytics(summary,{from:'2026-10-09',to:'2026-10-09'}).totals.active,1);
  assert.throws(()=>legacyAnalytics(summary,{from:'2026-08-01',to:'2026-10-09'}));
});

test('IST is the default and changes calendar day at 18:30 UTC', () => {
  assert.deepEqual(presetRange(1,new Date('2026-12-31T18:29:59Z')),{from:'2026-12-31',to:'2026-12-31'});
  assert.deepEqual(presetRange(1,new Date('2026-12-31T18:30:00Z')),{from:'2027-01-01',to:'2027-01-01'});
  assert.equal(rangeError({from:'2027-01-01',to:'2027-01-01'},new Date('2026-12-31T18:30:00Z')),'');
  assert.ok(rangeError({from:'2027-01-01',to:'2027-01-01'},new Date('2026-12-31T18:30:00Z'),'UTC'));
});

test('days carry the new and returning split when the server reports it', () => {
  const rows = dailyRows({range:{from:'2026-10-01',to:'2026-10-03'},coverageSince:'2026-10-02',
    daily:[{day:'2026-10-02',active:5,plays:9}],
    cohorts:[{day:'2026-10-02',newInstallations:2,returningInstallations:3,newPlays:4,returningPlays:5,listeners:5}]});
  assert.deepEqual(rows.map(r=>[r.newInstallations,r.returningInstallations]),[[null,null],[2,3],[0,0]]);
  assert.equal(rows[1].newInstallations + rows[1].returningInstallations, rows[1].active);
  // Older servers without the split leave it out rather than inventing zeros.
  assert.equal(dailyRows({range:{from:'2026-10-02',to:'2026-10-02'},coverageSince:'2026-10-01',daily:[]})[0].newInstallations, undefined);
});
