package com.orca.iptv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ImageLoader {

    private val cache = LruCache<String, Bitmap>(8 * 1024 * 1024)
    private val pool = Executors.newFixedThreadPool(3)

    fun load(url: String?, view: ImageView) {
        if (url.isNullOrBlank()) {
            view.setImageResource(R.drawable.image_placeholder)
            return
        }
        val cached = cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageResource(R.drawable.image_placeholder)
        view.tag = url
        pool.execute {
            val bitmap = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "IPTVSmartersPlayer")
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()

            if (bitmap != null) {
                cache.put(url, bitmap)
                view.post {
                    if (view.tag == url) view.setImageBitmap(bitmap)
                }
            }
        }
    }
}
