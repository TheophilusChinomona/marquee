# Profile recommendations across Marquee clients

Status: approved. The recommendation work stays in this worktree; the
Tauri desktop app is a separate worktree. No desktop UI or Jellyfin deployment is
part of this change.

Approved by the user on 2026-11-22. Implementation clarification: Online sources
Off preserves the existing privacy behaviour, keeping learning local and disabling
remote recommendation sync; cached provider matches remain usable.

## Intent

Make useful, explainable recommendations from each profile's actual viewing,
including provider and online titles. The user's example is The Good Wife and
Boston Legal leading toward Suits, with Just Like Heaven contributing a separate
romantic-comedy preference. Implement the ranking ourselves, with no LLM or model
running on the Fire TV. Study Jellyfin's separation of user data, metadata, and
playback; write our implementation independently.

## Established decisions and proposed implementation choices

Agreed: native Android TV UI, omarchy performs recommendation work, shared backend
usable by a future desktop client, persistent data across restarts, provider and
online candidates, small cached pages on the TV, bounded memory.

Alternatives discussed: on-device recommendation engine, adopting a media server,
and a custom shared backend. The user chose to keep building Marquee and share the
backend with a separately developed Tauri app.

Proposed builder choices requiring review: TMDB supplies title identity, genres,
keywords and related-title candidates; our own algorithm combines and ranks them.
TMDB is not the playback source. A server-only `TMDB_READ_TOKEN` is required for
rich metadata; none is currently present in the project's `.env`. No key belongs
in source control or shipped APKs. Supply TMDB attribution in the app and docs.

Use SQLite for durable server state and Android SQLite for durable TV watch data,
pending synchronization and a small provider title index. Keep the existing
Node service and Kotlin UI. Use Node's built-in SQLite with a documented minimum
Node 22.13; omarchy currently runs Node 26.12. Keep persistence access behind a
small module to accommodate supported Node SQLite class names.

## Current constraints

- `HistoryStore` is a 40-item resume list in SharedPreferences and removes titles
  at 95% completion. It cannot be the lasting taste record.
- `Recommendations` currently ranks broad provider categories; its candidate
  collection truncates categories before ranking. It does not understand themes.
- `Catalog.Item` contains source-specific IDs, title, category, image and extension,
  not canonical identity or rich semantic metadata.
- Profiles, favourites and resume state are currently local. Do not represent
  full cross-device favourites/resume synchronization as already implemented.
- `HomeActivity` must route both `gd:` and `fx:` online recommendation IDs through
  lazy online resolution; provider URL construction must never receive them.
- The artwork cache is now correctly bounded to 8 MiB. Views and decode buffers
  can still retain images independently of this cache.
- Existing Access credentials protect the household API, not individual users
  inside that household. Household profiles are not adversarial security boundaries.
- Cloudflare permits 5 requests per 10 seconds per IP. Batch watch updates and
  recommendation retrieval; do not sync the player's 10-second tick over HTTP.

## Data and identity

Keep Continue Watching's current behaviour. Add a separate durable watch ledger
that retains completed titles and records media kind, source ID, canonical ID
when known, title/year, watched duration, completion and last meaningful viewing.
Associate series evidence with the series, not each episode as a separate taste.
Exclude live TV from this version's learning.

Capture the active profile at playback start so late callbacks cannot write to a
different profile. Measure actual playing time, excluding pauses and buffering;
seeking to the end is not evidence of watching an entire film. Merge repeated
updates idempotently by playback-session ID and monotonically increasing revision.

Bootstrap existing resume entries once, preserving provenance and marking their
watch-time evidence as approximate. Completed titles removed by older versions
cannot be reconstructed. The three titles explicitly supplied by Theo may be
added to THEO as explicit watched seeds, not fabricated viewing-time records.

Backend profile IDs are server-generated UUIDs. Register a device UUID and map its
local profile IDs to backend profiles. Do not automatically merge profiles by
display name or reuse the Android `default` ID globally. Publish profile listing
and explicit linking endpoints for the future desktop client; persist the map.
Registration is idempotent for a device/local-profile pair. A client explicitly
links a new, unmapped local profile to an existing server UUID; changing an
established mapping is rejected with 409 in v1, avoiding ambiguous data migration.
The desktop should link before uploading any evidence. Renaming a display name
does not change identity.

Never store signed playback URLs, provider passwords or Access client secrets in
the watch ledger, recommendation cache, logs, fixtures or shared metadata.

## Candidate retrieval and ranking

Resolve titles by media kind, normalized title and year when present; prefer
known TMDB IDs. If identity is ambiguous, do not merge remakes or film/TV versions.
Return unresolved identity as such rather than confidently attaching wrong data.

Select at most 12 distinct meaningful seed titles per refresh, balancing recent
viewing with established completed favourites. Count actual viewing of at least
5 minutes as a weak signal, 20 minutes or 20% of duration as a normal signal, and
80% actual viewing as a strong one; explicit watched seeds are normal signals.
Cap the influence of repeated episodes within one series. Never infer a dislike
from a failed stream, short test play, pause or network failure.

Generate at most 240 candidates from related-title lists for those seeds. Score
using a normalized blend of related-title evidence (45%), keyword overlap (30%),
genre overlap (20%), and a small vote-count-adjusted quality prior (5%). Combine
evidence across seeds and apply recency decay with a 90-day half-life and a 0.25
floor. These are initial tunable constants, not a claim of measured optimality.
For sparse metadata, renormalize available components rather than inventing tags.

Deduplicate by canonical kind/ID; exclude already watched or substantially started
titles from discovery. Continue Watching remains their home. Diversify the first
20 so one dominant genre does not occupy more than 12 slots when other eligible
choices exist. Do not pad a short result with unverified or already watched titles.
Attach truthful reasons naming an actual seed and contributing shared evidence.
Suits is an evaluation candidate, not a special hard-coded production rule.

For a new profile, return an explicit cold-start state and retain the existing
non-personalized discovery fallback. Do not label arbitrary picks as learned taste.

## Source matching and online availability

Maintain a disk-backed title index from the existing provider catalogues. The TV
matches a small candidate page against that index without loading all 48k titles
just for recommendations. Exact kind/title/year matches take precedence over
online listings. Preserve provider IDs and playback details only on the client.

For unmatched candidates, the backend checks online title listings in a bounded
background queue: concurrency 1, at most 8 new availability searches per refresh,
and at most one refresh per profile at a time. Reuse shared listing cache entries
for 24 hours, including shorter negative cache entries (1 hour). Limit metadata
HTTP concurrency to 2 with deadlines, bounded retries and response-size limits.

Return only source-matched cards in the final displayed page. An online listing
means the title is listed, not that a file has been verified playable. Do not mint
or probe a video stream for every recommendation. Resolve freshly on Play, and
handle dead upstream files with an honest playback message.

On a cold refresh return cached results or an explicit pending state immediately;
the background job enriches results. Poll only while Home is visible, no sooner
than a server-supplied retry interval of at least 15 seconds, and stop on leaving.
Never put Chromium work on the critical path for provider playback.

## Shared HTTP contract (v1)

Keep existing resolver paths compatible. Add versioned JSON endpoints behind the
same authentication gate, rate limiter and public hostname:

- `POST /v1/devices/register`: device UUID and local profile descriptors; return
  persistent local-to-server profile mappings without name-based merging.
- `GET /v1/profiles`: household profile IDs and names for explicit desktop linking.
- `POST /v1/devices/profiles/link`: device UUID, previously unmapped local profile
  ID and existing server profile UUID; return the mapping and current watch epoch.
  Reject a conflicting existing mapping with 409; never merge histories implicitly.
- `POST /v1/watch/batch`: device ID and up to 50 session/revision updates; return
  accepted revisions. Each update carries its profile's watch epoch. Server
  validates profile membership, epoch and payload fields.
- `GET /v1/recommendations?profileId=<uuid>&limit=20&cursor=<opaque>`: at most 20 canonical candidate
  records with `kind`, `tmdbId`, `title`, `year`, artwork paths, reason, and optional
  matched online `gd:` ID; plus `state`, `generatedAt`, `revision`, `retryAfterS`.
  Client provider matching may replace an online source, never canonical identity.
  Include `nextCursor`, bound to the profile, watch epoch and immutable ranking
  revision; null when exhausted. A stale cursor yields 409 and the client restarts
  at the first page. A Home visit examines at most 3 candidate pages (60 records),
  one at a time, until 20 eligible displayed cards are found. Discard rejected
  candidates as each page is matched; never keep all 240 candidates on the TV.
  Do not auto-fetch more than 3 pages or spin when sources remain unavailable.
- `DELETE /v1/profiles/<uuid>/watch`: explicit reset of that profile's learned
  viewing data and recommendation cache, not another profile's data.
  In one transaction increment `watchEpoch` and delete its learned records and
  cached rankings. Return the new epoch. Every job checks its captured epoch
  before publishing; every stale watch batch is rejected with 409 plus the current
  epoch. On reset/epoch mismatch, clients clear that profile's local learning
  ledger, pending older-epoch updates and recommendation cache before resyncing.
  Only new viewing after acknowledging that epoch may be uploaded. Existing resume
  entries are not reimported: persist the one-time migration marker across reset.
  The server checks an update batch's epochs before committing any of its rows.

Do not expose session credentials in responses. Cap incoming JSON at 256 KiB;
return 400 for invalid data, 413 for oversized requests, 401 for auth failure and
429 with Retry-After for throttling. Missing metadata credentials produce an
explicit unavailable state, not simulated recommendations. Document request and
response fixtures so Tauri can implement against them without this worktree.

## Memory, lifecycle, persistence and reboot behaviour

TV: retain one 20-candidate page per active screen, at most 20 rendered personal
cards, and a disk cache of the last successful page per profile. Use recycled
views for the recommendation row, display-sized decode limits, an 8 MiB artwork
cache, a maximum of 2 image decode workers, and discard late results for old
profiles/screens. Never decode full-size poster/backdrop originals needlessly.

SQLite queries use bounded pages; never deserialize the entire watch ledger.
Keep at most 50 pending watch updates in memory; persist the rest and read in
batches. Coalesce completed revisions on disk, not an unbounded memory queue.
Network failure must not block playback or overwrite cached results with empty data.

Server: durable SQLite watch records and profile maps; at most 240 candidates per
refresh and 200 metadata records in a process LRU. Bound metadata/listing disk
cache to 5,000 entries with expiry and pruning; retain user watch records until
explicit reset. Use atomic transactions and an exclusive worker/job queue. Do not
cache full catalogues or profile histories as global JavaScript arrays.

Add a persistent data directory, a Docker named volume, systemd environment-file
support for the metadata token, and deployment exclusions so code updates never
overwrite databases or secrets. Ignore local data directories in git. Reuse the
existing systemd restart/boot setup; a stopped process does not erase learned taste.
Recover interrupted background work from durable state or rebuild its bounded
queue on demand after restart. Leave the resolver's existing resource ceilings.

## Verification and acceptance

- Pure ranking fixtures: multiple legal-drama seeds favour relevant unseen series;
  romance remains represented; completed titles excluded; duplicate episodes do
  not drown out other interests; unknown metadata does not fabricate similarity.
- Identity fixtures: remakes, same-name film/TV, non-Latin titles and decorated
  provider titles; online/provider duplicates merge without losing playback IDs.
- Persistence/API tests: restart retains watch data and profiles; duplicate and
  out-of-order batches do not double-count; profile isolation/reset; auth, malformed
  payloads, timeouts, 413/429; missing token; failed refresh retains last good page.
- Android tests: 95% completion clears resume but preserves watched evidence;
  seek-only sessions do not become completed; capture profile identity at play;
  offline batching and migration; late request after profile switch is discarded.
- Device checks: repeated Home/profile/search/play cycles, provider and online
  playback from recommendation cards, no secrets in logs. Record heap/PSS after
  warmup and 30 cycles; after GC, Java heap must not trend monotonically upward or
  exceed the warmed baseline by more than 20 MiB. The existing artwork regression
  must still pass. A finite soak test is evidence, not proof against every leak.
- Functional check with Theo's three supplied watched seeds, using real metadata
  and source matching. Report actual output; do not guarantee a named show is
  available until its source listing has been checked.

## References

- Existing source: `HistoryStore.kt`, `Recommendations.kt`, `ProfileStore.kt`,
  `HomeActivity.kt`, `PlayerActivity.kt`, `ImageLoader.kt`, `resolver/server.js`.
- TMDB candidate and keyword APIs:
  https://developer.themoviedb.org/reference/tv-series-recommendations
  https://developer.themoviedb.org/reference/tv-series-keywords
- Node SQLite: https://nodejs.org/api/sqlite.html
- Jellyfin architectural reference: https://github.com/jellyfin/jellyfin

## Not part of this iteration

Tauri screens, Plex/Jellyfin migration, LLM recommendations, third-party viewing
telemetry, full remote multi-household accounts, cross-device favourites/resume
sync, external subtitle acquisition, and making home-minted stream URLs playable
through a different public IP. Future clients can share profile/taste data through
this API; full playback-state synchronization is a separate feature.
