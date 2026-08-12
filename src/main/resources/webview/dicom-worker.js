import createDcm2niix from './dcm2niix.js';

function errorMessage(error) {
    return error && error.message ? error.message : String(error);
}

function normalizedInputName(rawName, index, usedNames) {
    const fallback = 'slice-' + String(index + 1) + '.dcm';
    const flattened = String(rawName || fallback)
        .split('/').join('_')
        .split('\\').join('_')
        .replace(/\.ima$/i, '.dcm');
    const dot = flattened.lastIndexOf('.');
    const stem = dot > 0 ? flattened.slice(0, dot) : flattened;
    const extension = dot > 0 ? flattened.slice(dot) : '';
    let candidate = flattened;
    let suffix = 2;
    while (usedNames.has(candidate)) {
        candidate = stem + '-' + String(suffix) + extension;
        suffix++;
    }
    usedNames.add(candidate);
    return candidate;
}

async function loadBuffer(item) {
    if (item.buffer instanceof ArrayBuffer) return item.buffer;
    if (!item.url) throw new Error('DICOM file has neither a buffer nor a URL');
    const response = await fetch(item.url);
    if (!response.ok) throw new Error('fetch failed: HTTP ' + response.status);
    return response.arrayBuffer();
}

async function convert(items) {
    const module = await createDcm2niix({
        print: function () {},
        printErr: function () {},
    });
    module.FS.mkdir('/input');
    module.FS.mkdir('/output');

    const usedNames = new Set();
    for (let index = 0; index < items.length; index++) {
        self.postMessage({
            type: 'progress',
            stage: 'loading',
            completed: index + 1,
            total: items.length,
        });
        const buffer = await loadBuffer(items[index]);
        const name = normalizedInputName(items[index].name, index, usedNames);
        module.FS.createDataFile('/input', name, new Uint8Array(buffer), true, true);
    }

    self.postMessage({ type: 'progress', stage: 'converting' });
    const exitCode = module.callMain(['-o', '/output', '/input']);
    if (exitCode !== 0 && exitCode !== 3) {
        throw new Error('dcm2niix failed with exit code ' + exitCode);
    }

    return module.FS.readdir('/output')
        .filter(function (name) {
            return name.endsWith('.nii') || name.endsWith('.nii.gz');
        })
        .map(function (name) {
            const data = module.FS.readFile('/output/' + name);
            const buffer = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
            return { name: name, buffer: buffer };
        });
}

self.addEventListener('message', async function (event) {
    const message = event.data || {};
    if (message.type !== 'convert') return;
    try {
        const series = await convert(message.items || []);
        const transfers = series.map(function (item) { return item.buffer; });
        self.postMessage({ type: 'result', series: series }, transfers);
    } catch (error) {
        self.postMessage({ type: 'error', message: errorMessage(error) });
    }
});
