package com.geniechatrn.genie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The context arithmetic, tested off-device.
 *
 * This is the code that decides whether a prompt fits in the window. On the
 * board it only misbehaves at the boundary -- a conversation long enough to
 * overflow -- which is slow and unreliable to reach by typing. The overflow
 * branch went unexercised on hardware for exactly that reason, so it is pinned
 * down here instead.
 *
 * The rule everything below checks: **the rendered prompt must never exceed
 * contextLength - reserve**, because Genie does not refuse an over-long prompt,
 * it silently truncates the reply (WARNING_CONTEXT_EXCEEDED).
 */
class ChatEngineContextTest {

    private val qwen = ChatTemplate.Qwen3
    private val system = Message(Role.SYSTEM, "You are a helpful assistant. Be brief.")

    private fun promptTokens(messages: List<Message>, thinking: Boolean = false): Int =
        qwen.estimateTokens(qwen.renderFull(messages, RenderOptions(thinking)))

    private fun conversation(turns: Int): List<Message> = buildList {
        repeat(turns) { i ->
            add(Message(Role.USER, "Question number $i about the NPU and how it works."))
            add(Message(Role.ASSISTANT, "Answer number $i, which is of a fairly typical length."))
        }
    }

    @Test
    fun `reserve scales with context length and toggles`() {
        // The old flat 256 took half of Qwen's window before anything was said.
        assertEquals(96, ChatEngine.reserveForReply(512, brevity = true, thinking = false))
        assertEquals(128, ChatEngine.reserveForReply(512, brevity = false, thinking = false))
        // Reasoning needs the room: <think> and the answer both land in the cache.
        assertEquals(256, ChatEngine.reserveForReply(512, brevity = true, thinking = true))
        // A big window doesn't get a silly reserve: both of these want more than
        // 512 (2048 and 682) and are held at the ceiling, because no reply from
        // these models needs more than ~512 tokens of room.
        assertEquals(512, ChatEngine.reserveForReply(4096, brevity = false, thinking = true))
        assertEquals(512, ChatEngine.reserveForReply(4096, brevity = true, thinking = false))
    }

    @Test
    fun `a long conversation is trimmed to fit the window`() {
        val reserve = ChatEngine.reserveForReply(512, brevity = true, thinking = false)
        val fitted = ChatEngine.fitToContext(qwen, system, conversation(30), 512, reserve)

        assertTrue("must drop turns", fitted.size < 61)
        assertTrue("prompt must fit", promptTokens(fitted) <= 512 - reserve)
    }

    @Test
    fun `trimming keeps the newest turn and starts on a user message`() {
        val history = conversation(30)
        val fitted = ChatEngine.fitToContext(qwen, system, history, 512, 96)

        assertEquals(Role.SYSTEM, fitted.first().role)
        // Never open on a dangling assistant reply whose question was evicted.
        assertEquals(Role.USER, fitted[1].role)
        assertEquals(history.last(), fitted.last())
    }

    @Test
    fun `a single oversized message is truncated rather than overflowing`() {
        val paste = Message(Role.USER, "x".repeat(20_000))
        val reserve = 96
        val fitted = ChatEngine.fitToContext(qwen, system, listOf(paste), 512, reserve)

        assertEquals(2, fitted.size)
        assertTrue("prompt must fit", promptTokens(fitted) <= 512 - reserve)
        assertTrue(
            "the user should be told something was cut",
            fitted.last().content.contains("trimmed to fit"),
        )
    }

    @Test
    fun `truncation keeps both ends of the text`() {
        val text = "START" + "y".repeat(5_000) + "END"
        val out = ChatEngine.truncateToTokens(text, 100)

        assertTrue(out.startsWith("START"))
        assertTrue("the question is usually at the end", out.endsWith("END"))
        assertTrue(out.length < text.length)
    }

    @Test
    fun `reasoning reserves more, so less history survives`() {
        val history = conversation(30)
        val plain = ChatEngine.fitToContext(
            qwen, system, history, 512, ChatEngine.reserveForReply(512, true, thinking = false),
        )
        val thinking = ChatEngine.fitToContext(
            qwen, system, history, 512, ChatEngine.reserveForReply(512, true, thinking = true),
        )
        assertTrue(thinking.size < plain.size)
        assertTrue(promptTokens(thinking, thinking = true) <= 512 - 256)
    }

    @Test
    fun `every window size stays inside its budget`() {
        // 512 is Qwen, 2048 is the 3B export, 4096 is Llama-1B.
        for (contextLength in listOf(512, 1024, 2048, 4096)) {
            for (thinking in listOf(false, true)) {
                val reserve = ChatEngine.reserveForReply(contextLength, true, thinking)
                val fitted =
                    ChatEngine.fitToContext(qwen, system, conversation(200), contextLength, reserve)
                assertTrue(
                    "ctx=$contextLength thinking=$thinking overflowed",
                    promptTokens(fitted, thinking) <= contextLength - reserve,
                )
            }
        }
    }
}
