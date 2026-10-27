package advcounter;

import java.util.*;

/** Minimal JSON reader/writer (objects become LinkedHashMap, arrays ArrayList, numbers Double). */
public final class Json {
	private final String s;
	private int i;

	private Json(String s) { this.s = s; }

	public static Object parse(String text) {
		Json p = new Json(text);
		p.ws();
		Object v = p.value();
		p.ws();
		if (p.i != p.s.length()) throw p.error("trailing characters");
		return v;
	}

	private RuntimeException error(String m) { return new IllegalArgumentException("JSON " + m + " at " + i); }

	private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

	private Object value() {
		if (i >= s.length()) throw error("unexpected end");
		char c = s.charAt(i);
		if (c == '{') return object();
		if (c == '[') return array();
		if (c == '"') return string();
		if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
		if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
		if (s.startsWith("null", i)) { i += 4; return null; }
		return number();
	}

	private Map<String, Object> object() {
		Map<String, Object> m = new LinkedHashMap<>();
		i++; ws();
		if (s.charAt(i) == '}') { i++; return m; }
		while (true) {
			ws();
			String k = string();
			ws();
			if (s.charAt(i++) != ':') throw error("expected ':'");
			ws();
			m.put(k, value());
			ws();
			char c = s.charAt(i++);
			if (c == '}') return m;
			if (c != ',') throw error("expected ',' or '}'");
		}
	}

	private List<Object> array() {
		List<Object> l = new ArrayList<>();
		i++; ws();
		if (s.charAt(i) == ']') { i++; return l; }
		while (true) {
			ws();
			l.add(value());
			ws();
			char c = s.charAt(i++);
			if (c == ']') return l;
			if (c != ',') throw error("expected ',' or ']'");
		}
	}

	private String string() {
		if (s.charAt(i) != '"') throw error("expected string");
		i++;
		StringBuilder b = new StringBuilder();
		while (true) {
			char c = s.charAt(i++);
			if (c == '"') return b.toString();
			if (c != '\\') { b.append(c); continue; }
			char e = s.charAt(i++);
			switch (e) {
				case 'n': b.append('\n'); break;
				case 't': b.append('\t'); break;
				case 'r': b.append('\r'); break;
				case 'b': b.append('\b'); break;
				case 'f': b.append('\f'); break;
				case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
				default: b.append(e);
			}
		}
	}

	private Double number() {
		int st = i;
		while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
		if (st == i) throw error("unexpected character");
		return Double.valueOf(s.substring(st, i));
	}

	public static String write(Object v) {
		StringBuilder b = new StringBuilder();
		write(b, v, 0);
		b.append('\n');
		return b.toString();
	}

	private static void indent(StringBuilder b, int n) { for (int k = 0; k < n; k++) b.append("  "); }

	@SuppressWarnings("unchecked")
	private static void write(StringBuilder b, Object v, int depth) {
		if (v == null) b.append("null");
		else if (v instanceof String) quote(b, (String) v);
		else if (v instanceof Boolean) b.append(v);
		else if (v instanceof Number) {
			double d = ((Number) v).doubleValue();
			if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
			else b.append(Double.toString(d));
		} else if (v instanceof Map) {
			Map<String, Object> m = (Map<String, Object>) v;
			if (m.isEmpty()) { b.append("{}"); return; }
			b.append("{\n");
			int k = 0;
			for (Map.Entry<String, Object> e : m.entrySet()) {
				indent(b, depth + 1);
				quote(b, e.getKey());
				b.append(": ");
				write(b, e.getValue(), depth + 1);
				if (++k < m.size()) b.append(',');
				b.append('\n');
			}
			indent(b, depth);
			b.append('}');
		} else if (v instanceof Collection) {
			Collection<Object> l = (Collection<Object>) v;
			if (l.isEmpty()) { b.append("[]"); return; }
			boolean flat = true;
			for (Object o : l) if (o instanceof Map || o instanceof Collection) flat = false;
			if (flat) {
				b.append('[');
				int k = 0;
				for (Object o : l) { if (k++ > 0) b.append(", "); write(b, o, depth); }
				b.append(']');
				return;
			}
			b.append("[\n");
			int k = 0;
			for (Object o : l) {
				indent(b, depth + 1);
				write(b, o, depth + 1);
				if (++k < l.size()) b.append(',');
				b.append('\n');
			}
			indent(b, depth);
			b.append(']');
		} else quote(b, v.toString());
	}

	private static void quote(StringBuilder b, String s) {
		b.append('"');
		for (int k = 0; k < s.length(); k++) {
			char c = s.charAt(k);
			switch (c) {
				case '"': b.append("\\\""); break;
				case '\\': b.append("\\\\"); break;
				case '\n': b.append("\\n"); break;
				case '\r': b.append("\\r"); break;
				case '\t': b.append("\\t"); break;
				default:
					if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
					else b.append(c);
			}
		}
		b.append('"');
	}

	// Typed accessors for parsed maps
	public static String str(Map<String, Object> m, String k, String def) {
		Object v = m.get(k);
		return v == null ? def : v.toString();
	}

	public static double num(Map<String, Object> m, String k, double def) {
		Object v = m.get(k);
		return v instanceof Number ? ((Number) v).doubleValue() : def;
	}

	public static boolean bool(Map<String, Object> m, String k, boolean def) {
		Object v = m.get(k);
		return v instanceof Boolean ? (Boolean) v : def;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v instanceof List ? (List<Object>) v : new ArrayList<>();
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
	}
}
