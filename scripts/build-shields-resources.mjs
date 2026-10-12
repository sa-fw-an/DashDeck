// Builds DashDeck Shields assets: adblock-rust resources (uBlock Origin scriptlets and
// redirect resources) plus the default filter lists.
//
// Usage: node scripts/build-shields-resources.mjs <path-to-uBlock-checkout>
// Output: app/src/main/assets/shields/
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const uboDir = process.argv[2];
if (!uboDir) {
    console.error('usage: node scripts/build-shields-resources.mjs <uBlock checkout>');
    process.exit(1);
}
const outDir = path.join(import.meta.dirname, '..', 'app', 'src', 'main', 'assets', 'shields');
fs.mkdirSync(outDir, { recursive: true });

const b64 = s => Buffer.from(s).toString('base64');
const resources = [];

// Scriptlets: uBO's newer ES-module format. Each entry carries the function itself,
// which adblock-rust injects in "function style" with JSON-encoded arguments.
const { builtinScriptlets } = await import(
    pathToFileURL(path.join(uboDir, 'src/js/resources/scriptlets.js')).href
);
for (const s of builtinScriptlets) {
    const isFn = s.name.endsWith('.fn');
    resources.push({
        name: s.name,
        aliases: s.aliases ?? [],
        kind: { mime: isFn ? 'fn/javascript' : 'application/javascript' },
        content: b64(s.fn.toString()),
        dependencies: s.dependencies ?? [],
        // Trusted scriptlets are only usable by lists parsed with this permission bit.
        ...(s.requiresTrust ? { permission: 1 } : {}),
    });
}

// Redirect resources ($redirect=noopjs etc.).
const mimeFor = name => {
    const ext = path.extname(name);
    return {
        '.gif': 'image/gif', '.png': 'image/png', '.js': 'application/javascript',
        '.css': 'text/css', '.html': 'text/html', '.json': 'application/json',
        '.mp3': 'audio/mp3', '.mp4': 'video/mp4', '.txt': 'text/plain', '.xml': 'text/xml',
    }[ext] ?? 'application/octet-stream';
};
const { default: redirects } = await import(
    pathToFileURL(path.join(uboDir, 'src/js/redirect-resources.js')).href
);
const known = new Set(resources.map(r => r.name));
for (const [name, details] of redirects) {
    const file = path.join(uboDir, 'src/web_accessible_resources', name);
    if (known.has(name) || !fs.existsSync(file)) continue;
    const alias = details.alias ?? [];
    resources.push({
        name,
        aliases: Array.isArray(alias) ? alias : [alias],
        kind: { mime: mimeFor(name) },
        content: fs.readFileSync(file).toString('base64'),
    });
}

fs.writeFileSync(path.join(outDir, 'resources.json'), JSON.stringify(resources));
console.log(`resources.json: ${resources.length} resources`);
