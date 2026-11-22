'use strict';

const { withPage, waitFor, pressPlay } = require('../lib/browser');
const { verify } = require('../lib/verify');

/**
 * goojara is a link site, not a stream API: an episode page offers two hosts
 * (Wootly, Vidsrc) behind `/go.php?url=<token>` redirects, and the token is minted
 * by their own JS from a per-session nonce. That is why a plain HTTP client gets a
 * 404 there and a browser gets a player.
 *
 * So the catalogue and the player both run inside Chromium, but as cheaply as
 * possible: one page load plus one in-page POST per question, and no typing.
 * What comes back at the end of a resolve is a signed CDN link - an MP4 file for
 * most titles, an HLS playlist for some - which is exactly what ExoPlayer eats.
 */

const BASE = process.env.GOOJARA_BASE || 'https://ww1.goojara.to';
const SEARCH_KEY = 'fbb30259a8';
const IMPLEMENTED = ['wootly'];
const HOST_ATTEMPTS = 2;

const ENTITIES = { '&amp;': '&', '&quot;': '"', '&#039;': "'", '&#39;': "'", '&lt;': '<', '&gt;': '>' };

function clean(text) {
  return String(text || '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&[a-z#0-9]+;/gi, (entity) => ENTITIES[entity.toLowerCase()] || entity)
    .replace(/\s+/g, ' ')
    .trim();
}

function absolute(value) {
  if (!value) return null;
  if (/^https?:/i.test(value)) return value;
  return BASE + (value.startsWith('/') ? value : '/' + value);
}

/** "Don't Trust the B---- in Apartment 23 (2011)" -> { name, year } */
function splitTitle(raw) {
  const value = clean(raw);
  const match = value.match(/^(.*?)\s*\((\d{4})\)\s*$/);
  if (!match) return { name: value, year: '' };
  return { name: clean(match[1]), year: match[2] };
}

function post(page, body) {
  return page.evaluate(async (payload) => {
    const response = await fetch('/xmre.php', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: payload
    });
    return { status: response.status, body: await response.text() };
  }, body);
}

/**
 * One page load, one POST. The site's own box fires a POST per keystroke, which is
 * nine round trips and nine chances to be rate limited; this sends the whole query.
 */
async function search(query) {
  const needle = clean(query).slice(0, 60);
  if (needle.length < 2) return [];
  return withPage(
    async (page) => {
      await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
      const response = await post(page, `x=${SEARCH_KEY}&q=${encodeURIComponent(needle)}`);
      if (response.status !== 200 || !response.body) return [];

      const out = [];
      const pattern = /<a href="(\/[A-Za-z0-9]{6})"><div class="(it|im)"><strong>([\s\S]*?)<\/strong>\s*\(?([0-9]{4})?\)?/g;
      let match;
      while ((match = pattern.exec(response.body)) !== null) {
        const { name, year } = splitTitle(match[3]);
        if (!name) continue;
        out.push({
          id: match[1],
          name,
          year: match[4] || year,
          // "it" is a series row, "im" a movie row in their markup
          kind: match[2] === 'it' ? 'tv' : 'movie'
        });
        if (out.length >= 25) break;
      }
      return out;
    },
    { timeoutMs: 45_000 }
  );
}

function parseEpisodes(html, season) {
  const episodes = [];
  const chunks = String(html).split('<div class="seho">').slice(1);
  for (const chunk of chunks) {
    const link = chunk.match(/<h1>\s*<a href="(\/[A-Za-z0-9]{6})">([\s\S]*?)<\/a>/);
    if (!link) continue;
    const number = (chunk.match(/<span class="sea">\s*([0-9]+)\s*<\/span>/) || [])[1];
    const date = (chunk.match(/<div class="date">([\s\S]*?)<\/div>/) || [])[1];
    const plot = (chunk.match(/<p>([\s\S]*?)<\/p>/) || [])[1];
    episodes.push({
      id: link[1],
      name: clean(link[2]).replace(/\.\.\.$/, ''),
      season,
      number: Number(number) || episodes.length + 1,
      airdate: clean(date),
      plot: clean(plot)
    });
  }
  return episodes;
}

/**
 * A title page knows its seasons; each season's episodes arrive through the same
 * POST endpoint the season buttons use. A movie page has no seasons at all.
 */
async function show(id) {
  const path = id.startsWith('/') ? id : '/' + id;
  return withPage(
    async (page) => {
      await page.goto(absolute(path), { waitUntil: 'domcontentloaded' });

      const meta = await page.evaluate(() => {
        const heading = document.querySelector('.marl h1') || document.querySelector('h1');
        const poster = document.querySelector('.imrl img');
        const seasons = [
          ...document.querySelectorAll('#sesh button[data-season], #drop a'),
          ...document.querySelectorAll('#seon button[data-season]')
        ]
          .map((el) => el.getAttribute('data-season') || el.textContent || '')
          .map((value) => Number(String(value).replace(/[^0-9]/g, '')))
          .filter((value) => Number.isFinite(value) && value > 0);
        const seasonHost = document.getElementById('seon');
        const posterSrc = poster ? poster.getAttribute('src') : '';
        return {
          title: document.title,
          heading: heading ? heading.textContent : '',
          poster: posterSrc
            ? /^https?:/i.test(posterSrc)
              ? posterSrc
              : location.origin + posterSrc
            : '',
          plot: document.querySelector('.marl p')?.textContent || '',
          seasons: [...new Set(seasons)].sort((a, b) => a - b),
          showRef: seasonHost ? seasonHost.getAttribute('data-id') || '' : '',
          hostLinks: document.querySelectorAll('#drl a[href*="go.php"]').length
        };
      });

      const { name, year } = splitTitle(meta.heading || meta.title);
      const poster = meta.poster;
      const plot = clean(meta.plot);

      if (meta.hostLinks && !meta.showRef) {
        return { id: path, kind: 'movie', name, year, poster, plot, seasons: [] };
      }

      const seasons = [];
      const numbers = meta.seasons.length ? meta.seasons : [1];
      for (const number of numbers) {
        if (!meta.showRef) continue;
        const response = await post(page, `s=${number}&t=${meta.showRef}`);
        if (response.status !== 200 || !response.body) continue;
        const episodes = parseEpisodes(response.body, number);
        if (episodes.length) seasons.push({ number, episodes: episodes.sort((a, b) => a.number - b.number) });
      }
      return { id: path, kind: 'tv', name, year, poster, plot, seasons };
    },
    { timeoutMs: 90_000 }
  );
}

/** The host links the page is offering, in the site's own order. */
async function hostLinks(page) {
  return page.evaluate(() =>
    [...document.querySelectorAll('a[href*="go.php"]')].map((a) => ({
      href: a.getAttribute('href'),
      host: (a.textContent || '').trim().toLowerCase()
    }))
  );
}

/**
 * Follows each host link until a media URL appears, and returns the first one that
 * the network accepted. Wootly lands on a shell page whose play button POSTs for
 * the file; Vidsrc chains through its own embed before it hands over a playlist.
 */
async function resolve(id, { preferred = null, allowFallback = true } = {}) {
  const path = id.startsWith('/') ? id : '/' + id;
  return withPage(
    async (page) => {
      const found = [];
      const seen = new Set();
      page.context().on('response', (response) => {
        const url = response.url();
        const type = (response.headers()['content-type'] || '').toLowerCase();
        const media =
          /\.(mp4|m3u8|mpd)(\?|$)/i.test(url) ||
          /\/source\?/i.test(url) ||
          type.includes('mpegurl') ||
          type.startsWith('video/');
        if (!media || seen.has(url)) return;
        seen.add(url);
        found.push({ url, status: response.status() });
      });

      await page.goto(absolute(path), { waitUntil: 'domcontentloaded' });
      const links = await hostLinks(page);
      if (!links.length) throw new Error('no host links on page');

      const ordered = preferred
        ? [...links].sort(
            (a, b) => Number(b.host.includes(preferred)) - Number(a.host.includes(preferred))
          )
        : links;

      // Only Wootly is implemented. Walking into an unimplemented host costs a
      // viewer a full deadline for nothing, so it is opt-in by name.
      const wanted = preferred ? ordered : ordered.filter((row) => IMPLEMENTED.some((h) => row.host.includes(h)));
      const candidates = allowFallback ? wanted : wanted.slice(0, 1);
      if (!candidates.length) throw new Error('no implemented host on this page');
      let lastStatus = 0;

      for (const link of candidates) {
        for (let attempt = 1; attempt <= HOST_ATTEMPTS; attempt++) {
        const before = found.length;
        // Same context, so the session cookies the page just minted come along.
        await page.goto(absolute(link.href), { waitUntil: 'domcontentloaded' }).catch(() => {});
        await page.waitForTimeout(attempt === 1 ? 1200 : 2500);
        await pressPlay(page, { rounds: attempt === 1 ? 3 : 4 });

        const media = await waitFor(
          page,
          async () => {
            const fresh = found.slice(before);
            // A 410 from the source hop means the file is gone; no point waiting
            // out the rest of the window for a player that will never appear.
            if (fresh.some((row) => row.status === 410)) return [{ url: 'gone', status: 410 }];
            const playable = fresh.filter((row) => row.status === 200 || row.status === 206);
            if (playable.length) return playable;
            const fromVideo = await page.evaluate(() =>
              [...document.querySelectorAll('video')]
                .map((video) => video.currentSrc || video.src)
                .filter(Boolean)
            );
            return fromVideo.length ? fromVideo.map((url) => ({ url, status: 0 })) : null;
          },
          { timeoutMs: attempt === 1 ? 20_000 : 26_000 }
        );

        if (media && media.length) {
          if (media.length === 1 && media[0].url === 'gone') {
            // A 410 will not change on a retry.
            lastStatus = media[0].status;
            break;
          }
          // A page can surface several candidates (and a /source hop that only
          // redirects); the first one that still answers is the answer.
          for (const candidate of media) {
            const checked = await verify(candidate.url);
            if (checked.ok) {
              return {
                host: link.host || 'unknown',
                url: checked.finalUrl,
                status: checked.status,
                container: checked.container,
                contentType: checked.contentType,
                referer: page.url()
              };
            }
            lastStatus = checked.status || lastStatus;
          }
        }
        }
      }

      const dead = found.filter((row) => row.status === 410 || row.status === 404);
      const status = dead[0]?.status || lastStatus;
      throw new Error(
        status
          ? `host reports the file is gone (${status})`
          : 'no media URL surfaced from any host'
      );
    },
    { timeoutMs: 120_000 }
  );
}

module.exports = { search, show, resolve, BASE };
