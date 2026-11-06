package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient
    private lateinit var query: EditText
    private lateinit var resultsView: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: View
    private val adapter = SearchResultAdapter(
        isFavorite = { result ->
            FavoriteStore.isFavorite(
                this,
                ProfileStore.activeId(this),
                result.item.id,
                result.mode
            )
        },
        onLongClick = { result -> toggleFavorite(result) }
    ) { result -> open(result) }
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Prefs.load(this) ?: run {
            finish()
            return
        }
        client = XtreamClient(creds.server, creds.user, creds.pass)

        setContentView(R.layout.activity_search)
        query = findViewById(R.id.query)
        resultsView = findViewById(R.id.results)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        resultsView.layoutManager = LinearLayoutManager(this)
        resultsView.adapter = adapter
        findViewById<Button>(R.id.back).setOnClickListener { finish() }

        query.doAfterTextChanged { text ->
            searchJob?.cancel()
            searchJob = scope.launch {
                delay(250)
                search(text?.toString().orEmpty())
            }
        }
        query.requestFocus()
        query.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
        }

        scope.launch {
            progress.visibility = View.VISIBLE
            withContext(Dispatchers.IO) { Catalog.ensureSearch(applicationContext) }
            progress.visibility = View.GONE
            status.text = "Type to search live TV, movies and series"
            if (query.text.isNotEmpty()) search(query.text.toString())
        }
    }

    private fun toggleFavorite(result: SearchResult) {
        val added = FavoriteStore.toggle(
            this,
            ProfileStore.activeId(this),
            result.item,
            result.mode
        )
        android.widget.Toast.makeText(
            this,
            if (added) "Added to favorites" else "Removed from favorites",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun search(raw: String) {
        val text = raw.trim()
        if (text.length < 2) {
            adapter.submit(emptyList())
            status.text = if (text.isEmpty()) {
                "Type to search live TV, movies and series"
            } else {
                "Type at least 2 characters"
            }
            return
        }

        progress.visibility = View.VISIBLE
        scope.launch {
            val results = withContext(Dispatchers.IO) { filter(text) }
            progress.visibility = View.GONE
            adapter.submit(results)
            status.text = if (results.isEmpty()) "No matches for \"$text\"" else ""
        }
    }

    private fun filter(text: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        Catalog.liveItems.asSequence()
            .filter { it.name.contains(text, ignoreCase = true) }
            .take(40)
            .forEach { results.add(SearchResult(it, MODE_LIVE)) }
        Catalog.searchVodItems.asSequence()
            .filter { it.name.contains(text, ignoreCase = true) }
            .take(40)
            .forEach { results.add(SearchResult(it, MODE_VOD)) }
        Catalog.searchSeriesItems.asSequence()
            .filter { it.name.contains(text, ignoreCase = true) }
            .take(40)
            .forEach { results.add(SearchResult(it, MODE_SERIES)) }
        return results
    }

    private fun open(result: SearchResult) {
        when (result.mode) {
            MODE_LIVE -> startActivity(
                PlayerActivity.intent(this, client.liveUrl(result.item.id), result.item.name)
            )
            MODE_VOD -> startActivity(
                PlayerActivity.intent(this, client.vodUrl(result.item.id, result.item.ext), result.item.name)
            )
            MODE_SERIES -> startActivity(
                SeriesActivity.intent(this, result.item.id, result.item.name)
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context) = Intent(context, SearchActivity::class.java)
    }
}
