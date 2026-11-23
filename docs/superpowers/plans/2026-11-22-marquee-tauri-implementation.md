# Marquee Tauri Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task by task. Keep a progress ledger, write meaningful behavior tests before implementation, and run the stated checks. Use a fresh whole-change reviewer at the end; do not fan out implementation by default.

**Goal:** Turn the approved desktop preview into a working Windows and Linux IPTV application without redesigning the interface.

**Architecture:** Keep React/TypeScript/Vite for presentation. A typed bridge calls Rust services for Xtream access, SQLite, credentials, recommendations, and embedded libmpv. Rust owns the playback session and saves progress even when a React screen unmounts.

**Tech Stack:** Tauri 2, stable Rust, React, TypeScript, Vite; proposed Rust dependencies: reqwest with rustls, rusqlite, keyring, serde, tokio, url, and a small libmpv adapter. Verify versions/APIs at implementation time, commit Cargo.lock and package-lock.json, and avoid adding overlapping state or media frameworks.

**Spec:** `docs/superpowers/specs/2026-11-22-marquee-desktop-design.md`

## Current baseline and decisions

- UI approved by the user: commit `eb45acd`. Preserve its layout, colours, typography, artwork treatment, and navigation. Screenshots in `desktop/design-review/` are visual references.
- Existing React UI is a sample-data prototype in `desktop/src/App.tsx`, `App.css`, and `preview-data.ts`. Playback, provider setup, persistence, profile isolation, and guide schedules are simulations.
- Tauri is an initial scaffold, with a sample greet command, generic app identity/icons, and no native media dependencies. A successful frontend build does not prove a native build.
- Rust installation was verified previously. Recheck MSVC/Windows SDK and compile a program before claiming native readiness; the earlier installer was not verified to completion.
- Keep `npm run dev` as the browser design preview. `npm run tauri dev` uses the real backend and starts at provider setup if no account exists. Preview data must never silently replace failed provider requests.
- This plan supersedes the earlier pre-preview task breakdown. The earlier file remains for history and points here.
- Use inline execution to control usage, with focused tests and one independent final review. No further design rounds unless a technical constraint requires a visible product change.

## Global Constraints

- First targets: Windows x86-64 installer and Linux x86-64 AppImage and Debian package, with Ubuntu 22.04+ as the initial Linux validation baseline.
- Rust owns provider requests, credentials, persistence, and libmpv lifecycle.
- No cloud profiles, telemetry, Android data migration, recording, downloads, or new external metadata services are proposed.
- No sample provider credentials or real stream URLs enter the repository or logs.
- Profile/provider identity isolates favorites and history; refresh preserves last good data.
- Embedded native playback is required. A separate external-player window or browser playback is not a substitute.
- Bundle pinned player dependencies for end users. Development may use an explicitly configured local runtime.
- Existing Android source, APK releases, and root `version.json` remain separate.
- Signed desktop updates are a release-stage dependency, not something to represent as working before configuration exists.

## Review Focus

1. Colliding provider IDs or a profile switch must not leak another account/profile's list, progress, or recommendations: Tasks 3, 5, 6.
2. Interrupted refresh and stale concurrent requests must preserve a complete usable catalogue: Tasks 4, 5.
3. Manual stop, reconnect, duplicate EOF, and network failure must not skip episodes or corrupt resume: Task 6.
4. Locked/unavailable Linux secret storage must permit explicit session-only login without plaintext fallback: Task 3.
5. Missing artwork/metadata, empty catalogues, unavailable EPG, and large libraries need useful, responsive UI: Tasks 4, 5, 7.

## Module map

```text
desktop/src/
  App.tsx                       app bootstrap and route state
  App.css                       approved shared styles
  api/{types,client,preview}.ts  typed contracts and explicit preview adapter
  components/                   Header, TitleCard, Rail, StatusView
  features/                     Home, Browse, Details, LiveGuide, Search,
                                MyList, Profiles, Settings, Connect, Player
  hooks/                        request cancellation and native subscriptions

desktop/src-tauri/src/
  lib.rs                        app lifecycle and command registration
  commands.rs                   thin validated Tauri handlers
  models.rs                     serializable camelCase DTOs
  provider.rs                   Xtream HTTP and response normalization
  credentials.rs                secret-store/session credential adapter
  store.rs + migrations/        SQLite operations and versioned schema
  catalog.rs                    refresh orchestration and paged queries
  recommendations.rs            local recommendation rules
  player/{mod,ffi,session}.rs    native renderer, mpv ownership, playback state

desktop/scripts/                verified runtime setup and platform checks
desktop/tests/                  frontend user-flow tests and fixtures
.github/workflows/desktop.yml   native build/test/package matrix
```

## Shared contract

Write these definitions in `api/types.ts`, mirror them in `models.rs`, and document commands in `desktop/CONTRACT.md` before wiring screens. Use camelCase JSON, string provider IDs, and integer millisecond durations/timestamps. Never use a title string as an episode identifier.

- `MediaKind = 'live' | 'movie' | 'series'`; `MediaRef = { kind, id }`.
- `MediaItem = { ref: MediaRef, name, categoryId, posterUrl?, backdropUrl?, year?, rating?, containerExtension? }`.
- `Profile = { id, name, avatar }`; provider scope comes from the current authenticated backend, not an arbitrary caller-supplied account ID.
- `Page<T> = { items: T[], total: number, offset: number, limit: number }`; query limit defaults to 60, maximum 200.
- `AppError = { code, message, retryable }`; codes: validation, unauthenticated, network, provider, storage, player, unsupported. Messages must redact secrets.
- All commands return a DTO or `AppError`, not raw HTTP or mpv diagnostic text.
- `app_bootstrap()` returns connection state, credential-storage status, profiles, activeProfileId, cache status, and settings. Never returns a password.
- `provider_connect({ baseUrl, username, password, remember })` validates and creates a session; the password is a one-time input and is immediately cleared from form state after submission. `provider_disconnect()` stops/saves playback and removes saved secrets/session.
- `catalog_query({ kind?, categoryId?, query?, offset, limit, sort })`, `catalog_categories({ kind })`, `catalog_refresh()`, `media_details({ ref })`, `epg_query({ channelIds, fromMs, toMs })`.
- `profiles_list()`, `profile_create({ name, avatar })`, `profile_update({ id, name, avatar })`, `profile_remove({ id })`, `profile_activate({ id })`.
- `favorites_list({ offset, limit })`, `favorite_set({ ref, saved })`, `history_list()`, `home_sections()`.
- `player_start({ ref, episodeId?, resume: boolean })`, `player_control({ action, value? })`, `player_snapshot()`.
- Player actions: pause, resume, seekAbsoluteMs, seekRelativeMs, volume, mute, audioTrack, subtitleTrack, previousEpisode, nextEpisode, selectEpisode, stop. Validate action/value pairs in Rust; no arbitrary mpv commands from JavaScript.
- `PlayerSnapshot = { sessionId, media, episodeId?, status, positionMs, durationMs?, seekable, volume, muted, tracks, selectedAudioId?, selectedSubtitleId?, queue, error? }`; status: loading, playing, paused, buffering, ended, stopped, error.
- Events: `catalog-progress` with refreshId/status/counts; `player-state` with sessionId and snapshot. Register listeners before starting operations, unlisten on teardown, and ignore obsolete session/refresh IDs. Bootstrap/snapshot commands recover state after frontend reload.

## Task 1: Run the approved UI inside a real desktop shell

**Files:** modify `desktop/src-tauri/{Cargo.toml,tauri.conf.json,src/lib.rs,capabilities/default.json}`, `desktop/package.json`, `desktop/src/App.tsx`; create `desktop/src/api/{types,client,preview}.ts`, `desktop/CONTRACT.md`, `desktop/rust-toolchain.toml`.

**Interfaces:** establish the shared contract above and explicit native-versus-preview adapters. Later tasks implement behind these contracts.

- [ ] Verify `rustc --version`, `cargo --version`, MSVC, Windows SDK, and WebView2; compile/run a temporary Rust hello-world. On Linux verify GTK/WebKitGTK development packages and compiler prerequisites.
- [ ] Brand app title/product/icons as Marquee; use a 1280x800 initial window, 900x600 minimum, native decorations, persisted size/position, and working fullscreen. The prototype strip must not duplicate a real OS title bar unnecessarily.
- [ ] Split components from the existing file as they are wired, preserving screenshots. Move sample data behind the preview adapter; native app never enters demo mode automatically.
- [ ] Add Vitest for behavior and React Testing Library for screen flows. Test `native_bootstrap_failure_shows_retry`: a failed native bootstrap renders retry/setup and zero sample movie cards. Run failing, implement, rerun passing.
- [ ] Run `npm run build`, `npm test -- --run`, `cargo check --manifest-path src-tauri/Cargo.toml`, and launch `npm run tauri dev` from `desktop/`.
- [ ] Commit the working shell and contract.

**Exit:** the approved UI opens in a native Marquee window; browser preview still works independently.

## Task 2: Prove embedded playback on both target platforms

**Files:** create `desktop/src-tauri/src/player/{mod,ffi}.rs`, `desktop/scripts/{setup-mpv.ps1,setup-mpv.sh}`, `desktop/resources/player-manifest.json`; wire shell/player surface.

**Interfaces:** `NativePlayer::load(source: &str, start_ms: u64)`, `command(PlayerCommand)`, `snapshot() -> PlayerSnapshot`, `shutdown()`. Internal source never crosses back to frontend. Production `player_start` is added after provider resolution exists.

- [ ] Evaluate a maintained Rust binding against the required API; avoid writing a broad FFI wrapper when a small existing binding is adequate. Prefer mpv's render API; select platform composition only after a real visual experiment.
- [ ] Embed a lawful local test video beneath/alongside the existing React overlay in the same app. Verify overlay clicks, focus, DPI, resize, minimize/restore, fullscreen, audio/subtitle tracks, and shutdown.
- [ ] Record chosen Windows and Linux composition in `desktop/CONTRACT.md`. Test Linux X11 and a Wayland session; explicitly document/test XWayland if required. Do not select a plugin whose Linux embedding is known not to work.
- [ ] Add runtime loading tests: missing library yields `code = 'player'`, loading error is actionable, failed initialization frees resources, repeated stop/shutdown is safe. Observe failures before implementing the adapter.
- [ ] Runtime setup must pin download/source revision, verify SHA-256, use app-owned resource paths, include notices/source obligations, and never fetch an unpinned latest build during packaging.
- [ ] Run Rust player tests, then real playback smoke tests on Windows and Linux. Record artifact, runtime version, display session, and result.
- [ ] Commit the verified integration. If Linux hardware/display access is missing, record that gate as blocked and continue independent provider work; do not mark cross-platform playback proven.

**Exit:** actual embedded video works with Marquee controls. Native feasibility is resolved before extensive backend wiring.

## Task 3: Connect accounts and persist isolated local state

**Files:** create `credentials.rs`, `store.rs`, `migrations/001_initial.sql`, auth portions of `provider.rs`, `commands.rs`; wire `features/{Connect,Profiles,Settings}.tsx`.

**Interfaces:** implement bootstrap/connect/disconnect/profile/favorite/history commands; `Store::open(path)`, `CredentialStore::{load,save,delete}`, internal `ProviderSession`. Credentials never enter SQLite.

- [ ] Write tests with temporary SQLite files and fake secret-store adapters: same media ID in providers A/B remains separate; profile A's list/history is invisible to B; deleting active profile selects a valid remaining profile; last profile cannot be deleted; schema migration/reopen preserves data.
- [ ] Add tests: secret-store failure plus `remember=true` returns explicit session-only status; wrong password does not overwrite the previous valid connection; logout clears secrets; profile rename preserves identity and watch data. Run these failing before implementation.
- [ ] SQLite tables: providers, profiles, preferences, categories, catalogue_items, refresh_runs, favorites, history. Use foreign keys and schema versioning; favorites/history keys include provider, profile, kind, and media ID. Series history additionally stores episode ID and parent series ID.
- [ ] Use Windows Credential Manager and Linux Secret Service. A locked/unavailable store permits session-only use with a clear UI state, never plaintext fallback. Do not promise stored credentials when saving failed.
- [ ] Normalize base URLs, preserve legitimate provider base paths, permit HTTP, require host and HTTP(S), reject embedded URL credentials/query/fragment. Encode username/password in queries and stream path segments correctly. Auth validation uses Xtream account state and sanitized errors.
- [ ] Replacing provider stops/saves playback first and atomically changes active scope only after authentication. Logout makes cached browsing available only under an explicit disconnected/offline state; no old credentials retained accidentally.
- [ ] Run store/auth/credential tests and native setup/profile flows, restart app, then commit.

**Exit:** real login and profiles survive restart; lists/history are isolated correctly.

## Task 4: Synchronize provider catalogues and details

**Files:** `provider.rs`, `catalog.rs`, Rust models/commands, fixture JSON under `desktop/src-tauri/tests/fixtures/`, paging hooks in `desktop/src/hooks/`.

**Interfaces:** implement catalogue/categories/refresh/details/EPG commands from shared contract; `resolve_stream(ref, episode_id) -> ResolvedStream` stays internal to Rust.

- [ ] Write fixture tests for numeric/string IDs and ratings, null fields, malformed JSON, empty categories, movie/series responses, season/episode ordering, missing extensions, base64 EPG, invalid timestamps, and missing guide data. Use a local HTTP fixture server; no live provider in automated tests.
- [ ] Add atomic-refresh test: begin with catalogue A, fail a later category fetching B, assert queries still return A. Cancellation/provider switch must not publish partial B or late events into the new account.
- [ ] Implement category-by-category refresh with at most 4 requests in flight, 30-second request timeout, cancellation, and sanitized retry status. GET retries only for transient network/429/5xx failures, at most 2 retries with bounded backoff and Retry-After handling. Never retry rejected credentials as if transient.
- [ ] Stage rows by refresh generation; switch active generation in one transaction only after successful completion. A valid authenticated empty catalogue is a success; no-category and malformed-response failures must be distinguishable.
- [ ] Refresh after 24 hours with manual refresh, retain last good data on failure, and show progress/retry. Query SQLite by page, search indexed normalized names, cap returned rows, and cancel obsolete UI queries.
- [ ] Resolve live/movie/episode URLs internally using provider IDs/extensions. Normalized DTOs omit passwords and credential-bearing stream URLs. Artwork must use safe HTTP(S) sources; strip unsafe schemes and show a fallback on failure. Copy required fonts into app assets for offline UI.
- [ ] Run provider/catalogue tests and Rust checks, then commit.

**Exit:** complete real provider library is available without rendering/loading it all at once.

## Task 5: Wire the approved browsing screens and recommendations

**Files:** `features/{Home,Browse,Details,Search,MyList,Profiles,Settings}.tsx`, `components/{TitleCard,Rail,StatusView}.tsx`, `recommendations.rs`, frontend flow tests.

**Interfaces:** consume Task 4 pages/details, Task 3 profiles/favorites/history; implement `home_sections()` from cached data and profile activity.

- [ ] Port Android recommendation behavior from `Recommendations.kt`, including category affinity, seen-item filtering, deterministic novelty, and diversity cap. Tests assert stable order for same profile/input, different affinity changes ranking, and seen titles are excluded.
- [ ] Home wires all parity rails: Continue Watching, Live Now, For You, Because You Watched, Top 10, New Releases, My List. Use provider/local ranking evidence; do not claim platform-wide popularity that no source supplies.
- [ ] Replace sample movies/series with paged data, provider categories, real sorting/filtering, complete details, related titles, season picker, episode IDs, and progress. No hardcoded Shogun episodes, cast, ages, HDR, or 4K claims on unrelated titles.
- [ ] Profiles CRUD and profile-scoped favorites update every affected view. Test switching between two profiles changes My List and Home. Rapid search tests assert stale earlier results never replace the latest query; search handles no results and special characters.
- [ ] Test provider with zero titles, missing poster/year/rating, and offline cache. Keep useful empty/error/retry states instead of displaying fabricated content. Unknown metadata is omitted.
- [ ] Add grid/list virtualization or bounded paging and horizontal rail paging; exercise a 50,000-item fixture and verify the DOM contains only visible/bounded cards.
- [ ] Compare approved screenshots at 1440x1000 and the native minimum size; use bundled font assets. Run frontend tests/build and recommendation tests, then commit.

**Exit:** browsing, search, lists, profiles, and Home work against real local/provider data with the approved UI intact.

## Task 6: Connect playback, resume, and episode queues

**Files:** `player/session.rs`, `commands.rs`, `features/Player.tsx`, `store.rs` history operations, session tests.

**Interfaces:** implement `player_start`, typed controls, snapshot/events, and backend-owned queue; starting accepts identifiers only and resolves through Task 4.

- [ ] Write deterministic session tests against a fake media engine: manual stop/error/replacement never advances; natural EOF advances exactly once; duplicate/stale EOF cannot advance again; final episode ends cleanly; autoplay off stops at end; previous/next/select use provider episode IDs.
- [ ] Resume rules match Android: ignore unknown/nonpositive duration, clamp negative position to zero, remove Continue Watching entry at progress >= 95%, keep at most 40 recent history entries per provider/profile. Assert 94% retained and 95% removed. Store completed status separately if needed for recommendation affinity.
- [ ] Save every 10 seconds, on pause/seek/item change/stop, profile/provider switch, and orderly exit. Tests close/reopen store and confirm correct episode ID/position; progress from old session cannot overwrite new playback.
- [ ] Wire real mpv states to React controls: play/pause, +/-10 seconds, bounded seek, volume/mute, available audio/subtitle tracks, fullscreen, episode picker, next/previous, and saved player preferences. Disable seek for nonseekable live streams.
- [ ] Handle stream load errors/retry/reconnect without false completion. A natural-end decision must use mpv end reason plus session state, not every EOF-looking notification.
- [ ] Lifecycle owns event polling, output callbacks, native surface and library lifetime; all resources end on shutdown. Handle window close by saving/stopping before destruction. Never block the UI thread waiting for network.
- [ ] Test real movie/series/live samples on both platforms, restart/resume, fullscreen, window close during playback, and changing profile. Run suite and commit.

**Exit:** real playback replaces simulated timer controls and survives everyday stop/resume/episode flows.

## Task 7: Complete live guide and desktop interaction

**Files:** `features/LiveGuide.tsx`, EPG normalization/cache in `provider.rs`/`catalog.rs`, integration tests, native window hooks.

**Interfaces:** `epg_query` accepts at most 50 visible channel IDs and a bounded 24-hour window. Deduplicate in-flight requests and cache by provider/channel/window.

- [ ] Test timezone conversion, programme overlap, day-boundary shifts, missing/invalid programme timestamps, and no-EPG channels. Now marker uses actual local time, not the preview's fixed date/time.
- [ ] Wire virtualized channels, category selection, lazily loaded guide cells, synced timeline scroll, current programme detail, and channel switching through player_start. Upcoming programmes are informative; no recording/reminders feature is implied.
- [ ] Preserve keyboard focus while refreshing rows. Verify tab order, Enter/Space actions, Escape/back, Ctrl+K search, player shortcuts, readable contrast, and reduced-motion behavior at desktop sizes.
- [ ] Add request/listener cleanup checks: switching screens/profiles must not duplicate polling or emit old content. Restore window geometry on a changed monitor setup without placing window offscreen.
- [ ] Run guide/flow tests, visual/native checks, and commit.

**Exit:** Live TV is a usable real guide, with complete keyboard/mouse navigation.

## Task 8: Build installable artifacts and prepare updates

**Files:** `.github/workflows/desktop.yml`, `desktop/src-tauri/tauri.{windows,linux}.conf.json`, resource packaging scripts/manifest, third-party notices, `desktop/README.md`, root README desktop instructions.

**Interfaces:** native Windows/Linux CI jobs run locked installs, tests and packaging; no publication required. Player resources resolve correctly from installed app paths, independent of working directory.

- [ ] Produce Windows x86-64 NSIS installer, Linux x86-64 AppImage and .deb from native build hosts. Declare required Linux OS libraries; bundle compatible pinned mpv/codec runtime dependencies and notices, verifying license/source obligations. Do not assume a developer-installed mpv exists on user machines.
- [ ] CI runs `npm ci`, `npm test -- --run`, `npm run build`, `cargo fmt --check`, `cargo test --locked`, `cargo clippy --locked --all-targets -- -D warnings`, and platform Tauri builds. All Cargo commands run from `desktop/src-tauri/`.
- [ ] Test install/launch/uninstall on clean Windows and Ubuntu environments; first run without provider, credential-store absence, playback, restart/resume, and installed runtime loading. A successful CI compile is not a substitute for a display/audio smoke test.
- [ ] Use a separate desktop release channel and Tauri signed updater. Without private signing key/public key/endpoint, keep updates explicitly unconfigured. No generated keys or release publication in this task. Once supplied, test valid signature, tampered download, unavailable endpoint, and user-controlled installation; this release gate remains pending until configured.
- [ ] Document beginner commands and troubleshooting, supported Linux display modes, known codec limits, data paths, and native runtime versions. Preserve browser demo for UI review, but exclude demo artwork/catalogue from production native resources.
- [ ] Obtain one whole-change review, fix material issues, rerun affected tests, and record results in `desktop/VALIDATION.md` with platform/runtime/date and unverified items.
- [ ] Commit reviewed changes. Push/create PR only when requested; do not publish a release automatically.

**Exit:** reproducible installable applications, verified on Windows and Linux. Signed update activation is separately tracked until keys and hosting exist.

## Delivery checkpoints and dependencies

1. Tasks 1-2: native Marquee window plus real embedded video; review the riskiest integration early.
2. Tasks 3-5: real account, full cached library, durable profiles/lists, and wired approved screens.
3. Tasks 6-7: complete player continuity and Live TV experience.
4. Task 8: packaged applications and release readiness.

Task 1 precedes every command consumer; Task 2 precedes production player work; Task 3 precedes provider-scoped catalogue; Task 4 precedes real browse/play/EPG; Tasks 5-6 share identity/history contracts; Task 7 consumes Task 6 player commands. Do not implement overlapping ownership concurrently. Missing Linux display access does not stop independent work, but blocks a full cross-platform completion claim.

## Verification record and scope control

Create a ledger under `.superpowers/` (git-ignored) naming this plan and recording completed tasks, commits, test evidence and architectural rulings. Do not repeat already-passed checks without new changes or unresolved concerns. Keep UI changes limited to wiring, state handling, and necessary native behavior; ask for visual review only when the approved design needs a material change.

Plan-only work is requested in this turn. No backend implementation or dependency installations are part of writing this document. Existing design approval remains valid; execution can begin when requested without restarting design discovery.

## Sources checked while planning

- Tauri setup: https://v2.tauri.app/start/prerequisites/
- mpv embedding guidance: https://github.com/mpv-player/mpv-examples/blob/master/libmpv/README.md
- Tauri signed updater: https://v2.tauri.app/plugin/updater/
- Existing app: `XtreamClient.kt`, `HistoryStore.kt`, `ProfileStore.kt`, `Recommendations.kt`, `CatalogSync.kt`, `PlayerActivity.kt`.
