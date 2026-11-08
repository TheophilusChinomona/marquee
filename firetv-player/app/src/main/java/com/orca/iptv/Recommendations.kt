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

    fun forYou(context: Context, profileId: String, limit: Int = 18): List<Recommendation> {
        val history = HistoryStore.all(context, profileId)
        val favorites = FavoriteStore.all(context, profileId)
        val sectionMap = HashMap<String, Pair<Catalog.Item, Int>>()

        Catalog.vodSections.forEach { section ->
            section.items.forEach { sectionMap[it.id] = it to MODE_VOD }
        }
        Catalog.seriesSections.forEach { section ->
            section.items.forEach { sectionMap[it.id] = it to MODE_SERIES }
        }

        val affinity = HashMap<String, Double>()
        val seen = HashSet<String>()

        fun learn(item: Catalog.Item, mode: Int, weight: Double) {
            val sectionId = sectionMap[item.id]?.first?.categoryId ?: item.categoryId
            if (sectionId.isNotBlank()) affinity[sectionId] = (affinity[sectionId] ?: 0.0) + weight
            seen.add("${mode}:${item.id}")
        }

        history.forEach { entry ->
            val found = sectionMap[entry.id]?.first
            if (found != null) learn(found, entry.mode, 1.0 + entry.progress * 2.0)
            seen.add("${entry.mode}:${entry.id}")
        }
        favorites.forEach { favorite ->
            val found = sectionMap[favorite.id]?.first
            if (found != null) learn(found, favorite.mode, 2.2)
            seen.add("${favorite.mode}:${favorite.id}")
        }

        val seed = profileId.hashCode()
        val scored = ArrayList<Recommendation>()
        Catalog.vodSections.forEach { section ->
            section.items.forEach itemLoop@ { item ->
                if (seen.contains("${MODE_VOD}:${item.id}")) return@itemLoop
                scored.add(
                    Recommendation(
                        item = item,
                        mode = MODE_VOD,
                        categoryId = section.id,
                        score = score(affinity, section.id, seed, item.id)
                    )
                )
            }
        }
        Catalog.seriesSections.forEach { section ->
            section.items.forEach itemLoop@ { item ->
                if (seen.contains("${MODE_SERIES}:${item.id}")) return@itemLoop
                scored.add(
                    Recommendation(
                        item = item,
                        mode = MODE_SERIES,
                        categoryId = section.id,
                        score = score(affinity, section.id, seed, item.id) + 0.15
                    )
                )
            }
        }

        val ordered = scored.sortedByDescending { it.score }
        val perCategory = HashMap<String, Int>()
        val diverse = ArrayList<Recommendation>(limit)
        ordered.forEach { candidate ->
            val count = perCategory[candidate.categoryId] ?: 0
            if (count < 2) {
                diverse.add(candidate)
                perCategory[candidate.categoryId] = count + 1
            }
        }
        if (diverse.size < limit) {
            ordered.forEach { candidate ->
                if (diverse.none { it.item.id == candidate.item.id && it.mode == candidate.mode }) {
                    diverse.add(candidate)
                }
            }
        }
        return diverse.take(limit)
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
            val candidates = when (entry.mode) {
                MODE_SERIES -> Catalog.seriesSections
                else -> Catalog.vodSections
            }
            val pool = candidates.firstOrNull { it.id == sectionId }?.items.orEmpty()
                .filterNot { it.id == entry.id }
                .map {
                    Recommendation(
                        item = it,
                        mode = entry.mode,
                        categoryId = sectionId,
                        score = 1.0
                    )
                }
            if (pool.isEmpty()) return@mapNotNull null
            Catalog.displayName(item.name) to pool.take(limit)
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
