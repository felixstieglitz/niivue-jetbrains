# Contributing to niivue-jetbrains

Thanks for your interest in improving the plugin! Bug reports, feature
requests, documentation fixes, and pull requests are all welcome.

## Development setup

You need a JDK 21. Gradle itself is provisioned by the wrapper, and the
IntelliJ Platform (the IDE the plugin is built against) is downloaded
automatically on the first build.

```bash
git clone https://github.com/felixstieglitz/niivue-jetbrains.git
cd niivue-jetbrains
./gradlew runIde        # launches a sandboxed IDE with the plugin installed
```

Open any supported volume file (e.g. a `.nii.gz`) in the sandbox IDE to see
the viewer. Sample volumes for testing are available from the
[Niivue demo images](https://github.com/niivue/niivue-demo-images) repository
and from [OpenNeuro](https://openneuro.org/).

If you develop inside IntelliJ IDEA, shared run configurations are included:
**Run Plugin**, **Run Tests**, and **Run Verifications** (in the `.run/` folder).

### Useful Gradle tasks

| Task | What it does |
|---|---|
| `./gradlew runIde` | Start a sandboxed IDE with the plugin |
| `./gradlew check` | Run tests |
| `./gradlew buildPlugin` | Build the distributable ZIP into `build/distributions/` |
| `./gradlew verifyPlugin` | Run the JetBrains Plugin Verifier against recent IDE versions |

## Project layout

| Path | Purpose |
|---|---|
| `src/main/kotlin/.../filetype/` | `NiftiFileType` — registers the supported extensions |
| `src/main/kotlin/.../editor/` | `NiivueFileEditorProvider` and `NiivueFileEditor` — the editor tab, JCEF browser, volume loading, and the Swing wheel bridge |
| `src/main/resources/webview/` | `index.html` (viewer page + scroll stepper) and the bundled `niivue.umd.js` |
| `src/main/resources/META-INF/plugin.xml` | Plugin manifest |
| `src/test/kotlin/` | Tests |

The [README's "How it works" section](README.md#how-it-works) describes the
architecture: the Kotlin side does IDE integration and data transport, the
bundled [Niivue](https://github.com/niivue/niivue) library does all medical
imaging domain work inside the webview.

### One invariant worth knowing before touching input handling

Scroll input **must not** go through JCEF's native wheel events. JCEF's wheel
synthesis is unreliable for macOS trackpads (it delivers tiny, near-alternating
deltas with no usable direction signal), which is why:

- the browser runs in **off-screen rendering mode** (a Swing component that
  receives real Swing input events),
- `NiivueFileEditor.installWheelBridge()` forwards Swing
  `MouseWheelEvent.preciseWheelRotation` into the page as
  `window.niivueWheel(delta, x, y)`, and
- `index.html` **swallows every native (trusted) wheel event** and scrolls only
  through that bridge, via a stepper that emits synthetic wheel events to
  Niivue's own listener.

If you simplify any of these three pieces away, trackpad scrolling breaks in
ways that only reproduce on real hardware — please test scroll changes with a
physical macOS trackpad *and* a mouse wheel in `runIde` before opening a PR.

## Updating the bundled Niivue

The viewer library ships inside the plugin (no network access at runtime):

1. Take `dist/niivue.umd.js` from the desired
   [niivue release](https://github.com/niivue/niivue/releases) (npm package `@niivue/niivue`).
2. Replace `src/main/resources/webview/niivue.umd.js`.
3. Update `src/main/resources/webview/NIIVUE_VERSION` to the new version number.
4. If Niivue's license text changed, refresh `NIIVUE_LICENSE.txt` as well.
5. Smoke-test in `runIde`: open a volume, scroll all four tiles, resize the window.

## Pull request guidelines

- Keep PRs focused; separate unrelated changes.
- Add an entry to the `[Unreleased]` section of [CHANGELOG.md](CHANGELOG.md)
  for user-visible changes.
- Make sure `./gradlew check` passes locally; CI additionally runs
  `buildPlugin` and `verifyPlugin` on every PR.
- For behavior that can't be covered by tests (rendering, scrolling, HiDPI),
  describe in the PR how you verified it manually.

## Reporting bugs

Please use the issue templates. The most helpful details are: IDE name and
version (**Help → About**), OS, plugin version, the file format involved, and
any stack traces from **Help → Show Log in Finder/Explorer**.
