# Desktop Validation Record

## 2026-11-23 — Online playback across networks (IP-bound CDN links)

### Symptom
Online titles resolved successfully but the native player showed "Playback failed or stream connection was lost." Live/IP TV movies played, so the player itself worked.

### Root cause (not a client bug)
Signed CDN links minted by the resolver only answer the **public IP that minted them**. Verified directly: resolving "Interstellar" on the resolver host (`omarchy`, egress `197.89.163.20`) returned `status:206`, and fetching that exact URL from the development Windows box (egress `45.220.15.46`, on a hotspot SSID, not the home LAN) returned **403** — same URL, same `Referer`, same User-Agent, same `Range`, with and without a referer.

### Fix
- **Resolver:** `/resolve` now also returns `streamUrl`, a same-origin `/stream?t=<hmac>` link. `/stream` relays the video bytes from the resolver (the minting IP) with Range passthrough, so seeking works from any network. The token is an HMAC over the upstream URL (not an open proxy); `RESOLVER_STREAM_SECRET` keeps links valid across restarts. `/stream` is self-authenticating and bypasses the bearer token and coarse rate limiter (video makes many Range requests).
- **Desktop client (`online.rs`):** `resolve` prefers `streamUrl`, re-rooting its path onto the connected base (LAN, Tailscale, or tunnel, preserving any `/t/<token>/` prefix) and dropping the upstream referer so the resolver applies it.

### Verified
- From the Windows box over Tailscale (`http://100.79.149.69:8099`): `resolve` → `streamUrl` → **206 `video/mp4`** at offset 0 and at a 50 MB offset. This is the same network and URL that returned 403 before the fix.
- Forged `/stream?t=…` token → **400**.
- `cargo test --locked` 35 passed; `cargo fmt --check` and `cargo clippy -D warnings` clean.
- Live smoke test against the Tailscale URL with resolve enabled passes using the client's `streamUrl` path.

### Operating note
The raw `url` still only plays from the minting network. The desktop app uses `streamUrl`, which works anywhere but relays video through the resolver host (its upstream bandwidth carries the stream). On the home LAN the resolver can be reached directly and either URL works.

## 2026-11-23 — Online Library integration (standalone resolver)

### Scope
Added an independent **Online Library** connection to the desktop app: sign in with a resolver URL + Cloudflare Access service token, search movies/series, open details, save to My List, resume, and play movies/series through the existing embedded libmpv player. No IPTV account is required for any of this; IPTV paths and UI are unchanged.

### Automated Verification Results
- **Rust formatting / linting (`cargo fmt --check`, `cargo clippy --locked --all-targets -- -D warnings`):** Passed, zero warnings.
- **Rust tests (`cargo test --locked`):** 33 passed, 2 ignored. Online-specific coverage:
  - Origin/identity validation and title-ID namespacing (`online:<sha256>:<upstream>`), including cross-resolver rejection.
  - HTTP authentication with Cloudflare Access headers, redirect refusal (no credential-carrying redirects), and sanitized unauthorized responses.
  - Metadata mapping for movies and series seasons/episodes without signed URLs.
  - Locked keyring reports session-only and never writes credentials to SQLite; remembered login restores on reopen; failed authentication preserves the previous working connection.
  - Saved online titles survive reopen, stay isolated by profile and resolver scope, and do not replace an active IPTV account.
  - Playback controller: deferred online next/previous/select/autoplay resolves fresh URLs, saves completion, requires a new URL per episode visit, and rejects stale transition results while reporting `resolving`.
- **Frontend tests (`npm test -- --run`):** 15 passed across 5 files, including 4 new online tests: online-only bootstrap opens search-led Home without triggering an IPTV sync; the setup form connects via online credentials, clears the secret from the form, and reaches Home; a late response for an old online query cannot replace current results; reconnect opens the online form.
- **Production bundle (`npm run build`):** Passed (`tsc && vite build`).

### Live Resolver Smoke Test
- `cargo test --locked live_resolver -- --ignored --nocapture` with `MARQUEE_RESOLVER_SMOKE_URL=https://resolver.sentiolangley.com/` and credentials imported from the main checkout `.env` — **passed** for authenticated health, search, and title details. Stream resolution was tested separately and returned HTTP 502 for the selected movie; end-to-end movie playback is not yet verified. No credentials or signed URLs were printed.

### Native verification
- Windows debug executable built with `cargo build --locked --features tauri/custom-protocol` and a native frontend bundle (`npm run build:native`).
- Native Windows app launched and rendered the existing real library. Settings exposes the Online Library tab. The explicit pinned mpv initialization/shutdown test passed.
- Online setup/search/reconnect are covered by frontend flow tests. End-to-end online video playback is unverified because the tested live resolver /resolve request returned HTTP 502. Linux display verification remains pending.

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
