'use strict';

const { contextBridge, ipcRenderer } = require('electron');

const allowedEvents = new Set([
  'panel:state',
  'clipboard:changed',
  'service:status',
  'app:syncClipboard',
  'navigate',
  'float:state',
  'window:shown',
  'theme:set'
]);

contextBridge.exposeInMainWorld('pocket', {
  mode: new URLSearchParams(location.search).get('mode') || 'main',

  expand: () => ipcRenderer.send('panel:expand'),
  collapse: () => ipcRenderer.send('panel:collapse'),
  toggle: () => ipcRenderer.send('panel:toggle'),
  openLibrary: () => ipcRenderer.send('window:openLibrary'),
  minimize: () => ipcRenderer.send('window:minimize'),
  closeWindow: () => ipcRenderer.send('window:close'),
  quit: () => ipcRenderer.send('app:quit'),

  readClipboard: () => ipcRenderer.invoke('clipboard:read'),
  writeClipboard: (text) => ipcRenderer.invoke('clipboard:write', text),

  floatToggle: () => ipcRenderer.send('float:toggle'),
  floatShow: () => ipcRenderer.send('float:show'),
  floatHide: () => ipcRenderer.send('float:hide'),
  floatStatus: () => ipcRenderer.invoke('float:status'),
  floatPin: (enabled) => ipcRenderer.invoke('float:pin', enabled),

  openReceivedFile: (id) => ipcRenderer.invoke('transfer:open', id),
  revealReceivedFile: (id) => ipcRenderer.invoke('transfer:reveal', id),
  copyReceivedFile: (id) => ipcRenderer.invoke('transfer:copy', id),

  dragStart: () => ipcRenderer.send('ball:dragStart'),
  dragEnd: (moved) => ipcRenderer.send('ball:dragEnd', { moved: !!moved }),
  ballMenu: () => ipcRenderer.send('ball:menu'),

  getSettings: () => ipcRenderer.invoke('app:settings'),
  setAutoLaunch: (enabled) => ipcRenderer.invoke('app:setAutoLaunch', enabled),
  setAlwaysOnTop: (enabled) => ipcRenderer.invoke('app:setAlwaysOnTop', enabled),
  setClipWatch: (enabled) => ipcRenderer.invoke('app:setClipWatch', enabled),
  setTheme: (dark) => ipcRenderer.send('theme:set', !!dark),
  startService: () => ipcRenderer.invoke('service:start'),

  openExternal: (url) => ipcRenderer.send('shell:open', url),
  openPath: (target) => ipcRenderer.send('shell:openPath', target),

  on: (channel, callback) => {
    if (!allowedEvents.has(channel) || typeof callback !== 'function') {
      return () => {};
    }
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on(channel, listener);
    return () => ipcRenderer.removeListener(channel, listener);
  }
});
