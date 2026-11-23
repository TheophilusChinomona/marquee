# Desktop Validation Record

## 2026-11-23 — Windows x86-64 development host

### Environment & Versions
- **OS:** Windows x86-64 (WSL not installed on this host)
- **Toolchain:** Rust 1.100.0, MSVC Build Tools 2022, WebView2, Node.js 26.13.0, npm 12.2.2, Tauri 2.12.7
- **Embedded Media Runtime:** mpv 0.42.0 LGPL (`libmpv-v0.42.0-x86_64-w64-mingw32-lgpl.zip`), SHA-256 verified against `resources/player-manifest.json`
- **Offline Fonts:** Bundled `@fontsource/barlow-condensed` (500–800) and `@fontsource/dm-sans` (400–700); zero external font requests

### Automated Verification Results
- **Frontend unit & flow tests (`npm test -- --run`):** 11 passed, 0 failed across 4 test files (`src/App.test.tsx`, `tests/browsing.test.tsx`, `tests/player.test.tsx`, `tests/live-guide.test.tsx`):
  - Native bootstrap failure shows Retry with zero sample titles; retry recovers cleanly to real provider setup.
  - Provider connect immediately clears password from form state and surfaces explicit session-only warning when secret storage is unavailable.
  - Profile CRUD creates, edits, activates, and protects the last remaining profile; switching profiles isolates Home and My List without leaking previous profile data.
  - Rapid search ignores stale earlier responses and escapes special characters safely.
  - 50,000-item library fixture renders only bounded cards (60 per page) and safely omits missing artwork/metadata without fabricated claims.
  - Player controls dispatch typed actions (`pause`, `seekRelativeMs`, `selectEpisode` by provider `episodeId`, `audioTrack`, `subtitleTrack`, `overlayVisible`, `stop`), disable seeking on live streams, and ignore obsolete `sessionId` events.
  - Live TV guide handles local time formatting, programme overlap, day-boundary crossing, invalid timestamps, and missing guide data cleanly.
- **Native production frontend bundle (`npm run build:native`):** Passed (`tsc && vite build`). Confirmed `dist/artwork` is stripped (`artwork_exists=False`) and `PreviewApp`/`preview-data.ts` are replaced with a 0.04 kB stub (`demo_data_in_native_js=False`). Browser design preview (`npm run dev`) remains independently functional.
- **Rust formatting & linting (`cargo fmt --check`, `cargo clippy --locked --all-targets -- -D warnings`):** Passed with zero warnings.
- **Rust unit & integration tests (`cargo test --locked`):** 23 passed, 0 failed:
  - SQLite schema migration, reopen persistence, provider/profile/kind isolation for favorites and history, last-profile protection, 94% retained / 95% completed Continue Watching rule with 40-entry limit, and series Continue Watching deduplication.
  - Credential redaction, base URL validation, locked secret-store session-only fallback without plaintext storage, and clean session-only disconnect.
  - Atomic generation-staged catalogue refresh (catalogue A preserved when later category fails for B; valid empty catalogue succeeds), transient 503 retry without retrying 401, concurrent deduplicated EPG query with 50-channel / 24h overflow-safe bounds.
  - On-device recommendations (`for_you` and `because_you_watched`) with kind-scoped category affinity, seen-item filtering, deterministic novelty, and diversity cap.
  - Deterministic playback session tests against `FakeEngine`: natural EOF advances once; stale pre-load position, `Stop`, and duplicate `NaturalEof` never skip episodes or corrupt history; manual stop, stream error, and autoplay-off never advance; live streams reject seek.
- **Pinned native libmpv runtime test (`cargo test --lib pinned_runtime_initializes -- --ignored`):** Passed against bundled `resources/mpv/libmpv-2.dll` (mpv 0.42.0).
- **Windows interactive smoke tests (Tasks 1–2):** Native Marquee window observed at 1280×800 with native decorations/icon; real embedded Sintel video playback, pause/resume, fullscreen toggle, and restore verified.
- **Windows x86-64 installer packaging (`npx tauri build --config src-tauri/tauri.windows.conf.json --bundles nsis`):** Produced `desktop/src-tauri/target/release/bundle/nsis/Marquee_0.1.0_x64-setup.exe` bundling `libmpv-2.dll`, `LICENSE.LGPL`, and `THIRD_PARTY_NOTICES.md`.

### Independent Whole-Change Review
- Conducted an independent read-only whole-change review covering provider/profile isolation, atomic refresh/EPG concurrency, playback EOF/history invariants, credential hygiene, CSP/capabilities, and packaging.
- Fixed all material findings and re-ran the full frontend + Rust verification suites and Windows NSIS installer rebuild:
  - Guarded `SessionController::tick` against pre-load stale `time-pos`/`duration`/`NaturalEof` via `MPV_EVENT_START_FILE`/`MPV_EVENT_FILE_LOADED`, added `suppress_next_end = true` on auto-advance, and expanded deterministic tests.
  - Replaced sequential `Notify` EPG fetching with concurrent `JoinSet` + `watch` channel + RAII drop guard and overflow-safe window validation.
  - Stored `provider_id` atomically inside `ConnectionState`, gated `disconnect` on `change_gate`, and re-verified active `(provider_id, profile_id)` across async `.await` points in `media_details` and `player::service::start`.
  - Streamed `CatalogService::refresh` category staging incrementally with `tokio::select!` cancellation.
  - Added `surface::destroy`, `init_lock`, single resize listener registration, and automatic child-HWND hiding during `loading`/`error`/`ended` states and open React track/episode drawers.
  - Fixed `Details` My List toggle state, `App` favorites request ordering, `Browse` kind-switch key reset, `Home`/`MyList` profile-switch clearing, `LiveGuide` provider-switch EPG refetch, and `recommendations` `(MediaKind, category_id)` namespacing.
  - Aliased `PreviewApp` to an empty stub in native builds, configured strict CSP in `tauri.conf.json`, removed unused `opener:default` permission, applied `preferred_audio`/`subtitles_default` preferences, and aligned Linux CI to Ubuntu 24.04 (`libmpv2`).

## Pending / Unverified Gates
- **Linux native build & display/audio playback:** This development host is Windows-only without WSL/Linux. Linux x86-64 `.deb`/AppImage packaging (`.github/workflows/desktop.yml`) and X11/XWayland/Wayland native video surface embedding (`player/surface.rs`) remain unverified until executed on a Linux display host.
- **Clean-machine install/launch/uninstall smoke tests:** End-to-end installer execution and real provider stream playback on clean Windows and Ubuntu machines have not yet been run.
- **Signed desktop updates:** Explicitly unconfigured until release signing keys and an update endpoint are provided; no keys were generated and no release was published.
