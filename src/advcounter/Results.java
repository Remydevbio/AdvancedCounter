package advcounter;

import java.io.IOException;
import java.util.*;

/** Builds the master results table (one row per image) from cached per-image metrics. */
public final class Results {
	private Results() {}

	public static final String NA = "NA";

	public static List<String> header(Project p) {
		List<String> h = new ArrayList<>(Arrays.asList("image", "file", "position", "position_name", "status", "needs_review", "area_unit"));
		for (Project.RegionClass r : p.regions) {
			h.add(r.name + "_area");
			h.add(r.name + "_area_px");
			for (Project.PointClass c : p.points) h.add(r.name + "_" + c.name);
		}
		if (!p.regions.isEmpty()) for (Project.PointClass c : p.points) h.add("Outside_" + c.name);
		for (Project.PointClass c : p.points) h.add("Total_" + c.name);
		for (Project.FormulaDef f : p.formulas) h.add(f.name);
		return h;
	}

	static String fmt(Double v) {
		if (v == null || v.isNaN() || v.isInfinite()) return NA;
		if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString(v.longValue());
		String s = String.format(Locale.ROOT, "%.6g", v);
		if (s.contains("e")) return s;
		if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		return s;
	}

	static Double metric(Project.ImageEntry e, String key, double scale, String... ids) {
		if (!e.done()) return null;
		for (String id : ids) if (!e.known.contains(id)) return null;
		Double v = e.metrics.get(key);
		return v == null ? null : v * scale;
	}

	public static List<String> row(Project p, Project.ImageEntry e, Map<String, Formula> compiled) {
		List<String> r = new ArrayList<>();
		r.add(e.displayName());
		r.add(e.path);
		r.add(Integer.toString(e.position));
		r.add(e.seriesName);
		r.add(e.status);
		r.add(p.needsReview(e) ? "yes" : "no");
		r.add(e.unit);
		double cal = e.pixelWidth * e.pixelHeight;
		for (Project.RegionClass rc : p.regions) {
			r.add(fmt(metric(e, "area:" + rc.id, cal, rc.id)));
			r.add(fmt(metric(e, "area:" + rc.id, 1, rc.id)));
			for (Project.PointClass c : p.points) r.add(fmt(metric(e, "count:" + rc.id + ":" + c.id, 1, rc.id, c.id)));
		}
		if (!p.regions.isEmpty()) for (Project.PointClass c : p.points) r.add(fmt(metric(e, "count:outside:" + c.id, 1, c.id)));
		for (Project.PointClass c : p.points) r.add(fmt(metric(e, "count:total:" + c.id, 1, c.id)));
		for (Project.FormulaDef f : p.formulas) {
			Formula fo = compiled.get(f.name);
			r.add(fo == null ? NA : fmt(fo.eval(e)));
		}
		return r;
	}

	/** Compiles all formulas; invalid ones map to null (their column is NA) and their errors are returned. */
	public static Map<String, Formula> compile(Project p, List<String> errors) {
		Map<String, Formula> m = new LinkedHashMap<>();
		for (Project.FormulaDef f : p.formulas) {
			try { m.put(f.name, Formula.parse(f.expression, p)); }
			catch (IllegalArgumentException ex) {
				m.put(f.name, null);
				if (errors != null) errors.add(f.name + ": " + ex.getMessage());
			}
		}
		return m;
	}

	public static String csv(Project p) {
		Map<String, Formula> compiled = compile(p, null);
		StringBuilder b = new StringBuilder();
		line(b, header(p));
		for (Project.ImageEntry e : p.images) line(b, row(p, e, compiled));
		return b.toString();
	}

	static void line(StringBuilder b, List<String> cells) {
		for (int k = 0; k < cells.size(); k++) {
			if (k > 0) b.append(',');
			String c = cells.get(k);
			if (c.matches(".*[\",\\n\\r].*") || c.startsWith(" ") || c.endsWith(" ")) b.append('"').append(c.replace("\"", "\"\"")).append('"');
			else b.append(c);
		}
		b.append('\n');
	}

	public static void write(Project p) throws IOException { Project.writeAtomic(p.resultsFile(), csv(p)); }
}
