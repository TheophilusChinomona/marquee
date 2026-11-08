# Claude Design prompt: Netflix-caliber Marquee

Use this as a single prompt. It deliberately forces a rebuild rather than a reskin, and it separates the UI from the recommendation engine so Claude Design does not return a moodboard instead of a product.

> Netflix has open-sourced a lot of **infrastructure**, but not its recommender models. The prompt therefore asks for real open-source recsys options — LightFM, TensorFlow Recommenders, Implicit, RecBole/Cornac, FAISS/ScaNN — rather than a fictional “Netflix recommender” you could clone.

```text
You are redesigning Marquee, a personal IPTV client for Fire TV / Android TV.

CONTEXT
- Native Android TV app: Kotlin, Jetpack Media3/ExoPlayer, Xtream Codes provider.
- 1920x1080, 320dpi (960x540dp), 10-foot viewing distance, D-pad only.
- One shared provider login; local profiles; per-profile favorites.
- Content: live channels, VOD movies, series, episodes, search.
- Public repo for context: https://github.com/TheophilusChinomona/marquee
- Current UI: warm black/amber "Marquee" theme, condensed type, card rails, top nav.
  It works, but it looks like a template and is nowhere near the bar below.

THE BAR
Redesign the experience to be Netflix-caliber, not a Netflix clone. Capture the
qualities that make Netflix feel premium on a TV — cinematic art direction, a
strong billboard, confident hierarchy, immersive rows, a real detail experience,
frictionless playback, and a guide that feels native to TV — while creating a
distinct Marquee identity.

Do not copy Netflix trademarks, logos, exact layouts, or artwork. Do not produce a
generic "modern, clean, sleek" dashboard. Make specific decisions and defend them.

NON-NEGOTIABLES
- Remote-first: every action reachable with D-pad; Back behavior defined.
- Focus is always unmistakable: ring, lift, scale, label treatment, no white flash.
- 1920x1080 frames, 48dp safe margins, 5% overscan tolerance.
- Minimum 18sp body/metadata; no 12sp labels.
- No blue/purple gradients, no five equal cards, no tiny captions, no placeholder
  gradients, no stock icon soup.
- 60fps interaction budget; progressive image loading; no heavy blur effects.
- Long titles, missing artwork, missing EPG, empty favorites, offline, and loading
  must all look designed.
- The current amber can stay only if it earns its place. The current layout is not
  a constraint.

DELIVERABLES — IN THIS ORDER

1) CRITIQUE + DIRECTION
- Audit the current screens as a TV product, not as a phone app.
- State one confident art direction and five design principles.
- Explain what you are removing and why.
- Reject at least three obvious but wrong choices (e.g. "Netflix clone", "dashboard",
  "equal card grid", "tiny metadata everywhere").

2) DESIGN SYSTEM
- Color, type scale, spacing (8pt grid), radii, elevation, scrims, focus treatment,
  motion timings/easing, image aspect ratios and crop rules.
- One signature interaction or visual motif that makes Marquee recognizable.
- Typography tested at 10 feet: display, section, card title, metadata, timestamps.
- Focus-state matrix: default, focused, pressed, disabled, live, progress, favorite.

3) INFORMATION ARCHITECTURE
- Global nav and Back stack.
- Home, Live TV, Movies, Series, Search, Favorites, Profiles, Player, Detail.
- Explain what belongs in the hero vs. rows vs. guide.
- Define the "continue watching" and "resume" model.

4) SCREENS — 1920x1080, high fidelity
HOME
- Cinematic billboard with title treatment, metadata, Play/Resume, Add to List.
- Ambient color extracted from artwork, with a tasteful scrim.
- Rows: Continue Watching (progress), Live Now (now/next), For You, Because You
  Watched [title], New Releases, My List, Top 10/local.
- Row peek, focused-card scale, label behavior, and horizontal scroll cues.
- Profile chip and current profile visible without clutter.

LIVE TV
- A real TV guide: time axis, channel column, now/next, program detail panel.
- Focus and scroll behavior for a 2-hour window; jump to now; day navigation.
- Channel zapping, last channel, channel numbers, favorites filter, catch-up badge.
- Mini-player while browsing the guide; full-screen live player.
- Graceful fallback when EPG is missing: now/next unknown, channel logos, no broken
  timeline.

DETAIL (movie + series)
- Backdrop, logo/title, year, rating, duration, genres, synopsis.
- Play/Resume, Add to List, trailer if available, audio/subtitle badges.
- Series: season selector, episode list with stills, progress, "next up".
- Similar titles and an explicit recommendation reason ("Because you watched...").
- Cast/crew optional; do not let metadata overwhelm the primary action.

SEARCH
- Voice/search entry that works with a remote keyboard.
- Recent searches, trending, genres, and grouped results.
- Empty, loading, and no-results states.
- Focus order that does not trap the user in the keyboard.

PROFILES
- "Who's watching?" with avatars and Add Profile.
- Active profile, edit/delete, kids-style restriction concept (optional).
- Clear copy that profiles share one provider login and isolate favorites.

PLAYER
- Live: channel logo/name, now/next, guide button, zap, audio/subtitle, buffer state.
- VOD: progress, resume, skip intro (if data exists), next episode.
- Controls auto-hide; remote-safe focus; no permanent chrome over the video.

STATES
- Loading skeletons, empty favorites, no EPG, provider offline, expired login,
  playback error, very long titles, missing artwork, 4K/live badges.

5) INTERACTIVE PROTOTYPE
- Produce a single self-contained HTML/CSS/JS prototype at 1920x1080.
- Keyboard: arrows move focus, Enter selects, Back returns, Escape closes.
- Use generic sample titles and placeholder art; no Netflix assets.
- Demonstrate at least: Home focus traversal, Live guide, Detail, Search, Profiles,
  and the player overlay.
- Include a toggle to show focus outlines and a reduced-motion variant.

6) RECOMMENDATIONS ENGINE — TECHNICAL APPENDIX
Design the UI surfaces and the engine separately. Assume a single household, a few
profiles, a small/medium catalog, and a home server or local backend — not a
Kubernetes cluster.

Event model
- impression, play, pause, seek, complete, abandon, favorite, remove, hide, search,
  dwell, profile switch, live channel tune, EPG click.
- Define event schema, privacy, retention, and per-profile aggregation.

Candidates
- Continue watching, trending/popular, new releases, content-based similarity
  (metadata + embeddings), item-item co-occurrence, collaborative filtering,
  live-channel affinity, editorial/curated, and "because you watched".
- Cold start: onboarding genre/channel picks, popularity, content metadata.

Ranking
- Learning-to-rank or hybrid scoring; freshness, fatigue, diversity (MMR), and
  business guards.
- Per-profile personalization; no cross-profile leakage.
- Explanation strings and confidence thresholds.

Evaluation
- Offline: Recall@K, NDCG, coverage, diversity.
- Online: CTR, play rate, completion, abandonment, time-to-play, hide rate.
- Guardrails against recommending the same title repeatedly.

Open-source stack
- Recommend a concrete, boring stack (Python + FastAPI + Postgres/SQLite +
  LightFM or TensorFlow Recommenders + Implicit + FAISS/ScaNN; Qdrant/Milvus only
  if justified).
- Explain what runs on-device vs. home server vs. nightly batch.
- Note: Netflix has open-sourced infrastructure (Conductor, Metaflow, DGS, Hollow,
  etc.), but its recommender models are not open source. Do not pretend otherwise;
  choose real open-source recsys components and justify them.

7) MIGRATION + PERFORMANCE
- Map the redesign onto the existing Kotlin app: HomeActivity, CardAdapter,
  BrowseActivity, SeriesActivity, SearchActivity, ProfilesActivity, PlayerActivity.
- Identify what to delete, what to replace, and what to build first.
- Give a phased plan: system foundation, Home, Live guide, Detail, Search/Profiles,
  recommendations, polish.
- Define an image/preload budget and a 60fps interaction budget.
- Call out data the app does not have yet (EPG, playback events, embeddings) and the
  smallest useful version of each.

ACCEPTANCE CRITERIA
- I can see one clear, confident direction, not a menu of moods.
- Every screen is TV-native and remote-operable.
- The Home and Live TV experiences feel like destinations, not lists.
- The recommendation engine is credible, privacy-aware, and buildable.
- The prototype runs at 1920x1080 and survives focus traversal, long titles, empty
  states, and missing EPG.
- No Netflix assets, no generic AI dashboard, no tiny text, no blue.

Start with the critique and direction. Then produce the design system and screens.
Finally produce the prototype and the recommendation-engine spec. Make decisions
rather than asking me to choose between ten options.
```
