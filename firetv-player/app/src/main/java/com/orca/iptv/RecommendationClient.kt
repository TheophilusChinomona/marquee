package com.orca.iptv

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RecommendationPage(val cards: List<MarqueeCardItem>, val state: String, val retryAfterS: Long = 60)
class RecommendationBudget(var remaining: Int = 3)

/** Versioned household API. Calls run on IO; no player tick makes an HTTP request. */
class RecommendationClient(private val context: Context) {
    private val base = Prefs.resolverBase(context).trimEnd('/')
    private class Failure(val code: Int, val body: JSONObject) : Exception()
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 4000; c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/json")
            val key = Prefs.resolverKey(context)
            if (key.contains(':')) { val parts = key.split(':', limit = 2); c.setRequestProperty("CF-Access-Client-Id", parts[0]); c.setRequestProperty("CF-Access-Client-Secret", parts[1]) }
            else if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            val code = c.responseCode
            val input = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = input?.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n = stream.read(buffer); if (n < 0) break; if (out.size() + n > 262144) throw java.io.IOException("Recommendation response too large"); out.write(buffer, 0, n) }; out.toByteArray()
            } ?: ByteArray(0)
            val result = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            if (code !in 200..299) throw Failure(code, result)
            return result
        } finally { c.disconnect() }
    }
    fun cached(profile: String): RecommendationPage = WatchLedger(context).use { ledger ->
        val json = ledger.get("page:$profile")?.let { runCatching { JSONObject(it) }.getOrNull() }
        RecommendationPage(decodeCards(json?.optJSONArray("cards") ?: JSONArray()).filterNot {
            ledger.seen(profile, if (it.mode == MODE_SERIES) "tv" else "movie", it.item.name, it.item.id)
        }, "cached")
    }
    suspend fun refresh(profile: String, budget: RecommendationBudget = RecommendationBudget()): RecommendationPage {
        if (base.isBlank()) return RecommendationPage(emptyList(), "unavailable")
        WatchLedger(context).use { ledger ->
            ledger.migrate(profile)
            val device = ledger.deviceId()
            currentCoroutineContext().ensureActive()
            val local = ProfileStore.all(context).firstOrNull { it.id == profile } ?: return RecommendationPage(emptyList(), "unavailable")
            // Register only this profile: <=1 mapping per visit, no accidental name merging.
            val mapping = request("/v1/devices/register", "POST", JSONObject().put("deviceId", device)
                .put("profiles", JSONArray().put(JSONObject().put("localId", profile).put("name", local.name)))).getJSONArray("mappings").getJSONObject(0)
            val reset = ledger.serverProfile(profile) != null && ledger.epoch(profile) != mapping.getInt("epoch")
            ledger.map(profile, mapping.getString("profileId"), mapping.getInt("epoch"))
            if (reset) return RecommendationPage(emptyList(), "reset", 15)
            val serverProfile = mapping.getString("profileId")
            val pending = ledger.pending(profile)
            if (pending.length() > 0) {
                currentCoroutineContext().ensureActive()
                for (i in 0 until pending.length()) pending.getJSONObject(i).put("profileId", serverProfile).put("epoch", ledger.epoch(profile))
                try {
                    val accepted = request("/v1/watch/batch", "POST", JSONObject().put("deviceId", device).put("updates", pending)).getJSONArray("accepted")
                    for (i in 0 until accepted.length()) accepted.getJSONObject(i).let { ledger.acknowledge(profile, it.getString("sessionId"), it.getLong("revision")) }
                } catch (e: Failure) {
                    if (e.code == 409 && e.body.has("epoch")) { ledger.map(profile, serverProfile, e.body.getInt("epoch")); return RecommendationPage(emptyList(), "reset", 15) }
                    throw e
                }
            }
            val cards = ArrayList<MarqueeCardItem>(20); val identities = HashSet<String>()
            var cursor = ""; var state = "pending"; var retry = 60L
            var cursorRestarted = false
            ProviderTitleIndex(context).use { index ->
                index.ensure()
                for (page in 0 until 3) {
                    currentCoroutineContext().ensureActive()
                    val result = try {
                        request("/v1/recommendations?profileId=" + encode(serverProfile) + "&limit=20" + if (cursor.isBlank()) "" else "&cursor=" + encode(cursor))
                    } catch (e: Failure) {
                        if (e.code == 409 && cursor.isNotBlank() && !cursorRestarted && budget.remaining > 0) {
                            cursorRestarted = true; cursor = ""; cards.clear(); identities.clear(); continue
                        }
                        throw e
                    }
                    if (result.has("epoch") && result.getInt("epoch") != ledger.epoch(profile)) { ledger.map(profile, serverProfile, result.getInt("epoch")); return RecommendationPage(emptyList(), "reset", 15) }
                    state = result.optString("state"); retry = result.optLong("retryAfterS", 60).coerceAtLeast(15)
                    val rows = result.optJSONArray("items") ?: JSONArray()
                    if (rows.length() > 0) {
                        if (budget.remaining <= 0) {
                            if (cards.isNotEmpty()) break
                            return cached(profile).copy(state = state, retryAfterS = retry)
                        }
                        budget.remaining--
                    }
                    for (i in 0 until rows.length()) {
                        val r = rows.getJSONObject(i); val kind = r.getString("kind"); val identity = "$kind:${r.getLong("tmdbId")}"
                        if (ledger.seen(profile, kind, r.getString("title"))) continue
                        if (!identities.add(identity) || cards.size >= 20) continue
                        val provider = index.match(kind, r.getString("title"), r.optInt("year"))
                        val online = r.optString("onlineId").takeIf { it.startsWith("gd:") }
                        val item = provider ?: online?.takeIf { Prefs.onlineEnabled(context) }?.let { Catalog.Item(it, r.getString("title"), "", r.optString("poster"), "mp4") } ?: continue
                        cards.add(MarqueeCardItem(item, if (kind == "tv") MODE_SERIES else MODE_VOD,
                            badge = if (provider == null) "ONLINE" else null, meta = r.optString("reason")))
                    }
                    cursor = result.optString("nextCursor").takeIf { it != "null" }.orEmpty()
                    if (cards.size >= 20 || cursor.isBlank()) break
                }
            }
            if (cards.isNotEmpty()) ledger.put("page:$profile", JSONObject().put("cards", encodeCards(cards)).toString())
            return RecommendationPage(cards, state, retry)
        }
    }
    companion object {
        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
        fun encodeCards(cards: List<MarqueeCardItem>): JSONArray = JSONArray().apply { cards.take(20).forEach { c -> put(JSONObject().put("id", c.item.id).put("name", c.item.name).put("category", c.item.categoryId).put("icon", c.item.icon).put("ext", c.item.ext).put("mode", c.mode).put("reason", c.meta).put("badge", c.badge)) } }
        fun decodeCards(rows: JSONArray): List<MarqueeCardItem> = (0 until minOf(20, rows.length())).mapNotNull { i -> runCatching { val c = rows.getJSONObject(i); MarqueeCardItem(Catalog.Item(c.getString("id"), c.getString("name"), c.optString("category"), c.optString("icon"), c.optString("ext")), c.getInt("mode"), meta = c.optString("reason"), badge = c.optString("badge").takeIf { it != "null" && it.isNotBlank() }) }.getOrNull() }
    }
}
