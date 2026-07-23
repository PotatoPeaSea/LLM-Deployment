package com.geniechatrn.genie

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The one tool that leaves the device.
 *
 * Keyless by design: there is no search API key on this project, and requiring
 * one would make the feature undemoable. So it uses two endpoints that need no
 * account:
 *
 *   1. DuckDuckGo's Instant Answer API -- good at definitions, people, places,
 *      "what is X"; returns nothing at all for many ordinary queries.
 *   2. Wikipedia's REST summary -- the fallback, and the reason (1)'s blind
 *      spots are survivable.
 *
 * Neither is a real web index, so this answers encyclopaedic questions well and
 * "what happened today" poorly. That tradeoff is worth stating to the model,
 * which is why the schema says what it is good for.
 *
 * Only the query string the model chose is transmitted. No chat history, no
 * device identifiers.
 */
object WebSearchTool : Tool {
    private const val TAG = "WebSearchTool"
    private const val TIMEOUT_MS = 8000
    private const val MAX_CHARS = 1200

    override val name = "web_search"

    override fun schema() = functionSchema(
        name,
        "Search the web for factual and encyclopaedic information: " +
            "definitions, people, places, organisations, science, history. " +
            "Use it when the answer is a fact you do not know or that may have " +
            "changed. It is weak at breaking news and live data.",
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "The search query.")
                })
            })
            put("required", JSONArray().put("query"))
        },
    )

    override fun run(context: Context, args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) return "No query given."

        instantAnswer(query)?.let { return it }
        wikipediaSummary(query)?.let { return it }
        return "No result found for \"$query\"."
    }

    /** DuckDuckGo Instant Answer. Returns null when it has nothing useful. */
    private fun instantAnswer(query: String): String? = runCatching {
        val url = "https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1"
        val json = JSONObject(get(url) ?: return null)

        json.optString("AbstractText").takeIf { it.isNotBlank() }?.let { abstract ->
            val source = json.optString("AbstractSource").takeIf { it.isNotBlank() }
            return "$abstract" + (source?.let { " (source: $it)" } ?: "")
        }
        json.optString("Answer").takeIf { it.isNotBlank() }?.let { return it }

        // RelatedTopics is the last resort: a list of one-line blurbs.
        val topics = json.optJSONArray("RelatedTopics") ?: return null
        val lines = mutableListOf<String>()
        for (i in 0 until minOf(topics.length(), 3)) {
            val text = topics.optJSONObject(i)?.optString("Text").orEmpty()
            if (text.isNotBlank()) lines.add("- $text")
        }
        if (lines.isEmpty()) null else lines.joinToString("\n")
    }.getOrElse {
        Log.w(TAG, "instant answer failed", it); null
    }

    /** Wikipedia REST summary for the best-matching article title. */
    private fun wikipediaSummary(query: String): String? = runCatching {
        val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=${enc(query)}&srlimit=1&format=json"
        val title = JSONObject(get(searchUrl) ?: return null)
            .optJSONObject("query")?.optJSONArray("search")
            ?.optJSONObject(0)?.optString("title")
            ?.takeIf { it.isNotBlank() } ?: return null

        val summary = JSONObject(
            get("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title)}") ?: return null,
        ).optString("extract").takeIf { it.isNotBlank() } ?: return null

        "$summary (source: Wikipedia, \"$title\")"
    }.getOrElse {
        Log.w(TAG, "wikipedia lookup failed", it); null
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    /**
     * Plain HttpURLConnection rather than a client dependency: this is two GETs
     * of small JSON, and the app has no other HTTP of its own.
     */
    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // Both APIs are friendlier to a request that identifies itself, and
            // Wikipedia's policy asks for it outright.
            setRequestProperty("User-Agent", "GenieChatRN/1.0 (on-device assistant)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }.take(MAX_CHARS * 8)
        } finally {
            connection.disconnect()
        }
    }
}
