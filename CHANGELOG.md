<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# niivue-jetbrains Changelog

## [Unreleased]
### Added
- **Add Image** menu: open further images next to the current one, each on its own canvas in a grid that rebalances as images come and go.
  - **File(s)**: pick one or more volumes through the native IDE file picker; each opens on its own canvas and streams through the request handler with no size limit.
  - **DICOM folder**: pick a folder and load its slices as one series. The slices are converted to NIfTI in the viewer by the bundled [dcm2niix](https://github.com/rordenlab/dcm2niix) WASM build; a folder holding several acquisitions yields one canvas per series.
  - **Example image**: load the MNI152 demo volume from the Niivue demo images.
- Crosshair, pan and 3D rotation are synchronized across all canvases; the active canvas is outlined and can be closed via the ✕ on its label.

### Changed
- The image metadata readout now sits on the active canvas instead of the viewport corner.
- View, Zoom and clip-plane settings apply to every canvas; ColorScale, Overlay and Header act on the active one.

## [0.1.1]

### Added

- Toolbar with **View**, **Zoom**, **ColorScale**, **Overlay**, **Header** and **Navigation** menus.
- **View**: switch between axial, sagittal, coronal, 3D render and multiplanar; cycle through all five views; cycle the 3D clip plane; reset the view (rotation, zoom and pan); toggle interpolation, colorbar, radiological convention and the crosshair.
- **Zoom**: toggle the mouse drag between crosshair navigation and pan/zoom.
- **ColorScale**: adjust colormap, min/max window, opacity and inversion, per volume or overlay.
- **Overlay**: load a second volume (e.g. a segmentation mask or statistical map) on top of the current image, then remove or replace it; it streams through the request handler with no size limit, chosen via the native IDE file picker.
- **Header**: view the full NIfTI header, or reset voxel size to 1 and origin to 0.
- **Navigation**: step the crosshair along each anatomical axis and move through 4D timepoints.
- Keyboard shortcuts: `H`/`J`/`K`/`L` (plus `Shift+U`/`Shift+D`) move the crosshair with press-and-hold repeat; `1`–`5` pick a view; `V` cycles views; `C` cycles the clip plane; `R` resets the view.
- Image metadata readout (matrix size and voxel size) and a crosshair readout (mm coordinates and voxel intensity).

### Changed

- Extracted the viewer/toolbar logic into a shared `webview/viewer.js` module, served alongside `index.html`.
- Hardened the CI workflow with a default read-only token.
- Replaced the Marketplace plugin icon with a true vector SVG.

## [0.1.0]

### Added

- Initial functional release.
- File type registration for 14 volume file formats: NIfTI (`.nii`, `.nii.gz`), NRRD (`.nrrd`, `.nhdr`), FreeSurfer (`.mgh`, `.mgz`), MetaImage (`.mha`), MRtrix (`.mif`, `.mif.gz`, `.mih`), BrainVoyager (`.v16`, `.vmr`), and NumPy (`.npy`, `.npz`).
- `FileEditorProvider` that opens supported files in an editor tab on double-click from the Project View.
- Embedded [Niivue](https://github.com/niivue/niivue) 0.68.2 viewer (WebGL2).
- Multiplanar 2×2 grid layout: axial, coronal, sagittal slices and 3D render.
- Slice scrolling with mouse wheel and macOS trackpad (including 3D zoom in the render tile), driven by a Swing-side wheel bridge for reliable input.
- HiDPI-aware rendering on Retina/HiDPI displays.
- JCEF availability fallback: shows an explanatory message instead of crashing on IDE installations without embedded Chromium.

[Unreleased]: https://github.com/felixstieglitz/niivue-jetbrains/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/felixstieglitz/niivue-jetbrains/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/felixstieglitz/niivue-jetbrains/commits/v0.1.0
