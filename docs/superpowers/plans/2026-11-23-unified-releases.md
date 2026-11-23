# Shared releases implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Publish Android and desktop together and enable signed desktop updates.

**Architecture:** Select a shared version once, fan out platform builds, then publish all artifacts and manifests together. Use the official Tauri updater with explicit user installation and native shutdown preparation.

**Tech Stack:** GitHub Actions, Node.js release helpers, Tauri 2, Rust, React, Vitest.

**Spec:** ../specs/2026-11-23-unified-releases-design.md

## Global constraints

- Keep existing Android updater schema and signing identity compatible.
- Only default-branch releases receive signing secrets; PR packaging remains unsigned.
- Publish after all builds succeed; never overwrite public versions.
- Windows NSIS and Linux AppImage support in-app installation; deb directs users to downloads.
- Preserve user data and save playback before installation/restart.

## Review focus

- A missing platform or signature must block publishing.
- A failed draft must be retryable without changing public releases.
- Manual checks must explain offline/no-update states.
- Repeated clicks/checks must not install twice or expose stale update handles.
- Browser/development and Debian builds must not attempt unsupported updates.

### Task 1: Release data and manifests

Files: scripts/desktop-release.cjs, scripts/desktop-release.test.cjs; scripts/release-version.cjs and tests.

Interfaces: normalizeVersion(value) returns a three-part semantic version; prepareDesktop(version) writes native/frontend release metadata; createManifest(directory, version, repo, tag) validates required platform assets and produces latest.json.

- [x] Write failing tests for version normalization and complete/missing/invalid release inputs.
- [x] Run node --test scripts/*.test.cjs and confirm expected failures.
- [x] Implement release helpers and verify suite green.

### Task 2: Coordinated release workflow

Files: .github/workflows/release.yml, .github/workflows/desktop.yml.

Interfaces: prepare job exports code/name/tag; desktop reusable workflow accepts release version and signing key; publish consumes platform artifacts.

- [x] Extract version selection into preparation, retain Android build checks/signing, reuse desktop jobs with release-only signing and version overrides.
- [x] Assemble validated files and latest.json, guard public tag collisions, retry same-commit drafts, publish complete draft as latest.
- [x] Validate workflow syntax, script tests, and diff whitespace.

### Task 3: Native updater and UI

Files: desktop/src/features/DesktopUpdates.tsx and tests, desktop/src/App.tsx, desktop/src/features/Settings.tsx; desktop/src-tauri/src/updates.rs, lib.rs, Cargo.toml/lock; desktop/package.json/lock; capabilities/default.json and tauri.conf.json.

Interfaces: UpdatesProvider owns one shared check/install state; DesktopUpdates exposes Settings controls; UpdateNotice offers startup update; native update_context returns version/enabled/installable; prepare_update stops playback and saves state.

- [x] Write failing UI tests for available/no-update/error/progress/install/retry and unsupported builds.
- [x] Install updater/process dependencies, configure public key and release-only signing, implement native context and shutdown commands.
- [x] Implement shared UI state and startup checks, remove hardcoded version, integrate Settings and notices.
- [x] Run complete frontend suite/build and Rust fmt/test/clippy.

### Task 4: Signing setup, documentation and review

- [x] Generate persistent signing key outside the repo without logging private contents; save to repository Actions secret and configure public key.
- [x] Document release triggering, migration, AppImage/deb behavior, and signing backup.
- [x] Obtain fresh whole-change review, address material findings, rerun affected checks.
- [x] Record platform CI/update smoke-test limitations and leave changes reviewable.

## Execution ledger

Approved design; execute inline in the existing feature worktree. No additional worktree or publication is needed. Implementation commits/push are deferred until requested; retain all changes for review.


Completed locally: 23 frontend tests, native frontend production build, 32 backend/release helper tests, 35 Rust tests (two existing ignored integration tests), Rust formatting/clippy, actionlint, whitespace checks, and a signing probe. Fresh review and follow-up review complete. Signing secret configured; no private key added to the worktree. Platform release publication and two-version native update smoke tests remain pending until these changes are integrated and CI runs.

Ruling: use separate desktop workflow check steps rather than multiline PowerShell checks, since a later successful native command can otherwise mask an earlier failure. This preserves the approved all-checks-pass publication gate.
