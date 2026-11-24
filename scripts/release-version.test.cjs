const {test}=require('node:test');const assert=require('node:assert/strict');
const {nextVersion}=require('./release-version.cjs');
test('initial release honours the prepared version with desktop-compatible semver',()=>assert.deepEqual(nextVersion({versionCode:17,versionName:'1.15'},{versionCode:10,versionName:'1.8'}),{versionCode:17,versionName:'1.15.0'}));
test('subsequent merges increment code and patch',()=>assert.deepEqual(nextVersion({versionCode:17,versionName:'1.15'},{versionCode:17,versionName:'1.15'}),{versionCode:18,versionName:'1.15.1'}));
test('patch releases keep increasing even with unchanged Gradle defaults',()=>assert.deepEqual(nextVersion({versionCode:17,versionName:'1.15'},{versionCode:18,versionName:'1.15.1'}),{versionCode:19,versionName:'1.15.2'}));
test('invalid versions fail closed',()=>assert.throws(()=>nextVersion({versionCode:17,versionName:'bad'},{versionCode:10,versionName:'1.8'})));
test('a higher Android code cannot roll desktop versions back',()=>assert.throws(()=>nextVersion({versionCode:30,versionName:'1.14'},{versionCode:20,versionName:'1.15.3'}),/increase/));
