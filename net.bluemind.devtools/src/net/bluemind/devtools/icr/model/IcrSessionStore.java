package net.bluemind.devtools.icr.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-memory store of the current Interactive Code Review (ICR) session and its
 * threads. Mutated from two sides:
 * <ul>
 * <li>the SWT UI thread (user creates a thread or posts a reply), and</li>
 * <li>MCP server worker threads (Claude posts a reply, long-polls for work).</li>
 * </ul>
 * so all mutators are synchronised. New user activity is funnelled into a
 * blocking {@code attention} queue that the {@code icr_next} tool drains —
 * Claude is woken exactly when there is something to act on, never busy-polls.
 */
public final class IcrSessionStore {

	/** Listener notified (on any thread) whenever the model changes. */
	public interface Listener {
		void icrChanged();
	}

	private static final IcrSessionStore INSTANCE = new IcrSessionStore();

	private final Map<String, IcrThread> threads = new LinkedHashMap<>();
	private final LinkedBlockingQueue<String> attention = new LinkedBlockingQueue<>();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();

	private volatile boolean active;
	private volatile String source;
	private volatile String repo;

	private IcrSessionStore() {
	}

	public static IcrSessionStore instance() {
		return INSTANCE;
	}

	// --- session lifecycle -------------------------------------------------

	public synchronized void startSession(String source, String repo) {
		this.active = true;
		this.source = source;
		this.repo = repo;
		// Resume: re-queue any thread still awaiting an answer so a freshly
		// (re)connected /icr skill catches up via icr_next.
		attention.clear();
		for (IcrThread t : threads.values()) {
			// A freshly (re)connected skill is not yet working on anything; the
			// "working" hint is re-armed when it picks a thread up via icr_next.
			t.setWorking(false);
			if (t.needsAttention()) {
				attention.offer(t.id());
			}
		}
		fireChanged();
	}

	public synchronized void endSession() {
		this.active = false;
		attention.clear();
		for (IcrThread t : threads.values()) {
			t.setWorking(false);
		}
		fireChanged();
	}

	public boolean isActive() {
		return active;
	}

	public String source() {
		return source;
	}

	public String repo() {
		return repo;
	}

	// --- threads -----------------------------------------------------------

	public IcrThread addThread(String workspacePath, String absolutePath, String selectedText, String body,
			int startLine, int endLine) {
		IcrThread thread = new IcrThread(UUID.randomUUID().toString(), workspacePath, absolutePath, selectedText,
				body, startLine, endLine, System.currentTimeMillis());
		synchronized (this) {
			threads.put(thread.id(), thread);
		}
		attention.offer(thread.id());
		fireChanged();
		return thread;
	}

	public synchronized IcrThread findThread(String id) {
		return threads.get(id);
	}

	public synchronized List<IcrThread> listThreads() {
		return new ArrayList<>(threads.values());
	}

	/** Threads anchored in the given workspace-relative file path. */
	public synchronized List<IcrThread> threadsForFile(String workspacePath) {
		List<IcrThread> out = new ArrayList<>();
		for (IcrThread t : threads.values()) {
			if (t.workspacePath().equals(workspacePath)) {
				out.add(t);
			}
		}
		return out;
	}

	/**
	 * Removes a thread from the session. The inline summary disappears once open
	 * editors recompute their code minings. Returns the removed thread, or
	 * {@code null} if it was unknown.
	 */
	public IcrThread removeThread(String id) {
		IcrThread removed;
		synchronized (this) {
			removed = threads.remove(id);
		}
		if (removed != null) {
			fireChanged();
		}
		return removed;
	}

	/**
	 * Appends a reply. A user reply re-queues the thread for Claude; a Claude reply
	 * marks the thread answered. Returns {@code null} if the thread is unknown.
	 */
	public IcrThread addReply(String threadId, String author, String body) {
		IcrThread thread;
		synchronized (this) {
			thread = threads.get(threadId);
			if (thread == null) {
				return null;
			}
			thread.addReply(new IcrReply(UUID.randomUUID().toString(), author, body, System.currentTimeMillis()));
			// Either Claude just answered (done working) or the user added fresh input
			// Claude has not yet picked up — both clear the "working" hint until the
			// next icr_next pickup re-arms it.
			thread.setWorking(false);
			if (IcrReply.AUTHOR_CLAUDE.equals(author)) {
				thread.setStatus(IcrThread.Status.ANSWERED);
			} else {
				thread.setStatus(IcrThread.Status.PENDING);
			}
		}
		if (IcrReply.AUTHOR_USER.equals(author)) {
			attention.offer(threadId);
		}
		fireChanged();
		return thread;
	}

	// --- long-poll ---------------------------------------------------------

	/**
	 * Blocks up to {@code timeoutMs} for the next thread needing Claude's
	 * attention. Returns {@code null} on timeout so the caller can loop. Stale
	 * queue entries (e.g. a thread answered out of band) are skipped.
	 */
	public IcrThread awaitAttention(long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		for (;;) {
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				return null;
			}
			String id = attention.poll(remaining, TimeUnit.MILLISECONDS);
			if (id == null) {
				return null;
			}
			IcrThread thread = findThread(id);
			if (thread != null && thread.needsAttention()) {
				// Claude has the thread now and will read/edit/reply: surface the
				// "Claude is working…" hint inline and in the box until it answers.
				thread.setWorking(true);
				fireChanged();
				return thread;
			}
		}
	}

	// --- listeners ---------------------------------------------------------

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	private void fireChanged() {
		for (Listener l : listeners) {
			try {
				l.icrChanged();
			} catch (RuntimeException ignored) {
				// a misbehaving listener must not break the model
			}
		}
	}

	/** Drops all threads and clears the session (used on Activator shutdown). */
	public synchronized void dispose() {
		active = false;
		threads.clear();
		attention.clear();
		Collection<Listener> snapshot = new ArrayList<>(listeners);
		listeners.clear();
		for (Listener l : snapshot) {
			try {
				l.icrChanged();
			} catch (RuntimeException ignored) {
			}
		}
	}
}
