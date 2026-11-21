# Marquee

A personal, Netflix-inspired IPTV client for Android TV and Fire TV. Built with Kotlin and Jetpack Media3, it talks to any provider that exposes the Xtream Codes API.

> Unofficial personal project. Not affiliated with Netflix, Amazon, or any IPTV provider. No streams or credentials are included.

## Why this exists

Most IPTV apps on a TV are built like phone apps stretched onto a big screen: dense grids, tiny text, awkward remote navigation, and a UI that ignores the fact that you are sitting ten feet away. Provider-supplied apps are often worse.

Marquee started as a reaction to that. The goal was to build the opposite:

- **A living-room experience first.** Large artwork, a cinematic billboard, horizontal rails, and a focus system designed for a D-pad, not a mouse.
- **Nothing between you and the content.** Near-black ground, one accent colour, and type that reads from across the room.
- **Fast, local state.** Profiles, My List, watch history, and recommendations live on the device. No accounts, no telemetry, no cloud round-trips.
- **Continuity.** Stop halfway through an episode, restart the app weeks later, and pick up from the exact second — with Continue Watching and a Resume button where you expect them.
- **Everything your provider gives you.** Not a curated subset: the full live channel list, every category, and the EPG your provider offers.

The result is a small, opinionated client that treats a Fire TV Stick like a cinema screen instead of a web page.

## Features

- **Netflix-inspired 10-foot UI** — pure-black ground, white type, a single red accent, artwork-first rails, white focus outlines, and smooth D-pad focus.
- **Home** — full-bleed billboard with Play / More Info / My List, then Continue Watching, Live Now, For You, Because You Watched, Top 10, New Releases, and My List rails.
- **Live TV guide** — every channel from your provider, virtualised rows, channel logos, a synced timeline with a live "now" marker, per-row EPG loaded lazily, and a category picker.
- **Series** — cinematic backdrop, season dropdown, a horizontal episode rail with stills and progress bars, an episode summary, and a Resume button for the last-watched episode.
- **Movies** — detail page with backdrop, metadata, plot, Play/Resume, My List, and More Like This.
- **Continue Watching** — per-profile playback history with resume positions, saved periodically and on exit, so force-stops don't lose your place.
- **Custom player** — 10-foot overlay with title, LIVE badge, play/pause, ±10-second seeking, progress, and auto-hiding controls.
- **Profiles** — a "Who's watching?" picker with one shared provider login. Each profile keeps its own My List, history, and recommendations.
- **My List** — a dedicated grid screen; long-press OK on any card to add or remove a title.
- **Search** — local search across the loaded live, VOD, and series catalogues.
- **Recommendations** — a small on-device hybrid of category affinity, stable novelty, and a diversity cap. Nothing leaves the device.

## Install

### Download

Grab the latest signed APK from the [Releases page](https://github.com/TheophilusChinomona/marquee/releases/latest).

### Fire TV / Fire OS

1. On the Fire TV: **Settings → My Fire TV → Developer Options → ADB Debugging → On** (enable Developer Options first by selecting **About → Device Name** seven times if needed).
2. Install the APK using one of these:
   - **Downloader app**: install Downloader from the Amazon Appstore, allow apps from unknown sources, and enter the APK URL from the release page.
   - **ADB over the network**:
     ```bash
     adb connect <fire-tv-ip>:5555
     adb install -r Marquee-1.4.apk
     ```
3. Launch **Marquee** from **Your Apps & Channels**.

### Android TV

The same APK works on Android TV / Google TV devices (Android 5.0+):

```bash
adb install -r Marquee-1.4.apk
```

## First run

Marquee does not ship a provider or any credentials. On first launch, enter:

- your provider's base URL (`http://host:port`),
- username,
- password.

These are stored only in the app's private `SharedPreferences` on the device. They are never bundled in the APK and never leave the device.

## Build from source

Requirements:

- JDK 17 or newer
- Android SDK platform 35 and build-tools
- Gradle 8.9 (the wrapper is included)

```bash
cd firetv-player
./gradlew :app:assembleDebug
```

The debug APK is written to `firetv-player/app/build/outputs/apk/debug/app-debug.apk`.

### Release builds

Release signing is read from `firetv-player/keystore.properties` (git-ignored):

```properties
storeFile=keystore/marquee-release.jks
storePassword=...
keyAlias=marquee
keyPassword=...
```

```bash
cd firetv-player
./gradlew :app:assembleRelease
```

The signed APK is written to `firetv-player/app/build/outputs/apk/release/app-release.apk`.

> Keep the keystore and `keystore.properties` out of git. Android will not install an update signed with a different key.

## How it is organised

```text
firetv-player/
  app/src/main/
    java/com/orca/iptv/
      HomeActivity.kt        Home screen and billboard
      MarqueeHome.kt         Marquee hero and rails
      MarqueeNav.kt          Top navigation and profile chip
      MarqueeUi.kt           Shared design system (colour, type, focus)
      BrowseActivity.kt      Live / movies / series grids
      LiveGuideActivity.kt   Live TV guide, categories, EPG
      DetailActivity.kt      Movie detail
      SeriesActivity.kt      Series detail, seasons, episodes, resume
      PlayerActivity.kt      Media3 player and overlay
      MyListActivity.kt      My List grid
      SearchActivity.kt      Local search
      ProfilesActivity.kt    Profile picker
      XtreamClient.kt        Xtream Codes API client
      *Store.kt              Local prefs: profiles, favorites, history
      Recommendations.kt     On-device recommendation baseline
    res/                     Themes, layouts, drawables, launcher icons
    assets/                  Bundled catalog metadata (no credentials)
  tools/                     Catalog and allowlist helper scripts
docs/
  design-prompt.md           Original design brief
  design/                    Design reference prototype
```

The bundled JSON catalogues in `assets/` make the Home rails work offline and keep the app fast. They contain provider/catalog metadata and artwork URLs only. To build them for your own provider, see `firetv-player/tools/build_catalog.py` (it reads provider credentials from `.env`, which is git-ignored).

## Privacy

- Profiles, My List, history, and recommendations are stored locally in app-private preferences.
- There is no analytics, tracking, or telemetry.
- Live EPG and artwork are fetched from your provider and the URLs it returns.
- Provider credentials are entered at runtime and stored on the device only.

## Known limitations

- Personal project, tested primarily on a Fire TV Stick 4K (Fire OS 7 / Android 7.1.2).
- Requires a provider that implements the Xtream Codes API.
- Playback depends on your provider, network, and region; some channels may not play.
- The bundled catalogue snapshots may not match your provider exactly.
- Release builds are signed with a personal key, so updates must come from this repository.

## Credits

- Built with [Jetpack Media3 / ExoPlayer](https://developer.android.com/media/media3).
- Design direction: "House Lights Down", later reworked into a Netflix-inspired 10-foot system.

## Disclaimer

Marquee is a client only. It does not host, provide, or endorse any streams or content. You are responsible for using it with a provider you are authorised to access. Not affiliated with Netflix, Amazon, Google, or any IPTV provider.
