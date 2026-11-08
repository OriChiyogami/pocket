# 口袋 Pocket · JS SDK 接口文档（v1.0）

宿主会把 `pocket-sdk.js` 注入每个页面（`/__pocket/sdk.js`），**开发者不需要手动引入**。页面里直接使用全局对象 `window.pocket` 即可。

所有异步方法都返回 `Promise`，失败时 reject 一个带 `code` 字段的 `Error`。

```js
pocket.ready().then(async () => {
  const info = await pocket.device.info();
  await pocket.ui.toast(`你好 ${info.model}`);
});
```

---

## 0. 自动兼容层

SDK 在页面加载时自动做了这些事，专门解决「HTML 在手机上排版崩坏」的问题：

| 行为 | 说明 |
|---|---|
| 补 viewport | 页面缺少 `meta[name=viewport]` 时自动注入 `width=device-width,initial-scale=1,viewport-fit=cover` |
| 安全区变量 | 注入 CSS 变量 `--pocket-safe-top/right/bottom/left`（单位 px，CSS px 即 dp） |
| 视口高度 | 注入 `--pocket-vh`（= innerHeight 的 1%）、`--pocket-vw` |
| 键盘高度 | 注入 `--pocket-keyboard`，键盘收起时为 `0px` |
| 字体缩放 | 固定 WebView `textZoom = 100`，不受系统字体大小影响 |
| 点击高亮 | 移除 Android 默认的灰色点击块 |
| 滚动回弹 | 禁用 overscroll，观感更接近原生 |

> **默认是「浏览器视口」模式**：宿主会把页面内缩到状态栏与导航栏之内，页面拿到的可用高度就是系统栏之间的区域，`--pocket-safe-*` 与 `--pocket-keyboard` 均为 `0`。这样绝大多数按浏览器尺寸写的页面不需要任何改动就能正常显示。
>
> 只有 manifest 里设置 `display.edgeToEdge: true` 时，页面才会延伸到系统栏下方，此时上述变量才有实际数值，开发者需要自行避让。

推荐写法：

```css
body {
  padding-top: calc(var(--pocket-safe-top) + 16px);
  padding-bottom: calc(var(--pocket-safe-bottom) + 16px);
}
.dock {
  position: fixed;
  bottom: calc(var(--pocket-safe-bottom) + 16px + var(--pocket-keyboard));
}
```

---

## 1. 应用信息

### `pocket.app.info(): Promise<AppInfo>`

```ts
interface AppInfo {
  id: string;
  name: string;
  version: string;
  description: string;
  accent: string;
  permissions: string[];
  source: 'builtin' | 'file' | 'html';
  installedAt: number;   // 毫秒时间戳
  launchCount: number;
  origin: string;        // https://<id>.pocket.local
}
```

### `pocket.app.close(): Promise<void>`
关闭当前小应用，返回宿主首页。

### `pocket.app.restart(): Promise<void>`
重新加载当前小应用。

---

## 2. 界面

### `pocket.ui.toast(text: string): Promise<void>`
原生 Toast，约 2 秒。

### `pocket.ui.haptic(type?: 'light'|'medium'|'heavy'|'success'|'error'): Promise<void>`
触感反馈，默认 `light`。**需要 `haptic` 权限。**

### `pocket.ui.fullscreen(enabled: boolean): Promise<void>`
隐藏 / 显示状态栏与导航栏。

### `pocket.ui.keepScreenOn(enabled: boolean): Promise<void>`
保持屏幕常亮（适合计时器、阅读类应用）。

### `pocket.ui.statusBar(options): Promise<void>`

```js
await pocket.ui.statusBar({ style: 'dark' }); // dark = 浅色图标，适合深色背景
```

`style`: `'auto' | 'light' | 'dark' | 'hidden'`
- `light`：浅色背景，使用深色图标
- `dark`：深色背景，使用浅色图标
- `hidden`：隐藏状态栏

### `pocket.ui.alert(options | title, message?, button?): Promise<void>`

```js
await pocket.ui.alert('提示', '保存成功');
await pocket.ui.alert({ title: '提示', message: '保存成功', button: '知道了' });
```

### `pocket.ui.confirm(options | message, okText?, cancelText?): Promise<boolean>`

```js
const ok = await pocket.ui.confirm('确定要删除吗？', '删除', '取消');
if (ok) { /* ... */ }
```

### `pocket.ui.prompt(options | title, message?, value?): Promise<string | null>`

```js
const name = await pocket.ui.prompt({
  title: '新建清单',
  placeholder: '给它起个名字',
  value: '',
  multiline: false,
  okText: '创建',
  cancelText: '取消'
});
if (name !== null) { /* ... */ }
```

### `pocket.ui.actionSheet(options | title, items?): Promise<number | null>`

```js
const index = await pocket.ui.actionSheet({ title: '选择操作', items: ['分享', '复制', '删除'] });
if (index === null) return;   // 取消
```

---

## 3. 存储

数据写在应用私有目录，**进程重启、手机重启都保留**；卸载应用或「清除数据」时清空。值可以是任意可 JSON 序列化的对象。

| 方法 | 说明 |
|---|---|
| `storage.get(key, fallback?)` | 读取，不存在时返回 `fallback`（默认 `null`） |
| `storage.set(key, value)` | 写入 |
| `storage.remove(key)` | 删除单个键 |
| `storage.clear()` | 清空 |
| `storage.keys(): string[]` | 所有键 |
| `storage.size(): number` | 占用字节数 |

```js
await pocket.storage.set('settings', { theme: 'dark', fontSize: 15 });
const settings = await pocket.storage.get('settings', {});
```

限制：key ≤ 128 字符，单值 ≤ 256KB。

---

## 4. 网络

### `pocket.http.request(options): Promise<HttpResponse>`

```js
const res = await pocket.http.request({
  url: 'https://api.example.com/list',
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ page: 1 }),
  timeout: 20000,
  responseType: 'text'   // 'text' | 'base64'
});
res.status;      // 200
res.headers;     // { 'content-type': 'application/json', ... }
res.body;        // 自动解析的 JSON 对象，或字符串，或 base64
res.contentType; // 原始 Content-Type
```

- 只支持 `https://`
- 域名必须在 `manifest.network.whitelist` 中，或经用户弹窗授权
- 绕过 CORS（走原生网络栈）
- 未授权时 reject：`{ code: 'E_NETWORK_DENIED' }`

---

## 5. 设备

### `pocket.device.info(): Promise<DeviceInfo>`

```ts
interface DeviceInfo {
  brand: string;
  model: string;
  os: string;            // "Android 14"
  sdk: number;
  width: number;         // 像素
  height: number;
  density: number;
  dpWidth: number;       // CSS px 宽度
  dpHeight: number;
  locale: string;        // "zh-CN"
  theme: 'light' | 'dark';
  safeArea: { top: number; right: number; bottom: number; left: number };
  keyboard: number;
  viewportHeight: number;
  statusBar: number;
  platform: 'android';
  container: 'pocket';
}
```

### `pocket.device.network(): Promise<{ connected, type, metered, online }>`

`type`: `'wifi' | 'cellular' | 'ethernet' | 'none' | 'unknown'`

### `pocket.viewport.refresh(): DeviceInfo`

重新计算 `--pocket-vh` / `--pocket-vw`（通常不需要手动调用，旋转屏幕时 SDK 会自动处理）。

---

## 6. 剪贴板 / 分享 / 文件 / 通知

### `pocket.clipboard.read(): Promise<string>` · `pocket.clipboard.write(text)`
需要 `clipboard` 权限。

### `pocket.share.text({ text, subject? })`
调起系统分享面板。需要 `share` 权限。

### `pocket.file.pick(options): Promise<PickedFile[] | null>`

```js
const files = await pocket.file.pick({ accept: 'image/*', multiple: false });
if (files) {
  console.log(files[0].name, files[0].mime, files[0].size, files[0].dataUrl);
}
```

```ts
interface PickedFile {
  name: string;
  mime: string;
  size: number;
  dataUrl: string;   // data:image/png;base64,....
}
```

- 取消选择返回 `null`（不抛错）
- 单文件上限 8MB
- 需要 `file` 权限

### `pocket.notify.show({ title, body })`
发送系统通知。需要 `notify` 权限；Android 13+ 首次使用会请求通知权限。

### `pocket.system.openExternal(url)`
在系统浏览器打开链接。需要 `external` 权限。

### `pocket.system.exit()`
同 `pocket.app.close()`。

---

## 7. 语音合成

口袋内置原生 TTS（`android.speech.tts.TextToSpeech`），并提供浏览器 `speechSynthesis` 兼容层，页面里原来的朗读代码通常不用改。

### `pocket.tts.speak(options): Promise<void>`

```js
await pocket.tts.speak({ text: 'Good morning', lang: 'en-US', rate: 0.9, pitch: 1 });
await pocket.tts.stop();
const ok = await pocket.tts.available();
```

- `rate` 0.5–2.0，`pitch` 0.5–2.0
- 不需要额外声明权限
- 未安装英文语音包时 `available()` 返回 `false`

### `speechSynthesis` 兼容层

若系统 WebView 没有可用的语音实现，SDK 会自动安装一个：

```js
const u = new SpeechSynthesisUtterance('I am writing to...');
u.lang = 'en-US';
u.rate = 0.9;
speechSynthesis.cancel();
speechSynthesis.speak(u);
```

- `speechSynthesis.getVoices()` 返回 `[{ voiceURI: 'android-tts', lang: 'en-US', ... }]`
- `onstart` / `onend` / `onerror` 会正常回调
- 首次发声有 1–2 秒引擎初始化延迟（模拟器更慢），属正常现象

### `fetch` 兼容层（翻译）

页面直接 `fetch()` 翻译接口时，SDK 会接管 `translate.googleapis.com/translate_a/single`：

- 单词 / 短语（≤3 个词、无标点）→ 走原生代理请求百度词典 `fanyi.baidu.com/sug`，返回释义
- 整句 → MyMemory 机翻（`https://api.mymemory.translated.net/get`）

涉及的域名要写进 `manifest.network.whitelist`，否则首次会弹「网络访问请求」；示例应用可以在 manifest 里
写好自己的白名单，例如 `api.mymemory.translated.net`、`translate.googleapis.com`、`fanyi.baidu.com`。

---

## 8. AI 对话

宿主里配置一次模型（设置 → AI 模型），所有应用通过 `pocket.ai` 统一调用，**不需要也不应该**各自保存 API Key。

### `pocket.ai.chat(options | prompt): Promise<string>`

```js
// 最快：一句话
const answer = await pocket.ai.chat('用一句话解释光合作用');

// 带上下文（做「这题为什么选 C」这类追问）
const reply = await pocket.ai.chat({
  system: '你是答疑老师，用中文回答，解释要短。',
  messages: [
    { role: 'user', content: '题干：…… 选项：A…B…C…D…，正确答案是 C。为什么不选 B？' }
  ],
  temperature: 0.3,
  maxTokens: 600
});
```

- 返回：助手回复的**纯文本字符串**
- 失败：Promise reject，`err.code = 'E_AI'`，`err.message` 里带接口状态码与返回片段
- 可选参数：`system`、`temperature`（默认 0.7）、`maxTokens`（默认不限制）
- 超时：连接 15 秒、读取 90 秒
- 不需要在 `manifest.permissions` 里声明（属于宿主级能力，和 tts 一样）

### `pocket.ai.ask(prompt, system?): Promise<string>`
`chat` 的简写版。

### `pocket.ai.chatFull(options): Promise<{ text, reasoning }>`
需要把「思考过程」和「正式回答」分开时用这个；`chat()` 永远只返回正式回答。
宿主会自动识别两种来源：接口的 `message.reasoning_content` / `reasoning` / `thinking` 字段，
以及被塞进正文里的 `<thinking>…</thinking>` 块。

### `pocket.ai.status(): Promise<{ available, provider, model }>`

```js
const st = await pocket.ai.status();
if (!st.available) { /* 引导用户去 设置 → AI 模型 */ }
```

### 两种模型配置

| 类型 | 需要填 | 说明 |
| --- | --- | --- |
| DeepSeek | 只填 API Key | 地址固定 `https://api.deepseek.com`，模型默认 `deepseek-chat` |
| 其他 / 中转站 | Base URL + Key + 模型名 | 标准 OpenAI 格式：`POST {BaseURL}/chat/completions`，`Authorization: Bearer <Key>` |

Key 只存在手机本地（SharedPreferences），不会随应用包分发。

---

## 9. 生图

宿主里配置一次中转站（设置 → 生图模型），应用通过 `pocket.image` 统一调用，同样不需要自带 Key。
手机首页右上角的「画」就是这套能力的原生界面：输入提示词、加最多 4 张参考图，生成后可保存到相册或分享。

### `pocket.image.generate(options | prompt): Promise<string[]>`

```js
// 纯文生图
const images = await pocket.image.generate('雨后的赛博朋克街道，霓虹倒影，电影感打光');
document.querySelector('img').src = images[0]; // data:image/png;base64,...

// 带参考图（改图）
const out = await pocket.image.generate({
  prompt: '把背景换成淡蓝色，保持主体不变',
  images: [refDataUrl],   // Data URL 或纯 base64，最多 4 张
  size: '1024x1024',
  quality: 'high',
  n: 1
});
```

- 返回：图片 Data URL 数组（`['data:image/png;base64,...']`），顺序与接口返回一致
- 参数：
  - `prompt`（必填）
  - `size`：`auto`（默认）/ `1024x1024` / `1536x1024` / `1024x1536`
  - `quality`：`auto`（默认）/ `low` / `medium` / `high`
  - `n`：1～4，默认 1
  - `format`：`png`（默认）/ `jpeg` / `webp`
  - `images`：参考图数组；为空走 `POST {BaseURL}/images/generations`，非空走 `POST {BaseURL}/images/edits`（multipart）
- 失败：Promise reject，`err.code = 'E_IMAGE'`
- 超时：连接 20 秒、读取 300 秒；大图常见 30～90 秒，页面里请给 loading 态
- 同样不需要在 `manifest.permissions` 里声明

### `pocket.image.status(): Promise<{ available, model, baseUrl }>`

```js
const st = await pocket.image.status();
if (!st.available) { /* 引导用户去 设置 → 生图模型 */ }
```

---

## 10. 文件与互传

「互传」和「口袋文件」两个内置应用就是用下面这组接口实现的。文件存在应用私有的
`files/transfer/`，与系统相册/下载、各个应用的 `www` 完全分开。

### `pocket.transfer.status(): Promise<TransferStatus>`

```js
const st = await pocket.transfer.status();
// { running, port, ip, deviceId, deviceName, peers }
```

### `pocket.transfer.devices(): Promise<Peer[]>`

```js
const devices = await pocket.transfer.devices();
// [{ id, name, platform: 'android'|'windows', host, port, lastSeen }]
```

同一 Wi-Fi 下，对端口袋打开后 5 秒内会自动出现；过期设备（25 秒无广播）自动消失。

### `pocket.transfer.pickAndSend(deviceId): Promise<{ ok, sent: string[] }>`

弹出原生文件选择器（可多选），选完直接流式发送，不经过 base64、不限文件大小。
发送过程中会收到 `transfer.progress` 事件。

```js
const devices = await pocket.transfer.devices();
if (devices.length) {
  const r = await pocket.transfer.pickAndSend(devices[0].id);
  console.log('已发送', r.sent);
}
```

### `pocket.transfer.sendStored(deviceId, ids): Promise<{ ok, sent: string[] }>`

把「口袋文件」里已有的文件转发给另一台设备（手机或电脑）。

### 传输事件

```js
pocket.on('transfer.progress', (p) => {
  // { name, sent, total, percent }
});
pocket.on('transfer.received', (data) => {
  // { file: { id, name, size, mime, direction, peer, time } }
});
```

### `pocket.files.list(options?): Promise<TransferFile[]>`

```js
const files = await pocket.files.list({ direction: 'in' }); // '' | 'in' | 'out'
// [{ id, name, size, mime, direction, peer, time }]
```

### `pocket.files.rename(id, name)` · `pocket.files.remove(ids)`

`ids` 可以是单个 id 或数组。

### `pocket.files.save(id): Promise<{ ok, path }>`

另存到系统「下载/Pocket」，方便用其它 App 打开。

### `pocket.files.url(id): string`

返回同源预览地址 `/__pocket/files/<id>`，可直接用于 `<img>`、`<video>`、`<audio>` 或 `fetch`。
该地址由宿主从 `files/transfer` 里流式返回，支持 Range，不经过网络。

---

## 11. 事件

```js
pocket.on('back', () => {
  // 返回 true 或 undefined → 消费返回键，应用不退出
  // 返回 false → 交还给宿主，退出应用
});
```

| 事件 | 触发时机 | payload |
|---|---|---|
| `resume` | 应用回到前台 | `{}` |
| `pause` | 应用进入后台 | `{}` |
| `back` | 用户按返回键 | — |
| `themechange` | 系统深浅色切换 | `{ dark: boolean }` |
| `networkchange` | 网络连通性变化 | `{ connected: boolean }` |
| `keyboard` | 键盘弹出/收起 | `{ height: number }` |
| `destroy` | 页面即将销毁 | `{}` |

`pocket.on(name, fn)` 返回一个取消订阅函数；`pocket.off(name, fn)` 手动移除。

---

## 12. 错误码

| code | 含义 |
|---|---|
| `E_PERMISSION` | manifest 未声明该能力 |
| `E_NETWORK_DENIED` | 域名不在白名单且用户拒绝 |
| `E_NETWORK` | 请求失败（超时、DNS、证书等） |
| `E_INVALID_ARG` | 参数缺失或不合法 |
| `E_NO_METHOD` | 未知方法 |
| `E_QUOTA` | 存储超限 |
| `E_CANCELLED` | 用户取消（`file.pick` 会转成 `null`，不会抛） |
| `E_AI` | AI 对话失败（接口状态码 / 额度 / 模型名） |
| `E_IMAGE` | 生图失败（接口状态码 / 额度 / 模型名） |
| `E_TRANSFER` | 互传失败（对端离线、网络中断、对方拒绝） |
| `E_NOT_FOUND` | 设备不在线或文件不存在 |
| `E_IO` | 本地文件读写失败（导出、重命名等） |
| `E_NO_BRIDGE` | 页面不在口袋容器内（例如用浏览器直接打开） |
| `E_INTERNAL` | 宿主内部异常 |

---

## 13. 类型声明

完整 TypeScript 声明见 `sdk/types/pocket.d.ts`。
