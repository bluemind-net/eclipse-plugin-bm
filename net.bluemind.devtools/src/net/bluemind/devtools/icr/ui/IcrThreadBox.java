package net.bluemind.devtools.icr.ui;

import org.eclipse.jface.dialogs.PopupDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import net.bluemind.devtools.icr.model.IcrReply;
import net.bluemind.devtools.icr.model.IcrSessionStore;
import net.bluemind.devtools.icr.model.IcrThread;

/**
 * Line-anchored box that shows a full ICR conversation (rendered markdown in a
 * {@link Browser}) plus a reply field. Opened by clicking the inline {@code 💬}
 * summary mining. Focusable, anchored at the clicked line, dismisses on outside
 * click, posts on Ctrl+Enter / Send, and live-updates while open (e.g. when
 * Claude posts an answer).
 */
public class IcrThreadBox extends PopupDialog {

	/** At most one box is open at a time; opening a new one closes the previous. */
	private static IcrThreadBox openInstance;

	/**
	 * When an outside click dismisses a box, the very same click may also land on
	 * that thread's inline summary and try to reopen it. We briefly suppress a
	 * reopen of that thread so the dismiss wins, regardless of event ordering.
	 */
	private static volatile String suppressReopenThreadId;
	private static volatile long suppressReopenUntilNanos;

	private final String threadId;
	private final Point anchor;
	private Browser browser;
	private Text input;
	private org.eclipse.swt.widgets.Listener outsideClickFilter;
	private final IcrSessionStore.Listener storeListener = this::onStoreChanged;

	public IcrThreadBox(Shell parent, String threadId, Point anchor) {
		super(parent, SWT.RESIZE | SWT.ON_TOP, true, false, false, false, false, null, null);
		this.threadId = threadId;
		this.anchor = anchor;
	}

	/**
	 * If a box for {@code threadId} is currently open, close it and return
	 * {@code true}. Lets a click on the inline summary act as a toggle (and avoids
	 * the close-then-reopen flicker when clicking the same glyph).
	 */
	public static boolean closeIfOpenFor(String threadId) {
		if (openInstance != null && threadId.equals(openInstance.threadId)) {
			Shell shell = openInstance.getShell();
			if (shell != null && !shell.isDisposed()) {
				openInstance.close();
				return true;
			}
		}
		return false;
	}

	/**
	 * True (consuming it) if a reopen of {@code threadId} is currently suppressed
	 * because an outside click just dismissed its box.
	 */
	public static boolean consumeSuppressedReopen(String threadId) {
		if (threadId.equals(suppressReopenThreadId) && System.nanoTime() < suppressReopenUntilNanos) {
			suppressReopenThreadId = null;
			return true;
		}
		return false;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 6;
		layout.marginHeight = 6;
		area.setLayout(layout);

		browser = new Browser(area, SWT.NONE);
		GridData bgd = new GridData(SWT.FILL, SWT.FILL, true, true);
		bgd.heightHint = 330;
		bgd.widthHint = 660;
		browser.setLayoutData(bgd);

		input = new Text(area, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
		GridData igd = new GridData(SWT.FILL, SWT.FILL, true, false);
		igd.heightHint = 72;
		input.setLayoutData(igd);
		input.setMessage("Reply… (Ctrl+Enter to send, Esc to close)");
		input.addListener(SWT.KeyDown, e -> {
			if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.MOD1) != 0) {
				e.doit = false;
				postReply();
			} else if (e.keyCode == SWT.ESC) {
				e.doit = false;
				close();
			}
		});

		Composite buttons = new Composite(area, SWT.NONE);
		buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		GridLayout buttonLayout = new GridLayout(2, false);
		buttonLayout.marginWidth = 0;
		buttonLayout.marginHeight = 0;
		buttons.setLayout(buttonLayout);

		Button delete = new Button(buttons, SWT.PUSH);
		delete.setText("Delete");
		delete.setToolTipText("Remove this conversation and its inline marker");
		delete.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
		delete.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(e -> deleteThread()));

		Button send = new Button(buttons, SWT.PUSH);
		send.setText("Send");
		send.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
		send.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(e -> postReply()));

		render();
		return area;
	}

	@Override
	protected Control createContents(Composite parent) {
		Control contents = super.createContents(parent);
		IcrSessionStore.instance().addListener(storeListener);
		// Dismiss on a mouse-down outside the box. A display filter fires reliably
		// here (SWT.Deactivate is flaky for ON_TOP popups on Wayland). The close is
		// deferred to the next tick so a click on the inline summary glyph is first
		// handled by its toggle action (avoids a close-then-reopen flicker).
		Display display = getShell().getDisplay();
		outsideClickFilter = e -> {
			Shell shell = getShell();
			if (shell == null || shell.isDisposed()) {
				return;
			}
			if (e.widget instanceof Control c && !c.isDisposed() && c.getShell() == shell) {
				return; // inside the box — keep open
			}
			// The same click may also hit this thread's inline summary; suppress a
			// reopen so the dismiss wins no matter which handler runs first.
			suppressReopenThreadId = threadId;
			suppressReopenUntilNanos = System.nanoTime() + 250_000_000L;
			close();
		};
		display.addFilter(SWT.MouseDown, outsideClickFilter);
		return contents;
	}

	@Override
	public int open() {
		// Only one thread box at a time — avoids stacking duplicates.
		if (openInstance != null && openInstance != this) {
			openInstance.close();
		}
		openInstance = this;
		int result = super.open();
		// Focus the reply field once the shell is actually shown (setting focus
		// during createContents, before the shell is visible, does not stick).
		if (input != null && !input.isDisposed()) {
			input.getDisplay().asyncExec(() -> {
				if (input != null && !input.isDisposed()) {
					input.setFocus();
				}
			});
		}
		return result;
	}

	@Override
	protected Point getInitialSize() {
		return new Point(720, 510);
	}

	@Override
	protected Point getInitialLocation(Point initialSize) {
		if (anchor == null) {
			return super.getInitialLocation(initialSize);
		}
		int x = anchor.x;
		int y = anchor.y + 14;
		Monitor monitor = getShell().getMonitor();
		Rectangle ca = monitor.getClientArea();
		if (x + initialSize.x > ca.x + ca.width) {
			x = ca.x + ca.width - initialSize.x;
		}
		if (x < ca.x) {
			x = ca.x;
		}
		if (y + initialSize.y > ca.y + ca.height) {
			y = anchor.y - initialSize.y - 4;
		}
		if (y < ca.y) {
			y = ca.y;
		}
		return new Point(x, y);
	}

	@Override
	public boolean close() {
		IcrSessionStore.instance().removeListener(storeListener);
		if (outsideClickFilter != null) {
			Shell shell = getShell();
			if (shell != null && !shell.isDisposed()) {
				shell.getDisplay().removeFilter(SWT.MouseDown, outsideClickFilter);
			}
			outsideClickFilter = null;
		}
		if (openInstance == this) {
			openInstance = null;
		}
		return super.close();
	}

	private void onStoreChanged() {
		Display display = browser == null ? null : browser.getDisplay();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(this::render);
	}

	private void deleteThread() {
		boolean confirmed = org.eclipse.jface.dialogs.MessageDialog.openConfirm(getShell(), "Delete conversation",
				"Remove this review conversation and its inline marker? This cannot be undone.");
		if (!confirmed) {
			return;
		}
		IcrSessionStore.instance().removeThread(threadId);
		// The inline 💬 summary is derived from the store; recompute so it disappears.
		IcrEditors.refreshCodeMinings();
		close();
	}

	private void postReply() {
		if (input == null || input.isDisposed()) {
			return;
		}
		String text = input.getText().trim();
		if (text.isEmpty()) {
			return;
		}
		IcrSessionStore.instance().addReply(threadId, IcrReply.AUTHOR_USER, text);
		input.setText("");
		render();
	}

	private void render() {
		IcrThread thread = IcrSessionStore.instance().findThread(threadId);
		if (thread == null || browser == null || browser.isDisposed()) {
			return;
		}
		StringBuilder body = new StringBuilder();
		if (thread.selectedText() != null && !thread.selectedText().isBlank()) {
			body.append("<div class=\"sel\">").append(MarkdownHtml.escape(thread.selectedText())).append("</div>");
		}
		appendMessage(body, IcrReply.AUTHOR_USER, "You", thread.body());
		for (IcrReply reply : thread.replies()) {
			String label = IcrReply.AUTHOR_CLAUDE.equals(reply.author()) ? "Claude" : "You";
			appendMessage(body, reply.author(), label, reply.body());
		}
		if (thread.working()) {
			body.append("<div class=\"msg claude working\">")
					.append("<div class=\"author claude\">Claude</div>")
					.append("<div class=\"typing\">⏳ working…</div></div>");
		}
		browser.setText(MarkdownHtml.document(body.toString()));
	}

	private static void appendMessage(StringBuilder out, String author, String label, String markdown) {
		String cls = IcrReply.AUTHOR_CLAUDE.equals(author) ? "claude" : "user";
		out.append("<div class=\"msg ").append(cls).append("\">")
				.append("<div class=\"author ").append(cls).append("\">").append(label).append("</div>")
				.append(MarkdownHtml.toHtml(markdown))
				.append("</div>");
	}
}
