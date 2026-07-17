package net.bluemind.devtools.icr.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.jface.text.codemining.LineHeaderCodeMining;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import net.bluemind.devtools.Activator;
import net.bluemind.devtools.icr.model.IcrReply;
import net.bluemind.devtools.icr.model.IcrSessionStore;
import net.bluemind.devtools.icr.model.IcrThread;

/**
 * Renders one compact summary line per ICR thread above the anchored code:
 * {@code 💬 N messages — Claude: <snippet>  ✎}. Clicking it toggles the thread
 * open: inline presentation (default, see {@link Activator#PREF_ICR_INLINE})
 * expands a {@link IcrInlineChatOverlay} embedded between this summary line
 * and the code, reserving space for it via blank {@link SpacerMining} rows;
 * the legacy popup presentation opens {@link IcrThreadBox} instead. Active
 * only while a session is running.
 */
public class IcrCodeMiningProvider extends AbstractCodeMiningProvider {

	private static final String BUBBLE = new String(Character.toChars(0x1F4AC)); // 💬 speech balloon
	private static final String PENCIL = new String(Character.toChars(0x270E)); // ✎ reply affordance
	private static final String HOURGLASS = new String(Character.toChars(0x23F3)); // ⏳ Claude working
	private static final int SNIPPET = 70;

	@Override
	public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(ITextViewer viewer,
			IProgressMonitor monitor) {
		IcrSessionStore store = IcrSessionStore.instance();
		if (!store.isActive()) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		IDocument document = viewer.getDocument();
		if (document == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		String workspacePath = IcrEditors.workspacePath(document);
		if (workspacePath == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		boolean inline = Activator.getDefault().getPreferenceStore().getBoolean(Activator.PREF_ICR_INLINE);
		IcrInlineChatManager manager = IcrInlineChatManager.instance();

		List<ICodeMining> minings = new ArrayList<>();
		for (IcrThread thread : store.threadsForFile(workspacePath)) {
			int line = lineOf(thread, document);
			if (line < 0) {
				continue;
			}
			try {
				int headerLine = headerLineOf(line, document);
				if (inline && manager.isExpanded(thread.id())) {
					int lineHeight = viewer.getTextWidget().getLineHeight();
					int rows = IcrInlineChatOverlay.reservedLineCount(lineHeight);
					// Added before the summary mining so the reserved blank rows sit
					// above it, keeping the bubble line where it always is: immediately
					// below the anchored code line.
					minings.add(new SpacerMining(headerLine, document, this, rows));
				}
				minings.add(
						new LineMining(headerLine, document, this, summary(thread), openAction(viewer, thread.id())));
			} catch (BadLocationException e) {
				// line scrolled out of range after edits — skip
			}
		}
		return CompletableFuture.completedFuture(minings);
	}

	/**
	 * {@code "💬 N messages — <lastAuthor>: <snippet>  ✎"}, or, while Claude is
	 * preparing its answer, {@code "💬 N messages — ⏳ Claude is working…  ✎"}.
	 */
	private static String summary(IcrThread thread) {
		List<IcrReply> replies = thread.replies();
		int count = 1 + replies.size();
		StringBuilder sb = new StringBuilder(BUBBLE).append(' ').append(count)
				.append(count == 1 ? " message" : " messages").append(" — ");
		if (thread.working()) {
			sb.append(HOURGLASS).append(" Claude is working…");
		} else {
			String lastAuthor;
			String lastBody;
			if (replies.isEmpty()) {
				lastAuthor = "You";
				lastBody = thread.body();
			} else {
				IcrReply last = replies.get(replies.size() - 1);
				lastAuthor = IcrReply.AUTHOR_CLAUDE.equals(last.author()) ? "Claude" : "You";
				lastBody = last.body();
			}
			sb.append(lastAuthor).append(": ").append(snippet(lastBody));
		}
		sb.append("   ").append(PENCIL);
		return sb.toString();
	}

	private static String snippet(String body) {
		if (body == null) {
			return "";
		}
		String firstLine = body.strip().replace("\n", " ").replaceAll("\\s+", " ");
		return firstLine.length() > SNIPPET ? firstLine.substring(0, SNIPPET - 1) + "…" : firstLine;
	}

	/** 0-based line for the thread: live anchor if available, else snapshot. */
	static int lineOf(IcrThread thread, IDocument document) {
		Position anchor = thread.anchor();
		if (anchor != null && !anchor.isDeleted()) {
			try {
				return document.getLineOfOffset(anchor.getOffset());
			} catch (BadLocationException ignored) {
				// fall through to snapshot
			}
		}
		int line = thread.startLine() - 1;
		if (line < 0 || line >= document.getNumberOfLines()) {
			return -1;
		}
		return line;
	}

	/**
	 * 0-based line the summary/bubble mining (and, when expanded, its spacer
	 * rows) attaches to: the line right after {@code anchorLine}, so
	 * {@code LineHeaderCodeMining}'s "draws above its line" behaviour renders
	 * them below the anchored code instead of above it. Falls back to
	 * {@code anchorLine} itself when it is the document's last line.
	 */
	static int headerLineOf(int anchorLine, IDocument document) {
		return Math.min(anchorLine + 1, document.getNumberOfLines() - 1);
	}

	private final class LineMining extends LineHeaderCodeMining {
		LineMining(int line, IDocument document, IcrCodeMiningProvider provider, String label,
				Consumer<MouseEvent> action) throws BadLocationException {
			super(line, document, provider, action);
			setLabel(label);
		}
	}

	/**
	 * A blank header mining reserving {@code rows} extra line-heights of
	 * vertical space above the anchored code line, so
	 * {@link IcrInlineChatOverlay} has room to paint over. Sharing a document
	 * line with the summary {@link LineMining}, multiple single-line minings on
	 * the same line would normally be concatenated onto one visual row by the
	 * code-mining framework — the only way to actually stack extra blank rows
	 * is a single mining whose own label contains embedded newlines, which is
	 * what this does.
	 */
	private final class SpacerMining extends LineHeaderCodeMining {
		SpacerMining(int line, IDocument document, IcrCodeMiningProvider provider, int rows)
				throws BadLocationException {
			super(line, document, provider, null);
			// String#split() drops trailing empty strings, so an all-blank label would
			// collapse to a zero-length array and reserve no height at all; a
			// non-empty last line keeps all `rows` blank lines in the split result.
			setLabel("\n".repeat(rows) + " ");
		}
	}

	private static Consumer<MouseEvent> openAction(ITextViewer viewer, String threadId) {
		return e -> {
			boolean inline = Activator.getDefault().getPreferenceStore().getBoolean(Activator.PREF_ICR_INLINE);
			if (inline) {
				IcrInlineChatManager.instance().toggle(viewer, threadId);
				return;
			}
			// Legacy popup: toggle — a click while this thread's box is open just
			// closes it; and don't reopen if an outside-click dismiss for this
			// thread just fired.
			if (IcrThreadBox.closeIfOpenFor(threadId) || IcrThreadBox.consumeSuppressedReopen(threadId)) {
				return;
			}
			Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
					? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell()
					: null;
			if (shell == null) {
				return;
			}
			Point anchor = null;
			if (e.widget instanceof Control control && !control.isDisposed()) {
				anchor = control.toDisplay(e.x, e.y);
			}
			new IcrThreadBox(shell, threadId, anchor).open();
		};
	}
}
