/*
 * Niivue viewer module shared by the plugin webview (index.html) and the
 * manual test bench (tests/bench.html). The host page provides the DOM
 * skeleton (#toolbar, #viewport > #gl/#meta/#status/#loc) and calls
 * NiivueViewer.init(); everything else — styles, toolbar, Niivue wiring,
 * the Swing wheel bridge entry point, and the volume load entry point —
 * lives here so both hosts run the exact same code.
 *
 * Host-specific behavior is limited to init(options):
 *   pickFile: () => Promise<{url, name, sizeMB} | null>
 *     Source of "pick another volume file" (used by Overlay > Add). The
 *     plugin omits it and the default implementation asks the IDE through
 *     the window.niivuePickFile bridge (see NiivueFileEditor); the bench
 *     passes an <input type=file> based picker.
 */
window.NiivueViewer = (function () {
    'use strict';

    // Numeric fallbacks in case a future bundle stops exporting the enums;
    // values verified against the bundled niivue 0.68.2.
    const SLICE = (window.niivue && niivue.SLICE_TYPE) ||
        { AXIAL: 0, CORONAL: 1, SAGITTAL: 2, MULTIPLANAR: 3, RENDER: 4 };
    const DRAG = (window.niivue && niivue.DRAG_MODE) ||
        { none: 0, contrast: 1, pan: 3, crosshair: 8 };

    const OVERLAY_COLORMAP = 'redyell';
    const OVERLAY_OPACITY = 0.5;

    // [depth, azimuth, elevation]; depth 2 disables the plane. Cycled by
    // View > Clip plane, only visible in the Render view.
    const CLIP_PLANES = [
        [2, 0, 0],
        [0, 0, 0],
        [0, 90, 0],
        [0, 180, 0],
        [0, 270, 0],
        [0, 0, 90],
        [0, 0, -90],
    ];

    let nv = null;
    let canvas, meta, status, loc, toolbar;
    let config = {};
    let pendingPick = null;
    let openPanel = null;

    const state = {
        smooth: true,
        colorbar: false,
        radiological: false,
        crosshair: true,
        zoomMode: false,
        clipIndex: 0,
    };

    /* ------------------------------------------------------------------ */
    /* Styles                                                              */
    /* ------------------------------------------------------------------ */

    const CSS = `
        html, body {
            margin: 0; padding: 0; height: 100%; overflow: hidden;
            background: #000; color: #ccc;
            font: 12px -apple-system, BlinkMacSystemFont, "Segoe UI", monospace;
        }
        body { display: flex; flex-direction: column; }
        #toolbar {
            flex: none; display: flex; align-items: center; gap: 2px;
            background: #1b1b1f; border-bottom: 1px solid #333;
            padding: 2px 6px; user-select: none; z-index: 20;
        }
        #viewport { flex: 1; position: relative; min-height: 0; }
        #gl { display: block; width: 100%; height: 100%; }
        #meta, #status, #loc {
            position: absolute; left: 12px;
            pointer-events: none; text-shadow: 0 0 4px #000; z-index: 5;
        }
        #meta { top: 8px; }
        #status { top: 24px; }
        #loc { bottom: 10px; }

        .tb-menu { position: relative; }
        .tb-btn {
            background: none; border: none; color: #ccc; font: inherit;
            padding: 4px 9px; border-radius: 4px; cursor: pointer;
        }
        .tb-btn:hover { background: #333; }
        .tb-btn.active { background: #2d4a6b; color: #fff; }
        .tb-btn:disabled { color: #666; cursor: default; background: none; }
        .tb-panel {
            display: none; position: absolute; top: 100%; left: 0;
            min-width: 170px; background: #26262b; border: 1px solid #444;
            border-radius: 5px; padding: 4px; margin-top: 2px;
            box-shadow: 0 4px 14px rgba(0,0,0,.6); z-index: 30;
        }
        .tb-panel.open { display: block; }
        .tb-item {
            display: flex; align-items: center; gap: 7px; width: 100%;
            background: none; border: none; color: #ccc; font: inherit;
            text-align: left; padding: 5px 9px; border-radius: 3px; cursor: pointer;
        }
        .tb-item:hover { background: #37373d; }
        .tb-item:disabled { color: #666; cursor: default; background: none; }
        .tb-item .mark { width: 13px; flex: none; text-align: center; }
        .tb-sep { height: 1px; background: #444; margin: 4px 2px; }
        .tb-note { padding: 4px 9px; color: #888; }
        .tb-row { display: flex; align-items: center; gap: 6px; padding: 4px 9px; }
        .tb-row label { flex: none; width: 62px; color: #aaa; }
        .tb-row input[type=number], .tb-row select {
            flex: 1; min-width: 0; background: #1b1b1f; color: #ccc;
            border: 1px solid #444; border-radius: 3px; font: inherit; padding: 2px 4px;
        }
        .tb-row input[type=range] { flex: 1; }
        dialog.tb-dialog {
            background: #26262b; color: #ccc; border: 1px solid #555;
            border-radius: 6px; max-width: 80vw; max-height: 80vh; padding: 12px;
        }
        dialog.tb-dialog::backdrop { background: rgba(0,0,0,.55); }
        dialog.tb-dialog pre {
            margin: 0 0 10px 0; max-height: 62vh; overflow: auto;
            font: 11px ui-monospace, Menlo, monospace; white-space: pre-wrap;
        }
    `;

    function injectStyles() {
        const style = document.createElement('style');
        style.textContent = CSS;
        document.head.appendChild(style);
    }

    /* ------------------------------------------------------------------ */
    /* Small helpers                                                       */
    /* ------------------------------------------------------------------ */

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function hasVolume() {
        return !!(nv && nv.volumes && nv.volumes.length > 0);
    }

    function hasOverlay() {
        return !!(nv && nv.volumes && nv.volumes.length > 1);
    }

    function setStatus(text) {
        status.textContent = text || '';
    }

    function closePanels() {
        if (openPanel) {
            openPanel.classList.remove('open');
            openPanel = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Menu framework                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Adds one dropdown menu to the toolbar. `render(panel)` rebuilds the
     * panel content each time it opens, so item state (checkmarks, enabled
     * flags, volume lists) is always current without bookkeeping.
     */
    function addMenu(label, render) {
        const wrap = el('div', 'tb-menu');
        const btn = el('button', 'tb-btn', label + ' ▾');
        const panel = el('div', 'tb-panel');
        btn.addEventListener('click', function (e) {
            e.stopPropagation();
            const wasOpen = panel === openPanel;
            closePanels();
            if (!wasOpen) {
                panel.textContent = '';
                render(panel);
                panel.classList.add('open');
                openPanel = panel;
            }
        });
        panel.addEventListener('click', function (e) { e.stopPropagation(); });
        wrap.appendChild(btn);
        wrap.appendChild(panel);
        toolbar.appendChild(wrap);
        return { button: btn, panel: panel };
    }

    /**
     * Panel entry. `opts`: mark (checkmark/radio cell content), disabled,
     * keepOpen (do not close the dropdown after the click — used by
     * repeat-click actions like crosshair stepping), refresh (re-render the
     * panel after the action so marks update).
     */
    function addItem(panel, label, action, opts) {
        opts = opts || {};
        const item = el('button', 'tb-item');
        const mark = el('span', 'mark', opts.mark || '');
        item.appendChild(mark);
        item.appendChild(document.createTextNode(label));
        if (opts.disabled) item.disabled = true;
        item.addEventListener('click', function () {
            action();
            if (opts.keepOpen) {
                if (opts.refresh) refreshPanel(panel);
            } else {
                closePanels();
            }
        });
        panel.appendChild(item);
        return item;
    }

    const panelRenderers = new Map();

    function refreshPanel(panel) {
        const render = panelRenderers.get(panel);
        if (render) {
            panel.textContent = '';
            render(panel);
        }
    }

    function addSep(panel) {
        panel.appendChild(el('div', 'tb-sep'));
    }

    /* ------------------------------------------------------------------ */
    /* View menu                                                           */
    /* ------------------------------------------------------------------ */

    function setSliceType(type) {
        nv.setSliceType(type);
    }

    // The five view modes cycled by "Cycle view mode" / the `v` shortcut, in
    // the same order as niivue-vscode.
    const VIEW_CYCLE = [SLICE.AXIAL, SLICE.SAGITTAL, SLICE.CORONAL, SLICE.RENDER, SLICE.MULTIPLANAR];

    function cycleViewMode() {
        // Read niivue's actual current type, not the mirrored state, so the
        // cycle never desyncs (index -1 wraps to the first entry, Axial).
        const i = VIEW_CYCLE.indexOf(nv.opts.sliceType);
        setSliceType(VIEW_CYCLE[(i + 1) % VIEW_CYCLE.length]);
    }

    // Default 3D render orientation, captured at init. Reset view restores it
    // too: dragging a render tile changes renderAzimuth/renderElevation, and
    // those persist until reset — resetting only the 2D pan (as niivue-vscode
    // does) leaves a rotated 3D view, which reads as "reset does nothing".
    let viewDefaults = null;

    function resetView() {
        nv.scene.pan2Dxyzmm = [0, 0, 0, 1];
        nv.scene.volScaleMultiplier = 1;
        if (viewDefaults) {
            nv.scene.renderAzimuth = viewDefaults.azimuth;
            nv.scene.renderElevation = viewDefaults.elevation;
        }
        nv.drawScene();
    }

    function cycleClipPlane() {
        state.clipIndex = (state.clipIndex + 1) % CLIP_PLANES.length;
        nv.setClipPlane(CLIP_PLANES[state.clipIndex].slice());
    }

    function applyToggles() {
        nv.setInterpolation(!state.smooth);
        nv.opts.isColorbar = state.colorbar;
        nv.setRadiologicalConvention(state.radiological);
        nv.setCrosshairWidth(state.crosshair ? 1 : 0);
        nv.opts.show3Dcrosshair = state.crosshair;
        nv.updateGLVolume();
        nv.drawScene();
    }

    function renderViewMenu(panel) {
        const types = [
            ['Axial', SLICE.AXIAL, '1'],
            ['Sagittal', SLICE.SAGITTAL, '2'],
            ['Coronal', SLICE.CORONAL, '3'],
            ['Render', SLICE.RENDER, '4'],
            ['Multiplanar', SLICE.MULTIPLANAR, '5'],
        ];
        types.forEach(function (t) {
            addItem(panel, t[0] + '  (' + t[2] + ')', function () { setSliceType(t[1]); }, {
                mark: nv.opts.sliceType === t[1] ? '●' : '',
                disabled: !hasVolume(),
            });
        });
        addSep(panel);
        addItem(panel, 'Cycle view mode  (V)', cycleViewMode,
            { keepOpen: true, refresh: true, disabled: !hasVolume() });
        addItem(panel, 'Clip plane (3D render)  (C)', cycleClipPlane,
            { keepOpen: true, disabled: !hasVolume() });
        addItem(panel, 'Reset view  (R)', resetView, { disabled: !hasVolume() });
        addSep(panel);
        const toggles = [
            ['Interpolation', 'smooth'],
            ['Colorbar', 'colorbar'],
            ['Radiological', 'radiological'],
            ['Crosshair', 'crosshair'],
        ];
        toggles.forEach(function (t) {
            addItem(panel, t[0], function () {
                state[t[1]] = !state[t[1]];
                applyToggles();
            }, {
                mark: state[t[1]] ? '✓' : '',
                keepOpen: true,
                refresh: true,
                disabled: !hasVolume(),
            });
        });
    }

    /* ------------------------------------------------------------------ */
    /* Zoom toggle                                                         */
    /* ------------------------------------------------------------------ */

    let zoomBtn = null;

    function setZoomMode(on) {
        state.zoomMode = on;
        // In pan mode niivue pans on drag and zooms 2D slices on scroll
        // (wheel-zoom keys off opts.dragMode, drag off dragModePrimary),
        // so both are switched together.
        nv.opts.dragMode = on ? DRAG.pan : DRAG.contrast;
        nv.opts.dragModePrimary = on ? DRAG.pan : DRAG.crosshair;
        zoomBtn.classList.toggle('active', on);
    }

    function addZoomButton() {
        zoomBtn = el('button', 'tb-btn', 'Zoom');
        zoomBtn.title = 'Toggle drag mode: pan/zoom vs. crosshair';
        zoomBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            closePanels();
            setZoomMode(!state.zoomMode);
        });
        toolbar.appendChild(zoomBtn);
    }

    /* ------------------------------------------------------------------ */
    /* ColorScale menu                                                     */
    /* ------------------------------------------------------------------ */

    function volumeLabel(i) {
        const name = (nv.volumes[i] && nv.volumes[i].name) || '';
        const role = i === 0 ? 'Volume' : 'Overlay ' + i;
        return name ? role + ' — ' + name : role;
    }

    function renderColorScaleMenu(panel) {
        if (!hasVolume()) {
            panel.appendChild(el('div', 'tb-note', 'Load a volume first'));
            return;
        }
        panel.style.minWidth = '240px';

        let idx = hasOverlay() ? nv.volumes.length - 1 : 0;

        const targetRow = el('div', 'tb-row');
        targetRow.appendChild(el('label', null, 'Target'));
        const target = el('select');
        nv.volumes.forEach(function (_, i) {
            const o = el('option', null, volumeLabel(i));
            o.value = String(i);
            target.appendChild(o);
        });
        target.value = String(idx);
        targetRow.appendChild(target);
        panel.appendChild(targetRow);

        const cmapRow = el('div', 'tb-row');
        cmapRow.appendChild(el('label', null, 'Colormap'));
        const cmap = el('select');
        nv.colormaps().forEach(function (name) {
            cmap.appendChild(el('option', null, name));
        });
        cmapRow.appendChild(cmap);
        panel.appendChild(cmapRow);

        const minRow = el('div', 'tb-row');
        minRow.appendChild(el('label', null, 'Min'));
        const min = el('input');
        min.type = 'number';
        min.step = 'any';
        minRow.appendChild(min);
        panel.appendChild(minRow);

        const maxRow = el('div', 'tb-row');
        maxRow.appendChild(el('label', null, 'Max'));
        const max = el('input');
        max.type = 'number';
        max.step = 'any';
        maxRow.appendChild(max);
        panel.appendChild(maxRow);

        const opRow = el('div', 'tb-row');
        opRow.appendChild(el('label', null, 'Opacity'));
        const opacity = el('input');
        opacity.type = 'range';
        opacity.min = '0';
        opacity.max = '1';
        opacity.step = '0.05';
        opRow.appendChild(opacity);
        panel.appendChild(opRow);

        const invRow = el('div', 'tb-row');
        invRow.appendChild(el('label', null, 'Invert'));
        const invert = el('input');
        invert.type = 'checkbox';
        invRow.appendChild(invert);
        panel.appendChild(invRow);

        function vol() { return nv.volumes[idx]; }

        function syncFromVolume() {
            const v = vol();
            if (!v) return;
            cmap.value = v.colormap || 'gray';
            min.value = v.cal_min !== undefined ? String(+v.cal_min.toPrecision(5)) : '';
            max.value = v.cal_max !== undefined ? String(+v.cal_max.toPrecision(5)) : '';
            opacity.value = v.opacity !== undefined ? String(v.opacity) : '1';
            invert.checked = !!v.colormapInvert;
        }

        target.addEventListener('change', function () {
            idx = Number(target.value);
            syncFromVolume();
        });
        cmap.addEventListener('change', function () {
            vol().colormap = cmap.value;
            nv.updateGLVolume();
        });
        min.addEventListener('change', function () {
            const v = parseFloat(min.value);
            if (isFinite(v)) { vol().cal_min = v; nv.updateGLVolume(); }
        });
        max.addEventListener('change', function () {
            const v = parseFloat(max.value);
            if (isFinite(v)) { vol().cal_max = v; nv.updateGLVolume(); }
        });
        opacity.addEventListener('input', function () {
            nv.setOpacity(idx, parseFloat(opacity.value));
        });
        invert.addEventListener('change', function () {
            vol().colormapInvert = invert.checked;
            nv.updateGLVolume();
        });

        syncFromVolume();
    }

    /* ------------------------------------------------------------------ */
    /* Overlay menu                                                        */
    /* ------------------------------------------------------------------ */

    function pickFile() {
        if (config.pickFile) return config.pickFile();
        return new Promise(function (resolve) {
            if (typeof window.niivuePickFile !== 'function' || pendingPick) {
                resolve(null);
                return;
            }
            pendingPick = resolve;
            window.niivuePickFile();
        });
    }

    /** IDE-side file chooser reports back through this global. */
    window.niivueViewerOnFilePicked = function (url, name, sizeMB) {
        const resolve = pendingPick;
        pendingPick = null;
        if (resolve) resolve(url ? { url: url, name: name, sizeMB: sizeMB || 0 } : null);
    };

    async function addOverlayFromUrl(url, name, sizeMB) {
        setStatus('Loading overlay ' + name +
            (sizeMB >= 200 ? ' (' + sizeMB + ' MB, this may take a moment)...' : '...'));
        try {
            await nv.addVolumeFromUrl({
                url: url,
                name: name,
                colormap: OVERLAY_COLORMAP,
                opacity: OVERLAY_OPACITY,
            });
            setStatus('');
        } catch (err) {
            setStatus('Overlay error: ' + (err && err.message ? err.message : String(err)));
            console.error('[Niivue] overlay load failed:', err);
        }
    }

    async function addOverlay() {
        const picked = await pickFile();
        if (picked) await addOverlayFromUrl(picked.url, picked.name, picked.sizeMB);
    }

    function removeLastOverlay() {
        if (!hasOverlay()) return;
        nv.removeVolume(nv.volumes[nv.volumes.length - 1]);
        nv.updateGLVolume();
    }

    async function replaceLastOverlay() {
        const picked = await pickFile();
        if (!picked) return;
        removeLastOverlay();
        await addOverlayFromUrl(picked.url, picked.name, picked.sizeMB);
    }

    function renderOverlayMenu(panel) {
        addItem(panel, 'Add overlay…', addOverlay, { disabled: !hasVolume() });
        addItem(panel, 'Replace last overlay…', replaceLastOverlay, { disabled: !hasOverlay() });
        addItem(panel, 'Remove last overlay', removeLastOverlay, { disabled: !hasOverlay() });
        if (hasOverlay()) {
            addSep(panel);
            panel.appendChild(el('div', 'tb-note',
                (nv.volumes.length - 1) + ' overlay(s) — adjust via ColorScale'));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Header menu                                                         */
    /* ------------------------------------------------------------------ */

    let headerDialog = null;

    function showHeaderDialog() {
        const hdr = hasVolume() && nv.volumes[0].hdr;
        if (!hdr) return;
        let text;
        try {
            text = hdr.toFormattedString();
        } catch (e) {
            text = JSON.stringify(hdr, null, 2);
        }
        if (!headerDialog) {
            headerDialog = el('dialog', 'tb-dialog');
            headerDialog.appendChild(el('pre'));
            const close = el('button', 'tb-btn', 'Close');
            close.style.background = '#37373d';
            close.addEventListener('click', function () { headerDialog.close(); });
            headerDialog.appendChild(close);
            document.body.appendChild(headerDialog);
        }
        headerDialog.querySelector('pre').textContent = text;
        headerDialog.showModal();
    }

    /**
     * Repair action for images with broken spatial metadata: forces voxel
     * size to 1 mm and the origin to 0, like niivue-vscode's
     * "Set header to 1" entry.
     */
    function setHeaderToIdentity() {
        if (!hasVolume()) return;
        nv.volumes.forEach(function (volume) {
            const hdr = volume.hdr;
            if (!hdr) return;
            hdr.pixDims[1] = 1;
            hdr.pixDims[2] = 1;
            hdr.pixDims[3] = 1;
            hdr.qoffset_x = 0;
            hdr.qoffset_y = 0;
            hdr.qoffset_z = 0;
            if (typeof volume.calculateRAS === 'function') volume.calculateRAS();
        });
        nv.updateGLVolume();
        nv.drawScene();
        showMetadata();
    }

    function renderHeaderMenu(panel) {
        addItem(panel, 'Show header', showHeaderDialog, { disabled: !hasVolume() });
        addItem(panel, 'Set voxel size to 1, origin to 0', setHeaderToIdentity,
            { disabled: !hasVolume() });
    }

    /* ------------------------------------------------------------------ */
    /* Navigation menu                                                     */
    /* ------------------------------------------------------------------ */

    function moveCrosshair(x, y, z) {
        nv.moveCrosshairInVox(x, y, z);
        nv.drawScene();
    }

    function frame4DCount() {
        const v = hasVolume() && nv.volumes[0];
        return (v && v.nFrame4D) || 1;
    }

    function stepFrame4D(delta) {
        const v = nv.volumes[0];
        const cur = nv.getFrame4D(v.id);
        const next = Math.min(Math.max(cur + delta, 0), v.nFrame4D - 1);
        if (next !== cur) nv.setFrame4D(v.id, next);
    }

    function renderNavigationMenu(panel) {
        // Vim-style crosshair keys, matching niivue-vscode: H/L left/right,
        // J/K posterior/anterior, Shift+D/U inferior/superior.
        const dirs = [
            ['Left', -1, 0, 0, 'H'], ['Right', 1, 0, 0, 'L'],
            ['Posterior', 0, -1, 0, 'J'], ['Anterior', 0, 1, 0, 'K'],
            ['Inferior', 0, 0, -1, '⇧D'], ['Superior', 0, 0, 1, '⇧U'],
        ];
        dirs.forEach(function (d) {
            addItem(panel, 'Crosshair ' + d[0].toLowerCase() + '  (' + d[4] + ')', function () {
                moveCrosshair(d[1], d[2], d[3]);
            }, { keepOpen: true, disabled: !hasVolume() });
        });
        if (frame4DCount() > 1) {
            addSep(panel);
            addItem(panel, 'Previous timepoint', function () { stepFrame4D(-1); },
                { keepOpen: true, refresh: true });
            addItem(panel, 'Next timepoint', function () { stepFrame4D(1); },
                { keepOpen: true, refresh: true });
            panel.appendChild(el('div', 'tb-note',
                'Timepoint ' + (nv.getFrame4D(nv.volumes[0].id) + 1) + ' / ' + frame4DCount()));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Toolbar assembly                                                    */
    /* ------------------------------------------------------------------ */

    function buildToolbar() {
        [
            ['View', renderViewMenu],
            null, // Zoom toggle goes here
            ['ColorScale', renderColorScaleMenu],
            ['Overlay', renderOverlayMenu],
            ['Header', renderHeaderMenu],
            ['Navigation', renderNavigationMenu],
        ].forEach(function (m) {
            if (!m) {
                addZoomButton();
                return;
            }
            const menu = addMenu(m[0], m[1]);
            panelRenderers.set(menu.panel, m[1]);
        });

        document.addEventListener('click', closePanels);
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') closePanels();
        });
    }

    /* ------------------------------------------------------------------ */
    /* Keyboard shortcuts                                                  */
    /* ------------------------------------------------------------------ */

    // Single-key shortcuts mirroring niivue-vscode.
    //
    // Why we drive these ourselves instead of using niivue's built-in keys:
    // niivue moves the crosshair once per `keydown` and relies on the browser
    // auto-repeating keydown while a key is held (there is no internal repeat
    // loop — verified in the 0.68.2 source). The JCEF/OSR webview does not
    // deliver those auto-repeats, so niivue's own keys would not repeat on
    // hold either. We therefore run a repeat timer ourselves: move on keydown,
    // keep moving on an interval, stop on keyup — hold-to-move works regardless
    // of whether the host sends auto-repeats. Ignoring `e.repeat` keeps the
    // rate ours even if some hosts do send them.
    //
    // niivue also binds these keys on its canvas (view cycle V, clip C/P, and
    // hard-coded H/J/K/L), so we intercept in the capture phase and
    // stopPropagation before the event reaches the canvas — otherwise both
    // fire and, with niivue's 50 ms debounce, a press jumps two views or
    // stalls. The configurable hotkeys are additionally blanked in the
    // constructor.
    //
    // In the plugin these fire only while the viewer webview has focus, so they
    // don't collide with the IDE keymap (bare letters aren't global IDE
    // actions; IdeaVim binds h/j/k/l only inside the code editor). Guarded so
    // typing in the ColorScale inputs or an open dialog is never hijacked.
    const SHORTCUT_KEYS = 'hHlLkKjJvVcCrR12345';
    const REPEAT_DELAY_MS = 250;   // hold this long before auto-repeat kicks in
    const REPEAT_RATE_MS = 80;     // steady repeat interval while held

    // Crosshair vectors per key (Shift+U/D are added conditionally on shift).
    const CROSSHAIR_MOVES = {
        h: [-1, 0, 0], l: [1, 0, 0], k: [0, 1, 0], j: [0, -1, 0],
    };

    let heldKey = null;
    let heldTimer = null;

    function crosshairVector(key, shift) {
        if (key === 'u') return shift ? [0, 0, 1] : null;
        if (key === 'd') return shift ? [0, 0, -1] : null;
        return CROSSHAIR_MOVES[key] || null;
    }

    function stopHold() {
        if (heldTimer !== null) {
            clearTimeout(heldTimer);
            clearInterval(heldTimer);
            heldTimer = null;
        }
        heldKey = null;
    }

    function startHold(key, vec) {
        if (heldKey === key) return; // already repeating this key
        stopHold();
        heldKey = key;
        const step = function () { moveCrosshair(vec[0], vec[1], vec[2]); };
        step(); // immediate move on press
        // Short delay before steady repeat, so a quick tap moves exactly once.
        heldTimer = setTimeout(function () {
            heldTimer = setInterval(step, REPEAT_RATE_MS);
        }, REPEAT_DELAY_MS);
    }

    function isTypingTarget() {
        const tag = document.activeElement && document.activeElement.tagName;
        return tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA';
    }

    function isShortcutKey(e) {
        if (e.metaKey || e.ctrlKey || e.altKey) return false;
        if (SHORTCUT_KEYS.indexOf(e.key) !== -1) return true;
        // Shift+U / Shift+D (superior / inferior)
        return e.shiftKey && (e.key === 'U' || e.key === 'u' || e.key === 'D' || e.key === 'd');
    }

    function installKeyboardShortcuts() {
        window.addEventListener('keydown', function (e) {
            if (isTypingTarget() || (headerDialog && headerDialog.open) || !hasVolume()) return;
            if (!isShortcutKey(e)) return;
            e.preventDefault();
            e.stopPropagation();

            const key = e.key.toLowerCase();
            const vec = crosshairVector(key, e.shiftKey);
            if (vec) {
                // Our timer drives the repeat; ignore host auto-repeats.
                if (!e.repeat) startHold(key, vec);
                return;
            }
            // Single-shot keys (views / clip / reset) — never repeat on hold.
            if (e.repeat) return;
            switch (key) {
                case '1': setSliceType(SLICE.AXIAL); break;
                case '2': setSliceType(SLICE.SAGITTAL); break;
                case '3': setSliceType(SLICE.CORONAL); break;
                case '4': setSliceType(SLICE.RENDER); break;
                case '5': setSliceType(SLICE.MULTIPLANAR); break;
                case 'v': cycleViewMode(); break;
                case 'c': cycleClipPlane(); break;
                case 'r': resetView(); break;
            }
            closePanels();
        }, true);

        // Releasing the held key (or Shift, which ends a Shift+U/D hold) stops
        // the repeat. Also swallow the keyup so niivue's keyUpListener never
        // re-fires our keys.
        window.addEventListener('keyup', function (e) {
            const key = e.key.toLowerCase();
            if (heldKey && (key === heldKey || e.key === 'Shift')) stopHold();
            if (!isTypingTarget() && hasVolume() && isShortcutKey(e)) e.stopPropagation();
        }, true);

        // Safety: never keep repeating if focus leaves the viewer mid-hold.
        window.addEventListener('blur', stopHold);
    }

    /* ------------------------------------------------------------------ */
    /* Metadata + crosshair readouts                                       */
    /* ------------------------------------------------------------------ */

    // Image metadata (top left), same format as niivue-vscode: matrix size
    // and voxel dimensions straight off the NIfTI header, plus the
    // timepoint count for 4D volumes.
    function showMetadata() {
        const hdr = nv.volumes && nv.volumes[0] && nv.volumes[0].hdr;
        if (!hdr || !hdr.dims || !hdr.dims[1]) { meta.textContent = ''; return; }
        let text = 'matrix size: ' + hdr.dims[1] + ' x ' + hdr.dims[2] + ' x ' + hdr.dims[3]
            + ', voxelsize: ' + hdr.pixDims[1].toPrecision(2)
            + ' x ' + hdr.pixDims[2].toPrecision(2)
            + ' x ' + hdr.pixDims[3].toPrecision(2);
        if (hdr.dims[4] > 1) text += ', timepoints: ' + hdr.dims[4];
        meta.textContent = text;
    }

    // Crosshair readout (bottom left): mm coordinates and voxel intensity,
    // formatted like the niivue-vscode footer. Niivue's own `string` is only
    // the fallback since it appends the 4D frame index even for 3D volumes.
    function installLocationReadout() {
        nv.onLocationChange = function (data) {
            if (!data) { loc.textContent = ''; return; }
            if (data.mm && data.values && data.values.length) {
                const p = function (n) { return String(Math.round(n * 10) / 10); };
                const v = data.values[0].value;
                loc.textContent = p(data.mm[0]) + ' x ' + p(data.mm[1]) + ' x ' + p(data.mm[2]) + ' mm'
                    + (v !== undefined && isFinite(v) ? ' : ' + String(+v.toFixed(4)) : '');
            } else {
                loc.textContent = data.string ? data.string.trim() : '';
            }
        };
    }

    /* ------------------------------------------------------------------ */
    /* Wheel bridge                                                        */
    /* ------------------------------------------------------------------ */

    // Scroll input arrives exclusively through the Swing-side bridge: the
    // IDE forwards MouseWheelEvents as window.niivueWheel(delta, x, y) calls
    // (see NiivueFileEditor.installWheelBridge), because JCEF's own wheel
    // synthesis is unreliable for macOS trackpads. All native wheel events
    // are therefore swallowed before Niivue's listener sees them; only our
    // synthetic step events pass. In the bench (no bridge) the same entry
    // point is fed from a native wheel listener, so behavior matches.
    function installWheelBridge() {
        window.addEventListener('wheel', function (e) {
            if (!e.isTrusted) return;
            e.preventDefault();
            e.stopPropagation();
        }, { capture: true, passive: false });

        // Bridge deltas are Swing preciseWheelRotation values (small
        // fractions per event for trackpads, ±1 per notch for mice), so
        // stepping is scale-independent: while events flow in a consistent
        // direction, emit at most one synthetic wheel event per interval —
        // Niivue's built-in listener then handles axis selection per tile,
        // crosshair sync across views, and 3D render-tile zoom, exactly
        // like in a plain browser.
        const GESTURE_GAP_MS = 250;   // idle time that ends a gesture
        const CONSISTENCY = 0.35;     // |net|/gross ratio required to step
        const WINDOW_MS = 150;        // max age of the netting window
        const MOMENTUM_FACTOR = 0.12; // deltas below peak*this are inertia...
        const MOMENTUM_CAP = 0.1;     // ...but never above this absolute value
                                      // (in wheel-rotation units), so a single
                                      // delta spike cannot raise the bar past
                                      // the normal event magnitudes
        let acc = 0;      // net signed delta in the current window
        let gross = 0;    // sum of |delta| in the current window
        let windowStart = 0;
        let peak = 0;     // largest |delta| seen in the current gesture
        let winCount = 0; // events in the current netting window
        let lastEventTime = 0;
        let lastStepTime = 0;
        let lastStepDir = 0;
        let stepInterval = 16; // adapts to the measured redraw cost

        window.niivueWheel = function (delta, x, y) {
            if (typeof delta !== 'number' || delta === 0 || !isFinite(delta)) return;
            if (openPanel) return; // scrolling while a dropdown is open stays in the menu
            const r = canvas.getBoundingClientRect();
            if (x < r.left || x >= r.right || y < r.top || y >= r.bottom) return;
            const mag = Math.abs(delta);
            const now = performance.now();

            if (now - lastEventTime > GESTURE_GAP_MS) {
                acc = 0; gross = 0; winCount = 0; peak = 0; windowStart = now;
            } else if (now - windowStart > WINDOW_MS) {
                acc = 0; gross = 0; winCount = 0; windowStart = now; // keep gesture peak
            }
            lastEventTime = now;
            acc += delta;
            gross += mag;
            winCount++;
            peak = Math.max(peak, mag);

            // macOS momentum phase: after the fingers lift, inertia events
            // keep flowing with far smaller, decaying deltas. Let them net
            // into the window but never step on them.
            if (mag < Math.min(peak * MOMENTUM_FACTOR, MOMENTUM_CAP)) return;

            const ratio = Math.abs(acc) / gross;
            const dir = acc > 0 ? 1 : -1;
            // Direction-change hysteresis: a step against the last step's
            // direction needs corroboration (several events in the window,
            // or a real pause since the last step), so a single stray
            // counter-event landing alone in a fresh window cannot fire a
            // backwards step.
            const dirOk = dir === lastStepDir
                || winCount >= 3
                || now - lastStepTime >= 100;
            if (now - lastStepTime >= stepInterval
                    && ratio >= CONSISTENCY
                    && dirOk) {
                const t0 = performance.now();
                canvas.dispatchEvent(new WheelEvent('wheel', {
                    clientX: x,
                    clientY: y,
                    deltaY: dir * 100,
                    bubbles: true,
                    cancelable: true,
                }));
                const drawMs = performance.now() - t0;
                // Self-throttle to the measured redraw cost so we never
                // enqueue more work than the renderer absorbs.
                stepInterval = Math.min(100, Math.max(16, drawMs * 1.5));
                lastStepTime = now;
                lastStepDir = dir;
                // Only a step (or gap/age reset above) closes the netting
                // window; a failed consistency check keeps accumulating so
                // isolated wobble events cannot pass as a fresh, trivially
                // consistent window.
                acc = 0;
                gross = 0;
                winCount = 0;
                windowStart = now;
            }
        };
    }

    /* ------------------------------------------------------------------ */
    /* Volume loading                                                      */
    /* ------------------------------------------------------------------ */

    // The URL points at the per-editor volume resource served by the
    // IDE-side request handler (same origin as this page); the fetch
    // streams the bytes straight from disk through Chromium. Loading a
    // base volume replaces everything currently shown (relevant for the
    // bench, where a new base can be chosen repeatedly).
    window.loadNiivueVolume = async function (url, name, sizeMB) {
        try {
            setStatus('Loading ' + name +
                (sizeMB >= 200 ? ' (' + sizeMB + ' MB, this may take a moment)...' : '...'));
            const resp = await fetch(url);
            if (!resp.ok) throw new Error('fetch failed: HTTP ' + resp.status);
            const buf = await resp.arrayBuffer();
            while (nv.volumes.length > 0) nv.removeVolume(nv.volumes[0]);
            await nv.loadFromArrayBuffer(buf, name);
            setStatus('');
            showMetadata();
        } catch (err) {
            setStatus('Error: ' + (err && err.message ? err.message : String(err)));
            console.error('[Niivue] load failed:', err);
        }
    };

    /* ------------------------------------------------------------------ */
    /* Init                                                                */
    /* ------------------------------------------------------------------ */

    function init(options) {
        config = options || {};
        canvas = document.getElementById('gl');
        meta = document.getElementById('meta');
        status = document.getElementById('status');
        loc = document.getElementById('loc');
        toolbar = document.getElementById('toolbar');

        injectStyles();

        nv = new niivue.Niivue({
            isResizeCanvas: true,
            isHighResolutionCapable: true,
            show3Dcrosshair: true,
            isOrientCube: true,
            logLevel: 'warn',
            multiplanarLayout: niivue.MULTIPLANAR_TYPE.GRID,
            multiplanarForceRender: true,
            // Disable niivue's built-in view/clip hotkeys; the toolbar owns
            // them (see installKeyboardShortcuts). The hard-coded H/J/K/L
            // crosshair keys aren't options, so they're additionally blocked
            // by intercepting keydown/keyup in the capture phase.
            viewModeHotKey: '',
            clipPlaneHotKey: '',
            cycleClipPlaneHotKey: '',
        });
        window.nv = nv;
        nv.attachToCanvas(canvas);

        viewDefaults = {
            azimuth: nv.scene.renderAzimuth,
            elevation: nv.scene.renderElevation,
        };

        installLocationReadout();
        installWheelBridge();
        buildToolbar();
        installKeyboardShortcuts();

        new ResizeObserver(function () { nv.resizeListener(); }).observe(canvas);

        setStatus('');
        return nv;
    }

    return {
        init: init,
        addOverlayFromUrl: function (url, name, sizeMB) {
            return addOverlayFromUrl(url, name, sizeMB || 0);
        },
        showMetadata: function () { showMetadata(); },
    };
})();
