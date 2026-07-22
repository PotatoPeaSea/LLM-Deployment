package com.geniechatrn.genie

/**
 * Per-model prompt formatting.
 *
 * Two models with genuinely different templates ship in this app, and getting a
 * template wrong doesn't error -- it silently degrades output. Both are
 * transcribed from the chat_template in the exported bundle's
 * tokenizer_config.json, which is the same source 05_deploy_and_run.sh uses.
 *
 * Every template has to render two shapes, because the dialog's KV cache
 * survives between queries (see ChatEngine):
 *
 *   renderFull  -- a whole conversation from scratch, for a fresh or reset
 *                  dialog. Includes the leading BOS, if the model has one.
 *   renderTurn  -- just the next user turn, for a dialog that already holds
 *                  the conversation. No BOS: a second one confuses the model.
 */
enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }

data class Message(val role: Role, val content: String)

/** Per-request knobs that change how the prompt is rendered. */
data class RenderOptions(
    /** Qwen3 only: false injects an empty <think></think> to suppress reasoning. */
    val thinking: Boolean = false,
)

sealed class ChatTemplate {

    abstract fun renderMessage(msg: Message): String

    /** Opens the assistant turn, i.e. tells the model to start generating. */
    protected abstract fun generationHeader(options: RenderOptions): String

    /** Prefix for a conversation started from scratch; empty for models with no BOS. */
    protected open val bos: String get() = ""

    fun renderFull(messages: List<Message>, options: RenderOptions = RenderOptions()): String =
        bos + messages.joinToString("") { renderMessage(it) } + generationHeader(options)

    fun renderTurn(msg: Message, options: RenderOptions = RenderOptions()): String =
        renderMessage(msg) + generationHeader(options)

    /**
     * Deliberate OVER-estimate (chars / 3.2). Overshooting wastes a little
     * context; undershooting truncates a reply mid-sentence. Genie reports true
     * occupancy after each query, so this only has to predict the next turn.
     */
    fun estimateTokens(text: String): Int = (text.length / 3.2).toInt() + 1

    fun messageTokens(msg: Message): Int = estimateTokens(renderMessage(msg))

    object Llama3 : ChatTemplate() {
        override val bos = "<|begin_of_text|>"

        override fun renderMessage(msg: Message): String =
            "<|start_header_id|>${msg.role.wire}<|end_header_id|>\n\n${msg.content}<|eot_id|>"

        override fun generationHeader(options: RenderOptions): String =
            "<|start_header_id|>assistant<|end_header_id|>\n\n"
    }

    /**
     * Qwen3 (ChatML). No BOS token of its own.
     *
     * Reasoning is controlled entirely by what follows the assistant header:
     * left open, the model emits its own <think>...</think> block first; primed
     * with an EMPTY think block, it skips reasoning and answers directly. That
     * is exactly what the official template does for enable_thinking=false, and
     * it is why the toggle needs no magic words in the user's message.
     */
    object Qwen3 : ChatTemplate() {
        override fun renderMessage(msg: Message): String =
            "<|im_start|>${msg.role.wire}\n${msg.content}<|im_end|>\n"

        override fun generationHeader(options: RenderOptions): String =
            if (options.thinking) "<|im_start|>assistant\n"
            else "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }
}

/**
 * Splits a Qwen3 reply into its reasoning and its answer *while it streams*.
 *
 * The <think> block arrives token by token like everything else, so the UI can't
 * wait for a complete reply to decide what is reasoning -- feed fragments in and
 * read [thoughts] and [answer] after each one. A reply with no think block puts
 * everything in [answer], which is what makes this safe to run unconditionally.
 */
class ReasoningSplitter {
    private val raw = StringBuilder()

    val thoughts: String
        get() {
            val text = raw.toString()
            val start = text.indexOf(OPEN)
            if (start < 0) return ""
            val body = text.substring(start + OPEN.length)
            val end = body.indexOf(CLOSE)
            return (if (end < 0) body else body.substring(0, end)).trim()
        }

    val answer: String
        get() {
            val text = raw.toString()
            val start = text.indexOf(OPEN)
            if (start < 0) return text.trim()
            val close = text.indexOf(CLOSE, start)
            // Still inside an unterminated think block: no answer yet.
            if (close < 0) return ""
            return text.substring(close + CLOSE.length).trim()
        }

    /** True once a think block has opened, so the UI can show the section early. */
    val hasThoughts: Boolean get() = raw.contains(OPEN)

    /**
     * What to actually show once generation has ENDED.
     *
     * Observed on Qwen3-4B at ctx512: it often reasons and then stops, never
     * closing </think> and never writing a separate answer. Mid-stream that is
     * indistinguishable from "still thinking", but at the end it means the
     * reasoning *is* the reply -- so promote it, rather than leaving the user
     * with an empty bubble and the answer hidden behind a disclosure.
     */
    fun finish(): Pair<String, String> {
        val body = answer
        return if (body.isNotBlank()) body to thoughts else thoughts to ""
    }

    fun append(fragment: String) {
        raw.append(fragment)
    }

    companion object {
        private const val OPEN = "<think>"
        private const val CLOSE = "</think>"
    }
}
