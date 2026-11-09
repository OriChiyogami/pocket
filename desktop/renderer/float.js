(function () {
  'use strict';

  var SERVER = 'http://127.0.0.1:8570';
  var bridge = window.pocket || {
    readClipboard: function () { return Promise.resolve(''); },
    writeClipboard: function () { return Promise.resolve(false); },
    floatHide: function () {},
    floatPin: function () { return Promise.resolve({ pinned: true }); },
    floatStatus: function () { return Promise.resolve({ pinned: true, visible: true }); },
    on: function () { return function () {}; }
  };

  function $(selector) { return document.querySelector(selector); }
  function $$(selector) { return Array.prototype.slice.call(document.querySelectorAll(selector)); }

  function esc(text) {
    return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function fmtSize(bytes) {
    var value = Number(bytes) || 0;
    if (value < 1024) return value + ' B';
    if (value < 1048576) return (value / 1024).toFixed(1) + ' KB';
    if (value < 1073741824) return (value / 1048576).toFixed(1) + ' MB';
    return (value / 1073741824).toFixed(2) + ' GB';
  }

  function fmtSpeed(bytes) {
    var value = Number(bytes) || 0;
    if (value <= 0) return '';
    if (value < 1024) return value + ' B/s';
    if (value < 1048576) return (value / 1024).toFixed(1) + ' KB/s';
    return (value / 1048576).toFixed(1) + ' MB/s';
  }

  function relTime(ms) {
    var stamp = Number(ms) || 0;
    if (!stamp) return '';
    var diff = Math.max(0, Date.now() - stamp);
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
    return new Date(stamp).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
  }

  var toastTimer = null;
  function toast(text) {
    var el = $('#fwToast');
    el.textContent = text;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); }, 2000);
  }

  function api(path, options) {
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, 6000);
    var opts = Object.assign({ cache: 'no-store', signal: controller.signal }, options || {});
    return fetch(SERVER + path, opts).then(function (res) {
      return res.text().then(function (text) {
        var data = null;
        try { data = JSON.parse(text); } catch (e) { data = text; }
        if (!res.ok) throw new Error((data && data.error) || ('HTTP ' + res.status));
        return data;
      });
    }).finally(function () { clearTimeout(timer); });
  }

  function phoneApi(phone, path, options) {
    if (!phone) return Promise.reject(new Error('没有选择手机'));
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, 6000);
    var opts = Object.assign({ cache: 'no-store', signal: controller.signal }, options || {});
    return fetch('http://' + phone.host + ':' + phone.port + path, opts).then(function (res) {
      return res.text().then(function (text) {
        var data = null;
        try { data = JSON.parse(text); } catch (e) { data = text; }
        if (!res.ok) throw new Error((data && data.error) || ('HTTP ' + res.status));
        return data;
      });
    }).finally(function () { clearTimeout(timer); });
  }

  var state = {
    tab: 'files',
    phone: null,
    devices: [],
    clipboard: [],
    active: [],
    lastActive: 0,
    sending: false,
    pinned: true,
    online: false
  };

  function setStatus() {
    var online = state.online;
    $('#fwStatus').textContent = state.phone
      ? state.phone.name + ' · 已连接'
      : (online ? '等待手机…' : '本地服务未连接');
    $('#fwFootText').textContent = online ? '同一 Wi-Fi · 不压缩' : '本地服务未连接，请打开口袋主程序';
    $('#fwFootDot').classList.toggle('off', !online);
    $('#fwDot').classList.toggle('off', !state.phone);
    $('#fwDeviceName').textContent = state.phone ? state.phone.name : '选择手机';
    $('#fwDeviceMeta').textContent = state.phone ? state.phone.host + ':' + state.phone.port : '未发现';
  }

  async function fetchDevices() {
    try {
      var data = await api('/api/transfer/devices');
      state.devices = (data && data.devices) || [];
      var keep = state.phone ? state.devices.filter(function (d) { return d.id === state.phone.id; })[0] : null;
      state.phone = keep || state.devices.filter(function (d) { return d.platform === 'android'; })[0] || state.devices[0] || null;
      state.online = true;
    } catch (e) {
      state.devices = [];
      state.phone = null;
      state.online = false;
    }
    setStatus();
  }

  async function fetchActive() {
    try {
      var data = await api('/api/transfer/active');
      var list = (data && data.transfers) || [];
      var had = state.lastActive;
      state.active = list;
      state.lastActive = list.length;
      renderActive();
      if (had > 0 && list.length === 0) toast('文件接收完成');
    } catch (e) {}
  }

  function renderActive() {
    var box = $('#fwLive');
    if (!state.active.length) { box.innerHTML = ''; return; }
    box.innerHTML = state.active.map(function (t) {
      var percent = Number(t.percent) || 0;
      var speed = fmtSpeed(t.speed);
      return '<div class="fwLiveRow">' +
        '<div class="fwLiveHead"><strong>接收 · ' + esc(t.name) + '</strong>' +
        '<span>' + percent + '%' + (speed ? ' · ' + speed : '') + '</span></div>' +
        '<div class="fwTrack"><i style="width:' + percent + '%"></i></div></div>';
    }).join('');
  }

  function uploadItem(url, item) {
    return new Promise(function (resolve, reject) {
      var row = document.createElement('div');
      row.className = 'fwProgressItem';
      row.innerHTML = '<div class="fwProgressHead"><strong>' + esc(item.name) + '</strong><span>准备中</span></div>' +
        '<div class="fwTrack"><i></i></div>';
      $('#fwProgress').prepend(row);
      var bar = row.querySelector('i');
      var info = row.querySelector('.fwProgressHead span');
      var xhr = new XMLHttpRequest();
      var lastTime = Date.now();
      var lastLoaded = 0;
      xhr.open('PUT', url);
      xhr.setRequestHeader('Content-Type', item.type || 'application/octet-stream');
      xhr.upload.onprogress = function (event) {
        if (!event.lengthComputable) return;
        var now = Date.now();
        var percent = Math.round((event.loaded / event.total) * 100);
        var speed = now > lastTime ? Math.round(((event.loaded - lastLoaded) * 1000) / (now - lastTime)) : 0;
        bar.style.width = percent + '%';
        info.textContent = percent + '%' + (speed > 0 ? ' · ' + fmtSpeed(speed) : '');
        lastTime = now;
        lastLoaded = event.loaded;
      };
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) {
          bar.style.width = '100%';
          info.textContent = '完成';
          setTimeout(function () { row.remove(); }, 1600);
          resolve();
        } else {
          info.textContent = '失败';
          row.style.borderColor = 'rgba(255,92,108,.4)';
          reject(new Error('HTTP ' + xhr.status));
        }
      };
      xhr.onerror = function () {
        info.textContent = '网络错误';
        reject(new Error('网络错误'));
      };
      xhr.send(item.file);
    });
  }

  async function sendFiles(fileList) {
    var files = Array.prototype.slice.call(fileList || []);
    if (!files.length) return;
    if (!state.phone) { toast('没发现手机，先让手机打开口袋'); return; }
    if (state.sending) { toast('正在发送，请稍候'); return; }
    state.sending = true;
    var base = 'http://' + state.phone.host + ':' + state.phone.port;
    try {
      var response = await fetch(base + '/api/transfer/offer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          from: { id: 'pc-float', name: 'Pocket 悬浮窗', platform: 'windows' },
          files: files.map(function (file) {
            return { name: file.name, size: file.size, mime: file.type || 'application/octet-stream' };
          })
        })
      });
      var data = await response.json();
      if (!data || !data.token) throw new Error((data && data.error) || '对方拒绝接收');
      for (var i = 0; i < files.length; i += 1) {
        await uploadItem(base + '/api/transfer/upload/' + data.token + '/' + i, {
          file: files[i],
          name: files[i].name,
          type: files[i].type || 'application/octet-stream'
        });
      }
      toast('发送完成：' + files.length + ' 个文件');
    } catch (e) {
      toast('发送失败：' + e.message);
    } finally {
      state.sending = false;
    }
  }

  async function fetchClipboard() {
    if (state.tab !== 'clip') return;
    var remote = null;
    try {
      remote = state.phone ? await phoneApi(state.phone, '/api/clipboard') : null;
    } catch (e) {
      remote = null;
    }
    state.clipboard = (remote && remote.items) || [];
    renderClipboard();
  }

  function renderClipboard() {
    var box = $('#fwClipList');
    if (!state.clipboard.length) {
      box.innerHTML = '<div class="fwClipEmpty">' +
        (state.phone ? '手机剪贴板还是空的<br>在手机上复制文字后会出现在这里' : '还没有连接手机<br>让手机打开口袋、连同一个 Wi-Fi') +
        '</div>';
      return;
    }
    box.innerHTML = state.clipboard.slice(0, 40).map(function (item, index) {
      var text = String(item.text || '');
      var preview = text.length > 160 ? text.slice(0, 160) + '…' : text;
      return '<div class="fwClipItem" data-index="' + index + '">' +
        '<div class="fwClipBody"><div class="fwClipText">' + esc(preview) + '</div>' +
        '<div class="fwClipMeta"><span class="fwSource">' + (item.source === 'pc' ? '电脑' : '手机') + '</span>' +
        '<span>' + relTime(item.time) + '</span></div></div>' +
        '<button class="fwCopyBtn" data-copy="' + index + '" title="复制">' +
        '<svg class="ico"><use href="#i-copy"></use></svg></button></div>';
    }).join('');
  }

  async function copyItem(index) {
    var item = state.clipboard[index];
    if (!item) return;
    var ok = await bridge.writeClipboard(item.text);
    toast(ok === false ? '复制失败' : '已复制到电脑剪贴板');
  }

  async function pushClipboard() {
    var text = '';
    try { text = await bridge.readClipboard(); } catch (e) { text = ''; }
    if (!text || !text.trim()) { toast('电脑剪贴板里没有文字'); return; }
    try {
      await api('/api/clipboard', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: text, source: 'pc' })
      });
      if (state.phone) {
        await phoneApi(state.phone, '/api/clipboard', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: text, source: 'pc' })
        });
        toast('已发到手机剪贴板');
      } else {
        toast('已记录到电脑（手机未连接）');
      }
      fetchClipboard();
    } catch (e) {
      toast('发送失败：' + e.message);
    }
  }

  function setTab(tab) {
    state.tab = tab;
    $$('#fwTabs button').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-tab') === tab); });
    $$('.fwPane').forEach(function (p) { p.classList.toggle('active', p.getAttribute('data-pane') === tab); });
    movePill();
    if (tab === 'clip') fetchClipboard();
  }

  function movePill() {
    var nav = $('#fwTabs');
    var pill = $('#fwPill');
    var active = nav.querySelector('button.active');
    if (!active) return;
    pill.style.left = active.offsetLeft + 'px';
    pill.style.width = active.offsetWidth + 'px';
  }

  function bind() {
    $$('#fwTabs button').forEach(function (button) {
      button.addEventListener('click', function () { setTab(button.getAttribute('data-tab')); });
    });

    $('#fwPick').addEventListener('click', function (event) {
      event.stopPropagation();
      $('#fwFileInput').click();
    });
    $('#fwDrop').addEventListener('click', function () { $('#fwFileInput').click(); });
    $('#fwFileInput').addEventListener('change', function (event) {
      sendFiles(event.target.files);
      event.target.value = '';
    });

    var drop = $('#fwDrop');
    ['dragenter', 'dragover'].forEach(function (name) {
      drop.addEventListener(name, function (event) {
        event.preventDefault();
        drop.classList.add('hover');
      });
    });
    ['dragleave', 'drop'].forEach(function (name) {
      drop.addEventListener(name, function (event) {
        event.preventDefault();
        drop.classList.remove('hover');
      });
    });
    drop.addEventListener('drop', function (event) {
      if (event.dataTransfer && event.dataTransfer.files) sendFiles(event.dataTransfer.files);
    });

    $('#fwDevice').addEventListener('click', function () {
      fetchDevices().then(function () { toast(state.phone ? '已连接 ' + state.phone.name : '还没发现手机'); });
    });

    $('#fwClipRefresh').addEventListener('click', function () {
      fetchClipboard().then(function () { toast('已刷新手机剪贴板'); });
    });
    $('#fwClipPush').addEventListener('click', pushClipboard);

    $('#fwClipList').addEventListener('click', function (event) {
      var copyBtn = event.target.closest('[data-copy]');
      if (copyBtn) {
        copyItem(Number(copyBtn.getAttribute('data-copy')));
        return;
      }
      var row = event.target.closest('[data-index]');
      if (row) copyItem(Number(row.getAttribute('data-index')));
    });

    $('#pinBtn').addEventListener('click', async function () {
      var next = !state.pinned;
      var result = await bridge.floatPin(next);
      state.pinned = !!(result && result.pinned);
      $('#pinBtn').classList.toggle('on', state.pinned);
      toast(state.pinned ? '已置顶' : '取消置顶');
    });

    $('#closeBtn').addEventListener('click', function () { bridge.floatHide(); });

    bridge.on('float:state', function (payload) {
      if (payload && typeof payload.pinned === 'boolean') {
        state.pinned = payload.pinned;
        $('#pinBtn').classList.toggle('on', state.pinned);
      }
    });
  }

  async function init() {
    bind();
    movePill();
    if (bridge.on) {
      bridge.on('theme:set', function (dark) {
        document.documentElement.classList.toggle('light', !dark);
        document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
      });
    }
    try {
      var status = await bridge.floatStatus();
      state.pinned = !!(status && status.pinned !== false);
      $('#pinBtn').classList.toggle('on', state.pinned);
    } catch (e) {}
    await fetchDevices();
    fetchClipboard();
    fetchActive();
    setInterval(fetchDevices, 4000);
    setInterval(fetchClipboard, 1500);
    setInterval(fetchActive, 700);
  }

  init();
})();
