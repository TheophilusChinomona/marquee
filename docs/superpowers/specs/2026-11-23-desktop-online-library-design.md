# Desktop Online Library

## Intent

Complete the desktop app's online resolver integration so the user can connect,
find movies and series, and watch without requiring an Xtream IPTV account.
The user selected the recommended standalone Online Library approach.

## Existing system and constraints

Desktop uses Tauri 2, React, Rust, SQLite, an OS credential store, and embedded
libmpv. Its current account, catalogue, history, and player paths assume an
Xtream provider. Android already implements the resolver protocol and provides
a reference for mapping titles, seasons, episodes, and authentication.

The existing resolver at https://resolver.sentiolangley.com passed an
authenticated /health request and reports version 0.2.0. The main checkout's
ignored .env contains CLIENT_ID, CLIENT_SECRET, and TMDB_READ_TOKEN. No values
belong in source control, frontend build variables, documentation, or logs.

The resolver exposes search and title details, not a full browsable catalogue.
The first release is therefore search-led; it must not imply an empty local
catalogue means there are no online titles. Resolved video links can depend on
the resolver and viewer sharing a public IP; a healthy connection does not
guarantee a given title will play.

## Approaches

1. Standalone Online Library, selected: connection and search work independently
   of IPTV. This requires removing provider-only assumptions from navigation,
   profile activity, and playback, but delivers the requested online access.
2. IPTV search fallback: mirror Android's fallback when local search has no hits.
   Smaller integration, but still requires an IPTV account to enter the app.
3. Embed a resolver server and Chromium in desktop: avoids external server setup
   but adds a browser runtime and service lifecycle. Rejected for this integration
   because the user already has a working authenticated server.

## User experience

- Setup offers IPTV and Online Library connections. Online setup accepts server
  URL, client ID, client secret, and a remember-on-device option. The supplied
  resolver URL can be entered during setup; distributed builds do not silently
  connect to this private deployment.
- A successful authenticated health check establishes the online connection.
  Settings supports checking, changing, and disconnecting it independently of IPTV.
- With only online connected, the app opens a useful search-led library with
  access to profiles, My List, and Continue Watching. IPTV Live TV and category
  browsing remain available when an IPTV account is connected.
- Search displays clearly identified online movies and series. With both sources
  connected, local and online results remain distinguishable; online lookup is
  debounced and stale responses cannot replace newer queries.
- Details displays resolver metadata, seasons, and episodes. Play resolves a
  fresh stream, displays a resolving state, and hands it to the native player.
  Retry resolves again rather than reusing an expired signed link.
- Errors distinguish invalid credentials, network failure, missing upstream
  media, and playback failure, with retry where meaningful.

## Backend and identity

Rust owns HTTP calls, credentials, and signed stream URLs. Add a resolver client
for /health, /search, /show, and /resolve. Send CF-Access-Client-Id and
CF-Access-Client-Secret only to the configured resolver origin; do not forward
them through redirects or to media hosts. Validate HTTP(S) addresses, use
bounded request timeouts, and return sanitized AppError values through IPC.

Introduce explicit online connection state separate from the IPTV session.
Extend bootstrap and the TypeScript contract with nonsecret online connection
status. Add connection commands and online search, and route details and player
requests by namespaced media identity. Existing IPTV commands remain compatible.

Persist resolver title metadata locally for saved items and resume entries.
Use a stable resolver-origin scope and namespaced title/episode identities so
online titles cannot collide with IPTV IDs or leak activity between servers.
Online history and favorites remain profile scoped and survive reconnection.
With both connections active, home and My List can include both sources without
changing the active IPTV account. This storage separation is an implementation
choice proposed here, not a pre-existing user requirement.

## Credentials and development configuration

Use a separate OS keyring entry for resolver credentials. Remembered and
session-only connections follow the existing desktop policy: if secure storage
is unavailable, explain session-only storage and never save plaintext secrets.
Disconnect clears the corresponding credentials and stops affected playback.
It retains nonsecret saved titles and watch history.

Provide an explicit native development-only import of the main checkout's .env
for CLIENT_ID and CLIENT_SECRET. Locate that checkout through git metadata,
never a committed machine-specific path. Import is user initiated and must not
expose secrets in bootstrap or automatically authenticate production builds.
TMDB_READ_TOKEN stays server-side: the existing resolver uses it for metadata
and recommendations. This integration does not ship it in the desktop binary or
introduce a direct desktop TMDB dependency.

## Playback

Resolve on each movie start and episode transition, including next, previous,
queue selection, and autoplay. Do not pre-resolve an entire season into URLs
that expire while the user watches. Carry resolver-provided referer information
to libmpv when needed, without forwarding resolver authentication to video hosts.
Persist progress with stable online title and episode IDs. Stop or invalidate
in-flight work on disconnect or source changes so late responses cannot start
playback in another account context.

## Scope and verification

Includes connection, search, details, movies and series playback, profile-scoped
saved titles/resume, and development configuration support. No OAuth browser
flow is needed for Cloudflare service credentials. No embedded resolver,
Chromium install, new hosted service, TMDB discovery feed, or public fallback
provider is part of this release. Existing server recommendation sync can be
added later; it is not required for finding and playing online titles.

Verify resolver protocol mapping and error handling with local HTTP fixtures,
credential persistence/session-only behavior, source identity isolation, offline
saved metadata, stale query and connection responses, and fresh resolution on
episode transitions. Verify frontend setup/search/details states, run existing
frontend tests and build, then Rust formatting/tests/clippy. Finish with an
authenticated health/search/details smoke check against the supplied server.
Native playback requires a usable libmpv runtime and an available upstream title;
report any unverified playback or platform behavior explicitly.

## Builder decisions

The implementer chooses module boundaries, schema migration details, namespaced
ID encoding, exact IPC names, UI copy, pagination limits, cache limits, and
timeout values within these constraints. Reuse the established visual style and
existing components. No dependency or architecture rewrite is intended.
