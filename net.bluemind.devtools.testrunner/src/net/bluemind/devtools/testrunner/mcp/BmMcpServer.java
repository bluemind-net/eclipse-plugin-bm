package net.bluemind.devtools.testrunner.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public final class BmMcpServer {

	private static final ILog LOG = Platform.getLog(BmMcpServer.class);
	private static final String PROTOCOL_VERSION = "2025-03-26";
	private static final String SERVER_NAME = "bm-eclipse-testrunner";
	private static final long DEFAULT_TEST_TIMEOUT_MS = 30 * 60 * 1000L;

	private HttpServer server;
	private String token;
	private int port;
	private Executor executor;

	public synchronized void start() throws IOException {
		if (server != null) {
			return;
		}
		this.token = generateToken();
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.port = server.getAddress().getPort();
		this.executor = Executors.newCachedThreadPool(daemonFactory());
		server.setExecutor(executor);
		server.createContext("/mcp", new McpHandler());
		server.createContext("/health", ex -> {
			byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/plain");
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(body);
			}
		});
		server.start();
		BmMcpLauncher.instance().ensureStarted();
		BmMcpRunStore.prune();
		LOG.info("BM MCP server listening on http://127.0.0.1:" + port + "/mcp");
	}

	public synchronized void stop() {
		if (server == null) {
			return;
		}
		try {
			server.stop(0);
		} catch (RuntimeException e) {
			LOG.warn("Error stopping MCP server: " + e.getMessage());
		}
		server = null;
		token = null;
		port = 0;
	}

	public boolean isRunning() {
		return server != null;
	}

	public int port() {
		return port;
	}

	public String token() {
		return token;
	}

	public String url() {
		return "http://127.0.0.1:" + port + "/mcp";
	}

	private final class McpHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange ex) throws IOException {
			try {
				if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
					respondEmpty(ex, 405);
					return;
				}
				if (!checkAuth(ex)) {
					respondEmpty(ex, 401);
					return;
				}
				byte[] raw = ex.getRequestBody().readAllBytes();
				String body = new String(raw, StandardCharsets.UTF_8);
				Object parsed;
				try {
					parsed = McpJson.parse(body);
				} catch (RuntimeException e) {
					respondJson(ex, 400, parseError(null, "Parse error: " + e.getMessage()));
					return;
				}
				if (parsed instanceof List<?> batch) {
					List<Object> responses = new java.util.ArrayList<>();
					for (Object item : batch) {
						if (item instanceof Map<?, ?> m) {
							Map<String, Object> resp = handleRequest(asStringMap(m));
							if (resp != null) {
								responses.add(resp);
							}
						}
					}
					if (responses.isEmpty()) {
						respondEmpty(ex, 202);
					} else {
						respondJson(ex, 200, responses);
					}
				} else if (parsed instanceof Map<?, ?> m) {
					Map<String, Object> resp = handleRequest(asStringMap(m));
					if (resp == null) {
						respondEmpty(ex, 202);
					} else {
						respondJson(ex, 200, resp);
					}
				} else {
					respondJson(ex, 400, parseError(null, "Invalid JSON-RPC payload"));
				}
			} catch (Throwable t) {
				LOG.error("MCP handler crash: " + t.getMessage(), t);
				try {
					respondJson(ex, 500, parseError(null, "Internal error: " + t.getMessage()));
				} catch (IOException ignored) {
				}
			} finally {
				ex.close();
			}
		}

		private boolean checkAuth(HttpExchange ex) {
			List<String> auths = ex.getRequestHeaders().get("Authorization");
			if (auths == null || auths.isEmpty()) {
				return false;
			}
			String expected = "Bearer " + token;
			for (String a : auths) {
				if (constantTimeEquals(a, expected)) {
					return true;
				}
			}
			return false;
		}
	}

	private Map<String, Object> handleRequest(Map<String, Object> req) {
		Object idObj = req.get("id");
		String method = str(req, "method");
		boolean isNotification = !req.containsKey("id") || idObj == null;

		if (method == null) {
			return isNotification ? null : error(idObj, -32600, "Invalid Request");
		}
		try {
			switch (method) {
			case "initialize":
				return isNotification ? null : success(idObj, initializeResult());
			case "notifications/initialized":
			case "notifications/cancelled":
				return null;
			case "ping":
				return isNotification ? null : success(idObj, new LinkedHashMap<>());
			case "tools/list":
				return isNotification ? null : success(idObj, toolsListResult());
			case "tools/call":
				if (isNotification) {
					return null;
				}
				return handleToolsCall(idObj, req);
			default:
				return isNotification ? null : error(idObj, -32601, "Method not found: " + method);
			}
		} catch (RuntimeException e) {
			LOG.warn("Error handling " + method + ": " + e.getMessage());
			return isNotification ? null : error(idObj, -32603, e.getMessage());
		}
	}

	private Map<String, Object> initializeResult() {
		Map<String, Object> tools = new LinkedHashMap<>();
		tools.put("listChanged", false);
		Map<String, Object> caps = new LinkedHashMap<>();
		caps.put("tools", tools);
		Map<String, Object> serverInfo = new LinkedHashMap<>();
		serverInfo.put("name", SERVER_NAME);
		serverInfo.put("version", "1.0.0");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("protocolVersion", PROTOCOL_VERSION);
		result.put("capabilities", caps);
		result.put("serverInfo", serverInfo);
		result.put("instructions", "Triggers JUnit Plugin Tests in the running Eclipse instance. Tools: "
				+ String.join(", ", List.of(BmMcpTools.TOOL_RUN_BUNDLE, BmMcpTools.TOOL_RUN_CLASS,
						BmMcpTools.TOOL_RUN_METHOD))
				+ ".");
		return result;
	}

	private Map<String, Object> toolsListResult() {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("tools", BmMcpTools.descriptors());
		return r;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> handleToolsCall(Object id, Map<String, Object> req) {
		Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());
		String name = str(params, "name");
		if (name == null) {
			return error(id, -32602, "Missing tool name");
		}
		Map<String, Object> args = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

		if (BmMcpTools.TOOL_REFRESH.equals(name)) {
			try {
				List<String> projects = BmMcpTools.asStringList(args.get("projects"));
				if (projects.isEmpty()) {
					return success(id, toolTextResult(
							"Missing or empty 'projects' argument.", true));
				}
				BmMcpTools.RefreshResult rr = BmMcpTools.refreshProjects(projects);
				return success(id, toolTextResult(rr.markdown(), !rr.ok()));
			} catch (Exception e) {
				LOG.error("refresh_projects error: " + e.getMessage(), e);
				return success(id, toolTextResult("Refresh failed: " + e.getMessage(), true));
			}
		}

		try {
			CompletableFuture<TestRunResult> future = BmMcpTools.invoke(name, args, DEFAULT_TEST_TIMEOUT_MS);
			TestRunResult result;
			try {
				result = future.get(DEFAULT_TEST_TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS);
			} catch (TimeoutException te) {
				return success(id, toolTextResult(
						"Test run timed out after " + (DEFAULT_TEST_TIMEOUT_MS / 1000) + "s.", true));
			} catch (CompletionException ce) {
				Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
				return success(id, toolTextResult("Test run failed: " + cause.getMessage(), true));
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return success(id, toolTextResult("Interrupted", true));
			} catch (java.util.concurrent.ExecutionException ee) {
				Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
				return success(id, toolTextResult("Test run failed: " + cause.getMessage(), true));
			}
			String text = formatResult(name, args, result);
			return success(id, toolTextResult(text, !result.success()));
		} catch (IllegalArgumentException e) {
			return success(id, toolTextResult(e.getMessage(), true));
		} catch (Exception e) {
			LOG.error("tools/call error: " + e.getMessage(), e);
			return success(id, toolTextResult("Unexpected error: " + e.getMessage(), true));
		}
	}

	private Map<String, Object> toolTextResult(String text, boolean isError) {
		Map<String, Object> textBlock = new LinkedHashMap<>();
		textBlock.put("type", "text");
		textBlock.put("text", text);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("content", List.of(textBlock));
		if (isError) {
			result.put("isError", true);
		}
		return result;
	}

	private String formatResult(String tool, Map<String, Object> args, TestRunResult r) {
		StringBuilder sb = new StringBuilder();
		String target;
		if (BmMcpTools.TOOL_RUN_METHOD.equals(tool)) {
			target = args.get("className") + "#" + args.get("methodName");
		} else if (BmMcpTools.TOOL_RUN_CLASS.equals(tool)) {
			target = String.valueOf(args.get("className"));
		} else {
			target = String.valueOf(args.get("project"));
		}
		sb.append("# BM Plugin Tests — ").append(target).append(" — ")
				.append(r.success() ? "PASSED" : "FAILED").append("\n\n");
		sb.append("Total: ").append(r.total())
				.append(" | Passed: ").append(r.passed())
				.append(" | Failed: ").append(r.failed())
				.append(" | Errored: ").append(r.errored())
				.append(" | Ignored: ").append(r.ignored()).append("\n");
		sb.append("Duration: ").append(formatDuration(r.durationMs())).append("\n\n");

		if (!r.failures().isEmpty()) {
			sb.append("## Failures (").append(r.failures().size()).append(")\n\n");
			int cap = 50;
			int shown = 0;
			for (var f : r.failures()) {
				if (shown++ >= cap) {
					sb.append("- ... ").append(r.failures().size() - cap)
							.append(" more (see failures.md)\n");
					break;
				}
				sb.append("- ").append(f.error() ? "[ERROR] " : "[FAIL] ")
						.append(f.className() == null ? "?" : f.className()).append("#")
						.append(f.methodName() == null ? "?" : f.methodName()).append("\n");
			}
			sb.append('\n');
		}

		sb.append("## Artifacts\n\n");
		if (r.failuresFile() != null) {
			sb.append("- Failure traces: `").append(r.failuresFile()).append("`\n");
		}
		sb.append("- stdout (").append(BmMcpRunStore.humanSize(r.stdoutBytes())).append("): `")
				.append(r.stdoutFile()).append("`\n");
		sb.append("- stderr (").append(BmMcpRunStore.humanSize(r.stderrBytes())).append("): `")
				.append(r.stderrFile()).append("`\n");
		sb.append("- Run dir: `").append(r.runDir()).append("`\n\n");
		sb.append("To inspect: `tail -n 200 '").append(r.stderrFile()).append("'` or `cat '")
				.append(r.failuresFile() == null ? r.stdoutFile() : r.failuresFile()).append("'`.\n");
		return sb.toString();
	}

	private static String formatDuration(long ms) {
		if (ms < 1000) {
			return ms + "ms";
		}
		return String.format("%.1fs", ms / 1000.0);
	}

	private static Map<String, Object> success(Object id, Map<String, Object> result) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("jsonrpc", "2.0");
		r.put("id", id);
		r.put("result", result);
		return r;
	}

	private static Map<String, Object> error(Object id, int code, String message) {
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("code", code);
		err.put("message", message);
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("jsonrpc", "2.0");
		r.put("id", id);
		r.put("error", err);
		return r;
	}

	private static Map<String, Object> parseError(Object id, String message) {
		return error(id, -32700, message);
	}

	private static void respondJson(HttpExchange ex, int status, Object body) throws IOException {
		byte[] bytes = McpJson.write(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static void respondEmpty(HttpExchange ex, int status) throws IOException {
		ex.sendResponseHeaders(status, -1);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asStringMap(Map<?, ?> m) {
		return (Map<String, Object>) m;
	}

	private static String str(Map<String, Object> m, String key) {
		Object v = m == null ? null : m.get(key);
		return v == null ? null : v.toString();
	}

	private static String generateToken() {
		byte[] buf = new byte[32];
		new SecureRandom().nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null || a.length() != b.length()) {
			return false;
		}
		int diff = 0;
		for (int i = 0; i < a.length(); i++) {
			diff |= a.charAt(i) ^ b.charAt(i);
		}
		return diff == 0;
	}

	private static ThreadFactory daemonFactory() {
		AtomicInteger counter = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, "bm-mcp-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}
}
