"""Offline regression checks for the chatbot harness -- no device needed.

Run:  python3 tests/test_chatbot.py
Covers the parts that broke during development: tool-call parsing across the
syntax shapes a 1B model actually emits, context budgeting against a 4096-token
window, and the agent loop's tool dispatch / confirmation / hop-cap behaviour.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))

from chatbot import tools
from chatbot.agent import parse_tool_call, TOOL_SYSTEM_PROMPT
from chatbot.prompt import Message, render, fit_to_context, estimate_tokens

fails = []

def check(label, got, want):
    if got != want:
        fails.append(f"{label}: got {got!r}, want {want!r}")
        print(f"  FAIL {label}: got {got!r} want {want!r}")
    else:
        print(f"  ok   {label}")

print("== tool-call parsing (the shapes a 1B model actually emits) ==")
cases = [
    ('TOOL: get_time {}',                          ("get_time", {})),
    ('TOOL: web_search {"query": "tokyo weather"}', ("web_search", {"query": "tokyo weather"})),
    ('TOOL: find_contact {"name": "Alice"}',        ("find_contact", {"name": "Alice"})),
    ('{"tool": "web_fetch", "args": {"url": "example.com"}}', ("web_fetch", {"url": "example.com"})),
    ('{"name": "calculate", "parameters": {"expression": "2+2"}}', ("calculate", {"expression": "2+2"})),
    ('<|python_tag|>web_search(query="best pizza")', ("web_search", {"query": "best pizza"})),
    ('calculate({"expression": "7*6"})',            ("calculate", {"expression": "7*6"})),
    # synonym / bare-scalar args -- very common from small models
    ('TOOL: web_search {"q": "rain"}',              ("web_search", {"query": "rain"})),
    ('TOOL: web_search "rain today"',               ("web_search", {})),
    ('Sure, let me check.\nTOOL: device_status {}', ("device_status", {})),
]
for text, want in cases:
    call = parse_tool_call(text)
    got = (call.name, call.args) if call else None
    check(repr(text)[:52], got, want)

print("\n== prose must NOT parse as a tool call ==")
for text in ["The capital of France is Paris.",
             "I can search the web for you if you want.",
             "You have 3 contacts named Alice."]:
    check(repr(text)[:52], parse_tool_call(text), None)

print("\n== context budgeting (the 4k question) ==")
system = Message("system", TOOL_SYSTEM_PROMPT.format(catalog=tools.catalog()))
sys_tokens = estimate_tokens(render([system]))
print(f"  system prompt + full tool catalog = ~{sys_tokens} tokens of 4096 "
      f"({100*sys_tokens/4096:.0f}%)")
if sys_tokens > 900:
    fails.append(f"system prompt too fat: {sys_tokens}")

# Simulate a long conversation and confirm it stays under budget.
history = []
for i in range(60):
    history.append(Message("user", f"Question number {i} about something. " * 6))
    history.append(Message("assistant", f"Answer number {i}. " * 6))
fitted = fit_to_context(system, history, 4096)
total = estimate_tokens(render(fitted))
print(f"  60-turn history trimmed to {len(fitted)-1} messages, ~{total} tokens")
check("fits in 4096 with reply headroom", total <= 4096 - 512 + 40, True)
check("system prompt retained", fitted[0].role, "system")
check("opens on a user turn", fitted[1].role, "user")
check("newest turn kept", fitted[-1].content, history[-1].content)

print("\n== oversized tool result is capped ==")
huge = tools.truncate("x" * 50000)
check("truncated under cap", len(huge) < tools.MAX_RESULT_CHARS + 80, True)

print("\n== local tools ==")
check("calculate 12*(3+4)", tools.calculate("12*(3+4)"), "84")
check("calculate rejects code", tools.calculate("__import__('os').system('ls')").startswith("Could not"), True)
print(f"  get_time -> {tools.get_time()}")

print("\n== tool catalog ==")
print(tools.catalog())


from chatbot.agent import Agent

from chatbot.prompt import estimate_tokens

fails = []

class FakeRuntime:
    """Replays canned model replies; records the prompts it was handed."""
    def __init__(self, replies):
        self.replies = list(replies)
        self.prompts = []
    def generate(self, prompt, timeout=300):
        self.prompts.append(prompt)
        return self.replies.pop(0) if self.replies else "(no more replies)"

def scenario(label, replies, user, confirm=lambda q: True, expect_calls=None):
    print(f"\n== {label} ==")
    rt = FakeRuntime(replies)
    calls = []
    orig = tools.REGISTRY["calculate"].func
    agent = Agent(rt, context_length=4096, confirm=confirm, log=lambda m: calls.append(m), enable_tools=True)
    answer = agent.ask(user)
    print(f"  user:   {user}")
    for c in calls: print(f"  {c.strip()}")
    print(f"  answer: {answer}")
    print(f"  model invocations: {len(rt.prompts)}")
    return agent, rt, answer, calls

# 1. Plain answer, no tool.
agent, rt, answer, calls = scenario(
    "no tool needed", ["The capital of France is Paris."], "capital of France?")
if calls: fails.append("plain answer should not invoke a tool")
if len(rt.prompts) != 1: fails.append("plain answer should take 1 model call")

# 2. Single tool hop: calculate.
agent, rt, answer, calls = scenario(
    "tool hop -> calculate",
    ['TOOL: calculate {"expression": "137*24"}', "That comes to 3288."],
    "what is 137 times 24?")
if not any("calculate" in c for c in calls): fails.append("calculate not dispatched")
if "3288" not in answer: fails.append("final answer missing tool result")
# the tool result must actually be in the second prompt
if "3288" not in rt.prompts[1]: fails.append("tool result not fed back into prompt")
if "ipython" not in rt.prompts[1]: fails.append("tool result not using ipython role")

# 3. Confirmation DENIED on a side-effecting tool.
agent, rt, answer, calls = scenario(
    "confirm denied -> call()",
    ['TOOL: call {"number": "+15551234"}', "Okay, I cancelled that call."],
    "call +15551234", confirm=lambda q: False)
if "declined" not in rt.prompts[1]: fails.append("denial not reported back to model")

# 4. Runaway loop is bounded.
agent, rt, answer, calls = scenario(
    "runaway tool loop is capped",
    ['TOOL: get_time {}'] * 6 + ["It is Tuesday."],
    "what time is it?")
if len(rt.prompts) > 4:
    fails.append(f"loop not bounded: {len(rt.prompts)} model calls")
print(f"  (bounded to {len(rt.prompts)} model invocations)")

# 5. Multi-turn history is retained across asks.
rt = FakeRuntime(["Hello!", "You said hi.", "Your name is Bryan."])
agent = Agent(rt, context_length=4096, confirm=lambda q: True, log=lambda m: None, enable_tools=True)
agent.ask("hi")
agent.ask("my name is Bryan")
agent.ask("what is my name?")
print("\n== multi-turn memory ==")
last = rt.prompts[-1]
print(f"  final prompt carries earlier turns: {'my name is Bryan' in last}")
print(f"  final prompt ~{estimate_tokens(last)} tokens")
if "my name is Bryan" not in last: fails.append("history not carried across turns")
if estimate_tokens(last) > 4096: fails.append("prompt exceeded context")

# 6. A huge tool result must not blow the context.
print("\n== oversized tool result vs context ==")
rt = FakeRuntime(['TOOL: web_fetch {"url": "example.com"}', "Summarised."])
agent = Agent(rt, context_length=4096, confirm=lambda q: True, log=lambda m: None, enable_tools=True)
tools.REGISTRY["web_fetch"].func = lambda url: "LOREM " * 20000  # 120k chars
agent.ask("summarise example.com")
tok = estimate_tokens(rt.prompts[1])
print(f"  prompt after a 120k-char page: ~{tok} tokens (limit 4096)")
if tok > 4096: fails.append(f"oversized tool result blew context: {tok}")


print("\n" + ("ALL CHECKS PASSED" if not fails else f"{len(fails)} FAILURES:\n" + "\n".join(fails)))
sys.exit(1 if fails else 0)
