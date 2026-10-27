package advcounter;

import java.awt.Color;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Project definition: classes, formulas, settings and image list. Saved as JSON (.acproj). */
public class Project {
	public static final String EXT = ".acproj";
	public static final String NOT_STARTED = "not started", ANNOTATED = "annotated", VERIFIED = "verified",
			EXCLUDED = "excluded", MISSING = "missing";
	public static final String Z_STACK = "stack", Z_MAX = "max";
	public static final String[] MARKERS = {"Dot", "Circle", "Cross", "Crosshair", "Hybrid"};
	public static final String DEFAULT_EXTENSIONS = "tif,tiff,png,jpg,jpeg,bmp,gif,czi,nd2,lif,lsm,oib,oif,ims,vsi,svs,ndpi,scn,zvi,lei,ics,ids,dv,r3d,stk,lms,sld,qptiff,btf";

	public static class Cls {
		public String id, name;
		public Color color;
		public Cls(String id, String name, Color color) { this.id = id; this.name = name; this.color = color; }
	}

	public static class RegionClass extends Cls {
		public RegionClass(String id, String name, Color color) { super(id, name, color); }
	}

	public static class PointClass extends Cls {
		public String marker = "Dot";
		public int size = 2; // PointRoi size index 0..6
		public PointClass(String id, String name, Color color) { super(id, name, color); }
	}

	public static class FormulaDef {
		public String name, expression;
		public FormulaDef(String name, String expression) { this.name = name; this.expression = expression; }
	}

	public static class ImageEntry {
		public String id, path, seriesName = "";
		public int series = 0; // Bio-Formats importer series index (flattened)
		public int position = 1; // 1-based position index within the file
		public int positionsInFile = 1;
		public String status = NOT_STARTED;
		public Set<String> known = new LinkedHashSet<>(); // class ids that existed when the image was last reviewed
		public Map<String, Double> metrics = new LinkedHashMap<>();
		public String unit = "pixel";
		public double pixelWidth = 1, pixelHeight = 1;

		public String displayName() {
			String base = new File(path).getName();
			if (positionsInFile <= 1) return base;
			return base + " [" + (seriesName.isEmpty() ? "position " + position : seriesName) + "]";
		}

		public String key() { return id + "_" + sanitize(displayName()); }

		public boolean done() { return ANNOTATED.equals(status) || VERIFIED.equals(status); }
	}

	public File file;
	public String name = "Project";
	public String imageFolder = ".";
	public boolean recursive = false;
	public String extensions = DEFAULT_EXTENSIONS;
	public String zMode = Z_STACK;
	public boolean autoContrast = true, saveOverlayPng = true, saveRoiSet = true, showAllSlices = false;
	public String regionTool = "Selection Brush Tool";
	public int lastImage = 0, nextId = 1;
	public List<RegionClass> regions = new ArrayList<>();
	public List<PointClass> points = new ArrayList<>();
	public List<FormulaDef> formulas = new ArrayList<>();
	public List<ImageEntry> images = new ArrayList<>();

	public String newId(String prefix) { return prefix + (nextId++); }

	public File dir() { return file.getAbsoluteFile().getParentFile(); }

	public File imageDir() {
		File f = new File(imageFolder);
		return f.isAbsolute() ? f : new File(dir(), imageFolder);
	}

	public File imageFile(ImageEntry e) {
		File f = new File(e.path);
		return f.isAbsolute() ? f : new File(imageDir(), e.path);
	}

	public File annotationDir() { return new File(dir(), baseName() + "_annotations"); }

	public File annotationFile(ImageEntry e) { return new File(annotationDir(), e.key() + ".json"); }

	public File resultsFile() { return new File(dir(), baseName() + "_results.csv"); }

	public String baseName() {
		String n = file.getName();
		return n.endsWith(EXT) ? n.substring(0, n.length() - EXT.length()) : n;
	}

	public List<Cls> allClasses() {
		List<Cls> l = new ArrayList<>(regions);
		l.addAll(points);
		return l;
	}

	public Cls cls(String id) {
		for (Cls c : allClasses()) if (c.id.equals(id)) return c;
		return null;
	}

	public Cls clsByName(String name) {
		for (Cls c : allClasses()) if (c.name.equals(name)) return c;
		return null;
	}

	public Set<String> classIds() {
		Set<String> s = new LinkedHashSet<>();
		for (Cls c : allClasses()) s.add(c.id);
		return s;
	}

	/** Image was reviewed before some current class existed. */
	public boolean needsReview(ImageEntry e) {
		return e.done() && !e.known.containsAll(classIds());
	}

	public static String sanitize(String s) {
		String r = s.replaceAll("[^A-Za-z0-9._-]+", "_");
		return r.length() > 80 ? r.substring(0, 80) : r;
	}

	/** Validates a class name for use in formulas and CSV headers; returns an error or null. */
	public String checkClassName(String name, Cls self) {
		if (name == null || name.trim().isEmpty()) return "Name is empty";
		if (!name.equals(name.trim())) return "Name has leading/trailing spaces";
		if (name.matches(".*[{}:\",].*")) return "Name must not contain { } : \" or ,";
		for (String r : new String[]{"Total", "Outside", "area", "area_px"})
			if (name.equalsIgnoreCase(r)) return "'" + name + "' is reserved";
		for (Cls c : allClasses()) if (c != self && c.name.equalsIgnoreCase(name)) return "Name '" + name + "' already used";
		return null;
	}

	// ---------------------------------------------------------------- IO

	static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

	static Color color(String s, Color def) {
		try { return Color.decode(s); } catch (Exception e) { return def; }
	}

	public Map<String, Object> toJson() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("format", "AdvancedCounter project");
		m.put("version", 1.0);
		m.put("name", name);
		m.put("imageFolder", imageFolder);
		m.put("recursive", recursive);
		m.put("extensions", extensions);
		m.put("zMode", zMode);
		m.put("autoContrast", autoContrast);
		m.put("saveOverlayPng", saveOverlayPng);
		m.put("saveRoiSet", saveRoiSet);
		m.put("showAllSlices", showAllSlices);
		m.put("regionTool", regionTool);
		m.put("lastImage", (double) lastImage);
		m.put("nextId", (double) nextId);
		List<Object> rl = new ArrayList<>();
		for (RegionClass r : regions) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("id", r.id); o.put("name", r.name); o.put("color", hex(r.color));
			rl.add(o);
		}
		m.put("regions", rl);
		List<Object> pl = new ArrayList<>();
		for (PointClass p : points) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("id", p.id); o.put("name", p.name); o.put("color", hex(p.color));
			o.put("marker", p.marker); o.put("size", (double) p.size);
			pl.add(o);
		}
		m.put("points", pl);
		List<Object> fl = new ArrayList<>();
		for (FormulaDef f : formulas) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("name", f.name); o.put("expression", f.expression);
			fl.add(o);
		}
		m.put("formulas", fl);
		List<Object> il = new ArrayList<>();
		for (ImageEntry e : images) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("id", e.id); o.put("path", e.path);
			o.put("series", (double) e.series); o.put("position", (double) e.position);
			o.put("positionsInFile", (double) e.positionsInFile); o.put("seriesName", e.seriesName);
			o.put("status", e.status);
			o.put("known", new ArrayList<Object>(e.known));
			o.put("unit", e.unit); o.put("pixelWidth", e.pixelWidth); o.put("pixelHeight", e.pixelHeight);
			o.put("metrics", new LinkedHashMap<String, Object>(e.metrics));
			il.add(o);
		}
		m.put("images", il);
		return m;
	}

	public static Project fromJson(Map<String, Object> m, File file) {
		Project p = new Project();
		p.file = file;
		p.name = Json.str(m, "name", p.name);
		p.imageFolder = Json.str(m, "imageFolder", ".");
		p.recursive = Json.bool(m, "recursive", false);
		p.extensions = Json.str(m, "extensions", DEFAULT_EXTENSIONS);
		p.zMode = Json.str(m, "zMode", Z_STACK);
		p.autoContrast = Json.bool(m, "autoContrast", true);
		p.saveOverlayPng = Json.bool(m, "saveOverlayPng", true);
		p.saveRoiSet = Json.bool(m, "saveRoiSet", true);
		p.showAllSlices = Json.bool(m, "showAllSlices", false);
		p.regionTool = Json.str(m, "regionTool", p.regionTool);
		p.lastImage = (int) Json.num(m, "lastImage", 0);
		p.nextId = (int) Json.num(m, "nextId", 1);
		for (Object o : Json.list(m, "regions")) {
			Map<String, Object> r = Json.map(o);
			p.regions.add(new RegionClass(Json.str(r, "id", ""), Json.str(r, "name", ""), color(Json.str(r, "color", ""), Color.yellow)));
		}
		for (Object o : Json.list(m, "points")) {
			Map<String, Object> r = Json.map(o);
			PointClass pc = new PointClass(Json.str(r, "id", ""), Json.str(r, "name", ""), color(Json.str(r, "color", ""), Color.red));
			pc.marker = Json.str(r, "marker", "Dot");
			pc.size = (int) Json.num(r, "size", 2);
			p.points.add(pc);
		}
		for (Object o : Json.list(m, "formulas")) {
			Map<String, Object> r = Json.map(o);
			p.formulas.add(new FormulaDef(Json.str(r, "name", ""), Json.str(r, "expression", "")));
		}
		for (Object o : Json.list(m, "images")) {
			Map<String, Object> r = Json.map(o);
			ImageEntry e = new ImageEntry();
			e.id = Json.str(r, "id", "");
			e.path = Json.str(r, "path", "");
			e.series = (int) Json.num(r, "series", 0);
			e.position = (int) Json.num(r, "position", 1);
			e.positionsInFile = (int) Json.num(r, "positionsInFile", 1);
			e.seriesName = Json.str(r, "seriesName", "");
			e.status = Json.str(r, "status", NOT_STARTED);
			for (Object k : Json.list(r, "known")) e.known.add(k.toString());
			e.unit = Json.str(r, "unit", "pixel");
			e.pixelWidth = Json.num(r, "pixelWidth", 1);
			e.pixelHeight = Json.num(r, "pixelHeight", 1);
			for (Map.Entry<String, Object> me : Json.map(r.get("metrics")).entrySet())
				if (me.getValue() instanceof Number) e.metrics.put(me.getKey(), ((Number) me.getValue()).doubleValue());
			p.images.add(e);
		}
		return p;
	}

	public static Project load(File f) throws IOException {
		String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		return fromJson(Json.map(Json.parse(s)), f);
	}

	public void save() throws IOException { writeAtomic(file, Json.write(toJson())); }

	public static void writeAtomic(File f, String text) throws IOException {
		File dir = f.getAbsoluteFile().getParentFile();
		if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
		File tmp = new File(dir, f.getName() + ".tmp");
		Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
		try {
			Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException ex) {
			Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Path of f relative to base if f is inside base, otherwise absolute. */
	public static String relativize(File base, File f) {
		try {
			Path b = base.getCanonicalFile().toPath(), p = f.getCanonicalFile().toPath();
			if (p.startsWith(b)) {
				String r = b.relativize(p).toString().replace(File.separatorChar, '/');
				return r.isEmpty() ? "." : r;
			}
			return p.toString();
		} catch (IOException e) {
			return f.getAbsolutePath();
		}
	}

	private static final Color[] PALETTE = {new Color(0x00, 0xc8, 0xff), new Color(0xff, 0xc8, 0x00), new Color(0xff, 0x40, 0xff),
			new Color(0x40, 0xff, 0x40), new Color(0xff, 0x50, 0x50), new Color(0x80, 0x80, 0xff), new Color(0xff, 0xff, 0xff), new Color(0xff, 0x90, 0x30)};

	public static Color paletteColor(int i) { return PALETTE[i % PALETTE.length]; }
}
