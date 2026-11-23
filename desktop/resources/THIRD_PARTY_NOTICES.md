# Third-Party Notices

Marquee Desktop bundles or links the following third-party software and assets:

## mpv / libmpv (0.42.0)

- **License:** GNU Lesser General Public License v2.1 or later (LGPL-2.1-or-later)
- **Source:** https://github.com/mpv-player/mpv/tree/v0.42.0
- **Windows x86-64 runtime artifact:** `libmpv-v0.42.0-x86_64-w64-mingw32-lgpl.zip`
- **SHA-256:** `8e5f77430efc53ef175804ba6618527e3af0448a0dcabf04d3cc72659d1a6fce`
- **Notice:** On Windows, `libmpv-2.dll` is loaded dynamically at runtime (`libloading`) and bundled alongside `LICENSE.LGPL`. Users may replace `libmpv-2.dll` with a compatible LGPL build.

## Bundled Fonts

- **Barlow Condensed** (`@fontsource/barlow-condensed`) — SIL Open Font License 1.1 (OFL-1.1)
- **DM Sans** (`@fontsource/dm-sans`) — SIL Open Font License 1.1 (OFL-1.1)

## Frameworks & Libraries

- **Tauri 2** (Apache-2.0 / MIT)
- **React & React DOM** (MIT)
- **Lucide Icons** (ISC)
- **SQLite (via `rusqlite` bundled)** (Public Domain)
- **Rustls / Reqwest / Tokio / Serde / Keyring** (MIT / Apache-2.0)
