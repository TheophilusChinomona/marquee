'use strict';

const http = require('node:http');
const { URL } = require('node:url');
const goojara = require('./resolvers/goojara');
const { shutdown } = require('./lib/browser');
const { join } = require('node:path');
const { Store } = require('./recommendations/store');
const { Metadata } = require('./recommendations/metadata');
const { RecommendationService } = require('./recommendations/service');
const { createRecommendationHandler } = require('./recommendations/http');

/**
 * Marquee's own streaming API. One job: given a title (or an episode), answer with
 * a URL the TV can play right now. Everything else - the site scraping, the session
 * dance, the headless browser - stays on this box so the Fire TV only ever sees a
 * small JSON document and a plain MP4/HLS link.
 *
 *   GET /health
 *   GET /search?q=apartment        -> { results: [{ id, name, year, kind, poster }] }
 *   GET /show?id=/tYOM5N           -> { id, kind, name, seasons: [{ number, episodes: [...] }] }
 *   GET /resolve?id=/eYOBgQ        -> { url, container, host, referer, cached }
 *   GET /resolve?id=/tYOM5N&season=2&episode=3
 */

const PORT = Number(process.env.RESOLVER_PORT || 8099);
const HOST = process.env.RESOLVER_HOST || '0.0.0.0';
const TOKEN = process.env.RESOLVER_TOKEN || '';
const CACHE_TTL_MS = Number(process.env.RESOLVER_CACHE_MS || 20 * 60 * 1000);
const DEADLINE_MS = Number(process.env.RESOLVER_TIMEOUT_MS || 90_000);

const cache = new Map();
const inflight = new Map();

/**
 * A public hostname is an open door, and behind it is a browser on a home machine.
 * The budget is deliberately generous for a household (a TV asks a handful of times
 * per episode) and useless for a scraper.
 */
const RATE_WINDOW_MS = Number(process.env.RESOLVER_RATE_WINDOW_MS || 10 * 60 * 1000);
const RATE_MAX = Number(process.env.RESOLVER_RATE_MAX || 60);
const rate = new Map();

function clientIp(req) {
  // Behind Cloudflare the socket is always the tunnel, so the real caller is here.
  const forwarded = req.headers['cf-connecting-ip'] || req.headers['x-forwarded-for'];
  if (forwarded) return String(forwarded).split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

function allow(ip) {
  const now = Date.now();
  const entry = rate.get(ip);
  if (!entry || entry.resetAt <= now) {
    rate.set(ip, { count: 1, resetAt: now + RATE_WINDOW_MS });
    if (rate.size > 1000) {
      for (const [key, value] of rate) if (value.resetAt <= now) rate.delete(key);
    }
    return { ok: true, remaining: RATE_MAX - 1 };
  }
  if (entry.count >= RATE_MAX) {
    return { ok: false, remaining: 0, retryAfter: Math.ceil((entry.resetAt - now) / 1000) };
  }
  entry.count++;
  return { ok: true, remaining: RATE_MAX - entry.count };
}

function cacheGet(key) {
  const hit = cache.get(key);
  if (!hit) return null;
  if (hit.expiresAt <= Date.now()) {
    cache.delete(key);
    return null;
  }
  return hit.value;
}

function cachePut(key, value, ttlMs = CACHE_TTL_MS) {
  cache.set(key, { value, expiresAt: Date.now() + ttlMs });
  if (cache.size > 500) {
    const oldest = [...cache.entries()].sort((a, b) => a[1].expiresAt - b[1].expiresAt)[0];
    if (oldest) cache.delete(oldest[0]);
  }
}

/** One resolve per key at a time: two viewers on the same episode share the work. */
async function once(key, work) {
  const running = inflight.get(key);
  if (running) return running;
  const task = work().finally(() => inflight.delete(key));
  inflight.set(key, task);
  return task;
}

/** A hung scrape must not hold a request (or the queue behind it) forever. */
function withDeadline(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

function json(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*'
  });
  res.end(body);
}

const recommendationStore = new Store(join(process.env.MARQUEE_DATA_DIR || join(__dirname, 'data'), 'marquee.sqlite'));
const recommendationService = new RecommendationService(recommendationStore, new Metadata(recommendationStore), query =>
  withDeadline(goojara.search(query), DEADLINE_MS, 'availability search'));
const recommendationHandler = createRecommendationHandler({ store: recommendationStore, service: recommendationService, json });

/**
 * A TV remote is a bad keyboard, so the token can also live in the path:
 * `https://host/t/<token>/search?q=…`. The app only ever stores one string, and a
 * proxy in front needs no knowledge of it.
 */
function splitTokenPath(pathname) {
  const match = pathname.match(/^\/t\/([^/]+)(\/.*)?$/);
  if (!match) return { path: pathname, token: '' };
  return { path: match[2] || '/', token: decodeURIComponent(match[1]) };
}

function authorised(req, url, pathToken) {
  if (!TOKEN) return true;
  const header = req.headers.authorization || '';
  const bearer = header.startsWith('Bearer ') ? header.slice(7) : '';
  return bearer === TOKEN || url.searchParams.get('token') === TOKEN || pathToken === TOKEN;
}

async function findEpisode(showId, season, episode) {
  const data = await goojara.show(showId);
  const seasons = data.seasons || [];
  const wanted = seasons.find((row) => Number(row.number) === Number(season)) || seasons[0];
  if (!wanted) throw new Error('no seasons on this title');
  const found =
    wanted.episodes.find((row) => Number(row.number) === Number(episode)) || wanted.episodes[0];
  if (!found) throw new Error('no episodes in this season');
  return { show: data, episode: found };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const split = splitTokenPath(url.pathname);
  const path = split.path.replace(/\/+$/, '') || '/';

  try {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'authorization,content-type',
        'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS'
      });
      return res.end();
    }

    if (path === '/' || path === '/health') {
      return json(res, 200, {
        status: 'ok',
        service: 'marquee-resolver',
        version: require('./package.json').version,
        uptimeS: Math.round(process.uptime()),
        cached: cache.size
      });
    }

    if (!authorised(req, url, split.token)) return json(res, 401, { error: 'unauthorised' });

    const quota = allow(clientIp(req));
    if (!quota.ok) {
      res.setHeader('Retry-After', String(quota.retryAfter || 60));
      return json(res, 429, { error: 'too many requests', retryAfterS: quota.retryAfter });
    }

    if (await recommendationHandler(req, res, url, path)) return;

    if (path === '/search') {
      const query = (url.searchParams.get('q') || '').trim();
      if (query.length < 2) return json(res, 400, { error: "missing 'q'" });
      const key = 'search:' + query.toLowerCase();
      const cached = cacheGet(key);
      if (cached) return json(res, 200, { results: cached, cached: true });
      const results = await once(key, () =>
        withDeadline(goojara.search(query), DEADLINE_MS, 'search')
      );
      cachePut(key, results, 10 * 60 * 1000);
      return json(res, 200, { results });
    }

    if (path === '/show') {
      const id = url.searchParams.get('id') || '';
      if (!id) return json(res, 400, { error: "missing 'id'" });
      const key = 'show:' + id;
      const cached = cacheGet(key);
      if (cached) return json(res, 200, { ...cached, cached: true });
      const data = await once(key, () =>
        withDeadline(goojara.show(id), DEADLINE_MS, 'show')
      );
      cachePut(key, data, 6 * 60 * 60 * 1000);
      return json(res, 200, data);
    }

    if (path === '/resolve') {
      const id = url.searchParams.get('id') || '';
      const season = url.searchParams.get('season');
      const episode = url.searchParams.get('episode');
      const host = url.searchParams.get('host') || null;
      if (!id) return json(res, 400, { error: "missing 'id'" });

      let episodeId = id;
      let label = '';
      if (season !== null || episode !== null) {
        const lookup = await findEpisode(id, season || 1, episode || 1);
        episodeId = lookup.episode.id;
        label = `${lookup.episode.season}x${lookup.episode.number} ${lookup.episode.name}`;
      }

      const key = `resolve:${episodeId}:${host || 'auto'}`;
      const cached = cacheGet(key);
      if (cached) return json(res, 200, { ...cached, cached: true });

      const playback = await once(key, () =>
        withDeadline(goojara.resolve(episodeId, { preferred: host }), DEADLINE_MS, 'resolve')
      );
      const payload = { id: episodeId, label, ...playback };
      cachePut(key, payload);
      return json(res, 200, payload);
    }

    return json(res, 404, { error: 'not found', paths: ['/health', '/search', '/show', '/resolve'] });
  } catch (error) {
    const message = error && error.message ? error.message : String(error);
    return json(res, 502, { error: message });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`marquee-resolver listening on http://${HOST}:${server.address().port}`);
  if (process.send) process.send({ port: server.address().port });
  console.log(`  source: ${goojara.BASE}`);
  if (TOKEN) console.log('  auth: bearer token required');
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, async () => {
    console.log(`\n${signal}: shutting down`);
    server.close();
    await shutdown();
    recommendationStore.close();
    process.exit(0);
  });
}
