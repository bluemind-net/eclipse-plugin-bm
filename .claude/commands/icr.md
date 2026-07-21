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

## Calling the MCP endpoint

All calls go through fixed scripts in `~/.claude/scripts/icr/` — never write raw curl/jq inline, and
never define a shell function around curl. Both of those patterns (a brace-delimited function body,
backslash-escaped `\"..\"` JSON) trip Claude Code's bash obfuscation heuristic and get blocked, and
they also cost far more tokens than a one-line script call. The scripts:

- **`icr_init.sh [workspace-filter]`** — resolves the Eclipse MCP config and writes
  `~/.claude/tmp/icr_env.sh`. Prints the resolved config path, or `NO_CONFIG` (exit 1) if none exists.
- **`icr_call.sh <tool_name> [json_arguments]`** — generic MCP tool call (`json_arguments` defaults to
  `{}`). Prints the tool's result text.
- **`icr_poll.sh`** — the background poll loop; run as-is with `run_in_background: true`.
- **`icr_reply.sh <threadId> <bodyFile>`** — posts an `icr_reply`, reading the markdown body from a
  file (write it with the Write tool first) so arbitrary quotes/backslashes/newlines in a reply never
  touch shell quoting.

### Step 1 — Locate the Eclipse MCP endpoint

Parse `$ARGUMENTS` for `--source` and `--repo` (default `--repo` to the current working directory).

```bash
~/.claude/scripts/icr/icr_init.sh
```

If it prints `NO_CONFIG`, tell the user to enable **Window → Preferences → BlueMind → "Enable MCP
server for Claude Code"** and that the plugin must be installed/running, then stop. There may be
several configs if multiple Eclipse instances run (one per branch); pass a substring of the repo's
workspace path as an argument to `icr_init.sh` to disambiguate, otherwise it takes the first found.

### Step 2 — Start the session

```bash
~/.claude/scripts/icr/icr_call.sh icr_start '{"source":"<source-or-empty>","repo":"<repo>"}'
```

This enables the **Ask Claude (ICR)…** context menu in Eclipse and re-queues any thread still
awaiting an answer. Tell the user:

> ICR session active. In Eclipse, select code → right-click **Ask Claude (ICR)…** (or `Ctrl+Alt+C`)
> to leave a question or change request. I'll handle each one in a dedicated agent and reply inline.

### Step 3 — Listen with a background poller

Run the poll loop **in the background** so the main session isn't blocked. It long-polls `icr_next`,
keeps looping while the status is `idle`, and prints the payload + exits as soon as the status is
anything else (`thread` or `inactive`). Its exit re-invokes the main session to dispatch.

Launch this with `run_in_background: true`:

```bash
~/.claude/scripts/icr/icr_poll.sh
```

When it exits:

- Payload `{"status":"inactive"}` → the session was stopped in Eclipse. Go to Step 5.
- Payload `{"status":"thread","thread":{…}}` → dispatch it (Step 4), then **relaunch this poller**.

### Step 4 — Dispatch one subagent per thread

Do **not** handle the thread in the main session. Spawn a subagent (Agent tool) whose entire job is
that one thread, and wait for it. Then relaunch the poller (Step 3). Give the subagent this brief,
with `<repo>` and the verbatim `thread` JSON substituted in:

> You are handling one Interactive Code Review thread inside a running Eclipse. Repo: `<repo>`.
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
> Call MCP tools with the fixed scripts in `~/.claude/scripts/icr/` — never write raw curl/jq inline,
> never define a shell function around curl (both get blocked as obfuscation):
> ```bash
> ~/.claude/scripts/icr/icr_call.sh refresh_projects '{"projects":["net.bluemind.foo"]}'
> ```
> To reply, write your markdown to a file first (Write tool), then:
> ```bash
> ~/.claude/scripts/icr/icr_reply.sh "<threadId>" /path/to/reply.md
> ```
> Follow the **Keeping replies fast** rules below. Your final message is not shown to the user — the
> reply the user sees is the one you post with `icr_reply.sh`.

When the subagent returns, relaunch the poller (Step 3).

### Step 5 — Stop

When the user says they are done, or the poller returned `{"status":"inactive"}`:

```bash
~/.claude/scripts/icr/icr_call.sh icr_stop
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
