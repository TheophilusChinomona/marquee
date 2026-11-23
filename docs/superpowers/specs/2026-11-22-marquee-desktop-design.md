# Marquee desktop design

Status: approved by the user on 2026-11-22, with remaining implementation decisions delegated to the builder. Windows and Linux, full Marquee experience, Tauri, a sibling `desktop/` directory, and embedded native playback are the user's choices.

## Intent

Bring Marquee to Windows and Linux as an installable desktop IPTV client, preserving its cinematic identity and full existing functionality while supporting mouse, keyboard, resizing, and fullscreen. Keep profiles and viewing activity on the device. The user is new to Rust and Tauri and wants reusable development tooling installed on this Windows computer.

## Existing app and scope

The repository contains a Kotlin Android/Fire TV application in `firetv-player/`; there is no reusable web frontend. Media3 and Android SharedPreferences must be replaced on desktop. Existing Kotlin models, API behavior, recommendation rules, and screenshots are the port's reference.

Required experiences:

- Provider setup: Xtream base URL, username, password, account validation, saved login, connection errors, logout, and provider replacement.
- Profile picker and management: one shared provider login; isolated favorites, watch history, and recommendations; keep at least one profile.
- Home: cinematic billboard and actions, Continue Watching, Live Now, For You, Because You Watched, Top 10, New Releases, and My List rails. Rankings must reflect available provider metadata or the existing deterministic local rules; do not invent popularity claims.
- Browsing: live, movies, and series; complete provider categories, artwork grids, and responsive navigation.
- Live guide: virtualized channel rows, category selection, current/upcoming EPG, local-time timeline and now marker, lazy programme retrieval, and useful missing-guide states.
- Movie details: backdrop, metadata, plot, Play/Resume, list toggle, and related titles.
- Series details: seasons, episode stills, descriptions, progress, resume, and episode selection.
- Playback: embedded native video, buffering/error states, play/pause, seek where supported, volume/mute, fullscreen, timeline, live indicator, audio/subtitle selection when available, previous/next episode, episode picker, and automatic advance on natural completion.
- Search: across the locally cached live, movie, and series catalogues, with grouped results and cancellation of obsolete queries.
- My List and per-profile viewing history, durable periodic progress saves and save-on-exit.
- Local recommendations ported from category affinity, stable novelty, and diversity caps.
- Settings: provider/account information, catalogue refresh/status, player preferences, version, and update status.
- Desktop update checking and explicit user-controlled installation once signed release infrastructure is configured.

No cloud profiles, telemetry, Android data migration, recording, downloads, or new external metadata services are proposed. Existing Android source and release metadata remain separate. No sample provider credentials or real stream URLs enter the repository or logs.

## Approaches considered

1. Tauri with browser video: smaller player integration, but ties codec and stream behavior to different webview engines. Rejected for the requested full IPTV experience.
2. Tauri with embedded libmpv: recommended and selected in conversation. More native rendering and packaging work, with a dedicated media engine shared across platforms.
3. Tauri launching an external player: useful as a diagnostic, but does not deliver the requested integrated player and is not the production design.

## Proposed structure and ownership

```text
desktop/
  src/                 React + TypeScript UI, feature modules, shared models
  public/              App-owned static assets
  src-tauri/
    src/               Rust provider, persistence, player, and command modules
    capabilities/      Minimum window permissions
    resources/         Packaged player dependencies and license notices
    Cargo.toml
    tauri.conf.json
  package.json
  README.md
```

Use Tauri 2, React, TypeScript, and Vite. Rust owns provider requests, credentials, persistence, and libmpv lifecycle. The frontend receives typed catalogue/view models and player events, not arbitrary filesystem or shell access. Use native OS window decorations initially, with window state restoration and an in-app fullscreen control. Preserve Marquee's near-black palette, red accent, cinematic artwork, and clear focus indication. Adapt TV-sized spacing to a desktop window without changing the product identity.

## Native playback: first implementation gate

Before building all screens, prove native playback in a minimal Tauri window on Windows and Linux. Use libmpv through a narrow Rust adapter, exposing load, pause, seek, volume, track selection, stop, and playback events. Credentials and stream URLs are resolved in Rust. Do not send stream URLs in process arguments or persist them in watch history.

The preferred end state is an embedded video surface with Marquee controls. Investigate rendering APIs and native window composition before choosing the binding. Platform rendering details are the builder's decision, but a separate external-player window is not an acceptable substitute. Test resize, fullscreen, focus, DPI, stacking, and shutdown. Linux must cover X11 and Wayland; if a compositor requires XWayland, document and verify that requirement explicitly rather than claiming native Wayland support. Do not claim cross-platform playback complete from a Windows-only compile.

Support provider-supplied HLS, transport streams, and common movie/episode containers as supported by the packaged mpv build; no universal codec guarantee. Exercise representative lawful test media, including audio and subtitles. Actual provider authentication and streams require an eventual user smoke test; credentials are entered through the app only.

Bundle pinned libmpv runtime dependencies in release packages so normal users do not install a separate player. Record source/version, checksums, redistribution license, notices, and source obligations before shipping. Development can use an explicitly configured local libmpv path. A missing runtime must produce a clear actionable error, never a silent webview fallback.

## Provider access and catalogue

Port Xtream authentication, categories, streams, movie/series details, stream URL construction, and EPG normalization from `XtreamClient.kt`. Permit HTTP providers because that is an existing product requirement; explain the connection's transport status in settings without blocking them. Validate input and redact credential-bearing URLs from errors and logs. Add timeouts, bounded concurrency, cancellation, and explicit retry UI.

Cache catalogues in SQLite under the platform app-data directory, partitioned by provider identity. Fetch category by category, commit a completed refresh atomically, retain the last good catalogue after failure, and expose refresh progress. Refresh after 24 hours, with manual refresh available. Search and grids use paging/virtualization so large libraries do not become giant DOM trees. Do not copy the Android provider-specific bundled catalogues into the desktop distribution; populate from the user's configured provider. Keep enough cached metadata for offline browsing; playback still requires the source.

Provider replacement must clear or namespace the previous catalogue and profile viewing data so reused stream IDs cannot show another provider's history or favorites. Recommendation identity is `(provider, media kind, item ID)`; episode resume additionally uses provider episode ID, matching the Android fix for renamed episodes.

## Local state and player lifecycle

Use SQLite migrations for profiles, favorites, history, preferences, and catalogue data. Store provider secrets in Windows Credential Manager and Linux Secret Service through an OS credential-store adapter. If Linux secret storage is unavailable or locked, support session-only login with an explanation; never silently fall back to plaintext credentials.

Rust is authoritative for the current playback session and its profile. Save progress every 10 seconds and on pause, item change, stop, and orderly exit. Restore positions only for seekable on-demand media. Profile switching stops/saves current playback before changing state. Natural episode completion advances exactly once; manual stop, network error, or arbitrary EOF must not incorrectly skip an episode. UI subscriptions must be cleaned up to prevent duplicate progress saves or advance events. The native player is torn down when the app closes.

Port the existing deterministic recommendation rules; cover profile isolation, seen-item filtering, and category diversity with fixtures. Existing Android completion/resume thresholds should be inspected and carried over rather than replaced with arbitrary defaults.

## Distribution and updates

First targets: Windows x86-64 installer and Linux x86-64 AppImage and Debian package, with Ubuntu 22.04+ as the initial Linux validation baseline. Other architectures and distributions are later additions. Use platform-native CI builds, with player dependency packaging and notices included. Build on Linux to validate Linux artifacts; Windows does not establish Linux readiness.

Use a desktop-specific release manifest/channel and Tauri's signed update mechanism. Never reuse the Android APK updater or root `version.json`. Signing keys, publication credentials, and release publication remain outside source control. Without those inputs, show updates as unconfigured and document setup; do not fabricate a working update service. No release publishing or key generation is included without an explicit request.

## Verification and delivery

Milestones are sequenced, not reductions in feature scope:

1. Verify Rust/MSVC/WebView2 locally and establish Windows/Linux build checks.
2. Prove embedded native playback and composition on target platforms.
3. Implement provider, persistence, catalogue, and player contracts with meaningful fixture tests.
4. Implement all browsing, profile, detail, search, guide, and player experiences.
5. Validate feature parity, errors, accessibility, large catalogues, packaging, and update configuration.

Required checks include frontend type checking/build, Rust formatting/checks/tests, fixture-based provider and persistence tests, player lifecycle tests, and actual native playback smoke tests. Test offline startup, invalid login, malformed/empty API data, missing artwork, unavailable secrets service, interrupted refresh, profile isolation, resume after restart, season transitions, and live streams without seek support.

Document setup and everyday commands for a Rust/Tauri beginner, including which parts run in TypeScript versus Rust. The final report must distinguish implemented features, tests executed locally, checks executed on Linux, and any blocked validation. An overnight build is permission for sustained work, not evidence of completion or a guarantee of unattended background execution.

## Technical references and review

- [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/): Windows MSVC and WebView2 requirements, Rust setup, and Linux system packages.
- [Rust installation](https://rust-lang.org/tools/install/): rustup, Cargo, stable toolchains, and PATH configuration.
- [Tauri webview versions](https://v2.tauri.app/reference/webview-versions/): platform engine differences.
- [mpv embedding guidance](https://github.com/mpv-player/mpv-examples/blob/master/libmpv/README.md): rendering API and native embedding tradeoffs.

An independent builder review on 2026-11-22 found no important unanswered planning questions. Native rendering feasibility remains an explicit implementation gate, not a verified capability of this repository.
