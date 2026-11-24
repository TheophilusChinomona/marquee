'use strict';
const fs = require('node:fs');
const { normalizeVersion } = require('./desktop-release.cjs');
function valid(value) {
  if (!Number.isSafeInteger(value.versionCode) || value.versionCode < 1) throw new Error('Invalid release version code');
  return { ...value, versionName: normalizeVersion(value.versionName) };
}
function nextVersion(prepared, published) {
  prepared = valid(prepared);
  published = valid(published);
  if (prepared.versionCode > published.versionCode) {
    const a = prepared.versionName.split('.').map(Number);
    const b = published.versionName.split('.').map(Number);
    const first = a.findIndex((part, i) => part !== b[i]);
    if (first < 0 || a[first] < b[first]) throw new Error('Prepared version must increase for desktop updates');
    return prepared;
  }
  const [major, minor, patch] = published.versionName.split('.').map(Number);
  return valid({ versionCode: published.versionCode + 1, versionName: `${major}.${minor}.${patch + 1}` });
}
if (require.main === module) {
  const gradle = fs.readFileSync('firetv-player/app/build.gradle.kts', 'utf8');
  const prepared = {
    versionCode: Number(gradle.match(/releaseVersionCode.*\?: (\d+)/)[1]),
    versionName: gradle.match(/releaseVersionName.*\?: "([^"]+)"/)[1],
  };
  const published = JSON.parse(fs.readFileSync(process.argv[2] || 'version.json', 'utf8'));
  const version = nextVersion(prepared, published);
  if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT,
    `code=${version.versionCode}\nname=${version.versionName}\ntag=v${version.versionName}\n`);
  console.log(JSON.stringify(version));
}
module.exports = { nextVersion };
