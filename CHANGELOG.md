<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# niivue-jetbrains Changelog

## [Unreleased]

## [0.1.0]
### Added
- Initial functional release.
- File type registration for 15 volume file formats: NIfTI (`.nii`, `.nii.gz`), NRRD (`.nrrd`, `.nhdr`), FreeSurfer (`.mgh`, `.mgz`), MetaImage (`.mha`), MRtrix (`.mif`, `.mif.gz`, `.mih`), BrainVoyager (`.v`, `.v16`, `.vmr`), and NumPy (`.npy`, `.npz`).
- `FileEditorProvider` that opens supported files in an editor tab on double-click from the Project View.
- Embedded [Niivue](https://github.com/niivue/niivue) 0.68.2 viewer (WebGL2).
- Multiplanar 2×2 grid layout: axial, coronal, sagittal slices and 3D render.
- Slice scrolling with mouse wheel and macOS trackpad (including 3D zoom in the render tile), driven by a Swing-side wheel bridge for reliable input.
- HiDPI-aware rendering on Retina/HiDPI displays.
- JCEF availability fallback: shows an explanatory message instead of crashing on IDE installations without embedded Chromium.
