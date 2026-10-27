package advcounter;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;

/** Project creation/editing: general settings, region classes, counters and calculations. */
public class SetupDialog extends JDialog {
	static final String[] SIZES = {"Tiny", "Small", "Medium", "Large", "Extra large", "XXL", "XXXL"};

	class Row {
		final Project.Cls orig;
		final boolean region;
		final JTextField name;
		final JButton color = new JButton();
		final JComboBox<String> marker = new JComboBox<>(Project.MARKERS), size = new JComboBox<>(SIZES);
		Color c;
		final String tmpId;

		Row(Project.Cls orig, boolean region, String name, Color c) {
			this.orig = orig; this.region = region; this.c = c;
			this.name = new JTextField(name, 14);
			tmpId = orig != null ? orig.id : "new" + (tmpCounter++);
			color.setIcon(CounterPanel.swatch(c));
			color.addActionListener(e -> {
				Color n = JColorChooser.showDialog(SetupDialog.this, "Color of " + this.name.getText(), this.c);
				if (n != null) { this.c = n; color.setIcon(CounterPanel.swatch(n)); }
			});
			if (orig instanceof Project.PointClass) {
				marker.setSelectedItem(((Project.PointClass) orig).marker);
				size.setSelectedIndex(Math.max(0, Math.min(SIZES.length - 1, ((Project.PointClass) orig).size)));
			} else size.setSelectedIndex(2);
		}
	}

	private int tmpCounter = 1;
	private final Project p;
	private final boolean creating;
	private boolean ok;
	private final List<Row> regionRows = new ArrayList<>(), pointRows = new ArrayList<>();
	private final JPanel regionPanel = new JPanel(new GridBagLayout()), pointPanel = new JPanel(new GridBagLayout());
	private final List<Project.FormulaDef> formulas = new ArrayList<>();
	private final DefaultListModel<String> formulaModel = new DefaultListModel<>();
	private final JList<String> formulaList = new JList<>(formulaModel);
	private final JTextField fName = new JTextField(16), fExpr = new JTextField(30), factor = new JTextField("1", 5);
	private final JComboBox<String> varBox = new JComboBox<>(), num = new JComboBox<>(), den = new JComboBox<>();
	private final JLabel fStatus = new JLabel(" ");
	// general
	private final JTextField projName = new JTextField(20), projDir = new JTextField(28), imgDir = new JTextField(28), exts = new JTextField(28);
	private final JCheckBox recursive = new JCheckBox("Include subfolders"), autoContrast = new JCheckBox("Auto brightness/contrast on each channel when opening"),
			png = new JCheckBox("Save flattened overlay PNG per image"), roiset = new JCheckBox("Save Fiji RoiSet .zip per image");
	private final JComboBox<String> zMode = new JComboBox<>(new String[]{"Z-stack counted as one image (points keep their slice)", "Maximum intensity projection"});

	private SetupDialog(Window owner, Project p, boolean creating) {
		super(owner, creating ? "Advanced Counter — New project" : "Advanced Counter — Edit project", ModalityType.APPLICATION_MODAL);
		this.p = p;
		this.creating = creating;
		for (Project.RegionClass r : p.regions) regionRows.add(new Row(r, true, r.name, r.color));
		for (Project.PointClass c : p.points) pointRows.add(new Row(c, false, c.name, c.color));
		for (Project.FormulaDef f : p.formulas) formulas.add(new Project.FormulaDef(f.name, f.expression));

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("General", general());
		tabs.addTab("Regions", classTab(true));
		tabs.addTab("Counters", classTab(false));
		tabs.addTab("Calculations", formulaTab());
		tabs.addChangeListener(e -> refreshVariables());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton okb = new JButton(creating ? "Create project" : "OK"), cancel = new JButton("Cancel");
		okb.addActionListener(e -> { if (apply()) { ok = true; dispose(); } });
		cancel.addActionListener(e -> dispose());
		buttons.add(okb); buttons.add(cancel);
		getContentPane().add(tabs, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		rebuildRows();
		refreshFormulaList();
		setSize(720, 560);
		setLocationRelativeTo(owner);
		getRootPane().setDefaultButton(okb);
		tabsRef = tabs;
	}

	JTabbedPane tabsRef;

	/** Shows the editor for an existing project; returns true if changes were applied. */
	public static boolean edit(Window owner, Project p, int tab) {
		SetupDialog d = new SetupDialog(owner, p, false);
		d.tabsRef.setSelectedIndex(tab);
		d.setVisible(true);
		return d.ok;
	}

	/** Shows the new-project dialog; returns the project (not yet scanned/saved) or null. */
	public static Project create(Window owner, File defaultFolder) {
		Project p = new Project();
		p.regions.add(new Project.RegionClass("r1", "ROI1", Project.paletteColor(0)));
		p.regions.add(new Project.RegionClass("r2", "ROI2", Project.paletteColor(1)));
		p.points.add(new Project.PointClass("p3", "Counter1", Color.red));
		p.points.add(new Project.PointClass("p4", "Counter2", Color.green));
		p.nextId = 5;
		SetupDialog d = new SetupDialog(owner, p, true);
		if (defaultFolder != null) { d.imgDir.setText(defaultFolder.getPath()); d.projDir.setText(defaultFolder.getPath()); }
		d.setVisible(true);
		return d.ok ? p : null;
	}

	// ---------------------------------------------------------------- general tab

	private JComponent general() {
		JPanel g = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 6, 4, 6);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		projName.setText(p.name);
		exts.setText(p.extensions);
		recursive.setSelected(p.recursive);
		autoContrast.setSelected(p.autoContrast);
		png.setSelected(p.saveOverlayPng);
		roiset.setSelected(p.saveRoiSet);
		zMode.setSelectedIndex(Project.Z_MAX.equals(p.zMode) ? 1 : 0);
		if (!creating) {
			projDir.setText(p.dir().getPath());
			projDir.setEditable(false);
			imgDir.setText(p.imageDir().getPath());
		}
		int y = 0;
		y = row(g, c, y, "Project name", projName, null);
		y = row(g, c, y, "Image folder", imgDir, browse(imgDir));
		y = row(g, c, y, "Save project in", projDir, creating ? browse(projDir) : null);
		y = row(g, c, y, "", recursive, null);
		y = row(g, c, y, "File extensions", exts, null);
		y = row(g, c, y, "Z-stacks", zMode, null);
		y = row(g, c, y, "", autoContrast, null);
		y = row(g, c, y, "", roiset, null);
		y = row(g, c, y, "", png, null);
		c.gridy = y; c.gridx = 0; c.gridwidth = 3; c.weighty = 1;
		g.add(CounterPanel.hint("Each position/series of a multi-position file (e.g. CZI scenes) is a separate image. Multichannel images open as composites. "
				+ "Results are written to <project>_results.csv next to the project file after every save."), c);
		return g;
	}

	private int row(JPanel g, GridBagConstraints c, int y, String label, JComponent field, JComponent extra) {
		c.gridy = y; c.gridx = 0; c.weightx = 0; c.gridwidth = 1;
		g.add(new JLabel(label), c);
		c.gridx = 1; c.weightx = 1;
		g.add(field, c);
		if (extra != null) { c.gridx = 2; c.weightx = 0; g.add(extra, c); }
		return y + 1;
	}

	private JButton browse(JTextField f) {
		JButton b = new JButton("Browse…");
		b.addActionListener(e -> {
			JFileChooser ch = new JFileChooser(f.getText().isEmpty() ? null : new File(f.getText()));
			ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
				f.setText(ch.getSelectedFile().getPath());
				if (f == imgDir && projDir.isEditable() && projDir.getText().isEmpty()) projDir.setText(f.getText());
			}
		});
		return b;
	}

	// ---------------------------------------------------------------- class tabs

	private JComponent classTab(boolean region) {
		JPanel t = new JPanel(new BorderLayout());
		JPanel holder = new JPanel(new BorderLayout());
		holder.add(region ? regionPanel : pointPanel, BorderLayout.NORTH);
		t.add(new JScrollPane(holder), BorderLayout.CENTER);
		JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JButton add = new JButton(region ? "Add region class" : "Add counter");
		add.addActionListener(e -> {
			List<Row> rows = region ? regionRows : pointRows;
			int n = regionRows.size() + pointRows.size();
			String base = region ? "ROI" : "Counter";
			int k = rows.size() + 1;
			while (nameUsed(base + k)) k++;
			rows.add(new Row(null, region, base + k, region ? Project.paletteColor(n) : Project.paletteColor(n + 4)));
			rebuildRows();
		});
		b.add(add);
		b.add(CounterPanel.hint(region
				? "Regions are areas drawn with the brush, polygon or freehand tool. They never overlap; area is reported per image."
				: "Counters are point classes. Each point is counted in the region that contains it, plus Outside and Total."));
		t.add(b, BorderLayout.SOUTH);
		return t;
	}

	private boolean nameUsed(String n) {
		for (Row r : regionRows) if (r.name.getText().trim().equalsIgnoreCase(n)) return true;
		for (Row r : pointRows) if (r.name.getText().trim().equalsIgnoreCase(n)) return true;
		return false;
	}

	private void rebuildRows() {
		fill(regionPanel, regionRows, true);
		fill(pointPanel, pointRows, false);
	}

	private void fill(JPanel panel, List<Row> rows, boolean region) {
		panel.removeAll();
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(2, 4, 2, 4);
		c.anchor = GridBagConstraints.WEST;
		c.gridy = 0;
		String[] head = region ? new String[]{"Name", "Color", "", "", ""} : new String[]{"Name", "Color", "Marker", "Size", ""};
		for (int i = 0; i < head.length; i++) { c.gridx = i; panel.add(new JLabel("<html><b>" + head[i] + "</b></html>"), c); }
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			c.gridy = i + 1;
			c.gridx = 0; panel.add(r.name, c);
			c.gridx = 1; panel.add(r.color, c);
			if (!region) { c.gridx = 2; panel.add(r.marker, c); c.gridx = 3; panel.add(r.size, c); }
			JPanel ops = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
			JButton up = new JButton("▲"), del = new JButton("Remove");
			final int idx = i;
			up.setEnabled(i > 0);
			up.addActionListener(e -> { Collections.swap(rows, idx, idx - 1); rebuildRows(); });
			del.addActionListener(e -> { rows.remove(idx); rebuildRows(); });
			ops.add(up); ops.add(del);
			c.gridx = 4; panel.add(ops, c);
		}
		panel.revalidate();
		panel.repaint();
	}

	// ---------------------------------------------------------------- formulas tab

	private JComponent formulaTab() {
		JPanel t = new JPanel(new BorderLayout(6, 6));
		t.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		formulaList.setVisibleRowCount(8);
		formulaList.addListSelectionListener(e -> {
			int i = formulaList.getSelectedIndex();
			if (i >= 0) { fName.setText(formulas.get(i).name); fExpr.setText(formulas.get(i).expression); validateExpr(); }
		});
		t.add(new JScrollPane(formulaList), BorderLayout.NORTH);
		JPanel ed = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 4, 3, 4);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		int y = 0;
		y = row(ed, c, y, "Column name", fName, null);
		y = row(ed, c, y, "Expression", fExpr, null);
		JPanel ins = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
		JButton insert = new JButton("Insert");
		insert.addActionListener(e -> {
			Object v = varBox.getSelectedItem();
			if (v == null) return;
			int pos = fExpr.getCaretPosition();
			String s = fExpr.getText();
			fExpr.setText(s.substring(0, pos) + v + s.substring(pos));
			validateExpr();
		});
		ins.add(varBox); ins.add(insert);
		for (String op : new String[]{"+", "-", "*", "/", "(", ")"}) {
			JButton ob = new JButton(op);
			ob.setMargin(new Insets(1, 5, 1, 5));
			ob.addActionListener(e -> { fExpr.setText(fExpr.getText() + " " + op + " "); validateExpr(); });
			ins.add(ob);
		}
		y = row(ed, c, y, "Variables", ins, null);
		JPanel ratio = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
		JButton make = new JButton("Use ratio");
		ratio.add(num); ratio.add(new JLabel("÷")); ratio.add(den); ratio.add(new JLabel("×")); ratio.add(factor); ratio.add(make);
		make.addActionListener(e -> {
			if (num.getSelectedItem() == null || den.getSelectedItem() == null) return;
			String f = factor.getText().trim();
			fExpr.setText(num.getSelectedItem() + " / " + den.getSelectedItem() + (f.isEmpty() || f.equals("1") ? "" : " * " + f));
			if (fName.getText().trim().isEmpty()) fName.setText(strip(num.getSelectedItem()) + "_per_" + strip(den.getSelectedItem()));
			validateExpr();
		});
		y = row(ed, c, y, "Quick ratio", ratio, null);
		y = row(ed, c, y, "", fStatus, null);
		JPanel fb = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
		JButton add = new JButton("Add as new"), upd = new JButton("Update selected"), del = new JButton("Delete selected");
		add.addActionListener(e -> saveFormula(-1));
		upd.addActionListener(e -> saveFormula(formulaList.getSelectedIndex()));
		del.addActionListener(e -> {
			int i = formulaList.getSelectedIndex();
			if (i >= 0) { formulas.remove(i); refreshFormulaList(); }
		});
		fb.add(add); fb.add(upd); fb.add(del);
		y = row(ed, c, y, "", fb, null);
		c.gridy = y; c.gridx = 0; c.gridwidth = 3;
		ed.add(CounterPanel.hint("Variables: {Region:area} (calibrated units²), {Region:area_px}, {Region:Counter}, {Outside:Counter}, {Total:Counter}. "
				+ "Only classes of this project are accepted. Calculations are recomputed for every image already analysed; missing data gives NA."), c);
		fExpr.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			public void insertUpdate(javax.swing.event.DocumentEvent e) { validateExpr(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { validateExpr(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { validateExpr(); }
		});
		t.add(ed, BorderLayout.CENTER);
		return t;
	}

	static String strip(Object v) { return v.toString().replaceAll("[{}]", "").replace(':', '_'); }

	private void refreshFormulaList() {
		formulaModel.clear();
		for (Project.FormulaDef f : formulas) formulaModel.addElement(f.name + "  =  " + f.expression);
	}

	private void refreshVariables() {
		Project tmp = draft();
		List<String> vars = Formula.variables(tmp);
		for (JComboBox<String> b : Arrays.asList(varBox, num, den)) {
			Object sel = b.getSelectedItem();
			b.setModel(new DefaultComboBoxModel<>(vars.toArray(new String[0])));
			if (sel != null && vars.contains(sel)) b.setSelectedItem(sel);
		}
		validateExpr();
	}

	private void validateExpr() {
		if (fExpr.getText().trim().isEmpty()) { fStatus.setText(" "); return; }
		try {
			Formula.parse(fExpr.getText(), draft());
			fStatus.setForeground(new Color(0, 120, 0));
			fStatus.setText("✔ valid");
		} catch (IllegalArgumentException ex) {
			fStatus.setForeground(new Color(180, 0, 0));
			fStatus.setText("✖ " + ex.getMessage());
		}
	}

	private void saveFormula(int index) {
		String n = fName.getText().trim(), ex = fExpr.getText().trim();
		if (n.isEmpty()) { err("Enter a column name for the calculation."); return; }
		for (int i = 0; i < formulas.size(); i++) if (i != index && formulas.get(i).name.equalsIgnoreCase(n)) { err("A calculation named '" + n + "' exists."); return; }
		Project d = draft();
		if (Results.header(d).subList(0, Results.header(d).size() - d.formulas.size()).stream().anyMatch(h -> h.equalsIgnoreCase(n))) { err("'" + n + "' is already a result column."); return; }
		try { Formula.parse(ex, d); } catch (IllegalArgumentException e) { err(e.getMessage()); return; }
		if (index >= 0) { formulas.get(index).name = n; formulas.get(index).expression = ex; }
		else formulas.add(new Project.FormulaDef(n, ex));
		refreshFormulaList();
		formulaList.setSelectedIndex(index >= 0 ? index : formulas.size() - 1);
	}

	private void err(String m) { JOptionPane.showMessageDialog(this, m, "Advanced Counter", JOptionPane.WARNING_MESSAGE); }

	/** Project reflecting the dialog's current class rows (names as typed). */
	private Project draft() {
		Project d = new Project();
		for (Row r : regionRows) d.regions.add(new Project.RegionClass(r.tmpId, r.name.getText().trim(), r.c));
		for (Row r : pointRows) d.points.add(new Project.PointClass(r.tmpId, r.name.getText().trim(), r.c));
		d.formulas.addAll(formulas);
		return d;
	}

	// ---------------------------------------------------------------- apply

	private boolean apply() {
		Project d = draft();
		if (projName.getText().trim().isEmpty()) { err("Enter a project name."); return false; }
		for (Project.Cls c : d.allClasses()) {
			String e = d.checkClassName(c.name, c);
			if (e != null) { err((d.regions.contains(c) ? "Region: " : "Counter: ") + e); return false; }
		}
		if (d.allClasses().isEmpty()) { err("Define at least one region class or counter."); return false; }
		// propagate renames into formulas
		List<Project.FormulaDef> fs = new ArrayList<>();
		for (Project.FormulaDef f : formulas) fs.add(new Project.FormulaDef(f.name, f.expression));
		for (Row r : regionRows) if (r.orig != null && !r.orig.name.equals(r.name.getText().trim()))
			for (Project.FormulaDef f : fs) f.expression = Formula.rename(f.expression, r.orig.name, r.name.getText().trim(), true);
		for (Row r : pointRows) if (r.orig != null && !r.orig.name.equals(r.name.getText().trim()))
			for (Project.FormulaDef f : fs) f.expression = Formula.rename(f.expression, r.orig.name, r.name.getText().trim(), false);
		d.formulas.clear();
		d.formulas.addAll(fs);
		for (Project.FormulaDef f : fs) {
			try { Formula.parse(f.expression, d); }
			catch (IllegalArgumentException ex) { err("Calculation '" + f.name + "' is invalid: " + ex.getMessage() + "\nFix or delete it in the Calculations tab."); return false; }
		}
		File img = new File(imgDir.getText().trim());
		if (!img.isDirectory()) { err("Image folder does not exist:\n" + img); return false; }
		File pdir = creating ? new File(projDir.getText().trim().isEmpty() ? img.getPath() : projDir.getText().trim()) : p.dir();
		if (creating && !pdir.isDirectory() && !pdir.mkdirs()) { err("Cannot create project folder:\n" + pdir); return false; }
		// deleted classes that hold data
		Set<String> kept = new HashSet<>();
		for (Row r : regionRows) if (r.orig != null) kept.add(r.orig.id);
		for (Row r : pointRows) if (r.orig != null) kept.add(r.orig.id);
		List<String> lost = new ArrayList<>();
		for (Project.Cls c : p.allClasses()) if (!kept.contains(c.id) && hasData(c.id)) lost.add(c.name);
		if (!lost.isEmpty() && JOptionPane.showConfirmDialog(this, "These classes contain annotations that will be deleted:\n" + String.join(", ", lost) + "\n\nContinue?",
				"Advanced Counter", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return false;

		p.name = projName.getText().trim();
		if (creating) p.file = new File(pdir, Project.sanitize(p.name) + Project.EXT);
		p.imageFolder = Project.relativize(p.dir(), img);
		p.recursive = recursive.isSelected();
		p.extensions = exts.getText().trim().isEmpty() ? Project.DEFAULT_EXTENSIONS : exts.getText().trim();
		p.zMode = zMode.getSelectedIndex() == 1 ? Project.Z_MAX : Project.Z_STACK;
		p.autoContrast = autoContrast.isSelected();
		p.saveOverlayPng = png.isSelected();
		p.saveRoiSet = roiset.isSelected();
		List<Project.RegionClass> rs = new ArrayList<>();
		for (Row r : regionRows) {
			Project.RegionClass rc = r.orig != null ? (Project.RegionClass) r.orig : new Project.RegionClass(p.newId("r"), "", r.c);
			rc.name = r.name.getText().trim(); rc.color = r.c;
			rs.add(rc);
		}
		List<Project.PointClass> ps = new ArrayList<>();
		for (Row r : pointRows) {
			Project.PointClass pc = r.orig != null ? (Project.PointClass) r.orig : new Project.PointClass(p.newId("p"), "", r.c);
			pc.name = r.name.getText().trim(); pc.color = r.c;
			pc.marker = (String) r.marker.getSelectedItem();
			pc.size = r.size.getSelectedIndex();
			ps.add(pc);
		}
		p.regions.clear(); p.regions.addAll(rs);
		p.points.clear(); p.points.addAll(ps);
		p.formulas.clear(); p.formulas.addAll(fs);
		Set<String> ids = p.classIds();
		for (Project.ImageEntry e : p.images) e.known.retainAll(ids);
		return true;
	}

	private boolean hasData(String id) {
		for (Project.ImageEntry e : p.images)
			for (Map.Entry<String, Double> m : e.metrics.entrySet())
				if (m.getValue() > 0 && (m.getKey().equals("area:" + id) || m.getKey().endsWith(":" + id))) return true;
		return false;
	}
}
