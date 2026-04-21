package net.bluemind.devtools.testrunner.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
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
			public void testCaseFinished(ITestCaseElement el) {
				Pending p = active;
				if (p == null) {
					return;
				}
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
		Pending p = beginRun();
		try {
			BmTestLaunchShortcut.launchProject(project, mode, p.id);
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
		Pending p = beginRun();
		try {
			BmTestLaunchShortcut.launchElement(type, methodName, mode, p.id);
		} catch (RuntimeException e) {
			failAndClear(p, e);
		}
		return withTimeout(p, timeoutMs);
	}

	private synchronized Pending beginRun() {
		if (active != null && !active.future.isDone()) {
			throw new IllegalStateException("Another MCP-triggered test run is already active. "
					+ "Tool calls are serialized; wait for the previous run to finish.");
		}
		Pending p = new Pending(UUID.randomUUID().toString());
		active = p;
		return p;
	}

	private void failAndClear(Pending p, Throwable e) {
		p.future.completeExceptionally(e);
		if (active == p) {
			active = null;
		}
	}

	private CompletableFuture<TestRunResult> withTimeout(Pending p, long timeoutMs) {
		return p.future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((r, err) -> {
			if (active == p) {
				active = null;
			}
		});
	}

	private void attachStreams(ILaunch launch) {
		if (launch == null || launch.getLaunchConfiguration() == null) {
			return;
		}
		String id;
		try {
			id = launch.getLaunchConfiguration().getAttribute(BmTestLaunchShortcut.MCP_REQUEST_ID_ATTR, (String) null);
		} catch (CoreException e) {
			return;
		}
		if (id == null) {
			return;
		}
		Pending p = active;
		if (p == null || !p.id.equals(id)) {
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
		synchronized (p.stdout) {
			int room = MAX_STREAM_BYTES - p.stdout.length();
			if (room <= 0) {
				return;
			}
			p.stdout.append(text, 0, Math.min(text.length(), room));
		}
	}

	private void appendStderr(Pending p, String text) {
		synchronized (p.stderr) {
			int room = MAX_STREAM_BYTES - p.stderr.length();
			if (room <= 0) {
				return;
			}
			p.stderr.append(text, 0, Math.min(text.length(), room));
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
		TestRunResult result;
		synchronized (p.stdout) {
			synchronized (p.stderr) {
				result = new TestRunResult(success, total, passed, failed, errored, ignored, dur,
						List.copyOf(p.failures), p.stdout.toString(), p.stderr.toString());
			}
		}
		p.future.complete(result);
		if (active == p) {
			active = null;
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

	private static final int MAX_STREAM_BYTES = 256 * 1024;

	private static final class Pending {
		final String id;
		final long startedAt = System.currentTimeMillis();
		final CompletableFuture<TestRunResult> future = new CompletableFuture<>();
		final AtomicInteger total = new AtomicInteger();
		final AtomicInteger passed = new AtomicInteger();
		final AtomicInteger failed = new AtomicInteger();
		final AtomicInteger errored = new AtomicInteger();
		final AtomicInteger ignored = new AtomicInteger();
		final List<TestRunResult.TestFailure> failures = Collections.synchronizedList(new ArrayList<>());
		final StringBuilder stdout = new StringBuilder();
		final StringBuilder stderr = new StringBuilder();
		final Set<IProcess> processes = Collections.newSetFromMap(new ConcurrentHashMap<>());

		Pending(String id) {
			this.id = id;
		}
	}
}
