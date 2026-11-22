'use strict';

/**
 * A URL is only worth handing to the TV if it still answers. Signed CDN links from
 * these hosts expire, and a dead file is reported honestly (410) instead of being
 * passed on as a playback error the viewer has to guess at.
 */

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/124.0.0.0 Safari/537.36';

const MEDIA_TYPES = ['video/', 'application/vnd.apple.mpegurl', 'application/x-mpegurl'];

function looksLikeMedia(url, contentType) {
  const type = String(contentType || '').toLowerCase();
  if (MEDIA_TYPES.some((prefix) => type.startsWith(prefix) || type.includes(prefix))) return true;
  return /\.(mp4|m3u8|mpd|mkv|webm)(\?|$)/i.test(url || '');
}

/** Follows redirects and asks for the first kilobyte, so nothing is downloaded twice. */
async function verify(url, { timeoutMs = 20_000, range = true } = {}) {
  const headers = { 'User-Agent': UA, Referer: 'https://web.wootly.ch/' };
  if (range) headers.Range = 'bytes=0-1023';
  try {
    const response = await fetch(url, {
      headers,
      redirect: 'follow',
      signal: AbortSignal.timeout(timeoutMs)
    });
    const contentType = response.headers.get('content-type') || '';
    const finalUrl = response.url || url;
    // The body is not needed; close it so the connection is not held open.
    try {
      await response.body?.cancel();
    } catch {
      /* already consumed */
    }
    const playable = (response.status === 200 || response.status === 206) && looksLikeMedia(finalUrl, contentType);
    return {
      ok: playable,
      status: response.status,
      finalUrl,
      contentType,
      container: /\.m3u8(\?|$)/i.test(finalUrl) || contentType.includes('mpegurl') ? 'hls' : 'mp4'
    };
  } catch (error) {
    return { ok: false, status: 0, finalUrl: url, contentType: '', error: error.message };
  }
}

module.exports = { verify, looksLikeMedia };
