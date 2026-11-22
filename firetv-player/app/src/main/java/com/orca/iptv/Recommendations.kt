package com.orca.iptv

import android.content.Context
import kotlin.math.absoluteValue

data class Recommendation(
    val item: Catalog.Item,
    val mode: Int,
    val categoryId: String,
    val score: Double
)

/**
 * Small, local hybrid recommender. It blends what the profile actually watched
 * or saved with category affinity and a stable novelty jitter, then applies a
 * per-category cap so a single genre cannot own the whole row.
 *
 * This is intentionally a tiny on-device baseline, not a pretend Netflix model.
 */
object Recommendations {

    /**
     * Candidates come from the whole library when it is loaded (31k movies, 17k
     * series); the bundled home slice is only a fallback. Drawing from ~970 titles was
     * why "For You" kept repeating itself.
     *
     * Scoring: category affinity learned from history, plus a jitter that is stable per
     * profile and per id, then at most two picks per category per pass so a single
     * shelf cannot own the row.
     */
    fun forYou(context: Context, profileId: String, limit: Int = 18): List<Recommendation> {
        val history = HistoryStore.all(context, profileId)
        val favorites = FavoriteStore.all(context, profileId)

        val affinity = HashMap<String, Double>()
        val seen = HashSet<String>()

        history.forEach { entry ->
            if (entry.categoryId.isNotBlank()) {
                affinity[entry.categoryId] =
                    (affinity[entry.categoryId] ?: 0.0) + 1.0 + entry.progress * 2.0
            }
            seen.add("${entry.mode}:${entry.id}")
        }
        favorites.forEach { seen.add("${it.mode}:${it.id}") }

        val vodItems = if (Catalog.hasSearch) {
            Catalog.searchVodItems
        } else {
            Catalog.vodSections.flatMap { it.items }
        }
        val seriesItems = if (Catalog.hasSearch) {
            Catalog.searchSeriesItems
        } else {
            Catalog.seriesSections.flatMap { it.items }
        }

        val seed = profileId.hashCode()
        val buckets = HashMap<String, ArrayList<Recommendation>>()

        fun collect(items: List<Catalog.Item>, mode: Int, bonus: Double) {
            items.forEach { item ->
                if (seen.contains("$mode:${item.id}")) return@forEach
                val bucket = buckets.getOrPut(item.categoryId) { ArrayList(4) }
                if (bucket.size >= 4) return@forEach
                bucket.add(
                    Recommendation(
                        item = item,
                        mode = mode,
                        categoryId = item.categoryId,
                        score = score(affinity, item.categoryId, seed, item.id) + bonus
                    )
                )
            }
        }

        collect(vodItems, MODE_VOD, 0.0)
        collect(seriesItems, MODE_SERIES, 0.15)

        val strongestFirst = buckets.entries.sortedByDescending { entry ->
            entry.value.maxOf { it.score }
        }

        val out = ArrayList<Recommendation>(limit)
        for (pass in 0 until 2) {
            strongestFirst.forEach { (_, bucket) ->
                if (out.size >= limit) return@forEach
                val pick = bucket.getOrNull(pass) ?: return@forEach
                if (!seen.add("${pick.mode}:${pick.item.id}")) return@forEach
                out.add(pick)
            }
            if (out.size >= limit) break
        }

        if (out.size < limit) {
            strongestFirst.forEach { (_, bucket) ->
                bucket.forEach { candidate ->
                    if (out.size < limit && out.none { it.item.id == candidate.item.id }) {
                        out.add(candidate)
                    }
                }
            }
        }
        return out.take(limit)
    }

    fun becauseYouWatched(
        context: Context,
        profileId: String,
        limit: Int = 18
    ): Pair<String, List<Recommendation>>? {
        val history = HistoryStore.all(context, profileId)
        return history.asSequence().mapNotNull { entry ->
            val item = Catalog.find(entry.mode, entry.id) ?: return@mapNotNull null
            val sectionId = item.categoryId
            val pool = if (Catalog.hasSearch) {
                val source = if (entry.mode == MODE_SERIES) {
                    Catalog.searchSeriesItems
                } else {
                    Catalog.searchVodItems
                }
                source.filter { it.categoryId == sectionId && it.id != entry.id }
            } else {
                val sections = if (entry.mode == MODE_SERIES) Catalog.seriesSections else Catalog.vodSections
                sections.firstOrNull { it.id == sectionId }?.items.orEmpty()
                    .filterNot { it.id == entry.id }
            }
            if (pool.isEmpty()) return@mapNotNull null
            val poolMapped = pool
                .map {
                    Recommendation(
                        item = it,
                        mode = entry.mode,
                        categoryId = sectionId,
                        score = 1.0
                    )
                }
            Catalog.displayName(item.name) to poolMapped.take(limit)
        }.firstOrNull()
    }

    private fun score(
        affinity: Map<String, Double>,
        categoryId: String,
        seed: Int,
        id: String
    ): Double {
        val known = affinity[categoryId] ?: 0.0
        val noise = ((id.hashCode() xor seed).absoluteValue % 1000) / 1000.0
        return known * 3.0 + noise * 0.9
    }
}
