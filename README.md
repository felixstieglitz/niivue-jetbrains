# niivue-jetbrains

![Build](https://github.com/felixstieglitz/niivue-jetbrains/workflows/Build/badge.svg)

A JetBrains plugin that opens NIfTI medical imaging files (`.nii`, `.nii.gz`) directly in IntelliJ IDEA, PyCharm, WebStorm, CLion and other JetBrains IDEs. Powered by [Niivue](https://github.com/niivue/niivue), the same WebGL2 viewer used in the [Niivue VSCode extension](https://github.com/niivue/niivue-vscode) and the standalone web app.

## What it does

Double-click a `.nii` or `.nii.gz` file in the Project View. It opens in an editor tab showing:

- **Axial, coronal, sagittal slices** in a 2×2 grid
- **Interactive 3D render** in the fourth quadrant
- **Orientation cube** indicating anatomical axes (L/R, A/P, S/I)

The viewer is HiDPI-aware and works in any JetBrains IDE with embedded Chromium (JCEF) support.

## Installation

### From source (until the plugin is published)

```bash
git clone https://github.com/felixstieglitz/niivue-jetbrains.git
cd niivue-jetbrains
./gradlew buildPlugin
```

The built `.zip` lands in `build/distributions/`. Install it in your IDE via <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙</kbd> → <kbd>Install plugin from disk…</kbd>.

### Running a sandbox for development

```bash
./gradlew runIde
```

This launches an isolated IDE instance with the plugin loaded — useful when iterating on the plugin code.

## Use cases

If you work with NIfTI files using Python tooling (`nibabel`, `dipy`, `ANTs`, `FSL`, `nilearn`) and want to inspect a volume without switching applications, this plugin gives you a fast, in-IDE viewer.

## Requirements

- A JetBrains IDE that ships with JCEF (embedded Chromium). All modern IntelliJ-based IDEs (2023.1+) qualify.
- WebGL2-capable graphics. On hardware without WebGL2 the rendering surface stays blank.

## Roadmap

Possible additions for future releases:

- DICOM (`.dcm`) support via the niivue DICOM loader
- Mesh formats (`.gii`, `.mz3`)
- Drag-and-drop overlays (load multiple volumes)
- Streaming volume transfer (current implementation Base64-encodes the file for the bridge — fine up to ~100 MB, suboptimal beyond)
- Settings panel for default colormap, background, view mode

## License & attribution

This plugin is open source. It bundles two assets from the [Niivue project](https://github.com/niivue/niivue), both © Niivue and BSD-2-Clause licensed:

- The Niivue JavaScript library (used to render the viewer)
- The Niivue brain logo (used as the plugin's Marketplace icon)

The full BSD-2-Clause notice ships with the plugin at [src/main/resources/webview/NIIVUE_LICENSE.txt](src/main/resources/webview/NIIVUE_LICENSE.txt) and covers both assets.

This plugin is a community project and is **not officially affiliated with, endorsed by, or sponsored by the Niivue project**.
