package advcounter;

import ij.*;
import ij.gui.*;
import ij.measure.Calibration;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Area;
import java.io.File;
import java.util.*;
import java.util.List;

/** One open project: the current image, its annotation, the side panel and the point tool. */
public class Session implements KeyEventDispatcher {
	public static Session current;

	public final Project project;
	public int index = -1;
	public Project.ImageEntry entry;
	public ImagePlus imp;
	public Annotation ann;
	public String active; // active class id
	public boolean verifyMode;
	public boolean showFill = true, showLabels = true;
	public volatile boolean busy;
	final Deque<Annotation> undo = new ArrayDeque<>();
	CounterPanel panel;
	PointTool tool;
	private boolean closingImage;
	private final WindowAdapter closeListener = new WindowAdapter() {
		@Override public void windowClosed(WindowEvent e) { if (!closingImage) EventQueue.invokeLater(Session.this::imageClosedByUser); }
	};

	public Session(Project project) { this.project = project; }

	public static void start(Project p, boolean verify) {
		if (current != null) current.close();
		Session s = new Session(p);
		current = s;
		s.verifyMode = verify;
		s.tool = new PointTool();
		Toolbar.addPlugInTool(s.tool);
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(s);
		s.panel = new CounterPanel(s);
		s.panel.setVisible(true);
		if (!p.points.isEmpty()) s.active = p.points.get(0).id;
		if (!p.regions.isEmpty()) s.active = p.regions.get(0).id;
		if (p.images.isEmpty()) { IJ.showMessage("Advanced Counter", "No images found in\n" + p.imageDir()); s.panel.refresh(); return; }
		int start = Math.max(0, Math.min(p.lastImage, p.images.size() - 1));
		if (verify) { int v = s.nextIndex(-1, true, false); if (v >= 0) start = v; }
		s.open(start);
	}

	// ---------------------------------------------------------------- image lifecycle

	public void open(int i) {
		if (busy || i < 0 || i >= project.images.size()) return;
		if (imp != null && !leave(false)) return;
		busy = true;
		index = i;
		entry = project.images.get(i);
		panel.refresh();
		final Project.ImageEntry e = entry;
		new Thread(() -> {
			ImagePlus loaded = null;
			Annotation a = null;
			String error = null;
			try {
				IJ.showStatus("Advanced Counter: opening " + e.displayName());
				loaded = ImageLoader.open(project, e);
				File af = project.annotationFile(e);
				a = af.exists() ? Annotation.load(af) : new Annotation(loaded.getWidth(), loaded.getHeight());
				if (a.width != loaded.getWidth() || a.height != loaded.getHeight()) {
					if (af.exists()) error = "Saved annotation size " + a.width + "x" + a.height + " differs from image " + loaded.getWidth() + "x" + loaded.getHeight() + ".\nAnnotations are shown unchanged.";
					a.width = loaded.getWidth(); a.height = loaded.getHeight();
				}
			} catch (Throwable ex) {
				IJ.log("Advanced Counter: cannot open " + e.displayName() + ": " + ex);
				error = "Cannot open " + e.displayName() + ":\n" + ex.getMessage();
				if (loaded == null) a = null;
			}
			final ImagePlus fimp = loaded;
			final Annotation fa = a;
			final String ferr = error;
			EventQueue.invokeLater(() -> shown(fimp, fa, ferr));
		}, "Advanced Counter loader").start();
	}

	private void shown(ImagePlus loaded, Annotation a, String error) {
		busy = false;
		IJ.showStatus("");
		if (loaded == null) { panel.refresh(); if (error != null) IJ.error("Advanced Counter", error); return; }
		imp = loaded;
		ann = a;
		ann.retain(project.classIds());
		undo.clear();
		Calibration cal = imp.getCalibration();
		entry.pixelWidth = cal.pixelWidth;
		entry.pixelHeight = cal.pixelHeight;
		String unit = cal.getUnit();
		entry.unit = unit == null || unit.isEmpty() || unit.startsWith("pixel") ? "pixel" : unit;
		if (entry.unit.equals("pixel")) { entry.pixelWidth = 1; entry.pixelHeight = 1; }
		imp.show();
		ImageWindow win = imp.getWindow();
		if (win != null) {
			win.addWindowListener(closeListener);
			panel.placeImageWindow(win);
		}
		selectClass(active == null || project.cls(active) == null ? firstClass() : active);
		panel.refresh();
		if (error != null) IJ.error("Advanced Counter", error);
	}

	String firstClass() {
		List<Project.Cls> l = project.allClasses();
		return l.isEmpty() ? null : l.get(0).id;
	}

	/** Commits, saves and closes the current image. markVerified marks it verified. */
	public boolean leave(boolean markVerified) {
		if (imp == null) return true;
		commitSelection();
		if (markVerified && !Project.EXCLUDED.equals(entry.status)) entry.status = Project.VERIFIED;
		if (!save()) return false;
		ImagePlus old = imp;
		imp = null;
		ann = null;
		closingImage = true;
		try {
			ImageWindow w = old.getWindow();
			if (w != null) w.removeWindowListener(closeListener);
			old.changes = false;
			old.close();
		} finally { closingImage = false; }
		return true;
	}

	void imageClosedByUser() {
		if (imp == null) return;
		ImagePlus old = imp;
		if (old.getWindow() != null && old.getWindow().isVisible()) return;
		save();
		imp = null;
		ann = null;
		panel.refresh();
	}

	/** Saves annotation, ROI set, overlay PNG, project file and results CSV. */
	public boolean save() {
		if (imp == null || ann == null) return true;
		try {
			ann.retain(project.classIds());
			entry.metrics = ann.metrics(project);
			entry.known = new LinkedHashSet<>(project.classIds());
			if (Project.NOT_STARTED.equals(entry.status)) entry.status = Project.ANNOTATED;
			ann.save(project, entry);
			boolean stack = imp.getStackSize() > 1;
			if (project.saveRoiSet) ann.saveRoiSet(new File(project.annotationDir(), entry.key() + "_RoiSet.zip"), project, stack);
			if (project.saveOverlayPng) saveOverlayPng();
			project.lastImage = index;
			project.save();
			Results.write(project);
			if (panel != null) panel.setMessage("Saved " + entry.displayName());
			return true;
		} catch (Throwable ex) {
			IJ.handleException(ex);
			return JOptionPane.showConfirmDialog(panel, "Saving failed: " + ex.getMessage() + "\nContinue anyway?", "Advanced Counter",
					JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
		}
	}

	void saveOverlayPng() {
		if ((long) imp.getWidth() * imp.getHeight() > 100_000_000L) { IJ.log("Advanced Counter: overlay PNG skipped (image too large): " + entry.displayName()); return; }
		Roi roi = imp.getRoi();
		imp.deleteRoi();
		Overlay normal = imp.getOverlay();
		imp.setOverlay(buildOverlay(true));
		ImagePlus flat = imp.flatten();
		imp.setOverlay(normal);
		if (roi != null) imp.setRoi(roi, false);
		IJ.saveAs(flat, "PNG", new File(project.annotationDir(), entry.key() + "_overlay.png").getPath());
		flat.close();
	}

	// ---------------------------------------------------------------- classes and regions

	public boolean isRegion(String id) {
		for (Project.RegionClass r : project.regions) if (r.id.equals(id)) return true;
		return false;
	}

	public void selectClass(String id) {
		commitSelection();
		active = id;
		if (imp != null) {
			if (id != null && isRegion(id)) {
				Area a = ann.regions.get(id);
				if (a != null) imp.setRoi(Annotation.toRoi(a), true);
				else imp.deleteRoi();
				selectRegionTool();
			} else {
				imp.deleteRoi();
				if (id != null) Toolbar.addPlugInTool(tool);
			}
			refreshOverlay();
			ImageWindow w = imp.getWindow();
			if (w != null) { w.toFront(); imp.getCanvas().requestFocus(); }
		}
		panel.refresh();
	}

	void selectRegionTool() {
		String t = project.regionTool;
		Toolbar tb = Toolbar.getInstance();
		if (tb == null) return;
		if (setTool(tb, t)) return;
		if (t.contains("Brush Tool") && Menus.getCommands().containsKey("Install Brush and Smart Brush")) {
			IJ.run("Install Brush and Smart Brush"); // installs its tools via invokeLater
			EventQueue.invokeLater(() -> { if (!setTool(tb, t)) tb.setTool("polygon"); });
			return;
		}
		IJ.showStatus("Advanced Counter: tool '" + t + "' not available, using polygon");
		tb.setTool("polygon");
	}

	/** Selects a built-in tool by name, or a plugin tool by its exact name (setTool(String) maps any "*brush*" to ImageJ's brush). */
	static boolean setTool(Toolbar tb, String name) {
		if (!name.contains(" ")) return tb.setTool(name);
		int id = tb.getToolId(name);
		if (id < 0) return false;
		tb.setTool(id);
		return name.equals(Toolbar.getToolName());
	}

	/** Stores the current selection as the active region (existing other regions win). */
	public boolean commitSelection() {
		if (imp == null || ann == null || active == null || !isRegion(active)) return false;
		Roi roi = imp.getRoi();
		if (roi == null || !roi.isArea()) return false;
		Area before = ann.regions.get(active);
		Area candidate = Annotation.pixelArea(roi, ann.width, ann.height);
		for (Map.Entry<String, Area> e : ann.regions.entrySet()) if (!e.getKey().equals(active)) candidate.subtract(e.getValue());
		if (before != null ? before.equals(candidate) : candidate.isEmpty()) return false;
		snapshot();
		Area stored = ann.setRegion(active, roi);
		if (stored.isEmpty()) imp.deleteRoi();
		else imp.setRoi(Annotation.toRoi(stored), true);
		refreshOverlay();
		panel.refresh();
		return true;
	}

	public void clearRegion() {
		if (ann == null || active == null || !isRegion(active)) return;
		if (ann.regions.containsKey(active)) { snapshot(); ann.regions.remove(active); }
		imp.deleteRoi();
		refreshOverlay();
		panel.refresh();
	}

	// ---------------------------------------------------------------- points

	int planeZ() { return imp == null ? 1 : imp.getZ(); }
	int planeT() { return imp == null ? 1 : imp.getT(); }

	boolean onPlane(Annotation.Pt p) { return project.showAllSlices || (p.z == planeZ() && p.t == planeT()); }

	public int nearest(int x, int y, double radius) {
		int best = -1;
		double bd = radius * radius;
		for (int i = 0; i < ann.points.size(); i++) {
			Annotation.Pt p = ann.points.get(i);
			if (!onPlane(p)) continue;
			double d = (p.x - x) * (double) (p.x - x) + (p.y - y) * (double) (p.y - y);
			if (d <= bd) { bd = d; best = i; }
		}
		return best;
	}

	public void addPoint(int x, int y) {
		if (ann == null || active == null || isRegion(active) || x < 0 || y < 0 || x >= ann.width || y >= ann.height) return;
		snapshot();
		ann.points.add(new Annotation.Pt(active, x, y, planeZ(), planeT()));
		changed();
	}

	public void removePoint(int i) { snapshot(); ann.points.remove(i); changed(); }

	public void reassign(int i) {
		if (active == null || isRegion(active) || ann.points.get(i).cls.equals(active)) return;
		snapshot();
		ann.points.get(i).cls = active;
		changed();
	}

	public void movePoint(int i, int x, int y, boolean first) {
		if (first) snapshot();
		Annotation.Pt p = ann.points.get(i);
		p.x = Math.max(0, Math.min(ann.width - 1, x));
		p.y = Math.max(0, Math.min(ann.height - 1, y));
		refreshOverlay();
	}

	void changed() { refreshOverlay(); panel.refresh(); }

	void snapshot() {
		undo.push(ann.copy());
		while (undo.size() > 100) undo.removeLast();
	}

	public void undo() {
		if (undo.isEmpty() || imp == null) return;
		Annotation prev = undo.pop();
		ann.regions.clear(); ann.regions.putAll(prev.regions);
		ann.points.clear(); ann.points.addAll(prev.points);
		if (active != null && isRegion(active)) {
			Area a = ann.regions.get(active);
			if (a != null) imp.setRoi(Annotation.toRoi(a), true); else imp.deleteRoi();
		}
		changed();
	}

	// ---------------------------------------------------------------- display

	public void refreshOverlay() {
		if (imp == null) return;
		imp.setOverlay(buildOverlay(false));
	}

	Overlay buildOverlay(boolean allPlanes) {
		Overlay ov = new Overlay();
		boolean hyper = imp.isHyperStack() || imp.getNChannels() > 1;
		boolean stack = imp.getStackSize() > 1;
		for (Project.RegionClass r : project.regions) {
			Area a = ann.regions.get(r.id);
			if (a == null) continue;
			if (showFill) {
				Roi fill = Annotation.toRoi(a);
				fill.setFillColor(new Color(r.color.getRed(), r.color.getGreen(), r.color.getBlue(), 55));
				ov.add(fill);
			}
			Roi outline = Annotation.toRoi(a);
			outline.setStrokeColor(r.color);
			ov.add(outline);
			if (showLabels) {
				Rectangle b = a.getBounds();
				int size = Math.max(12, Math.min(imp.getWidth(), imp.getHeight()) / 40);
				TextRoi t = new TextRoi(b.x + 2, b.y + 2, r.name, new Font("SansSerif", Font.BOLD, size));
				t.setStrokeColor(r.color);
				ov.add(t);
			}
		}
		for (Project.PointClass pc : project.points) {
			Map<String, PointRoi> byPlane = new LinkedHashMap<>();
			for (Annotation.Pt p : ann.points) {
				if (!p.cls.equals(pc.id)) continue;
				boolean all = allPlanes || project.showAllSlices || !stack;
				String k = all ? "all" : p.z + ":" + p.t;
				PointRoi pr = byPlane.get(k);
				if (pr == null) {
					pr = new PointRoi(p.x, p.y);
					if (!all) {
						if (hyper) pr.setPosition(0, p.z, p.t);
						else pr.setPosition(imp.getNSlices() > 1 ? p.z : p.t);
					}
					byPlane.put(k, pr);
				} else pr.addPoint(p.x, p.y);
			}
			for (PointRoi pr : byPlane.values()) {
				pr.setStrokeColor(pc.color);
				pr.setFillColor(pc.color);
				pr.setPointType(Annotation.pointType(pc.marker));
				pr.setSize(pc.size);
				pr.setShowLabels(false);
				ov.add(pr);
			}
		}
		ov.selectable(false);
		return ov;
	}

	// ---------------------------------------------------------------- navigation

	boolean reviewable(Project.ImageEntry e) { return e.done(); }

	boolean unfinished(Project.ImageEntry e) {
		return Project.NOT_STARTED.equals(e.status) || project.needsReview(e) || (verifyMode && Project.ANNOTATED.equals(e.status));
	}

	/** Next (dir>0) or previous index; in verify mode only annotated/verified images; -1 if none. */
	int nextIndex(int from, boolean forward, boolean onlyUnfinished) {
		int n = project.images.size();
		for (int i = forward ? from + 1 : from - 1; i >= 0 && i < n; i += forward ? 1 : -1) {
			Project.ImageEntry e = project.images.get(i);
			if (verifyMode && !reviewable(e)) continue;
			if (onlyUnfinished && !unfinished(e)) continue;
			return i;
		}
		return -1;
	}

	public void next() {
		if (busy) return;
		int n = nextIndex(index, true, false);
		if (verifyMode) {
			if (n < 0) { leaveStay(true); IJ.showMessage("Advanced Counter", "Verification reached the last annotated image.\n" + summary()); return; }
			if (leave(true)) open(n);
			return;
		}
		if (n < 0) { leaveStay(false); IJ.showMessage("Advanced Counter", "This is the last image.\n" + summary()); return; }
		open(n);
	}

	public void previous() {
		if (busy) return;
		int n = nextIndex(index, false, false);
		if (n >= 0) open(n);
	}

	public void nextUnfinished() {
		if (busy) return;
		int n = nextIndex(index, true, true);
		if (n < 0) n = nextIndex(-1, true, true);
		if (n < 0 || n == index) { save(); panel.refresh(); IJ.showMessage("Advanced Counter", "No unfinished image left.\n" + summary()); return; }
		open(n);
	}

	/** Saves the current image (optionally verifying it) without moving. */
	void leaveStay(boolean verify) {
		commitSelection();
		if (verify && entry != null && !Project.EXCLUDED.equals(entry.status)) entry.status = Project.VERIFIED;
		save();
		panel.refresh();
	}

	public void setVerifyMode(boolean v) {
		verifyMode = v;
		panel.refresh();
	}

	public void toggleExcluded() {
		if (entry == null) return;
		commitSelection();
		entry.status = Project.EXCLUDED.equals(entry.status) ? (entry.metrics.isEmpty() ? Project.NOT_STARTED : Project.ANNOTATED) : Project.EXCLUDED;
		if (imp != null) save();
		else try { project.save(); Results.write(project); } catch (Exception ex) { IJ.handleException(ex); }
		panel.refresh();
	}

	public String summary() {
		int ns = 0, an = 0, ve = 0, ex = 0, rv = 0;
		for (Project.ImageEntry e : project.images) {
			switch (e.status) {
				case Project.ANNOTATED: an++; break;
				case Project.VERIFIED: ve++; break;
				case Project.EXCLUDED: ex++; break;
				default: ns++;
			}
			if (project.needsReview(e)) rv++;
		}
		return project.images.size() + " images: " + ns + " not started, " + an + " annotated, " + ve + " verified, " + ex + " excluded"
				+ (rv > 0 ? ", " + rv + " need review (new classes)" : "");
	}

	/** Called after the project definition was edited. */
	public void projectEdited() {
		if (ann != null) ann.retain(project.classIds());
		if (active != null && project.cls(active) == null) active = firstClass();
		try { project.save(); Results.write(project); } catch (Exception ex) { IJ.handleException(ex); }
		panel.rebuild();
		if (imp != null) selectClass(active);
	}

	public void rescan() {
		commitSelection();
		List<String> errors = new ArrayList<>();
		int added = ImageLoader.scan(project, errors);
		try { project.save(); Results.write(project); } catch (Exception ex) { IJ.handleException(ex); }
		panel.rebuild();
		IJ.showMessage("Advanced Counter", added + " new image(s) added." + (errors.isEmpty() ? "" : "\nUnreadable:\n" + String.join("\n", errors)));
	}

	public void close() {
		if (imp != null) leave(false);
		KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
		if (panel != null) panel.dispose();
		if (current == this) current = null;
	}

	// ---------------------------------------------------------------- keyboard

	@Override
	public boolean dispatchKeyEvent(KeyEvent e) {
		if (e.getID() != KeyEvent.KEY_PRESSED || busy) return false;
		Component src = e.getComponent();
		Window w = src instanceof Window ? (Window) src : src == null ? null : SwingUtilities.getWindowAncestor(src);
		boolean inImage = imp != null && imp.getWindow() != null && w == imp.getWindow();
		boolean inPanel = panel != null && w == panel;
		if (!inImage && !inPanel) return false;
		if (e.getComponent() instanceof JTextComponent || e.getComponent() instanceof TextComponent) return false;
		int code = e.getKeyCode();
		boolean mods = e.isControlDown() || e.isAltDown() || e.isMetaDown();
		if (!mods && code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9) {
			List<Project.Cls> l = project.allClasses();
			int k = code - KeyEvent.VK_1;
			if (k < l.size()) selectClass(l.get(k).id);
			return true;
		}
		if (!mods && (code == KeyEvent.VK_ENTER)) { commitSelection(); return true; }
		if (e.isControlDown() && !e.isShiftDown() && code == KeyEvent.VK_Z && (active == null || !isRegion(active) || imp == null || imp.getRoi() == null)) {
			undo();
			return true;
		}
		return false;
	}
}
