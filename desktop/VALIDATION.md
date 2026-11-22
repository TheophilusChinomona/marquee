# Desktop validation record

## 2026-11-23 — Windows x86-64 development host

- Rust 1.100.0, MSVC Build Tools 2022, WebView2; Tauri 2.12.7; mpv 0.42.0 LGPL runtime from the mpv project's tagged GitHub release. Archive SHA-256 matches `resources/player-manifest.json`.
- `cargo check`, native Tauri dev launch, TypeScript/Vite production build passed. Frontend startup failure and successful retry: 2 tests passed. Rust bootstrap and missing/invalid runtime input tests: 3 passed.
- Explicit native runtime test (`cargo test --lib pinned_runtime_initializes -- --ignored`) passed: initialize, inspect mpv version, repeated stop, repeated shutdown, reject command after shutdown.
- Lawful local sample: W3C-hosted Blender Sintel trailer, https://media.w3.org/2010/05/sintel/trailer.mp4 (downloaded only to ignored scratch; not shipped).
- Real video observed within a child surface of the Marquee window, with React header and controls in the same window. No external player process. Pause changed to Play and position stayed at 0:19. Fullscreen expanded to 1920×1200; video resized correctly, controls remained clickable, and returning to windowed restored 1280×800 client geometry.
- This proves the first Windows composition experiment, not finished playback parity. Controls currently sit alongside video. GPU render-API composition, overlay panels, track switching, DPI changes, minimize/restore, real provider streams, and clean installed-runtime playback still need validation.

## Pending gates

- Linux build, X11/XWayland/Wayland display/audio playback and install smoke tests. This host reports WSL is not installed; no Linux result is claimed.
- Windows/Linux complete account/catalogue/profile/history flows and installable artifacts.
- Updater remains unconfigured until signing public key and release endpoint are supplied; no keys generated or releases published.

## Reproduce the Windows player experiment

Run `powershell -ExecutionPolicy Bypass -File scripts/setup-mpv.ps1` from `desktop/`, set `MARQUEE_PLAYER_SMOKE` to an absolute lawful local media path, then `npm run tauri dev`. The smoke path is consumed only in Rust debug builds and is never accepted over IPC. Clear the environment variable for ordinary first-run setup. Do not use provider URLs in environment variables or command-line arguments.
