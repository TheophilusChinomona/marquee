'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { randomUUID } = require('node:crypto');
const { Store } = require('../recommendations/store');

function setup(t) {
  const dir = mkdtempSync(join(tmpdir(), 'marquee-rec-'));
  const store = new Store(join(dir, 'state.sqlite'));
  t.after(() => { store.close(); rmSync(dir, { recursive: true, force: true }); });
  const deviceId = randomUUID();
  const profile = store.register(deviceId, [{ localId: 'default', name: 'Theo' }])[0];
  return { store, deviceId, profile, dir };
}
const update = (p, extra = {}) => ({ profileId: p.profileId, epoch: p.epoch,
  sessionId: 'session-1', revision: 1, kind: 'tv', sourceId: 'gd:tv:/t123',
  title: 'Boston Legal', year: 2004, playedMs: 25 * 60000, durationMs: 40 * 60000,
  completed: false, updatedAt: Date.now(), ...extra });

test('registration is stable and does not merge same-name profiles on other devices', t => {
  const { store, deviceId, profile } = setup(t);
  assert.equal(store.register(deviceId, [{ localId: 'default', name: 'Theo' }])[0].profileId, profile.profileId);
  const second = store.register(randomUUID(), [{ localId: 'default', name: 'Theo' }])[0];
  assert.notEqual(second.profileId, profile.profileId);
  const desktop = randomUUID(); store.register(desktop, []);
  assert.equal(store.link(desktop, 'desktop', profile.profileId).profileId, profile.profileId);
  assert.throws(() => store.link(desktop, 'desktop', second.profileId), /mapping/i);
});
test('session revisions are idempotent, completed seeds persist, reset rejects offline replay', t => {
  const { store, deviceId, profile } = setup(t);
  store.batch(deviceId, [update(profile, { playedMs: 35 * 60000, completed: true, revision: 2 })]);
  store.batch(deviceId, [update(profile), update(profile)]);
  assert.equal(store.seeds(profile.profileId)[0].playedMs, 35 * 60000);
  assert.equal(store.seeds(profile.profileId)[0].completed, true);
  const nextEpoch = store.reset(profile.profileId);
  assert.equal(nextEpoch, profile.epoch + 1);
  assert.equal(store.seeds(profile.profileId).length, 0);
  assert.throws(() => store.batch(deviceId, [update(profile)]), e => e.status === 409 && e.epoch === nextEpoch);
});
test('batch is atomic across epochs and forbids writing an unlinked profile', t => {
  const { store, deviceId, profile } = setup(t);
  const other = store.register(randomUUID(), [{ localId: 'me', name: 'Other' }])[0];
  assert.throws(() => store.batch(deviceId, [update(profile), update(other)]), e => e.status === 403);
  assert.equal(store.seeds(profile.profileId).length, 0);
});
test('profile and viewing evidence survive reopening the database', t => {
  const { store, deviceId, profile, dir } = setup(t);
  store.batch(deviceId, [update(profile)]); store.close();
  const reopened = new Store(join(dir, 'state.sqlite'));
  assert.equal(reopened.profiles()[0].id, profile.profileId);
  assert.equal(reopened.seeds(profile.profileId)[0].title, 'Boston Legal');
  reopened.close();
});
test('seek-only evidence is weak and episode sessions do not create independent series seeds', t => {
  const { store, deviceId, profile } = setup(t);
  store.batch(deviceId, [update(profile, { playedMs: 10000, completed: true }),
    update(profile, { sessionId: 'episode-2', playedMs: 1500000 }),
    update(profile, { sessionId: 'episode-3', playedMs: 1500000 })]);
  assert.equal(store.seeds(profile.profileId).length, 1);
  assert.equal(store.seeds(profile.profileId)[0].completed, false);
});
test('known online identities exclude translated titles even outside the seed window',t=>{
  const {store,deviceId,profile}=setup(t);
  store.batch(deviceId,[update(profile,{sourceId:'fx:tv:71446',title:'La casa de papel'})]);
  assert.equal(store.seen(profile.profileId,'tv',71446,'Money Heist',2017),true);
});
test('decorated and accented titles use normalized watched exclusion',t=>{
  const {store,deviceId,profile}=setup(t);
  store.batch(deviceId,[update(profile,{title:'EN| Café [HD] - 2004'})]);
  assert.equal(store.seen(profile.profileId,'tv',555,'Cafe',2004),true);
});
