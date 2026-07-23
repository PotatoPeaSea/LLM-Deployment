package com.geniechatrn.genie

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read upcoming calendar events.
 *
 * Queries Instances rather than Events on purpose: Events stores a recurring
 * meeting once, with an RRULE the caller would have to expand itself. Instances
 * is the expanded view, so "what's on tomorrow" includes the weekly stand-up
 * without this file having to understand recurrence rules at all.
 */
object CalendarTool : Tool {
    override val name = "query_calendar"
    override val permission = Manifest.permission.READ_CALENDAR

    private const val MAX_RESULTS = 20
    private const val DAY_MS = 24L * 60 * 60 * 1000

    override fun schema() = functionSchema(
        name,
        "List the user's upcoming calendar events. Use this for questions " +
            "about the user's schedule, meetings or appointments.",
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("days_ahead", JSONObject().apply {
                    put("type", "integer")
                    put("description",
                        "How many days ahead to look. Defaults to 7. Use 1 for today/tomorrow.")
                })
            })
            put("required", JSONArray())
        },
    )

    override fun run(context: Context, args: JSONObject): String {
        val days = args.optInt("days_ahead", 7).coerceIn(1, 90)
        val start = System.currentTimeMillis()
        val end = start + days * DAY_MS

        // Instances is queried by appending the window to the URI, not by a
        // selection -- that is how the provider knows what to expand.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
            it.build()
        }

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
        )

        val stamp = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
        val dayOnly = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        val events = mutableListOf<String>()

        context.contentResolver.query(
            uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && events.size < MAX_RESULTS) {
                val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(untitled)"
                val begin = c.getLong(1)
                val finish = c.getLong(2)
                val location = c.getString(3)?.takeIf { it.isNotBlank() }
                val allDay = c.getInt(4) == 1
                events.add(buildString {
                    if (allDay) {
                        append("${dayOnly.format(Date(begin))} (all day): $title")
                    } else {
                        append("${stamp.format(Date(begin))}–${
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(finish))
                        }: $title")
                    }
                    if (location != null) append(" @ $location")
                })
            }
        }

        return when {
            events.isEmpty() -> "No events in the next $days day(s)."
            else -> "Next $days day(s):\n" + events.joinToString("\n")
        }
    }
}
