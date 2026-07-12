# Visual test bench

`bench.html` is a manual, visual test surface for the viewer toolbar. It loads
the exact `niivue.umd.js` and `viewer.js` that the plugin ships (via relative
`<script src>`), so what you see in the browser is what the JetBrains editor
renders — only the "pick a file" source differs (a plain file input here, the
IDE's native chooser in the plugin).

## Running it

Open `tests/bench.html` directly in a browser — double-click it, or:

```
open tests/bench.html          # macOS
```

No dev server is needed: classic `<script>` tags and `file://` object URLs work
without one. Pick a **Base volume** at the top, then exercise the toolbar.

## Test data

Volumes live in the sibling repo `../niivue-jetbrains-test-data/`:

| File | Use for |
| --- | --- |
| `01_nifti.nii` | Basic single volume, all View/Zoom/Header checks |
| `overlay_labels.nii.gz` | **Purpose-built overlay** — a 4-label segmentation mask aligned to the 16³ synthetic volumes (`01`–`10`). Load any of them as base, this on top. |
| `real_PD25_subcortical.nii.gz` | Realistic atlas — also works as an overlay (looks like a segmentation) |
| `test-volume-293mb.nii` / `test-volume-500mb.nii` | Large-file overlay / load streaming |

The cleanest overlay demo is `01_nifti.nii` (base) + `overlay_labels.nii.gz`
(overlay): four labelled blobs sit inside the sphere and pick up distinct
`redyell` colours, exactly like subcortical nuclei. `overlay_labels.nii.gz` was
generated to share the base volumes' geometry so it aligns perfectly.

## Checklist

Navigation and ColorScale are intentionally **not** listed — they are
self-evident live (crosshair moves; colors change). These are the ones worth a
deliberate look.

### View
- [ ] **Axial / Sagittal / Coronal / Render / Multiplanar** each switch the layout; the active one shows a ● dot.
- [ ] **Cycle view mode** — click repeatedly (or press `V`): steps through the five views in order Axial → Sagittal → Coronal → Render → Multiplanar → back to Axial.
- [ ] **Clip plane (3D render)** — switch to *Render* first, then click repeatedly (or press `C`): the cut plane through the 3D volume rotates through its positions.
- [ ] **Reset view** — in *Render*, drag to rotate the volume and scroll to zoom, then click **Reset view** (or press `R`): the rotation, zoom **and** 2D pan all return to the default. (This is the fixed behaviour — the old reset left the 3D rotation untouched.)
- [ ] **Interpolation** ✓ off → voxels look blocky/nearest-neighbour; on → smooth.
- [ ] **Colorbar** ✓ → a colour scale bar appears in the render.
- [ ] **Radiological** ✓ → left/right orientation flips (watch the L/R labels).
- [ ] **Crosshair** ✓ off → the crosshair lines disappear.

### Zoom
- [ ] Toggle **Zoom** on (button highlights). Drag on a 2D slice → the slice pans; scroll → it zooms in/out around the cursor.
- [ ] Toggle **Zoom** off → dragging moves the crosshair again (normal navigation). *Reset view* recenters.

### Keyboard shortcuts
Click the canvas once so the viewer has focus, then:
- [ ] **H / L** move the crosshair left / right, **J / K** posterior / anterior, **Shift+D / Shift+U** inferior / superior (watch the crosshair and the bottom-left mm readout).
- [ ] **Hold** a crosshair key → it keeps moving (press-and-hold repeat, like niivue-vscode). A quick tap moves exactly one voxel.
- [ ] **1–5** select Axial / Sagittal / Coronal / Render / Multiplanar; **V** cycles views; **C** cycles the clip plane; **R** resets the view. (These are single-shot — holding does not repeat.)
- [ ] Shortcuts are ignored while a ColorScale number field is focused (type in one — pressing `4` should enter the digit, not switch to Render).
- [ ] *(Real plugin)* confirm hold-to-move repeats **smoothly** (~10/sec). If it crawls (~1/sec), the JCEF webview reports the page as "hidden" and throttles JS timers — then the repeat has to move to a Swing timer on the Kotlin side. (In this browser bench the preview is always "hidden", so hold repeats at ~1/sec here — that's expected and not the real rate.)

### Overlay
- [ ] With a base volume loaded, **Overlay > Add overlay…** opens a file picker; choose a second volume → it renders on top in the `redyell` colormap at 50% opacity.
- [ ] The added overlay is adjustable under **ColorScale** (Target dropdown lists `Volume` + `Overlay 1`): change its colormap, min/max, opacity, invert.
- [ ] **Overlay > Remove last overlay** removes it; the base remains.
- [ ] **Overlay > Replace last overlay…** swaps the top overlay for a newly picked one.
- [ ] Large file (`test-volume-293mb.nii`) as overlay still loads (in the plugin this exercises the streaming path; in the bench it confirms the load logic).

### Header
- [ ] **Header > Show header** opens a dialog with the full NIfTI header dump (matrix dims, voxel sizes, datatype, sform/qform, etc.). *Close* dismisses it.
- [ ] **Header > Set voxel size to 1, origin to 0** — the top-left `voxelsize:` readout changes to `1.0 x 1.0 x 1.0` and the image rescales.

### Add Image
- [ ] Not implemented yet — planned for a later version (side-by-side multi-canvas comparison). No test.
