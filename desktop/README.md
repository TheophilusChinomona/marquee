# Marquee desktop preview

An interactive React/TypeScript design preview for the planned Windows and Linux Tauri app.

```powershell
cd desktop
npm ci
npm run dev
```

Open http://localhost:1420. Build the frontend with `npm run build`.

Explore Home, Movies, Series, title details, episodes, Live TV guide, My List, Search, Profiles, Settings, and provider setup. The player has simulated controls; no video is streamed. All content is sample data, state resets on reload, and no provider credentials are collected. The Tauri backend remains the initial scaffold; native playback and provider integration are future work.

Review screenshots are in `design-review/`. Sample film and television artwork is served locally for design review; artwork belongs to its respective owners and was sourced from The Movie Database. It is not a production catalogue.

The broader desktop design and implementation plan are in `../docs/superpowers/`. Backend work is paused while the screens are reviewed.
