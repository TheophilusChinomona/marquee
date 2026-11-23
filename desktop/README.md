# Marquee Desktop

Windows and Linux desktop IPTV client built with **Tauri 2**, **React + TypeScript**, **Rust**, **SQLite**, and embedded **libmpv**.

> No provider credentials, real stream URLs, or sample catalogues are bundled in native desktop builds.

## Prerequisites

- **Node.js 22+** and npm
- **Rust stable** (`rustup` — pinned via `desktop/rust-toolchain.toml`)
- **Windows:** Visual Studio C++ Build Tools 2022 (MSVC + Windows SDK) and WebView2 Runtime
- **Linux (Ubuntu 24.04+ / `libmpv2`):**
  ```bash
  sudo apt-get install -y libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev \
    librsvg2-dev libsecret-1-dev libssl-dev libmpv-dev patchelf pkg-config
  ```

## Quick Start

### 1. Install dependencies & stage the pinned libmpv runtime

**Windows (PowerShell):**
```powershell
cd desktop
npm ci
powershell -ExecutionPolicy Bypass -File scripts/setup-mpv.ps1
```

**Linux (Ubuntu 22.04+):**
```bash
cd desktop
npm ci
bash scripts/setup-mpv.sh
```

### 2. Run the native desktop app

```powershell
cd desktop
npm run tauri dev
```

### 3. Browser UI preview (design review only)

```powershell
cd desktop
npm run dev
```

Open `http://localhost:1420` to explore the standalone sample-data design preview. Native builds never load sample titles or demo artwork.

## Testing & Verification

```powershell
# Frontend tests and production bundle
cd desktop
npm test -- --run
npm run build

# Rust formatting, unit tests, and clippy (run from desktop/src-tauri)
cd desktop/src-tauri
cargo fmt --check
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
```

## Building Installers

**Windows x86-64 (NSIS installer):**
```powershell
cd desktop
npx tauri build --config src-tauri/tauri.windows.conf.json --bundles nsis
```

**Linux x86-64 (AppImage & `.deb`):**
```bash
cd desktop
npx tauri build --config src-tauri/tauri.linux.conf.json --bundles deb,appimage
```

## Local Data Paths & Privacy

- **SQLite database (`marquee.sqlite`)**: Stored in Tauri's per-user app data directory (`%APPDATA%\app.marquee.desktop\` on Windows, `~/.local/share/app.marquee.desktop/` on Linux).
- **Provider credentials**: Stored only in Windows Credential Manager or Linux Secret Service (`libsecret`). If the OS secret store is locked or unavailable, Marquee explicitly offers session-only login and never falls back to plaintext files.
- **No cloud accounts or telemetry**: Profiles, My List, watch history, and local recommendations stay on your device.

## Platform Notes & Known Limits

- **Embedded playback runtime**: Windows bundles pinned `libmpv-2.dll` (mpv 0.42.0 LGPL); see `resources/THIRD_PARTY_NOTICES.md`.
- **Linux display modes**: Linux X11/XWayland/Wayland surface embedding is still pending native Linux display verification (see `VALIDATION.md`).
- **Desktop updates**: Signed Tauri updates remain explicitly unconfigured until release signing keys and update hosting are configured. Android APK updates (`version.json`) remain completely separate.
- **Troubleshooting**: If playback reports *"Player runtime unavailable"*, re-run `scripts/setup-mpv.ps1` (Windows) or install `libmpv2` / re-run `scripts/setup-mpv.sh` (Linux).
