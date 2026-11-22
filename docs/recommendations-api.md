# Marquee recommendation API v1

Android and desktop share this contract. Use your own resolver base URL and
Cloudflare service-token headers, or its configured Bearer token. Never ship a
personal hostname/key in the app. Credentials authorize a household; profiles
separate taste inside that household, not mutually untrusted accounts.

Desktop clients should use native HTTP requests (for example, Tauri's Rust HTTP
client) rather than placing service credentials in a webview's local storage or
depending on cross-origin browser preflights through Access.

Requests are JSON objects <=256 KiB. Errors: 400 invalid data, 401/403 access,
409 epoch/mapping/cursor conflict, 413 too large, 429 throttled (honour Retry-After).
Cloudflare may return HTML errors; preserve the last good page on failures.

## Identity

Persist a random device UUID. `POST /v1/devices/register`:

```json
{"deviceId":"01234567-89ab-4cde-8fab-0123456789ab","profiles":[{"localId":"local-profile-id","name":"Display name"}]}
```

Returns `{"mappings":[{"localId":"local-profile-id","profileId":"server-uuid","epoch":0}]}`.
Repeat registration is idempotent. Display names never cause automatic merging.

Desktop joining an existing profile: register with `profiles: []`, then
`GET /v1/profiles` returns `{"profiles":[{"id":"server-uuid","name":"Display name","epoch":0}]}`.
Let the user select one; `POST /v1/devices/profiles/link`:

```json
{"deviceId":"01234567-89ab-4cde-8fab-0123456789ab","localId":"desktop-profile","profileId":"server-uuid"}
```

Returns the mapping. An established mapping cannot change in v1 (409); histories
are never implicitly merged. Link before uploading desktop viewing evidence.

## Watch evidence

`POST /v1/watch/batch`, at most 50 updates:

```json
{
  "deviceId":"01234567-89ab-4cde-8fab-0123456789ab",
  "updates":[{
    "profileId":"server-uuid","epoch":0,"sessionId":"playback-session-uuid","revision":3,
    "kind":"tv","sourceId":"source-series-id","title":"Boston Legal","year":2004,
    "playedMs":1800000,"durationMs":2400000,"updatedAt":1795380000000,
    "explicitWatched":false,"approximate":false
  }]
}
```

Kind is `tv` or `movie`. Episodes use the series source ID. `tmdbId` is optional
when known. Never send a signed video URL, provider password or service secret in
watch data. `playedMs` is cumulative actual playing time for this session, excluding
pauses/buffering/seeking. Session revisions make retries/out-of-order updates safe.
The response is `{"accepted":[{"profileId":"server-uuid","sessionId":"...","revision":3}]}`.
Clear only pending revisions <= acknowledged revision; retain newer local updates.

Explicit watched seeds are user statements, not invented durations. Migrating old
resume entries sets `approximate: true`. Capture profile and epoch at playback
start; reject later writes from a session whose epoch was reset.

## Recommendations

`GET /v1/recommendations?profileId=<uuid>&limit=20` returns:

```json
{
  "state":"ready","epoch":0,"revision":"ranking-uuid","generatedAt":1795380000000,
  "retryAfterS":15,"nextCursor":null,
  "items":[{
    "kind":"tv","tmdbId":123,"title":"Example title","year":2020,
    "poster":"https://image.tmdb.org/t/p/w342/example.jpg",
    "genres":[18],"keywords":["lawyer"],"rating":8,"votes":1000,
    "score":1.2,"reason":"Because you watched Boston Legal · lawyer",
    "onlineId":"gd:tv:/example"
  }]
}
```

This is an illustrative payload, not an availability claim. Match kind/title/year
against the client's disk-backed provider index first. Otherwise use a non-null
`onlineId`; hide candidates without either source. Preserve provider playback IDs
and extensions. Online IDs use existing `/show` and `/resolve`; mint a fresh URL
only on Play. A listing match does not guarantee a working upstream file.

State: `cold_start`, `pending`, `refreshing`, `ready`, `unavailable`. Missing TMDB
configuration includes `reason: "metadata_not_configured"`. Keep the last good
page through refresh/errors. Label category-only fallback as discovery, not learned taste.
Follow URL-encoded `nextCursor` only as needed: at most 3 nonempty candidate pages
per Home visit, processed one at a time, at most 20 display cards. Drop rejected
candidates. Poll pending state no faster than retryAfterS (minimum 15 seconds),
only while visible. Discard old-profile replies. A stale cursor returns 409.

## Preference reset

`DELETE /v1/profiles/<uuid>/watch` returns `{"profileId":"server-uuid","epoch":1}`.
It atomically increments epoch, removes learned records/rankings and invalidates
old jobs. Stale uploads return 409 with the current profile/epoch. Clear that
profile's local learning/pending/cached recommendations, including pre-reset
playing sessions; never reimport its old resume entries. Other profiles,
favourites and provider credentials are untouched.

## Operations and attribution

Node >=22.13 is required. Server-only `TMDB_READ_TOKEN` comes from
https://www.themoviedb.org/settings/api . TMDB supplies metadata and related-title
candidates; Marquee combines seeds, ranks, diversifies and matches sources. This
is a content-based engine, not a trained model or a promise of Netflix-level accuracy.

Systemd reads `~/.config/marquee/resolver.env` (mode 600) and persists SQLite in
`~/.local/share/marquee`; restart after changing secrets. Compose forwards the token
and persists `/data` in the `marquee-data` volume. Deploys exclude databases and
secrets. Reboot preserves records; interrupted refresh work restarts on demand.
Back up using SQLite's online backup facility, or stop the service before copying.

Online sources Off keeps TV learning local and suppresses remote recommendation
sync; cached provider picks/library stay usable. Turning it on resumes sync.
TMDB receives requested title IDs/search terms, not profile names, viewing durations
or provider credentials. No subtitle acquisition or full cross-device resume/
favourites sync is included in this API version.

This product uses the TMDB API but is not endorsed or certified by TMDB.
Metadata source: https://www.themoviedb.org/ .
