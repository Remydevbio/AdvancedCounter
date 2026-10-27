package advcounter;

import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.plugin.tool.PlugInTool;

import java.awt.event.MouseEvent;

/**
 * Toolbar tool for counting. Click: add point of the active counter. Drag an existing point: move it.
 * Ctrl+click: delete nearest point (right-click is reserved by ImageJ for its popup menu). Shift+click: reassign nearest point to the active counter.
 */
public class PointTool extends PlugInTool {
	public static final String NAME = "Advanced Counter points";
	static final double PICK_RADIUS = 8; // screen pixels
	private int dragging = -1;
	private boolean moved;

	@Override public String getToolName() { return NAME; }

	@Override public String getToolIcon() { return "C00fO11ccC0f0o4466Cf00L8088L8f8f"; }

	private Session session(ImagePlus imp) {
		Session s = Session.current;
		return s != null && !s.busy && s.imp == imp && s.ann != null && s.active != null && !s.isRegion(s.active) ? s : null;
	}

	@Override
	public void mousePressed(ImagePlus imp, MouseEvent e) {
		Session s = session(imp);
		if (s == null) return;
		ImageCanvas c = imp.getCanvas();
		int x = c.offScreenX(e.getX()), y = c.offScreenY(e.getY());
		int near = s.nearest(x, y, PICK_RADIUS / c.getMagnification());
		dragging = -1;
		if (e.isControlDown()) {
			if (near >= 0) s.removePoint(near);
		} else if (e.isShiftDown()) {
			if (near >= 0) s.reassign(near);
		} else if (near >= 0) {
			dragging = near;
			moved = false;
		} else s.addPoint(x, y);
		e.consume();
	}

	@Override
	public void mouseDragged(ImagePlus imp, MouseEvent e) {
		Session s = session(imp);
		if (s == null || dragging < 0 || dragging >= s.ann.points.size()) return;
		ImageCanvas c = imp.getCanvas();
		s.movePoint(dragging, c.offScreenX(e.getX()), c.offScreenY(e.getY()), !moved);
		moved = true;
		e.consume();
	}

	@Override
	public void mouseReleased(ImagePlus imp, MouseEvent e) {
		Session s = session(imp);
		if (s != null && dragging >= 0 && moved) s.changed();
		dragging = -1;
		e.consume();
	}
}
