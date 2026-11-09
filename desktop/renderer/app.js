(function () {
  'use strict';

  var SERVER = 'http://127.0.0.1:8570';

  var bridge = window.pocket || {
    on: function () { return function () {}; },
    readClipboard: function () { return Promise.resolve(''); },
    writeClipboard: function () { return Promise.resolve(false); },
    getSettings: function () { return Promise.resolve({ online: false, port: 8570, version: '1.0.0' }); },
    setAutoLaunch: function () { return Promise.resolve(false); },
    setAlwaysOnTop: function () { return Promise.resolve(true); },
    setClipWatch: function () { return Promise.resolve(true); },
    startService: function () { return Promise.resolve(false); },
    floatToggle: function () {},
    floatStatus: function () { return Promise.resolve({ visible: false }); },
    openReceivedFile: function () { return Promise.resolve({ ok: false, error: '无法打开' }); },
    revealReceivedFile: function () { return Promise.resolve(false); },
    copyReceivedFile: function () { return Promise.resolve({ ok: false, error: '无法复制' }); },
    openExternal: function (url) { window.open(url, '_blank'); },
    openPath: function () {},
    quit: function () {},
    openLibrary: function () {},
    minimize: function () {},
    closeWindow: function () {},
    expand: function () {},
    collapse: function () {},
    dragStart: function () {},
    dragEnd: function () {},
    ballMenu: function () {}
  };

  // ---------------------------------------------------------------- helpers

  // 主题：浅蓝工作台（默认）/ 深色玻璃。切换时先用一层同色遮罩过渡，避免硬闪。
  var THEME_KEY = 'pocket.theme';

  function readTheme() {
    try { return localStorage.getItem(THEME_KEY) === 'dark'; } catch (e) { return false; }
  }

  function applyTheme(dark, animate) {
    var link = document.getElementById('themeLight');
    var root = document.documentElement;
    var finish = function () {
      if (link) link.disabled = dark;
      root.setAttribute('data-theme', dark ? 'dark' : 'light');
      try { localStorage.setItem(THEME_KEY, dark ? 'dark' : 'light'); } catch (e) {}
      if (bridge.setTheme) bridge.setTheme(dark);
    };
    if (!animate) { finish(); return; }

    var veil = document.createElement('div');
    veil.style.cssText = [
      'position:fixed', 'inset:0', 'z-index:9999', 'pointer-events:none',
      'opacity:0', 'transition:opacity .22s ease',
      'background:' + (dark ? '#eef4fd' : '#04101f')
    ].join(';');
    document.body.appendChild(veil);
    requestAnimationFrame(function () { veil.style.opacity = '1'; });
    setTimeout(function () {
      finish();
      veil.style.opacity = '0';
      setTimeout(function () {
        if (veil.parentNode) veil.parentNode.removeChild(veil);
      }, 260);
    }, 230);
  }

  applyTheme(readTheme(), false);

  function $(selector, root) {
    return (root || document).querySelector(selector);
  }

  function $$(selector, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(selector));
  }

  function esc(text) {
    return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function icon(name, cls) {
    return '<svg class="' + (cls || 'ico') + '"><use href="#i-' + name + '"></use></svg>';
  }

  function fmtSize(bytes) {
    var value = Number(bytes) || 0;
    if (value < 1024) return value + ' B';
    if (value < 1048576) return (value / 1024).toFixed(1) + ' KB';
    if (value < 1073741824) return (value / 1048576).toFixed(1) + ' MB';
    return (value / 1073741824).toFixed(2) + ' GB';
  }

  function isImageFile(file) {
    if (!file) return false;
    return /^image\//.test(String(file.mime || '')) ||
      /\.(png|jpe?g|gif|webp|bmp|avif)$/i.test(String(file.name || ''));
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
    if (diff < 2592000000) return Math.floor(diff / 86400000) + ' 天前';
    return new Date(stamp).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
  }

  function emptyState(title, sub) {
    return '<div class="emptyState"><strong>' + esc(title) + '</strong>' + esc(sub) + '</div>';
  }

  var toastTimer = null;
  function toast(text, duration) {
    var el = $('#toast');
    el.textContent = text;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); }, duration || 2200);
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
    var base = 'http://' + phone.host + ':' + phone.port + path;
    return fetch(base, opts).then(function (res) {
      return res.text().then(function (text) {
        var data = null;
        try { data = JSON.parse(text); } catch (e) { data = text; }
        if (!res.ok) throw new Error((data && data.error) || ('HTTP ' + res.status));
        return data;
      });
    }).finally(function () { clearTimeout(timer); });
  }

  function animateNumber(el, to, duration) {
    if (!el) return;
    var target = Number(to) || 0;
    var from = Number(el.getAttribute('data-value') || 0);
    if (from === target) {
      el.textContent = String(target);
      el.setAttribute('data-value', String(target));
      return;
    }
    var started = performance.now();
    var span = duration || 520;
    function step(now) {
      var t = Math.min(1, (now - started) / span);
      var e = 1 - Math.pow(1 - t, 3);
      var value = Math.round(from + (target - from) * e);
      el.textContent = String(value);
      if (t < 1) requestAnimationFrame(step);
      else el.setAttribute('data-value', String(target));
    }
    el.setAttribute('data-value', String(from));
    requestAnimationFrame(step);
  }

  // ---------------------------------------------------------------- state

  var state = {
    page: 'overview',
    libTab: 'apps',
    filter: 'all',
    range: 7,
    devices: [],
    phone: null,
    lastPhone: null,
    lastPhoneAt: 0,
    selectedId: '',
    clipboard: [],
    received: [],
    active: [],
    apps: [],
    htmlLib: [],
    host: null,
    lanIp: '',
    settings: {
      online: false,
      port: 8570,
      version: '1.0.0',
      hostname: '',
      autoLaunch: false,
      alwaysOnTop: true,
      clipWatch: true,
      floatVisible: false
    },
    counts: { devices: 0, clipboard: 0, files: 0, today: 0 },
    sending: false,
    receiving: false,
    palette: { open: false, items: [], index: 0 },
    lastActiveCount: 0
  };

  // ---------------------------------------------------------------- greeting

  function updateGreeting() {
    var now = new Date();
    var h = now.getHours();
    var word = h < 6 ? '凌晨好' : (h < 11 ? '早上好' : (h < 14 ? '中午好' : (h < 18 ? '下午好' : '晚上好')));
    var name = String(state.settings.hostname || '').trim();
    if (name.length > 15) name = name.slice(0, 15) + '…';
    $('#greetText').textContent = name ? word + '，' + name : word + '，欢迎回来';
    var week = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][now.getDay()];
    $('#dateText').textContent = (now.getMonth() + 1) + ' 月 ' + now.getDate() + ' 日 · ' + week;
  }

  // ---------------------------------------------------------------- navigation

  function moveNavPill() {
    var nav = $('#mainNav');
    var pill = $('#navPill');
    var active = nav.querySelector('button.active');
    if (!active) return;
    pill.style.top = active.offsetTop + 'px';
    pill.style.height = active.offsetHeight + 'px';
    pill.animate(
      [{ transform: 'scaleY(1)' }, { transform: 'scaleY(1.16)' }, { transform: 'scaleY(1)' }],
      { duration: 430, easing: 'cubic-bezier(.34,1.46,.64,1)' }
    );
  }

  function moveLibPill() {
    var nav = $('#libNav');
    var pill = $('#libPill');
    var active = nav.querySelector('button.active');
    if (!active) return;
    pill.style.left = active.offsetLeft + 'px';
    pill.style.width = active.offsetWidth + 'px';
    pill.animate(
      [{ transform: 'scaleX(1)' }, { transform: 'scaleX(1.14)' }, { transform: 'scaleX(1)' }],
      { duration: 380, easing: 'cubic-bezier(.34,1.46,.64,1)' }
    );
  }

  function resetScroll() {
    var pages = $('#pages');
    pages.scrollTop = 0;
    requestAnimationFrame(function () { pages.scrollTop = 0; });
    setTimeout(function () { pages.scrollTop = 0; }, 60);
  }

  function setActivePage(page) {
    state.page = page;
    $$('.page').forEach(function (p) { p.classList.toggle('active', p.getAttribute('data-page') === page); });
    $$('#mainNav button').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-page') === page); });
    $$('#dock button').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-page') === page); });
    $$('#thumbs .thumb').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-go') === page); });
    $('#thumbs').classList.toggle('show', page !== 'overview');
    resetScroll();
    moveNavPill();
    if (page === 'clipboard') renderClipboard();
    if (page === 'devices') renderDevices();
    if (page === 'library') { renderLibrary(); moveLibPill(); fetchLibrary(); }
    if (page === 'files') { renderActive(); fetchActive(); }
    if (page === 'settings') loadSettings();
  }

  function go(page, card) {
    if (!page || page === state.page) return;
    var target = $('.page[data-page="' + page + '"]');
    var hero = target ? target.querySelector('.pageHead') : null;
    if (card && typeof document.startViewTransition === 'function' && hero) {
      card.style.viewTransitionName = 'moduleHero';
      var transition = document.startViewTransition(function () {
        card.style.viewTransitionName = '';
        hero.style.viewTransitionName = 'moduleHero';
        setActivePage(page);
      });
      transition.finished.finally(function () {
        hero.style.viewTransitionName = '';
      });
    } else {
      setActivePage(page);
    }
  }

  // ---------------------------------------------------------------- tilt / spotlight

  function bindTiltCards() {
    $$('.tilt').forEach(function (card) {
      if (card.getAttribute('data-tilt')) return;
      card.setAttribute('data-tilt', '1');
      card.addEventListener('pointermove', function (event) {
        var rect = card.getBoundingClientRect();
        var x = event.clientX - rect.left;
        var y = event.clientY - rect.top;
        card.style.setProperty('--mx', x + 'px');
        card.style.setProperty('--my', y + 'px');
        var rx = ((y / rect.height) - 0.5) * -4;
        var ry = ((x / rect.width) - 0.5) * 4;
        card.style.setProperty('--rx', rx.toFixed(2) + 'deg');
        card.style.setProperty('--ry', ry.toFixed(2) + 'deg');
      });
      card.addEventListener('pointerleave', function () {
        card.style.setProperty('--rx', '0deg');
        card.style.setProperty('--ry', '0deg');
      });
    });
  }

  // ---------------------------------------------------------------- stats / overview

  function todayStart() {
    var now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  }

  function updateStats() {
    var start = todayStart();
    state.counts.devices = state.devices.length;
    state.counts.clipboard = state.clipboard.length;
    state.counts.files = state.received.length;
    state.counts.today = state.received.filter(function (f) { return Number(f.mtime) >= start; }).length;

    var values = {
      devices: state.counts.devices,
      clipboard: state.counts.clipboard,
      files: state.counts.files,
      today: state.counts.today
    };
    $$('[data-count]').forEach(function (el) {
      animateNumber(el, values[el.getAttribute('data-count')] || 0);
    });

    var filesBadge = $('#filesBadge');
    if (filesBadge) filesBadge.textContent = state.received.length + ' 个文件';
    var recvCount = $('#recvCount');
    if (recvCount) recvCount.textContent = state.received.length;
    var deviceBadge = $('#deviceBadge');
    if (deviceBadge) deviceBadge.textContent = state.devices.length + ' 台';

    var phone = state.phone;
    $('#focusName').textContent = phone ? phone.name : '等待手机';
    $('#focusMeta').textContent = phone
      ? phone.host + ' · 端口 ' + phone.port + ' · 同一 Wi-Fi'
      : '让手机打开口袋，连同一个 Wi-Fi';
    $('#focusClip').textContent = state.clipboard.length;
    $('#focusFiles').textContent = state.received.length;
    var topSpeed = 0;
    state.active.forEach(function (t) { topSpeed = Math.max(topSpeed, Number(t.speed) || 0); });
    $('#focusSpeed').textContent = topSpeed > 0 ? Math.round(topSpeed / 1024) : 0;
    $('#focusTag').textContent = phone ? '已连接' : '局域网';
    $('#focusDot').classList.toggle('off', !state.settings.online || !phone);
    $('#focusStatus').textContent = !state.settings.online
      ? '本地服务未启动'
      : (phone ? '手机已连接 · 可以互传' : '服务运行中 · 等待手机');

    $('#ssDot').classList.toggle('off', !state.settings.online);
    $('#ssTitle').textContent = state.settings.online ? '服务运行中' : '服务未连接';
    $('#ssSub').textContent = '127.0.0.1:' + (state.settings.port || 8570);
    $('#versionSmall').textContent = 'v' + (state.settings.version || '1.0.0');

    var serviceText = $('#serviceStateText');
    if (serviceText) {
      serviceText.textContent = state.settings.online
        ? '运行中 · 127.0.0.1:' + (state.settings.port || 8570)
        : '未启动，点右侧按钮拉起';
    }
    var portChip = $('#portChip');
    if (portChip) portChip.textContent = String(state.settings.port || 8570);
    var versionChip = $('#versionChip');
    if (versionChip) versionChip.textContent = state.settings.version || '1.0.0';
    var versionText = $('#versionText');
    if (versionText) versionText.textContent = 'Pocket Desktop · ' + (state.settings.platform || 'win32');
  }

  function renderChart() {
    var days = state.range;
    var now = new Date();
    var start = todayStart();
    var buckets = new Array(days).fill(0);
    state.clipboard.forEach(function (item) {
      var stamp = Number(item.time);
      if (!stamp) return;
      var d = new Date(stamp);
      var day = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
      var diff = Math.round((start - day) / 86400000);
      if (diff >= 0 && diff < days) buckets[days - 1 - diff] += 1;
    });
    var max = Math.max.apply(null, buckets.concat([1]));
    var scaleMax = Math.max(4, Math.ceil(max / 4) * 4);
    $('#axisMax').textContent = scaleMax;
    $('#axisMid').textContent = Math.round(scaleMax / 2);

    var html = '';
    for (var i = 0; i < days; i += 1) {
      var d = new Date(start - (days - 1 - i) * 86400000);
      var label = (d.getMonth() + 1) + '/' + d.getDate();
      if (days > 7 && i % 2 === 1 && i !== days - 1) label = '';
      var percent = buckets[i] === 0 ? 5 : Math.max(8, Math.round((buckets[i] / scaleMax) * 88));
      html += '<div class="barCol" style="--h:' + percent + '%">' +
        '<span class="barTip">' + label + ' · ' + buckets[i] + ' 条</span>' +
        '<i></i><small>' + label + '</small></div>';
    }
    $('#chartBars').innerHTML = html;
  }

  function renderRecent() {
    var clips = state.clipboard.slice(0, 4);
    var clipBox = $('#recentClips');
    if (!clips.length) {
      clipBox.innerHTML = emptyState('还没有剪贴板记录', '在电脑上复制文字，或点「同步手机」');
    } else {
      clipBox.innerHTML = clips.map(function (item) {
        var first = String(item.text || '').split(/\r?\n/)[0].slice(0, 44);
        return '<div class="miniRow"><span class="miniIcon">' + icon('clipboard') + '</span>' +
          '<span class="miniBody"><strong>' + esc(first || '(空白)') + '</strong>' +
          '<small>' + (item.source === 'phone' ? '手机' : '电脑') + ' · ' + relTime(item.time) + '</small></span>' +
          '<span class="miniTag">' + String(item.text || '').length + ' 字</span></div>';
      }).join('');
    }

    var files = state.received.slice(0, 4);
    var fileBox = $('#recentFiles');
    if (!files.length) {
      fileBox.innerHTML = emptyState('还没有收到文件', '在手机「互传」里选择这台电脑发送');
    } else {
      fileBox.innerHTML = files.map(function (file) {
        return '<div class="miniRow" data-file-row="' + esc(file.id) + '" title="点一下直接用本机程序打开">' +
          '<span class="miniIcon">' + icon('folder') + '</span>' +
          '<span class="miniBody"><strong>' + esc(file.name) + '</strong>' +
          '<small>' + fmtSize(file.size) + ' · ' + relTime(file.mtime) + '</small></span>' +
          '<span class="miniTag">收到</span></div>';
      }).join('');
    }
  }

  // ---------------------------------------------------------------- devices

  function renderDevicePill() {
    var phone = state.phone;
    $('#deviceName').textContent = phone ? phone.name : '选择手机';
    $('#deviceDot').classList.toggle('off', !phone);
  }

  function renderDevicePicker() {
    var box = $('#devicePicker');
    if (!state.devices.length) {
      box.innerHTML = '<button disabled>未发现设备</button><button data-refresh-devices>' + icon('refresh') + '重新扫描</button>';
      return;
    }
    box.innerHTML = state.devices.map(function (d) {
      var selected = state.phone && state.phone.id === d.id;
      return '<button data-device="' + esc(d.id) + '" class="' + (selected ? 'sel' : '') + '">' +
        icon(d.platform === 'android' ? 'phone' : 'pc') +
        '<span>' + esc(d.name) + '</span>' +
        '<small>' + esc(d.host) + '</small></button>';
    }).join('') + '<button data-refresh-devices>' + icon('refresh') + '重新扫描</button>';
  }

  function renderDevices() {
    var box = $('#deviceList');
    if (!state.devices.length) {
      box.innerHTML = emptyState('还没有发现设备', '让手机打开口袋、连同一个 Wi-Fi，几秒后会出现在这里');
    } else {
      box.innerHTML = state.devices.map(function (d) {
        return '<div class="deviceCard">' +
          '<span class="dIcon">' + icon(d.platform === 'android' ? 'phone' : 'pc') + '</span>' +
          '<span class="dBody"><strong>' + esc(d.name) + '</strong>' +
          '<small>' + (d.platform === 'android' ? '手机' : '电脑') + ' · ' + esc(d.host) + ':' + d.port + '</small></span>' +
          '<button class="dSend" data-send-device="' + esc(d.id) + '">发送文件</button></div>';
      }).join('');
    }

    var card = $('#serviceCard');
    if (card) {
      card.innerHTML =
        '<i class="serviceDot"></i><strong>' + (state.settings.online ? '本地服务运行中' : '本地服务未启动') + '</strong>' +
        '<div class="kv"><span>本机地址</span><span>127.0.0.1:' + (state.settings.port || 8570) + '</span></div>' +
        '<div class="kv"><span>局域网</span><span>' + (state.lanIp ? state.lanIp + ':' + (state.settings.port || 8570) : '检测中') + '</span></div>' +
        '<div class="kv"><span>在线设备</span><span>' + state.devices.length + ' 台</span></div>' +
        '<div class="kv"><span>版本</span><span>' + esc(state.settings.version || '1.0.0') + '</span></div>';
    }
  }

  async function fetchDevices() {
    try {
      var data = await api('/api/transfer/devices');
      state.devices = (data && data.devices) || [];
      var keep = state.selectedId ? state.devices.filter(function (d) { return d.id === state.selectedId; })[0] : null;
      var phone = keep || state.devices.filter(function (d) { return d.platform === 'android'; })[0] || state.devices[0] || null;
      if (phone) {
        state.lastPhone = phone;
        state.lastPhoneAt = Date.now();
      } else if (state.lastPhone && Date.now() - state.lastPhoneAt < 600000) {
        // 手机退到后台时 UDP 心跳可能暂时收不到，保留上次地址继续 HTTP 轮询
        phone = state.lastPhone;
      }
      state.phone = phone;
      if (phone) state.selectedId = phone.id;
      renderDevicePill();
      renderDevicePicker();
      renderDevices();
      updateStats();
    } catch (e) {
      state.devices = [];
      state.phone = (state.lastPhone && Date.now() - state.lastPhoneAt < 600000)
        ? state.lastPhone
        : null;
      renderDevicePill();
      renderDevices();
      updateStats();
    }
  }

  // ---------------------------------------------------------------- clipboard

  function filteredClipboard() {
    return state.clipboard.filter(function (item) {
      return state.filter === 'all' || item.source === state.filter;
    });
  }

  function renderClipboard() {
    var list = filteredClipboard();
    var box = $('#clipList');
    if (!list.length) {
      box.innerHTML = emptyState('还没有剪贴板记录', '在电脑上复制文字，或点「同步手机」');
    } else {
      box.innerHTML = list.map(function (item) {
        var source = item.source === 'phone' ? '手机' : '电脑';
        var preview = item.text.length > 320 ? item.text.slice(0, 320) + '…' : item.text;
        return '<div class="clipItem" data-id="' + esc(item.id) + '">' +
          '<div class="clipBody"><div class="clipText">' + esc(preview) + '</div>' +
          '<div class="clipMeta"><span class="sourceTag ' + item.source + '">' + source + '</span>' +
          '<span>' + relTime(item.time) + '</span><span>' + item.text.length + ' 字</span></div></div>' +
          '<div class="clipOps">' +
          '<button class="miniIconBtn" data-act="copy" title="复制">' + icon('copy') + '</button>' +
          '<button class="miniIconBtn danger" data-act="del" title="删除">' + icon('trash') + '</button>' +
          '</div></div>';
      }).join('');
    }
    animateNumber($('#filterNum'), list.length);
    $('#filterLabel').textContent = state.filter === 'all' ? '全部' : (state.filter === 'phone' ? '手机' : '电脑');
  }

  async function fetchClipboard() {
    var localPromise = api('/api/clipboard').catch(function () { return null; });
    var remotePromise = state.phone ? phoneApi(state.phone, '/api/clipboard').catch(function () { return null; }) : Promise.resolve(null);
    var results = await Promise.all([localPromise, remotePromise]);
    var local = results[0];
    var remote = results[1];
    var map = new Map();

    function put(item, source, fromPhone) {
      if (!item || !item.text) return;
      var text = String(item.text);
      var time = Number(item.time) || Date.now();
      var entry = {
        id: fromPhone ? 'phone-' + item.id : String(item.id || ''),
        text: text,
        time: time,
        source: source
      };
      var old = map.get(text);
      if (!old || entry.time >= old.time) map.set(text, entry);
    }

    if (local && local.items) {
      local.items.forEach(function (item) { put(item, item.source === 'phone' ? 'phone' : 'pc', false); });
    }
    if (remote && remote.items) {
      remote.items.forEach(function (item) { put(item, 'phone', true); });
    }

    state.clipboard = Array.from(map.values())
      .sort(function (a, b) { return b.time - a.time; })
      .slice(0, 50);
    renderClipboard();
    updateStats();
    renderRecent();
    renderChart();
  }

  async function copyClip(entry) {
    var ok = await bridge.writeClipboard(entry.text);
    toast(ok === false ? '复制失败' : '已复制到剪贴板');
  }

  async function deleteClip(entry) {
    try {
      if (entry.id.indexOf('phone-') === 0) {
        if (!state.phone) throw new Error('手机不在线');
        await phoneApi(state.phone, '/api/clipboard/' + encodeURIComponent(entry.id.slice(6)), { method: 'DELETE' });
      } else {
        await api('/api/clipboard/' + encodeURIComponent(entry.id), { method: 'DELETE' });
      }
      await fetchClipboard();
      toast('已删除');
    } catch (e) {
      toast('删除失败：' + e.message);
    }
  }

  async function syncClipboard() {
    if (!state.phone) { toast('先在「文件」页选择手机'); return; }
    try {
      var remote = await phoneApi(state.phone, '/api/clipboard');
      var items = (remote && remote.items) || [];
      var count = 0;
      for (var i = 0; i < items.length; i += 1) {
        var item = items[i];
        if (!item || !item.text) continue;
        await api('/api/clipboard', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: item.text, source: 'phone', time: item.time })
        });
        count += 1;
      }
      await fetchClipboard();
      toast(count ? '已同步 ' + count + ' 条' : '没有新内容');
    } catch (e) {
      toast('同步失败：' + e.message);
    }
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
      }
      await fetchClipboard();
      toast(state.phone ? '已发到手机剪贴板' : '已记录到电脑历史（未连接手机）');
    } catch (e) {
      toast('发送失败：' + e.message);
    }
  }

  async function clearClipboard() {
    if (!confirm('清空电脑端的剪贴板历史？手机上的记录不受影响。')) return;
    try {
      await api('/api/clipboard/clear', { method: 'POST' });
      await fetchClipboard();
      toast('已清空电脑历史');
    } catch (e) {
      toast('清空失败：' + e.message);
    }
  }

  // ---------------------------------------------------------------- received / live

  function renderReceived() {
    var box = $('#recvList');
    if (!state.received.length) {
      box.innerHTML = emptyState('还没有收到文件', '在手机「互传」里选择这台电脑发送');
      return;
    }
    box.innerHTML = state.received.map(function (file) {
      var preview = isImageFile(file)
        ? '<span class="fThumb"><img src="' + SERVER + '/api/transfer/file/' + encodeURIComponent(file.id) + '" alt="" loading="lazy"></span>'
        : '<span class="fIcon">' + icon('folder') + '</span>';
      return '<div class="fileItem" data-file-row="' + esc(file.id) + '" title="点一下直接用本机程序打开">' +
        preview +
        '<span class="fBody"><strong>' + esc(file.name) + '</strong>' +
        '<small>' + fmtSize(file.size) + ' · ' + new Date(file.mtime).toLocaleString('zh-CN') + '</small></span>' +
        '<span class="fOps">' +
        '<button class="miniIconBtn" data-file-copy="' + esc(file.id) + '" title="复制文件">' + icon('copy') + '</button>' +
        '<button class="miniIconBtn" data-file-reveal="' + esc(file.id) + '" title="打开文件所在位置">' + icon('folder') + '</button>' +
        '<button class="miniIconBtn danger" data-file-del="' + esc(file.id) + '" title="删除">' + icon('trash') + '</button>' +
        '</span></div>';
    }).join('');
  }

  async function fetchReceived() {
    try {
      var data = await api('/api/transfer/files');
      state.received = (data && data.files) || [];
      renderReceived();
      updateStats();
      renderRecent();
    } catch (e) {}
  }

  function renderActive() {
    var box = $('#recvLive');
    if (!state.active.length) {
      box.innerHTML = '';
      return;
    }
    box.innerHTML = state.active.map(function (t) {
      var percent = Number(t.percent) || 0;
      var speed = fmtSpeed(t.speed);
      var sizeText = fmtSize(t.received) + (t.total ? ' / ' + fmtSize(t.total) : '');
      return '<div class="liveRow">' +
        '<div class="liveHead"><strong>正在接收 · ' + esc(t.name) + '</strong>' +
        '<span>' + percent + '%' + (speed ? ' · ' + speed : '') + '</span></div>' +
        '<div class="progressTrack"><i style="width:' + percent + '%"></i></div>' +
        '<div class="liveMeta"><span>' + sizeText + '</span><span>对方发送中</span></div>' +
        '</div>';
    }).join('');
  }

  async function fetchActive() {
    try {
      var data = await api('/api/transfer/active');
      var list = (data && data.transfers) || [];
      var had = state.lastActiveCount;
      state.active = list;
      state.lastActiveCount = list.length;
      renderActive();
      if (had > 0 && list.length === 0) {
        fetchReceived();
        toast('文件接收完成');
      }
      updateStats();
    } catch (e) {
      if (state.active.length) {
        state.active = [];
        state.lastActiveCount = 0;
        renderActive();
      }
    }
  }

  // ---------------------------------------------------------------- send

  function uploadItem(url, item) {
    return new Promise(function (resolve, reject) {
      var row = document.createElement('div');
      row.className = 'progressItem';
      row.innerHTML = '<div class="progressHead"><strong>' + esc(item.name) + '</strong><span>准备中</span></div>' +
        '<div class="progressTrack"><i></i></div>';
      $('#progressList').prepend(row);
      var bar = row.querySelector('i');
      var info = row.querySelector('.progressHead span');
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
          info.textContent = '完成 · ' + fmtSize(item.size);
          setTimeout(function () { row.remove(); }, 2200);
          resolve();
        } else {
          info.textContent = '失败';
          row.style.borderColor = 'rgba(255,92,108,.4)';
          reject(new Error('HTTP ' + xhr.status));
        }
      };
      xhr.onerror = function () {
        info.textContent = '网络错误';
        row.style.borderColor = 'rgba(255,92,108,.4)';
        reject(new Error('网络错误'));
      };
      xhr.send(item.blob || item.file);
    });
  }

  async function sendItems(items) {
    var list = (items || []).filter(Boolean);
    if (!list.length) return;
    if (!state.phone) { toast('没发现手机，先让手机打开口袋'); return; }
    if (state.sending) { toast('正在发送，请稍候'); return; }
    state.sending = true;
    var phone = state.phone;
    var base = 'http://' + phone.host + ':' + phone.port;
    try {
      var offer = {
        from: { id: 'pc-app', name: 'Pocket Desktop', platform: 'windows' },
        files: list.map(function (item) {
          return { name: item.name, size: item.size, mime: item.type || 'application/octet-stream' };
        })
      };
      var response = await fetch(base + '/api/transfer/offer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(offer)
      });
      var data = await response.json();
      if (!data || !data.token) throw new Error((data && data.error) || '对方拒绝接收');
      for (var i = 0; i < list.length; i += 1) {
        await uploadItem(base + '/api/transfer/upload/' + data.token + '/' + i, list[i]);
      }
      toast('发送完成：' + list.length + ' 个文件');
    } catch (e) {
      toast('发送失败：' + e.message);
    } finally {
      state.sending = false;
    }
  }

  function sendFiles(fileList) {
    var files = Array.prototype.slice.call(fileList || []);
    if (!files.length) return;
    sendItems(files.map(function (file) {
      return {
        file: file,
        name: file.name,
        size: file.size,
        type: file.type || 'application/octet-stream'
      };
    }));
  }

  async function sendBlob(name, blob, type) {
    sendItems([{ blob: blob, name: name, size: blob.size, type: type || 'application/octet-stream' }]);
  }

  // ---------------------------------------------------------------- library

  function renderLibrary() {
    var apps = state.apps || [];
    $('#appsCount').textContent = apps.length;
    $('#htmlCount').textContent = (state.htmlLib || []).length;

    var grid = $('#appGrid');
    if (!apps.length) {
      grid.innerHTML = emptyState('还没有小应用', '把 .pkt 或 HTML 拖进来，会自动生成手机应用');
    } else {
      grid.innerHTML = apps.map(function (app) {
        var iconHtml = app.hasIcon
          ? '<img src="' + SERVER + '/icon/' + encodeURIComponent(app.id) + '" alt="">'
          : esc((app.name || '?').slice(0, 1));
        return '<article class="appCard tilt">' +
          '<div class="appTop"><span class="appIcon">' + iconHtml + '</span>' +
          '<span class="appMeta"><strong>' + esc(app.name) + '</strong>' +
          '<small>v' + esc(app.version) + ' · ' + fmtSize(app.size) + '</small></span></div>' +
          '<p class="appDesc">' + esc(app.description || '没有描述') + '</p>' +
          '<div class="appOps">' +
          '<button class="miniBtn" data-app-send="' + esc(app.file) + '">' + icon('send') + '发到手机</button>' +
          '<button class="miniBtn ghost" data-app-del="' + esc(app.file) + '" title="删除">' + icon('trash') + '</button>' +
          '</div></article>';
      }).join('');
    }

    var htmlBox = $('#htmlList');
    var lib = state.htmlLib || [];
    if (!lib.length) {
      htmlBox.innerHTML = '<div class="libRow">' + emptyState('还没有 HTML 收藏', '把网页另存为 HTML，拖到资源库里') + '</div>';
    } else {
      htmlBox.innerHTML = lib.map(function (item) {
        return '<div class="libRow" data-html-row="' + esc(item.file) + '">' +
          '<span class="fIcon">' + icon('html') + '</span>' +
          '<span class="fBody"><strong>' + esc(item.name) + '</strong>' +
          '<small>' + fmtSize(item.size) + ' · ' + relTime(item.modifiedAt) + '</small></span>' +
          '<span class="fOps">' +
          '<button class="miniIconBtn" data-html-open="' + esc(item.file) + '" title="在浏览器打开">' + icon('external') + '</button>' +
          '<button class="miniIconBtn" data-html-send="' + esc(item.file) + '" title="发到手机">' + icon('send') + '</button>' +
          '<button class="miniIconBtn" data-html-rename="' + esc(item.file) + '" title="重命名">' + icon('edit') + '</button>' +
          '<button class="miniIconBtn danger" data-html-del="' + esc(item.file) + '" title="删除">' + icon('trash') + '</button>' +
          '</span></div>';
      }).join('');
    }

    renderApk();
    bindTiltCards();
  }

  function renderApk() {
    var card = $('#apkCard');
    var host = state.host;
    if (!host) {
      card.innerHTML = '<span class="apkIcon">' + icon('apk') + '</span>' +
        '<div class="apkInfo"><h2>还没有放置手机安装包</h2>' +
        '<p>把 pocket-host.apk 放进 dist 目录，手机就能从局域网下载安装。</p></div>';
      return;
    }
    var lanUrl = state.lanIp ? 'http://' + state.lanIp + ':' + (state.settings.port || 8570) + '/host.apk' : '';
    card.innerHTML =
      '<span class="apkIcon">' + icon('apk') + '</span>' +
      '<div class="apkInfo"><h2>口袋手机端</h2>' +
      '<p>安装到手机后，与电脑连同一个 Wi-Fi，即可互传文件、同步剪贴板。</p>' +
      '<div class="apkMeta">' +
      '<div><span>版本</span><strong>v' + esc(host.version) + '</strong></div>' +
      '<div><span>大小</span><strong>' + fmtSize(host.size) + '</strong></div>' +
      '<div><span>更新</span><strong>' + new Date(host.updatedAt).toLocaleDateString('zh-CN') + '</strong></div>' +
      '</div>' + (lanUrl ? '<div class="lanUrl">' + esc(lanUrl) + '</div>' : '') + '</div>' +
      '<div class="apkOps">' +
      '<button class="primaryBtn" id="apkDownload">' + icon('download') + '下载安装包</button>' +
      '<button class="ghostBtn" id="apkCopy">' + icon('copy') + '复制下载链接</button>' +
      '</div>';
    var dl = $('#apkDownload');
    if (dl) dl.addEventListener('click', function () { bridge.openExternal(SERVER + '/host.apk'); });
    var cp = $('#apkCopy');
    if (cp) cp.addEventListener('click', async function () {
      if (!lanUrl) { toast('还没有检测到局域网地址'); return; }
      var ok = await bridge.writeClipboard(lanUrl);
      toast(ok === false ? '复制失败' : '下载链接已复制');
    });
  }

  async function fetchLibrary() {
    try {
      var results = await Promise.all([
        api('/api/apps').catch(function () { return { apps: [] }; }),
        api('/api/library').catch(function () { return { library: [] }; }),
        api('/api/host').catch(function () { return null; }),
        api('/api/transfer/debug').catch(function () { return null; })
      ]);
      state.apps = (results[0] && results[0].apps) || [];
      state.htmlLib = (results[1] && results[1].library) || [];
      state.host = results[2];
      if (results[3] && results[3].addresses) {
        var ipv4 = results[3].addresses.filter(function (ip) { return /^\d+\.\d+\.\d+\.\d+$/.test(ip) && ip.indexOf('127.') !== 0; });
        state.lanIp = ipv4[0] || '';
      }
      renderLibrary();
      renderDevices();
      updateStats();
    } catch (e) {}
  }

  async function sendApp(file) {
    try {
      toast('正在读取 ' + file + '…');
      var response = await fetch(SERVER + '/apps/' + encodeURIComponent(file));
      if (!response.ok) throw new Error('HTTP ' + response.status);
      var blob = await response.blob();
      await sendBlob(file, blob, 'application/octet-stream');
    } catch (e) {
      toast('发送失败：' + e.message);
    }
  }

  async function uploadLibraryFiles(files) {
    var list = Array.prototype.slice.call(files || []).filter(function (f) {
      return /\.(pkt|html?)$/i.test(f.name);
    });
    if (!list.length) { toast('只支持 .pkt 或 HTML'); return; }
    for (var i = 0; i < list.length; i += 1) {
      var file = list[i];
      var isHtml = /\.html?$/i.test(file.name);
      var url = SERVER + '/api/upload?filename=' + encodeURIComponent(file.name) +
        '&kind=' + (isHtml ? 'html' : 'pkt') + '&target=phone';
      try {
        var response = await fetch(url, { method: 'POST', body: file });
        var data = await response.json();
        if (!response.ok) throw new Error((data && data.error) || ('HTTP ' + response.status));
        toast('已生成手机应用：' + (data.name || file.name));
      } catch (e) {
        toast('上传失败：' + e.message);
      }
    }
    fetchLibrary();
    setLibTab('apps');
  }

  function setLibTab(tab) {
    state.libTab = tab;
    $$('#libNav button').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-libtab') === tab); });
    $$('.libPane').forEach(function (p) { p.classList.toggle('active', p.getAttribute('data-libpane') === tab); });
    moveLibPill();
  }

  // ---------------------------------------------------------------- settings

  function setToggle(el, on) {
    if (!el) return;
    el.classList.toggle('on', !!on);
    el.setAttribute('aria-checked', on ? 'true' : 'false');
  }

  async function loadSettings() {
    try {
      var settings = await bridge.getSettings();
      state.settings = Object.assign({}, state.settings, settings || {});
      updateGreeting();
      setToggle($('#autoLaunchToggle'), state.settings.autoLaunch);
      setToggle($('#themeToggle'), readTheme());
      setToggle($('#topToggle'), state.settings.alwaysOnTop !== false);
      setToggle($('#clipToggle'), state.settings.clipWatch !== false);
      updateFloatChip(!!state.settings.floatVisible);
      updateStats();
    } catch (e) {}
  }

  function updateFloatChip(visible) {
    var chip = $('#floatBtn');
    if (!chip) return;
    chip.classList.toggle('on', !!visible);
    var label = chip.querySelector('span');
    if (label) label.textContent = visible ? '悬浮窗已开' : '悬浮窗';
    var settingBtn = $('#floatSettingBtn');
    if (settingBtn) settingBtn.textContent = visible ? '关闭' : '打开';
  }

  // ---------------------------------------------------------------- palette

  function paletteItems(query) {
    var q = String(query || '').trim().toLowerCase();
    var items = [
      { icon: 'grid', title: '概览', sub: '回到工作台首页', cat: '页面', run: function () { go('overview'); } },
      { icon: 'folder', title: '文件互传', sub: '发送与接收文件', cat: '页面', run: function () { go('files'); } },
      { icon: 'clipboard', title: '剪贴板', sub: '手机和电脑的多轮历史', cat: '页面', run: function () { go('clipboard'); } },
      { icon: 'devices', title: '设备', sub: '查看同一 Wi-Fi 的设备', cat: '页面', run: function () { go('devices'); } },
      { icon: 'library', title: '资源库', sub: '小应用、HTML 收藏、安装包', cat: '页面', run: function () { go('library'); } },
      { icon: 'settings', title: '设置', sub: '启动、悬浮窗和服务', cat: '页面', run: function () { go('settings'); } },
      { icon: 'upload', title: '选择文件发送', sub: '打开文件选择器', cat: '动作', run: function () { go('files'); setTimeout(function () { $('#fileInput').click(); }, 320); } },
      { icon: 'sync', title: '同步手机剪贴板', sub: '把手机历史拉到电脑', cat: '动作', run: syncClipboard },
      { icon: 'send', title: '把电脑剪贴板发到手机', sub: '发送当前复制的文字', cat: '动作', run: pushClipboard },
      { icon: 'pin', title: '开关剪贴板悬浮窗', sub: '拖文件、取手机剪贴板', cat: '动作', run: function () { bridge.floatToggle(); } },
      { icon: 'trash', title: '清空电脑剪贴板历史', sub: '手机记录不受影响', cat: '动作', run: clearClipboard }
    ];

    state.devices.forEach(function (device) {
      items.push({
        icon: device.platform === 'android' ? 'phone' : 'pc',
        title: device.name,
        sub: device.host + ':' + device.port + ' · 发送文件',
        cat: '设备',
        run: function () {
          state.selectedId = device.id;
          state.phone = device;
          renderDevicePill();
          renderDevicePicker();
          go('files');
        }
      });
    });

    state.clipboard.slice(0, 8).forEach(function (item) {
      var text = String(item.text || '').split(/\r?\n/)[0].slice(0, 34);
      items.push({
        icon: 'copy',
        title: text || '(空白剪贴板)',
        sub: '复制到电脑剪贴板 · ' + relTime(item.time),
        cat: '剪贴板',
        run: function () { copyClip(item); }
      });
    });

    state.apps.slice(0, 8).forEach(function (app) {
      items.push({
        icon: 'app',
        title: app.name,
        sub: 'v' + app.version + ' · 发到手机',
        cat: '小应用',
        run: function () { sendApp(app.file); }
      });
    });

    if (!q) return items.slice(0, 18);
    return items.filter(function (item) {
      return (item.title + ' ' + item.sub + ' ' + item.cat).toLowerCase().indexOf(q) !== -1;
    }).slice(0, 18);
  }

  function highlight(text, query) {
    var raw = String(text || '');
    var q = String(query || '').trim();
    if (!q) return esc(raw);
    var index = raw.toLowerCase().indexOf(q.toLowerCase());
    if (index === -1) return esc(raw);
    return esc(raw.slice(0, index)) + '<mark>' + esc(raw.slice(index, index + q.length)) + '</mark>' + esc(raw.slice(index + q.length));
  }

  function renderPalette() {
    var input = $('#paletteInput');
    var query = input.value;
    state.palette.items = paletteItems(query);
    if (!state.palette.items.length) {
      $('#paletteList').innerHTML = '<div class="paletteEmpty">没有匹配的结果</div>';
      return;
    }
    if (state.palette.index >= state.palette.items.length) state.palette.index = 0;
    $('#paletteList').innerHTML = state.palette.items.map(function (item, index) {
      return '<button class="paletteItem ' + (index === state.palette.index ? 'active' : '') + '" data-palette="' + index + '">' +
        '<span class="paletteIcon">' + icon(item.icon) + '</span>' +
        '<span class="paletteText"><strong>' + highlight(item.title, query) + '</strong>' +
        '<small>' + highlight(item.sub, query) + '</small></span>' +
        '<span class="paletteCat">' + esc(item.cat) + '</span></button>';
    }).join('');
  }

  function openPalette() {
    state.palette.open = true;
    state.palette.index = 0;
    $('#palette').classList.add('open');
    $('#paletteInput').value = '';
    renderPalette();
    setTimeout(function () { $('#paletteInput').focus(); }, 30);
  }

  function closePalette() {
    state.palette.open = false;
    $('#palette').classList.remove('open');
    $('#paletteInput').blur();
  }

  function runPalette(index) {
    var item = state.palette.items[index];
    if (!item) return;
    closePalette();
    item.run();
  }

  // ---------------------------------------------------------------- events

  function bindEvents() {
    document.addEventListener('click', function (event) {
      var target = event.target;
      var goBtn = target.closest ? target.closest('[data-go]') : null;
      if (goBtn) {
        go(goBtn.getAttribute('data-go'), goBtn.classList.contains('tilt') ? goBtn : null);
        return;
      }
      var pageBtn = target.closest ? target.closest('[data-page]') : null;
      if (pageBtn && (pageBtn.closest('#mainNav') || pageBtn.closest('#dock'))) {
        go(pageBtn.getAttribute('data-page'));
        return;
      }
      var deviceBtn = target.closest ? target.closest('[data-device]') : null;
      if (deviceBtn) {
        var id = deviceBtn.getAttribute('data-device');
        var device = state.devices.filter(function (d) { return d.id === id; })[0];
        if (device) {
          state.phone = device;
          state.selectedId = device.id;
          renderDevicePill();
          renderDevicePicker();
          $('#devicePicker').classList.remove('open');
          toast('已选择 ' + device.name);
        }
        return;
      }
      if (target.closest && target.closest('[data-refresh-devices]')) {
        fetchDevices().then(function () { toast('已重新扫描'); });
        return;
      }
      var sendDevice = target.closest ? target.closest('[data-send-device]') : null;
      if (sendDevice) {
        var sendId = sendDevice.getAttribute('data-send-device');
        var found = state.devices.filter(function (d) { return d.id === sendId; })[0];
        if (found) { state.phone = found; state.selectedId = found.id; }
        go('files');
        setTimeout(function () { $('#fileInput').click(); }, 260);
        return;
      }
      var clipItem = target.closest ? target.closest('.clipItem') : null;
      if (clipItem) {
        var actBtn = target.closest('[data-act]');
        var entry = state.clipboard.filter(function (c) { return c.id === clipItem.getAttribute('data-id'); })[0];
        if (entry && actBtn) {
          if (actBtn.getAttribute('data-act') === 'copy') copyClip(entry);
          else deleteClip(entry);
        }
        return;
      }
      var appSend = target.closest ? target.closest('[data-app-send]') : null;
      if (appSend) { sendApp(appSend.getAttribute('data-app-send')); return; }
      var appDel = target.closest ? target.closest('[data-app-del]') : null;
      if (appDel) {
        var appFile = appDel.getAttribute('data-app-del');
        if (confirm('从资源库删除「' + appFile + '」？')) {
          api('/api/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ file: appFile })
          }).then(function () { fetchLibrary(); toast('已删除'); }).catch(function (e) { toast(e.message); });
        }
        return;
      }
      var htmlOpen = target.closest ? target.closest('[data-html-open]') : null;
      if (htmlOpen) {
        bridge.openExternal(SERVER + '/library/' + encodeURIComponent(htmlOpen.getAttribute('data-html-open')));
        return;
      }
      var htmlSend = target.closest ? target.closest('[data-html-send]') : null;
      if (htmlSend) {
        var hFile = htmlSend.getAttribute('data-html-send');
        api('/api/library/send', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ file: hFile })
        }).then(function () {
          toast('已生成手机应用，手机「从电脑导入」可见');
          fetchLibrary();
        }).catch(function (e) { toast('打包失败：' + e.message); });
        return;
      }
      var htmlRename = target.closest ? target.closest('[data-html-rename]') : null;
      if (htmlRename) {
        var oldFile = htmlRename.getAttribute('data-html-rename');
        var oldName = oldFile.replace(/\.html?$/i, '');
        var next = prompt('重命名收藏（不用写 .html）', oldName);
        if (!next || next === oldName) return;
        api('/api/library/rename', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ file: oldFile, name: next })
        }).then(function () { fetchLibrary(); toast('已重命名'); }).catch(function (e) { toast(e.message); });
        return;
      }
      var htmlDel = target.closest ? target.closest('[data-html-del]') : null;
      if (htmlDel) {
        var delFile = htmlDel.getAttribute('data-html-del');
        if (confirm('从本机收藏删除「' + delFile + '」？\n已发到手机的应用不受影响。')) {
          api('/api/library/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ file: delFile })
          }).then(function () { fetchLibrary(); toast('已删除'); }).catch(function (e) { toast(e.message); });
        }
        return;
      }
      var fileCopy = target.closest ? target.closest('[data-file-copy]') : null;
      if (fileCopy) {
        var copyId = fileCopy.getAttribute('data-file-copy');
        bridge.copyReceivedFile(copyId).then(function (result) {
          if (!result || !result.ok) {
            toast((result && result.error) || '复制失败');
            return;
          }
          toast(result.kind === 'file' ? '文件已复制，可直接粘贴' : '复制文件失败，已复制路径');
        });
        return;
      }
      var fileReveal = target.closest ? target.closest('[data-file-reveal]') : null;
      if (fileReveal) {
        var revealId = fileReveal.getAttribute('data-file-reveal');
        bridge.revealReceivedFile(revealId).then(function (ok) {
          if (!ok) toast('文件不存在或已被删除');
        });
        return;
      }
      var fileDel = target.closest ? target.closest('[data-file-del]') : null;
      if (fileDel) {
        if (!confirm('删除这个文件？')) return;
        api('/api/transfer/file/' + encodeURIComponent(fileDel.getAttribute('data-file-del')), { method: 'DELETE' })
          .then(function () { fetchReceived(); toast('已删除'); })
          .catch(function (e) { toast(e.message); });
        return;
      }
      var fileRow = target.closest ? target.closest('[data-file-row]') : null;
      if (fileRow) {
        var openId = fileRow.getAttribute('data-file-row');
        bridge.openReceivedFile(openId).then(function (result) {
          if (!result || !result.ok) toast((result && result.error) || '打开失败');
        });
        return;
      }
      var paletteItem = target.closest ? target.closest('[data-palette]') : null;
      if (paletteItem) {
        runPalette(Number(paletteItem.getAttribute('data-palette')));
      }
    });

    $('#searchBtn').addEventListener('click', openPalette);
    $('#paletteMask').addEventListener('click', closePalette);
    $('#paletteInput').addEventListener('input', function () {
      state.palette.index = 0;
      renderPalette();
    });
    $('#paletteInput').addEventListener('keydown', function (event) {
      if (event.key === 'Escape') { closePalette(); return; }
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        state.palette.index = Math.min(state.palette.index + 1, state.palette.items.length - 1);
        renderPalette();
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        state.palette.index = Math.max(state.palette.index - 1, 0);
        renderPalette();
        return;
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        runPalette(state.palette.index);
      }
    });

    document.addEventListener('keydown', function (event) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        if (state.palette.open) closePalette();
        else openPalette();
      }
      if (event.key === 'Escape' && state.palette.open) closePalette();
    });

    $('#filterMain').addEventListener('click', function (event) {
      event.stopPropagation();
      $('#filterWrap').classList.toggle('open');
    });
    document.addEventListener('click', function (event) {
      if (!event.target.closest || !event.target.closest('#filterWrap')) {
        $('#filterWrap').classList.remove('open');
      }
    });
    $$('#filterOptions button').forEach(function (button) {
      button.addEventListener('click', function () {
        state.filter = button.getAttribute('data-filter');
        $('#filterWrap').classList.remove('open');
        renderClipboard();
      });
    });

    $('#chartSeg').addEventListener('click', function (event) {
      var button = event.target.closest('button');
      if (!button) return;
      state.range = Number(button.getAttribute('data-range')) || 7;
      $$('#chartSeg button').forEach(function (b) { b.classList.toggle('on', b === button); });
      renderChart();
    });

    $('#devicePill').addEventListener('click', function (event) {
      event.stopPropagation();
      $('#devicePicker').classList.toggle('open');
    });
    document.addEventListener('click', function (event) {
      if (!event.target.closest || !event.target.closest('.deviceWrap') && !event.target.closest('#devicePill')) {
        $('#devicePicker').classList.remove('open');
      }
    });

    var dropZone = $('#dropZone');
    ['dragenter', 'dragover'].forEach(function (name) {
      dropZone.addEventListener(name, function (event) {
        event.preventDefault();
        dropZone.classList.add('hover');
      });
    });
    ['dragleave', 'drop'].forEach(function (name) {
      dropZone.addEventListener(name, function (event) {
        event.preventDefault();
        dropZone.classList.remove('hover');
      });
    });
    dropZone.addEventListener('drop', function (event) {
      if (event.dataTransfer && event.dataTransfer.files) sendFiles(event.dataTransfer.files);
    });
    dropZone.addEventListener('click', function (event) {
      if (event.target.closest('#pickBtn2')) return;
      $('#fileInput').click();
    });
    $('#pickBtn').addEventListener('click', function () { $('#fileInput').click(); });
    $('#pickBtn2').addEventListener('click', function (event) { event.stopPropagation(); $('#fileInput').click(); });
    $('#fileInput').addEventListener('change', function (event) {
      sendFiles(event.target.files);
      event.target.value = '';
    });

    $('#clipSync').addEventListener('click', syncClipboard);
    $('#clipPush').addEventListener('click', pushClipboard);
    $('#clipClear').addEventListener('click', clearClipboard);
    $('#heroSync') && $('#heroSync').addEventListener('click', syncClipboard);
    $('#dockSync') && $('#dockSync').addEventListener('click', syncClipboard);
    $('#focusSend').addEventListener('click', function () {
      go('files');
      setTimeout(function () { $('#fileInput').click(); }, 280);
    });

    $('#scanBtn').addEventListener('click', function () {
      fetchDevices().then(function () { toast('已重新扫描'); });
    });

    $('#libNav').addEventListener('click', function (event) {
      var button = event.target.closest('button');
      if (button) setLibTab(button.getAttribute('data-libtab'));
    });
    $('#libRefresh').addEventListener('click', function () { fetchLibrary(); toast('已刷新资源库'); });
    $('#libUploadBtn').addEventListener('click', function () { $('#libFileInput').click(); });
    $('#libFileInput').addEventListener('change', function (event) {
      uploadLibraryFiles(event.target.files);
      event.target.value = '';
    });
    var libDrop = $('#libDrop');
    ['dragenter', 'dragover'].forEach(function (name) {
      libDrop.addEventListener(name, function (event) {
        event.preventDefault();
        libDrop.classList.add('hover');
      });
    });
    ['dragleave', 'drop'].forEach(function (name) {
      libDrop.addEventListener(name, function (event) {
        event.preventDefault();
        libDrop.classList.remove('hover');
      });
    });
    libDrop.addEventListener('drop', function (event) {
      if (event.dataTransfer && event.dataTransfer.files) uploadLibraryFiles(event.dataTransfer.files);
    });

    $('#autoLaunchToggle').addEventListener('click', async function () {
      var next = !state.settings.autoLaunch;
      state.settings.autoLaunch = await bridge.setAutoLaunch(next);
      setToggle($('#autoLaunchToggle'), state.settings.autoLaunch);
      toast(state.settings.autoLaunch ? '已开启开机启动' : '已关闭开机启动');
    });
    if (!window.__themeBound) {
      window.__themeBound = true;
      document.addEventListener('click', function (event) {
        var target = event.target;
        if (!target || !target.closest) return;
        if (target.closest('#themeToggle')) {
          var next = !readTheme();
          applyTheme(next, true);
          setToggle($('#themeToggle'), next);
          toast(next ? '已切换到深色模式' : '已切换到浅色模式');
        }
      });
    }
    $('#topToggle').addEventListener('click', async function () {
      var next = state.settings.alwaysOnTop === false;
      state.settings.alwaysOnTop = await bridge.setAlwaysOnTop(next);
      setToggle($('#topToggle'), state.settings.alwaysOnTop);
    });
    $('#clipToggle').addEventListener('click', async function () {
      var next = state.settings.clipWatch === false;
      state.settings.clipWatch = await bridge.setClipWatch(next);
      setToggle($('#clipToggle'), state.settings.clipWatch);
    });
    $('#floatSettingBtn').addEventListener('click', function () { bridge.floatToggle(); });
    $('#startService').addEventListener('click', async function () {
      toast('正在启动本地服务…');
      var ok = await bridge.startService();
      await loadSettings();
      toast(ok ? '本地服务已启动' : '启动失败，请检查 Node 是否安装');
    });
    $('#openDataDir').addEventListener('click', function () {
      var root = state.settings.projectRoot;
      if (!root) { toast('找不到项目目录'); return; }
      bridge.openPath(root + '\\library');
    });
    $('#quitBtn').addEventListener('click', function () { bridge.quit(); });
    $('#sideUser').addEventListener('click', function () { go('settings'); });

    $('#bellBtn').addEventListener('click', function () {
      if (state.active.length) {
        go('files');
        toast(state.active.length + ' 个文件正在接收');
      } else {
        toast('暂时没有新的动态');
      }
    });
    $('#floatBtn').addEventListener('click', function () { bridge.floatToggle(); });
    $('#ballBtn').addEventListener('click', function () { bridge.minimize(); });
    $('#closeBtn').addEventListener('click', function () { bridge.quit(); });

    $$('#dock button').forEach(function (button) {
      button.addEventListener('pointerenter', function () {
        var buttons = $$('#dock button');
        var index = buttons.indexOf(button);
        buttons.forEach(function (b) { b.classList.remove('near'); });
        if (buttons[index - 1]) buttons[index - 1].classList.add('near');
        if (buttons[index + 1]) buttons[index + 1].classList.add('near');
      });
      button.addEventListener('pointerleave', function () {
        $$('#dock button').forEach(function (b) { b.classList.remove('near'); });
      });
    });

    window.addEventListener('resize', function () {
      moveNavPill();
      if (state.page === 'library') moveLibPill();
    });

    bridge.on('clipboard:changed', function () {
      fetchClipboard();
    });
    bridge.on('service:status', function () {
      loadSettings();
    });
    bridge.on('app:syncClipboard', function () {
      syncClipboard();
    });
    bridge.on('navigate', function (page) {
      if (page) go(page);
    });
    bridge.on('window:shown', function () {
      document.body.classList.remove('in');
      void document.body.offsetWidth;
      document.body.classList.add('in');
      setTimeout(function () {
        moveNavPill();
        if (state.page === 'library') moveLibPill();
      }, 60);
      fetchDevices();
      fetchActive();
    });
    bridge.on('float:state', function (payload) {
      updateFloatChip(!!(payload && payload.visible));
    });
  }

  // ---------------------------------------------------------------- init

  async function init() {
    document.body.classList.add('in');
    bindEvents();
    updateGreeting();
    await loadSettings();
    await Promise.all([
      fetchDevices(),
      fetchClipboard(),
      fetchReceived(),
      fetchLibrary()
    ]);
    fetchActive();
    moveNavPill();
    resetScroll();
    requestAnimationFrame(function () {
      moveNavPill();
      resetScroll();
      bindTiltCards();
    });

    setInterval(fetchDevices, 4000);
    setInterval(fetchClipboard, 1500);
    setInterval(fetchReceived, 6000);
    setInterval(fetchActive, 700);
    setInterval(loadSettings, 15000);
  }

  init();
})();
