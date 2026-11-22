package com.orca.iptv

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache

/** Runs against real Android bitmaps, including on the Fire TV. No network required. */
class ImageLoaderMemoryTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            @Suppress("UNCHECKED_CAST")
            val cache = ImageLoader::class.java.getDeclaredField("cache").apply {
                isAccessible = true
            }.get(null) as LruCache<String, Bitmap>
            cache.evictAll()
            try {
                // Nine 1 MiB images must evict the oldest from an 8 MiB cache.
                repeat(9) { index ->
                    cache.put("poster-$index", Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888))
                }
                val retainedBytes = cache.snapshot().values.sumOf { it.byteCount }
                check(retainedBytes <= 8 * 1024 * 1024) {
                    "Artwork cache retained $retainedBytes bytes; expected at most 8 MiB"
                }
                check(cache.get("poster-0") == null) { "Oldest poster was not evicted" }
                check(cache.get("poster-8") != null) { "Newest poster was evicted" }
                check(cache.size() == retainedBytes) { "Cache size is not measured in bytes" }
                result.putString("stream", "PASS: artwork cache stays within 8 MiB and evicts oldest images\n")
            } finally {
                cache.evictAll()
            }
            val ledgerName = "recommendation-ledger-test.db"
            targetContext.deleteDatabase(ledgerName)
            val indexName = "recommendation-index-test.db"
            targetContext.deleteDatabase(indexName)
            ProviderTitleIndex(targetContext, indexName).use { index ->
                index.importCatalog("movie", java.io.StringReader("""{"items":[["1","EN| Same Name - 2005","cat","","mp4"],["2","Same Name - 2024","cat","","mp4"]]}"""))
                check(index.match("movie", "Same Name", 2005)?.id == "1")
                check(index.match("movie", "Same Name", 0) == null) { "Remakes were merged" }
                check(index.match("tv", "Same Name", 2005) == null) { "Film/TV identities mixed" }
                check(ProviderTitleIndex.normalize("EN| Boston Legal [HD] - 2004") == "boston legal")
                check(ProviderTitleIndex.yearOf("EN| Boston Legal - 2004") == 2004)
            }
            targetContext.deleteDatabase(indexName)
            WatchLedger(targetContext, ledgerName).use { ledger ->
                ledger.record("p1", "session", "Boston Legal", "provider:series:1", "tv", 1800000, 2400000)
                ledger.record("p1", "session", "Boston Legal", "provider:series:1", "tv", 2000000, 2400000)
                check(ledger.pending("p1").length() == 1) { "Session updates duplicated" }
                check(ledger.pending("p1").getJSONObject(0).getLong("playedMs") == 2000000L)
                check(ledger.pending("other").length() == 0) { "Profiles mixed" }
                ledger.record("p1", "seek", "Movie", "provider:movie:2", "movie", 10000, 6000000)
                val pendingSeek = ledger.pending("p1")
                check((0 until pendingSeek.length()).map { pendingSeek.getJSONObject(it) }.first { it.getString("sessionId") == "seek" }.getBoolean("completed").not())
                ledger.map("p1", "server-profile", 0)
                ledger.map("p1", "server-profile", 1)
                ledger.record("p1", "pre-reset-player", "Old viewing", "old", "tv", 1800000, 2400000, expectedEpoch = 0)
                check(ledger.pending("p1").length() == 0) { "Reset retained old learning" }
                check(ledger.epoch("p1") == 1)
                check(!ledger.seen("other", "tv", "Boston Legal"))
                repeat(60) { ledger.record("p1", "s$it", "Movie $it", "source:$it", "movie", 600000, 6000000) }
                check(ledger.pending("p1").length() == 50) { "Pending batch unbounded" }
            }
            WatchLedger(targetContext, ledgerName).use { ledger ->
                check(ledger.pending("p1").length() == 50) { "Ledger did not persist" }
            }
            targetContext.deleteDatabase(ledgerName)
            result.putString("stream", result.getString("stream") + "PASS: durable profile ledger, completion evidence, epochs, bounded batches\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
