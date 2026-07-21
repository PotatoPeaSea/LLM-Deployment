"""Llama-3.x chat-template rendering and context budgeting.

Two jobs:

1. Render a message list into the exact Llama-3.x prompt format. Getting this
   wrong doesn't error, it silently degrades output quality -- the template is
   copied from the exported bundle's tokenizer_config.json chat_template, same
   source 05_deploy_and_run.sh uses.

2. Keep the rendered prompt inside the model's context window. With a 4096
   context and a tool-calling system prompt, this is a real constraint, not a
   formality: the tool catalog is fixed overhead paid on EVERY turn, and tool
   results (web pages especially) are the single biggest source of context
   blowup. Budget is enforced by dropping whole oldest turns, never by
   truncating the system prompt or the newest user message.

Token counting is a deliberate over-estimate (chars / 3.2) rather than a real
tokenizer: the `tokenizers` package isn't installed on this host, and the cost
of overshooting the estimate is a wasted turn on-device, while the cost of
undershooting is a hard truncation mid-generation. Erring high is cheap.
"""
from __future__ import annotations

from dataclasses import dataclass

# Roles Llama 3.x understands. "ipython" is its designated role for tool
# results -- using it (rather than stuffing results into a user turn) keeps the
# model from mistaking tool output for something the human said.
TOOL_RESULT_ROLE = "ipython"

CHARS_PER_TOKEN = 3.2


@dataclass
class Message:
    role: str  # system | user | assistant | ipython
    content: str


def estimate_tokens(text: str) -> int:
    return int(len(text) / CHARS_PER_TOKEN) + 1


BOS = "<|begin_of_text|>"
# Trailing open assistant header is what tells the model to generate now.
GENERATION_HEADER = "<|start_header_id|>assistant<|end_header_id|>\n\n"


def render_message(msg: Message) -> str:
    return f"<|start_header_id|>{msg.role}<|end_header_id|>\n\n{msg.content}<|eot_id|>"


def render(messages: list[Message]) -> str:
    """Render messages into the Llama-3.x prompt, ready for generation."""
    return BOS + "".join(render_message(m) for m in messages) + GENERATION_HEADER


def message_tokens(msg: Message) -> int:
    """Cost of a message *as rendered*, wrapper tokens included.

    Measured off render_message rather than the bare content so the budget in
    fit_to_context can't drift from what render() actually produces -- that
    drift is exactly how a prompt sails past the context limit.
    """
    return estimate_tokens(render_message(msg))


def fit_to_context(
    system: Message,
    history: list[Message],
    context_length: int,
    reserve_for_reply: int = 512,
    pinned: list[Message] | None = None,
) -> list[Message]:
    """Return system + as much recent history as fits in the context window.

    Drops from the OLDEST end, and only at turn boundaries (a user message and
    everything it produced stay together), so the model never sees a tool
    result whose originating request has been evicted -- that combination
    reliably confuses small models into re-calling the tool.

    `pinned` messages (the few-shot examples) sit between the system prompt and
    the live history and are never evicted -- on a 1B model they are what keeps
    tool-calling working at all, so losing them mid-conversation would silently
    break the agent exactly when the conversation got interesting.
    """
    pinned = pinned or []
    fixed = (
        message_tokens(system)
        + sum(message_tokens(m) for m in pinned)
        + estimate_tokens(BOS)
        + estimate_tokens(GENERATION_HEADER)
    )
    budget = context_length - reserve_for_reply - fixed
    if budget <= 0:
        raise ValueError(
            f"System prompt and examples alone exceed the {context_length}-token "
            f"context budget; shrink the tool catalog or the few-shot examples."
        )

    # Walk backwards accumulating messages, then cut at the last boundary that fit.
    kept: list[Message] = []
    used = 0
    for msg in reversed(history):
        cost = message_tokens(msg)
        if used + cost > budget:
            break
        kept.append(msg)
        used += cost
    kept.reverse()

    # Don't open on a dangling tool result / assistant reply whose user turn was
    # evicted; trim forward to the first user message.
    while kept and kept[0].role != "user":
        kept.pop(0)

    return [system, *pinned, *kept]
