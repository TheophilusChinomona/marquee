# Contributing to Marquee

Thanks for wanting to help. Marquee is a personal project, but issues and pull requests are welcome.

## Ground rules

- Be respectful and constructive.
- **Never post provider credentials, URLs, or personal stream links** in issues or pull requests.
- Marquee is a client only. Do not use issues or PRs to share or request copyrighted streams.
- Keep discussion on topic: the app, the UI, and the code.

## Reporting bugs

Open an issue and include:

- Device and OS (e.g. Fire TV Stick 4K, Fire OS 7 / Android 7.1.2)
- App version (Settings or the release tag)
- What you expected vs. what happened
- Steps to reproduce
- A screenshot or short screen recording if it is a UI problem
- Relevant `adb logcat` output if it is a crash or playback problem

## Suggesting features

Open an issue describing the problem first, not just the solution. Explain what you are trying to do on a TV and why the current flow gets in the way. Focus/remote behaviour matters as much as visuals.

## Development setup

Requirements:

- JDK 17+
- Android SDK platform 35 and build-tools
- The Gradle wrapper included in `firetv-player/`

```bash
cd firetv-player
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Test on a real device or an Android TV emulator. Verify:

- D-pad focus order on every screen you touch (up/down/left/right, wrapping at edges)
- Back behaviour
- Playback start, pause, seek and resume
- Touch targets if you touch layouts

## Code style

- Kotlin, following the existing style. Keep activities small and use `MarqueeUi` tokens instead of hard-coded colours.
- Prefer the shared design system (`MarqueeUi`, `MarqueeNav`, `MarqueeHome`) over new ad-hoc views.
- Focus navigation is part of the feature. Wire `nextFocus*` or key handling explicitly when default focus search misbehaves.
- No new dependencies without a clear reason; keep the APK small.
- No analytics, tracking, or network calls beyond the user's own provider.

## Pull requests

1. Fork the repo and branch from `master`.
2. Keep the change focused: one concern per PR.
3. Describe what changed and how you tested it (device, OS, steps).
4. Screenshots or a short recording are strongly encouraged for UI changes.
5. Make sure the project builds before opening the PR:

   ```bash
   cd firetv-player
   ./gradlew :app:assembleDebug
   ```

`master` is protected: force pushes and deletions are disabled. Open a PR rather than pushing directly.

## Signing and secrets

- Never commit `keystore.properties`, `keystore/`, `.env`, or any real credentials.
- Use your own debug keystore for local builds. Release signing is configured through `firetv-player/keystore.properties`, which is git-ignored.
- If you accidentally expose a secret, rotate it immediately and tell a maintainer.

## License

By contributing, you agree that your contributions are licensed under the MIT License (see [LICENSE](LICENSE)).
