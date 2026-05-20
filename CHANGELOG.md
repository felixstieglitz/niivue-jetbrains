<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# niivue-jetbrains Changelog

## [Unreleased]

## [0.1.0]
### Added
- Initial functional release.
- File type registration for `.nii` and `.nii.gz` (NIfTI medical imaging files).
- `FileEditorProvider` that opens NIfTI files in an editor tab on double-click from the Project View.
- Embedded [Niivue](https://github.com/niivue/niivue) 0.68.2 viewer (WebGL2).
- Multiplanar 2×2 grid layout: axial, coronal, sagittal slices and 3D render.
- HiDPI-aware rendering on Retina/HiDPI displays.
- JCEF availability fallback: shows an explanatory message instead of crashing on IDE installations without embedded Chromium.
