import advcounter.*;
import ij.*;
import ij.gui.*;
import ij.io.RoiDecoder;
import ij.process.*;
import loci.common.services.ServiceFactory;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.out.OMETiffWriter;
import loci.formats.services.OMEXMLService;
import ome.units.UNITS;
import ome.units.quantity.Length;

import java.awt.*;
import java.awt.geom.Area;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.ZipFile;

/** Headless tests of geometry, metrics, persistence, formulas, CSV and image loading. */
public class CoreTest {
	static int passed, failed;
	static final File DATA = new File("build/test-data");

	static void ok(boolean b, String m) {
		if (b) { passed++; System.out.println("PASS " + m); }
		else { failed++; System.out.println("FAIL " + m); }
	}

	static void eq(Object a, Object b, String m) { ok(Objects.equals(a, b), m + " (got " + a + ", expected " + b + ")"); }

	public static void main(String[] args) throws Exception {
		json();
		geometry();
		persistence();
		formulas();
		results();
		images();
		System.out.println("Completed: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
		System.exit(0);
	}

	static Project project() {
		Project p = new Project();
		p.regions.add(new Project.RegionClass("r1", "Tumor", Color.cyan));
		p.regions.add(new Project.RegionClass("r2", "Stroma", Color.yellow));
		p.points.add(new Project.PointClass("p3", "GFP+", Color.green));
		p.points.add(new Project.PointClass("p4", "GFP-", Color.red));
		p.nextId = 5;
		return p;
	}

	static void json() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("s", "a \"q\" \\ \n é");
		m.put("n", 3.25);
		m.put("i", 42.0);
		m.put("b", true);
		m.put("l", Arrays.asList(1.0, "x", null));
		Object back = Json.parse(Json.write(m));
		eq(back, m, "JSON round trip");
	}

	static long maskCount(Roi r) {
		ImageProcessor mask = r.getMask();
		if (mask == null) return (long) r.getBounds().width * r.getBounds().height;
		long n = 0;
		for (byte v : (byte[]) mask.getPixels()) if (v != 0) n++;
		return n;
	}

	static void geometry() {
		Project p = project();
		Annotation a = new Annotation(200, 150);
		a.setRegion("r1", new Roi(10, 10, 50, 50));
		Area r2 = a.setRegion("r2", new Roi(40, 40, 50, 50));
		eq(Annotation.pixelCount(a.regions.get("r1")), 2500L, "rectangle region area");
		eq(Annotation.pixelCount(r2), 2100L, "second region excludes overlap with first (existing wins)");
		Area inter = new Area(a.regions.get("r1"));
		inter.intersect(a.regions.get("r2"));
		ok(inter.isEmpty(), "regions share no area");
		// redrawing region 1 over region 2 leaves region 2 intact
		a.setRegion("r1", new Roi(10, 10, 70, 70));
		eq(Annotation.pixelCount(a.regions.get("r2")), 2100L, "editing first region keeps second region");
		eq(Annotation.pixelCount(a.regions.get("r1")), 3700L, "extended first region loses the part owned by second");
		// pixel-exact rasterization of curved / polygon ROIs equals ImageJ mask count
		OvalRoi oval = new OvalRoi(100.3, 20.7, 61.2, 41.9);
		eq(Annotation.pixelCount(Annotation.pixelArea(oval, 200, 150)), maskCount(oval), "oval area equals ImageJ mask pixel count");
		PolygonRoi poly = new PolygonRoi(new float[]{120, 190, 170, 130.5f}, new float[]{80, 90, 140, 130.2f}, Roi.POLYGON);
		eq(Annotation.pixelCount(Annotation.pixelArea(poly, 200, 150)), maskCount(poly), "polygon area equals ImageJ mask pixel count");
		// clipping to the image
		eq(Annotation.pixelCount(Annotation.pixelArea(new Roi(-10, -10, 20, 20), 200, 150)), 100L, "region clipped to image bounds");
		// composite ROI with hole (as produced by brush tools)
		ShapeRoi ring = new ShapeRoi(new Roi(0, 100, 40, 40)).not(new ShapeRoi(new Roi(10, 110, 20, 20)));
		Annotation b = new Annotation(200, 150);
		b.setRegion("r1", ring);
		eq(Annotation.pixelCount(b.regions.get("r1")), 1600L - 400L, "region with hole");
		ok(!Annotation.containsPixel(b.regions.get("r1"), 20, 120), "hole pixel not in region");
		ok(Annotation.containsPixel(b.regions.get("r1"), 5, 120), "ring pixel in region");

		// metrics
		a.points.add(new Annotation.Pt("p3", 15, 15, 1, 1)); // Tumor
		a.points.add(new Annotation.Pt("p3", 50, 50, 1, 1)); // overlap zone -> Tumor (owned by r1)
		a.points.add(new Annotation.Pt("p4", 85, 85, 1, 1)); // Stroma
		a.points.add(new Annotation.Pt("p4", 195, 5, 1, 1)); // outside
		a.points.add(new Annotation.Pt("p3", 59, 59, 1, 1)); // Tumor edge pixel
		a.points.add(new Annotation.Pt("p3", 80, 80, 1, 1)); // Stroma (r1 ends at 79)
		Map<String, Double> m = a.metrics(p);
		eq(m.get("count:r1:p3"), 3.0, "GFP+ in Tumor");
		eq(m.get("count:r2:p3"), 1.0, "GFP+ in Stroma");
		eq(m.get("count:r2:p4"), 1.0, "GFP- in Stroma");
		eq(m.get("count:outside:p4"), 1.0, "GFP- outside");
		eq(m.get("count:total:p3"), 4.0, "GFP+ total");
		eq(m.get("area:r1"), 3700.0, "metric area Tumor");
		// retain removes deleted classes
		Annotation c = a.copy();
		c.retain(new HashSet<>(Arrays.asList("r1", "p3")));
		ok(!c.regions.containsKey("r2") && c.points.stream().allMatch(q -> q.cls.equals("p3")), "retain drops deleted classes");
		ok(a.regions.containsKey("r2"), "copy is independent");
	}

	static void persistence() throws Exception {
		Project p = project();
		DATA.mkdirs();
		p.file = new File(DATA, "persist/test" + Project.EXT);
		p.imageFolder = ".";
		Project.ImageEntry e = new Project.ImageEntry();
		e.id = "img1"; e.path = "a b.tif"; e.status = Project.ANNOTATED;
		e.known.addAll(p.classIds());
		p.images.add(e);
		p.formulas.add(new Project.FormulaDef("density", "{Tumor:GFP+} / {Tumor:area}"));
		Annotation a = new Annotation(100, 80);
		a.setRegion("r1", new ShapeRoi(new OvalRoi(5, 5, 50, 40)).or(new ShapeRoi(new Roi(70, 10, 20, 20))));
		a.setRegion("r2", new Roi(0, 0, 100, 80));
		a.points.add(new Annotation.Pt("p3", 20, 20, 2, 1));
		e.metrics = a.metrics(p);
		a.save(p, e);
		p.save();
		Annotation b = Annotation.load(p.annotationFile(e));
		eq(Annotation.pixelCount(b.regions.get("r1")), Annotation.pixelCount(a.regions.get("r1")), "annotation reload keeps region 1 area");
		eq(Annotation.pixelCount(b.regions.get("r2")), Annotation.pixelCount(a.regions.get("r2")), "annotation reload keeps region 2 (with cut-out) area");
		eq(Annotation.pixelCount(b.regions.get("r2")), 8000L - Annotation.pixelCount(a.regions.get("r1")), "region 2 = image minus region 1");
		eq(b.points.size(), 1, "points reloaded");
		eq(b.points.get(0).z, 2, "point z reloaded");
		Project q = Project.load(p.file);
		eq(q.regions.size(), 2, "project regions reloaded");
		eq(q.points.get(0).name, "GFP+", "project counter name reloaded");
		eq(q.images.get(0).metrics, e.metrics, "image metrics reloaded");
		eq(q.images.get(0).known, e.known, "known classes reloaded");
		eq(q.formulas.get(0).expression, "{Tumor:GFP+} / {Tumor:area}", "formula reloaded");
		// RoiSet readable by plain ImageJ
		File zip = new File(p.annotationDir(), e.key() + "_RoiSet.zip");
		a.saveRoiSet(zip, p, true);
		try (ZipFile z = new ZipFile(zip)) {
			eq(z.size(), 3, "RoiSet has 2 regions + 1 point ROI");
			Roi r = RoiDecoder.openFromByteArray(z.getInputStream(z.getEntry("Tumor.roi")).readAllBytes());
			eq(r.getName(), "Tumor", "RoiSet region name");
			eq(maskCount(r), Annotation.pixelCount(a.regions.get("r1")), "RoiSet region area identical");
		}
	}

	static void formulas() {
		Project p = project();
		Project.ImageEntry e = new Project.ImageEntry();
		e.status = Project.ANNOTATED;
		e.known.addAll(p.classIds());
		e.pixelWidth = 0.5; e.pixelHeight = 0.5;
		e.metrics.put("area:r1", 400.0);
		e.metrics.put("count:r1:p3", 10.0);
		e.metrics.put("count:r1:p4", 30.0);
		e.metrics.put("count:total:p3", 12.0);
		e.metrics.put("area:r2", 0.0);
		eq(Formula.parse("{Tumor:GFP+} / ({Tumor:GFP+} + {Tumor:GFP-})", p).eval(e), 0.25, "fraction formula");
		eq(Formula.parse("{Tumor:GFP+}/{Tumor:area}", p).eval(e), 0.1, "density uses calibrated area (400 px * 0.25)");
		eq(Formula.parse("{Tumor:GFP+}/{Tumor:area_px}*1e3", p).eval(e), 25.0, "pixel area and exponent number");
		eq(Formula.parse("-{Total:GFP+} + 2*3", p).eval(e), -6.0, "unary minus and precedence");
		eq(Formula.parse("{Tumor:GFP+}/{Stroma:area}", p).eval(e), null, "division by zero gives NA");
		for (String bad : new String[]{"{Liver:area}", "{Tumor:CD8}", "{Tumor}", "1 +", "(1", "Tumor/2", "{Outside:Tumor}"}) {
			try { Formula.parse(bad, p); ok(false, "rejects " + bad); }
			catch (IllegalArgumentException ex) { ok(true, "rejects '" + bad + "': " + ex.getMessage()); }
		}
		Project.ImageEntry old = new Project.ImageEntry();
		old.status = Project.ANNOTATED;
		old.known.addAll(Arrays.asList("r1", "p3"));
		old.metrics.putAll(e.metrics);
		eq(Formula.parse("{Tumor:GFP+}/{Tumor:area_px}", p).eval(old), 0.025, "formula on known classes works");
		eq(Formula.parse("{Tumor:GFP-}/{Tumor:area_px}", p).eval(old), null, "formula with class added later is NA");
		Project.ImageEntry ns = new Project.ImageEntry();
		eq(Formula.parse("1+1", p).eval(ns), 2.0, "constant formula");
		eq(Formula.parse("{Total:GFP+}", p).eval(ns), null, "not-started image gives NA");
		eq(Formula.rename("{Tumor:GFP+}/{Tumor:area}+{Stroma:GFP+}", "Tumor", "Cancer", true), "{Cancer:GFP+}/{Cancer:area}+{Stroma:GFP+}", "region rename in formula");
		eq(Formula.rename("{Tumor:GFP+}/{Total:GFP+}", "GFP+", "Pos", false), "{Tumor:Pos}/{Total:Pos}", "counter rename in formula");
		ok(Formula.variables(p).contains("{Stroma:GFP-}") && Formula.variables(p).contains("{Outside:GFP+}"), "variable list for builder");
		eq(p.checkClassName("Total", null) != null, true, "reserved name rejected");
		eq(p.checkClassName("a:b", null) != null, true, "colon in name rejected");
		eq(p.checkClassName("gfp+", null) != null, true, "duplicate name (case-insensitive) rejected");
	}

	static void results() {
		Project p = project();
		p.file = new File(DATA, "res/r" + Project.EXT);
		p.formulas.add(new Project.FormulaDef("pos_fraction", "{Tumor:GFP+}/({Tumor:GFP+}+{Tumor:GFP-})"));
		Project.ImageEntry a = new Project.ImageEntry();
		a.id = "img1"; a.path = "x.tif"; a.status = Project.VERIFIED; a.unit = "µm"; a.pixelWidth = 2; a.pixelHeight = 2;
		a.known.addAll(p.classIds());
		Annotation an = new Annotation(100, 100);
		an.setRegion("r1", new Roi(0, 0, 10, 10));
		an.points.add(new Annotation.Pt("p3", 1, 1, 1, 1));
		an.points.add(new Annotation.Pt("p4", 2, 2, 1, 1));
		an.points.add(new Annotation.Pt("p4", 3, 3, 1, 1));
		an.points.add(new Annotation.Pt("p4", 50, 50, 1, 1));
		a.metrics = an.metrics(p);
		Project.ImageEntry b = new Project.ImageEntry();
		b.id = "img2"; b.path = "y.czi"; b.positionsInFile = 2; b.position = 2; b.seriesName = "Scene 2";
		p.images.add(a); p.images.add(b);
		List<String> h = Results.header(p);
		eq(h.subList(7, h.size()), Arrays.asList("Tumor_area", "Tumor_area_px", "Tumor_GFP+", "Tumor_GFP-", "Stroma_area", "Stroma_area_px", "Stroma_GFP+", "Stroma_GFP-",
				"Outside_GFP+", "Outside_GFP-", "Total_GFP+", "Total_GFP-", "pos_fraction"), "CSV header layout");
		Map<String, Formula> c = Results.compile(p, null);
		List<String> r = Results.row(p, a, c);
		Map<String, String> row = new HashMap<>();
		for (int i = 0; i < h.size(); i++) row.put(h.get(i), r.get(i));
		eq(row.get("Tumor_area"), "400", "calibrated area");
		eq(row.get("Tumor_area_px"), "100", "pixel area");
		eq(row.get("Tumor_GFP-"), "2", "count in region");
		eq(row.get("Outside_GFP-"), "1", "count outside");
		eq(row.get("Stroma_area"), "0", "empty region area 0");
		eq(row.get("pos_fraction"), "0.333333", "formula column");
		eq(row.get("area_unit"), "µm", "unit column");
		List<String> r2 = Results.row(p, b, c);
		eq(r2.get(0), "y.czi [Scene 2]", "multi-position display name");
		ok(r2.subList(7, r2.size()).stream().allMatch(Results.NA::equals), "not-started row is all NA");
		// adding a counter later: NA for old image, needs review
		p.points.add(new Project.PointClass("p5", "CD8", Color.blue));
		p.formulas.add(new Project.FormulaDef("cd8_density", "{Tumor:CD8}/{Tumor:area}"));
		h = Results.header(p);
		r = Results.row(p, a, Results.compile(p, null));
		row.clear();
		for (int i = 0; i < h.size(); i++) row.put(h.get(i), r.get(i));
		eq(row.get("Tumor_CD8"), "NA", "new counter NA on earlier image");
		eq(row.get("cd8_density"), "NA", "formula with new counter NA on earlier image");
		eq(row.get("pos_fraction"), "0.333333", "added formula spread to existing results");
		eq(row.get("needs_review"), "yes", "earlier image flagged for review");
		String csv = Results.csv(p);
		eq(csv.split("\n").length, 3, "CSV has header + one row per image");
	}

	// ---------------------------------------------------------------- image loading

	static void writeOmeTiff(File f, int series, int sizeC, int sizeZ, String namePrefix) throws Exception {
		IMetadata meta = new ServiceFactory().getInstance(OMEXMLService.class).createOMEXMLMetadata();
		for (int s = 0; s < series; s++) {
			MetadataTools.populateMetadata(meta, s, namePrefix + (s + 1), false, "XYCZT", "uint16", 64, 48, sizeZ, sizeC, 1, 1);
			meta.setPixelsPhysicalSizeX(new Length(0.65, UNITS.MICROMETER), s);
			meta.setPixelsPhysicalSizeY(new Length(0.65, UNITS.MICROMETER), s);
		}
		f.delete();
		OMETiffWriter w = new OMETiffWriter();
		w.setMetadataRetrieve(meta);
		w.setId(f.getPath());
		for (int s = 0; s < series; s++) {
			w.setSeries(s);
			for (int pl = 0; pl < sizeC * sizeZ; pl++) {
				byte[] b = new byte[64 * 48 * 2];
				for (int i = 0; i < 64 * 48; i++) { int v = (s + 1) * 1000 + pl * 100 + (i % 64); b[2 * i] = (byte) (v >> 8); b[2 * i + 1] = (byte) v; }
				w.saveBytes(pl, b);
			}
		}
		w.close();
	}

	static void images() throws Exception {
		File dir = new File(DATA, "images");
		if (dir.exists()) for (File f : dir.listFiles()) if (f.isFile()) f.delete();
		dir.mkdirs();
		ImagePlus g = IJ.createImage("g", "8-bit ramp", 80, 60, 1);
		IJ.saveAsTiff(g, new File(dir, "img2.tif").getPath());
		IJ.saveAsTiff(IJ.createImage("rgb", "RGB ramp", 80, 60, 1), new File(dir, "img10.tif").getPath());
		new ij.io.FileSaver(IJ.createImage("png", "8-bit ramp", 30, 20, 1)).saveAsPng(new File(dir, "img1.png").getPath());
		ImagePlus hs = IJ.createHyperStack("hs", 70, 50, 3, 4, 1, 16);
		for (int i = 1; i <= hs.getStackSize(); i++) {
			ImageProcessor ip = hs.getStack().getProcessor(i);
			for (int y = 0; y < 50; y++) for (int x = 0; x < 70; x++) ip.set(x, y, i * 500 + (x == 5 && y == 5 ? 0 : x * (1 + i % 3) * 7));
		}
		IJ.saveAsTiff(hs, new File(dir, "stack.tif").getPath());
		writeOmeTiff(new File(dir, "multipos.ome.tif"), 3, 2, 1, "Scene ");
		Files.write(new File(dir, "notes.txt").toPath(), "x".getBytes());
		Project p = project();
		p.file = new File(DATA, "images/proj" + Project.EXT);
		List<String> errors = new ArrayList<>();
		int added = ImageLoader.scan(p, errors);
		eq(errors, new ArrayList<String>(), "no scan errors");
		eq(added, 7, "4 single images + 3 positions of the multi-position file");
		List<String> names = new ArrayList<>();
		for (Project.ImageEntry e : p.images) names.add(e.displayName());
		System.out.println("  scanned: " + names);
		eq(names.get(0), "img1.png", "natural sort first");
		ok(names.indexOf("img2.tif") < names.indexOf("img10.tif"), "natural sort img2 before img10");
		ok(names.contains("multipos.ome.tif [Scene 2]"), "position named from metadata");
		eq(ImageLoader.scan(p, null), 0, "rescan adds nothing new");
		Project.ImageEntry s3 = null, st = null, rgb = null;
		for (Project.ImageEntry e : p.images) {
			if (e.displayName().endsWith("[Scene 3]")) s3 = e;
			if (e.path.equals("stack.tif")) st = e;
			if (e.path.equals("img10.tif")) rgb = e;
		}
		ImagePlus i3 = ImageLoader.open(p, s3);
		eq(i3.getNChannels(), 2, "position opens with both channels");
		ok(i3.isComposite() && ((CompositeImage) i3).getMode() == IJ.COMPOSITE, "multichannel opened as composite");
		eq((int) i3.getStack().getProcessor(1).getPixelValue(0, 0), 3000, "third position pixel data (not first)");
		eq(i3.getCalibration().pixelWidth, 0.65, "calibration read from metadata");
		ImagePlus is = ImageLoader.open(p, st);
		eq(is.getNSlices(), 4, "z-stack kept as stack in stack mode");
		ok(is.isComposite() && ((CompositeImage) is).getMode() == IJ.COMPOSITE, "tif hyperstack composite");
		is.setC(1);
		double max1 = is.getDisplayRangeMax();
		is.setC(3);
		double max3 = is.getDisplayRangeMax();
		ok(max1 != max3 && max1 < 65535 && max3 < 65535, "per-channel auto contrast (" + max1 + " vs " + max3 + ")");
		p.zMode = Project.Z_MAX;
		ImagePlus mp = ImageLoader.open(p, st);
		eq(mp.getNSlices(), 1, "max projection mode gives one plane");
		eq(mp.getNChannels(), 3, "projection keeps channels");
		mp.setPosition(2, 1, 1);
		eq((int) mp.getProcessor().getPixelValue(5, 5), (int) (2 + 3 * 3) * 500, "max projection value of channel 2 (last z)");
		ImagePlus ir = ImageLoader.open(p, rgb);
		eq(ir.getBitDepth(), 24, "RGB stays RGB");
		ok(names.stream().noneMatch(n -> n.endsWith(".txt")), "non-image files ignored");
	}
}
