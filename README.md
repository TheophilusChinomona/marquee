# Marquee

Marquee is a personal Android TV / Fire TV IPTV client built with Kotlin and Jetpack Media3. It targets the Xtream Codes API and is designed around a cinematic 10-foot UI rather than a generic blue media grid.

> Unofficial personal project. Not affiliated with any IPTV provider.

## Features

- **Marquee UI** — near-black navy, bone type, amber focus, a full-bleed billboard, artwork-first rails, and the "House Lights Down" design system from the native redesign.
- **Xtream login** — provider URL, username, and password entered once and stored in private app preferences.
- **Home** — cinematic billboard with Play, My List and Details; Continue Watching, Live Now, For You, Because You Watched, Top 10, New Releases and My List rails.
- **Continue Watching** — per-profile playback history with resume positions, recorded by `HistoryStore`.
- **Recommendations** — an on-device hybrid baseline: category affinity from history and favorites, stable novelty, and a two-per-category diversity cap. Surfaced as For You, Because You Watched and Top 10.
- **Live guide** — EPG timeline with a live “now” marker, channel rows, programme cells, per-profile My List toggles, and a graceful no-guide-data fallback.
- **Browse** — live channels in a 4-column grid; movies and series in a 6-column 2:3 poster grid, with shared Marquee navigation.
- **Player** — custom 10-foot overlay with title, LIVE badge, play/pause, seek and time for VOD; controls auto-hide.
- **Detail** — cinematic movie page with backdrop, metadata, plot, Play/Resume, My List and More Like This.
- **Series detail** — backdrop, metadata, seasons, episode cards, and playback; episode progress feeds Continue Watching.
- **Search** — local search across the loaded live, VOD, and series catalogs.
- **Profiles** — a `Who's watching?` picker with one shared provider login. Each profile keeps its own My List, playback history and recommendations.
- **My List** — long-press **OK** on any card to add/remove it; saved titles appear in the My List rail and tab.

## Design

The native 1.3 UI follows the “House Lights Down” system: deep navy ground, bone type, amber focus, a cinematic billboard, horizontal rails, a remote-first search keypad, and a redesigned profile picker.

The original Claude Design brief is in [`docs/design-prompt.md`](docs/design-prompt.md). The responsive prototype is kept as a design reference at `docs/design/marquee-prototype.html`; it is not the Android runtime.

## Build

Requirements:

- JDK 17 or newer
- Android SDK platform 35
- Android SDK build-tools
- Gradle 8.9 (a wrapper is included)

```bash
cd firetv-player
./gradlew :app:assembleDebug
```

The debug APK is written to:

```text
firetv-player/app/build/outputs/apk/debug/app-debug.apk
```

For a Fire TV, enable ADB debugging and install with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Profiles, history and recommendations

Profiles are device-local. They share the provider login and isolate the personalized state:

- `ProfileStore` stores profiles and the active profile in `profiles` preferences.
- `FavoriteStore` stores each profile's My List under `items_<profileId>`.
- `HistoryStore` stores each profile's playback history and resume position under `history_<profileId>`.
- `Recommendations` blends each profile's category affinity with stable novelty and a diversity cap. It runs locally; no viewing data leaves the device.
- The profile chip in the top bar opens the picker.
- Long-press **OK** on a Home/Browse/Search card toggles My List.
- Long-press a My List row to remove it.

If you need separate provider credentials per profile, that is a different authentication model and is not implemented here.

## Data and privacy

- `.env`, `channel-results/`, and local build artifacts are ignored.
- Credentials are entered at runtime and stored in the app's private `SharedPreferences`; they are not in this repository.
- The app bundles catalog JSON in `firetv-player/app/src/main/assets` for offline browsing. Those assets contain provider/catalog metadata but no credentials. Replace or remove them if you want a clean data set.
- The APK is a debug build; release signing is not configured.
