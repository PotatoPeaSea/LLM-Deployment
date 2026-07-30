package com.geniechatrn.genie

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools the on-device model is allowed to call.
 *
 * GenieX has no function-calling API of its own, so this is assembled by hand
 * around the one hook it does give us: `applyChatTemplate(..., tools = <json>)`.
 * Qwen3.5's template renders that JSON into the system prompt in Hermes style
 * and, when it wants a tool, the model emits
 *
 *     <tool_call>{"name": "...", "arguments": {...}}</tool_call>
 *
 * [parseCalls] pulls those back out and [Tool.run] executes them. See
 * GenieXEngine.generate for the loop that ties the two ends together.
 *
 * Every tool is READ-ONLY and local unless stated. Nothing here can send the
 * user's data anywhere -- web_search transmits only the query string the model
 * chose, which is the one place anything leaves the device.
 */
data class ToolCall(val name: String, val arguments: JSONObject)

interface Tool {
    val name: String
    /** JSON Schema, in the shape Qwen's template expects under `function`. */
    fun schema(): JSONObject
    /**
     * The Android runtime permission this needs, or null. Declared rather than
     * checked inside [run] so the engine can ask for it BEFORE the model's turn
     * is already half-spent, and so a refusal becomes a tool result the model
     * can talk about instead of an exception that kills the reply.
     */
    val permission: String? get() = null
    fun run(context: Context, args: JSONObject): String
}

object Tools {

    /** Registry. Order is the order the model sees them in. */
    fun all(): List<Tool> = listOf(
        DateTimeTool,
        BatteryTool,
        DeviceInfoTool,
        CalendarTool,
        ContactsTool,
        WebSearchTool,
    )

    fun byName(name: String): Tool? = all().firstOrNull { it.name == name }

    /**
     * The `tools` argument for applyChatTemplate: a JSON array of
     * {"type":"function","function":{name, description, parameters}}.
     */
    fun schemaJson(tools: List<Tool> = all()): String {
        val array = JSONArray()
        for (tool in tools) {
            array.put(JSONObject().apply {
                put("type", "function")
                put("function", tool.schema())
            })
        }
        return array.toString()
    }

    fun hasPermission(context: Context, permission: String?): Boolean =
        permission == null ||
            ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Extract every `<tool_call>{...}</tool_call>` in [reply].
     *
     * Tolerant on purpose: a 2B at 4-bit will occasionally forget the closing
     * tag or wrap the JSON in a ```json fence, and re-prompting costs a whole
     * generation. Anything that does not parse is ignored, which degrades to
     * "the model just talked" rather than to an error.
     */
    fun parseCalls(reply: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        var index = 0
        while (true) {
            val start = reply.indexOf(OPEN, index)
            if (start < 0) break
            val bodyStart = start + OPEN.length
            val close = reply.indexOf(CLOSE, bodyStart)
            // Unterminated: take the rest and let the JSON parse decide.
            val body = if (close < 0) reply.substring(bodyStart) else reply.substring(bodyStart, close)
            index = if (close < 0) reply.length else close + CLOSE.length

            val json = body.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            runCatching {
                val obj = JSONObject(json)
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@runCatching
                val args = obj.optJSONObject("arguments") ?: JSONObject()
                calls.add(ToolCall(name, args))
            }
            if (close < 0) break
        }
        return calls
    }

    /** True if the reply is asking for a tool rather than answering. */
    fun wantsTool(reply: String): Boolean = reply.contains(OPEN)

    /**
     * Everything the model said that was NOT a tool call, so a reply that mixes
     * prose and a call does not leak the raw XML into the chat bubble.
     */
    fun stripCalls(reply: String): String {
        var out = reply
        while (true) {
            val start = out.indexOf(OPEN)
            if (start < 0) break
            val close = out.indexOf(CLOSE, start)
            out = if (close < 0) out.substring(0, start)
            else out.substring(0, start) + out.substring(close + CLOSE.length)
        }
        return out.trim()
    }

    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"
}

/** Convenience for the many tools whose schema takes no arguments. */
internal fun noArgs(): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject())
    put("required", JSONArray())
}

internal fun functionSchema(
    name: String,
    description: String,
    parameters: JSONObject,
): JSONObject = JSONObject().apply {
    put("name", name)
    put("description", description)
    put("parameters", parameters)
}
