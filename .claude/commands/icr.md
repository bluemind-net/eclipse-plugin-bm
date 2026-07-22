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

## Architecture — handle threads directly, reply before recompiling

Handle threads **directly in the main session** — do not spawn a subagent per thread. Subagent
spawn plus serial dispatch adds latency that isn't worth it; the main session has plenty of room
for lightweight edit-and-reply work.

1. Launch a **background poller** that long-polls `icr_next` until a thread arrives, prints it, and
   exits (Step 3). While it polls, your main session is idle and usable.
2. When a thread arrives, handle it **yourself** (Step 4): read the file, make the edit and/or
   compose the answer, and **post the reply immediately** — before running any Eclipse recompile
   check. Only after the reply is posted do you run `refresh_projects`/`get_problems` (in the
   background is fine), and only when the edit plausibly warrants it (see "Keeping replies fast").
3. Re-arm the poller right after posting the reply. If several threads land close together, don't
   let a recompile on thread N block picking up thread N+1 — kick the recompile off and move on.

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
> to leave a question or change request. I'll handle each one and reply inline.

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

### Step 4 — Handle the thread yourself, reply first

The thread payload has: `id`, `filePath` (workspace-relative, e.g. `/net.bluemind.foo/src/Foo.java`),
`absolutePath`, `startLine`, `endLine`, `selectedText`, `body` (the user's request), `status`, and
`replies` (the full conversation on this thread; the last entry is the user's latest message).

1. Read the file (use `absolutePath`) around `startLine`–`endLine`, using `selectedText` as the
   precise anchor.
2. Decide from `body` and `replies`:
   - **A question / clarification** → answer it.
   - **A change request** → edit the file directly.
   - **Both** → make the edit first, then answer.
3. Write your reply (markdown, rendered in the Eclipse popup) to a file (Write tool), then post it
   immediately:
   ```bash
   ~/.claude/scripts/icr/icr_reply.sh "<threadId>" /path/to/reply.md
   ```
   Reference the earlier exchange; if you changed code, summarize what you did. Do this **before**
   any recompile check — don't let Eclipse verification gate the reply.
4. Only after the reply is posted, if the edit was non-trivial or could plausibly break compilation
   (see "Keeping replies fast"), run the recompile check — fine to do this in the background so it
   doesn't block re-arming the poller:
   ```bash
   ~/.claude/scripts/icr/icr_call.sh refresh_projects '{"projects":["net.bluemind.foo"]}'
   ```
   derived from the first segment of `filePath`, then inspect `get_problems`. If it surfaces a
   compile error, fix it and post a short follow-up reply on the same thread.
5. Relaunch the poller (Step 3) right after posting the reply — don't wait on the recompile.

### Step 5 — Stop

When the user says they are done, or the poller returned `{"status":"inactive"}`:

```bash
~/.claude/scripts/icr/icr_call.sh icr_stop
```

This ends the session and disables the menu. Existing `💬` threads stay visible in open editors.

## Keeping replies fast (default: fast mode)

Replies feel slow in Eclipse not because of long polling (that delivers a thread the instant it's
posted) but because of the work done *after* the thread arrives: subagent spawn, source
verification, Eclipse recompiles, and multi-paragraph reply generation. Handling threads directly
(Step 4) removes the subagent-spawn cost; posting the reply before recompiling removes the
recompile from the critical path.

Default to a lean loop optimized for responsiveness:

- **Keep replies short.** A few sentences, not essays. This is the single biggest lever on latency.
  Expand only when the user asks for detail.
- **For questions, answer directly** from the code you can already see. Skip `ack`/cross-file
  verification unless the answer is non-obvious or you'd otherwise be guessing.
- **For change requests, edit then reply immediately.** Only recompile (`refresh_projects` +
  `get_problems`) afterward, and only when the edit is non-trivial or could plausibly break
  compilation. Skip the recompile entirely for trivial edits (comments, renames within a scope,
  string/message tweaks) and say so.
- Don't over-explain the tooling or narrate steps — just do the work and reply.

If the user asks for thoroughness (verify before answering, always recompile, detailed rationale),
switch back to the careful mode for the rest of the session.
