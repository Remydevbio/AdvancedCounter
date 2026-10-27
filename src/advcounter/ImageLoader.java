package advcounter;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.ContrastEnhancer;
import ij.plugin.ZProjector;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;

import java.io.File;
import java.util.*;

/** Folder scanning and image opening (ImageJ for plain formats, Bio-Formats for everything else). */
public final class ImageLoader {
	private ImageLoader() {}

	static final Set<String> PLAIN = new HashSet<>(Arrays.asList("tif", "tiff", "png", "jpg", "jpeg", "bmp", "gif"));
	/** Bio-Formats auxiliary series that are not acquisition positions. */
	static final String AUX = "(?i).*\\b(label|macro|thumbnail|slide ?preview|overview)\\b.*";

	static String ext(File f) {
		String n = f.getName().toLowerCase(Locale.ROOT);
		int d = n.lastIndexOf('.');
		return d < 0 ? "" : n.substring(d + 1);
	}

	static boolean plain(File f) {
		String n = f.getName().toLowerCase(Locale.ROOT);
		return PLAIN.contains(ext(f)) && !n.endsWith(".ome.tif") && !n.endsWith(".ome.tiff");
	}

	public static List<File> listImages(Project p) {
		Set<String> exts = new HashSet<>();
		for (String s : p.extensions.split("[,; ]+")) if (!s.isEmpty()) exts.add(s.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""));
		List<File> out = new ArrayList<>();
		collect(p.imageDir(), exts, p.recursive, p.annotationDir().getAbsoluteFile(), out);
		out.sort((a, b) -> natural(a.getPath(), b.getPath()));
		return out;
	}

	private static void collect(File dir, Set<String> exts, boolean recursive, File skip, List<File> out) {
		File[] fs = dir.listFiles();
		if (fs == null) return;
		for (File f : fs) {
			if (f.getName().startsWith(".")) continue;
			if (f.isDirectory()) {
				if (recursive && !f.getAbsoluteFile().equals(skip) && !f.getName().endsWith("_annotations")) collect(f, exts, true, skip, out);
			} else if (exts.contains(ext(f)) && !f.getName().endsWith("_overlay.png")) out.add(f);
		}
	}

	/** Natural sort so that img2 comes before img10. */
	static int natural(String a, String b) {
		int i = 0, j = 0;
		while (i < a.length() && j < b.length()) {
			char x = a.charAt(i), y = b.charAt(j);
			if (Character.isDigit(x) && Character.isDigit(y)) {
				int si = i, sj = j;
				while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
				while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
				String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
				if (na.length() != nb.length()) return na.length() - nb.length();
				int c = na.compareTo(nb);
				if (c != 0) return c;
			} else {
				int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
				if (c != 0) return c;
				i++; j++;
			}
		}
		return (a.length() - i) - (b.length() - j);
	}

	public static class Position {
		public final int series; // flattened importer index
		public final String name;
		Position(int series, String name) { this.series = series; this.name = name; }
	}

	/** Acquisition positions (series) of a file; pyramid sub-resolutions and label/macro images are skipped. */
	public static List<Position> positions(File f) throws Exception {
		List<Position> l = new ArrayList<>();
		if (plain(f)) { l.add(new Position(0, "")); return l; }
		ImageReader r = new ImageReader();
		try {
			r.setFlattenedResolutions(false);
			IMetadata meta = MetadataTools.createOMEXMLMetadata();
			r.setMetadataStore(meta);
			r.setId(f.getPath());
			int flat = 0;
			for (int s = 0; s < r.getSeriesCount(); s++) {
				r.setSeries(s);
				String name = "";
				try { name = meta.getImageName(s); } catch (Exception ignored) {}
				if (name == null) name = "";
				if (!(r.getSeriesCount() > 1 && name.matches(AUX))) l.add(new Position(flat, name));
				flat += Math.max(1, r.getResolutionCount());
			}
		} finally {
			r.close();
		}
		if (l.isEmpty()) l.add(new Position(0, ""));
		return l;
	}

	/** Adds images/positions not yet in the project; returns the number added. Existing entries are kept. */
	public static int scan(Project p, List<String> errors) {
		Set<String> have = new HashSet<>();
		for (Project.ImageEntry e : p.images) have.add(e.path + "#" + e.series);
		List<File> files = listImages(p);
		int added = 0, k = 0;
		for (File f : files) {
			IJ.showProgress(k++, files.size());
			IJ.showStatus("Advanced Counter: scanning " + f.getName());
			String rel = Project.relativize(p.imageDir(), f);
			List<Position> ps;
			try { ps = positions(f); }
			catch (Throwable ex) {
				if (errors != null) errors.add(f.getName() + ": " + ex.getMessage());
				continue;
			}
			for (int i = 0; i < ps.size(); i++) {
				Position pos = ps.get(i);
				if (have.contains(rel + "#" + pos.series)) continue;
				Project.ImageEntry e = new Project.ImageEntry();
				e.id = p.newId("img");
				e.path = rel;
				e.series = pos.series;
				e.position = i + 1;
				e.positionsInFile = ps.size();
				e.seriesName = pos.name;
				p.images.add(e);
				added++;
			}
		}
		IJ.showProgress(1.0);
		IJ.showStatus("");
		return added;
	}

	public static ImagePlus open(Project p, Project.ImageEntry e) throws Exception {
		File f = p.imageFile(e);
		if (!f.exists()) throw new java.io.FileNotFoundException(f.getPath());
		ImagePlus imp = null;
		if (plain(f) && e.series == 0) imp = IJ.openImage(f.getPath());
		if (imp == null) {
			ImporterOptions o = new ImporterOptions();
			o.setId(f.getPath());
			o.setQuiet(true);
			o.setWindowless(true);
			o.setVirtual(false);
			o.setAutoscale(true);
			o.setGroupFiles(false);
			o.setSplitChannels(false);
			o.setSplitFocalPlanes(false);
			o.setSplitTimepoints(false);
			o.setColorMode(ImporterOptions.COLOR_MODE_COMPOSITE);
			o.setOpenAllSeries(false);
			o.setSeriesOn(0, false);
			o.setSeriesOn(e.series, true);
			ImagePlus[] imps = BF.openImagePlus(o);
			if (imps == null || imps.length == 0) throw new IllegalStateException("Bio-Formats returned no image for " + f.getName());
			imp = imps[0];
		}
		return prepare(p, imp, e.displayName());
	}

	/** Composite display, optional Z max projection and per-channel auto contrast. Pixel data is not altered. */
	public static ImagePlus prepare(Project p, ImagePlus imp, String title) {
		if (Project.Z_MAX.equals(p.zMode) && imp.getNSlices() > 1) {
			Calibration cal = imp.getCalibration().copy();
			ImagePlus proj = ZProjector.run(imp, "max all");
			proj.setCalibration(cal);
			imp.close();
			imp = proj;
		}
		if (imp.getBitDepth() != 24 && imp.getNChannels() > 1) {
			if (!imp.isComposite()) imp = new CompositeImage(imp, IJ.COMPOSITE);
			else ((CompositeImage) imp).setMode(IJ.COMPOSITE);
		}
		if (p.autoContrast && imp.getBitDepth() != 24) autoContrast(imp);
		imp.setTitle(title);
		return imp;
	}

	public static void autoContrast(ImagePlus imp) {
		int c0 = imp.getC(), z0 = imp.getZ(), t0 = imp.getT();
		int midZ = (imp.getNSlices() + 1) / 2;
		ContrastEnhancer ce = new ContrastEnhancer();
		for (int c = 1; c <= imp.getNChannels(); c++) {
			imp.setPosition(c, midZ, t0);
			ce.stretchHistogram(imp, 0.35); // display range of the current channel only
		}
		imp.setPosition(c0, z0, t0);
	}
}
