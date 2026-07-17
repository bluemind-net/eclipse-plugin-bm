package net.bluemind.devtools.icr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.bluemind.devtools.icr.model.IcrReply;
import net.bluemind.devtools.icr.model.IcrSessionStore;
import net.bluemind.devtools.icr.model.IcrThread;
import net.bluemind.devtools.testrunner.mcp.BmMcpTools.ToolResult;
import net.bluemind.devtools.testrunner.mcp.McpJson;

/**
 * MCP tools backing the Interactive Code Review (ICR) feature. Claude (the
 * {@code /icr} skill) is the MCP client; it long-polls {@link #TOOL_NEXT} for
 * new user comments/replies, acts on them, then posts answers via
 * {@link #TOOL_REPLY}.
 *
 * <p>
 * Every tool returns its payload as a JSON document in the tool text content so
 * the skill can parse it directly with {@code jq}.
 */
public final class IcrMcpTools {

	public static final String TOOL_START = "icr_start";
	public static final String TOOL_LIST = "icr_list";
	public static final String TOOL_NEXT = "icr_next";
	public static final String TOOL_REPLY = "icr_reply";
	public static final String TOOL_STOP = "icr_stop";

	/** How long {@code icr_next} blocks before returning an idle response. */
	private static final long NEXT_POLL_MS = 25_000L;

	private IcrMcpTools() {
	}

	public static List<String> toolNames() {
		return List.of(TOOL_START, TOOL_LIST, TOOL_NEXT, TOOL_REPLY, TOOL_STOP);
	}

	public static boolean isIcrTool(String name) {
		return toolNames().contains(name);
	}

	public static List<Map<String, Object>> descriptors() {
		List<Map<String, Object>> tools = new ArrayList<>();
		tools.add(descriptor(TOOL_START,
				"Start (or resume) an interactive code review session. Enables the 'Ask Claude (ICR)'"
						+ " context menu in the Eclipse editor and re-queues any thread still awaiting an"
						+ " answer. Call this once at the beginning of the /icr skill. Returns the current"
						+ " session state as JSON.",
				Map.of("source", paramString("Optional source branch the review is framed against, e.g. master."),
						"repo", paramString("Optional absolute path of the repository under review.")),
				List.of()));
		tools.add(descriptor(TOOL_LIST,
				"List every review thread of the current session as JSON (id, file, line range, selected"
						+ " text, body, full reply conversation, status). Use to catch up after reconnecting.",
				Map.of(), List.of()));
		tools.add(descriptor(TOOL_NEXT,
				"Long-poll for the next review thread needing your attention (a new user comment, or a"
						+ " thread whose last message came from the user). Blocks up to ~25s then returns"
						+ " {\"status\":\"idle\"} so you can loop. When a thread is ready it returns"
						+ " {\"status\":\"thread\",\"thread\":{...}} with the file path, line range, selected"
						+ " text and conversation. Read the file, make any edits, then reply via icr_reply.",
				Map.of(), List.of()));
		tools.add(descriptor(TOOL_REPLY,
				"Post your answer to a review thread. Appends a 'claude' reply (markdown is rendered in the"
						+ " Eclipse popup) and marks the thread answered. Call this after handling a thread"
						+ " returned by icr_next.",
				Map.of("threadId", paramString("Thread id, from icr_next / icr_list."),
						"body", paramString("Your reply, in markdown.")),
				List.of("threadId", "body")));
		tools.add(descriptor(TOOL_STOP,
				"End the current review session (disables the 'Ask Claude (ICR)' menu). Call when the user"
						+ " says they are done. Existing threads remain visible in open editors.",
				Map.of(), List.of()));
		return tools;
	}

	public static ToolResult invoke(String tool, Map<String, Object> args) {
		IcrSessionStore store = IcrSessionStore.instance();
		switch (tool) {
		case TOOL_START:
			return start(store, str(args, "source"), str(args, "repo"));
		case TOOL_LIST:
			return list(store);
		case TOOL_NEXT:
			return next(store);
		case TOOL_REPLY:
			return reply(store, str(args, "threadId"), str(args, "body"));
		case TOOL_STOP:
			return stop(store);
		default:
			throw new IllegalArgumentException("Unknown ICR tool: " + tool);
		}
	}

	private static ToolResult start(IcrSessionStore store, String source, String repo) {
		store.startSession(source, repo);
		Map<String, Object> payload = sessionPayload(store);
		payload.put("ok", true);
		return json(true, payload);
	}

	private static ToolResult list(IcrSessionStore store) {
		return json(true, sessionPayload(store));
	}

	private static ToolResult next(IcrSessionStore store) {
		if (!store.isActive()) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("status", "inactive");
			p.put("message", "No active ICR session. Call icr_start first.");
			return json(false, p);
		}
		IcrThread thread;
		try {
			thread = store.awaitAttention(NEXT_POLL_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("status", "idle");
			return json(true, p);
		}
		Map<String, Object> p = new LinkedHashMap<>();
		if (thread == null) {
			p.put("status", "idle");
		} else {
			p.put("status", "thread");
			p.put("thread", threadPayload(thread));
		}
		return json(true, p);
	}

	private static ToolResult reply(IcrSessionStore store, String threadId, String body) {
		if (threadId == null || threadId.isBlank()) {
			return error("Missing required argument: threadId");
		}
		if (body == null || body.isBlank()) {
			return error("Missing required argument: body");
		}
		IcrThread thread = store.addReply(threadId, IcrReply.AUTHOR_CLAUDE, body);
		if (thread == null) {
			return error("Unknown threadId: " + threadId);
		}
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("ok", true);
		p.put("thread", threadPayload(thread));
		return json(true, p);
	}

	private static ToolResult stop(IcrSessionStore store) {
		store.endSession();
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("ok", true);
		p.put("active", false);
		return json(true, p);
	}

	// --- payloads ----------------------------------------------------------

	private static Map<String, Object> sessionPayload(IcrSessionStore store) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("active", store.isActive());
		p.put("source", store.source());
		p.put("repo", store.repo());
		List<Map<String, Object>> threads = new ArrayList<>();
		int pending = 0;
		for (IcrThread t : store.listThreads()) {
			threads.add(threadPayload(t));
			if (t.needsAttention()) {
				pending++;
			}
		}
		p.put("threads", threads);
		p.put("pendingAttention", pending);
		return p;
	}

	private static Map<String, Object> threadPayload(IcrThread t) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("id", t.id());
		p.put("filePath", t.workspacePath());
		p.put("absolutePath", t.absolutePath());
		p.put("startLine", t.startLine());
		p.put("endLine", t.endLine());
		p.put("selectedText", t.selectedText());
		p.put("body", t.body());
		p.put("status", t.status().name().toLowerCase());
		p.put("createdAt", t.createdAt());
		List<Map<String, Object>> replies = new ArrayList<>();
		for (IcrReply r : t.replies()) {
			Map<String, Object> rp = new LinkedHashMap<>();
			rp.put("id", r.id());
			rp.put("author", r.author());
			rp.put("body", r.body());
			rp.put("createdAt", r.createdAt());
			replies.add(rp);
		}
		p.put("replies", replies);
		return p;
	}

	private static ToolResult json(boolean ok, Object payload) {
		return new ToolResult(ok, McpJson.write(payload));
	}

	private static ToolResult error(String message) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("ok", false);
		p.put("error", message);
		return new ToolResult(false, McpJson.write(p));
	}

	// --- descriptor helpers (mirror BmMcpTools) ----------------------------

	private static Map<String, Object> descriptor(String name, String description,
			Map<String, Map<String, Object>> properties, List<String> required) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", required);
		Map<String, Object> tool = new LinkedHashMap<>();
		tool.put("name", name);
		tool.put("description", description);
		tool.put("inputSchema", schema);
		return tool;
	}

	private static Map<String, Object> paramString(String desc) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "string");
		m.put("description", desc);
		return m;
	}

	private static String str(Map<String, Object> args, String key) {
		Object v = args == null ? null : args.get(key);
		return v == null ? null : v.toString();
	}
}
