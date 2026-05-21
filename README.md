# niivue-jetbrains

![Build](https://github.com/felixstieglitz/niivue-jetbrains/workflows/Build/badge.svg)

A JetBrains plugin that opens medical imaging volume files (NIfTI, NRRD, FreeSurfer, MetaImage, MRtrix, BrainVoyager, NumPy) directly in IntelliJ IDEA, PyCharm, WebStorm, CLion and other JetBrains IDEs. Powered by [Niivue](https://github.com/niivue/niivue), the same WebGL2 viewer used in the [Niivue VSCode extension](https://github.com/niivue/niivue-vscode) and the standalone web app.

## What it does

Double-click a supported file in the Project View. It opens in an editor tab showing:

- **Axial, coronal, sagittal slices** in a 2×2 grid
- **Interactive 3D render** in the fourth quadrant
- **Orientation cube** indicating anatomical axes (L/R, A/P, S/I)

The viewer is HiDPI-aware and works in any JetBrains IDE with embedded Chromium (JCEF) support.

## Installation

Install via the JetBrains Marketplace from within your IDE:

<kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search for "Niivue Viewer" → <kbd>Install</kbd>

## Supported formats

| Format | Extensions |
|---|---|
| NIfTI | `.nii`, `.nii.gz` |
| NRRD | `.nrrd`, `.nhdr` |
| FreeSurfer | `.mgh`, `.mgz` |
| MetaImage | `.mha` |
| MRtrix | `.mif`, `.mif.gz`, `.mih` |
| BrainVoyager | `.v`, `.v16`, `.vmr` |
| NumPy | `.npy`, `.npz` |

## Requirements

- A JetBrains IDE **2025.2 or newer** with JCEF (embedded Chromium) support — true for all major IDEs (IntelliJ IDEA, PyCharm, WebStorm, CLion, …).
- WebGL2-capable graphics. On hardware without WebGL2 the rendering surface stays blank.

## License & attribution

This plugin is open source. It bundles two assets from the [Niivue project](https://github.com/niivue/niivue), both © Niivue and BSD-2-Clause licensed:

- The Niivue JavaScript library (used to render the viewer)
- The Niivue brain logo (used as the plugin's Marketplace icon)

The full BSD-2-Clause notice ships with the plugin at [src/main/resources/webview/NIIVUE_LICENSE.txt](src/main/resources/webview/NIIVUE_LICENSE.txt) and covers both assets.

This plugin is a community project and is **not officially affiliated with, endorsed by, or sponsored by the Niivue project**.
