# niivue-jetbrains

![Build](https://github.com/felixstieglitz/niivue-jetbrains/workflows/Build/badge.svg)

A JetBrains plugin that opens medical imaging volume files (NIfTI, NRRD, FreeSurfer, MetaImage, MRtrix, BrainVoyager, NumPy) directly in IntelliJ IDEA, PyCharm, WebStorm, CLion and other JetBrains IDEs. Powered by [Niivue](https://github.com/niivue/niivue), the same WebGL2 viewer used in the [Niivue VSCode extension](https://github.com/niivue/niivue-vscode) and the standalone web app.

## What it does

Double-click a supported file in the Project View. It opens in an editor tab showing:

- **Axial, coronal, sagittal slices** in a 2×2 grid
- **Interactive 3D render** in the fourth quadrant
- **Orientation cube** indicating anatomical axes (L/R, A/P, S/I)
- **Scroll through slices** with the mouse wheel or trackpad; scrolling over the 3D tile zooms

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
| BrainVoyager | `.v16`, `.vmr` |
| NumPy | `.npy`, `.npz` |

## Requirements

- A JetBrains IDE **2025.2 or newer** with JCEF (embedded Chromium) support - true for all major IDEs (IntelliJ IDEA, PyCharm, WebStorm, CLion, …).
- WebGL2-capable graphics. On hardware without WebGL2 the rendering surface stays blank.

## How it works

The plugin registers a `FileEditorProvider` that claims the supported file
extensions (see `plugin.xml`). When you double-click a supported file,
the JetBrains IDE creates a per-tab editor backed by a `JBCefBrowser` - an embedded
Chromium instance. A per-browser CEF request handler serves the viewer page, the
[Niivue](https://github.com/niivue/niivue) JavaScript bundle, and the volume
bytes from a virtual `http://localhost` origin; these requests are intercepted
inside Chromium and never touch the network.

The page fetches the volume from a per-editor URL, and Chromium streams the
bytes directly from a file stream - no Base64 encoding, no `executeJavaScript`
payloads, and no size limit beyond renderer memory. Niivue then handles format
detection (NIfTI, NRRD, MGH, MetaImage, etc., plus gzip decompression) and
WebGL2 rendering.

Scroll input deliberately bypasses JCEF, whose own wheel-event synthesis is
unreliable for macOS trackpads: the browser runs in off-screen rendering mode
so it is an ordinary Swing component, a Swing `MouseWheelListener` forwards
`preciseWheelRotation` plus the cursor position into the page as
`window.niivueWheel(delta, x, y)` calls, and the page swallows every native
wheel event. A small stepper in the webview coalesces that stream into
discrete slice steps and hands them to Niivue's built-in wheel listener as
synthetic wheel events, which drives per-tile axis selection, crosshair sync
across views, and 3D zoom exactly as in a plain browser.

Multiplanar slices (axial, coronal, sagittal) and an interactive 3D render
are shown in a 2×2 grid via Niivue's `multiplanarForceRender` option. Per-tab
browser disposal cascades from the `FileEditor` via IntelliJ's `Disposer`,
so closing a tab releases the native Chromium instance cleanly.

Files of 200 MB or more show a hint in the loading overlay that loading may
take a moment; there is no hard size cap beyond what the Chromium renderer's
memory can hold.

## Contributing

Contributions are welcome — bug reports, format requests, and pull requests alike.
See [CONTRIBUTING.md](CONTRIBUTING.md) for the development setup (`./gradlew runIde`
gives you a sandboxed IDE with the plugin installed), the project layout, and
how to update the bundled Niivue version.

## License & attribution

This plugin is open source. It bundles two assets from the [Niivue project](https://github.com/niivue/niivue), both © Niivue and BSD-2-Clause licensed:

- The Niivue JavaScript library (used to render the viewer)
- The Niivue brain logo (used as the plugin's Marketplace icon)

The full BSD-2-Clause notice ships with the plugin at [src/main/resources/webview/NIIVUE_LICENSE.txt](src/main/resources/webview/NIIVUE_LICENSE.txt) and covers both assets.

This plugin is a community project and is **not officially affiliated with, endorsed by, or sponsored by the Niivue project**.
