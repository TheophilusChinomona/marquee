package com.orca.iptv

import android.content.Context
import org.json.JSONArray

object ScanAllowlist {

    fun streamIds(context: Context): Set<String> = load(context, "playable_live_ids.json")

    fun categoryIds(context: Context): Set<String> = load(context, "playable_live_categories.json")

    private fun load(context: Context, name: String): Set<String> = runCatching {
        val text = context.assets.open(name).bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        val set = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            set.add(arr.getString(i))
        }
        set
    }.getOrDefault(emptySet())
}
