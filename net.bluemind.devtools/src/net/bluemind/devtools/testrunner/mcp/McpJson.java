package net.bluemind.devtools.testrunner.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpJson {

	private McpJson() {
	}

	public static Object parse(String text) {
		Parser p = new Parser(text);
		p.skipWs();
		Object value = p.readValue();
		p.skipWs();
		if (p.pos < p.src.length()) {
			throw new IllegalArgumentException("Unexpected trailing content at " + p.pos);
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		Object value = parse(text);
		if (!(value instanceof Map)) {
			throw new IllegalArgumentException("Expected JSON object");
		}
		return (Map<String, Object>) value;
	}

	public static String write(Object value) {
		StringBuilder sb = new StringBuilder();
		writeTo(sb, value);
		return sb.toString();
	}

	private static void writeTo(StringBuilder sb, Object value) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof String s) {
			writeString(sb, s);
		} else if (value instanceof Boolean b) {
			sb.append(b.booleanValue() ? "true" : "false");
		} else if (value instanceof Number n) {
			if (n instanceof Double || n instanceof Float) {
				double d = n.doubleValue();
				if (Double.isNaN(d) || Double.isInfinite(d)) {
					sb.append("null");
					return;
				}
				if (d == Math.floor(d) && Math.abs(d) < 1e15) {
					sb.append(Long.toString((long) d));
					return;
				}
			}
			sb.append(n.toString());
		} else if (value instanceof Map<?, ?> m) {
			sb.append('{');
			boolean first = true;
			for (var entry : m.entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				writeString(sb, String.valueOf(entry.getKey()));
				sb.append(':');
				writeTo(sb, entry.getValue());
			}
			sb.append('}');
		} else if (value instanceof Iterable<?> it) {
			sb.append('[');
			boolean first = true;
			for (Object o : it) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				writeTo(sb, o);
			}
			sb.append(']');
		} else {
			writeString(sb, value.toString());
		}
	}

	private static void writeString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"' -> sb.append("\\\"");
			case '\\' -> sb.append("\\\\");
			case '\b' -> sb.append("\\b");
			case '\f' -> sb.append("\\f");
			case '\n' -> sb.append("\\n");
			case '\r' -> sb.append("\\r");
			case '\t' -> sb.append("\\t");
			default -> {
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
			}
		}
		sb.append('"');
	}

	private static final class Parser {
		final String src;
		int pos;

		Parser(String src) {
			this.src = src;
		}

		Object readValue() {
			skipWs();
			if (pos >= src.length()) {
				throw new IllegalArgumentException("Unexpected end of input");
			}
			char c = src.charAt(pos);
			return switch (c) {
			case '{' -> readObject();
			case '[' -> readArray();
			case '"' -> readString();
			case 't', 'f' -> readBoolean();
			case 'n' -> readNull();
			default -> readNumber();
			};
		}

		Map<String, Object> readObject() {
			expect('{');
			Map<String, Object> map = new LinkedHashMap<>();
			skipWs();
			if (peek() == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWs();
				String key = readString();
				skipWs();
				expect(':');
				Object value = readValue();
				map.put(key, value);
				skipWs();
				char c = peek();
				if (c == ',') {
					pos++;
				} else if (c == '}') {
					pos++;
					return map;
				} else {
					throw new IllegalArgumentException("Expected , or } at " + pos);
				}
			}
		}

		List<Object> readArray() {
			expect('[');
			List<Object> list = new ArrayList<>();
			skipWs();
			if (peek() == ']') {
				pos++;
				return list;
			}
			while (true) {
				list.add(readValue());
				skipWs();
				char c = peek();
				if (c == ',') {
					pos++;
				} else if (c == ']') {
					pos++;
					return list;
				} else {
					throw new IllegalArgumentException("Expected , or ] at " + pos);
				}
			}
		}

		String readString() {
			expect('"');
			StringBuilder sb = new StringBuilder();
			while (pos < src.length()) {
				char c = src.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					if (pos >= src.length()) {
						throw new IllegalArgumentException("Unterminated escape");
					}
					char esc = src.charAt(pos++);
					switch (esc) {
					case '"' -> sb.append('"');
					case '\\' -> sb.append('\\');
					case '/' -> sb.append('/');
					case 'b' -> sb.append('\b');
					case 'f' -> sb.append('\f');
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case 'u' -> {
						if (pos + 4 > src.length()) {
							throw new IllegalArgumentException("Bad \\u escape");
						}
						int code = Integer.parseInt(src.substring(pos, pos + 4), 16);
						sb.append((char) code);
						pos += 4;
					}
					default -> throw new IllegalArgumentException("Bad escape \\" + esc);
					}
				} else {
					sb.append(c);
				}
			}
			throw new IllegalArgumentException("Unterminated string");
		}

		Boolean readBoolean() {
			if (src.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (src.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			throw new IllegalArgumentException("Bad literal at " + pos);
		}

		Object readNull() {
			if (src.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw new IllegalArgumentException("Bad literal at " + pos);
		}

		Number readNumber() {
			int start = pos;
			if (peek() == '-') {
				pos++;
			}
			while (pos < src.length() && "0123456789".indexOf(src.charAt(pos)) >= 0) {
				pos++;
			}
			boolean isFloat = false;
			if (pos < src.length() && src.charAt(pos) == '.') {
				isFloat = true;
				pos++;
				while (pos < src.length() && "0123456789".indexOf(src.charAt(pos)) >= 0) {
					pos++;
				}
			}
			if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
				isFloat = true;
				pos++;
				if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
					pos++;
				}
				while (pos < src.length() && "0123456789".indexOf(src.charAt(pos)) >= 0) {
					pos++;
				}
			}
			String token = src.substring(start, pos);
			if (isFloat) {
				return Double.parseDouble(token);
			}
			long l = Long.parseLong(token);
			if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
				return (int) l;
			}
			return l;
		}

		void skipWs() {
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
					pos++;
				} else {
					break;
				}
			}
		}

		char peek() {
			return pos < src.length() ? src.charAt(pos) : '\0';
		}

		void expect(char c) {
			if (pos >= src.length() || src.charAt(pos) != c) {
				throw new IllegalArgumentException("Expected '" + c + "' at " + pos);
			}
			pos++;
		}
	}
}
