'use strict';

const { chromium } = require('playwright-core');

/**
 * One Chromium for the whole process: launching costs seconds and the whole point
 * of this service is to answer a play request while the viewer is still holding the
 * remote. Requests get their own browser context, so cookies and sessions from one
 * title can never leak into another.
 */

const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/124.0.0.0 Safari/537.36';

const MAX_CONTEXTS = Number(process.env.RESOLVER_CONCURRENCY || 2);
const IDLE_CLOSE_MS = Number(process.env.RESOLVER_IDLE_MS || 120_000);

let browserPromise = null;
let active = 0;
const waiting = [];
let idleTimer = null;

function launchOptions() {
  const options = {
    headless: !process.env.RESOLVER_HEADFUL,
    args: [
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--mute-audio',
      '--autoplay-policy=no-user-gesture-required',
      '--disable-blink-features=AutomationControlled'
    ]
  };
  // A packaged Chromium path (Docker image, Windows without a global install).
  if (process.env.MARQUEE_CHROME) options.executablePath = process.env.MARQUEE_CHROME;
  return options;
}

async function browser() {
  if (!browserPromise) {
    browserPromise = chromium.launch(launchOptions()).catch((error) => {
      browserPromise = null;
      throw error;
    });
  }
  const instance = await browserPromise;
  if (idleTimer) {
    clearTimeout(idleTimer);
    idleTimer = null;
  }
  return instance;
}

function acquire() {
  if (active < MAX_CONTEXTS) {
    active++;
    return Promise.resolve();
  }
  return new Promise((resolve) => waiting.push(resolve));
}

function release() {
  const next = waiting.shift();
  if (next) next();
  else active = Math.max(0, active - 1);
  scheduleIdleClose();
}

function scheduleIdleClose() {
  if (active > 0 || idleTimer) return;
  idleTimer = setTimeout(async () => {
    idleTimer = null;
    if (active > 0) return;
    const pending = browserPromise;
    browserPromise = null;
    try {
      const instance = await pending;
      await instance?.close();
    } catch {
      /* already gone */
    }
  }, IDLE_CLOSE_MS);
  idleTimer.unref?.();
}

/**
 * Runs `work(page, context)` in a fresh context. The page is closed for you; the
 * caller only owns the return value.
 */
async function withPage(work, { timeoutMs = 60_000, mobile = false } = {}) {
  await acquire();
  let context = null;
  try {
    const instance = await browser();
    context = await instance.newContext({
      userAgent: USER_AGENT,
      viewport: mobile ? { width: 412, height: 915 } : { width: 1280, height: 800 },
      locale: 'en-US',
      timezoneId: 'Africa/Johannesburg',
      javaScriptEnabled: true
    });
    context.setDefaultTimeout(timeoutMs);
    context.setDefaultNavigationTimeout(timeoutMs);
    const page = await context.newPage();
    page.on('dialog', (dialog) => dialog.dismiss().catch(() => {}));
    // Ads are the main source of popups; a resolver only needs the player frames.
    page.on('popup', (popup) => popup.close().catch(() => {}));
    return await work(page, context);
  } finally {
    if (context) await context.close().catch(() => {});
    release();
  }
}

/** Waits until `check()` is truthy, polling page state. */
async function waitFor(page, check, { timeoutMs = 15_000, intervalMs = 400 } = {}) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await check().catch(() => null);
    if (value) return value;
    if (Date.now() > deadline) return null;
    await page.waitForTimeout(intervalMs);
  }
}

/** Clicks every play affordance the page exposes, across all frames. */
async function pressPlay(page, { rounds = 3, waitMs = 3500 } = {}) {
  for (let round = 0; round < rounds; round++) {
    let clicked = false;
    for (const frame of page.frames()) {
      const hit = await frame
        .evaluate(() => {
          const targets = [
            '#bigPlay',
            '#playh',
            '.play-button',
            '#prime',
            '.jw-icon-display',
            '.vjs-big-play-button',
            'button[aria-label="Play"]',
            'video'
          ];
          let count = 0;
          for (const selector of targets) {
            document.querySelectorAll(selector).forEach((el) => {
              try {
                el.click();
                count++;
              } catch {
                /* ignore */
              }
            });
          }
          // Wootly's shell page is a form: submitting it is deterministic, while a
          // click only works once its own handler has been attached.
          const shell = document.getElementById('jgb');
          if (shell) {
            try {
              shell.submit();
              count++;
            } catch {
              /* ignore */
            }
          }
          const video = document.querySelector('video');
          if (video) {
            video.muted = true;
            video.play?.().catch(() => {});
          }
          return count;
        })
        .catch(() => 0);
      if (hit) clicked = true;
    }
    if (!clicked && round > 0) break;
    await page.waitForTimeout(waitMs);
  }
}

async function shutdown() {
  const pending = browserPromise;
  browserPromise = null;
  if (idleTimer) clearTimeout(idleTimer);
  try {
    const instance = await pending;
    await instance?.close();
  } catch {
    /* nothing to close */
  }
}

module.exports = { withPage, waitFor, pressPlay, shutdown, USER_AGENT };
