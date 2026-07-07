package net.bluemind.devtools.icr.ui;

import net.bluemind.devtools.icr.model.IcrReply;
import net.bluemind.devtools.icr.model.IcrThread;

/**
 * Builds the HTML body fragment for an {@link IcrThread}'s conversation
 * (selected code, initial question, replies, "working" indicator), for
 * rendering in an SWT {@link org.eclipse.swt.browser.Browser} via
 * {@link MarkdownHtml#document(String)}. Shared by {@link IcrThreadBox} (popup
 * presentation) and {@link IcrInlineChatOverlay} (inline presentation).
 */
final class IcrConversationHtml {

	private IcrConversationHtml() {
	}

	static String bodyHtml(IcrThread thread) {
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
			body.append("<div class=\"msg claude working\">").append("<div class=\"author claude\">Claude</div>")
					.append("<div class=\"typing\">⏳ working…</div></div>");
		}
		return body.toString();
	}

	private static void appendMessage(StringBuilder out, String author, String label, String markdown) {
		String cls = IcrReply.AUTHOR_CLAUDE.equals(author) ? "claude" : "user";
		out.append("<div class=\"msg ").append(cls).append("\">").append("<div class=\"author ").append(cls)
				.append("\">").append(label).append("</div>").append(MarkdownHtml.toHtml(markdown))
				.append("</div>");
	}
}
