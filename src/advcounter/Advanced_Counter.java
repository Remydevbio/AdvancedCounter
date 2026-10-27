package advcounter;

import ij.IJ;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;

import java.awt.EventQueue;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Menu entry: Plugins > Advanced Counter. */
public class Advanced_Counter implements PlugIn {
	@Override
	public void run(String arg) {
		EventQueue.invokeLater(() -> {
			try { dispatch(arg); }
			catch (Throwable t) { IJ.handleException(t); }
		});
	}

	static void dispatch(String arg) throws Exception {
		if ("close".equals(arg)) { if (Session.current != null) Session.current.close(); return; }
		if (Session.current != null && !IJ.showMessageWithCancel("Advanced Counter", "Save and close the open project \"" + Session.current.project.name + "\"?")) return;
		if (Session.current != null) Session.current.close();
		if ("new".equals(arg)) {
			Project p = SetupDialog.create(IJ.getInstance(), null);
			if (p == null) return;
			if (p.file.exists() && !IJ.showMessageWithCancel("Advanced Counter", "A project already exists:\n" + p.file + "\n\nOpen it instead (OK) or cancel?")) return;
			if (p.file.exists()) { background(() -> open(p.file, false)); return; }
			background(() -> createAndStart(p));
			return;
		}
		OpenDialog od = new OpenDialog("Open Advanced Counter project (" + Project.EXT + ")", null);
		if (od.getFileName() == null) return;
		File f = new File(od.getDirectory(), od.getFileName());
		if (!f.getName().endsWith(Project.EXT)) { IJ.error("Advanced Counter", "Please choose a " + Project.EXT + " file."); return; }
		boolean verify = "verify".equals(arg);
		background(() -> open(f, verify));
	}

	interface Job { void run() throws Exception; }

	/** Folder scanning can be slow (Bio-Formats); keep it off the event thread. */
	static void background(Job j) {
		new Thread(() -> {
			try { j.run(); } catch (Throwable t) { IJ.handleException(t); }
		}, "Advanced Counter scan").start();
	}

	static void startOnEdt(Project p, boolean verify) throws Exception {
		if (EventQueue.isDispatchThread()) Session.start(p, verify);
		else EventQueue.invokeAndWait(() -> Session.start(p, verify));
	}

	public static Project createAndStart(Project p) throws Exception {
		List<String> errors = new ArrayList<>();
		ImageLoader.scan(p, errors);
		p.save();
		Results.write(p);
		if (!errors.isEmpty()) IJ.log("Advanced Counter: unreadable files:\n" + String.join("\n", errors));
		startOnEdt(p, false);
		return p;
	}

	public static void open(File f, boolean verify) throws Exception {
		Project p = Project.load(f);
		List<String> errors = new ArrayList<>();
		int added = ImageLoader.scan(p, errors);
		if (added > 0) { p.save(); IJ.log("Advanced Counter: " + added + " new image(s) found in " + p.imageDir()); }
		Results.write(p);
		startOnEdt(p, verify);
	}
}
