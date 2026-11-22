# Marquee desktop contracts

Rust owns provider credentials, SQLite state, catalogue requests, and playback. React receives sanitized DTOs. Commands return `{code,message,retryable}` errors; raw network errors and credential-bearing URLs must not cross IPC.

## Startup and identity

`app_bootstrap` returns `Bootstrap` from `src/api/types.ts`: connection state, profiles, active profile ID, credential status. Until persistence is initialized it truthfully returns a disconnected first run. Native startup failure displays Retry and never loads sample titles. Browser-only UI review loads the separate lazy `PreviewApp` adapter. The native UI never uses sample credentials or artwork as an error fallback.

Media identity is `(providerId, kind, id)`; profile activity additionally includes profile ID. Episode history includes episode ID and parent series ID. IDs are strings even when the provider sends numbers. Rust is authoritative for active profile/provider and playback session.

## Window

1280×800 initially, minimum 900×600, native decorations. The Tauri window-state plugin persists geometry, maximization, and fullscreen; it checks available monitors before restoring positions. Native startup omits the preview title strip. Fullscreen permission is explicitly granted to the main window, without shell or arbitrary filesystem capabilities.

## Playback feasibility

Windows native child-surface playback has been observed with mpv 0.42.0; see `VALIDATION.md`. React controls occupy a reserved header/footer around the native video rectangle, with resize/fullscreen handled on the UI thread. Final overlay composition remains open. Linux X11/Wayland gates remain unverified while this host has no Linux environment.

The maintained `libmpv2` 7.0.1 binding was evaluated. It links `libmpv2-sys` at build/load time, so it cannot provide our required recoverable missing-runtime path without additional delay loading. The current adapter uses `libloading` with only the published client ABI needed for lifecycle, typed commands, properties and events. Runtime handles are owned; initialization failure drops the handle and library. Internal native commands are not an IPC escape hatch: the frontend can only send the `PlayerCommand` enum.
