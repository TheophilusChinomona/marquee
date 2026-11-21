package com.orca.iptv

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A corner card, not a modal takeover: the check runs on launch and the offer only
 * appears when a newer build is actually published.
 *
 * The prompt is a Dialog rather than a view added to the activity, because Home
 * requests default focus while its catalogue renders and would steal focus from an
 * overlay. A dialog owns its window, so the D-pad stays in the prompt.
 *
 * The install is always handed to the system installer — a sideloaded app cannot
 * replace itself silently, and Fire OS shows its "unknown apps" gate on first use.
 */
object UpdatePrompt {

    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val TAG = "MarqueeUpdate"
    private var current: Dialog? = null
    private var primary: TextView? = null

    fun showIfAvailable(activity: AppCompatActivity, scope: CoroutineScope) {
        scope.launch {
            val update = withContext(Dispatchers.IO) { UpdateChecker.check(activity) } ?: return@launch
            present(activity, update)
        }
    }

    private fun present(activity: AppCompatActivity, update: AppUpdate) {
        if (activity.isFinishing || current?.isShowing == true) return

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = MarqueeUi.fill(activity, 0xFF141414.toInt(), 10)
            setPadding(dp(activity, 28), dp(activity, 24), dp(activity, 28), dp(activity, 24))
        }

        panel.addView(MarqueeUi.text(activity, "UPDATE AVAILABLE", 12f, MarqueeUi.DUST, MarqueeUi.medium()).apply {
            letterSpacing = 0.12f
        })
        panel.addView(MarqueeUi.text(
            activity,
            "Marquee ${update.versionName}",
            26f,
            MarqueeUi.INK,
            MarqueeUi.display()
        ).apply { setPadding(0, dp(activity, 6), 0, 0) })
        panel.addView(MarqueeUi.text(
            activity,
            update.notes.ifBlank { "A newer build is available on GitHub." },
            15f,
            MarqueeUi.SAND,
            MarqueeUi.body()
        ).apply { setPadding(0, dp(activity, 10), 0, 0) })

        val status = MarqueeUi.text(
            activity,
            "Replaces this install. Profiles, My List and history stay on the device.",
            13f,
            MarqueeUi.DUST,
            MarqueeUi.body()
        ).apply { setPadding(0, dp(activity, 14), 0, 0) }
        panel.addView(status)

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 18), 0, 0)
        }
        val install = MarqueeUi.button(activity, "Download & install", primary = true)
        val later = MarqueeUi.button(activity, "Later")
        buttons.addView(install, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(activity, 44)
        ))
        buttons.addView(later, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(activity, 44)
        ).apply { marginStart = dp(activity, 12) })
        panel.addView(buttons)

        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { dismiss() }
        }
        scrim.addView(panel, FrameLayout.LayoutParams(
            dp(activity, 620),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply {
            leftMargin = dp(activity, 48)
            bottomMargin = dp(activity, 48)
        })

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(scrim, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setDimAmount(0f)
            }
            // Dismiss only through "Later": after the system installer returns, the
            // prompt must still be there so a retry (or a permission grant) is possible.
            setCancelable(false)
        }
        current = dialog

        later.setOnClickListener { dismiss() }
        install.setOnClickListener { startDownload(activity, update, status, install) }
        dialog.setOnDismissListener {
            current = null
            primary = null
        }
        primary = install
        dialog.show()
        // If the catalogue finishes rendering it calls requestDefaultFocus() on Home,
        // which can land after this; re-assert once the layout has settled.
        install.postDelayed({ if (dialog.isShowing) install.requestFocus() }, 400L)
    }

    private fun dismiss() {
        runCatching { current?.dismiss() }
        current = null
        primary = null
    }

    /**
     * Called from the host activity's onResume: returning from the system installer
     * restores the app window with focus on the decor view, which leaves the D-pad
     * nowhere useful. Put it back on the retry button.
     */
    fun refocus() {
        val button = primary ?: return
        if (current?.isShowing != true) return
        button.postDelayed({ if (current?.isShowing == true) button.requestFocus() }, 150L)
    }

    private fun startDownload(
        activity: AppCompatActivity,
        update: AppUpdate,
        status: TextView,
        button: TextView
    ) {
        button.isEnabled = false
        button.text = "Downloading…"
        Thread {
            val apk = try {
                download(activity, update) { percent ->
                    activity.runOnUiThread { status.text = "Downloading… $percent%" }
                }
            } catch (error: Exception) {
                Log.e(TAG, "download failed", error)
                null
            }
            activity.runOnUiThread {
                if (apk == null) {
                    status.text = "Download failed. Check the connection and try again."
                    button.text = "Download & install"
                    button.isEnabled = true
                    button.requestFocus()
                } else {
                    status.text = "Opening the installer…"
                    // The installer may need a permission grant first, so keep a way
                    // back: an enabled button also gives the D-pad something to focus
                    // when the app window resumes.
                    button.text = "Install again"
                    button.isEnabled = true
                    if (!install(activity, apk)) {
                        status.text = "Could not open the installer on this device."
                    }
                }
            }
        }.apply { name = "marquee-update" }.start()
    }

    private fun download(context: Context, update: AppUpdate, onProgress: (Int) -> Unit): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "Marquee.apk")
        if (target.exists()) target.delete()

        val conn = URL(update.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "apk http $code for ${update.apkUrl}")
                return null
            }
            val total = conn.contentLengthLong
            var copied = 0L
            var last = -1
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) {
                            val percent = ((copied * 100L) / total).toInt()
                            if (percent != last) {
                                last = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (update.sha256.isNotBlank() && !sha256(target).equals(update.sha256, ignoreCase = true)) {
                Log.e(TAG, "sha256 mismatch: downloaded ${sha256(target)} expected ${update.sha256}")
                target.delete()
                return null
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    private fun install(activity: AppCompatActivity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            // Fire OS: Settings → My TV → Developer Options → Install unknown apps → Marquee.
            Toast.makeText(
                activity,
                "Allow \"Install unknown apps\" for Marquee, then choose Download & install again",
                Toast.LENGTH_LONG
            ).show()
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.e(TAG, "could not start installer", error)
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun dp(context: Context, value: Int): Int = MarqueeUi.dp(context, value)
}
