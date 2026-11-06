'use strict';

const http = require('http');
const dgram = require('dgram');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { describePackage } = require('./pkt-reader');
const { createZip } = require('./zip');

const DISCOVER_PORT_OFFSET = 1;
const MAX_UPLOAD = 64 * 1024 * 1024;

function lanAddresses() {
  const result = [];
  for (const [name, list] of Object.entries(os.networkInterfaces() || {})) {
    for (const item of list || []) {
      if (item.family !== 'IPv4' && item.family !== 4) continue;
      if (item.internal) continue;
      result.push({ name, address: item.address });
    }
  }
  return result;
}

function bestAddressFor(peerIp) {
  const addrs = lanAddresses().map((a) => a.address);
  if (!addrs.length) return peerIp;
  const prefix = String(peerIp).split('.').slice(0, 3).join('.');
  const match = addrs.find((a) => a.split('.').slice(0, 3).join('.') === prefix);
  return match || addrs[0];
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

function scanLibrary(dir) {
  if (!fs.existsSync(dir)) return [];
  const files = fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith('.pkt'));
  const apps = [];
  for (const file of files) {
    const full = path.join(dir, file);
    try {
      const info = describePackage(full);
      if (!info) continue;
      apps.push({
        file,
        id: info.manifest.id || file,
        name: info.manifest.name || file,
        version: info.manifest.version || '1.0.0',
        description: info.manifest.description || '',
        accent: info.manifest.accent || '#7C5CFF',
        size: info.size,
        modifiedAt: fs.statSync(full).mtimeMs,
        hasIcon: !!info.iconData
      });
    } catch (e) {
      // 损坏的包跳过
    }
  }
  apps.sort((a, b) => b.modifiedAt - a.modifiedAt);
  return apps;
}

/** 读取电脑端放置的宿主 APK 信息（pocket-host.apk + 可选 pocket-host.json）。 */
function readHostInfo(dir) {
  const apk = path.join(dir, 'pocket-host.apk');
  if (!fs.existsSync(apk)) return null;
  let meta = {};
  const metaFile = path.join(dir, 'pocket-host.json');
  if (fs.existsSync(metaFile)) {
    try {
      meta = JSON.parse(fs.readFileSync(metaFile, 'utf8'));
    } catch (e) {
      meta = {};
    }
  }
  const stat = fs.statSync(apk);
  return {
    file: 'pocket-host.apk',
    size: stat.size,
    version: String(meta.version || '1.0.0'),
    versionCode: Number(meta.versionCode || 0),
    notes: String(meta.notes || ''),
    updatedAt: stat.mtimeMs
  };
}

function findPackage(dir, file) {
  const safe = path.basename(file);
  if (!safe.toLowerCase().endsWith('.pkt')) return null;
  const full = path.join(dir, safe);
  if (!fs.existsSync(full)) return null;
  return full;
}

function slugify(text) {
  return String(text)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 24) || 'app';
}

function uniqueFile(dir, base, ext) {
  let name = base + ext;
  let i = 1;
  while (fs.existsSync(path.join(dir, name))) {
    name = `${base}-${i}${ext}`;
    i++;
    if (i > 999) break;
  }
  return name;
}

/** 把上传的 HTML 包装成 .pkt；同名应用复用原 id，实现数据不丢的覆盖更新 */
function packHtmlBuffer(dir, htmlBuffer, originalName) {
  const title = path.basename(originalName, path.extname(originalName)).trim() || '新应用';
  const baseSlug = slugify(title);
  const norm = (s) => String(s || '').replace(/\s+/g, '').toLowerCase();
  // 只按应用名匹配；中文名 slug 为空时不能拿来当匹配条件，否则会误伤其它中文应用
  const existing = scanLibrary(dir).find((a) => norm(a.name) === norm(title));
  const hash = crypto.createHash('sha256').update(htmlBuffer).digest('hex').slice(0, 6);
  const id = existing ? existing.id : (baseSlug ? `local.${baseSlug}-${hash}` : `local.app-${hash}`);
  let version = '1.0.1';
  if (existing) {
    const parts = String(existing.version || '1.0.0').split('.');
    const last = Number(parts[parts.length - 1]) || 0;
    parts[parts.length - 1] = String(last + 1);
    version = parts.join('.');
  }

  const manifest = {
    schema: 'pocket.app/1',
    id,
    name: title.slice(0, 40),
    version,
    entry: 'index.html',
    description: '由电脑端上传的 HTML 自动打包',
    accent: '#7C5CFF',
    display: {
      orientation: 'auto',
      statusBar: 'auto',
      fullscreen: false,
      edgeToEdge: false,
      background: '#FFFFFFFF'
    },
    permissions: ['storage', 'http', 'clipboard', 'share', 'haptic', 'external'],
    network: { whitelist: [] }
  };

  const entries = [
    { name: 'manifest.json', data: Buffer.from(JSON.stringify(manifest, null, 2), 'utf8'), mtime: new Date() },
    { name: 'index.html', data: htmlBuffer, mtime: new Date() }
  ];

  // 同名应用沿用原文件名，覆盖旧包，避免电脑端出现重复条目
  const file = existing ? existing.file : uniqueFile(dir, baseSlug || 'app', '.pkt');
  fs.writeFileSync(path.join(dir, file), createZip(entries));
  return { file, manifest };
}

/** 本机 HTML 收藏：文件名清洗 + 扫描 */
function sanitizeHtmlName(raw) {
  const base = path.basename(String(raw || '')).replace(/\.html?$/i, '').trim() || 'html';
  return base.replace(/[\\/:*?"<>|\s]+/g, '-').slice(0, 60) + '.html';
}

function scanHtmlLibrary(dir) {
  try {
    return fs.readdirSync(dir)
      .filter((f) => /\.html?$/i.test(f))
      .map((file) => {
        const full = path.join(dir, file);
        const st = fs.statSync(full);
        return {
          file,
          name: file.replace(/\.html?$/i, ''),
          size: st.size,
          modifiedAt: st.mtimeMs
        };
      })
      .sort((a, b) => b.modifiedAt - a.modifiedAt);
  } catch (e) {
    return [];
  }
}

function readBody(req, limit = MAX_UPLOAD) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('文件超过 64MB 上限'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function escapeHtml(text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderPage(options) {
  const { name, port, addresses, apps, host } = options;
  const library = options.library || [];
  const addrList = addresses.length
    ? addresses.map((a) => `<span class="chip">${a.address}:${port}</span>`).join('')
    : '<span class="chip muted">未检测到局域网地址</span>';

  const cards = apps.length
    ? apps.map((app) => `
      <div class="card" data-file="${escapeHtml(app.file)}">
        <div class="icon" style="--accent:${app.accent}">
          ${app.hasIcon ? `<img src="/icon/${encodeURIComponent(app.id)}" alt="">` : `<span>${escapeHtml((app.name || '?').slice(0, 1))}</span>`}
        </div>
        <div class="meta">
          <div class="row1"><h3>${escapeHtml(app.name)}</h3><span class="ver">v${escapeHtml(app.version)}</span></div>
          <p>${escapeHtml(app.description || '未填写描述')}</p>
          <div class="sub">${formatSize(app.size)} · ${escapeHtml(app.file)}</div>
        </div>
        <div class="ops">
          <button class="mini danger" data-del="${escapeHtml(app.file)}" title="从电脑端移除">✕</button>
        </div>
      </div>`).join('')
    : `<div class="empty"><div class="emptyIcon">＋</div><h3>还没有插件包</h3><p>把 .pkt 或 .html 拖到上面的上传区，手机端即可立刻看到</p></div>`;

  const libraryCards = library.length
    ? library.map((item) => `
      <div class="card libCard" data-lib="${escapeHtml(item.file)}">
        <div class="icon lib" style="--accent:#22B8CF"><span>H</span></div>
        <div class="meta">
          <div class="row1"><h3>${escapeHtml(item.name)}</h3></div>
          <div class="sub">${formatSize(item.size)} · ${escapeHtml(item.file)} · ${new Date(item.modifiedAt).toLocaleString('zh-CN')}</div>
        </div>
        <div class="ops">
          <button class="mini text" data-lib-open title="在电脑上打开">打开</button>
          <button class="mini text" data-lib-migrate title="把旧页面里的数据导进来">迁数据</button>
          <button class="mini text" data-lib-send title="打包并发到手机">发到手机</button>
          <button class="mini text" data-lib-rename title="重命名">✎</button>
          <button class="mini danger" data-lib-rm title="从收藏中删除">✕</button>
        </div>
      </div>`).join('')
    : `<div class="empty"><div class="emptyIcon">H</div><h3>还没有本机 HTML</h3><p>把平时在电脑上用的 .html 拖到上面的上传区，就会收进这里；随时能打开或发到手机</p></div>`;

  const hostSection = host
    ? `<div class="panel">
        <h2>手机端 App 安装包</h2>
        <div class="hostLine">
          <div>
            <strong>口袋手机端 v${escapeHtml(host.version)}</strong>
            <span>${formatSize(host.size)} · 这是给手机安装的包，电脑只负责保存和分发</span>
          </div>
          <a class="hostDl" href="/host.apk" download>下载到手机</a>
        </div>
        ${host.notes ? `<p class="notes">手机浏览器打开本页也能直接下载；或在手机端 设置 → 检查更新 里更新。</p>` : ''}
      </div>`
    : `<div class="panel">
        <h2>手机端 App 安装包</h2>
        <div class="hostLine">
          <div>
            <strong>还没有放置手机端安装包</strong>
            <span>把 pocket-host.apk 放进项目 dist/ 目录，手机端即可下载更新（电脑本身不会安装）</span>
          </div>
        </div>
      </div>`;

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>口袋 · 电脑端</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  :root { color-scheme: light dark; }
  body {
    font-family: -apple-system, "PingFang SC", "HarmonyOS Sans SC", "Microsoft YaHei", system-ui, sans-serif;
    background: #F6F5FA; color: #14121C; min-height: 100vh; padding: 36px 24px 64px;
  }
  @media (prefers-color-scheme: dark) { body { background: #0C0B10; color: #F2F0F7; } }
  body::before {
    content: ""; position: fixed; inset: 0; pointer-events: none;
    background:
      radial-gradient(38% 28% at 8% 0%, rgba(124,92,255,.15), transparent 70%),
      radial-gradient(34% 24% at 96% 4%, rgba(255,107,157,.12), transparent 70%);
  }
  .wrap { position: relative; max-width: 900px; margin: 0 auto; }
  header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
  h1 { font-size: 34px; letter-spacing: -1px; font-weight: 800; }
  h1 em { font-style: normal; background: linear-gradient(120deg,#7C5CFF,#FF6B9D); -webkit-background-clip: text; background-clip: text; color: transparent; }
  .sub { margin-top: 8px; font-size: 13px; color: #6B6780; }
  @media (prefers-color-scheme: dark) { .sub { color: #9C97B0; } }
  .status { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: #0E9F6E; font-weight: 600;
            background: rgba(46,213,115,.12); padding: 7px 13px; border-radius: 999px; }
  .dot { width: 7px; height: 7px; border-radius: 50%; background: #2ED573; box-shadow: 0 0 8px #2ED573; }

  .panel { margin-top: 22px; padding: 18px 20px; border-radius: 22px; background: rgba(255,255,255,.82); border: 1px solid rgba(124,92,255,.14); }
  @media (prefers-color-scheme: dark) { .panel { background: rgba(255,255,255,.05); border-color: rgba(255,255,255,.09); } }
  .panel h2 { font-size: 12px; letter-spacing: 1.4px; text-transform: uppercase; color: #8E89A3; margin-bottom: 10px; }
  .steps { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; font-size: 13.5px; }
  .step { background: rgba(124,92,255,.1); color: #5B43C9; padding: 7px 12px; border-radius: 999px; font-weight: 600; }
  @media (prefers-color-scheme: dark) { .step { background: rgba(124,92,255,.18); color: #B7A6FF; } }
  .arrow { color: #A8A3B8; }
  .chips { margin-top: 14px; display: flex; flex-wrap: wrap; gap: 8px; }
  .chip { font-family: ui-monospace, Menlo, monospace; font-size: 12.5px; padding: 6px 10px; border-radius: 8px; background: rgba(34,184,207,.12); color: #0E7490; }
  @media (prefers-color-scheme: dark) { .chip { color: #7DD3E8; } }
  .chip.muted { background: rgba(0,0,0,.05); color: #8E89A3; }

  .drop {
    display: block; margin-top: 22px; padding: 34px 20px; border-radius: 22px; text-align: center; cursor: pointer;
    border: 2px dashed rgba(124,92,255,.35); background: rgba(124,92,255,.045);
    transition: all .18s ease;
  }
  .drop.hover { border-color: #7C5CFF; background: rgba(124,92,255,.12); transform: scale(1.01); }
  .drop.libDrop { margin-top: 12px; padding: 22px 18px; border-color: rgba(34,184,207,.35); background: rgba(34,184,207,.05); }
  .drop.libDrop.hover { border-color: #0E7490; background: rgba(34,184,207,.12); }
  .drop.libDrop .dropIcon { width: 44px; height: 44px; margin-bottom: 8px; background: rgba(34,184,207,.14); color: #0E7490; font-size: 18px; font-weight: 800; }
  .libTip { margin-top: 8px; font-size: 12px; line-height: 1.55; color: #8E89A3; }
  .libTip code { font-family: ui-monospace, Menlo, monospace; background: rgba(0,0,0,.05); padding: 1px 5px; border-radius: 5px; }
  .dropIcon { width: 54px; height: 54px; margin: 0 auto 12px; border-radius: 50%;
              background: rgba(124,92,255,.13); color: #7C5CFF; display: grid; place-items: center; font-size: 24px; }
  .drop h3 { font-size: 16px; font-weight: 700; }
  .drop p { margin-top: 7px; font-size: 12.5px; color: #8E89A3; }
  .drop input { display: none; }
  .uploadStatus { margin-top: 12px; font-size: 12.5px; color: #5B43C9; min-height: 18px; }
  @media (prefers-color-scheme: dark) { .uploadStatus { color: #B7A6FF; } }

  .sectionTitle { margin: 32px 0 14px; display: flex; align-items: center; gap: 10px; }
  .sectionTitle h2 { font-size: 20px; font-weight: 700; }
  .count { font-size: 12px; color: #7C5CFF; background: rgba(124,92,255,.12); padding: 3px 9px; border-radius: 999px; font-weight: 700; }
  .hint { margin-left: auto; font-size: 12px; color: #8E89A3; }
  .grid { display: grid; gap: 12px; }
  .card { display: flex; align-items: center; gap: 16px; padding: 15px 16px; border-radius: 20px;
          background: rgba(255,255,255,.86); border: 1px solid rgba(0,0,0,.05); transition: transform .15s ease, box-shadow .15s ease; }
  .card:hover { transform: translateY(-1px); box-shadow: 0 12px 28px rgba(60,40,120,.09); }
  @media (prefers-color-scheme: dark) { .card { background: rgba(255,255,255,.05); border-color: rgba(255,255,255,.08); } }
  .icon { width: 50px; height: 50px; border-radius: 15px; flex-shrink: 0; display: grid; place-items: center;
          overflow: hidden; color: #fff; font-weight: 800; font-size: 20px;
          background: linear-gradient(135deg, var(--accent), color-mix(in srgb, var(--accent) 55%, #fff)); }
  .icon img { width: 100%; height: 100%; object-fit: cover; }
  .meta { flex: 1; min-width: 0; }
  .row1 { display: flex; align-items: baseline; gap: 8px; }
  .row1 h3 { font-size: 15.5px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .ver { font-size: 11.5px; color: #8E89A3; }
  .meta p { margin-top: 3px; font-size: 12.5px; color: #6B6780; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  @media (prefers-color-scheme: dark) { .meta p { color: #9C97B0; } }
  .meta .sub { margin-top: 5px; font-size: 11px; color: #A8A3B8; }
  .ops { display: flex; gap: 8px; flex-shrink: 0; }
  .mini { width: 36px; height: 36px; border-radius: 11px; display: grid; place-items: center; text-decoration: none;
          border: 1px solid rgba(0,0,0,.07); background: rgba(0,0,0,.03); color: #5B43C9; font-size: 15px; cursor: pointer; }
  .mini:hover { background: rgba(124,92,255,.12); }
  .mini.danger { color: #E5484D; }
  .mini.danger:hover { background: rgba(229,72,77,.12); }
  .mini.text { width: auto; padding: 0 11px; font-size: 12.5px; font-weight: 600; }
  @media (prefers-color-scheme: dark) { .mini { border-color: rgba(255,255,255,.1); background: rgba(255,255,255,.05); color: #B7A6FF; } }

  .empty { text-align: center; padding: 50px 20px; border-radius: 20px; border: 1px dashed rgba(124,92,255,.28); }
  .emptyIcon { width: 54px; height: 54px; margin: 0 auto 12px; border-radius: 50%; background: rgba(124,92,255,.1); color: #7C5CFF; display: grid; place-items: center; font-size: 22px; }
  .empty h3 { font-size: 16px; }
  .empty p { margin-top: 6px; font-size: 12.5px; color: #8E89A3; }
  .headRight { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
  .shutdown { border: 1px solid rgba(0,0,0,.08); background: rgba(0,0,0,.035); color: #6B6780;
              padding: 7px 12px; border-radius: 999px; font-size: 12px; cursor: pointer; }
  .shutdown:hover { background: rgba(229,72,77,.1); color: #E5484D; border-color: rgba(229,72,77,.25); }
  @media (prefers-color-scheme: dark) { .shutdown { border-color: rgba(255,255,255,.12); background: rgba(255,255,255,.06); color: #C9C4DA; } }
  .hostLine { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
  .hostLine strong { font-size: 15px; }
  .hostLine span { margin-left: 10px; font-size: 12px; color: #8E89A3; }
  .tagOk { font-size: 11.5px; font-weight: 700; color: #0E9F6E; background: rgba(46,213,115,.12); padding: 4px 10px; border-radius: 999px; }
  .hostDl { font-size: 12px; font-weight: 600; color: #fff; text-decoration: none; padding: 7px 13px; border-radius: 999px;
            background: linear-gradient(120deg,#7C5CFF,#9A7BFF); }
  .hostDl:hover { filter: brightness(1.06); }
  .notes { margin-top: 8px; font-size: 12.5px; color: #6B6780; }
  @media (prefers-color-scheme: dark) { .notes { color: #9C97B0; } }
  footer { margin-top: 40px; text-align: center; font-size: 11.5px; color: #A8A3B8; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div>
      <h1>口袋 <em>电脑端</em></h1>
      <p class="sub">${escapeHtml(name)} · ${escapeHtml(os.hostname())}</p>
    </div>
    <div class="headRight">
      <div class="status"><span class="dot"></span>服务运行中</div>
      <button class="shutdown" id="shutdown">关闭服务</button>
    </div>
  </header>

  <div class="panel">
    <h2>手机怎么取</h2>
    <div class="steps">
      <span class="step">1 手机连同一 Wi-Fi</span><span class="arrow">→</span>
      <span class="step">2 打开口袋</span><span class="arrow">→</span>
      <span class="step">3 点 + → 从电脑导入</span>
    </div>
    <div class="chips">${addrList}</div>
  </div>

  ${hostSection}

  <label class="drop" id="drop">
    <input type="file" id="fileInput" multiple accept=".pkt,.html,.htm">
    <div class="dropIcon">↑</div>
    <h3>发给手机：把 .pkt 或 .html 拖到这里</h3>
    <p>.html 会自动打包成手机应用；这个入口不会写进电脑端的 HTML 收藏 · 单文件最大 64MB</p>
    <div class="uploadStatus" id="uploadStatus"></div>
  </label>

  <div class="sectionTitle">
    <h2>已收录的小玩意</h2>
    <span class="count" id="count">${apps.length}</span>
    <span class="hint">手机上进入「从电脑导入」即可看到这里的全部内容</span>
  </div>

  <div class="grid" id="grid">${cards}</div>

  <div class="sectionTitle">
    <h2>本机 HTML 收藏</h2>
    <span class="count" id="libCount">${library.length}</span>
    <span class="hint">电脑上自己的 HTML，只在这里管理，不会发到手机</span>
  </div>

  <label class="drop libDrop" id="libDrop">
    <input type="file" id="libInput" multiple accept=".html,.htm">
    <div class="dropIcon">H</div>
    <h3>只收进电脑端收藏：把 .html 拖到这里</h3>
    <p>可以在电脑上直接打开、改名、随时找回；想给手机用再点「发到手机」 · 单文件最大 64MB</p>
    <div class="uploadStatus" id="libStatus"></div>
  </label>

  <p class="libTip">原来直接双击打开这个文件时，数据存在那个页面的浏览器存储里；换到这里打开后点一次「迁数据」，在旧页面按 F12 → 控制台运行 <code>copy(JSON.stringify(localStorage))</code>，把结果粘进来即可。</p>

  <div class="grid" id="libGrid">${libraryCards}</div>

  <footer>口袋 Pocket · 电脑端服务 · 端口 ${port} · 目录 ${escapeHtml(options.dir)}</footer>
</div>

<script>
(function () {
  var grid = document.getElementById('grid');

  function uploadOne(file, target) {
    return new Promise(function (resolve, reject) {
      var kind = /\.html?$/i.test(file.name) ? 'html' : 'pkt';
      var url = '/api/upload?filename=' + encodeURIComponent(file.name) +
                '&kind=' + kind + '&size=' + file.size + '&target=' + target;
      var xhr = new XMLHttpRequest();
      xhr.open('POST', url);
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.upload.onprogress = function (e) {
        if (e.lengthComputable) {
          setStatus(target, '上传 ' + file.name + ' … ' + Math.round(e.loaded / e.total * 100) + '%');
        }
      };
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) {
          try { resolve(JSON.parse(xhr.responseText)); } catch (e) { resolve({ ok: true }); }
        } else {
          var msg = '上传失败';
          try { msg = JSON.parse(xhr.responseText).error || msg; } catch (e) {}
          reject(new Error(msg));
        }
      };
      xhr.onerror = function () { reject(new Error('网络错误')); };
      xhr.send(file);
    });
  }

  function setStatus(target, text, tone) {
    var el = document.getElementById(target === 'library' ? 'libStatus' : 'uploadStatus');
    if (!el) return;
    el.textContent = text;
    el.style.color = tone === 'error' ? '#E5484D' : '';
  }

  async function handleFiles(files, target) {
    if (!files || !files.length) return;
    var list = Array.prototype.slice.call(files);
    for (var i = 0; i < list.length; i++) {
      var f = list[i];
      var allowed = target === 'library' ? /\.html?$/i.test(f.name) : /\.(pkt|html?)$/i.test(f.name);
      if (!allowed) {
        setStatus(target, '跳过不支持的文件：' + f.name, 'error');
        continue;
      }
      try {
        var res = await uploadOne(f, target);
        setStatus(target, '已收录：' + (res.name || f.name));
      } catch (err) {
        setStatus(target, err.message + '：' + f.name, 'error');
        return;
      }
    }
    setStatus(target, '上传完成，刷新中…');
    setTimeout(function () { location.reload(); }, 400);
  }

  function bindDrop(dropId, inputId, target) {
    var drop = document.getElementById(dropId);
    var input = document.getElementById(inputId);
    if (!drop || !input) return;
    input.addEventListener('change', function () { handleFiles(input.files, target); });
    ['dragenter', 'dragover'].forEach(function (name) {
      drop.addEventListener(name, function (e) {
        e.preventDefault(); e.stopPropagation();
        drop.classList.add('hover');
      });
    });
    ['dragleave', 'drop'].forEach(function (name) {
      drop.addEventListener(name, function (e) {
        e.preventDefault(); e.stopPropagation();
        drop.classList.remove('hover');
      });
    });
    drop.addEventListener('drop', function (e) {
      var dt = e.dataTransfer;
      if (dt && dt.files) handleFiles(dt.files, target);
    });
  }

  bindDrop('drop', 'fileInput', 'phone');
  bindDrop('libDrop', 'libInput', 'library');

  grid.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-del]');
    if (!btn) return;
    var file = btn.getAttribute('data-del');
    if (!confirm('确定从电脑端移除「' + file + '」吗？\\n手机端已安装的副本不受影响。')) return;
    fetch('/api/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ file: file })
    }).then(function (r) {
      if (r.ok) location.reload();
      else alert('删除失败');
    });
  });

  var libGrid = document.getElementById('libGrid');
  if (libGrid) {
    libGrid.addEventListener('click', function (e) {
      var btn = e.target.closest('[data-lib-open],[data-lib-migrate],[data-lib-send],[data-lib-rename],[data-lib-rm]');
      if (!btn) return;
      var card = btn.closest('[data-lib]');
      var file = card ? card.getAttribute('data-lib') : '';
      if (!file) return;

      if (btn.hasAttribute('data-lib-open')) {
        window.open('/library/' + encodeURIComponent(file), '_blank');
        return;
      }

      if (btn.hasAttribute('data-lib-migrate')) {
        window.open('/library/' + encodeURIComponent(file) + '?import=1', '_blank');
        return;
      }

      if (btn.hasAttribute('data-lib-send')) {
        btn.disabled = true;
        btn.textContent = '发送中…';
        fetch('/api/library/send', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ file: file })
        }).then(function (r) { return r.json(); }).then(function (j) {
          if (!j.ok) throw new Error(j.error || '发送失败');
          location.reload();
        }).catch(function (err) {
          btn.disabled = false;
          btn.textContent = '发到手机';
          alert(err.message);
        });
        return;
      }

      if (btn.hasAttribute('data-lib-rename')) {
        var title = card.querySelector('h3').textContent;
        var next = prompt('重命名收藏（不用写 .html）', title);
        if (!next || next === title) return;
        fetch('/api/library/rename', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ file: file, name: next })
        }).then(function (r) { return r.json(); }).then(function (j) {
          if (j.ok) location.reload(); else alert(j.error || '重命名失败');
        });
        return;
      }

      if (btn.hasAttribute('data-lib-rm')) {
        if (!confirm('从本机收藏中删除「' + file + '」？\\n已发到手机的应用不受影响。')) return;
        fetch('/api/library/delete', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ file: file })
        }).then(function (r) { return r.json(); }).then(function (j) {
          if (j.ok) location.reload(); else alert(j.error || '删除失败');
        });
      }
    });
  }

  var shutdown = document.getElementById('shutdown');
  shutdown.addEventListener('click', function () {
    if (!confirm('关闭电脑端服务？\\n关闭后手机暂时无法从这台电脑导入，重新双击桌面快捷方式即可再启动。')) return;
    shutdown.disabled = true;
    shutdown.textContent = '正在关闭…';
    fetch('/api/shutdown', { method: 'POST' }).then(function () {
      document.body.innerHTML = '<div style="min-height:100vh;display:grid;place-items:center;text-align:center;font-family:system-ui,sans-serif;color:#6B6780">' +
        '<div><h1 style="font-size:24px;color:#14121C;margin-bottom:10px">服务已关闭</h1>' +
        '<p style="font-size:13px">可以关闭这个页面了。需要时再双击桌面的「口袋电脑端」。</p></div></div>';
    }).catch(function () {
      alert('关闭失败，请直接关闭启动窗口');
    });
  });
})();
</script>
</body>
</html>`;
}

function contentTypeForPkt() {
  return 'application/vnd.pocket.app';
}

function startServer(options) {
  const dir = path.resolve(options.dir || 'dist');
  const port = Number(options.port || 8570);
  const name = options.name || os.hostname();
  const addresses = lanAddresses();
  const pidFile = path.join(dir, '.pocket-server.json');
  // 本机 HTML 收藏目录：默认项目和 dist 同级
  const libraryDir = path.resolve(options.library || path.join(dir, '..', 'library'));

  fs.mkdirSync(dir, { recursive: true });
  fs.mkdirSync(libraryDir, { recursive: true });

  let stopping = false;
  let udp = null;

  // 后台常驻：任何单次请求异常都不应该让整个服务退出
  process.on('uncaughtException', (err) => {
    console.error('[pocket] 未捕获异常（服务继续运行）:', (err && err.stack) || err);
  });
  process.on('unhandledRejection', (reason) => {
    console.error('[pocket] 未处理的 Promise 拒绝（服务继续运行）:', reason);
  });

  function stopService() {
    if (stopping) return;
    stopping = true;
    try {
      if (fs.existsSync(pidFile)) fs.unlinkSync(pidFile);
    } catch (e) {}
    try {
      if (udp) udp.close();
    } catch (e) {}
    try {
      server.close(() => setTimeout(() => process.exit(0), 60));
    } catch (e) {
      setTimeout(() => process.exit(0), 60);
    }
    setTimeout(() => process.exit(0), 600);
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const pathname = decodeURIComponent(url.pathname);

    try {
      if (pathname === '/' || pathname === '/index.html') {
        const apps = scanLibrary(dir);
        const html = renderPage({
          name,
          port,
          addresses,
          apps,
          library: scanHtmlLibrary(libraryDir),
          host: readHostInfo(dir),
          dir: path.relative(process.cwd(), dir) || dir
        });
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(html);
        return;
      }

      if (pathname === '/api/apps') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(JSON.stringify({ name, port, apps: scanLibrary(dir) }));
        return;
      }

      if (pathname === '/api/library') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(JSON.stringify({ ok: true, library: scanHtmlLibrary(libraryDir) }));
        return;
      }

      if (pathname === '/api/library/send' && req.method === 'POST') {
        const payload = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8') || '{}');
        const file = path.basename(payload.file || '');
        const full = path.join(libraryDir, file);
        if (!file || !fs.existsSync(full)) {
          res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: '收藏里没有这个文件' }));
          return;
        }
        const html = fs.readFileSync(full, 'utf8');
        const result = packHtmlBuffer(dir, Buffer.from(html, 'utf8'), file);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({
          ok: true,
          file: result.file,
          id: result.manifest.id,
          version: result.manifest.version,
          name: result.manifest.name
        }));
        return;
      }

      if (pathname === '/api/library/delete' && req.method === 'POST') {
        const payload = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8') || '{}');
        const file = path.basename(payload.file || '');
        const full = path.join(libraryDir, file);
        if (!file || !fs.existsSync(full)) {
          res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: '收藏里没有这个文件' }));
          return;
        }
        fs.unlinkSync(full);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: true }));
        return;
      }

      if (pathname === '/api/library/rename' && req.method === 'POST') {
        const payload = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8') || '{}');
        const file = path.basename(payload.file || '');
        const full = path.join(libraryDir, file);
        if (!file || !fs.existsSync(full)) {
          res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: '收藏里没有这个文件' }));
          return;
        }
        const next = sanitizeHtmlName(payload.name || '');
        const target = path.join(libraryDir, next);
        if (fs.existsSync(target) && target !== full) {
          res.writeHead(409, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: '同名收藏已存在' }));
          return;
        }
        fs.renameSync(full, target);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: true, file: next }));
        return;
      }

      if (pathname.startsWith('/library/')) {
        const file = path.basename(decodeURIComponent(pathname.slice('/library/'.length)));
        const full = path.join(libraryDir, file);
        if (!file || !fs.existsSync(full)) {
          res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end('not found');
          return;
        }
        // 打开收藏的 HTML 时，额外注入一个「旧数据导入」脚本：
        // 浏览器的 localStorage 按来源隔离，直接从 file:// 打开的数据不会自动跟过来
        let html = fs.readFileSync(full, 'utf8');
        const importScript = `
<script>
(function(){
  if (location.search.indexOf('import=1') === -1) return;
  var tip = '把旧数据粘贴到这里（原来的 file:// 页面按 F12 → 控制台运行：\\ncopy(JSON.stringify(localStorage))\\n然后把结果粘贴进来）';
  var text = prompt(tip);
  if (!text) return;
  try {
    var obj = JSON.parse(text);
    var n = 0;
    Object.keys(obj).forEach(function (k) { localStorage.setItem(k, String(obj[k])); n++; });
    alert('已导入 ' + n + ' 项，刷新后生效');
    location.href = location.pathname;
  } catch (e) {
    alert('格式不对：' + e.message);
  }
})();
</script>
`;
        if (/<\/body>/i.test(html)) {
          html = html.replace(/<\/body>/i, importScript + '</body>');
        } else {
          html += importScript;
        }
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(html);
        return;
      }

      if (pathname === '/api/health') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: true, service: 'pocket-host', name, port }));
        return;
      }

      if (pathname === '/api/host') {
        const info = readHostInfo(dir);
        if (!info) {
          res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: '电脑端还没有放置 pocket-host.apk' }));
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(JSON.stringify({ ok: true, ...info }));
        return;
      }

      if (pathname === '/host.apk') {
        const info = readHostInfo(dir);
        const full = path.join(dir, 'pocket-host.apk');
        if (!info || !fs.existsSync(full)) {
          res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end('not found');
          return;
        }
        const stat = fs.statSync(full);
        res.writeHead(200, {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Length': stat.size,
          'Content-Disposition': 'attachment; filename="pocket-host.apk"'
        });
        fs.createReadStream(full).pipe(res);
        return;
      }

      if (pathname === '/api/shutdown' && req.method === 'POST') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: true, message: '服务正在关闭' }));
        setTimeout(stopService, 120);
        return;
      }

      if (pathname === '/api/upload' && req.method === 'POST') {
        const filename = path.basename(url.searchParams.get('filename') || 'upload.pkt');
        const kind = (url.searchParams.get('kind') || '').toLowerCase();
        const target = (url.searchParams.get('target') || 'phone').toLowerCase();
        const body = await readBody(req);
        if (!body.length) {
          res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: '空文件' }));
          return;
        }

        const isHtml = kind === 'html' || /\.html?$/i.test(filename);

        // 入口一：本机 HTML 收藏，只存电脑端，不发给手机
        if (target === 'library') {
          if (!isHtml) {
            res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
            res.end(JSON.stringify({ error: '这个入口只收 .html' }));
            return;
          }
          const safe = sanitizeHtmlName(filename);
          fs.writeFileSync(path.join(libraryDir, safe), body);
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ ok: true, file: safe, name: safe.replace(/\.html?$/i, '') }));
          return;
        }

        // 入口二：发给手机（.pkt 直接收，.html 自动打成应用；不再往收藏里塞）
        let result;
        if (isHtml) {
          result = packHtmlBuffer(dir, body, filename);
        } else {
          const safe = uniqueFile(dir, slugify(filename.replace(/\.pkt$/i, '')), '.pkt');
          const pktPath = path.join(dir, safe);
          fs.writeFileSync(pktPath, body);
          const info = describePackage(pktPath);
          if (!info) {
            fs.unlinkSync(pktPath);
            res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
            res.end(JSON.stringify({ error: '不是有效的 .pkt 包' }));
            return;
          }
          result = { file: safe, manifest: info.manifest };
        }

        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({
          ok: true,
          file: result.file,
          id: result.manifest.id,
          name: result.manifest.name
        }));
        return;
      }

      if (pathname === '/api/delete' && req.method === 'POST') {
        const body = await readBody(req, 1024 * 1024);
        let payload;
        try { payload = JSON.parse(body.toString('utf8')); } catch (e) { payload = null; }
        const file = payload && payload.file ? path.basename(payload.file) : '';
        const full = file ? findPackage(dir, file) : null;
        if (!full) {
          res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: '文件不存在' }));
          return;
        }
        fs.unlinkSync(full);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: true }));
        return;
      }

      if (pathname.startsWith('/icon/')) {
        const id = pathname.slice('/icon/'.length);
        const app = scanLibrary(dir).find((a) => a.id === id);
        if (!app) { res.writeHead(404); res.end('not found'); return; }
        try {
          const info = describePackage(path.join(dir, app.file));
          if (!info || !info.iconData) { res.writeHead(404); res.end('no icon'); return; }
          const mime = app.file.toLowerCase().endsWith('.svg') ? 'image/svg+xml' : 'image/png';
          res.writeHead(200, { 'Content-Type': mime, 'Cache-Control': 'public, max-age=3600' });
          res.end(info.iconData);
        } catch (e) {
          res.writeHead(500); res.end('error');
        }
        return;
      }

      if (pathname.startsWith('/apps/')) {
        const file = pathname.slice('/apps/'.length);
        const full = findPackage(dir, file);
        if (!full) { res.writeHead(404); res.end('not found'); return; }
        const stat = fs.statSync(full);
        res.writeHead(200, {
          'Content-Type': contentTypeForPkt(),
          'Content-Length': stat.size,
          'Content-Disposition': `attachment; filename="${encodeURIComponent(file)}"`
        });
        fs.createReadStream(full).pipe(res);
        return;
      }

      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('not found');
    } catch (error) {
      res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: error.message || '服务器错误' }));
    }
  });

  udp = dgram.createSocket('udp4');
  udp.on('message', (msg, rinfo) => {
    const text = msg.toString('utf8').trim();
    if (text.startsWith('POCKET_DISCOVER')) {
      const payload = Buffer.from(JSON.stringify({
        service: 'pocket',
        name,
        port,
        host: bestAddressFor(rinfo.address),
        version: 1
      }), 'utf8');
      udp.send(payload, rinfo.port, rinfo.address);
    }
  });

  process.on('SIGINT', stopService);
  process.on('SIGTERM', stopService);

  return new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(port, '0.0.0.0', () => {
      try {
        fs.writeFileSync(pidFile, JSON.stringify({
          pid: process.pid,
          port,
          dir,
          startedAt: Date.now()
        }, null, 2));
      } catch (e) {}
      udp.on('error', () => {});
      udp.bind(port + DISCOVER_PORT_OFFSET, '0.0.0.0', () => {
        try { udp.setBroadcast(true); } catch (e) {}
        resolve({ server, udp, address: addresses, port, dir });
      });
    });
  });
}

module.exports = { startServer, scanLibrary, lanAddresses };
