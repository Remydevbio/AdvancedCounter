# Advanced Counter (Fiji plugin)

Project-based counting for Fiji: any number of **region classes** (areas drawn with the brush, polygon or freehand tool) and **counters** (point classes). Each point is assigned to the region containing it. Annotation goes through a folder image by image, and the project can be closed and continued later. A **verify** pass lets you correct everything. A master **CSV** with areas, counts and your own **calculations** is rewritten on every save.

Built and tested with Fiji 2.18.0 / ImageJ 1.54p and JDK 21. Bytecode targets Java 8.

## Install

```sh
./build.sh      # compile + headless tests -> dist/Advanced_Counter.jar
./install.sh    # copy into Tools/Fiji/plugins (FIJI_DIR=... to override)
```

Restart Fiji, then open **Plugins › Advanced Counter**: *New Project…*, *Open Project…*, *Verify Project…*, *Close Project*.

## Project

*New Project…* asks for:

- **General:** project name, image folder, where to save the project, subfolders, file extensions, Z-stack mode, auto brightness/contrast, and the RoiSet/JPEG exports.
- **Regions:** e.g. ROI1, ROI2 (name, colour).
- **Counters:** e.g. Counter1, Counter2 (name, colour, marker, size).
- **Calculations** (optional; can be added later).

The folder is scanned:

- Every Bio-Formats-readable file (CZI, ND2, LIF, OME-TIFF, …) and plain TIFF/PNG/JPG becomes an entry.
- **Each position/series of a multi-position file (e.g. CZI scenes) is a separate image.** Pyramid sub-resolutions and label/macro/overview images are skipped.
- Z-stacks are either **one image with slider (points remember their slice)** or **maximum-intensity projected**, depending on the project option. Do not change this option after annotating: points in stack mode keep their z.
- Multichannel images always open as **composites** (all channels shown). RGB stays RGB.
- Optional per-channel auto brightness/contrast (display only, pixels untouched). The B&C, Channels and Auto contrast buttons are in the panel.

Files written next to the project file:

| File | Content |
|---|---|
| `<name>.acproj` | Project definition (JSON): classes, calculations, settings, image list, status, cached measurements |
| `<name>_results.csv` | Master results table, rewritten after every save |
| `<name>_annotations/<id>_<image>.json` | Region shapes and points of one image (plugin format) |
| `<name>_annotations/<id>_<image>_RoiSet.zip` | Same data as standard Fiji ROIs (open in the ROI Manager without the plugin) |
| `<name>_annotations/<id>_<image>_overlay.jpg` | Flattened image with annotations, JPEG (skipped above 100 Mpixel; an overlay PNG from an older version is replaced) |

Image paths are stored relative to the project, so a project folder can be moved together with its images.

## Annotating

The image window opens on the left and the **side panel** docks on the right of the screen.

- **Regions:** click a region (or press its number key). Its current shape becomes the selection, and your chosen drawing tool is selected: *Selection Brush Tool* (installed automatically from your brush plugin), Smart Brush, polygon, freehand, ImageJ brush, rectangle or oval.
  - Draw normally. Shift adds; Ctrl/Alt subtracts with the brush.
  - **Enter** or *Apply selection* stores it, and so does switching class or image.
  - **Regions never overlap: existing regions win.** If ROI2 is drawn over ROI1, the ROI1 pixels are removed from ROI2. Extending ROI1 into ROI2 leaves ROI2 unchanged. Regions are stored pixel-exact, so areas add up without double counting.
  - Clearing the selection does not delete a region. Use *Clear region* for that.
- **Counters:** click a counter (or press its number key). The *Advanced Counter points* tool is selected.

  | Action | Effect |
  |---|---|
  | Click | Add a point |
  | Drag a point | Move it |
  | **Ctrl+click** | Delete the nearest point (right-click is reserved by ImageJ for its menu) |
  | **Shift+click** | Reassign the nearest point to the active counter |
  | **Ctrl+Z** / *Undo* | Undo the last change (100 steps per image; in region mode the brush keeps its own undo) |

- **Keys 1–9** select classes in panel order: regions first, then counters.
- The panel shows live areas, counts per region (plus *outside*), and current calculation values.
- **Navigation:**
  - *Previous*/*Next* move one image; *Next unfinished* jumps to the next not-started (or needs-review) image; the drop-down jumps to any image (○ not started, ✔ annotated, ✔✔ verified, ✖ excluded).
  - Leaving an image saves it and marks it *annotated*.
  - *Exclude image* keeps an image in the table with NA values.
- *Save*, *Rescan folder* (adds new files), *Edit project…*, *Calculations…* and *Close project* are at the bottom. Reopening a project resumes at the last image.

## Verify

Choose *Verify Project…* or switch the panel to **Verify**.

- Only annotated images are visited, one after another, with all annotations shown and editable.
- **Verified, next** saves your corrections, marks the image *verified* and opens the next one.
- *Next unverified* skips images that are already verified.

## Results CSV

There is one row per image (every image is listed; images not yet done show NA).

| Columns | Content |
|---|---|
| Image info | `image, file, position, position_name, status, needs_review, area_unit` |
| Per region R | `R_area` (calibrated, unit²), `R_area_px`, then `R_<counter>` for every counter |
| Outside regions | `Outside_<counter>` (only when regions exist) |
| Whole image | `Total_<counter>` |
| Calculations | One column per calculation |

If a class is added while the project is running, images annotated earlier get **NA** for it (not a false 0) and `needs_review = yes`. Opening them again, for example in verify mode, fills it in.

## Calculations

Open *Calculations…* in the panel or the Calculations tab of the project dialog.

- **Variables:** `{Region:area}` (calibrated), `{Region:area_px}`, `{Region:Counter}`, `{Outside:Counter}`, `{Total:Counter}`.
- **Operators:** `+ - * /`, parentheses and numbers. For example, `({ROI1:Counter1} + {ROI1:Counter2}) / {ROI1:area}`.
- Only classes of the project are accepted. The variable list and the *Quick ratio* builder (A ÷ B × factor) offer exactly those.
- Expressions are checked as you type.
- Adding or editing a calculation recomputes it for **all images already analysed**. Renaming a class updates the expressions.
- Division by zero or missing data gives NA.

## Development and tests

- `./build.sh` runs `test/CoreTest.java` headless (88 checks): geometry and exclusivity, pixel-exact areas, metrics, save/reload, RoiSet, formulas, NA logic, CSV, scanning and opening of TIFF/PNG/RGB/hyperstacks and multi-position OME-TIFF via Bio-Formats, max projection, auto contrast.
- `./gui-test.sh` installs the jar and runs `test/advcounter/GuiTest.java` inside the real Fiji (119 checks). It covers:
  - the brush and polygon regions with overlap removal, and all point gestures and undo;
  - navigation and saving, the CSV and exports, z-stack points;
  - reopening a project, adding a class or calculation mid-project, verify-mode edits, exclusion;
  - the dialog rename and calculation flow, the New Project menu path, and closing the image window.

  Windows appear on screen. Input is synthesized inside Java, so your mouse and keyboard are not taken over. Offscreen screenshots go to `build/gui/`.
- The build expects a toolchain folder (JDK, ImageJ and Bio-Formats jars, `env.sh`) at `../Tools/FijiPluginDev`; set `FIJI_PLUGIN_DEV` to use another location. Fiji is expected at `../Tools/Fiji` (`FIJI_DIR` to override).

Code generated with Claude Code (Anthropic), built and tested against the Fiji install above.
