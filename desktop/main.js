'use strict';

const {
  app, BrowserWindow, ipcMain, screen, clipboard, Tray, Menu,
  nativeImage, shell, globalShortcut
} = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const http = require('http');
const { spawn, execFile } = require('child_process');

const SERVER_PORT = 8570;
const SERVER_BASE = `http://127.0.0.1:${SERVER_PORT}`;

const MAIN_W = 1180;
const MAIN_H = 760;

const BALL_SIZE = 108;
const BALL_VISUAL = 80;
const BALL_INSET = (BALL_SIZE - BALL_VISUAL) / 2;

const FLOAT_W = 336;
const FLOAT_H = 470;

let mainWin = null;
let ballWin = null;
let floatWin = null;
let tray = null;

let lastClip = '';
let clipTimer = null;
let clipWatchEnabled = true;
let alwaysOnTopEnabled = true;
let floatPinned = true;
let currentThemeDark = false;

let dragTimer = null;
let dragOffset = null;
let dragStartBounds = null;
let quitting = false;
let mainShownOnce = false;

// ---------------------------------------------------------------- helpers

function findProjectRoot() {
  let dir = __dirname;
  for (let i = 0; i < 7; i += 1) {
    if (fs.existsSync(path.join(dir, 'cli', 'bin', 'pocket.js'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return null;
}

const PROJECT_ROOT = findProjectRoot();

function primaryWorkArea() {
  return screen.getPrimaryDisplay().workArea;
}

function clampToWorkArea(bounds) {
  const display = screen.getDisplayNearestPoint({
    x: Math.round(bounds.x + bounds.width / 2),
    y: Math.round(bounds.y + bounds.height / 2)
  });
  const wa = display.workArea;
  const width = Math.min(bounds.width, wa.width - 16);
  const height = Math.min(bounds.height, wa.height - 16);
  const x = Math.max(wa.x + 8, Math.min(bounds.x, wa.x + wa.width - width - 8));
  const y = Math.max(wa.y + 8, Math.min(bounds.y, wa.y + wa.height - height - 8));
  return {
    x: Math.round(x),
    y: Math.round(y),
    width: Math.round(width),
    height: Math.round(height)
  };
}

function ballRect() {
  if (!ballWin) return { x: 0, y: 0, width: BALL_VISUAL, height: BALL_VISUAL };
  const bounds = ballWin.getBounds();
  return {
    x: bounds.x + BALL_INSET,
    y: bounds.y + BALL_INSET,
    width: BALL_VISUAL,
    height: BALL_VISUAL
  };
}

function sendState(state) {
  broadcast('panel:state', state);
}

function allWindows() {
  return [mainWin, floatWin, ballWin].filter((win) => win && !win.isDestroyed());
}

function broadcast(channel, payload) {
  allWindows().forEach((win) => win.webContents.send(channel, payload));
}

// ---------------------------------------------------------------- windows

function createBallWindow() {
  const wa = primaryWorkArea();
  const cfg = readConfig();
  let x = Number(cfg.ballX);
  let y = Number(cfg.ballY);
  if (!Number.isFinite(x) || !Number.isFinite(y)) {
    x = wa.x + wa.width - BALL_SIZE - 18;
    y = wa.y + wa.height - BALL_SIZE - 18;
  }
  const bounds = clampToWorkArea({ x, y, width: BALL_SIZE, height: BALL_SIZE });

  ballWin = new BrowserWindow({
    width: BALL_SIZE,
    height: BALL_SIZE,
    x: bounds.x,
    y: bounds.y,
    icon: path.join(__dirname, 'assets', 'pocket-icon.png'),
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    hasShadow: false,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      backgroundThrottling: false
    }
  });
  ballWin.setAlwaysOnTop(true, 'screen-saver');
  ballWin.setMenu(null);
  ballWin.loadFile(path.join(__dirname, 'renderer', 'ball.html'));
  ballWin.once('ready-to-show', () => {
    ballWin.showInactive();
  });
  ballWin.on('closed', () => { ballWin = null; });
}

function createMainWindow() {
  const wa = primaryWorkArea();
  const width = Math.min(MAIN_W, wa.width - 72);
  const height = Math.min(MAIN_H, wa.height - 72);

  mainWin = new BrowserWindow({
    width,
    height,
    x: Math.round(wa.x + (wa.width - width) / 2),
    y: Math.round(wa.y + (wa.height - height) / 2),
    minWidth: 980,
    minHeight: 640,
    icon: path.join(__dirname, 'assets', 'pocket-icon.png'),
    frame: false,
    transparent: false,
    backgroundColor: '#eef4fd',
    resizable: true,
    minimizable: true,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: false,
    alwaysOnTop: readConfig().alwaysOnTop !== false,
    hasShadow: true,
    show: false,
    title: '口袋 Pocket',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      backgroundThrottling: false
    }
  });
  mainWin.setMenu(null);
  mainWin.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  mainWin.on('close', (event) => {
    if (quitting) return;
    event.preventDefault();
    hideMain();
  });

  mainWin.on('closed', () => { mainWin = null; });
}

function createFloatWindow() {
  const wa = primaryWorkArea();
  floatWin = new BrowserWindow({
    width: FLOAT_W,
    height: FLOAT_H,
    x: wa.x + wa.width - FLOAT_W - 24,
    y: wa.y + Math.round((wa.height - FLOAT_H) / 2),
    icon: path.join(__dirname, 'assets', 'pocket-icon.png'),
    frame: false,
    transparent: false,
    backgroundColor: '#eff5fd',
    resizable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: floatPinned,
    hasShadow: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      backgroundThrottling: false
    }
  });
  floatWin.setMenu(null);
  floatWin.loadFile(path.join(__dirname, 'renderer', 'float.html'));
  floatWin.webContents.on('did-finish-load', () => {
    floatWin.webContents.send('theme:set', currentThemeDark);
  });
  floatWin.on('close', (event) => {
    if (quitting) return;
    event.preventDefault();
    floatWin.hide();
    sendFloatState();
  });
  floatWin.on('show', () => {
    sendFloatState();
  });
  floatWin.on('closed', () => { floatWin = null; });
}

function showMain(page) {
  if (!mainWin) createMainWindow();
  if (!mainWin) return;
  if (ballWin) ballWin.hide();
  if (!mainWin.isVisible()) {
    mainWin.show();
    mainShownOnce = true;
  }
  mainWin.focus();
  mainWin.webContents.send('window:shown');
  if (page) mainWin.webContents.send('navigate', page);
  sendFloatState();
}

function hideMain() {
  if (mainWin && mainWin.isVisible()) mainWin.hide();
  if (ballWin) {
    const bounds = clampToWorkArea(ballWin.getBounds());
    ballWin.setBounds(bounds);
    ballWin.showInactive();
  }
  sendFloatState();
}

function toggleMain() {
  if (mainWin && mainWin.isVisible()) hideMain();
  else showMain();
}

function showFloat() {
  if (!floatWin) createFloatWindow();
  if (!floatWin) return;
  const target = clampToWorkArea({
    x: ballWin && ballWin.isVisible()
      ? ballWin.getBounds().x - FLOAT_W - 12
      : primaryWorkArea().x + primaryWorkArea().width - FLOAT_W - 24,
    y: ballWin && ballWin.isVisible()
      ? ballWin.getBounds().y + BALL_SIZE - FLOAT_H
      : primaryWorkArea().y + Math.round((primaryWorkArea().height - FLOAT_H) / 2),
    width: FLOAT_W,
    height: FLOAT_H
  });
  floatWin.setBounds(target);
  floatWin.setAlwaysOnTop(floatPinned, 'screen-saver');
  floatWin.show();
  floatWin.focus();
  floatWin.webContents.send('window:shown');
  sendFloatState();
}

function hideFloat() {
  if (floatWin && floatWin.isVisible()) floatWin.hide();
  sendFloatState();
}

function toggleFloat() {
  if (floatWin && floatWin.isVisible()) hideFloat();
  else showFloat();
}

function sendFloatState() {
  const visible = !!(floatWin && !floatWin.isDestroyed() && floatWin.isVisible());
  const payload = { visible, pinned: floatPinned };
  if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('float:state', payload);
  if (floatWin && !floatWin.isDestroyed()) floatWin.webContents.send('float:state', payload);
}

function showBallMenu() {
  const menu = Menu.buildFromTemplate([
    { label: '打开口袋', click: () => showMain() },
    { label: '剪贴板悬浮窗', click: showFloat },
    { type: 'separator' },
    { label: '退出', click: () => { quitting = true; app.quit(); } }
  ]);
  menu.popup({ window: ballWin || mainWin || floatWin });
}

// ---------------------------------------------------------------- config

function createTray() {
  const iconPath = path.join(__dirname, 'assets', 'pocket-icon.png');
  let icon = nativeImage.createEmpty();
  try {
    icon = nativeImage.createFromPath(iconPath).resize({ width: 18, height: 18 });
  } catch (e) {}

  tray = new Tray(icon);
  const autoLaunch = getAutoLaunch();
  const menu = Menu.buildFromTemplate([
    { label: '打开口袋', click: () => showMain() },
    { label: '剪贴板悬浮窗', click: showFloat },
    { type: 'separator' },
    { label: '同步手机剪贴板', click: () => broadcast('app:syncClipboard', {}) },
    {
      label: '开机启动',
      type: 'checkbox',
      checked: autoLaunch,
      click: (item) => setAutoLaunch(item.checked)
    },
    { type: 'separator' },
    { label: '退出', click: () => { quitting = true; app.quit(); } }
  ]);
  tray.setToolTip('口袋 Pocket');
  tray.setContextMenu(menu);
  tray.on('click', toggleMain);
}

function getConfigPath() {
  return path.join(app.getPath('userData'), 'pocket-desktop.json');
}

function readConfig() {
  try {
    return JSON.parse(fs.readFileSync(getConfigPath(), 'utf8'));
  } catch (e) {
    return {};
  }
}

function writeConfig(patch) {
  const next = Object.assign({}, readConfig(), patch);
  try {
    fs.mkdirSync(path.dirname(getConfigPath()), { recursive: true });
    fs.writeFileSync(getConfigPath(), JSON.stringify(next, null, 2), 'utf8');
  } catch (e) {}
  return next;
}

function getAutoLaunch() {
  return !!readConfig().autoLaunch;
}

function setAutoLaunch(enabled) {
  writeConfig({ autoLaunch: !!enabled });
  try {
    const args = process.defaultApp ? [app.getAppPath()] : [];
    app.setLoginItemSettings({
      openAtLogin: !!enabled,
      path: process.execPath,
      args
    });
  } catch (e) {}
}

// ---------------------------------------------------------------- service / clipboard

function checkHealth() {
  return new Promise((resolve) => {
    const req = http.get(`${SERVER_BASE}/api/health`, { timeout: 800 }, (res) => {
      res.resume();
      resolve(res.statusCode === 200);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

function startService() {
  return new Promise(async (resolve) => {
    if (await checkHealth()) return resolve(true);
    if (!PROJECT_ROOT) return resolve(false);
    try {
      const child = spawn(
        'node',
        [
          path.join(PROJECT_ROOT, 'cli', 'bin', 'pocket.js'),
          'serve',
          '--dir', path.join(PROJECT_ROOT, 'dist'),
          '--port', String(SERVER_PORT),
          '--name', os.hostname()
        ],
        {
          cwd: PROJECT_ROOT,
          detached: true,
          stdio: 'ignore',
          windowsHide: true
        }
      );
      child.unref();
    } catch (e) {
      return resolve(false);
    }
    for (let i = 0; i < 12; i += 1) {
      await new Promise((r) => setTimeout(r, 350));
      if (await checkHealth()) return resolve(true);
    }
    resolve(false);
  });
}

function postLocalClipboard(text, source) {
  return new Promise((resolve) => {
    if (!text) return resolve(false);
    const body = JSON.stringify({ text, source: source || 'pc', time: Date.now() });
    const req = http.request(
      {
        host: '127.0.0.1',
        port: SERVER_PORT,
        path: '/api/clipboard',
        method: 'POST',
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          'Content-Length': Buffer.byteLength(body)
        },
        timeout: 1500
      },
      (res) => {
        res.resume();
        res.on('end', () => resolve(res.statusCode === 200));
      }
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
    req.write(body);
    req.end();
  });
}

function startClipboardWatcher() {
  const cfg = readConfig();
  clipWatchEnabled = cfg.clipWatch !== false;
  floatPinned = cfg.floatPinned !== false;
  lastClip = clipboard.readText() || '';
  clipTimer = setInterval(async () => {
    if (!clipWatchEnabled) return;
    let text = '';
    try { text = clipboard.readText() || ''; } catch (e) { return; }
    if (!text || text === lastClip) return;
    lastClip = text;
    await postLocalClipboard(text, 'pc');
    broadcast('clipboard:changed', { source: 'pc' });
  }, 900);
}

// ---------------------------------------------------------------- ipc

ipcMain.on('window:minimize', hideMain);
ipcMain.on('window:close', hideMain);
ipcMain.on('window:openLibrary', () => showMain('library'));
ipcMain.on('panel:expand', () => showMain());
ipcMain.on('panel:collapse', hideMain);
ipcMain.on('panel:toggle', toggleMain);
ipcMain.on('panel:library', () => showMain('library'));
ipcMain.on('app:quit', () => { quitting = true; app.quit(); });
ipcMain.on('shell:open', (_e, url) => {
  if (typeof url === 'string' && /^https?:\/\//i.test(url)) shell.openExternal(url);
});
ipcMain.on('shell:openPath', (_e, target) => {
  if (typeof target === 'string') shell.openPath(target);
});
ipcMain.on('float:toggle', toggleFloat);
ipcMain.on('float:show', showFloat);
ipcMain.on('float:hide', hideFloat);
ipcMain.on('theme:set', (_e, dark) => {
  const isDark = !!dark;
  currentThemeDark = isDark;
  if (mainWin && !mainWin.isDestroyed()) {
    mainWin.setBackgroundColor(isDark ? '#04101f' : '#eef4fd');
  }
  if (floatWin && !floatWin.isDestroyed()) {
    floatWin.setBackgroundColor(isDark ? '#06182c' : '#eff5fd');
    floatWin.webContents.send('theme:set', isDark);
  }
});

ipcMain.handle('float:status', () => ({
  visible: !!(floatWin && !floatWin.isDestroyed() && floatWin.isVisible()),
  pinned: floatPinned
}));

ipcMain.handle('float:pin', (_e, enabled) => {
  floatPinned = typeof enabled === 'boolean' ? enabled : !floatPinned;
  writeConfig({ floatPinned });
  if (floatWin && !floatWin.isDestroyed()) {
    floatWin.setAlwaysOnTop(floatPinned, 'screen-saver');
  }
  sendFloatState();
  return { visible: !!(floatWin && floatWin.isVisible()), pinned: floatPinned };
});

ipcMain.handle('clipboard:read', () => {
  try { return clipboard.readText() || ''; } catch (e) { return ''; }
});

ipcMain.handle('clipboard:write', (_e, text) => {
  const value = String(text == null ? '' : text);
  try {
    clipboard.writeText(value);
    lastClip = value;
    return true;
  } catch (e) {
    return false;
  }
});

ipcMain.handle('app:settings', async () => {
  const autoLaunch = getAutoLaunch();
  const online = await checkHealth();
  const cfg = readConfig();
  return {
    autoLaunch,
    online,
    alwaysOnTop: cfg.alwaysOnTop !== false,
    clipWatch: cfg.clipWatch !== false,
    floatVisible: !!(floatWin && !floatWin.isDestroyed() && floatWin.isVisible()),
    floatPinned,
    port: SERVER_PORT,
    version: app.getVersion(),
    platform: process.platform,
    hostname: os.hostname(),
    projectRoot: PROJECT_ROOT || ''
  };
});

ipcMain.handle('app:setAutoLaunch', (_e, enabled) => {
  setAutoLaunch(enabled);
  return getAutoLaunch();
});

ipcMain.handle('app:setAlwaysOnTop', (_e, enabled) => {
  alwaysOnTopEnabled = !!enabled;
  writeConfig({ alwaysOnTop: alwaysOnTopEnabled });
  allWindows().forEach((win) => {
    win.setAlwaysOnTop(alwaysOnTopEnabled, 'screen-saver');
  });
  return alwaysOnTopEnabled;
});

ipcMain.handle('app:setClipWatch', (_e, enabled) => {
  clipWatchEnabled = !!enabled;
  writeConfig({ clipWatch: clipWatchEnabled });
  return clipWatchEnabled;
});

ipcMain.handle('service:start', async () => await startService());

ipcMain.on('ball:dragStart', () => {
  if (!ballWin) return;
  const point = screen.getCursorScreenPoint();
  const bounds = ballWin.getBounds();
  dragStartBounds = bounds;
  dragOffset = { x: point.x - bounds.x, y: point.y - bounds.y };
  if (dragTimer) clearInterval(dragTimer);
  dragTimer = setInterval(() => {
    if (!ballWin || !dragOffset) return;
    const cursor = screen.getCursorScreenPoint();
    const display = screen.getDisplayNearestPoint(cursor);
    const wa = display.workArea;
    const x = Math.min(Math.max(cursor.x - dragOffset.x, wa.x), wa.x + wa.width - BALL_SIZE);
    const y = Math.min(Math.max(cursor.y - dragOffset.y, wa.y), wa.y + wa.height - BALL_SIZE);
    ballWin.setBounds({ x: Math.round(x), y: Math.round(y), width: BALL_SIZE, height: BALL_SIZE });
  }, 16);
});

ipcMain.on('ball:dragEnd', (_e, payload) => {
  if (dragTimer) { clearInterval(dragTimer); dragTimer = null; }
  dragOffset = null;
  let moved = false;
  if (ballWin && dragStartBounds) {
    const bounds = ballWin.getBounds();
    moved = Math.abs(bounds.x - dragStartBounds.x) + Math.abs(bounds.y - dragStartBounds.y) > 4;
    if (moved) {
      writeConfig({ ballX: bounds.x, ballY: bounds.y });
    }
  }
  dragStartBounds = null;
  if (!moved) showMain();
});

ipcMain.on('ball:menu', showBallMenu);

ipcMain.handle('window:bounds', () => (mainWin ? mainWin.getBounds() : null));

function transferFilePath(id) {
  if (!PROJECT_ROOT) return '';
  const safe = path.basename(String(id == null ? '' : id));
  if (!safe) return '';
  return path.join(PROJECT_ROOT, 'library', 'transfer', safe);
}

ipcMain.handle('transfer:open', async (_e, id) => {
  const full = transferFilePath(id);
  if (!full || !fs.existsSync(full)) return { ok: false, error: '文件不存在' };
  const error = await shell.openPath(full);
  return error ? { ok: false, error } : { ok: true, path: full };
});

ipcMain.handle('transfer:reveal', (_e, id) => {
  const full = transferFilePath(id);
  if (!full || !fs.existsSync(full)) return false;
  shell.showItemInFolder(full);
  return true;
});

// 把真实文件放进 Windows 剪贴板（CF_HDROP），粘贴出来就是文件本身，而不是路径字符串
function copyFilesToClipboard(files) {
  return new Promise((resolve, reject) => {
    const quote = (p) => "'" + String(p).replace(/'/g, "''") + "'";
    const script = 'Set-Clipboard -Path @(' + files.map(quote).join(',') + ')';
    const encoded = Buffer.from(script, 'utf16le').toString('base64');
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-STA', '-EncodedCommand', encoded], { windowsHide: true }, (error) => {
      if (error) reject(error); else resolve();
    });
  });
}

ipcMain.handle('transfer:copy', async (_e, id) => {
  const full = transferFilePath(id);
  if (!full || !fs.existsSync(full)) return { ok: false, error: '文件不存在' };
  try {
    await copyFilesToClipboard([full]);
    return { ok: true, kind: 'file', path: full };
  } catch (error) {
    try { clipboard.writeText(full); } catch (e) {}
    return { ok: true, kind: 'path', path: full, warning: String(error.message || error) };
  }
});

// ---------------------------------------------------------------- lifecycle

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    showMain();
  });

  app.whenReady().then(async () => {
    app.setAppUserModelId('io.pocket.desktop');
    createBallWindow();
    createMainWindow();
    createFloatWindow();
    createTray();
    startClipboardWatcher();
    await startService();
    globalShortcut.register('CommandOrControl+Shift+V', toggleMain);
    globalShortcut.register('CommandOrControl+Shift+B', toggleFloat);
    globalShortcut.register('CommandOrControl+Shift+L', () => showMain('library'));
    broadcast('service:status', { online: await checkHealth() });

    const wantMain = process.argv.includes('--show-main');
    if (wantMain) {
      showMain();
    }
  });
}

app.on('window-all-closed', () => {
  // 常驻托盘
});

app.on('before-quit', () => {
  quitting = true;
  if (clipTimer) clearInterval(clipTimer);
  if (dragTimer) clearInterval(dragTimer);
  globalShortcut.unregisterAll();
  if (tray) { tray.destroy(); tray = null; }
});

app.on('activate', () => {
  showMain();
});
