/**
 * Pocket SDK v1.0
 * 由宿主自动注入到每个页面（/__pocket/sdk.js），开发者无需手动引入。
 *
 * 设计原则：
 *  1. 同步方法走 PocketNative.call，异步方法走 PocketNative.callAsync + Promise
 *  2. 所有能力都要在 manifest.json 的 permissions 中声明
 *  3. 自动注入移动端兼容层：视口、安全区变量、字体缩放、点击高亮
 */
(function (global) {
  'use strict';

  if (global.pocket && global.pocket.__isPocketSdk) return;

  var native = global.PocketNative || null;

  /* ------------------------------------------------------------------ *
   * 1. Bridge 基础设施
   * ------------------------------------------------------------------ */

  var seq = 1;
  var pending = {};
  var handlers = {};

  var ASYNC_METHODS = {
    'ui.alert': 1,
    'ui.confirm': 1,
    'ui.prompt': 1,
    'ui.actionSheet': 1,
    'clipboard.read': 1,
    'clipboard.write': 1,
    'file.pick': 1,
    'share.text': 1,
    'notify.show': 1,
    'system.openExternal': 1,
    'http.request': 1,
    'ai.chat': 1,
    'tts.speak': 1,
    'tts.stop': 1,
    'tts.available': 1
  };

  function makeError(code, message) {
    var err = new Error(message || code || 'PocketError');
    err.code = code || 'E_UNKNOWN';
    err.isPocketError = true;
    return err;
  }

  function callSync(method, params) {
    if (!native) {
      return { ok: false, code: 'E_NO_BRIDGE', message: '当前页面不在口袋容器内运行' };
    }
    try {
      var raw = native.call(JSON.stringify({ method: method, params: params || {} }));
      return JSON.parse(raw);
    } catch (err) {
      return { ok: false, code: 'E_BRIDGE', message: String((err && err.message) || err) };
    }
  }

  function invoke(method, params) {
    if (!ASYNC_METHODS[method]) {
      var res = callSync(method, params);
      if (res && res.ok) return Promise.resolve(res.data === undefined ? null : res.data);
      return Promise.reject(makeError(res && res.code, res && res.message));
    }
    return new Promise(function (resolve, reject) {
      if (!native) {
        reject(makeError('E_NO_BRIDGE', '当前页面不在口袋容器内运行'));
        return;
      }
      var id = seq++;
      pending[id] = { resolve: resolve, reject: reject };
      native.callAsync(JSON.stringify({ id: id, method: method, params: params || {} }));
    });
  }

  /** 原生异步回调入口 */
  global.__pocketResolve = function (id, payload) {
    var slot = pending[id];
    if (!slot) return;
    delete pending[id];
    var res;
    try {
      res = JSON.parse(payload);
    } catch (e) {
      res = { ok: false, code: 'E_BRIDGE', message: '回调数据解析失败' };
    }
    if (res && res.ok) slot.resolve(res.data === undefined ? null : res.data);
    else slot.reject(makeError(res && res.code, res && res.message));
  };

  /** 原生事件入口 */
  global.__pocketEvent = function (name, payload) {
    var data = payload;
    if (typeof payload === 'string') {
      try { data = JSON.parse(payload); } catch (e) { data = payload; }
    }
    dispatch(name, data);
  };

  function dispatch(name, data) {
    var list = handlers[name];
    if (!list || !list.length) return;
    list.slice().forEach(function (fn) {
      try { fn(data); } catch (e) { console.error('[pocket] 事件处理异常:', name, e); }
    });
  }

  function on(name, fn) {
    if (typeof fn !== 'function') return function () {};
    (handlers[name] = handlers[name] || []).push(fn);
    return function () { off(name, fn); };
  }

  function off(name, fn) {
    var list = handlers[name];
    if (!list) return;
    handlers[name] = list.filter(function (item) { return item !== fn; });
  }

  /* ------------------------------------------------------------------ *
   * 2. 兼容层（解决手机端尺寸、安全区、字体缩放等常见问题）
   * ------------------------------------------------------------------ */

  var compatApplied = false;

  function readDeviceInfo() {
    var res = callSync('device.info', {});
    return res && res.ok ? res.data : null;
  }

  function applyCompat() {
    if (compatApplied) return;
    var doc = global.document;
    if (!doc || !doc.documentElement) return;

    if (doc.head && !doc.querySelector('meta[name="viewport"]')) {
      var meta = doc.createElement('meta');
      meta.setAttribute('name', 'viewport');
      meta.setAttribute('content',
        'width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover');
      doc.head.appendChild(meta);
    }

    var info = readDeviceInfo();
    var area = (info && info.safeArea) || { top: 0, right: 0, bottom: 0, left: 0 };

    var style = doc.createElement('style');
    style.id = '__pocket_base_style';
    style.textContent = [
      ':root{',
      '  --pocket-safe-top:' + (area.top || 0) + 'px;',
      '  --pocket-safe-right:' + (area.right || 0) + 'px;',
      '  --pocket-safe-bottom:' + (area.bottom || 0) + 'px;',
      '  --pocket-safe-left:' + (area.left || 0) + 'px;',
      '  --pocket-keyboard:0px;',
      '}',
      'html{-webkit-text-size-adjust:100%;text-size-adjust:100%;}',
      'body{-webkit-tap-highlight-color:transparent;overscroll-behavior:none;}',
      '*{-webkit-tap-highlight-color:transparent;}'
    ].join('\n');
    (doc.head || doc.documentElement).appendChild(style);

    updateViewport();
    compatApplied = true;
  }

  function updateViewport() {
    var doc = global.document;
    if (!doc || !doc.documentElement) return;
    var h = global.innerHeight || 0;
    doc.documentElement.style.setProperty('--pocket-vh', (h / 100) + 'px');
    doc.documentElement.style.setProperty('--pocket-vw', ((global.innerWidth || 0) / 100) + 'px');
  }

  if (typeof global.addEventListener === 'function') {
    global.addEventListener('resize', updateViewport);
    global.addEventListener('orientationchange', function () {
      setTimeout(updateViewport, 120);
    });
  }

  if (global.document) {
    // 引导脚本被注入在 <head> 开头，此时 head 已存在：
    // 立即写入安全区变量，避免首屏出现布局跳动。
    if (global.document.head) {
      applyCompat();
    } else if (global.document.readyState === 'loading') {
      global.document.addEventListener('DOMContentLoaded', applyCompat);
    } else {
      applyCompat();
    }
  }

  /* ------------------------------------------------------------------ *
   * 3. 公开 API
   * ------------------------------------------------------------------ */

  function dialogArgs(a, b, c) {
    if (typeof a === 'string') {
      return { title: a, message: b == null ? '' : String(b), button: c || '好' };
    }
    return a || {};
  }

  var pocket = {

    __isPocketSdk: true,
    version: '1.0.0',
    platform: 'android',

    /* ---- 应用 ---- */
    app: {
      info: function () { return invoke('app.info'); },
      close: function () { return invoke('app.close'); },
      restart: function () { return invoke('app.restart'); }
    },

    /* ---- 界面 ---- */
    ui: {
      toast: function (text) {
        return invoke('ui.toast', { text: String(text == null ? '' : text) });
      },
      haptic: function (type) {
        return invoke('ui.haptic', { type: type || 'light' });
      },
      fullscreen: function (enabled) {
        return invoke('ui.fullscreen', { enabled: !!enabled });
      },
      keepScreenOn: function (enabled) {
        return invoke('ui.keepScreenOn', { enabled: !!enabled });
      },
      statusBar: function (options) {
        var opts = options || {};
        return invoke('ui.statusBar', {
          style: opts.style || 'auto',
          color: opts.color || ''
        });
      },
      alert: function (a, b, c) {
        var opts = dialogArgs(a, b, c);
        return invoke('ui.alert', {
          title: opts.title || '',
          message: opts.message || '',
          button: opts.button || '好'
        });
      },
      confirm: function (a, b, c) {
        var opts = typeof a === 'string'
          ? { title: '确认', message: a, okText: b, cancelText: c }
          : (a || {});
        return invoke('ui.confirm', {
          title: opts.title || '确认',
          message: opts.message || '',
          okText: opts.okText || '确定',
          cancelText: opts.cancelText || '取消'
        }).then(function (v) { return v === true; });
      },
      prompt: function (a, b, c) {
        var opts = typeof a === 'string'
          ? { title: a, message: b || '', value: c || '' }
          : (a || {});
        return invoke('ui.prompt', {
          title: opts.title || '',
          message: opts.message || '',
          placeholder: opts.placeholder || '',
          value: opts.value || '',
          multiline: !!opts.multiline,
          okText: opts.okText || '确定',
          cancelText: opts.cancelText || '取消'
        });
      },
      actionSheet: function (a, b) {
        var opts = typeof a === 'string' ? { title: '', items: b || [] } : (a || {});
        return invoke('ui.actionSheet', {
          title: opts.title || '',
          items: opts.items || [],
          cancelText: opts.cancelText || '取消'
        });
      }
    },

    /* ---- 存储（跨重启保留，卸载即清除） ---- */
    storage: {
      get: function (key, fallback) {
        return invoke('storage.get', { key: String(key) }).then(function (value) {
          return value === null ? (fallback === undefined ? null : fallback) : value;
        });
      },
      set: function (key, value) {
        return invoke('storage.set', { key: String(key), value: value === undefined ? null : value });
      },
      remove: function (key) {
        return invoke('storage.remove', { key: String(key) });
      },
      clear: function () {
        return invoke('storage.clear');
      },
      keys: function () {
        return invoke('storage.keys');
      },
      size: function () {
        return invoke('storage.size');
      }
    },

    /* ---- 网络（原生代理，无 CORS 限制） ---- */
    http: {
      request: function (a, b) {
        var opts = typeof a === 'string' ? Object.assign({ url: a }, b || {}) : (a || {});
        return invoke('http.request', {
          url: opts.url || '',
          method: opts.method || 'GET',
          headers: opts.headers || {},
          body: opts.body == null ? '' : (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body)),
          timeout: opts.timeout || 20000,
          responseType: opts.responseType || 'text'
        });
      }
    },

    /* ---- 设备 ---- */
    device: {
      info: function () { return invoke('device.info'); },
      network: function () { return invoke('device.network'); }
    },

    /* ---- 语音合成（原生 TTS，同时通过兼容层补齐 speechSynthesis） ---- */
    tts: {
      speak: function (options) {
        var opts = typeof options === 'string' ? { text: options } : (options || {});
        return invoke('tts.speak', {
          text: String(opts.text == null ? '' : opts.text),
          lang: opts.lang || 'en-US',
          rate: opts.rate || 1,
          pitch: opts.pitch || 1,
          utteranceId: opts.utteranceId || ''
        });
      },
      stop: function () { return invoke('tts.stop'); },
      available: function () { return invoke('tts.available'); }
    },

    /* ---- AI（统一走宿主里配置的模型，应用不用自带 Key） ---- */
    ai: {
      /** 当前是否配置了模型：{ available, provider, model } */
      status: function () { return invoke('ai.config'); },

      /**
       * 对话。返回助手回复的纯文本。
       * chat('你好') / chat({ prompt: '...', system: '...' })
       * chat({ messages: [{role:'user',content:'...'}, ...], system, temperature, maxTokens })
       */
      chat: function (a, b) {
        var opts = typeof a === 'string' ? { prompt: a } : (a || {});
        var messages = opts.messages;
        if (!messages && opts.prompt) messages = [{ role: 'user', content: String(opts.prompt) }];
        return invoke('ai.chat', {
          messages: messages || [],
          system: opts.system || '',
          temperature: typeof opts.temperature === 'number' ? opts.temperature : 0.7,
          maxTokens: typeof opts.maxTokens === 'number' ? opts.maxTokens : 0
        }).then(function (r) { return (r && r.text !== undefined) ? r.text : r; });
      },

      /** 只要一句话问答的快捷方式 */
      ask: function (prompt, system) {
        return pocket.ai.chat({ prompt: String(prompt == null ? '' : prompt), system: system || '' });
      }
    },

    /* ---- 剪贴板 ---- */
    clipboard: {
      read: function () { return invoke('clipboard.read'); },
      write: function (text) {
        return invoke('clipboard.write', { text: String(text == null ? '' : text) });
      }
    },

    /* ---- 分享 ---- */
    share: {
      text: function (a, b) {
        var opts = typeof a === 'string' ? { text: a, subject: b } : (a || {});
        return invoke('share.text', { text: opts.text || '', subject: opts.subject || '' });
      }
    },

    /* ---- 文件选择（返回 base64 Data URL） ---- */
    file: {
      pick: function (options) {
        var opts = options || {};
        return invoke('file.pick', {
          accept: opts.accept || '*/*',
          multiple: !!opts.multiple
        }).catch(function (err) {
          if (err && err.code === 'E_CANCELLED') return null;
          throw err;
        });
      }
    },

    /* ---- 通知 ---- */
    notify: {
      show: function (a, b) {
        var opts = typeof a === 'string' ? { title: a, body: b || '' } : (a || {});
        return invoke('notify.show', { title: opts.title || '', body: opts.body || '' });
      }
    },

    /* ---- 系统 ---- */
    system: {
      openExternal: function (url) {
        return invoke('system.openExternal', { url: String(url) });
      },
      exit: function () {
        return invoke('system.exit');
      }
    },

    /* ---- 事件 ---- */
    on: on,
    off: off,

    /* ---- 视口工具 ---- */
    viewport: {
      refresh: function () {
        updateViewport();
        return readDeviceInfo();
      },
      safeArea: function () {
        var info = readDeviceInfo();
        return (info && info.safeArea) || { top: 0, right: 0, bottom: 0, left: 0 };
      }
    },

    /* ---- 生命周期 ---- */
    ready: function () {
      return new Promise(function (resolve) {
        if (global.document && global.document.readyState !== 'loading') {
          resolve(pocket);
        } else if (global.document) {
          global.document.addEventListener('DOMContentLoaded', function () { resolve(pocket); });
        } else {
          resolve(pocket);
        }
      });
    }
  };

  /* ---- 键盘高度 ---- */
  on('keyboard', function (data) {
    var height = (data && data.height) || 0;
    if (global.document && global.document.documentElement) {
      global.document.documentElement.style.setProperty('--pocket-keyboard', height + 'px');
    }
  });

  /* ---- 返回键：返回 true 表示已消费，宿主不退出 ---- */
  global.__pocketDispatchBack = function () {
    var list = handlers['back'];
    if (!list || !list.length) return 'false';
    var consumed = false;
    list.slice().forEach(function (fn) {
      try {
        if (fn() !== false) consumed = true;
      } catch (e) {
        console.error('[pocket] back 处理异常:', e);
      }
    });
    return consumed ? 'true' : 'false';
  };

  /* ------------------------------------------------------------------ *
   * 4. speechSynthesis 兼容层
   * Android WebView 不实现 Web Speech API，这里用原生 TTS 补齐，
   * 让依赖 speechSynthesis 的页面（朗读、点词发音）无需改动即可运行。
   * ------------------------------------------------------------------ */
  (function () {
    if (global.speechSynthesis && typeof global.speechSynthesis.speak === 'function') return;

    var listeners = {};
    var counter = 0;

    var synth = {
      speaking: false,
      pending: false,
      paused: false,
      onvoiceschanged: null,
      getVoices: function () {
        return [{
          name: 'Android TTS',
          voiceURI: 'android-tts',
          lang: 'en-US',
          localService: true,
          default: true
        }];
      },
      speak: function (utterance) {
        if (!utterance || !utterance.text) return;
        var id = 'pocket-tts-' + (++counter);
        listeners[id] = utterance;
        synth.speaking = true;
        invoke('tts.speak', {
          text: String(utterance.text),
          lang: utterance.lang || 'en-US',
          rate: utterance.rate || 1,
          pitch: utterance.pitch || 1,
          utteranceId: id
        }).catch(function () {
          delete listeners[id];
          synth.speaking = Object.keys(listeners).length > 0;
          if (typeof utterance.onerror === 'function') {
            try { utterance.onerror({ error: 'synthesis-failed' }); } catch (e) {}
          }
        });
      },
      cancel: function () {
        listeners = {};
        synth.speaking = false;
        invoke('tts.stop', {}).catch(function () {});
      },
      pause: function () { synth.paused = true; },
      resume: function () { synth.paused = false; }
    };

    var Utterance = function (text) {
      this.text = String(text == null ? '' : text);
      this.lang = '';
      this.rate = 1;
      this.pitch = 1;
      this.volume = 1;
      this.voice = null;
      this.onstart = null;
      this.onend = null;
      this.onerror = null;
    };

    global.SpeechSynthesisUtterance = global.SpeechSynthesisUtterance || Utterance;
    global.__pocketTtsEvent = function (id, type) {
      var u = listeners[id];
      if (!u) return;
      if (type === 'start') {
        if (typeof u.onstart === 'function') {
          try { u.onstart({ type: 'start' }); } catch (e) {}
        }
        return;
      }
      if (type === 'end') {
        delete listeners[id];
        synth.speaking = Object.keys(listeners).length > 0;
        if (typeof u.onend === 'function') {
          try { u.onend({ type: 'end' }); } catch (e) {}
        }
        return;
      }
      if (type === 'error') {
        delete listeners[id];
        synth.speaking = Object.keys(listeners).length > 0;
        if (typeof u.onerror === 'function') {
          try { u.onerror({ error: 'synthesis-failed' }); } catch (e) {}
        }
      }
    };

    try {
      Object.defineProperty(global, 'speechSynthesis', {
        value: synth, configurable: true, writable: true
      });
    } catch (e) {
      global.speechSynthesis = synth;
    }
  })();

  /* ------------------------------------------------------------------ *
   * 5. 在线翻译兜底
   * 部分页面先请求 translate.googleapis.com（国内不可达），这里直接改用
   * MyMemory 并返回 Google 相同格式，避免 6 秒超时后才有中文。
   * ------------------------------------------------------------------ */
  (function () {
    var originalFetch = global.fetch;
    if (!originalFetch) return;

    function jsonResponse(obj) {
      var body = JSON.stringify(obj);
      if (typeof global.Response === 'function') {
        return new global.Response(body, {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      return {
        ok: true,
        status: 200,
        json: function () { return Promise.resolve(obj); },
        text: function () { return Promise.resolve(body); }
      };
    }

    function myMemory(q, init) {
      return originalFetch.call(global,
        'https://api.mymemory.translated.net/get?langpair=en|zh-CN&q=' + encodeURIComponent(q),
        init).then(function (r) {
          if (!r.ok) throw new Error('http');
          return r.json();
        }).then(function (d) {
          var out = d && d.responseData && d.responseData.translatedText;
          if (!out) throw new Error('empty');
          return jsonResponse([[[out, q, null, null, 1]]]);
        });
    }

    function baiduDict(q, init) {
      // 百度词典接口不支持跨域，走原生的 http 代理（无 CORS 限制）
      return pocket.http.request({
        url: 'https://fanyi.baidu.com/sug?kw=' + encodeURIComponent(q),
        method: 'GET'
      }).then(function (r) {
        var body = r && r.body;
        if (typeof body === 'string') {
          try { body = JSON.parse(body); } catch (e) { body = null; }
        }
        var entry = body && body.data && body.data[0];
        if (!entry || !entry.v) throw new Error('empty');
        return jsonResponse([[[entry.v, q, null, null, 1]]]);
      });
    }

    global.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      if (url.indexOf('https://translate.googleapis.com/translate_a/single') === 0) {
        var q = '';
        try { q = new global.URL(url).searchParams.get('q') || ''; } catch (e) {}
        // 单词/短语先用百度词典拿到准确的中文释义，失败再退到 MyMemory 机翻
        var words = q.trim().split(/\s+/);
        var isWord = words.length <= 3 && !/[.!?。！？,，;；:：\n]/.test(q);
        if (isWord) {
          return baiduDict(q, init).catch(function () { return myMemory(q, init); });
        }
        // 整句翻译：MyMemory 支持跨域，直接走网络更快
        return myMemory(q, init);
      }
      return originalFetch.apply(global, arguments);
    };
  })();

  global.pocket = pocket;

  // 通知宿主 SDK 已就绪
  if (native && native.call) {
    callSync('app.info', {});
  }
})(window);
