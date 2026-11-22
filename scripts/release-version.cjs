'use strict';
const fs=require('node:fs');
function valid(v){if(!Number.isSafeInteger(v.versionCode)||v.versionCode<1||!/^\d+\.\d+(?:\.\d+)?$/.test(v.versionName))throw Error('Invalid release version');return v;}
function nextVersion(prepared,published){valid(prepared);valid(published);if(prepared.versionCode>published.versionCode)return prepared;const [major,minor,patch=0]=published.versionName.split('.').map(Number);return {versionCode:published.versionCode+1,versionName:`${major}.${minor}.${patch+1}`};}
if(require.main===module){const gradle=fs.readFileSync('firetv-player/app/build.gradle.kts','utf8');const prepared={versionCode:Number(gradle.match(/releaseVersionCode.*\?: (\d+)/)[1]),versionName:gradle.match(/releaseVersionName.*\?: "([^"]+)"/)[1]};const published=JSON.parse(fs.readFileSync(process.argv[2]||'version.json','utf8'));const v=nextVersion(prepared,published);if(process.env.GITHUB_OUTPUT)fs.appendFileSync(process.env.GITHUB_OUTPUT,`code=${v.versionCode}\nname=${v.versionName}\ntag=v${v.versionName}\n`);console.log(JSON.stringify(v));}
module.exports={nextVersion};
