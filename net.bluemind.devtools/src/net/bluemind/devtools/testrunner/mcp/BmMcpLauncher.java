package net.bluemind.devtools.testrunner.mcp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElement.Result;
import org.eclipse.jdt.junit.model.ITestRunSession;

import net.bluemind.devtools.testrunner.BmTestLaunchShortcut;

public final class BmMcpLauncher {

	private static final ILog LOG = Platform.getLog(BmMcpLauncher.class);
	private static final BmMcpLauncher INSTANCE = new BmMcpLauncher();

	/**
	 * Cheap, best-effort signatures scanned in every stdout/stderr chunk as it streams
	 * in. Not a parser — a chunk boundary can split one of these strings and miss it —
	 * but good enough to turn "a run that's spamming warnings" into a countable signal
	 * exposed by {@link #statusSnapshot()}, without touching the run itself.
	 */
	private static final List<String> TROUBLE_SIGNATURES = List.of("has been blocked for", "BlockedThreadChecker",
			"OutOfMemoryError", "Address already in use", "Connection refused", "Deadlock");

	/**
	 * Testcontainers logs each container's lifecycle at INFO through a {@code tc.<image>}
	 * logger — confirmed against a real run's console (elasticsearch-tests, 2026-09-02):
	 * "Creating container for image: X" immediately followed, once the container is up
	 * (observed 9-12s later for elasticsearch-tests), by "Container X started in ...".
	 * Nothing is logged in between for a container with no INFO-level wait strategy, so the
	 * gap between the two lines *is* the wait — same best-effort, chunk-boundary-sensitive
	 * scanning as {@link #TROUBLE_SIGNATURES}, not a real Docker state query.
	 */
	private static final Pattern CONTAINER_CREATING = Pattern.compile("Creating container for image: (\\S+)");
	private static final Pattern CONTAINER_STARTED = Pattern.compile("Container (\\S+) started in");

	/**
	 * Single-slot pending run. Callers serialize tool calls at the MCP server
	 * layer, so at most one {@link Pending} is active at any time; this removes the
	 * need to correlate {@link ITestRunSession} events back to a launch (the
	 * public JDT model API does not expose the underlying ILaunch).
	 */
	private volatile Pending active;
	private volatile boolean listenersInstalled;

	public static BmMcpLauncher instance() {
		return INSTANCE;
	}

	public synchronized void ensureStarted() {
		if (listenersInstalled) {
			return;
		}
		TestRunListener testListener = new TestRunListener() {
			@Override
			public void testCaseStarted(ITestCaseElement el) {
				Pending p = active;
				if (p == null) {
					return;
				}
				p.currentTest = testSlug(el);
				long now = System.currentTimeMillis();
				p.lastActivityAt.set(now);
				p.lastTestEventAt.set(now);
			}

			@Override
			public void testCaseFinished(ITestCaseElement el) {
				Pending p = active;
				if (p == null) {
					return;
				}
				long now = System.currentTimeMillis();
				p.lastActivityAt.set(now);
				p.lastTestEventAt.set(now);
				p.total.incrementAndGet();
				Result r = el.getTestResult(false);
				if (r == Result.OK) {
					p.passed.incrementAndGet();
				} else if (r == Result.FAILURE) {
					p.failed.incrementAndGet();
					p.failures.add(toFailure(el, false));
				} else if (r == Result.ERROR) {
					p.errored.incrementAndGet();
					p.failures.add(toFailure(el, true));
				} else if (r == Result.IGNORED) {
					p.ignored.incrementAndGet();
				}
			}

			@Override
			public void sessionFinished(ITestRunSession session) {
				Pending p = active;
				if (p != null) {
					complete(p);
				}
			}
		};
		JUnitCore.addTestRunListener(testListener);

		ILaunchesListener2 launchListener = new ILaunchesListener2() {
			@Override
			public void launchesAdded(ILaunch[] launches) {
				for (ILaunch l : launches) {
					attachStreams(l);
				}
			}

			@Override
			public void launchesChanged(ILaunch[] launches) {
				for (ILaunch l : launches) {
					attachStreams(l);
				}
			}

			@Override
			public void launchesRemoved(ILaunch[] launches) {
			}

			@Override
			public void launchesTerminated(ILaunch[] launches) {
			}
		};
		DebugPlugin.getDefault().getLaunchManager().addLaunchListener(launchListener);

		listenersInstalled = true;
	}

	public CompletableFuture<TestRunResult> runProject(IJavaProject project, String mode, long timeoutMs) {
		ensureStarted();
		Pending p = beginRun(project.getElementName());
		try {
			BmTestLaunchShortcut.launchProject(project, mode);
		} catch (RuntimeException e) {
			failAndClear(p, e);
		}
		return withTimeout(p, timeoutMs);
	}

	public CompletableFuture<TestRunResult> runType(IType type, String mode, long timeoutMs) throws CoreException {
		return runTypeOrMethod(type, null, mode, timeoutMs);
	}

	public CompletableFuture<TestRunResult> runMethod(IMethod method, String mode, long timeoutMs) throws CoreException {
		return runTypeOrMethod(method.getDeclaringType(), method.getElementName(), mode, timeoutMs);
	}

	private CompletableFuture<TestRunResult> runTypeOrMethod(IType type, String methodName, String mode,
			long timeoutMs) {
		ensureStarted();
		String slug = type.getElementName() + (methodName != null ? "#" + methodName : "");
		Pending p = beginRun(slug);
		try {
			BmTestLaunchShortcut.launchElement(type, methodName, mode);
		} catch (RuntimeException e) {
			failAndClear(p, e);
		}
		return withTimeout(p, timeoutMs);
	}

	private synchronized Pending beginRun(String slug) {
		if (active != null && !active.future.isDone()) {
			throw new IllegalStateException("Another MCP-triggered test run is already active. "
					+ "Tool calls are serialized; wait for the previous run to finish.");
		}
		Pending p;
		try {
			Path dir = BmMcpRunStore.allocate(slug);
			p = new Pending(slug, dir);
		} catch (IOException e) {
			throw new IllegalStateException("Could not allocate run directory: " + e.getMessage(), e);
		}
		active = p;
		return p;
	}

	private void failAndClear(Pending p, Throwable e) {
		closeQuiet(p);
		p.future.completeExceptionally(e);
		if (active == p) {
			active = null;
		}
	}

	private CompletableFuture<TestRunResult> withTimeout(Pending p, long timeoutMs) {
		return p.future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((r, err) -> {
			if (err != null) {
				closeQuiet(p);
			}
			if (active == p) {
				active = null;
			}
		});
	}

	/**
	 * Point-in-time snapshot of the active run, or {@code active: false}. Never blocks,
	 * never touches the run itself — pure read, meant to be polled to tell a genuinely
	 * stuck run apart from a slow-but-healthy one without guessing from silence.
	 */
	public Map<String, Object> statusSnapshot() {
		Pending p = active;
		Map<String, Object> m = new LinkedHashMap<>();
		if (p == null) {
			m.put("active", false);
			return m;
		}
		long now = System.currentTimeMillis();
		int total = p.total.get();
		int failed = p.failed.get();
		int errored = p.errored.get();
		m.put("active", true);
		m.put("target", p.slug);
		m.put("startedAt", p.startedAt);
		m.put("elapsedMs", now - p.startedAt);
		m.put("lastActivityAt", p.lastActivityAt.get());
		m.put("sinceLastActivityMs", now - p.lastActivityAt.get());
		m.put("lastTestEventAt", p.lastTestEventAt.get());
		m.put("sinceLastTestEventMs", now - p.lastTestEventAt.get());
		m.put("currentTest", p.currentTest);
		m.put("total", total);
		m.put("passed", p.passed.get());
		m.put("failed", failed);
		m.put("errored", errored);
		m.put("ignored", p.ignored.get());
		// Cheap, non-authoritative hint: several tests in and every single one failing or
		// erroring smells like a broken setup (infra never came up) rather than N unrelated
		// test bugs — worth surfacing, not worth acting on by itself.
		m.put("allFailingSoFar", total >= 3 && failed + errored == total);
		Map<String, Integer> signals = new LinkedHashMap<>();
		p.troubleSignals.forEach((k, v) -> signals.put(k, v.get()));
		m.put("troubleSignals", signals);
		Map<String, Long> waitingOn = new LinkedHashMap<>();
		p.waitingOnContainers.forEach((image, since) -> waitingOn.put(image, now - since));
		m.put("waitingOn", waitingOn);
		m.put("runDir", p.runDir.toString());
		m.put("stdoutTail", tail(p.stdoutFile, 4000));
		m.put("stderrTail", tail(p.stderrFile, 4000));
		return m;
	}

	/**
	 * Forcibly ends the active run: terminates every {@link IProcess} attached to it, then
	 * the underlying {@link ILaunch} as a fallback for a hang that never got that far (a
	 * launch stuck before any process was even attached), then releases the MCP lock.
	 * Idempotent no-op when nothing is active.
	 */
	public synchronized Map<String, Object> cancel() {
		Pending p = active;
		Map<String, Object> m = new LinkedHashMap<>();
		if (p == null) {
			m.put("cancelled", false);
			m.put("reason", "No MCP test run is currently active.");
			return m;
		}
		List<String> terminated = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		for (IProcess proc : p.processes) {
			try {
				if (!proc.isTerminated()) {
					proc.terminate();
					terminated.add(proc.getLabel());
				}
			} catch (DebugException e) {
				errors.add(proc.getLabel() + ": " + e.getMessage());
			}
		}
		ILaunch launch = p.launch;
		if (launch != null) {
			try {
				if (!launch.isTerminated()) {
					launch.terminate();
				}
			} catch (DebugException e) {
				errors.add("launch: " + e.getMessage());
			}
		}
		failAndClear(p, new CancellationException("Cancelled via the cancel_test_run MCP tool."));
		m.put("cancelled", true);
		m.put("target", p.slug);
		m.put("terminatedProcesses", terminated);
		if (!errors.isEmpty()) {
			m.put("errors", errors);
		}
		return m;
	}

	private static String tail(Path file, int maxChars) {
		try {
			if (file == null || !Files.exists(file)) {
				return null;
			}
			String text = Files.readString(file, StandardCharsets.UTF_8);
			return text.length() > maxChars ? text.substring(text.length() - maxChars) : text;
		} catch (IOException e) {
			return null;
		}
	}

	private static String testSlug(ITestCaseElement el) {
		String cls = el.getTestClassName();
		String method = el.getTestMethodName();
		return (cls == null ? "?" : cls) + "#" + (method == null ? "?" : method);
	}

	/**
	 * Correlates an incoming {@link ILaunch} to the waiting {@link Pending} run, if any.
	 *
	 * <p>Used to key off an MCP-request-id attribute stamped on the launch configuration by
	 * {@link BmTestLaunchShortcut#createLaunchConfiguration}. That broke silently: JDT/PDE
	 * launch shortcuts reuse an existing launch configuration for a given target instead of
	 * creating a new one, so {@code createLaunchConfiguration} — and the attribute stamp —
	 * only ever ran on the *first* run of any given class/bundle in the workspace. Every
	 * later run of the same target reused the old configuration carrying the first run's
	 * stale id, which never matched the new {@link Pending}, so this method silently
	 * returned early: no stream capture, and {@link #cancel()}'s {@code launch.terminate()}
	 * fallback silently no-op'd too (confirmed against a real workspace, 2026-09-02).
	 *
	 * <p>Correlating by identity instead: MCP runs are strictly serialized ({@link #beginRun}
	 * refuses a second one while one is active), so the first genuinely new {@link ILaunch}
	 * to appear while a {@link Pending} is waiting for one ({@code p.launch == null}) — one
	 * that did not already exist in {@link Pending#launchesBeforeStart}, the snapshot taken
	 * right as the run began — can safely be assumed to be ours, no attribute read-back
	 * needed. The one edge case this misattributes is a human manually starting an unrelated
	 * launch in the same short window; acceptable since it only affects stream capture and
	 * cancellation, never the JUnit result itself (that comes from the separate, global
	 * {@link TestRunListener}).
	 */
	private void attachStreams(ILaunch launch) {
		if (launch == null) {
			return;
		}
		Pending p = active;
		if (p == null) {
			return;
		}
		if (p.launch == null) {
			// First sighting: the launch object usually appears (launchesAdded) before it has
			// any IProcess yet, so this call's loop below often finds nothing — that's fine,
			// the *next* launchesChanged call for this same, now-locked-on launch re-enters
			// this method and the loop picks up the process once it actually exists.
			if (p.launchesBeforeStart.contains(launch)) {
				return;
			}
			p.launch = launch;
		} else if (p.launch != launch) {
			return;
		}
		for (IProcess proc : launch.getProcesses()) {
			if (!p.processes.add(proc)) {
				continue;
			}
			IStreamsProxy sp = proc.getStreamsProxy();
			if (sp == null) {
				continue;
			}
			IStreamMonitor out = sp.getOutputStreamMonitor();
			IStreamMonitor err = sp.getErrorStreamMonitor();
			if (out != null) {
				String initial = out.getContents();
				if (initial != null && !initial.isEmpty()) {
					appendStdout(p, initial);
				}
				out.addListener((text, monitor) -> appendStdout(p, text));
			}
			if (err != null) {
				String initial = err.getContents();
				if (initial != null && !initial.isEmpty()) {
					appendStderr(p, initial);
				}
				err.addListener((text, monitor) -> appendStderr(p, text));
			}
		}
	}

	private void appendStdout(Pending p, String text) {
		touchActivity(p, text);
		writeStream(p.stdoutWriter, p.stdoutBytes, text);
	}

	private void appendStderr(Pending p, String text) {
		touchActivity(p, text);
		writeStream(p.stderrWriter, p.stderrBytes, text);
	}

	/**
	 * Updates the staleness clock and the trouble-signature/container-wait state
	 * {@link #statusSnapshot()} reports. Deliberately does not touch
	 * {@link Pending#lastTestEventAt} — that clock reflects real JUnit lifecycle events
	 * only, not console noise (see its javadoc).
	 */
	private void touchActivity(Pending p, String text) {
		if (text == null || text.isEmpty()) {
			return;
		}
		p.lastActivityAt.set(System.currentTimeMillis());
		for (String sig : TROUBLE_SIGNATURES) {
			if (text.contains(sig)) {
				p.troubleSignals.computeIfAbsent(sig, k -> new AtomicInteger()).incrementAndGet();
			}
		}
		Matcher creating = CONTAINER_CREATING.matcher(text);
		while (creating.find()) {
			p.waitingOnContainers.putIfAbsent(creating.group(1), System.currentTimeMillis());
		}
		Matcher started = CONTAINER_STARTED.matcher(text);
		while (started.find()) {
			p.waitingOnContainers.remove(started.group(1));
		}
	}

	private void writeStream(BufferedWriter writer, AtomicLong counter, String text) {
		if (writer == null || text == null || text.isEmpty()) {
			return;
		}
		synchronized (writer) {
			try {
				writer.write(text);
				// Flushed on every chunk, not just on close: get_test_run_status reads this
				// file live while the run is still active, so an unflushed buffer would make
				// it lie about what the console actually shows right now.
				writer.flush();
				counter.addAndGet(text.getBytes(StandardCharsets.UTF_8).length);
			} catch (IOException e) {
				LOG.warn("Stream write failed: " + e.getMessage());
			}
		}
	}

	private void complete(Pending p) {
		if (p.future.isDone()) {
			return;
		}
		long dur = System.currentTimeMillis() - p.startedAt;
		int total = p.total.get();
		int passed = p.passed.get();
		int failed = p.failed.get();
		int errored = p.errored.get();
		int ignored = p.ignored.get();
		boolean success = failed == 0 && errored == 0 && total > 0;
		List<TestRunResult.TestFailure> snapshot = List.copyOf(p.failures);
		closeQuiet(p);
		Path failuresFile = writeFailuresFile(p.runDir, snapshot);
		TestRunResult result = new TestRunResult(success, total, passed, failed, errored, ignored, dur,
				snapshot, p.runDir, p.stdoutFile, p.stderrFile, failuresFile,
				p.stdoutBytes.get(), p.stderrBytes.get());
		p.future.complete(result);
		if (active == p) {
			active = null;
		}
	}

	private Path writeFailuresFile(Path runDir, List<TestRunResult.TestFailure> failures) {
		if (failures.isEmpty()) {
			return null;
		}
		Path target = runDir.resolve("failures.md");
		StringBuilder sb = new StringBuilder();
		for (var f : failures) {
			sb.append("## ").append(f.error() ? "[ERROR] " : "[FAIL] ")
					.append(f.className() == null ? "?" : f.className()).append("#")
					.append(f.methodName() == null ? "?" : f.methodName()).append("\n\n");
			sb.append("```\n");
			sb.append(f.trace() == null ? "(no trace)" : f.trace().trim());
			sb.append("\n```\n\n");
		}
		try {
			Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
			return target;
		} catch (IOException e) {
			LOG.warn("Could not write failures.md: " + e.getMessage());
			return null;
		}
	}

	private static TestRunResult.TestFailure toFailure(ITestElement el, boolean error) {
		String cls = null;
		String method = null;
		if (el instanceof ITestCaseElement tc) {
			cls = tc.getTestClassName();
			method = tc.getTestMethodName();
		}
		String trace = null;
		try {
			if (el.getFailureTrace() != null) {
				trace = el.getFailureTrace().getTrace();
			}
		} catch (Exception e) {
			LOG.warn("Could not read failure trace: " + e.getMessage());
		}
		return new TestRunResult.TestFailure(cls, method, error, trace);
	}

	private static void closeQuiet(Pending p) {
		close(p.stdoutWriter);
		close(p.stderrWriter);
	}

	private static void close(BufferedWriter w) {
		if (w == null) {
			return;
		}
		synchronized (w) {
			try {
				w.flush();
				w.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static final class Pending {
		final String slug;
		final Path runDir;
		final Path stdoutFile;
		final Path stderrFile;
		final BufferedWriter stdoutWriter;
		final BufferedWriter stderrWriter;
		final AtomicLong stdoutBytes = new AtomicLong();
		final AtomicLong stderrBytes = new AtomicLong();
		final long startedAt = System.currentTimeMillis();
		final AtomicLong lastActivityAt = new AtomicLong(startedAt);
		/**
		 * Set only by {@code testCaseStarted}/{@code testCaseFinished} — real JUnit
		 * progress, unlike {@link #lastActivityAt} which any console line (including
		 * trouble-signature noise) also resets. The signal to trust when deciding whether
		 * a run is genuinely stuck.
		 */
		final AtomicLong lastTestEventAt = new AtomicLong(startedAt);
		final CompletableFuture<TestRunResult> future = new CompletableFuture<>();
		final AtomicInteger total = new AtomicInteger();
		final AtomicInteger passed = new AtomicInteger();
		final AtomicInteger failed = new AtomicInteger();
		final AtomicInteger errored = new AtomicInteger();
		final AtomicInteger ignored = new AtomicInteger();
		final List<TestRunResult.TestFailure> failures = Collections.synchronizedList(new ArrayList<>());
		final Set<IProcess> processes = Collections.newSetFromMap(new ConcurrentHashMap<>());
		final Map<String, AtomicInteger> troubleSignals = new ConcurrentHashMap<>();
		/** Image -> creation-started-at, for images currently between "Creating container" and "started". */
		final Map<String, Long> waitingOnContainers = new ConcurrentHashMap<>();
		/**
		 * Snapshot of every {@link ILaunch} that already existed the instant this run began.
		 * {@link #attachStreams} treats the first launch NOT in this set as ours — see its
		 * javadoc for why matching by launch-configuration attribute doesn't work.
		 */
		final Set<ILaunch> launchesBeforeStart = new HashSet<>(
				Arrays.asList(DebugPlugin.getDefault().getLaunchManager().getLaunches()));
		volatile String currentTest;
		volatile ILaunch launch;

		Pending(String slug, Path runDir) {
			this.slug = slug;
			this.runDir = runDir;
			this.stdoutFile = runDir.resolve("stdout.log");
			this.stderrFile = runDir.resolve("stderr.log");
			try {
				this.stdoutWriter = Files.newBufferedWriter(stdoutFile, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				this.stderrWriter = Files.newBufferedWriter(stderrFile, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			} catch (IOException e) {
				throw new IllegalStateException("Could not open stream files in " + runDir, e);
			}
		}
	}
}
