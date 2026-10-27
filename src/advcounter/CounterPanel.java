package advcounter;

import ij.IJ;
import ij.ImageJ;
import ij.gui.ImageWindow;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/** Side panel docked to the right of the screen; the image window fills the space to its left. */
public class CounterPanel extends JFrame {
	static final int WIDTH = 400;
	final Session s;
	final Project p;
	private final JLabel imageLabel = new JLabel(), statusLabel = new JLabel(), message = new JLabel(" ");
	private final JComboBox<String> jump = new JComboBox<>();
	private final JButton prev = new JButton("◀ Previous"), next = new JButton("Next ▶"), nextUnf = new JButton("Next unfinished");
	private final JRadioButton annotate = new JRadioButton("Annotate"), verify = new JRadioButton("Verify");
	private final JPanel regionBox = new JPanel(new GridLayout(0, 1, 2, 2)), pointBox = new JPanel(new GridLayout(0, 1, 2, 2));
	private final Map<String, JToggleButton> classButtons = new LinkedHashMap<>();
	private final JComboBox<String> regionTool = new JComboBox<>(new String[]{"Selection Brush Tool", "Intensity Smart Brush Tool", "polygon", "freehand", "brush", "rectangle", "oval"});
	private final JCheckBox fill = new JCheckBox("Fill regions", true), labels = new JCheckBox("Region labels", true), allSlices = new JCheckBox("Points of all slices");
	private final JToggleButton exclude = new JToggleButton("Exclude image");
	private final JTextArea values = new JTextArea(7, 28);
	private boolean updating;

	CounterPanel(Session s) {
		super("Advanced Counter — " + s.project.name);
		this.s = s;
		this.p = s.project;
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override public void windowClosing(WindowEvent e) { closeProject(); }
		});
		VBox root = new VBox();
		root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		JPanel head = section("Image");
		imageLabel.setFont(imageLabel.getFont().deriveFont(Font.BOLD));
		jump.setPrototypeDisplayValue("\u25CB some_image_name_of_typical_length.czi");
		head.add(left(imageLabel));
		head.add(statusLabel);
		head.add(jump);
		jump.addActionListener(e -> { if (!updating && jump.getSelectedIndex() >= 0 && jump.getSelectedIndex() != s.index) s.open(jump.getSelectedIndex()); });
		JPanel nav = new JPanel(new GridLayout(1, 2, 3, 0));
		nav.add(prev); nav.add(next);
		prev.addActionListener(e -> s.previous());
		next.addActionListener(e -> s.next());
		nextUnf.addActionListener(e -> s.nextUnfinished());
		head.add(nav);
		head.add(nextUnf);
		JPanel mode = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		ButtonGroup g = new ButtonGroup();
		g.add(annotate); g.add(verify);
		mode.add(new JLabel("Mode:")); mode.add(annotate); mode.add(verify); mode.add(exclude);
		annotate.addActionListener(e -> s.setVerifyMode(false));
		verify.addActionListener(e -> s.setVerifyMode(true));
		exclude.addActionListener(e -> { if (!updating) s.toggleExcluded(); });
		head.add(mode);
		root.add(head);

		JPanel regions = section("Regions (area)");
		regions.add(regionBox);
		JPanel rbtn = new JPanel(new GridLayout(1, 2, 3, 0));
		JButton apply = new JButton("Apply selection ⏎"), clear = new JButton("Clear region");
		apply.addActionListener(e -> s.commitSelection());
		clear.addActionListener(e -> s.clearRegion());
		rbtn.add(apply); rbtn.add(clear);
		regions.add(rbtn);
		JPanel tool = new JPanel(new BorderLayout(4, 0));
		tool.add(new JLabel("Drawing tool:"), BorderLayout.WEST);
		tool.add(regionTool);
		regionTool.setSelectedItem(p.regionTool);
		regionTool.addActionListener(e -> {
			if (updating) return;
			p.regionTool = (String) regionTool.getSelectedItem();
			if (s.active != null && s.isRegion(s.active)) s.selectRegionTool();
		});
		regions.add(tool);
		regions.add((hint("Shift+draw adds, Ctrl/Alt subtracts. Overlaps with other regions are removed (existing region wins).")));
		root.add(regions);

		JPanel points = section("Counters (points)");
		points.add(pointBox);
		points.add((hint("Click add · drag move · Ctrl+click delete · Shift+click reassign · Ctrl+Z undo · keys 1–9 select class")));
		root.add(points);

		JPanel disp = section("Display");
		JPanel d1 = new JPanel(new GridLayout(0, 2, 3, 0));
		d1.add(fill); d1.add(labels); d1.add(allSlices);
		fill.addActionListener(e -> { s.showFill = fill.isSelected(); s.refreshOverlay(); });
		labels.addActionListener(e -> { s.showLabels = labels.isSelected(); s.refreshOverlay(); });
		allSlices.addActionListener(e -> { p.showAllSlices = allSlices.isSelected(); s.refreshOverlay(); });
		disp.add(d1);
		JPanel d2 = new JPanel(new GridLayout(1, 3, 3, 0));
		JButton bc = new JButton("B&C"), ch = new JButton("Channels"), auto = new JButton("Auto contrast");
		bc.addActionListener(e -> runOnImage("Brightness/Contrast..."));
		ch.addActionListener(e -> runOnImage("Channels Tool..."));
		auto.addActionListener(e -> { if (s.imp != null) { ImageLoader.autoContrast(s.imp); s.imp.updateAndDraw(); } });
		d2.add(bc); d2.add(ch); d2.add(auto);
		disp.add(d2);
		root.add(disp);

		JPanel res = section("Current image results");
		values.setEditable(false);
		values.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		JScrollPane vs = new JScrollPane(values);
		vs.setPreferredSize(new Dimension(300, 140));
		vs.setMinimumSize(new Dimension(100, 140));
		res.add(vs);
		root.add(res);

		JPanel bottom = new JPanel(new GridLayout(3, 2, 3, 3));
		JButton undo = new JButton("Undo"), save = new JButton("Save"), edit = new JButton("Edit project…"),
				calc = new JButton("Calculations…"), rescan = new JButton("Rescan folder"), close = new JButton("Close project");
		undo.addActionListener(e -> s.undo());
		save.addActionListener(e -> { s.commitSelection(); s.save(); refresh(); });
		edit.addActionListener(e -> editProject(0));
		calc.addActionListener(e -> editProject(3));
		rescan.addActionListener(e -> s.rescan());
		close.addActionListener(e -> closeProject());
		bottom.add(undo); bottom.add(save); bottom.add(edit); bottom.add(calc); bottom.add(rescan); bottom.add(close);
		root.add(bottom);
		root.add(left(message));
		root.end();

		setContentPane(new JScrollPane(root, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
		rebuild();
		Rectangle sb = screen();
		setBounds(sb.x + sb.width - WIDTH, sb.y, WIDTH, sb.height);
	}

	static Rectangle screen() { return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds(); }

	/** Vertical stack: each added component is a full-width row; tracks the scroll viewport width. */
	static class VBox extends JPanel implements Scrollable {
		VBox() { super(new GridBagLayout()); }

		@Override protected void addImpl(Component c, Object cons, int index) {
			if (cons == null) {
				GridBagConstraints g = new GridBagConstraints();
				g.gridx = 0; g.gridy = GridBagConstraints.RELATIVE; g.weightx = 1;
				g.fill = GridBagConstraints.HORIZONTAL; g.anchor = GridBagConstraints.NORTHWEST;
				g.insets = new Insets(1, 0, 1, 0);
				cons = g;
			}
			super.addImpl(c, cons, index);
		}

		/** Pushes rows to the top. */
		void end() {
			GridBagConstraints g = new GridBagConstraints();
			g.gridx = 0; g.gridy = GridBagConstraints.RELATIVE; g.weighty = 1;
			add(Box.createVerticalGlue(), g);
		}

		public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
		public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 96; }
		public boolean getScrollableTracksViewportWidth() { return true; }
		public boolean getScrollableTracksViewportHeight() { return false; }
	}

	static VBox section(String title) {
		VBox q = new VBox();
		q.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP));
		return q;
	}

	/** Lets long single-line text shrink (truncated with an ellipsis) instead of widening the panel. */
	static JComponent left(JComponent c) {
		Dimension d = c.getPreferredSize();
		c.setPreferredSize(new Dimension(40, Math.max(d.height, 18)));
		c.setMinimumSize(new Dimension(20, Math.max(d.height, 18)));
		return c;
	}

	static JLabel hint(String t) {
		JLabel l = new JLabel("<html><div style='width:290px'>" + t + "</div></html>");
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
		return l;
	}

	void runOnImage(String cmd) {
		if (s.imp == null) return;
		ij.WindowManager.setCurrentWindow(s.imp.getWindow());
		IJ.run(cmd);
	}

	/** Puts the image window left of the panel, below the ImageJ toolbar, zoomed to fit. */
	void placeImageWindow(ImageWindow win) {
		Rectangle sb = screen();
		int top = sb.y;
		ImageJ ij = IJ.getInstance();
		if (ij != null && ij.isVisible()) {
			Rectangle ib = ij.getBounds();
			if (ib.y < sb.y + sb.height / 3 && ib.x < getX()) top = Math.max(top, ib.y + ib.height);
		}
		int w = Math.max(300, getX() - sb.x - 4), h = Math.max(300, sb.y + sb.height - top);
		win.setLocationAndSize(sb.x, top, w, h);
	}

	static Icon swatch(Color c) {
		return new Icon() {
			public void paintIcon(Component comp, Graphics g, int x, int y) {
				g.setColor(c); g.fillRect(x, y, 14, 14);
				g.setColor(Color.darkGray); g.drawRect(x, y, 14, 14);
			}
			public int getIconWidth() { return 15; }
			public int getIconHeight() { return 15; }
		};
	}

	/** Rebuilds class buttons and image list (after project edits). */
	void rebuild() {
		updating = true;
		regionBox.removeAll();
		pointBox.removeAll();
		classButtons.clear();
		ButtonGroup g = new ButtonGroup();
		int k = 1;
		for (Project.Cls c : p.allClasses()) {
			JToggleButton b = new JToggleButton();
			b.setIcon(swatch(c.color));
			b.setHorizontalAlignment(SwingConstants.LEFT);
			b.setFocusable(false);
			final String id = c.id;
			b.addActionListener(e -> s.selectClass(id));
			b.putClientProperty("key", k <= 9 ? Integer.toString(k) : " ");
			k++;
			g.add(b);
			classButtons.put(c.id, b);
			(c instanceof Project.RegionClass ? regionBox : pointBox).add(b);
		}
		if (p.regions.isEmpty()) regionBox.add(new JLabel("No region classes (Edit project to add)"));
		if (p.points.isEmpty()) pointBox.add(new JLabel("No counters (Edit project to add)"));
		updating = false;
		refresh();
		validate();
		repaint();
	}

	void setMessage(String m) { message.setText(m); }

	void refresh() {
		if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater(this::refresh); return; }
		updating = true;
		try {
			DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
			for (Project.ImageEntry e : p.images) model.addElement(mark(e) + " " + e.displayName());
			jump.setModel(model);
			jump.setSelectedIndex(s.index >= 0 && s.index < p.images.size() ? s.index : -1);
			Project.ImageEntry e = s.entry;
			if (e == null) { imageLabel.setText("No image"); statusLabel.setText(s.summary()); }
			else {
				imageLabel.setText((s.index + 1) + " / " + p.images.size() + ": " + e.displayName() + (s.busy ? "  (loading…)" : s.imp == null ? "  (closed)" : ""));
				statusLabel.setText("<html><div style='width:290px'>Status: <b>" + e.status + "</b>" + (p.needsReview(e) ? " — <font color='#c06000'>needs review (new classes)</font>" : "") + "</div></html>");
			}
			exclude.setSelected(e != null && Project.EXCLUDED.equals(e.status));
			annotate.setSelected(!s.verifyMode);
			verify.setSelected(s.verifyMode);
			next.setText(s.verifyMode ? "Verified, next ▶" : "Next ▶");
			nextUnf.setText(s.verifyMode ? "Next unverified" : "Next unfinished");
			allSlices.setSelected(p.showAllSlices);
			regionTool.setSelectedItem(p.regionTool);
			Map<String, Double> m = s.ann == null ? null : s.ann.metrics(p);
			for (Project.Cls c : p.allClasses()) {
				JToggleButton b = classButtons.get(c.id);
				if (b == null) continue;
				b.setSelected(c.id.equals(s.active));
				String key = (String) b.getClientProperty("key");
				String v = "";
				if (m != null && c instanceof Project.RegionClass) {
					double px = m.getOrDefault("area:" + c.id, 0.0);
					v = px == 0 ? "—" : area(px * e.pixelWidth * e.pixelHeight) + " " + e.unit + "²  (" + (long) px + " px)";
				} else if (m != null) {
					v = String.valueOf(m.getOrDefault("count:total:" + c.id, 0.0).longValue());
					List<String> parts = new ArrayList<>();
					for (Project.RegionClass r : p.regions) parts.add(r.name + " " + m.getOrDefault("count:" + r.id + ":" + c.id, 0.0).longValue());
					if (!p.regions.isEmpty()) parts.add("outside " + m.getOrDefault("count:outside:" + c.id, 0.0).longValue());
					if (!parts.isEmpty()) v += "  (" + String.join(", ", parts) + ")";
				}
				b.setText("<html><div style='width:290px'><b>" + key + "</b>&nbsp; " + esc(c.name) + "&nbsp;&nbsp;<font color='#555555'>" + esc(v) + "</font></div></html>");
			}
			values.setText(currentValues(m));
			values.setCaretPosition(0);
			boolean ok = !s.busy;
			prev.setEnabled(ok); next.setEnabled(ok && s.entry != null); nextUnf.setEnabled(ok); jump.setEnabled(ok);
		} finally { updating = false; }
	}

	static String esc(String t) { return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

	static String mark(Project.ImageEntry e) {
		switch (e.status) {
			case Project.VERIFIED: return "✔✔";
			case Project.ANNOTATED: return "✔";
			case Project.EXCLUDED: return "✖";
			default: return "○";
		}
	}

	static String area(double v) { return Results.fmt(Math.abs(v) >= 100 ? Math.rint(v * 10) / 10 : v); }

	String currentValues(Map<String, Double> m) {
		if (m == null || s.entry == null) return s.summary();
		Project.ImageEntry tmp = new Project.ImageEntry();
		tmp.status = Project.ANNOTATED;
		tmp.known.addAll(p.classIds());
		tmp.metrics = m;
		tmp.pixelWidth = s.entry.pixelWidth;
		tmp.pixelHeight = s.entry.pixelHeight;
		StringBuilder b = new StringBuilder();
		List<String> errors = new ArrayList<>();
		Map<String, Formula> fs = Results.compile(p, errors);
		if (p.formulas.isEmpty()) b.append("No calculations defined (Calculations…)\n");
		for (Project.FormulaDef f : p.formulas) {
			Formula fo = fs.get(f.name);
			b.append(f.name).append(" = ").append(fo == null ? "invalid" : Results.fmt(fo.eval(tmp))).append('\n');
		}
		for (String er : errors) b.append("! ").append(er).append('\n');
		b.append('\n').append(s.summary());
		return b.toString();
	}

	void editProject(int tab) {
		if (s.busy) return;
		s.commitSelection();
		if (SetupDialog.edit(this, p, tab)) s.projectEdited();
	}

	void closeProject() {
		if (s.busy) return;
		s.close();
		IJ.showStatus("Advanced Counter: project saved and closed");
	}
}
