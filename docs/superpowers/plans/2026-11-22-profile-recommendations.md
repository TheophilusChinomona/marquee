# Profile Recommendations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Durable per-profile learning and explainable provider/online recommendations shared through a desktop-ready API.

**Architecture:** Native Kotlin clients keep durable local viewing evidence and small cached pages. The existing Node service owns SQLite profile identity, metadata, ranking and bounded online listing checks. Playback resolution remains lazy.

**Tech Stack:** Kotlin/Android SQLite, Node >=22.13/built-in SQLite, node:test, TMDB HTTP metadata, existing Goojara resolver.

**Spec:** `docs/superpowers/specs/2026-11-22-profile-recommendations-design.md`

## Global Constraints

- Work in this worktree as explicitly requested; preserve its existing uncommitted app/resolver changes. Do not create a second worktree or commit unrelated work.
- No Tauri UI changes, no secrets in tracked files, no LLM, no signed URL persistence.
- 20 displayed cards; 20 candidates/page, at most 3 candidate pages/Home visit; 240 candidates/refresh; 12 seeds; metadata concurrency 2; listing concurrency 1 and at most 8 searches/refresh.
- SQLite durable state; 200 metadata objects in memory; 5,000 disk cache entries; JSON requests <=256 KiB; watch batches <=50.
- Missing TMDB token is explicit unavailable state. Never substitute fabricated semantic recommendations.
- Existing stream endpoints, authentication and resource ceilings remain compatible.

## Review Focus

- Completed titles removed from resume must remain in the learning ledger (task 4).
- Offline/replayed batches and interrupted/reset jobs must not resurrect cleared taste (tasks 1, 3, 4).
- Source identity/remakes and provider-vs-online duplicates must retain playable IDs (tasks 2, 5).
- Profile switches must invalidate late results and capture playback ownership (tasks 4, 5).
- Metadata/provider outages and cold-start pending work must preserve last good results (tasks 3, 5).

## Task 1: Durable household identity and watch store

Files: create `resolver/recommendations/store.js`, `resolver/test/recommendation-store.test.js`.
Interfaces: `Store(path)` with register/link/list profiles, epoch-checked batched session upsert, bounded seed queries, reset, bounded metadata cache and ranking page persistence.

- [ ] Write node:test cases for registration idempotence, cross-device explicit linking, conflicting mappings, profile isolation, restart persistence, duplicate/out-of-order revisions and stale reset epochs.
- [ ] Run `node --test test/recommendation-store.test.js`; observe failure before implementation.
- [ ] Implement transactional SQLite store, bounded queries, validation, reset epochs, JSON-safe rows and close.
- [ ] Rerun tests and verify no watch data/key/URL leaks through cache payloads.

## Task 2: Metadata and own ranking

Files: create `resolver/recommendations/rank.js`, `metadata.js`, `service.js`; tests `resolver/test/recommendation-ranking.test.js`, `recommendation-service.test.js`.
Interfaces: `rank(seeds,candidates,now)` returns scored canonical candidates/reasons; injectable TMDB client and listing lookup allow offline fixtures. Service refresh owns single-flight state, epoch checks and durable results.

- [ ] Test legal-drama/romance blend, episode influence cap, watched exclusion, duplicate kind/ID, missing metadata and no fabricated reasons; identity fixtures include remakes, film/TV homonyms and non-Latin text.
- [ ] Observe failures; implement documented ranking constants, TMDB identity/details/related requests, request deadlines/size limits and bounded caches.
- [ ] Test missing-token/cold-start states, one refresh/profile, max 12 seeds/240 candidates/8 listing checks, reset during refresh, errors retaining last page.
- [ ] Implement bounded enrichment and online listing matching with provenance; rerun tests.

## Task 3: Versioned HTTP API and deployment

Files: create `resolver/recommendations/http.js`, API tests; modify `resolver/server.js`, `package.json`, deployment files, `.gitignore`, resolver README; create `docs/recommendations-api.md`.
Interfaces: endpoints exactly as spec; `createRecommendationHandler({store,service})` returns a handler used after existing auth/quota checks. Export server creation or test handler through a temporary real HTTP listener.

- [ ] Write real HTTP tests for devices, link, profiles, batches, epochs/reset, cursors, missing token, 400/413/409 and unsupported methods.
- [ ] Observe failures, implement bounded JSON parsing and profile-bound revision/epoch cursors; add POST/DELETE CORS methods without weakening authentication.
- [ ] Verify tests; document desktop payload examples, attribution, server-only TMDB token and operational startup/reboot behaviour.
- [ ] Add persistent Docker volume/data path, systemd environment file and safe deploy exclusions. Test restart preservation using temporary directories.

## Task 4: Android durable viewing ledger

Files: create `WatchLedger.kt`, extend `ImageLoaderMemoryTest.kt` instrumentation runner with ledger checks; modify `PlayerActivity.kt`, `ProfileStore.kt`.
Interfaces: SQLite local sessions/revisions/profile maps/pending batches; capture profile/session at playback start, playing-time deltas, seed explicit watched imports and idempotent resume migration.

- [ ] Device test: completion survives resume removal, seek-only not completed, duplicate session revisions do not double-count, reset keeps migration marker, profile isolation and bounded pending batch reads.
- [ ] Observe failures; implement ledger and playback lifecycle hooks with monotonic actual-playing time, immutable profile ownership and no network on playback ticks.
- [ ] Run Android regression instrumentation including existing bitmap cache test.

## Task 5: Android shared recommendations and bounded presentation

Files: create `RecommendationClient.kt`, `ProviderTitleIndex.kt`; modify `HomeActivity.kt`, `Recommendations.kt`, `ImageLoader.kt` and recommendation rail integration.
Interfaces: background register/sync/get pages, cached per-profile last-good cards, local SQLite provider lookup streamed from bundled catalogues, provider-first canonical matching, source-safe navigation.

- [ ] Test canonical provider matching, online ID routing, stale profile replies, epoch rejection and offline-cache retention.
- [ ] Implement one visible-screen refresh, <=3 pages/20 cards, batch sync, explicit unavailable/pending states and truthful reasons; keep fallback labelled as discovery.
- [ ] Bound decode workers to 2 and sampled image dimensions, recycle recommendation views; cancel/discard old results.
- [ ] Build release and run regression tests.

## Task 6: Integration verification and delivery

- [ ] Run Node tests, release build and device instrumentation; `git diff --check`.
- [ ] Review implementation against spec with a fresh reviewer; resolve material findings.
- [ ] Deploy service without overwriting state/secrets; verify unauthenticated requests blocked, authenticated versioned API and restart persistence.
- [ ] With a configured TMDB token, verify Theo's actual seed recommendations and provider/online navigation. Otherwise clearly report credential-dependent checks as unverified.
- [ ] Install signed APK preserving TV settings, verify provider/online playback, and measure warm heap/PSS over 30 navigation/profile cycles.
- [ ] Deliver API contract for Tauri and record exact tests/remaining limitations. Do not publish a GitHub release without the separately requested release workflow.

## Execution ledger

2026-11-22: User approved the design. Native inline execution is the default for this tightly coupled change; this workspace is explicitly selected and already contains the work it extends. Keep files uncommitted until integration/release scope is separately settled. Metadata credential is absent; implementation and deterministic tests can proceed without it, but real semantic output cannot be claimed until configured.

2026-11-22 implementation results:
- Tasks 1-3 implemented: 22 Node tests pass locally, covering durable identity,
  idempotent batches, reset epochs, ranking/identity, metadata cache expiry and
  concurrency, HTTP validation and auth. Backend deployed with private TMDB token
  and persistent SQLite. Authenticated API returns 200; unauthenticated returns 403.
- Tasks 4-5 implemented and release/test APKs compile. Native instrumentation now
  covers artwork eviction, ledger persistence/isolation, captured epochs,
  bounded batches, provider remake/kind matching. Device execution is pending:
  the user confirmed the Fire TV is powered off. Do not mark those tests passed.
- Real metadata evaluation on a disposable verification profile: 60 ranked
  candidates from The Good Wife, Boston Legal and Just Like Heaven; Suits rank 15,
  and provider catalogue contains EN| Suits (ID 20832). Some top legal-drama
  titles also matched online listings. The verification profile is removed after
  validation, leaving shared metadata cache only; do not confuse it with the
  not-yet-registered TV profile. Its three explicit seed statements can be imported
  into the real THEO mapping once the TV first registers.
- Independent review found/fixed cross-kind provider ID collisions, missing
  canonical watched exclusions, stale ranking/reset display, raw year loss,
  visit-wide pagination, cursor rollover and invalidation cooldown behaviour.
- Task 6 partial: final build and server checks are performed; TV playback,
  instrumentation and 30-cycle memory soak remain unrun while TV is off. GitHub
  publishing is separate: release APK first, version.json afterward. No public
  release or manifest bump is implied by local builds or server deployment.
