'use strict';

const fs = require('fs');
const path = require('path');
const { createZip } = require('./zip');

const MAX_TOTAL_BYTES = 64 * 1024 * 1024;
const ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,63}$/;

function walk(dir, base = dir, files = []) {
  for (const name of fs.readdirSync(dir)) {
    if (name.startsWith('.') || name === 'node_modules') continue;
    const full = path.join(dir, name);
    const stat = fs.statSync(full);
    if (stat.isDirectory()) {
      walk(full, base, files);
    } else if (stat.isFile()) {
      const relative = path.relative(base, full).split(path.sep).join('/');
      files.push({ name: relative, full, stat });
    }
  }
  return files;
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    return null;
  }
}

function stripUndefined(obj) {
  const out = {};
  for (const key of Object.keys(obj)) {
    if (obj[key] !== undefined) out[key] = obj[key];
  }
  return out;
}

function normalizeManifest(dir, rawOverrides = {}) {
  const overrides = stripUndefined(rawOverrides);
  const existing = readJson(path.join(dir, 'manifest.json'));
  const pkg = readJson(path.join(dir, 'package.json')) || {};
  const folderName = path.basename(path.resolve(dir));

  if (existing) {
    return Object.assign({}, existing, overrides);
  }

  const id = overrides.id || pkg.pocket && pkg.pocket.id ||
    'local.' + folderName.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  const name = overrides.name || pkg.title || pkg.name || folderName;

  return {
    schema: 'pocket.app/1',
    id,
    name,
    version: overrides.version || pkg.version || '1.0.0',
    entry: 'index.html',
    description: pkg.description || '',
    accent: overrides.accent || '#7C5CFF',
    display: { orientation: 'auto', statusBar: 'auto', fullscreen: false, background: '#FFFFFFFF' },
    permissions: ['storage', 'haptic', 'clipboard', 'share', 'external'],
    network: { whitelist: [] }
  };
}

function validateManifest(manifest, dir) {
  if (!ID_PATTERN.test(manifest.id || '')) {
    throw new Error(`manifest.id 非法：${manifest.id}（只允许小写字母、数字、点、下划线、连字符）`);
  }
  const entry = manifest.entry || 'index.html';
  if (!fs.existsSync(path.join(dir, entry))) {
    throw new Error(`入口文件不存在：${entry}`);
  }
  if (!manifest.name) {
    throw new Error('manifest.name 不能为空');
  }
}

/**
 * @param {string} dir 应用目录
 * @param {string} outFile 输出 .pkt
 */
function packDirectory(dir, outFile, overrides = {}) {
  const absDir = path.resolve(dir);
  if (!fs.existsSync(absDir)) throw new Error(`目录不存在：${absDir}`);

  const manifest = normalizeManifest(absDir, overrides);
  validateManifest(manifest, absDir);

  const files = walk(absDir);

  // manifest 单独写一份规范化的到包根
  const manifestBuffer = Buffer.from(JSON.stringify(manifest, null, 2), 'utf8');

  const entries = files
    .filter((file) => file.name !== 'manifest.json')
    .map((file) => ({
      name: file.name,
      data: fs.readFileSync(file.full),
      mtime: file.stat.mtime
    }));

  entries.unshift({ name: 'manifest.json', data: manifestBuffer, mtime: new Date() });

  const total = entries.reduce((sum, e) => sum + e.data.length, 0);
  if (total > MAX_TOTAL_BYTES) {
    throw new Error(`应用体积 ${(total / 1024 / 1024).toFixed(1)}MB 超过 64MB 上限`);
  }

  const zipBuffer = createZip(entries);

  fs.mkdirSync(path.dirname(path.resolve(outFile)), { recursive: true });
  fs.writeFileSync(outFile, zipBuffer);

  return {
    manifest,
    file: path.resolve(outFile),
    files: entries.length,
    sourceBytes: total,
    packedBytes: zipBuffer.length
  };
}

function packHtmlFile(htmlFile, outFile, options = {}) {
  const absHtml = path.resolve(htmlFile);
  if (!fs.existsSync(absHtml)) throw new Error(`文件不存在：${absHtml}`);

  const html = fs.readFileSync(absHtml, 'utf8');
  const fallbackName = path.basename(absHtml, path.extname(absHtml));
  const name = options.name || fallbackName;
  const hash = require('crypto').createHash('sha256').update(html).digest('hex').slice(0, 6);
  const slug = name.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fa5]+/g, '-').replace(/^-|-$/g, '').slice(0, 18) || 'html';

  const manifest = {
    schema: 'pocket.app/1',
    id: options.id || `local.${slug}-${hash}`,
    name,
    version: options.version || '1.0.0',
    entry: 'index.html',
    description: options.description || '',
    accent: options.accent || '#7C5CFF',
    display: { orientation: 'auto', statusBar: 'auto', fullscreen: false, background: '#FFFFFFFF' },
    permissions: ['storage', 'http', 'clipboard', 'share', 'haptic', 'external'],
    network: { whitelist: (options.whitelist || []).slice() }
  };

  const entries = [
    { name: 'manifest.json', data: Buffer.from(JSON.stringify(manifest, null, 2), 'utf8'), mtime: new Date() },
    { name: 'index.html', data: Buffer.from(html, 'utf8'), mtime: fs.statSync(absHtml).mtime }
  ];

  const zipBuffer = createZip(entries);
  fs.mkdirSync(path.dirname(path.resolve(outFile)), { recursive: true });
  fs.writeFileSync(outFile, zipBuffer);

  return { manifest, file: path.resolve(outFile), files: 2, sourceBytes: html.length, packedBytes: zipBuffer.length };
}

module.exports = { packDirectory, packHtmlFile, normalizeManifest };
