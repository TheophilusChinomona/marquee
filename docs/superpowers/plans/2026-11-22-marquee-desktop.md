# Marquee Desktop Implementation Plan

> Superseded after UI approval by [Marquee Tauri Implementation Plan](2026-11-22-marquee-tauri-implementation.md). Keep this earlier outline for context; execute the newer plan against the approved preview.

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Track progress with checkboxes and the plan ledger.

**Goal:** Implement the full Marquee desktop experience in `desktop/`, with a native embedded player and Windows/Linux build support.

**Architecture:** A React/TypeScript/Vite frontend calls typed Tauri commands. Rust owns the provider session, SQLite persistence, credential storage, and dynamically loaded libmpv. Feature modules keep catalogue, local state, and playback independent.

**Tech Stack:** Tauri 2, Rust stable MSVC, React, TypeScript, Vite, rusqlite, reqwest, keyring, libloading, libmpv.

**Spec:** `docs/superpowers/specs/2026-11-22-marquee-desktop-design.md`

## Global Constraints

- Windows x86-64 and Linux x86-64 first; native Linux validation is distinct from Windows checks.
- Full existing experience, no provider credentials or real stream URLs in source or logs.
- Rust owns authentication, stream resolution, persistence, and player lifecycle.
- Profile/provider identity isolates favorites and history; refresh preserves last good data.
- Embedded libmpv; unavailable runtime is an actionable error, never a browser or external-player fallback.
- Updates remain explicitly unconfigured until signed release infrastructure exists.
- Existing Android app and updater stay separate.

## Review Focus

1. Provider replacement with colliding IDs must not expose previous viewing data.
2. Interrupted or malformed catalogue refresh must preserve the previous complete snapshot.
3. Player stop/error must not trigger automatic episode advance.
4. Missing/locked credential storage must allow session-only use without plaintext fallback.
5. Empty/offline catalogue, unknown media metadata, and missing images must yield useful recoverable UI states.

### Task 1: Desktop foundation and native tooling

**Files:** `desktop/package.json`, Vite/TypeScript configs, `desktop/src-tauri/{Cargo.toml,tauri.conf.json,build.rs,src/main.rs,src/lib.rs}`, capabilities, icons, `desktop/src/{types.ts,api.ts}`.

**Interfaces:** shared camelCase JSON models and commands described in `desktop/CONTRACT.md`. Native commands receive structured input; raw provider URLs stay in Rust.

- [ ] Verify Rust, formatter, Clippy, MSVC, Windows SDK, Node, and WebView2; compile and run a Rust smoke program outside the repo.
- [ ] Generate a React/TypeScript Tauri scaffold and replace sample files.
- [ ] Define command/model contracts before delegating backend work.
- [ ] Establish `npm run build`, `npm test`, `cargo test`, and `cargo check` commands.

### Task 2: Provider, catalogue, and durable local state

**Files:** `desktop/src-tauri/src/{models.rs,backend.rs,provider.rs,store.rs}`, integration tests as appropriate.

**Interfaces:** `Backend::open(path)`, provider commands and local state operations in CONTRACT.md; `Backend::resolve_stream(&MediaItem)` supplies credentials internally to player startup; history save accepts a typed entry.

- [ ] Write fixture tests for Xtream variable field types, base64 EPG, URL validation, episode IDs, and credential redaction; run them failing before implementation.
- [ ] Implement bounded provider access and metadata normalization.
- [ ] Write isolation/atomic-refresh/resume tests, then implement SQLite migrations and transactions.
- [ ] Implement OS-backed secrets with session-only fallback, authenticated boot, profile/favorite/history operations, cache refresh, paging/search, details, guide, and logout.
- [ ] Run Rust tests and report exact command signatures and evidence.

### Task 3: Embedded native player

**Files:** `desktop/src-tauri/src/player.rs`, `desktop/scripts/setup-mpv.ps1`, `desktop/src/features/Player.tsx`.

**Interfaces:** `player_start(input)`, `player_control(input)`, `player_snapshot()`; state includes position, duration, pause, buffering, error, natural completion, and tracks. Starting playback resolves media through Backend. Native handle access is restricted to the app window.

- [ ] Write tests for input validation, seek clamping, EOF-versus-stop behavior, and history identity; observe failures.
- [ ] Implement dynamic libmpv FFI with owned library/handle lifetimes, safe serialized commands, suppressed credential-bearing mpv logs, native embedding, and teardown.
- [ ] Add snapshot/progress persistence and controls for pause, seek, volume/mute, tracks, and stop.
- [ ] Acquire a pinned runtime with recorded checksum/license metadata; prove native playback locally with lawful test media.
- [ ] Document Linux X11/XWayland requirements and establish a separate Linux smoke test gate.

### Task 4: Complete desktop UI

**Files:** `desktop/src/{App.tsx,styles.css,components/*,features/*,domain.ts,domain.test.ts}`.

**Interfaces:** only the typed API from Task 1; native player is authoritative. UI requests page-sized catalogue results and profile-scoped lists.

- [ ] Write recommendation/resume/queue tests first, then port deterministic Android behavior.
- [ ] Build setup, profiles, responsive navigation, billboard and rails, browsing/categories, search, details/seasons/episodes, My List, guide, settings, and player overlay.
- [ ] Implement loading, empty, error, offline, missing-artwork states, keyboard navigation, and fullscreen.
- [ ] Verify type checking, tests, production build, and browser preview; test native flow separately.

### Task 5: Packaging, documentation, and integration validation

**Files:** `.github/workflows/desktop.yml`, `desktop/README.md`, runtime license/manifest documents, root README desktop section.

**Interfaces:** CI runs platform builds and fixture tests; packaging includes verified player resources.

- [ ] Add Windows/Linux CI and platform runtime packaging scripts, with no publishing secrets required.
- [ ] Document beginner setup and daily commands, native dependencies/licenses, updates-unconfigured status, and validation limits.
- [ ] Run frontend build/tests, Rust format/check/tests/Clippy, and Windows application smoke test.
- [ ] Obtain independent whole-change review, address material findings, rerun affected checks, and record remaining platform/provider validation honestly.

## Execution decisions

The user approved implementation and delegated remaining decisions. Proceed without another plan-approval question. Work in the existing isolated branch. Keep a progress ledger and use focused subagent work where file ownership is independent; never allow overlapping mutations. Do not push or publish releases.
