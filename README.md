# 口袋 Pocket

**把 AI 生成的 HTML 小应用装进口袋，在手机上像原生 App 一样打开；电脑和手机在同一 Wi-Fi 下直接传文件、同步剪贴板。**

一个本地优先的个人小工具集：文件互传、剪贴板与小应用数据都在本机或局域网内处理；只有主动使用 AI 生图 / 对话时，才会访问你自己配置的模型接口。

## 它能做什么

- **小应用宿主**：把单文件 HTML / `.pkt` 插件包安装成手机上的「小玩意」，统一管理、离线可用
- **JS SDK**：宿主自动注入 `window.pocket`，提供本地存储、跨域请求、TTS、弹窗、分享、文件、剪贴板等能力
- **局域网互传**：手机 ↔ 电脑自动发现，直接拖文件互传，不走微信 / QQ / 网盘；带双向进度与速度
- **双向剪贴板**：手机复制的文字 1~2 秒内出现在电脑剪贴板，电脑复制的也能发到手机，保留多轮历史
- **电脑端悬浮球 / 悬浮窗**：桌面常驻小窗，随时发文件、看剪贴板；支持浅蓝工作台与深色玻璃两套主题
- **AI 生图**：手机端内置生图页，支持提示词、参考图、自定义尺寸（填 `宽x高`）、批量生成与保存相册

## 组成

| 部分 | 技术 | 说明 |
| --- | --- | --- |
| 手机端 | Kotlin + Jetpack Compose | Android 8.0+，WebView 从虚拟 https 域名加载 `.pkt`，`PocketBridge` 注入原生能力 |
| 电脑端 | Electron（原生 DOM/CSS） | Windows 桌面管理页、悬浮球、悬浮窗；内置 Node 互传服务 |
| 插件包 | `.pkt`（zip） | `manifest.json` + `index.html` + 静态资源，可热装、可更新 |
| SDK | JavaScript | `sdk/src/pocket-sdk.js`，由宿主注入每个小应用 |
| CLI | Node（零依赖） | 打包 `.pkt`、启动电脑端服务、局域网导入 |

## 架构

```
┌──────────────────────────┐        同一 Wi-Fi        ┌──────────────────────────┐
│ Android 宿主 (Compose)    │◄───────────────────────►│ Windows 桌面端 (Electron) │
│  ├ WebView 小应用         │   HTTP 8572 / UDP 8571   │  ├ 管理页 / 悬浮窗        │
│  ├ PocketBridge + SDK     │                         │  ├ 互传服务 8570          │
│  ├ 互传服务 / 文件存储     │                         │  ├ 剪贴板轮询 / 悬浮球     │
│  └ 剪贴板监听              │                         │  └ 文件库 library/        │
└──────────────────────────┘                         └──────────────────────────┘
```

- 设备发现：UDP 8571（广播 + 网关单播探测），HTTP 8572 负责手机端文件收发
- 文件与剪贴板全部在本机流转；电脑端文件默认保存在 `library/transfer`
- 小应用通过 `https://<app-id>.pocket.local` 这类虚拟域名加载，隔离存储与权限

## 快速开始

### 1. 电脑端

```bash
cd desktop
npm install
npm start          # 开发运行
npm run pack       # 打包到 ../dist/desktop/Pocket-win32-x64
```

Windows 上也可以直接双击 `tools/pocket-desktop.cmd`（优先启动已打包版本，否则用 Electron 跑源码）。

### 2. 手机端

```bash
cd android
gradle assembleRelease   # 需要 JDK 17 + Android SDK 34 + Gradle 8.7
# 产物：android/app/build/outputs/apk/release/
```

把 APK 安装到手机后，打开「口袋」即可。首次启动会申请通知权限；需要实时剪贴板时按引导开启无障碍服务。

### 3. 打包一个小应用

```bash
node cli/bin/pocket.js pack examples/01-hello -o dist/hello.pkt
node cli/bin/pocket.js serve --dir dist
```

然后在手机端「+」→「从电脑导入」，同一 Wi-Fi 下会自动发现电脑端服务。

## 目录结构

```
android/        Android 宿主（Compose UI / WebView / 原生桥 / 互传 / 剪贴板）
desktop/        电脑端 Electron 应用（主进程 / 渲染层 / 悬浮球 / 悬浮窗 / 资源）
builtin-apps/   内置小应用源码（互传 / 剪贴板 / 口袋文件），构建成 .pkt
cli/            打包与电脑端服务 CLI
sdk/            注入到小应用的 pocket-sdk.js
docs/           架构、插件包规范、JS API、交付指南、开发日志
examples/       示例小应用
tools/          本地启动脚本与图标
```

## 文档

- [项目结构](docs/00-项目结构.md)
- [架构设计](docs/01-架构设计.md)
- [插件包规范](docs/02-插件包规范.md)
- [JS API](docs/03-JS-API.md)
- [开发与构建指南](docs/04-开发与构建指南.md)
- [开发日志](docs/05-开发日志.md)
- [局域网互传](docs/06-局域网互传.md)

## 隐私与安全

- 仓库**不包含**任何个人文件、剪贴板内容、接收的文件、截图或示例隐私数据；`library/`、`.screenshots/` 等均已忽略
- 生图 API Key 只保存在本机 `android/image-secrets.properties`，构建时注入，不入库
- 签名 keystore 与密码只保存在本机 `android/pocket-release.jks` / `android/keystore.properties`，已在 `.gitignore` 中排除
- 所有互传、剪贴板、小应用数据均在本机或局域网内处理，不经过第三方服务器
- AI 功能是可选的：提示词 / 参考图只会发送到你配置的模型接口，不经过本项目的任何中转

## 状态

当前版本与更新记录见 [CHANGELOG](CHANGELOG.md)。手机端 1.10.x、电脑端 1.3.x。
