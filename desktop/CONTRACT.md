# Marquee Desktop Contracts

Rust owns provider credentials, SQLite state, catalogue requests, recommendations, and libmpv playback. React receives sanitized camelCase DTOs. Commands return `{ code, message, retryable }` (`AppError`); raw network errors and credential-bearing URLs never cross IPC.

## Error Codes

`validation`, `unauthenticated`, `network`, `provider`, `storage`, `player`, `unsupported`.

## Identity & Scope

- Media identity is `(providerId, kind, id)` with string IDs even when providers send numbers.
- Profile activity (favorites, watch history, recommendations) is isolated by `(providerId, profileId, kind, mediaId)`.
- Episode watch history additionally stores provider `episodeId` (never episode titles).
- Provider scope always comes from the active Rust backend state, never caller-supplied account IDs.

## IPC Commands

| Command | Purpose |
| --- | --- |
| `app_bootstrap()` | Returns connection state, credential status/warning, profiles, active profile ID, cache status, and settings. Never returns a password. |
| `provider_connect({ baseUrl, username, password, remember })` | Stops/saves active playback, authenticates Xtream account, stores credentials in OS keyring when `remember=true` (or falls back explicitly to `session` status with warning—never plaintext). |
| `provider_disconnect()` | Stops/saves active playback, cancels refresh, clears session and OS keyring entry; retains cached library for explicit offline browsing. |
| `profiles_list()`, `profile_create`, `profile_update`, `profile_remove`, `profile_activate` | CRUD and active profile switching; last profile cannot be removed; switching stops/saves active playback. |
| `favorites_list({ offset, limit })`, `favorite_set({ ref, saved })` | Paged profile-scoped favorites (limit clamped 1–200). |
| `history_list()` | Continue Watching entries (progress < 95%, capped at 40 recent entries per provider/profile). |
| `settings_update({ autoplayNext?, subtitlesDefault?, preferredAudio?, compactLibrary? })` | Persists playback and appearance preferences in SQLite. |
| `catalog_query({ kind?, categoryId?, query?, offset, limit, sort })` | Indexed, paged SQLite queries over the active published generation. |
| `catalog_categories({ kind })` | Category list with counts for the active published generation. |
| `catalog_refresh()`, `catalog_refresh_cancel()` | Atomic generation-staged refresh (max 4 concurrent category fetches, 30s timeout, bounded transient retries only). Preserves last good catalogue on failure or cancellation. |
| `media_details({ ref })` | Returns full movie/series metadata, seasons/episodes, related titles, My List status, and resume position/episode ID. |
| `epg_query({ channelIds, fromMs, toMs })` | Bounded to <= 50 channels and <= 24-hour window; deduplicates in-flight requests and caches per `(providerId, channelId)`. |
| `home_sections()` | Builds Continue Watching, Live Now, For You, Because You Watched, Top 10, New Releases, and My List locally from SQLite. |
| `player_start({ ref, episodeId?, resume })` | Resolves stream URL strictly inside Rust, loads into embedded libmpv, starts 250ms session poller, persists progress every 10s and on pause/seek/stop/switch/exit. |
| `player_control({ action, value? })` | Typed actions only: `pause`, `resume`, `seekAbsoluteMs`, `seekRelativeMs`, `volume`, `mute`, `audioTrack`, `subtitleTrack`, `previousEpisode`, `nextEpisode`, `selectEpisode`, `stop`. No arbitrary mpv commands from JS. |
| `player_snapshot()` | Returns current `PlayerSnapshot` for UI recovery after reload. |

## Events

- `catalog-progress`: `{ refreshId, status, stage, completedCategories, totalCategories, liveCount, movieCount, seriesCount, error? }`
- `player-state`: `{ sessionId, snapshot }`

Frontend listeners register before initiating actions, unlisten on unmount, and discard obsolete `refreshId` / `sessionId` events.

## Window & Playback Composition

- Initial 1280×800, minimum 900×600, native OS title bar and decorations.
- `tauri-plugin-window-state` persists size/position/fullscreen, paired with a startup monitor-intersection guard so the window never restores offscreen.
- Windows uses an embedded native child surface (`HWND` `STATIC` child positioned between the 80px header and 180px control bar) attached to dynamically loaded `libmpv-2.dll` (mpv 0.42.0 LGPL) via `libloading`. The child surface is hidden while browsing and destroyed on window close.
- Linux X11/XWayland/Wayland native surface embedding remains an open platform gate pending a Linux display host.
