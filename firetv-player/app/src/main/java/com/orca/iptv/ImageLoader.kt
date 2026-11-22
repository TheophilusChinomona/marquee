package com.orca.iptv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference

object ImageLoader {

    // LruCache counts entries by default. Account for bytes so artwork cannot
    // consume the player's heap after browsing a large catalogue.
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(32), ThreadPoolExecutor.DiscardOldestPolicy())

    fun load(url: String?, view: ImageView) {
        view.tag = url
        if (url.isNullOrBlank()) {
            view.setImageResource(R.drawable.image_placeholder)
            return
        }
        val targetWidth = (view.width.takeIf { it > 0 } ?: view.layoutParams?.width?.takeIf { it > 0 } ?: 960).coerceIn(96, 1920)
        val targetHeight = (view.height.takeIf { it > 0 } ?: view.layoutParams?.height?.takeIf { it > 0 } ?: 540).coerceIn(96, 1080)
        val cacheKey = "$url@$targetWidth:$targetHeight"
        val cached = cache.get(cacheKey)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageResource(R.drawable.image_placeholder)
        val target = WeakReference(view)
        pool.execute {
            if (target.get()?.tag != url) return@execute
            val bitmap = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "IPTVSmartersPlayer")
                try {
                    val bytes = conn.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; if (output.size() + n > 8 * 1024 * 1024) throw java.io.IOException("Image too large"); output.write(buffer, 0, n) }
                        output.toByteArray()
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > targetWidth * 2 || bounds.outHeight / sample > targetHeight * 2) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } finally { conn.disconnect() }
            }.getOrNull()

            if (bitmap != null) {
                cache.put(cacheKey, bitmap)
                target.get()?.post {
                    target.get()?.takeIf { it.tag == url }?.setImageBitmap(bitmap)
                }
            }
        }
    }
}
