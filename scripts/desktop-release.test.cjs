const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { normalizeVersion, prepareDesktop, stageDesktop, createManifest, releaseFiles } = require('./desktop-release.cjs');

test('shared desktop version normalizes legacy Android versions', () => {
  assert.equal(normalizeVersion('1.15'), '1.15.0');
  assert.equal(normalizeVersion('1.15.3'), '1.15.3');
  for (const value of ['bad', '1.2.3.4', '01.2.3', '1.2-beta', '../1.2']) {
    assert.throws(() => normalizeVersion(value), /version/i);
  }
});

function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'marquee-release-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  for (const name of releaseFiles) fs.writeFileSync(path.join(dir, name), 'artifact');
  const sig = Buffer.from('untrusted comment: signature\nRWQsignature\n').toString('base64');
  fs.writeFileSync(path.join(dir, 'Marquee-windows-x86_64-setup.exe.sig'), sig);
  fs.writeFileSync(path.join(dir, 'Marquee-linux-x86_64.AppImage.sig'), sig);
  fs.writeFileSync(path.join(dir, 'version.json'), JSON.stringify({ versionName: '1.15.3', versionCode: 20,
    apkUrl: 'https://github.com/owner/repo/releases/download/v1.15.3/Marquee.apk' }));
  return { dir, sig };
}

test('desktop manifest points both platforms at the same immutable release', t => {
  const { dir, sig } = fixture(t);
  const manifest = createManifest(dir, '1.15.3', 'owner/repo', 'v1.15.3');
  assert.equal(manifest.version, '1.15.3');
  assert.deepEqual(Object.keys(manifest.platforms), ['windows-x86_64', 'linux-x86_64']);
  assert.equal(manifest.platforms['windows-x86_64'].signature, sig);
  assert.equal(manifest.platforms['linux-x86_64'].url,
    'https://github.com/owner/repo/releases/download/v1.15.3/Marquee-linux-x86_64.AppImage');
});

test('missing platforms, empty signatures and mismatched Android versions block release', t => {
  const { dir } = fixture(t);
  const deb = path.join(dir, 'Marquee-linux-x86_64.deb');
  fs.unlinkSync(deb);
  assert.throws(() => createManifest(dir, '1.15.3', 'owner/repo', 'v1.15.3'), /missing/i);
  fs.writeFileSync(deb, 'artifact');
  const signature = path.join(dir, 'Marquee-linux-x86_64.AppImage.sig');
  fs.writeFileSync(signature, '');
  assert.throws(() => createManifest(dir, '1.15.3', 'owner/repo', 'v1.15.3'), /empty/i);
  fs.writeFileSync(signature, 'not-a-signature');
  assert.throws(() => createManifest(dir, '1.15.3', 'owner/repo', 'v1.15.3'), /signature/i);
  assert.throws(() => createManifest(dir, '1.15.4', 'owner/repo', 'v1.15.4'), /version/i);
});

test('release preparation synchronizes executable, frontend and lockfile versions', t => {
  const { dir } = fixture(t);
  fs.mkdirSync(path.join(dir, 'desktop/src-tauri'), { recursive: true });
  for (const file of ['desktop/package.json', 'desktop/package-lock.json', 'desktop/src-tauri/tauri.conf.json']) {
    fs.writeFileSync(path.join(dir, file), JSON.stringify({ version: '0.1.0', packages: { '': { version: '0.1.0' } } }));
  }
  fs.writeFileSync(path.join(dir, 'desktop/src-tauri/Cargo.toml'), '[package]\nname = "desktop"\nversion = "0.1.0"\n');
  fs.writeFileSync(path.join(dir, 'desktop/src-tauri/Cargo.lock'), '[[package]]\nname = "desktop"\nversion = "0.1.0"\n\n[[package]]\nname = "other"\nversion = "0.1.0"\n');
  prepareDesktop('1.15', dir);
  prepareDesktop('1.15', dir);
  assert.equal(JSON.parse(fs.readFileSync(path.join(dir, 'desktop/package-lock.json'))).packages[''].version, '1.15.0');
  assert.match(fs.readFileSync(path.join(dir, 'desktop/src-tauri/Cargo.toml'), 'utf8'), /version = "1.15.0"/);
  const lock = fs.readFileSync(path.join(dir, 'desktop/src-tauri/Cargo.lock'), 'utf8');
  assert.match(lock, /name = "desktop"\nversion = "1.15.0"/);
  assert.match(lock, /name = "other"\nversion = "0.1.0"/);
});

test('staging requires exactly one installer and a signature for release builds', t => {
  const { dir } = fixture(t);
  const bundle = path.join(dir, 'desktop/src-tauri/target/release/bundle/nsis');
  fs.mkdirSync(bundle, { recursive: true });
  fs.writeFileSync(path.join(bundle, 'Marquee_1.2.3_x64-setup.exe'), 'exe');
  assert.throws(() => stageDesktop('windows', true, dir), /ENOENT/);
  fs.writeFileSync(path.join(bundle, 'Marquee_1.2.3_x64-setup.exe.sig'), 'signature');
  stageDesktop('windows', true, dir);
  assert.equal(fs.readFileSync(path.join(dir, 'desktop/release/Marquee-windows-x86_64-setup.exe'), 'utf8'), 'exe');
  fs.writeFileSync(path.join(bundle, 'other.exe'), 'stale');
  assert.throws(() => stageDesktop('windows', true, dir), /Expected one/);
});
