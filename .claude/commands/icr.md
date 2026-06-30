---
description: Run an Interactive Code Review (ICR) session inside the running Eclipse (BlueMind Dev Tools)
argument-hint: "[--source <branch>] [--repo <path>]"
---

Run an Interactive Code Review (ICR) session **inside the running Eclipse** (BlueMind Developer
Tools plugin). The user selects code in an Eclipse editor, right-clicks **Ask Claude (ICR)…**, and
types a question or change request. You listen for these via the plugin's MCP server, act on each one
(answer and/or edit the file), and post a reply that appears inline in Eclipse as a comment thread.

## Usage

```
/icr [--source <branch>] [--repo <path>]
```

- `--source`: branch the review is framed against (e.g. `master`). Optional context only — the user
  can comment on any selection in any file, not just the diff.
- `--repo`: repository path (default: current working directory).

This reuses the always-on Eclipse MCP server (the same one documented in
`net.bluemind.devtools/docs/CLAUDE_CODE_MCP.md`). There is no separate server to start — `icr_start`
simply opens a review session and enables the editor menu.

## Architecture — a thin dispatcher, one agent per thread

The main Claude session must stay free. It does **not** run the poll loop and it does **not** handle
threads itself. Instead:

1. It launches a **background poller** that long-polls `icr_next` until a thread arrives, prints it,
   and exits (Step 3). While it polls, your main session is idle and usable.
2. When a thread arrives, the main session **spawns a fresh subagent to handle just that one thread**
   (Step 4), waits for it, then **re-arms the poller** and goes back to idle.

Threads are dispatched **serially** — one subagent at a time — which keeps concurrent edits to the
same file from colliding. Threads posted while a subagent is working are queued server-side (not
lost) and delivered on the next poll. Each subagent gets a clean, focused context (just its thread),
so long review sessions never accumulate context or hit turn/token limits.

## What to do

### Step 1 — Locate the Eclipse MCP endpoint

Parse `$ARGUMENTS` for `--source` and `--repo` (default `--repo` to the current working directory).

Find the config of the Eclipse open on this workspace and extract `url` + `token`:

```bash
# There may be several if multiple Eclipse instances run (one per branch). Prefer the one whose
# "workspace"/"projects" match the repo under review; otherwise take the first.
ls ~/.config/bluemind/mcp/eclipse-*.json
CFG=$(ls ~/.config/bluemind/mcp/eclipse-*.json | head -1)
URL=$(jq -r .url "$CFG")
TOKEN=$(jq -r .token "$CFG")
```

If no config exists, tell the user to enable **Window → Preferences → BlueMind → "Enable MCP server
for Claude Code"** and that the plugin must be installed/running, then stop.

Note the resolved `URL` and `TOKEN` — you'll pass them to each per-thread subagent (Step 4). Define a
helper for the calls you make directly from the main session (`icr_start`, `icr_stop`):

```bash
icr() { # $1=tool name, $2=arguments JSON object
  curl -s -X POST "$URL" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    --max-time 60 \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
    | jq -r '.result.content[0].text'
}
```

### Step 2 — Start the session

```bash
icr icr_start '{"source":"<source-or-empty>","repo":"<repo>"}'
```

This enables the **Ask Claude (ICR)…** context menu in Eclipse and re-queues any thread still
awaiting an answer. Tell the user:

> ICR session active. In Eclipse, select code → right-click **Ask Claude (ICR)…** (or `Ctrl+Alt+C`)
> to leave a question or change request. I'll handle each one in a dedicated agent and reply inline.

### Step 3 — Listen with a background poller

Run the poll loop **in the background** so the main session isn't blocked. The poller long-polls
`icr_next`, keeps looping while the status is `idle`, and prints the payload + exits as soon as the
status is anything else (`thread` or `inactive`). Its exit re-invokes the main session to dispatch.

Launch this with `run_in_background: true`:

```bash
CFG=$(ls ~/.config/bluemind/mcp/eclipse-*.json | head -1)
URL=$(jq -r .url "$CFG"); TOKEN=$(jq -r .token "$CFG")
while :; do
  RESP=$(curl -s -X POST "$URL" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" --max-time 60 \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"icr_next","arguments":{}}}' \
    | jq -c '.result.content[0].text | fromjson')
  [ "$(echo "$RESP" | jq -r '.status // "idle"')" != "idle" ] && { echo "$RESP"; break; }
done
```

When it exits:

- Payload `{"status":"inactive"}` → the session was stopped in Eclipse. Go to Step 5.
- Payload `{"status":"thread","thread":{…}}` → dispatch it (Step 4), then **relaunch this poller**.

### Step 4 — Dispatch one subagent per thread

Do **not** handle the thread in the main session. Spawn a subagent (Agent tool) whose entire job is
that one thread, and wait for it. Then relaunch the poller (Step 3). Give the subagent this brief,
with `URL`, `TOKEN`, `<repo>`, and the verbatim `thread` JSON substituted in:

> You are handling one Interactive Code Review thread inside a running Eclipse. Endpoint: `URL` /
> bearer token `TOKEN`. Repo: `<repo>`.
>
> Thread JSON: `<thread>`
>
> The thread has: `id`, `filePath` (workspace-relative, e.g. `/net.bluemind.foo/src/Foo.java`),
> `absolutePath`, `startLine`, `endLine`, `selectedText`, `body` (the user's request), `status`, and
> `replies` (the full conversation on this thread; the last entry is the user's latest message).
>
> 1. Read the file (use `absolutePath`) around `startLine`–`endLine`, using `selectedText` as the
>    precise anchor.
> 2. Decide from `body` and `replies`:
>    - **A question / clarification** → answer it.
>    - **A change request** → edit the file, then recompile in Eclipse when warranted (see fast-mode
>      rules below). Derive the Eclipse project from the first segment of `filePath` (e.g.
>      `net.bluemind.foo`) and call `refresh_projects`; inspect `get_problems` and fix compile errors
>      before replying.
>    - **Both** → make the edit first, then answer.
> 3. Post your reply (markdown, rendered in the Eclipse popup). Reference the earlier exchange; if you
>    changed code, summarize what you did.
>
> Call MCP tools with curl, e.g.:
> ```bash
> icr() { curl -s -X POST "$URL" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
>   --max-time 60 \
>   -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
>   | jq -r '.result.content[0].text'; }
> icr refresh_projects '{"projects":["net.bluemind.foo"]}'
> icr icr_reply '{"threadId":"<id>","body":"<your markdown reply>"}'
> ```
> Follow the **Keeping replies fast** rules below. Your final message is not shown to the user — the
> reply the user sees is the one you post with `icr_reply`.

When the subagent returns, relaunch the poller (Step 3).

### Step 5 — Stop

When the user says they are done, or the poller returned `{"status":"inactive"}`:

```bash
icr icr_stop '{}'
```

This ends the session and disables the menu. Existing `💬` threads stay visible in open editors.

## Keeping replies fast (default: fast mode)

These rules are part of the brief handed to each per-thread subagent (Step 4). Replies feel slow in
Eclipse not because of long polling (that delivers a thread the instant it's posted) but because of
the work done *after* the thread arrives: source verification, Eclipse recompiles, and
multi-paragraph reply generation. Threads are also handled **serially** — the next poll only resumes
after the current reply is posted — so long turnarounds compound.

Default to a lean loop optimized for responsiveness:

- **Keep replies short.** A few sentences, not essays. This is the single biggest lever on latency.
  Expand only when the user asks for detail.
- **For questions, answer directly** from the code you can already see. Skip `ack`/cross-file
  verification unless the answer is non-obvious or you'd otherwise be guessing.
- **For change requests, still edit correctly**, but only recompile (`refresh_projects` +
  `get_problems`) when the edit is non-trivial or could plausibly break compilation. Skip the
  recompile for trivial edits (comments, renames within a scope, string/message tweaks) and say so.
- Don't over-explain the tooling or narrate steps — just do the work and reply.

If the user asks for thoroughness (verify before answering, always recompile, detailed rationale),
switch back to the careful mode for the rest of the session.
