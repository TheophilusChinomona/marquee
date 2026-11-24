'use strict';
const fs = require('node:fs');
const path = require('node:path');

const releaseFiles = [
  'Marquee.apk', 'version.json',
  'Marquee-windows-x86_64-setup.exe', 'Marquee-windows-x86_64-setup.exe.sig',
  'Marquee-linux-x86_64.AppImage', 'Marquee-linux-x86_64.AppImage.sig',
  'Marquee-linux-x86_64.deb',
];

function normalizeVersion(value) {
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?$/.test(value)) {
    throw new Error('Invalid release version');
  }
  const parts = value.split('.');
  if (parts.some(part => !Number.isSafeInteger(Number(part)) || Number(part) > 65535)) {
    throw new Error('Release version exceeds installer limits');
  }
  return parts.length === 2 ? `${value}.0` : value;
}

function prepareDesktop(value, root = process.cwd()) {
  const version = normalizeVersion(value);
  for (const file of ['desktop/package.json', 'desktop/package-lock.json', 'desktop/src-tauri/tauri.conf.json']) {
    const filename = path.join(root, file);
    const data = JSON.parse(fs.readFileSync(filename, 'utf8'));
    data.version = version;
    if (file.endsWith('tauri.conf.json')) {
      data.bundle ??= {};
      data.bundle.createUpdaterArtifacts = true;
    }
    if (data.packages?.['']) data.packages[''].version = version;
    fs.writeFileSync(filename, JSON.stringify(data, null, 2) + '\n');
  }
  for (const file of ['desktop/src-tauri/Cargo.toml', 'desktop/src-tauri/Cargo.lock']) {
    const filename = path.join(root, file);
    const source = fs.readFileSync(filename, 'utf8');
    const packageVersion = /(name = "desktop"\r?\nversion = ")[^"]+("\r?\n)/;
    if (!packageVersion.test(source)) throw new Error(`Unable to set desktop version in ${file}`);
    const updated = source.replace(packageVersion, (_, prefix, suffix) => `${prefix}${version}${suffix}`);
    fs.writeFileSync(filename, updated);
  }
  return version;
}

function stageDesktop(platform, signed, root = process.cwd()) {
  const output = path.join(root, 'desktop/release');
  fs.mkdirSync(output, { recursive: true });
  const types = platform === 'windows' ? [['nsis', '.exe', 'Marquee-windows-x86_64-setup.exe']]
    : platform === 'linux' ? [
      ['appimage', '.AppImage', 'Marquee-linux-x86_64.AppImage'],
      ['deb', '.deb', 'Marquee-linux-x86_64.deb'],
    ] : null;
  if (!types) throw new Error('Unsupported desktop platform');
  for (const [folder, extension, name] of types) {
    const directory = path.join(root, 'desktop/src-tauri/target/release/bundle', folder);
    const files = fs.readdirSync(directory).filter(file => file.endsWith(extension));
    if (files.length !== 1) throw new Error(`Expected one ${extension} installer, found ${files.length}`);
    const source = path.join(directory, files[0]);
    fs.copyFileSync(source, path.join(output, name));
    if (signed && extension !== '.deb') fs.copyFileSync(`${source}.sig`, path.join(output, `${name}.sig`));
  }
}

function createManifest(directory, value, repo, tag) {
  const version = normalizeVersion(value);
  if (tag !== `v${version}`) throw new Error('Release tag/version mismatch');
  if (!/^[\w.-]+\/[\w.-]+$/.test(repo)) throw new Error('Invalid repository');
  for (const name of releaseFiles) {
    const filename = path.join(directory, name);
    if (!fs.existsSync(filename)) throw new Error(`Missing release asset: ${name}`);
    if (fs.statSync(filename).size === 0) throw new Error(`Empty release asset: ${name}`);
  }
  const android = JSON.parse(fs.readFileSync(path.join(directory, 'version.json'), 'utf8'));
  if (android.versionName !== version) throw new Error('Android/desktop version mismatch');
  const base = `https://github.com/${repo}/releases/download/${tag}`;
  if (android.apkUrl !== `${base}/Marquee.apk`) throw new Error('Android release URL mismatch');
  const platforms = {};
  for (const [platform, asset] of [
    ['windows-x86_64', 'Marquee-windows-x86_64-setup.exe'],
    ['linux-x86_64', 'Marquee-linux-x86_64.AppImage'],
  ]) {
    const signature = fs.readFileSync(path.join(directory, `${asset}.sig`), 'utf8').trim();
    if (!/^[A-Za-z0-9+/=]+$/.test(signature) ||
        !Buffer.from(signature, 'base64').toString('utf8').startsWith('untrusted comment:')) {
      throw new Error(`Invalid updater signature for ${platform}`);
    }
    platforms[platform] = { signature, url: `${base}/${asset}` };
  }
  return { version, notes: `Marquee ${version}`, pub_date: new Date().toISOString(), platforms };
}

if (require.main === module) {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'prepare') prepareDesktop(args[0]);
  else if (command === 'stage') stageDesktop(args[0], args[1] === 'signed');
  else if (command === 'manifest') {
    const [directory, version, repo, tag] = args;
    const manifest = createManifest(directory, version, repo, tag);
    fs.writeFileSync(path.join(directory, 'latest.json'), JSON.stringify(manifest, null, 2) + '\n');
  } else throw new Error('Expected prepare, stage or manifest command');
}

module.exports = { normalizeVersion, prepareDesktop, stageDesktop, createManifest, releaseFiles };
