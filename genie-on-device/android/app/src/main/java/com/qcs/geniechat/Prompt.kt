package com.qcs.geniechat

/**
 * Llama-3.x chat-template rendering, ported from scripts/chatbot/prompt.py.
 *
 * Getting the template wrong doesn't error, it silently degrades output, so the
 * exact strings below are the ones from the exported bundle's
 * tokenizer_config.json chat_template -- same source 05_deploy_and_run.sh uses.
 *
 * The token count is a deliberate OVER-estimate (chars / 3.2) rather than a real
 * tokenizer: overshooting wastes a little context, undershooting truncates a
 * reply mid-sentence. Genie tells us the true occupancy after each query
 * (GenieBridge.nativeContextOccupancy), so the estimate is only ever used to
 * predict whether the NEXT turn fits.
 */
object Prompt {
    const val BOS = "<|begin_of_text|>"

    /** A trailing open assistant header is what tells the model to generate now. */
    const val GENERATION_HEADER = "<|start_header_id|>assistant<|end_header_id|>\n\n"

    private const val CHARS_PER_TOKEN = 3.2

    fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN).toInt() + 1

    fun renderMessage(msg: Message): String =
        "<|start_header_id|>${msg.role.wire}<|end_header_id|>\n\n${msg.content}<|eot_id|>"

    /** Cost of a message *as rendered*, wrapper tokens included. */
    fun messageTokens(msg: Message): Int = estimateTokens(renderMessage(msg))

    /** A full prompt from scratch: BOS, every message, then the generation header. */
    fun render(messages: List<Message>): String =
        BOS + messages.joinToString("") { renderMessage(it) } + GENERATION_HEADER

    /**
     * One turn appended to a dialog whose KV cache already holds the conversation.
     * No BOS -- the cache already has it, and a second one confuses the model.
     */
    fun renderTurn(msg: Message): String = renderMessage(msg) + GENERATION_HEADER

    /**
     * Return as much recent history as fits, dropping from the OLDEST end and
     * only at turn boundaries, so the model never sees an assistant reply whose
     * user message has been evicted.
     */
    fun fitToContext(
        system: Message,
        history: List<Message>,
        contextLength: Int,
        reserveForReply: Int = 512,
    ): List<Message> {
        val fixed = messageTokens(system) + estimateTokens(BOS) + estimateTokens(GENERATION_HEADER)
        val budget = contextLength - reserveForReply - fixed
        require(budget > 0) {
            "The system prompt alone exceeds the $contextLength-token context budget."
        }

        val kept = ArrayDeque<Message>()
        var used = 0
        for (msg in history.asReversed()) {
            val cost = messageTokens(msg)
            if (used + cost > budget) break
            kept.addFirst(msg)
            used += cost
        }
        // Don't open on a dangling assistant reply whose user turn was evicted.
        while (kept.isNotEmpty() && kept.first().role != Role.USER) kept.removeFirst()

        return listOf(system) + kept
    }
}

enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

data class Message(val role: Role, val content: String)
