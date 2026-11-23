# Shared Android and desktop releases

## Intent and agreed versioning

Publish Windows and Linux installers alongside Android on GitHub Releases, and let installed desktop apps discover and install newer versions. The user chose the same version as Android.

## Approaches

- Shared release pipeline and version (chosen): select the existing monotonic Android version once, build every platform with it, and publish one complete release. This requires coordinating the builds but keeps downloads and update manifests consistent.
- Independent desktop releases: simpler platform scheduling, but separate version bumps and release channels conflict with the chosen shared version.

## Release behavior

Retain the existing default-branch and manual release triggers and monotonic version selection. Its current automatic patch increment remains: each eligible default-branch push creates the next shared version unless a higher prepared Android version is supplied.

Select versionCode, semantic versionName, and tag once in a preparation job. Android uses that versionCode and versionName; desktop packaging receives the same versionName for both its native executable and frontend version display. Source package versions may remain development versions; release builds must override them consistently.

Build Android, Windows x86-64 NSIS, and Linux x86-64 AppImage/deb for the same commit. Preserve their existing tests and runtime staging. Publish only after every build and verification succeeds. Assemble all assets first, create a draft release, then publish it as latest. A failed platform must leave the previous public release and updater manifests intact. Preserve serialized releases and fail rather than overwrite an existing published version.

Release assets include the Android APK and existing version.json, desktop installers, desktop update signatures, and a latest.json desktop updater manifest. Windows updates use NSIS and Linux updates use AppImage. Debian packages are downloadable releases; Debian installations should be directed to the new package rather than offered an unsupported automatic replacement.

The existing desktop PR workflow remains independent of release secrets and continues to test/package contributions without publishing.

Retries may reuse or replace only an unpublished draft belonging to the selected commit/tag; never mutate a public release. Enable updater artifact signing only in release builds. Read the installed version through Tauri rather than a hardcoded UI label.

## Desktop update experience

Use Tauri's official updater and process plugins. Check for updates on native release startup without blocking setup or playback. Show an available-update notice with an explicit install action; never install or restart automatically during playback. Settings > About includes the actual installed version, Check for updates, available version, download progress, errors with retry, and Update and restart. A dismissed startup notice leaves the update accessible in Settings. Network errors on background checks do not interrupt app use. Preview/development mode does not perform production updates.

Verify the updater signature before installation. Use GitHub's latest release latest.json endpoint, which is reliable because Android and desktop publish together. Add only updater and restart capabilities needed by the main app window.

Detect AppImage packaging through native runtime information rather than treating all Linux installations alike. Before installation/relaunch, stop playback and flush application state through the existing shutdown path; process restart must not rely on a window-close event for cleanup.

## Signing and rollout

Generate one persistent Tauri update signing identity, store its private key only in a protected local backup and GitHub Actions secret, and commit its public key into updater configuration. Do not log the private key. Signing secrets are used only by the default-branch release workflow. Document recovery/backup location without revealing the key. No operating-system code-signing certificates are assumed.

Existing 0.1.0 desktop installations have no updater; users must install the first updater-enabled release manually. Subsequent shared version bumps become discoverable in-app. Signed updater artifacts are required for publication; missing signing configuration fails clearly before expensive release builds.

## Verification

Test shared version propagation, updater manifest platforms/URLs/signatures, missing assets and invalid versions, and release publication dependencies. Test update availability, no-update/error states, explicit installation, progress and restart behavior with mocked native APIs. Run frontend build/tests, Rust checks, workflow validation and manifest tests. Windows/Linux installer generation and real update installation require the platform CI runs and a two-version smoke test; report those separately from local checks.

## Scope and implementation decisions

Keep Android updater schema and behavior compatible. No macOS builds, silent installs, telemetry, or changes to application user data. Builder may choose reusable jobs and helper script structure while preserving the atomic release behavior above. Installing the updater-enabled build once is the migration path.
