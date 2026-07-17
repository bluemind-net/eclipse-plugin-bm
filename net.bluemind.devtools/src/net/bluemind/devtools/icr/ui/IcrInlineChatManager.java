package net.bluemind.devtools.icr.ui;

import org.eclipse.jface.text.ITextViewer;

/**
 * Coordinates the single ICR thread that is currently expanded inline (at
 * most one at a time, mirroring the previous single-open-{@link IcrThreadBox}
 * behaviour). Owns the one live {@link IcrInlineChatOverlay} instance and its
 * lifecycle: expanding a thread disposes any previously expanded one.
 */
final class IcrInlineChatManager {

	private static final IcrInlineChatManager INSTANCE = new IcrInlineChatManager();

	private IcrInlineChatOverlay overlay;

	private IcrInlineChatManager() {
	}

	static IcrInlineChatManager instance() {
		return INSTANCE;
	}

	/** True while {@code threadId} is the currently expanded inline thread. */
	boolean isExpanded(String threadId) {
		return overlay != null && overlay.threadId().equals(threadId);
	}

	/**
	 * Toggles the inline chat for {@code threadId}: collapses it if already
	 * expanded, otherwise collapses whichever other thread was expanded and
	 * expands this one. Triggers a code-mining refresh so the provider emits (or
	 * stops emitting) the spacer rows the overlay is positioned over.
	 */
	void toggle(ITextViewer viewer, String threadId) {
		if (isExpanded(threadId)) {
			collapse();
			return;
		}
		collapse();
		overlay = new IcrInlineChatOverlay(viewer, threadId);
		IcrEditors.refreshCodeMinings();
	}

	/** Collapses the currently expanded thread, if any. No-op otherwise. */
	void collapse() {
		if (overlay == null) {
			return;
		}
		IcrInlineChatOverlay current = overlay;
		overlay = null;
		current.dispose();
		IcrEditors.refreshCodeMinings();
	}
}
