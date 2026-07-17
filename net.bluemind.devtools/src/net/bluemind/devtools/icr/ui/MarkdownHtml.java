package net.bluemind.devtools.icr.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal markdown → HTML converter for rendering ICR conversations in an SWT
 * {@link org.eclipse.swt.browser.Browser}. Supports the subset that shows up in
 * code-review replies: fenced and inline code, bold/italic, headings, bullet and
 * numbered lists, links, and paragraphs. Dependency-free, in the same spirit as
 * the hand-rolled JSON in {@code McpJson}.
 */
public final class MarkdownHtml {

	private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
	private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
	private static final Pattern ITALIC = Pattern.compile("(?<![*])\\*([^*]+)\\*(?![*])");
	private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

	private MarkdownHtml() {
	}

	/** Convert a markdown body to an HTML fragment (no &lt;html&gt; wrapper). */
	public static String toHtml(String markdown) {
		if (markdown == null || markdown.isBlank()) {
			return "";
		}
		String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
		StringBuilder out = new StringBuilder();
		boolean inCode = false;
		boolean inUl = false;
		boolean inOl = false;
		StringBuilder paragraph = new StringBuilder();

		for (String raw : lines) {
			String trimmed = raw.strip();

			if (trimmed.startsWith("```")) {
				flushParagraph(out, paragraph);
				closeLists(out, inUl, inOl);
				inUl = inOl = false;
				if (inCode) {
					out.append("</code></pre>\n");
					inCode = false;
				} else {
					out.append("<pre><code>");
					inCode = true;
				}
				continue;
			}
			if (inCode) {
				out.append(escape(raw)).append('\n');
				continue;
			}

			if (trimmed.isEmpty()) {
				flushParagraph(out, paragraph);
				closeLists(out, inUl, inOl);
				inUl = inOl = false;
				continue;
			}

			Matcher heading = Pattern.compile("^(#{1,4})\\s+(.*)$").matcher(trimmed);
			if (heading.matches()) {
				flushParagraph(out, paragraph);
				closeLists(out, inUl, inOl);
				inUl = inOl = false;
				int level = heading.group(1).length();
				out.append("<h").append(level).append('>').append(inline(heading.group(2)))
						.append("</h").append(level).append(">\n");
				continue;
			}

			if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
				flushParagraph(out, paragraph);
				if (inOl) {
					out.append("</ol>\n");
					inOl = false;
				}
				if (!inUl) {
					out.append("<ul>\n");
					inUl = true;
				}
				out.append("<li>").append(inline(trimmed.substring(2))).append("</li>\n");
				continue;
			}

			Matcher ol = Pattern.compile("^(\\d+)\\.\\s+(.*)$").matcher(trimmed);
			if (ol.matches()) {
				flushParagraph(out, paragraph);
				if (inUl) {
					out.append("</ul>\n");
					inUl = false;
				}
				if (!inOl) {
					out.append("<ol>\n");
					inOl = true;
				}
				out.append("<li>").append(inline(ol.group(2))).append("</li>\n");
				continue;
			}

			closeLists(out, inUl, inOl);
			inUl = inOl = false;
			if (paragraph.length() > 0) {
				paragraph.append(' ');
			}
			paragraph.append(trimmed);
		}

		flushParagraph(out, paragraph);
		closeLists(out, inUl, inOl);
		if (inCode) {
			out.append("</code></pre>\n");
		}
		return out.toString();
	}

	/** Wrap a conversation fragment in a full, self-contained HTML document. */
	public static String document(String bodyHtml) {
		return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>"
				+ "body{font-family:-apple-system,Segoe UI,Ubuntu,sans-serif;font-size:12px;"
				+ "margin:0;padding:8px;color:#1f2328;}"
				+ "pre{background:#f6f8fa;border-radius:6px;padding:8px;overflow:auto;}"
				+ "code{font-family:Consolas,Menlo,monospace;font-size:11px;}"
				+ "pre code{background:none;}"
				+ ":not(pre)>code{background:#eff1f3;border-radius:4px;padding:1px 4px;}"
				+ ".msg{border:1px solid #d0d7de;border-radius:6px;margin:6px 0;padding:6px 8px;}"
				+ ".msg.claude{background:#f3f8ff;border-color:#b6daff;}"
				+ ".msg.user{background:#fbfbfb;}"
				+ ".author{font-weight:600;margin-bottom:2px;}"
				+ ".author.claude{color:#0969da;} .author.user{color:#57606a;}"
				+ ".msg.working{opacity:.85;} .typing{color:#57606a;font-style:italic;}"
				+ ".sel{background:#fff8c5;border-left:3px solid #d4a72c;padding:4px 8px;"
				+ "white-space:pre-wrap;font-family:Consolas,Menlo,monospace;font-size:11px;margin:4px 0;}"
				+ "h1,h2,h3,h4{margin:6px 0 4px;} p{margin:4px 0;}"
				+ "</style></head><body>" + bodyHtml
				// Keep the latest message in view: scroll to the bottom after layout.
				+ "<script>window.scrollTo(0, document.body.scrollHeight);</script>"
				+ "</body></html>";
	}

	public static String escape(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String inline(String text) {
		String s = escape(text);
		s = replaceWith(INLINE_CODE, s, m -> "<code>" + m.group(1) + "</code>");
		s = replaceWith(LINK, s, m -> "<a href=\"" + m.group(2) + "\">" + m.group(1) + "</a>");
		s = replaceWith(BOLD, s, m -> "<strong>" + m.group(1) + "</strong>");
		s = replaceWith(ITALIC, s, m -> "<em>" + m.group(1) + "</em>");
		return s;
	}

	private interface Repl {
		String apply(Matcher m);
	}

	private static String replaceWith(Pattern pattern, String input, Repl repl) {
		Matcher m = pattern.matcher(input);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(sb, Matcher.quoteReplacement(repl.apply(m)));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static void flushParagraph(StringBuilder out, StringBuilder paragraph) {
		if (paragraph.length() > 0) {
			out.append("<p>").append(inline(paragraph.toString())).append("</p>\n");
			paragraph.setLength(0);
		}
	}

	private static void closeLists(StringBuilder out, boolean inUl, boolean inOl) {
		if (inUl) {
			out.append("</ul>\n");
		}
		if (inOl) {
			out.append("</ol>\n");
		}
	}
}
