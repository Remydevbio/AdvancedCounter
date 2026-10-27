package advcounter;

import java.util.*;

/**
 * Arithmetic over project measurements. Variables are written in braces:
 * {Region:area} (calibrated), {Region:area_px}, {Region:Counter}, {Outside:Counter}, {Total:Counter}.
 * Operators + - * / and parentheses. Unknown names are rejected at parse time; missing data or
 * division by zero evaluate to null (NA).
 */
public class Formula {
	interface Node { Double eval(Project.ImageEntry e); }

	/** A resolved measurement. */
	public static class Var implements Node {
		final String key; // metric key
		final boolean calibratedArea;
		final Set<String> classIds = new LinkedHashSet<>();
		final String label;

		Var(String label, String key, boolean calibratedArea, String... ids) {
			this.label = label; this.key = key; this.calibratedArea = calibratedArea;
			classIds.addAll(Arrays.asList(ids));
		}

		public Double eval(Project.ImageEntry e) {
			if (!e.done() || !e.known.containsAll(classIds)) return null;
			Double v = e.metrics.get(key);
			if (v == null) return null;
			return calibratedArea ? v * e.pixelWidth * e.pixelHeight : v;
		}
	}

	private final String src;
	private final Project project;
	private int i;
	public final Node root;
	public final List<Var> vars = new ArrayList<>();

	private Formula(String src, Project project) {
		this.src = src; this.project = project;
		root = expr();
		ws();
		if (i < src.length()) throw err("unexpected '" + src.charAt(i) + "'");
	}

	/** Parses and validates; throws IllegalArgumentException with a readable message. */
	public static Formula parse(String src, Project project) {
		if (src == null || src.trim().isEmpty()) throw new IllegalArgumentException("Expression is empty");
		return new Formula(src, project);
	}

	public Double eval(Project.ImageEntry e) {
		Double v = root.eval(e);
		return v == null || v.isNaN() || v.isInfinite() ? null : v;
	}

	private IllegalArgumentException err(String m) { return new IllegalArgumentException(m + " (position " + (i + 1) + ")"); }

	private void ws() { while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++; }

	private Node expr() {
		Node l = term();
		while (true) {
			ws();
			if (i >= src.length()) return l;
			char c = src.charAt(i);
			if (c != '+' && c != '-') return l;
			i++;
			Node a = l, b = term();
			l = c == '+' ? e -> bin(a.eval(e), b.eval(e), '+') : e -> bin(a.eval(e), b.eval(e), '-');
		}
	}

	private Node term() {
		Node l = factor();
		while (true) {
			ws();
			if (i >= src.length()) return l;
			char c = src.charAt(i);
			if (c != '*' && c != '/') return l;
			i++;
			Node a = l, b = factor();
			l = c == '*' ? e -> bin(a.eval(e), b.eval(e), '*') : e -> bin(a.eval(e), b.eval(e), '/');
		}
	}

	static Double bin(Double a, Double b, char op) {
		if (a == null || b == null) return null;
		switch (op) {
			case '+': return a + b;
			case '-': return a - b;
			case '*': return a * b;
			default: return b == 0 ? null : a / b;
		}
	}

	private Node factor() {
		ws();
		if (i >= src.length()) throw err("unexpected end of expression");
		char c = src.charAt(i);
		if (c == '-') { i++; Node f = factor(); return e -> { Double v = f.eval(e); return v == null ? null : -v; }; }
		if (c == '+') { i++; return factor(); }
		if (c == '(') {
			i++;
			Node n = expr();
			ws();
			if (i >= src.length() || src.charAt(i) != ')') throw err("missing ')'");
			i++;
			return n;
		}
		if (c == '{') {
			int end = src.indexOf('}', i);
			if (end < 0) throw err("missing '}'");
			String name = src.substring(i + 1, end);
			Var v = variable(name);
			i = end + 1;
			vars.add(v);
			return v;
		}
		if (Character.isDigit(c) || c == '.') {
			int st = i;
			while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
			if (i < src.length() && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
				i++;
				if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) i++;
				while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
			}
			try {
				final double d = Double.parseDouble(src.substring(st, i));
				return e -> d;
			} catch (NumberFormatException ex) { throw err("bad number"); }
		}
		throw err("unexpected '" + c + "' (variables must be written like {Region:Counter})");
	}

	private Var variable(String name) {
		int colon = name.indexOf(':');
		if (colon < 0) throw err("variable {" + name + "} must look like {Region:Counter}, {Region:area} or {Total:Counter}");
		String a = name.substring(0, colon).trim(), b = name.substring(colon + 1).trim();
		Project.PointClass p = point(b);
		if (a.equalsIgnoreCase("Total") || a.equalsIgnoreCase("Outside")) {
			if (p == null) throw err("unknown counter '" + b + "'");
			if (a.equalsIgnoreCase("Outside") && project.regions.isEmpty()) throw err("{Outside:...} needs at least one region class");
			String k = a.equalsIgnoreCase("Total") ? "total" : "outside";
			return new Var("{" + name + "}", "count:" + k + ":" + p.id, false, p.id);
		}
		Project.RegionClass r = region(a);
		if (r == null) throw err("unknown region '" + a + "'");
		if (b.equalsIgnoreCase("area")) return new Var("{" + name + "}", "area:" + r.id, true, r.id);
		if (b.equalsIgnoreCase("area_px")) return new Var("{" + name + "}", "area:" + r.id, false, r.id);
		if (p == null) throw err("unknown counter '" + b + "'");
		return new Var("{" + name + "}", "count:" + r.id + ":" + p.id, false, r.id, p.id);
	}

	private Project.PointClass point(String n) {
		for (Project.PointClass p : project.points) if (p.name.equals(n)) return p;
		return null;
	}

	private Project.RegionClass region(String n) {
		for (Project.RegionClass r : project.regions) if (r.name.equals(n)) return r;
		return null;
	}

	/** All variables valid in this project, for the formula builder. */
	public static List<String> variables(Project project) {
		List<String> l = new ArrayList<>();
		for (Project.RegionClass r : project.regions) {
			l.add("{" + r.name + ":area}");
			l.add("{" + r.name + ":area_px}");
			for (Project.PointClass p : project.points) l.add("{" + r.name + ":" + p.name + "}");
		}
		if (!project.regions.isEmpty()) for (Project.PointClass p : project.points) l.add("{Outside:" + p.name + "}");
		for (Project.PointClass p : project.points) l.add("{Total:" + p.name + "}");
		return l;
	}

	/** Rewrites variable references after a class rename. */
	public static String rename(String expr, String oldName, String newName, boolean region) {
		StringBuilder b = new StringBuilder();
		int k = 0;
		while (k < expr.length()) {
			int s = expr.indexOf('{', k);
			if (s < 0) { b.append(expr.substring(k)); break; }
			int e = expr.indexOf('}', s);
			if (e < 0) { b.append(expr.substring(k)); break; }
			b.append(expr, k, s);
			String inner = expr.substring(s + 1, e);
			int c = inner.indexOf(':');
			if (c >= 0) {
				String a = inner.substring(0, c).trim(), v = inner.substring(c + 1).trim();
				if (region && a.equals(oldName)) a = newName;
				if (!region && v.equals(oldName)) v = newName;
				inner = a + ":" + v;
			}
			b.append('{').append(inner).append('}');
			k = e + 1;
		}
		return b.toString();
	}
}
