package advcounter;

import ij.*;
import ij.gui.*;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import loci.common.services.ServiceFactory;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.out.OMETiffWriter;
import loci.formats.services.OMEXMLService;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Runs inside real Fiji. User input is synthesized as AWT events dispatched to the plugin's components
 * (no native mouse/keyboard, so the desktop is not disturbed). Screenshots are rendered offscreen.
 */
public class GuiTest implements PlugIn {
	static final File ROOT = new File(System.getProperty("advcounter.root", System.getProperty("user.dir")));
	static final File OUT = new File(ROOT, "build/gui");
	final StringBuilder report = new StringBuilder();
	int passed, failed;

	public void run(String arg) {
		new Thread(() -> {
			try { tests(); }
			catch (Throwable t) { failed++; report.append("FAIL exception ").append(t).append('\n'); for (StackTraceElement s : t.getStackTrace()) report.append("    at ").append(s).append('\n'); }
			finally {
				report.append("Completed ").append(passed).append(" passed, ").append(failed).append(" failed; ImageJ ").append(IJ.getVersion()).append('\n');
				try { Files.write(new File(OUT, "gui-results.txt").toPath(), report.toString().getBytes()); } catch (Exception e) { e.printStackTrace(); }
				if (!"keep".equals(System.getProperty("advcounter.gui"))) System.exit(failed == 0 ? 0 : 1);
			}
		}, "Advanced Counter GUI test").start();
	}

	void ok(boolean b, String m) {
		if (b) passed++; else failed++;
		report.append(b ? "PASS " : "FAIL ").append(m).append('\n');
		System.out.println((b ? "PASS " : "FAIL ") + m);
	}

	void eq(Object a, Object b, String m) { ok(Objects.equals(a, b), m + " (got " + a + ", expected " + b + ")"); }

	void ui(Runnable r) throws Exception { EventQueue.invokeAndWait(r); }

	void waitFor(BooleanSupplier c, String what) throws Exception {
		for (int i = 0; i < 400; i++) {
			final boolean[] v = {false};
			ui(() -> v[0] = c.getAsBoolean());
			if (v[0]) return;
			Thread.sleep(50);
		}
		throw new AssertionError("timeout waiting for " + what);
	}

	void idle() throws Exception { ui(() -> {}); Thread.sleep(150); ui(() -> {}); }

	Session s() { return Session.current; }

	ImageCanvas canvas() { return s().imp.getCanvas(); }

	Point screenOf(int x, int y) {
		ImageCanvas c = canvas();
		return new Point((int) Math.floor(c.screenXD(x + 0.5)), (int) Math.floor(c.screenYD(y + 0.5)));
	}

	void mouse(int id, int x, int y, int mods, int button) throws Exception {
		Point p = screenOf(x, y);
		ImageCanvas c = canvas();
		// posted through the system event queue like native input (the brush tool filters at queue level)
		Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new MouseEvent(c, id, System.currentTimeMillis(), mods, p.x, p.y, id == MouseEvent.MOUSE_CLICKED ? 1 : 0, false, button));
		ui(() -> {});
	}

	void click(int x, int y, int mods) throws Exception {
		int bm = mods | InputEvent.BUTTON1_DOWN_MASK;
		mouse(MouseEvent.MOUSE_PRESSED, x, y, bm, MouseEvent.BUTTON1);
		mouse(MouseEvent.MOUSE_RELEASED, x, y, mods, MouseEvent.BUTTON1);
		mouse(MouseEvent.MOUSE_CLICKED, x, y, mods, MouseEvent.BUTTON1);
		idle();
	}

	void drag(int x0, int y0, int x1, int y1, int mods) throws Exception {
		int bm = mods | InputEvent.BUTTON1_DOWN_MASK;
		mouse(MouseEvent.MOUSE_MOVED, x0, y0, mods, 0);
		mouse(MouseEvent.MOUSE_PRESSED, x0, y0, bm, MouseEvent.BUTTON1);
		int n = 12;
		for (int i = 1; i <= n; i++) {
			mouse(MouseEvent.MOUSE_DRAGGED, x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n, bm, MouseEvent.BUTTON1);
			Thread.sleep(30);
		}
		mouse(MouseEvent.MOUSE_RELEASED, x1, y1, mods, MouseEvent.BUTTON1);
		idle();
	}

	/** Key press on the image canvas through the normal AWT dispatch (KeyEventDispatchers run first). */
	boolean key(int code, int mods) throws Exception {
		ImageCanvas c = canvas();
		final boolean[] handled = {false};
		ui(() -> {
			KeyEvent e = new KeyEvent(c, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mods, code, KeyEvent.CHAR_UNDEFINED);
			handled[0] = s().dispatchKeyEvent(e);
			if (!handled[0]) c.dispatchEvent(e);
		});
		idle();
		return handled[0];
	}

	AbstractButton button(Container root, String textStart) {
		for (Component c : root.getComponents()) {
			if (c instanceof AbstractButton) {
				String t = ((AbstractButton) c).getText();
				if (t != null && t.replaceAll("<[^>]+>", "").startsWith(textStart)) return (AbstractButton) c;
			}
			if (c instanceof Container) { AbstractButton b = button((Container) c, textStart); if (b != null) return b; }
		}
		return null;
	}

	void press(String text) throws Exception {
		AbstractButton b = button(s().panel.getContentPane(), text);
		ok(b != null, "panel button '" + text + "' exists");
		ui(b::doClick);
		idle();
	}

	void shot(Window w, String name) throws Exception {
		ui(() -> {
			try {
				BufferedImage im = new BufferedImage(Math.max(1, w.getWidth()), Math.max(1, w.getHeight()), BufferedImage.TYPE_INT_RGB);
				Graphics2D g = im.createGraphics();
				w.printAll(g);
				g.dispose();
				ImageIO.write(im, "png", new File(OUT, name + ".png"));
			} catch (Exception e) { report.append("screenshot failed ").append(name).append(": ").append(e).append('\n'); }
		});
	}

	void waitImage(int index) throws Exception {
		waitFor(() -> s() != null && !s().busy && s().imp != null && s().index == index && s().imp.getWindow() != null && s().imp.getWindow().isShowing(), "image " + index);
		idle();
		Thread.sleep(300);
	}

	List<Map<String, String>> csv(Project p) throws Exception {
		List<String> lines = Files.readAllLines(p.resultsFile().toPath());
		List<String> h = split(lines.get(0));
		List<Map<String, String>> rows = new ArrayList<>();
		for (String l : lines.subList(1, lines.size())) {
			List<String> c = split(l);
			Map<String, String> m = new LinkedHashMap<>();
			for (int i = 0; i < h.size(); i++) m.put(h.get(i), i < c.size() ? c.get(i) : "");
			rows.add(m);
		}
		return rows;
	}

	static List<String> split(String l) {
		List<String> out = new ArrayList<>();
		StringBuilder b = new StringBuilder();
		boolean q = false;
		for (int i = 0; i < l.length(); i++) {
			char c = l.charAt(i);
			if (q) { if (c == '"' && i + 1 < l.length() && l.charAt(i + 1) == '"') { b.append('"'); i++; } else if (c == '"') q = false; else b.append(c); }
			else if (c == '"') q = true;
			else if (c == ',') { out.add(b.toString()); b.setLength(0); }
			else b.append(c);
		}
		out.add(b.toString());
		return out;
	}

	// ---------------------------------------------------------------- fixtures

	File fixtures() throws Exception {
		File dir = new File(OUT, "images");
		if (dir.exists()) for (File f : dir.listFiles()) if (f.isFile()) f.delete();
		if (new File(dir, "GuiProject_annotations").exists()) for (File f : new File(dir, "GuiProject_annotations").listFiles()) f.delete();
		dir.mkdirs();
		ImagePlus a = IJ.createHyperStack("a", 400, 300, 2, 1, 1, 16);
		for (int c = 1; c <= 2; c++) {
			ImageProcessor ip = a.getStack().getProcessor(c);
			for (int y = 0; y < 300; y++) for (int x = 0; x < 400; x++) ip.set(x, y, c == 1 ? x * 50 : y * 80 + (((x / 40) + (y / 40)) % 2) * 3000);
		}
		IJ.saveAsTiff(a, new File(dir, "A_two_channels.tif").getPath());
		ImagePlus b = IJ.createHyperStack("b", 300, 200, 2, 3, 1, 16);
		for (int i = 1; i <= b.getStackSize(); i++) {
			ImageProcessor ip = b.getStack().getProcessor(i);
			for (int y = 0; y < 200; y++) for (int x = 0; x < 300; x++) ip.set(x, y, i * 1000 + (x + y) * 5);
		}
		IJ.saveAsTiff(b, new File(dir, "B_zstack.tif").getPath());
		IMetadata meta = new ServiceFactory().getInstance(OMEXMLService.class).createOMEXMLMetadata();
		for (int sr = 0; sr < 2; sr++) MetadataTools.populateMetadata(meta, sr, "Scene " + (sr + 1), false, "XYCZT", "uint8", 200, 150, 1, 2, 1, 1);
		OMETiffWriter w = new OMETiffWriter();
		w.setMetadataRetrieve(meta);
		w.setId(new File(dir, "C_positions.ome.tif").getPath());
		for (int sr = 0; sr < 2; sr++) {
			w.setSeries(sr);
			for (int pl = 0; pl < 2; pl++) {
				byte[] px = new byte[200 * 150];
				for (int i = 0; i < px.length; i++) px[i] = (byte) ((i % 200) + sr * 40 + pl * 20);
				w.saveBytes(pl, px);
			}
		}
		w.close();
		return dir;
	}

	// ---------------------------------------------------------------- tests

	void tests() throws Exception {
		OUT.mkdirs();
		File dir = fixtures();
		Project p = new Project();
		p.name = "GuiProject";
		p.file = new File(dir, "GuiProject" + Project.EXT);
		p.regions.add(new Project.RegionClass("r1", "ROI1", Color.cyan));
		p.regions.add(new Project.RegionClass("r2", "ROI2", Color.yellow));
		p.points.add(new Project.PointClass("p3", "A", Color.red));
		p.points.add(new Project.PointClass("p4", "B", Color.green));
		p.nextId = 5;
		p.formulas.add(new Project.FormulaDef("A_per_ROI1_area", "{ROI1:A}/{ROI1:area_px}*10000"));
		p.formulas.add(new Project.FormulaDef("B_fraction_ROI1", "{ROI1:B}/({ROI1:A}+{ROI1:B})"));
		Advanced_Counter.createAndStart(p);
		waitImage(0);
		Session s = s();
		eq(p.images.size(), 4, "project has 2 single images + 2 positions");
		ok(s.panel.isShowing(), "side panel shown");
		Rectangle wb = s.imp.getWindow().getBounds(), pb = s.panel.getBounds();
		ok(wb.x + wb.width <= pb.x + 2, "image window placed left of the side panel (" + wb + " / " + pb + ")");
		ok(s.imp.isComposite() && ((CompositeImage) s.imp).getMode() == IJ.COMPOSITE, "image opened as composite (all channels)");
		eq(s.active, "r1", "first region class active");
		eq(Toolbar.getToolName(), "Selection Brush Tool", "brush tool selected for regions");

		// ROI1 with a rectangle selection + Enter
		ui(() -> s.imp.setRoi(new Roi(50, 50, 150, 150)));
		ok(key(KeyEvent.VK_ENTER, 0), "Enter handled by plugin");
		eq(Annotation.pixelCount(s.ann.regions.get("r1")), 22500L, "ROI1 committed from selection");

		// ROI2 with the brush, overlapping ROI1
		ok(key(KeyEvent.VK_2, 0), "key 2 handled");
		eq(s.active, "r2", "key 2 selects ROI2");
		ok(s.imp.getRoi() == null, "empty ROI2 shows no selection");
		eq(Toolbar.getToolName(), "Selection Brush Tool", "brush tool still active");
		ok(Toolbar.getPlugInTool() != null && Toolbar.getPlugInTool().getClass().getName().startsWith("Intensity_Selection_Tools"), "plugin brush (not ImageJ brush) active");
		drag(170, 120, 330, 120, 0);
		waitFor(() -> s.imp.getRoi() != null, "brush stroke selection");
		Thread.sleep(400);
		ok(s.imp.getRoi() != null && s.imp.getRoi().contains(250, 120), "brush stroke created selection");
		drag(300, 120, 300, 230, InputEvent.SHIFT_DOWN_MASK);
		Thread.sleep(400);
		ok(s.imp.getRoi().contains(300, 220), "Shift+brush stroke extends selection");
		ok(key(KeyEvent.VK_3, 0), "key 3 handled");
		eq(s.active, "p3", "key 3 selects counter A and commits ROI2");
		Area r1 = s.ann.regions.get("r1"), r2 = s.ann.regions.get("r2");
		ok(r2 != null && Annotation.containsPixel(r2, 250, 120) && Annotation.containsPixel(r2, 300, 220), "ROI2 stored from brush strokes");
		ok(!Annotation.containsPixel(r2, 180, 120), "ROI2 excludes ROI1 pixels (existing wins)");
		Area inter = new Area(r1); inter.intersect(r2);
		ok(inter.isEmpty(), "ROI1 and ROI2 do not overlap");
		eq(Annotation.pixelCount(r1), 22500L, "ROI1 unchanged by ROI2");
		eq(Toolbar.getToolName(), PointTool.NAME, "point tool selected for counters");
		ok(s.imp.getRoi() == null, "selection cleared in counting mode");

		// points
		click(60, 60, 0); click(100, 100, 0); click(120, 140, 0); click(250, 120, 0); click(380, 20, 0);
		eq(s.ann.points.size(), 5, "5 A points added by clicks");
		key(KeyEvent.VK_4, 0);
		eq(s.active, "p4", "key 4 selects counter B");
		click(70, 180, 0); click(150, 60, 0);
		click(100, 100, InputEvent.CTRL_DOWN_MASK);
		eq(s.ann.points.size(), 6, "Ctrl+click deletes a point");
		click(150, 60, InputEvent.CTRL_DOWN_MASK);
		eq(s.ann.points.size(), 5, "second Ctrl+click deletes another point");
		click(150, 60, 0);
		click(60, 60, InputEvent.SHIFT_DOWN_MASK);
		Map<String, Double> m = s.ann.metrics(p);
		eq(m.get("count:r1:p4"), 3.0, "Shift+click reassigns A point to B");
		drag(120, 140, 260, 125, 0);
		eq(s.ann.metrics(p).get("count:r2:p3"), 2.0, "dragging a point moves it into ROI2");
		ok(key(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "Ctrl+Z handled in counting mode");
		m = s.ann.metrics(p);
		eq(m.get("count:r1:p3"), 1.0, "undo restores moved point (A in ROI1)");
		eq(m.get("count:r2:p3"), 1.0, "A in ROI2");
		eq(m.get("count:outside:p3"), 1.0, "A outside");
		eq(m.get("count:r1:p4"), 3.0, "B in ROI1");
		Overlay ov = s.imp.getOverlay();
		int pts = 0;
		for (Roi r : ov.toArray()) if (r instanceof PointRoi) pts += ((PointRoi) r).size();
		eq(pts, 6, "overlay shows all points");
		ok(button(s.panel.getContentPane(), "3").getText().contains("(ROI1 1, ROI2 1, outside 1)"), "panel shows per-region counts: " + button(s.panel.getContentPane(), "3").getText().replaceAll("<[^>]+>", ""));
		shot(s.imp.getWindow(), "1-annotated-image");
		shot(s.panel, "1-panel");
		// region edit via polygon tool: extend ROI1 into ROI2 -> ROI2 kept
		ui(() -> { s.panel.p.regionTool = "polygon"; });
		key(KeyEvent.VK_1, 0);
		eq(Toolbar.getToolName(), "polygon", "region tool switched to polygon");
		ok(s.imp.getRoi() != null && Annotation.pixelCount(Annotation.pixelArea(s.imp.getRoi(), 400, 300)) == 22500, "existing ROI1 shown as editable selection");
		ui(() -> s.imp.setRoi(new PolygonRoi(new int[]{50, 260, 260, 50}, new int[]{50, 50, 200, 200}, 4, Roi.POLYGON)));
		key(KeyEvent.VK_3, 0);
		ok(!Annotation.containsPixel(s.ann.regions.get("r1"), 250, 120), "extended ROI1 does not take ROI2 pixels");
		Area expect = Annotation.pixelArea(new PolygonRoi(new int[]{50, 260, 260, 50}, new int[]{50, 50, 200, 200}, 4, Roi.POLYGON), 400, 300);
		expect.subtract(s.ann.regions.get("r2"));
		ok(expect.equals(s.ann.regions.get("r1")) && Annotation.pixelCount(expect) > 22500, "ROI1 = drawn polygon minus ROI2 (" + Annotation.pixelCount(expect) + " px)");
		Area ov12 = new Area(s.ann.regions.get("r1")); ov12.intersect(s.ann.regions.get("r2"));
		ok(ov12.isEmpty(), "still no overlap after editing ROI1");
		long r1px = Annotation.pixelCount(s.ann.regions.get("r1")), r2px = Annotation.pixelCount(s.ann.regions.get("r2"));
		ok(key(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo region edit");
		eq(Annotation.pixelCount(s.ann.regions.get("r1")), 22500L, "undo restores ROI1");
		ui(() -> s.imp.setRoi(new PolygonRoi(new int[]{50, 260, 260, 50}, new int[]{50, 50, 200, 200}, 4, Roi.POLYGON)));
		key(KeyEvent.VK_1, 0);
		ui(() -> s.imp.setRoi(new PolygonRoi(new int[]{50, 260, 260, 50}, new int[]{50, 50, 200, 200}, 4, Roi.POLYGON)));
		key(KeyEvent.VK_ENTER, 0);
		key(KeyEvent.VK_4, 0);
		eq(Annotation.pixelCount(s.ann.regions.get("r1")), r1px, "ROI1 re-extended");

		// next image -> saved files + CSV
		press("Next");
		waitImage(1);
		Project.ImageEntry e0 = p.images.get(0);
		eq(e0.status, Project.ANNOTATED, "image 1 marked annotated");
		ok(p.annotationFile(e0).exists(), "annotation JSON saved");
		ok(new File(p.annotationDir(), e0.key() + "_RoiSet.zip").exists(), "Fiji RoiSet saved");
		ok(new File(p.annotationDir(), e0.key() + "_overlay.jpg").exists(), "overlay JPEG saved");
		ok(!new File(p.annotationDir(), e0.key() + "_overlay.png").exists(), "no overlay PNG written");
		List<Map<String, String>> rows = csv(p);
		eq(rows.size(), 4, "CSV has one row per image");
		Map<String, String> row = rows.get(0);
		eq(row.get("ROI1_area_px"), Long.toString(r1px), "CSV ROI1 area");
		eq(row.get("ROI2_area_px"), Long.toString(r2px), "CSV ROI2 area");
		eq(row.get("ROI1_A"), "1", "CSV ROI1 A");
		eq(row.get("ROI1_B"), "3", "CSV ROI1 B");
		eq(row.get("ROI2_A"), "1", "CSV ROI2 A");
		eq(row.get("Outside_A"), "1", "CSV outside A");
		eq(row.get("Total_A"), "3", "CSV total A");
		eq(row.get("B_fraction_ROI1"), "0.75", "CSV formula");
		eq(rows.get(1).get("status"), Project.NOT_STARTED, "next image not started yet in CSV");
		eq(rows.get(1).get("ROI1_A"), "NA", "not started image NA");
		ImagePlus flat = IJ.openImage(new File(p.annotationDir(), e0.key() + "_overlay.jpg").getPath());
		ok(flat != null && flat.getWidth() == 400 && flat.getBitDepth() == 24, "overlay JPEG is a flattened RGB image");

		// z-stack image
		eq(s.imp.getNSlices(), 3, "z-stack opened as stack");
		ok(s.imp.getWindow() instanceof StackWindow, "stack window with sliders");
		eq(s.active, "p4", "counter B stays active on next image");
		ui(() -> s.imp.setPosition(1, 2, 1));
		click(100, 100, 0);
		eq(s.ann.points.get(0).z, 2, "point stores its z slice");
		Roi pr = null;
		for (Roi r : s.imp.getOverlay().toArray()) if (r instanceof PointRoi) pr = r;
		ok(pr != null && pr.getZPosition() == 2, "point shown only on its slice");
		ui(() -> s.imp.setPosition(1, 3, 1));
		eq(s.nearest(100, 100, 5), -1, "point not pickable on other slice");
		shot(s.imp.getWindow(), "2-zstack");

		// next: multi-position file
		press("Next");
		waitImage(2);
		ok(s.entry.displayName().equals("C_positions.ome.tif [Scene 1]"), "position named: " + s.entry.displayName());
		eq(s.imp.getNChannels(), 2, "position composite with 2 channels");
		press("Close project");
		ok(Session.current == null, "project closed");
		ok(!s.panel.isDisplayable(), "panel disposed");

		// reopen and continue
		Advanced_Counter.open(p.file, false);
		waitImage(2);
		Session s2 = s();
		ok(s2 != s, "new session");
		eq(s2.project.images.get(1).status, Project.ANNOTATED, "status restored");
		ui(() -> s2.panel.getContentPane());
		JComboBox<?> jump = find(s2.panel.getContentPane(), JComboBox.class, 0);
		ui(() -> jump.setSelectedIndex(0));
		waitImage(0);
		eq(s2.ann.points.size(), 6, "reopened image has its points");
		eq(Annotation.pixelCount(s2.ann.regions.get("r1")), r1px, "reopened image has ROI1");

		// add counter + formula during the project
		Project p2 = s2.project;
		ui(() -> {
			p2.points.add(new Project.PointClass(p2.newId("p"), "C", Color.magenta));
			p2.formulas.add(new Project.FormulaDef("C_density", "{ROI1:C}/{ROI1:area_px}"));
			p2.formulas.add(new Project.FormulaDef("B_per_area", "{ROI1:B}/{ROI1:area_px}"));
			s2.projectEdited();
		});
		idle();
		rows = csv(p2);
		eq(rows.get(0).get("ROI1_C"), "NA", "new counter is NA on annotated images");
		eq(rows.get(0).get("needs_review"), "yes", "annotated image flagged for review");
		eq(rows.get(0).get("B_per_area"), Results.fmt(3.0 / r1px), "formula added later is computed for previous images");
		eq(rows.get(0).get("C_density"), "NA", "formula with new counter NA before review");
		ok(button(s2.panel.getContentPane(), "5") != null, "panel shows new counter button");

		// verify mode: edit and continue
		press("Verify");
		ok(s2.verifyMode, "verify mode on");
		key(KeyEvent.VK_4, 0);
		click(70, 180, InputEvent.CTRL_DOWN_MASK);
		key(KeyEvent.VK_5, 0);
		click(90, 90, 0);
		press("Verified, next");
		waitImage(1);
		rows = csv(p2);
		eq(rows.get(0).get("status"), Project.VERIFIED, "image verified");
		eq(rows.get(0).get("ROI1_B"), "2", "verification edit saved");
		eq(rows.get(0).get("ROI1_C"), "1", "new counter counted during verification");
		eq(rows.get(0).get("needs_review"), "no", "review flag cleared");
		eq(rows.get(0).get("C_density"), Results.fmt(1.0 / r1px), "new formula computed after review");
		eq(s2.index, 1, "verify goes to next annotated image");
		press("Exclude image");
		eq(s2.entry.status, Project.EXCLUDED, "image excluded");
		rows = csv(p2);
		eq(rows.get(1).get("Total_B"), "NA", "excluded image NA");
		press("Exclude image");
		eq(s2.entry.status, Project.ANNOTATED, "exclusion undone");
		press("Verified, next");
		waitImage(2);
		eq(p2.images.get(1).status, Project.VERIFIED, "second image verified");
		AbstractButton last = button(s2.panel.getContentPane(), "Verified, next");
		EventQueue.invokeLater(last::doClick); // ends with a modal message
		Thread.sleep(1500);
		closeDialogs();
		idle();
		eq(p2.images.get(2).status, Project.VERIFIED, "last annotated image verified");
		eq(s2.index, 2, "verification stops at last annotated image (position 2 never annotated)");
		shot(s2.panel, "3-panel-verify");

		// dialogs
		EventQueue.invokeLater(() -> SetupDialog.edit(s2.panel, p2, 3));
		waitFor(() -> dialog() != null, "edit dialog");
		Thread.sleep(400);
		JDialog d = dialog();
		JTabbedPane tabs = find(d.getContentPane(), JTabbedPane.class, 0);
		for (int t = 0; t < tabs.getTabCount(); t++) { final int ti = t; ui(() -> tabs.setSelectedIndex(ti)); idle(); shot(d, "4-edit-tab" + t); }
		ui(() -> tabs.setSelectedIndex(3));
		JComboBox<?> vars = find((Container) tabs.getComponentAt(3), JComboBox.class, 0);
		List<String> vlist = new ArrayList<>();
		for (int i = 0; i < vars.getItemCount(); i++) vlist.add(vars.getItemAt(i).toString());
		ok(vlist.contains("{ROI2:C}") && vlist.contains("{Total:A}") && vlist.size() == 2 * (2 + 3) + 3 + 3, "formula builder lists only project variables: " + vlist);
		// rename a region and add a formula through the dialog, then OK
		ui(() -> tabs.setSelectedIndex(1));
		JTextField rname = find((Container) tabs.getComponentAt(1), JTextField.class, 0);
		ui(() -> rname.setText("Tumor"));
		ui(() -> tabs.setSelectedIndex(3));
		JTextField fname = find((Container) tabs.getComponentAt(3), JTextField.class, 0), fexpr = find((Container) tabs.getComponentAt(3), JTextField.class, 1);
		ui(() -> { fname.setText("bad"); fexpr.setText("{Liver:A}/2"); });
		idle();
		ok(find((Container) tabs.getComponentAt(3), JLabel.class, 0) != null, "formula status label");
		ui(() -> { fname.setText("A_in_tumor_per_total"); fexpr.setText("{Tumor:A}/{Total:A}"); });
		ui(() -> button(d.getContentPane(), "Add as new").doClick());
		ui(() -> button(d.getContentPane(), "OK").doClick());
		waitFor(() -> dialog() == null, "dialog closed with OK");
		ui(s2::projectEdited);
		idle();
		eq(p2.regions.get(0).name, "Tumor", "region renamed");
		ok(p2.formulas.get(0).expression.equals("{Tumor:A}/{Tumor:area_px}*10000"), "rename propagated into formulas: " + p2.formulas.get(0).expression);
		eq(p2.formulas.get(p2.formulas.size() - 1).name, "A_in_tumor_per_total", "formula added via dialog");
		rows = csv(p2);
		ok(rows.get(0).containsKey("Tumor_area") && !rows.get(0).containsKey("ROI1_area"), "CSV header uses new region name");
		eq(rows.get(0).get("A_in_tumor_per_total"), Results.fmt(1.0 / 3), "dialog formula computed for verified image");
		eq(Project.load(p2.file).regions.get(0).name, "Tumor", "rename saved in project file");

		// full menu path: Plugins > Advanced Counter > New Project... (asks to close current project first)
		File dir2 = new File(OUT, "images2");
		dir2.mkdirs();
		for (File f : dir2.listFiles()) if (f.isFile()) f.delete();
		Files.copy(new File(dir, "A_two_channels.tif").toPath(), new File(dir2, "only.tif").toPath());
		new Advanced_Counter().run("new");
		waitFor(() -> modal("Advanced Counter") != null || dialog() != null, "close-current confirmation");
		Thread.sleep(300);
		Dialog confirm = modal("Advanced Counter");
		if (confirm != null) { AbstractButton okb = null; Button awtOk = awtButton(confirm, "OK"); ok(awtOk != null, "confirmation has OK"); ui(() -> { for (ActionListener l : awtOk.getActionListeners()) l.actionPerformed(new ActionEvent(awtOk, ActionEvent.ACTION_PERFORMED, "OK")); }); }
		waitFor(() -> dialog() != null, "new project dialog");
		Thread.sleep(400);
		shot(dialog(), "5-new-project");
		JDialog nd = dialog();
		JTextField pname = find(nd.getContentPane(), JTextField.class, 0), idir = find(nd.getContentPane(), JTextField.class, 1), pdir = find(nd.getContentPane(), JTextField.class, 2);
		ui(() -> { pname.setText("Menu Project"); idir.setText(dir2.getPath()); pdir.setText(dir2.getPath()); });
		ui(() -> button(nd.getContentPane(), "Create project").doClick());
		waitFor(() -> Session.current != null && Session.current != s2, "session from menu");
		waitImage(0);
		Session s3 = s();
		eq(s3.project.file.getName(), "Menu_Project.acproj", "project file named from project name");
		ok(s3.project.file.exists() && s3.project.resultsFile().exists(), "project and results files created");
		eq(s3.project.images.size(), 1, "menu project scanned its folder");
		eq(s3.project.regions.size() + s3.project.points.size(), 4, "default classes ROI1, ROI2, Counter1, Counter2");
		ok(!s2.panel.isDisplayable(), "previous project closed");
		press("Close project");

		// reopen the first project for the window-close test
		Advanced_Counter.open(p2.file, false);
		waitImage(2);
		Session s4 = s();
		// user closes the image window: annotations are saved
		ui(() -> { s4.selectClass("p3"); });
		click(20, 20, 0);
		int before = s4.ann.points.size();
		ui(() -> s4.imp.getWindow().dispatchEvent(new WindowEvent(s4.imp.getWindow(), WindowEvent.WINDOW_CLOSING)));
		Thread.sleep(800);
		idle();
		ok(s4.imp == null, "session notices closed image window");
		Annotation saved = Annotation.load(p2.annotationFile(p2.images.get(2)));
		eq(saved.points.size(), before, "annotation saved when the window was closed");
		press("Close project");
		report.append("Screenshots in ").append(OUT).append('\n');
	}

	Dialog modal(String title) {
		for (Window w : Window.getWindows()) if (w instanceof Dialog && !(w instanceof JDialog) && w.isShowing() && title.equals(((Dialog) w).getTitle())) return (Dialog) w;
		return null;
	}

	Button awtButton(Container c, String label) {
		for (Component k : c.getComponents()) {
			if (k instanceof Button && label.equals(((Button) k).getLabel().trim())) return (Button) k;
			if (k instanceof Container) { Button b = awtButton((Container) k, label); if (b != null) return b; }
		}
		return null;
	}

	JDialog dialog() {
		for (Window w : Window.getWindows()) if (w instanceof JDialog && w.isShowing() && ((JDialog) w).getTitle().startsWith("Advanced Counter")) return (JDialog) w;
		return null;
	}

	void closeDialogs() throws Exception {
		for (Window w : Window.getWindows())
			if (w.isShowing() && w instanceof Dialog && ((Dialog) w).getTitle().contains("Advanced Counter") && !(w instanceof JDialog)) {
				report.append("info dialog: ").append(((Dialog) w).getTitle()).append('\n');
				ui(w::dispose);
			}
	}

	@SuppressWarnings("unchecked")
	<T> T find(Container root, Class<T> type, int nth) {
		List<T> l = new ArrayList<>();
		collect(root, type, l);
		return l.size() > nth ? l.get(nth) : null;
	}

	<T> void collect(Container root, Class<T> type, List<T> out) {
		for (Component c : root.getComponents()) {
			if (type.isInstance(c)) out.add(type.cast(c));
			if (c instanceof Container) collect((Container) c, type, out);
		}
	}
}
