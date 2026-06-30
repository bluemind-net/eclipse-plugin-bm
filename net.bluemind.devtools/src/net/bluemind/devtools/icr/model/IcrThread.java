package net.bluemind.devtools.icr.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jface.text.Position;

/**
 * A single review thread anchored to a code selection: the user's original
 * comment/question plus the conversation that follows.
 *
 * <p>
 * Mirrors the "remark" shape of the legacy web reviewer. A thread "needs
 * attention" from Claude when it is still {@link Status#PENDING} or when the
 * last message in the conversation came from the user.
 */
public final class IcrThread {

	public enum Status {
		PENDING, ANSWERED
	}

	private final String id;
	/** Workspace-relative path, e.g. {@code /net.bluemind.foo/src/Foo.java}. */
	private final String workspacePath;
	/** Absolute filesystem path, for tools that read the file directly. */
	private final String absolutePath;
	private final String selectedText;
	private final String body;
	private final long createdAt;

	/** Snapshot line numbers (1-based) captured when the thread was created. */
	private final int startLine;
	private final int endLine;

	/**
	 * Live anchor in the editor document, kept up to date by the document's
	 * default position updater as the file is edited. May be {@code null} when no
	 * editor is open on the file. The code-mining provider prefers this over the
	 * snapshot {@link #startLine}.
	 */
	private volatile Position anchor;

	private volatile Status status = Status.PENDING;

	/**
	 * True between the moment Claude picks this thread up via {@code icr_next} and
	 * the moment it posts its answer — i.e. while Claude is reading the code and
	 * preparing the reply/changes. Display-only; drives the "Claude is working…"
	 * hint in the inline summary and the thread box.
	 */
	private volatile boolean working;

	private final List<IcrReply> replies = new ArrayList<>();

	public IcrThread(String id, String workspacePath, String absolutePath, String selectedText, String body,
			int startLine, int endLine, long createdAt) {
		this.id = id;
		this.workspacePath = workspacePath;
		this.absolutePath = absolutePath;
		this.selectedText = selectedText;
		this.body = body;
		this.startLine = startLine;
		this.endLine = endLine;
		this.createdAt = createdAt;
	}

	public String id() {
		return id;
	}

	public String workspacePath() {
		return workspacePath;
	}

	public String absolutePath() {
		return absolutePath;
	}

	public String selectedText() {
		return selectedText;
	}

	public String body() {
		return body;
	}

	public int startLine() {
		return startLine;
	}

	public int endLine() {
		return endLine;
	}

	public long createdAt() {
		return createdAt;
	}

	public Position anchor() {
		return anchor;
	}

	public void setAnchor(Position anchor) {
		this.anchor = anchor;
	}

	public Status status() {
		return status;
	}

	void setStatus(Status status) {
		this.status = status;
	}

	/** True while Claude is actively preparing an answer for this thread. */
	public boolean working() {
		return working;
	}

	void setWorking(boolean working) {
		this.working = working;
	}

	/** Live, unmodifiable view of the conversation. */
	public synchronized List<IcrReply> replies() {
		return Collections.unmodifiableList(new ArrayList<>(replies));
	}

	synchronized void addReply(IcrReply reply) {
		replies.add(reply);
	}

	/** True when Claude still owes the user an answer on this thread. */
	public synchronized boolean needsAttention() {
		if (replies.isEmpty()) {
			return status == Status.PENDING;
		}
		return IcrReply.AUTHOR_USER.equals(replies.get(replies.size() - 1).author());
	}
}
