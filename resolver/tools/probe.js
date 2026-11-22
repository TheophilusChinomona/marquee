'use strict';

/**
 * Command-line poke at the resolver, without starting the HTTP server.
 *
 *   node tools/probe.js search "apartment 23"
 *   node tools/probe.js show /tYOM5N
 *   node tools/probe.js resolve /eYOBgQ
 *   node tools/probe.js resolve /tYOM5N 2 3
 */

const goojara = require('../resolvers/goojara');
const { shutdown } = require('../lib/browser');

async function main() {
  const [command, arg, season, episode] = process.argv.slice(2);
  if (!command) {
    console.log('usage: probe.js search <query> | show <id> | resolve <id> [season episode]');
    process.exit(1);
  }
  const started = Date.now();
  try {
    if (command === 'search') {
      console.log(JSON.stringify(await goojara.search(arg), null, 2));
    } else if (command === 'show') {
      const data = await goojara.show(arg);
      const summary = {
        ...data,
        seasons: data.seasons.map((row) => ({
          number: row.number,
          episodes: row.episodes.length,
          first: row.episodes[0]
        }))
      };
      console.log(JSON.stringify(summary, null, 2));
    } else if (command === 'resolve') {
      let target = arg;
      if (season) {
        const data = await goojara.show(arg);
        const picked = data.seasons
          .find((row) => Number(row.number) === Number(season))
          ?.episodes.find((row) => Number(row.number) === Number(episode || 1));
        if (!picked) throw new Error(`no episode ${season}x${episode} on ${arg}`);
        target = picked.id;
        console.log(`# episode ${picked.season}x${picked.number} ${picked.name} -> ${target}`);
      }
      console.log(JSON.stringify(await goojara.resolve(target), null, 2));
    } else {
      console.log('unknown command', command);
      process.exit(1);
    }
  } catch (error) {
    console.error('FAILED:', error.message);
    process.exitCode = 2;
  } finally {
    console.error(`(${((Date.now() - started) / 1000).toFixed(1)}s)`);
    await shutdown();
  }
}

main();
