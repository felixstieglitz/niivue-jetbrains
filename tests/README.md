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

No dev server is needed for most of the bench: classic `<script>` tags and
`file://` object URLs work without one. Pick a **Base volume** at the top, then
exercise the toolbar.

**Except for DICOM:** the dcm2niix WASM module runs in a module worker, which
browsers block on `file://`. To test **Add Image > DICOM folder**, serve the
parent of both repos over HTTP:

```
cd .. && python3 -m http.server 8765
# then open http://localhost:8765/niivue-jetbrains/tests/bench.html
```

**Add Image > Example image** needs network access (it fetches the MNI152 demo
volume from `niivue.github.io`).

## Test data

Bring your own volume files — any of the supported formats (NIfTI, NRRD,
FreeSurfer, MetaImage, MRtrix, NumPy) works. For the checks below you'll want:

| Kind | Use for |
| --- | --- |
| A plain single volume (e.g. any `.nii` / `.nii.gz`) | Base volume, all View/Zoom/Header checks |
| A second volume, ideally a segmentation mask or atlas aligned to the base | Overlay checks (renders in `redyell` at 50% opacity) |
| A large volume (a few hundred MB) | Large-file overlay / load streaming |
| A folder of DICOM slices | Add Image ▸ DICOM folder |

The cleanest overlay demo is a base volume plus a label/segmentation volume
sharing its geometry: the labelled regions pick up distinct `redyell` colours.

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
- [ ] A large volume (a few hundred MB) as overlay still loads (in the plugin this exercises the streaming path; in the bench it confirms the load logic).

### Header
- [ ] **Header > Show header** opens a dialog with the full NIfTI header dump (matrix dims, voxel sizes, datatype, sform/qform, etc.). *Close* dismisses it.
- [ ] **Header > Set voxel size to 1, origin to 0** — the top-left `voxelsize:` readout changes to `1.0 x 1.0 x 1.0` and the image rescales.

### Add Image
Needs an HTTP server for the DICOM entry and network for the example image
(see *Running it*).

- [ ] **Add Image > File(s)…** — pick two volumes at once: both open on their own canvas next to the base, the grid rebalances, and each canvas shows its filename top-right.
- [ ] **Add Image > Example image (MNI152)** — a brain volume opens on a new canvas (this one comes over the network).
- [ ] **Add Image > DICOM folder…** — pick a folder of DICOM slices: the status line shows the conversion, then a canvas appears with the converted NIfTI series. Also confirm a folder whose slices have **extension-less, UID-style filenames** (no `.dcm`/`.ima`) works identically — that exercises the name pre-filter plus the `DICM` magic-byte check.
- [ ] **Sync** — click into one canvas: the crosshair jumps to the same anatomical position on every other canvas (they may show different volumes). `H`/`J`/`K`/`L` and the Navigation menu sync too; so do drag-pan and 3D rotation.
- [ ] **Active canvas** — clicking a canvas outlines it; the metadata readout (top-left) and the ColorScale / Overlay / Header / Navigation menus then act on *that* image. View, Zoom and clip plane stay global (they change every canvas).
- [ ] **Close** — the ✕ on a canvas label removes it and the grid rebalances. The last remaining canvas cannot be closed.
- [ ] **Scroll** — the wheel scrolls slices on the canvas under the cursor only.
