package net.bluemind.devtools.icr.ui;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.IViewportListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;

import net.bluemind.devtools.icr.model.IcrReply;
import net.bluemind.devtools.icr.model.IcrSessionStore;
import net.bluemind.devtools.icr.model.IcrThread;

/**
 * Chat UI for one ICR thread, embedded directly in the editor between the
 * thread's summary {@code 💬} mining line and the anchored code line — a real
 * {@link Composite} child of the editor's {@link StyledText}, not a floating
 * shell. Holds a read-only {@link Browser} (conversation history, rendered via
 * {@link IcrConversationHtml}) and an editable {@link Text} reply field.
 *
 * <p>
 * Owned exclusively by {@link IcrInlineChatManager}, which guarantees at most
 * one instance is alive at a time and disposes it when the thread is
 * collapsed, deleted, or another thread is expanded.
 */
final class IcrInlineChatOverlay {

	/** Total pixel height reserved above the anchored code line for this overlay. */
	static final int RESERVED_PX = 300;

	/** Number of {@code LineHeaderCodeMining} spacer rows needed to reserve {@link #RESERVED_PX}. */
	static int reservedLineCount(int lineHeight) {
		return lineHeight <= 0 ? 1 : Math.max(1, (int) Math.ceil(RESERVED_PX / (double) lineHeight));
	}

	private final ITextViewer viewer;
	private final StyledText styledText;
	private final String threadId;

	private Composite composite;
	private Browser browser;
	private Text input;

	private final IcrSessionStore.Listener storeListener = this::onStoreChanged;
	private final IViewportListener viewportListener = offset -> reposition();
	private final ControlListener controlListener = ControlListener.controlResizedAdapter(e -> reposition());
	private final DisposeListener parentDisposeListener = e -> IcrInlineChatManager.instance().collapse();

	IcrInlineChatOverlay(ITextViewer viewer, String threadId) {
		this.viewer = viewer;
		this.styledText = viewer.getTextWidget();
		this.threadId = threadId;
		createContents();
		IcrSessionStore.instance().addListener(storeListener);
		viewer.addViewportListener(viewportListener);
		styledText.addControlListener(controlListener);
		styledText.addDisposeListener(parentDisposeListener);
		reposition();
		render();
		input.getDisplay().asyncExec(() -> {
			if (input != null && !input.isDisposed()) {
				input.setFocus();
			}
		});
	}

	String threadId() {
		return threadId;
	}

	private void createContents() {
		composite = new Composite(styledText, SWT.BORDER);
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 4;
		layout.marginHeight = 4;
		layout.verticalSpacing = 4;
		composite.setLayout(layout);

		browser = new Browser(composite, SWT.NONE);
		browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		input = new Text(composite, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
		GridData igd = new GridData(SWT.FILL, SWT.FILL, true, false);
		igd.heightHint = 48;
		input.setLayoutData(igd);
		input.setMessage("Reply… (Ctrl+Enter to send, Esc to collapse)");
		input.addListener(SWT.KeyDown, e -> {
			if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.MOD1) != 0) {
				e.doit = false;
				postReply();
			} else if (e.keyCode == SWT.ESC) {
				e.doit = false;
				IcrInlineChatManager.instance().collapse();
			}
		});

		Composite buttons = new Composite(composite, SWT.NONE);
		buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		GridLayout buttonLayout = new GridLayout(2, false);
		buttonLayout.marginWidth = 0;
		buttonLayout.marginHeight = 0;
		buttons.setLayout(buttonLayout);

		Button delete = new Button(buttons, SWT.PUSH);
		delete.setText("Delete");
		delete.setToolTipText("Remove this conversation and its inline marker");
		delete.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
		delete.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> deleteThread()));

		Button send = new Button(buttons, SWT.PUSH);
		send.setText("Send");
		send.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
		send.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> postReply()));

		composite.moveAbove(null);
	}

	/**
	 * Recomputes the overlay's position and visibility from the thread's current
	 * anchor. Hides (without disposing) the composite when the anchored line has
	 * scrolled out of the visible viewport, or when the thread/document is gone.
	 */
	void reposition() {
		if (composite == null || composite.isDisposed()) {
			return;
		}
		IcrThread thread = IcrSessionStore.instance().findThread(threadId);
		IDocument document = viewer.getDocument();
		if (thread == null || document == null) {
			IcrInlineChatManager.instance().collapse();
			return;
		}
		int line = IcrCodeMiningProvider.lineOf(thread, document);
		if (line < 0) {
			composite.setVisible(false);
			return;
		}
		int headerLine = IcrCodeMiningProvider.headerLineOf(line, document);
		int modelOffset;
		try {
			modelOffset = document.getLineOffset(headerLine);
		} catch (BadLocationException e) {
			composite.setVisible(false);
			return;
		}
		int widgetOffset = viewer instanceof ITextViewerExtension5 ext5 ? ext5.modelOffset2WidgetOffset(modelOffset)
				: modelOffset;
		if (widgetOffset < 0) {
			// folded away (e.g. inside a collapsed region) — nothing sensible to anchor to
			composite.setVisible(false);
			return;
		}
		int widgetLine = styledText.getLineAtOffset(widgetOffset);
		int lineHeight = styledText.getLineHeight();
		int height = reservedLineCount(lineHeight) * lineHeight;
		// getLinePixel returns the top of the line's box including its vertical
		// indent, and the spacer rows are the first thing in that indent (followed
		// by the summary mining, then the anchored code line itself) — so the
		// reserved block starts exactly at the line's top pixel.
		int topY = styledText.getLinePixel(widgetLine);
		Rectangle clientArea = styledText.getClientArea();
		boolean visible = topY + height > 0 && topY < clientArea.height;
		composite.setVisible(visible);
		if (visible) {
			composite.setBounds(clientArea.x, topY, clientArea.width, height);
		}
	}

	void dispose() {
		IcrSessionStore.instance().removeListener(storeListener);
		if (!styledText.isDisposed()) {
			viewer.removeViewportListener(viewportListener);
			styledText.removeControlListener(controlListener);
			styledText.removeDisposeListener(parentDisposeListener);
		}
		if (composite != null && !composite.isDisposed()) {
			composite.dispose();
		}
		composite = null;
	}

	private void onStoreChanged() {
		Display display = styledText.isDisposed() ? null : styledText.getDisplay();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			if (composite == null || composite.isDisposed()) {
				return;
			}
			if (IcrSessionStore.instance().findThread(threadId) == null) {
				IcrInlineChatManager.instance().collapse();
				return;
			}
			render();
			reposition();
		});
	}

	private void deleteThread() {
		boolean confirmed = org.eclipse.jface.dialogs.MessageDialog.openConfirm(composite.getShell(),
				"Delete conversation", "Remove this review conversation and its inline marker? This cannot be undone.");
		if (!confirmed) {
			return;
		}
		IcrInlineChatManager.instance().collapse();
		IcrSessionStore.instance().removeThread(threadId);
		IcrEditors.refreshCodeMinings();
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
		browser.setText(MarkdownHtml.document(IcrConversationHtml.bodyHtml(thread)));
	}
}
