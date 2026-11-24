# Marquee Desktop

Windows and Linux desktop IPTV client built with **Tauri 2**, **React + TypeScript**, **Rust**, **SQLite**, and embedded **libmpv**.

Marquee can also connect to a **Marquee Resolver** online library independently of any IPTV provider, so you can search movies and series and watch them through the same native player without an IPTV account.

> No provider credentials, real stream URLs, or sample catalogues are bundled in native desktop builds.

## Online Library

Connecting to an online library requires a resolver URL and a Cloudflare Access service token (`CLIENT_ID` / `CLIENT_SECRET`). It is completely separate from IPTV setup:

- Choose **Online Library** in setup (or **Settings → Online Library**) and enter the resolver URL, client ID, and client secret.
- Credentials live only in Rust and the OS keyring; if the secret store is unavailable Marquee reports a session-only login instead of writing secrets to disk. Logout clears them.
- Search is the entry point: type at least 3 characters to find movies and series online. Saved titles appear under My List, and progress/resume works per profile.
- Every playback start and every episode change re-resolves a fresh stream URL; signed URLs are never persisted.
- Development builds can import credentials from the main checkout's `.env` (located via `git rev-parse --git-common-dir`) through **Connect using local development credentials** instead of typing them.

Online identity is namespaced by the normalized resolver address, so switching resolvers never exposes another server's saved titles.

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
- **Desktop updates**: Installed release builds check on startup and offer **Update and restart**. You can also check in **Settings → About Marquee**. Windows NSIS and Linux AppImage support signed in-app installation; Debian users download and install the new `.deb` manually. Background network failures never block app startup. Development builds and browser previews do not check production updates.
- **First updater-enabled installation**: Existing `0.1.0` builds have no updater. Download and install an updater-enabled release once; later releases appear in the app. Installation saves playback and restarts Marquee only after you choose to update.
- **Troubleshooting**: If playback reports *"Player runtime unavailable"*, re-run `scripts/setup-mpv.ps1` (Windows) or install `libmpv2` / re-run `scripts/setup-mpv.sh` (Linux).

## Shared GitHub Releases

The **Build and publish Marquee release** workflow publishes Android, Windows, and Linux together using one version and commit. Eligible pushes to the default branch (or a manual run on that branch) retain the existing Android automatic patch increment. To prepare a larger version bump, increase both `versionCode` and `versionName` defaults in `firetv-player/app/build.gradle.kts`. The prepared semantic version must exceed the latest published version; legacy two-part versions normalize to three parts.

CI selects the shared version once, sets Android build properties, and updates desktop package/Tauri/Cargo metadata in the build checkout. All platform tests and builds must pass before a release is published. A failed build leaves the previous release available. Re-running a failed publication can reuse a draft for the same commit, but cannot overwrite a public release or another commit's draft.

Each release contains `Marquee.apk`, Android's compatible `version.json`, Windows NSIS `.exe`, Linux `.AppImage` and `.deb`, desktop `.sig` files, and `latest.json` for the Tauri updater. The desktop PR workflow continues to create unsigned Actions artifacts without release secrets. Desktop update signatures are independent of Android APK signing and do not provide Windows Authenticode signing.

### Updater signing identity

`desktop/src-tauri/tauri.conf.json` contains the public verification key. GitHub Actions uses the repository secret `TAURI_SIGNING_PRIVATE_KEY` to sign release installers. The private key backup on the setup machine is `%LOCALAPPDATA%\Marquee\release-signing\marquee.key` (with its `.pub` companion), outside the repository and restricted to the current Windows user and SYSTEM. Keep a secure independent backup of this identity: replacing or losing it prevents existing installations from trusting new updates. Never commit or print its contents. The generated key has no password; CI explicitly supplies an empty signing password.

Ordinary local installer builds do not require the signing key. For a local signed release test, set `TAURI_SIGNING_PRIVATE_KEY` to the backup file path and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` to an empty string, run `node scripts/desktop-release.cjs prepare <version>` from the repository root, then build with the platform command above. Preparation modifies version files and enables updater artifact generation in that checkout; use a disposable checkout for this test.

Before relying on the first production update, smoke-test installation from one updater-enabled version to a higher version on Windows and Linux AppImage. This device check is separate from unit tests and CI compilation.
