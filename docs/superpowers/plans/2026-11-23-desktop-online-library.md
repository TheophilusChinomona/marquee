# Desktop Online Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Connect the native desktop app to an authenticated resolver independently of IPTV, with search, saved titles, resume, and movie/series playback.

**Architecture:** A Rust resolver module owns connection state, HTTP, credentials, and metadata persistence. Existing media commands dispatch by namespaced identity; existing playback gains deferred episode resolution. React receives only nonsecret connection state and media DTOs.

**Tech Stack:** Existing Tauri 2, React/TypeScript, Rust, reqwest, SQLite, keyring, libmpv.

**Spec:** `docs/superpowers/specs/2026-11-23-desktop-online-library-design.md`

## Global Constraints

- No IPTV account required for online connection or playback.
- Credentials stay in Rust and the OS keyring; explicitly report session-only fallback.
- TMDB_READ_TOKEN stays server-side. Development .env import is explicit and native/debug only.
- Online identity includes the normalized resolver address; profile activity remains isolated.
- Resolve movies on start and episodes on every transition; never persist signed URLs.
- Authenticate only the configured resolver; disable HTTP redirects carrying service credentials.
- Preserve the existing IPTV paths and UI style.

## Review Focus

- Disconnect/profile switches while resolution is pending cannot start stale playback (Tasks 1, 3).
- Resolver-origin changes cannot expose another server's saved metadata (Tasks 1, 2).
- Expired streams and long seasons require fresh episode resolution (Task 3).
- Locked keyrings and failed authentication cannot overwrite a working connection (Task 1).
- An online-only user must reach search instead of IPTV synchronization screens (Task 4).

### Task 1: Resolver client and independent connection

**Files:** Create `desktop/src-tauri/src/online.rs`; modify `lib.rs`, `models.rs`, `commands.rs`, `state.rs` as needed.

**Interfaces:** `OnlineService` owns a generation counter and optional cloned resolver session; `OnlineStatus` exposes connected, baseUrl, credentialStatus, warning, and development import availability. Commands `online_connect`, `online_disconnect`, `online_check`, `online_import_development` return bootstrap/status without secrets. `ResolverSession::search`, `details`, and `resolve` use existing media DTOs.

- [x] Write tests for URL/identity validation, HTTP authentication and redirect refusal, malformed/unauthorized responses, metadata mapping, failed-auth preservation, and session-only keyring behavior.
- [x] Run `cargo test --locked online` and observe expected failures before implementation.
- [x] Implement bounded HTTP requests, separate keyring entry, restore marker, connection generation, and explicit development import located through `git rev-parse --git-common-dir`.
- [x] Run the focused tests and verify successful authenticated connection and sanitized failures.

### Task 2: Persist metadata and integrate profile activity

**Files:** Create `desktop/src-tauri/src/online_store.rs`; modify `store.rs`, `state.rs`, `commands.rs`, `models.rs`.

**Interfaces:** Store methods create a resolver scope without replacing the active IPTV provider, cache `MediaItem`/`MediaDetails`, and query online favorites/history using the existing profile tables. Online media refs encode server scope and upstream title ID. Commands merge local/online favorites and home sections, dispatch details/save by scope, and keep catalogue queries local.

- [x] Write failing tests proving saved metadata survives reopening, profiles and resolver scopes remain isolated, and online connection does not replace an IPTV account.
- [x] Run focused tests to see the missing behavior fail.
- [x] Implement persistent metadata using a new versioned SQLite migration and existing history/favorites relationships; return saved details offline and fresh details online.
- [x] Verify focused tests plus existing state/store tests.

### Task 3: Resolve playback and episode transitions

**Files:** Modify `player/service.rs`, `player/session.rs`, `player/mod.rs`, `commands.rs`.

**Interfaces:** Player service resolves source-aware media starts. The session controller exposes a pending transition request for deferred queue entries; the async player service resolves it and verifies connection generation, profile, and player session before loading. Referer is applied per file without resolver credentials.

- [x] Write failing session tests for deferred next/previous/select/autoplay, natural EOF history, and stale transition rejection.
- [x] Run focused player tests and observe failures.
- [x] Add online start routing, fresh resolution for queue transitions, sanitized failures, per-file referer, and generation checks on starts/transitions.
- [x] Run player and online tests; preserve existing IPTV autoplay and controls.

### Task 4: Desktop connection and search UI

**Files:** Create `desktop/src/features/OnlineConnect.tsx`; modify `api/types.ts`, `api/client.ts`, `App.tsx`, `features/Connect.tsx`, `features/Settings.tsx`, `features/Search.tsx`, `features/Home.tsx`, `components/Header.tsx`, `App.css`; add frontend online tests.

**Interfaces:** `DesktopApi` adds optional online commands/search; bootstrap adds optional online status for compatibility with preview/test clients. Setup selects IPTV or Online Library. Online search uses separate result/error/loading states and debounce with cleanup. Online-only Home explains search and displays saved/resume sections.

- [x] Write failing UI tests for online-only entry, credential form, independent connection settings, online search, and stale query cleanup.
- [x] Run `npm test -- --run` against the focused tests and observe missing behavior.
- [x] Implement setup/settings, online-only navigation, search results, resolving state, and source-aware playback gating.
- [x] Run all frontend tests and `npm run build`.

### Task 5: Verify and document

**Files:** Update `desktop/README.md`, `desktop/CONTRACT.md`, `desktop/VALIDATION.md`, and this plan's progress.

- [x] Run `cargo fmt --check`, `cargo test --locked`, and `cargo clippy --locked --all-targets -- -D warnings` from `desktop/src-tauri`.
- [x] Run frontend suite and production build if changed since Task 4.
- [x] Smoke-check authenticated health/search/details against the supplied resolver without printing credentials or signed URLs.
- [x] Inspect the final diff for credentials, regressions, and spec gaps; document verified behavior and any playback/platform limits.

Execution: implement inline in this existing isolated worktree under the user's approved build request. No additional execution-method approval is needed.

## Verification outcome

- Rust suite: 33 passed, 2 opt-in tests ignored by default; pinned mpv opt-in test passed separately.
- Frontend suite: 15 passed; native production frontend bundle passed.
- Rust formatting and clippy passed. Independent review findings were fixed with regression tests.
- Live authenticated health, search, and title details passed. Live stream resolution for the tested movie returned HTTP 502; end-to-end online video playback remains unverified.
- Changes remain on the existing feature branch; no merge or remote deployment is included.
