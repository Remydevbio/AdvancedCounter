package advcounter;

import ij.gui.*;
import ij.io.RoiEncoder;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.*;
import java.awt.geom.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.zip.*;

/** Annotations of one image: pixel-aligned, mutually exclusive region areas plus classified points. */
public class Annotation {
	public static class Pt {
		public String cls;
		public int x, y, z, t; // pixel indices; z,t 1-based
		public Pt(String cls, int x, int y, int z, int t) { this.cls = cls; this.x = x; this.y = y; this.z = z; this.t = t; }
		Pt copy() { return new Pt(cls, x, y, z, t); }
	}

	public int width, height;
	public final Map<String, Area> regions = new LinkedHashMap<>();
	public final List<Pt> points = new ArrayList<>();
	/** Pixel counts of stored areas; stored Areas are replaced, never mutated, so identity is a safe key. */
	private final Map<Area, Long> countCache = new IdentityHashMap<>();

	public Annotation(int width, int height) { this.width = width; this.height = height; }

	public Annotation copy() {
		Annotation a = new Annotation(width, height);
		for (Map.Entry<String, Area> e : regions.entrySet()) a.regions.put(e.getKey(), new Area(e.getValue()));
		for (Pt p : points) a.points.add(p.copy());
		return a;
	}

	// ---------------------------------------------------------------- geometry

	/** Converts an area ROI into a pixel-aligned Area (pixel centers inside the ROI), clipped to the image. */
	public static Area pixelArea(Roi roi, int width, int height) {
		if (roi == null || !roi.isArea()) return new Area();
		Rectangle b = roi.getBounds().intersection(new Rectangle(0, 0, width, height));
		if (b.isEmpty()) return new Area();
		ImageProcessor mask = roi.getMask();
		Rectangle rb = roi.getBounds();
		ByteProcessor bp = new ByteProcessor(b.width, b.height);
		byte[] px = (byte[]) bp.getPixels();
		int n = 0;
		for (int y = 0; y < b.height; y++)
			for (int x = 0; x < b.width; x++) {
				int mx = b.x + x - rb.x, my = b.y + y - rb.y;
				boolean in = mask == null || mask.get(mx, my) != 0;
				if (in) { px[y * b.width + x] = (byte) 255; n++; }
			}
		if (n == 0) return new Area();
		bp.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
		Roi traced = new ThresholdToSelection().convert(bp);
		if (traced == null) return new Area();
		Area a = toArea(traced);
		a.transform(AffineTransform.getTranslateInstance(b.x, b.y));
		return a;
	}

	public static Area toArea(Roi roi) {
		if (roi instanceof ShapeRoi) {
			Shape s = ((ShapeRoi) roi).getShape();
			Area a = new Area(s);
			Rectangle2D.Double r = roi.getFloatBounds();
			a.transform(AffineTransform.getTranslateInstance(r.x, r.y));
			return a;
		}
		Polygon p = roi.getPolygon();
		return new Area(p);
	}

	public static Roi toRoi(Area a) {
		if (a == null || a.isEmpty()) return null;
		return new ShapeRoi(a);
	}

	public static long pixelCount(Area a) {
		if (a == null || a.isEmpty()) return 0;
		// regions are pixel-aligned, so the ImageJ mask (pixel centers inside) is exact
		Roi roi = new ShapeRoi(a);
		ImageProcessor mask = roi.getMask();
		Rectangle b = roi.getBounds();
		if (mask == null) return (long) b.width * b.height;
		byte[] px = (byte[]) mask.getPixels();
		long n = 0;
		for (byte v : px) if (v != 0) n++;
		return n;
	}

	public static boolean containsPixel(Area a, int x, int y) { return a != null && a.contains(x + 0.5, y + 0.5); }

	/**
	 * Sets region cls from the given selection: rasterized to pixels, clipped to the image and with all
	 * other regions subtracted (existing regions win). Returns the stored area.
	 */
	public Area setRegion(String cls, Roi selection) {
		Area a = pixelArea(selection, width, height);
		for (Map.Entry<String, Area> e : regions.entrySet())
			if (!e.getKey().equals(cls)) a.subtract(e.getValue());
		if (a.isEmpty()) regions.remove(cls);
		else regions.put(cls, a);
		return a;
	}

	public String regionAt(int x, int y) {
		for (Map.Entry<String, Area> e : regions.entrySet()) if (containsPixel(e.getValue(), x, y)) return e.getKey();
		return null;
	}

	/** Removes data that belongs to classes no longer in the project. */
	public void retain(Set<String> classIds) {
		regions.keySet().retainAll(classIds);
		points.removeIf(p -> !classIds.contains(p.cls));
	}

	// ---------------------------------------------------------------- metrics

	/** Keys: area:R (pixels), count:R:P, count:outside:P, count:total:P. */
	public Map<String, Double> metrics(Project project) {
		Map<String, Double> m = new LinkedHashMap<>();
		for (Project.RegionClass r : project.regions) {
			Area ra = regions.get(r.id);
			Long px = ra == null ? Long.valueOf(0) : countCache.get(ra);
			if (px == null) {
				px = pixelCount(ra);
				if (countCache.size() > 64) countCache.clear(); // drop counts of replaced areas
				countCache.put(ra, px);
			}
			m.put("area:" + r.id, (double) px);
			for (Project.PointClass p : project.points) m.put("count:" + r.id + ":" + p.id, 0.0);
		}
		for (Project.PointClass p : project.points) {
			m.put("count:outside:" + p.id, 0.0);
			m.put("count:total:" + p.id, 0.0);
		}
		for (Pt pt : points) {
			if (!m.containsKey("count:total:" + pt.cls)) continue;
			m.merge("count:total:" + pt.cls, 1.0, Double::sum);
			String r = regionAt(pt.x, pt.y);
			String k = r == null ? "count:outside:" + pt.cls : "count:" + r + ":" + pt.cls;
			if (m.containsKey(k)) m.merge(k, 1.0, Double::sum);
			else m.merge("count:outside:" + pt.cls, 1.0, Double::sum);
		}
		return m;
	}

	// ---------------------------------------------------------------- IO

	static List<Object> pathToJson(Area a) {
		List<Object> subpaths = new ArrayList<>();
		List<Object> cur = null;
		double[] c = new double[6];
		for (PathIterator it = a.getPathIterator(null); !it.isDone(); it.next()) {
			int type = it.currentSegment(c);
			if (type == PathIterator.SEG_MOVETO) { cur = new ArrayList<>(); subpaths.add(cur); }
			if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) { cur.add(c[0]); cur.add(c[1]); }
			else if (type == PathIterator.SEG_QUADTO) { cur.add(c[2]); cur.add(c[3]); }
			else if (type == PathIterator.SEG_CUBICTO) { cur.add(c[4]); cur.add(c[5]); }
		}
		return subpaths;
	}

	static Area pathFromJson(List<Object> subpaths) {
		Path2D.Double p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
		for (Object o : subpaths) {
			@SuppressWarnings("unchecked") List<Object> l = (List<Object>) o;
			for (int k = 0; k + 1 < l.size(); k += 2) {
				double x = ((Number) l.get(k)).doubleValue(), y = ((Number) l.get(k + 1)).doubleValue();
				if (k == 0) p.moveTo(x, y); else p.lineTo(x, y);
			}
			p.closePath();
		}
		return new Area(p);
	}

	public Map<String, Object> toJson(Project project, Project.ImageEntry e) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("format", "AdvancedCounter annotation");
		m.put("image", e.displayName());
		m.put("width", (double) width);
		m.put("height", (double) height);
		Map<String, Object> rs = new LinkedHashMap<>();
		for (Map.Entry<String, Area> r : regions.entrySet()) {
			Map<String, Object> o = new LinkedHashMap<>();
			Project.Cls c = project.cls(r.getKey());
			o.put("name", c == null ? "" : c.name);
			o.put("paths", pathToJson(r.getValue()));
			rs.put(r.getKey(), o);
		}
		m.put("regions", rs);
		List<Object> pl = new ArrayList<>();
		for (Pt p : points) {
			List<Object> o = new ArrayList<>();
			o.add(p.cls); o.add((double) p.x); o.add((double) p.y); o.add((double) p.z); o.add((double) p.t);
			pl.add(o);
		}
		m.put("points", pl);
		return m;
	}

	public static Annotation fromJson(Map<String, Object> m) {
		Annotation a = new Annotation((int) Json.num(m, "width", 0), (int) Json.num(m, "height", 0));
		for (Map.Entry<String, Object> r : Json.map(m.get("regions")).entrySet()) {
			Area area = pathFromJson(Json.list(Json.map(r.getValue()), "paths"));
			if (!area.isEmpty()) a.regions.put(r.getKey(), area);
		}
		for (Object o : Json.list(m, "points")) {
			@SuppressWarnings("unchecked") List<Object> l = (List<Object>) o;
			a.points.add(new Pt(l.get(0).toString(), ((Number) l.get(1)).intValue(), ((Number) l.get(2)).intValue(),
					((Number) l.get(3)).intValue(), ((Number) l.get(4)).intValue()));
		}
		return a;
	}

	public static Annotation load(File f) throws IOException {
		return fromJson(Json.map(Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))));
	}

	public void save(Project project, Project.ImageEntry e) throws IOException {
		Project.writeAtomic(project.annotationFile(e), Json.write(toJson(project, e)));
	}

	public static int pointType(String marker) {
		switch (marker) {
			case "Circle": return PointRoi.CIRCLE;
			case "Cross": return PointRoi.CROSS;
			case "Crosshair": return PointRoi.CROSSHAIR;
			case "Hybrid": return PointRoi.HYBRID;
			default: return PointRoi.DOT;
		}
	}

	/** ImageJ ROIs (regions as ShapeRoi, points per class and plane as PointRoi), usable without the plugin. */
	public List<Roi> imagejRois(Project project, boolean stack) {
		List<Roi> rois = new ArrayList<>();
		for (Project.RegionClass r : project.regions) {
			Roi roi = toRoi(regions.get(r.id));
			if (roi == null) continue;
			roi.setName(r.name);
			roi.setStrokeColor(r.color);
			rois.add(roi);
		}
		for (Project.PointClass pc : project.points) {
			Map<String, PointRoi> byPlane = new LinkedHashMap<>();
			for (Pt p : points) {
				if (!p.cls.equals(pc.id)) continue;
				String k = p.z + ":" + p.t;
				PointRoi pr = byPlane.get(k);
				if (pr == null) {
					pr = new PointRoi(p.x, p.y);
					byPlane.put(k, pr);
					if (stack) pr.setPosition(0, p.z, p.t);
				} else pr.addPoint(p.x, p.y);
			}
			for (Map.Entry<String, PointRoi> e : byPlane.entrySet()) {
				PointRoi pr = e.getValue();
				pr.setName(stack ? pc.name + " z" + e.getKey().replace(":", " t") : pc.name);
				pr.setStrokeColor(pc.color);
				pr.setPointType(pointType(pc.marker));
				rois.add(pr);
			}
		}
		return rois;
	}

	public void saveRoiSet(File f, Project project, boolean stack) throws IOException {
		File tmp = new File(f.getPath() + ".tmp");
		Set<String> used = new HashSet<>();
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
			for (Roi roi : imagejRois(project, stack)) {
				String n = Project.sanitize(roi.getName());
				String k = n;
				for (int i = 2; !used.add(k); i++) k = n + "-" + i;
				zos.putNextEntry(new ZipEntry(k + ".roi"));
				zos.write(RoiEncoder.saveAsByteArray(roi));
				zos.closeEntry();
			}
		}
		Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
