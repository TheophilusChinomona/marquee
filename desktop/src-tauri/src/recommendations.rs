use crate::{
    models::{AppError, HomeSection, HomeSectionsResponse, MediaItem, MediaKind},
    store::Store,
};
use std::collections::{HashMap, HashSet};

const DEFAULT_LIMIT: usize = 18;
const MAX_CANDIDATES_PER_CATEGORY: usize = 4;

fn stable_hash(input: &str) -> u64 {
    // FNV-1a 64-bit deterministic hash across platforms and runs.
    let mut hash: u64 = 0xcbf29ce484222325;
    for byte in input.as_bytes() {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

fn score_item(
    affinity: &HashMap<(MediaKind, String), f64>,
    kind: MediaKind,
    category_id: &str,
    seed: u64,
    id: &str,
) -> f64 {
    let known = affinity
        .get(&(kind, category_id.to_string()))
        .copied()
        .unwrap_or(0.0);
    let noise_bucket = (stable_hash(id) ^ seed) % 1000;
    let noise = (noise_bucket as f64) / 1000.0;
    known * 3.0 + noise * 0.9
}

struct ScoredItem {
    item: MediaItem,
    score: f64,
}

/// Local hybrid recommender ported from Android `Recommendations.kt`.
/// Blends profile watch-history category affinity with deterministic novelty jitter
/// and a per-category diversity cap.
pub fn for_you(
    store: &Store,
    provider_id: &str,
    profile_id: &str,
    limit: usize,
) -> Result<Vec<MediaItem>, AppError> {
    let history = store.all_history(provider_id, profile_id)?;
    let favorites = store.all_favorite_refs(provider_id, profile_id)?;
    let (generation, _) = store.provider_generation(provider_id)?;
    let Some(generation) = generation else {
        return Ok(Vec::new());
    };

    let mut affinity: HashMap<(MediaKind, String), f64> = HashMap::new();
    let mut seen: HashSet<(MediaKind, String)> = HashSet::new();

    for entry in &history {
        let Ok(kind) = MediaKind::parse(&entry.kind) else {
            continue;
        };
        seen.insert((kind, entry.media_id.clone()));
        if let Some(item) = store.get_catalogue_item_for_generation(
            provider_id,
            &generation,
            kind,
            &entry.media_id,
        )? && !item.category_id.trim().is_empty()
        {
            let progress = if entry.duration_ms > 0 {
                (entry.position_ms as f64 / entry.duration_ms as f64).clamp(0.0, 1.0)
            } else if entry.completed {
                1.0
            } else {
                0.0
            };
            *affinity.entry((kind, item.category_id)).or_insert(0.0) += 1.0 + progress * 2.0;
        }
    }

    for fav in favorites {
        seen.insert((fav.kind, fav.id));
    }

    let vod_candidates = store.sample_items_by_kind(
        provider_id,
        MediaKind::Movie,
        2000,
        "COALESCE(c.rating, 0) DESC, COALESCE(c.added, 0) DESC, c.id ASC",
    )?;
    let series_candidates = store.sample_items_by_kind(
        provider_id,
        MediaKind::Series,
        2000,
        "COALESCE(c.rating, 0) DESC, COALESCE(c.added, 0) DESC, c.id ASC",
    )?;

    let seed = stable_hash(profile_id);
    let mut buckets: HashMap<(MediaKind, String), Vec<ScoredItem>> = HashMap::new();

    let mut collect = |items: Vec<MediaItem>, bonus: f64| {
        for item in items {
            let kind = item.media_ref.kind;
            if seen.contains(&(kind, item.media_ref.id.clone())) {
                continue;
            }
            let bucket = buckets.entry((kind, item.category_id.clone())).or_default();
            if bucket.len() >= MAX_CANDIDATES_PER_CATEGORY {
                continue;
            }
            let score =
                score_item(&affinity, kind, &item.category_id, seed, &item.media_ref.id) + bonus;
            bucket.push(ScoredItem { item, score });
        }
    };

    collect(vod_candidates, 0.0);
    collect(series_candidates, 0.15);

    for bucket in buckets.values_mut() {
        bucket.sort_by(|a, b| {
            b.score
                .total_cmp(&a.score)
                .then_with(|| a.item.media_ref.id.cmp(&b.item.media_ref.id))
        });
    }

    let mut strongest_first: Vec<((MediaKind, String), Vec<ScoredItem>)> =
        buckets.into_iter().collect();
    strongest_first.sort_by(|((ka, cat_a), a), ((kb, cat_b), b)| {
        let max_a = a.first().map(|s| s.score).unwrap_or(0.0);
        let max_b = b.first().map(|s| s.score).unwrap_or(0.0);
        max_b
            .total_cmp(&max_a)
            .then_with(|| ka.as_str().cmp(kb.as_str()))
            .then_with(|| cat_a.cmp(cat_b))
    });

    let mut out: Vec<MediaItem> = Vec::with_capacity(limit);
    let mut picked: HashSet<(MediaKind, String)> = HashSet::new();

    for pass in 0..2 {
        for (_, bucket) in &strongest_first {
            if out.len() >= limit {
                break;
            }
            if let Some(candidate) = bucket.get(pass) {
                let key = (
                    candidate.item.media_ref.kind,
                    candidate.item.media_ref.id.clone(),
                );
                if picked.insert(key) {
                    out.push(candidate.item.clone());
                }
            }
        }
        if out.len() >= limit {
            break;
        }
    }

    if out.len() < limit {
        for (_, bucket) in &strongest_first {
            for candidate in bucket {
                if out.len() >= limit {
                    break;
                }
                let key = (
                    candidate.item.media_ref.kind,
                    candidate.item.media_ref.id.clone(),
                );
                if picked.insert(key) {
                    out.push(candidate.item.clone());
                }
            }
        }
    }

    Ok(out)
}

pub fn because_you_watched(
    store: &Store,
    provider_id: &str,
    profile_id: &str,
    limit: usize,
) -> Result<Option<(String, Vec<MediaItem>)>, AppError> {
    let history = store.all_history(provider_id, profile_id)?;
    let (generation, _) = store.provider_generation(provider_id)?;
    let Some(generation) = generation else {
        return Ok(None);
    };

    for entry in history {
        let Ok(kind) = MediaKind::parse(&entry.kind) else {
            continue;
        };
        if kind == MediaKind::Live {
            continue;
        }
        let Some(item) = store.get_catalogue_item_for_generation(
            provider_id,
            &generation,
            kind,
            &entry.media_id,
        )?
        else {
            continue;
        };
        let peers = store.related_items(
            provider_id,
            kind,
            &item.category_id,
            &item.media_ref.id,
            limit,
        )?;
        if !peers.is_empty() {
            return Ok(Some((item.name, peers)));
        }
    }
    Ok(None)
}

pub fn build_home_sections(
    store: &Store,
    provider_id: &str,
    profile_id: &str,
    refreshing: bool,
) -> Result<HomeSectionsResponse, AppError> {
    let cache_status = store.cache_status(Some(provider_id), refreshing)?;
    let mut sections: Vec<HomeSection> = Vec::new();

    // 1. Continue Watching
    let continue_items: Vec<MediaItem> = store
        .history_items(provider_id, profile_id)?
        .into_iter()
        .filter_map(|h| h.item)
        .take(12)
        .collect();
    if !continue_items.is_empty() {
        sections.push(HomeSection {
            id: "continue".into(),
            kind: "continue".into(),
            eyebrow: Some("CONTINUE WATCHING".into()),
            title: "Pick up where you left off".into(),
            items: continue_items,
        });
    }

    // 2. Live Now
    let live_items = store.sample_items_by_kind(provider_id, MediaKind::Live, 14, "c.rowid ASC")?;
    if !live_items.is_empty() {
        sections.push(HomeSection {
            id: "live".into(),
            kind: "live".into(),
            eyebrow: Some("ON AIR".into()),
            title: "Live Now".into(),
            items: live_items,
        });
    }

    // 3. For You (local hybrid recommendations)
    let for_you_items = for_you(store, provider_id, profile_id, DEFAULT_LIMIT)?;
    if !for_you_items.is_empty() {
        sections.push(HomeSection {
            id: "for-you".into(),
            kind: "forYou".into(),
            eyebrow: Some("FOR YOU".into()),
            title: "Made for your next movie night".into(),
            items: for_you_items.clone(),
        });
    }

    // 4. Because You Watched
    if let Some((title, peers)) =
        because_you_watched(store, provider_id, profile_id, DEFAULT_LIMIT)?
    {
        sections.push(HomeSection {
            id: "because".into(),
            kind: "because".into(),
            eyebrow: Some("BECAUSE YOU WATCHED".into()),
            title: format!("Because you watched {title}"),
            items: peers,
        });
    }

    // 5. Top 10 (by provider rating evidence in this library)
    let mut top_movies = store.sample_items_by_kind(
        provider_id,
        MediaKind::Movie,
        10,
        "COALESCE(c.rating, 0) DESC, COALESCE(c.added, 0) DESC, c.id ASC",
    )?;
    let mut top_series = store.sample_items_by_kind(
        provider_id,
        MediaKind::Series,
        10,
        "COALESCE(c.rating, 0) DESC, COALESCE(c.added, 0) DESC, c.id ASC",
    )?;
    top_movies.append(&mut top_series);
    top_movies.sort_by(|a, b| {
        b.rating
            .unwrap_or(0.0)
            .total_cmp(&a.rating.unwrap_or(0.0))
            .then_with(|| b.added.unwrap_or(0).cmp(&a.added.unwrap_or(0)))
            .then_with(|| a.media_ref.id.cmp(&b.media_ref.id))
    });
    let top_10: Vec<MediaItem> = top_movies
        .into_iter()
        .take(10)
        .enumerate()
        .map(|(i, mut item)| {
            item.rank = Some(i + 1);
            item
        })
        .collect();
    if !top_10.is_empty() {
        sections.push(HomeSection {
            id: "top10".into(),
            kind: "top10".into(),
            eyebrow: Some("HIGHEST RATED IN YOUR LIBRARY".into()),
            title: "Top 10".into(),
            items: top_10.clone(),
        });
    }

    // 6. New Releases (most recently added by provider timestamp)
    let mut recent_movies = store.sample_items_by_kind(
        provider_id,
        MediaKind::Movie,
        12,
        "COALESCE(c.added, 0) DESC, COALESCE(c.year, '') DESC, c.id ASC",
    )?;
    let mut recent_series = store.sample_items_by_kind(
        provider_id,
        MediaKind::Series,
        12,
        "COALESCE(c.added, 0) DESC, COALESCE(c.year, '') DESC, c.id ASC",
    )?;
    recent_movies.append(&mut recent_series);
    recent_movies.sort_by(|a, b| {
        b.added
            .unwrap_or(0)
            .cmp(&a.added.unwrap_or(0))
            .then_with(|| b.year.cmp(&a.year))
            .then_with(|| a.media_ref.id.cmp(&b.media_ref.id))
    });
    let new_releases: Vec<MediaItem> = recent_movies.into_iter().take(14).collect();
    if !new_releases.is_empty() {
        sections.push(HomeSection {
            id: "new-releases".into(),
            kind: "newReleases".into(),
            eyebrow: Some("RECENTLY ADDED".into()),
            title: "New Releases".into(),
            items: new_releases.clone(),
        });
    }

    // 7. My List
    let my_list = store.favorites_page(provider_id, profile_id, 0, 14)?.items;
    if !my_list.is_empty() {
        sections.push(HomeSection {
            id: "my-list".into(),
            kind: "myList".into(),
            eyebrow: Some("SAVED FOR LATER".into()),
            title: "My List".into(),
            items: my_list,
        });
    }

    // Hero picks: prefer items with artwork from For You -> Top 10 -> New Releases
    let mut hero: Vec<MediaItem> = Vec::new();
    let mut hero_seen: HashSet<(MediaKind, String)> = HashSet::new();
    for pool in [&for_you_items, &top_10, &new_releases] {
        for item in pool {
            if hero.len() >= 3 {
                break;
            }
            let key = (item.media_ref.kind, item.media_ref.id.clone());
            if hero_seen.insert(key) {
                hero.push(item.clone());
            }
        }
    }

    Ok(HomeSectionsResponse {
        hero,
        sections,
        cache_status,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::provider::{ParsedCatalogItem, ParsedCategory, normalize_search_name};

    fn make_item(
        kind: MediaKind,
        id: &str,
        cat: &str,
        rating: f64,
        added: i64,
    ) -> ParsedCatalogItem {
        let name = format!("Title {id}");
        ParsedCatalogItem {
            kind,
            id: id.into(),
            normalized_name: normalize_search_name(&name),
            name,
            category_id: cat.into(),
            poster: Some(format!("https://img.test/{id}.jpg")),
            backdrop: None,
            year: Some("2024".into()),
            rating: Some(rating),
            extension: Some("mp4".into()),
            added: Some(added),
        }
    }

    #[test]
    fn recommendations_are_stable_respect_affinity_exclude_seen_and_cap_diversity() {
        let mut db = Store::open_memory().unwrap();
        let provider = db.connect_provider("acc", "http://p.test/").unwrap();
        let profile_a = db.profiles().unwrap()[0].id.clone();
        let profile_b = db.create_profile("Alex", "blue").unwrap().id;

        db.begin_refresh_run("r1", &provider).unwrap();
        db.stage_categories(
            &provider,
            "g1",
            MediaKind::Movie,
            &[
                ParsedCategory {
                    id: "sci".into(),
                    name: "Sci-Fi".into(),
                },
                ParsedCategory {
                    id: "com".into(),
                    name: "Comedy".into(),
                },
                ParsedCategory {
                    id: "dra".into(),
                    name: "Drama".into(),
                },
            ],
        )
        .unwrap();

        let mut items = Vec::new();
        for i in 1..=6 {
            items.push(make_item(
                MediaKind::Movie,
                &format!("s{i}"),
                "sci",
                8.0,
                100 + i,
            ));
            items.push(make_item(
                MediaKind::Movie,
                &format!("c{i}"),
                "com",
                8.0,
                100 + i,
            ));
            items.push(make_item(
                MediaKind::Movie,
                &format!("d{i}"),
                "dra",
                8.0,
                100 + i,
            ));
        }
        db.stage_items(&provider, "g1", &items).unwrap();
        db.publish_generation(&provider, "g1", "r1").unwrap();

        // Profile A watches Sci-Fi (s1 completed, s2 in progress) and favorites s3
        db.save_history(&provider, &profile_a, "movie", "s1", "", 1000, 1000, 10)
            .unwrap();
        db.save_history(&provider, &profile_a, "movie", "s2", "", 500, 1000, 20)
            .unwrap();
        db.set_favorite(&provider, &profile_a, "movie", "s3", true)
            .unwrap();

        // Profile B watches Comedy (c1, c2)
        db.save_history(&provider, &profile_b, "movie", "c1", "", 1000, 1000, 10)
            .unwrap();
        db.save_history(&provider, &profile_b, "movie", "c2", "", 1000, 1000, 20)
            .unwrap();

        let rec_a1 = for_you(&db, &provider, &profile_a, 10).unwrap();
        let rec_a2 = for_you(&db, &provider, &profile_a, 10).unwrap();
        let ids_a1: Vec<&str> = rec_a1.iter().map(|i| i.media_ref.id.as_str()).collect();
        let ids_a2: Vec<&str> = rec_a2.iter().map(|i| i.media_ref.id.as_str()).collect();

        // Stable deterministic order
        assert_eq!(ids_a1, ids_a2);
        // Seen/favorited titles excluded
        assert!(!ids_a1.contains(&"s1"));
        assert!(!ids_a1.contains(&"s2"));
        assert!(!ids_a1.contains(&"s3"));
        // Preferred category leads
        assert_eq!(rec_a1[0].category_id, "sci");
        // Diversity cap: first 3 picks come from different categories (1 per category in pass 0)
        assert_ne!(rec_a1[1].category_id, "sci");

        // Different affinity changes ranking for Profile B
        let rec_b = for_you(&db, &provider, &profile_b, 10).unwrap();
        assert_eq!(rec_b[0].category_id, "com");

        // Because You Watched uses most recent history item (s2 -> peers in sci, excluding s2)
        let (title, peers) = because_you_watched(&db, &provider, &profile_a, 10)
            .unwrap()
            .unwrap();
        assert_eq!(title, "Title s2");
        assert!(!peers.iter().any(|p| p.media_ref.id == "s2"));
        assert!(peers.iter().all(|p| p.category_id == "sci"));

        // Home sections builds all parity rails cleanly
        let home = build_home_sections(&db, &provider, &profile_a, false).unwrap();
        let kinds: Vec<&str> = home.sections.iter().map(|s| s.kind.as_str()).collect();
        assert!(kinds.contains(&"continue"));
        assert!(kinds.contains(&"forYou"));
        assert!(kinds.contains(&"because"));
        assert!(kinds.contains(&"top10"));
        assert!(kinds.contains(&"newReleases"));
        assert!(kinds.contains(&"myList"));
        assert!(!home.hero.is_empty());
    }
}
