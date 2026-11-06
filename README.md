# 口袋 Pocket

一个 Android 宿主 App：把 AI 生成的 HTML 小应用装进手机，统一管理、离线可用、更新不丢数据。

- 手机端：Kotlin + Jetpack Compose 宿主，用 WebView 从虚拟 https 域名加载 `.pkt` 插件包
- 电脑端：一个零依赖的 Node 服务 + 管理页，负责「把 HTML 拖进来 → 手机来下载」
- 插件包：`.pkt`（zip = `manifest.json` + `index.html` + 静态资源）
- JS SDK：宿主自动注入 `window.pocket`，提供存储、网络（绕过 CORS）、TTS、弹窗、分享等能力

## 目录

```
android/     Android 宿主（Compose UI / WebView 容器 / 原生桥）
cli/         打包工具与电脑端服务（node cli/bin/pocket.js）
sdk/         注入到每个应用的 pocket-sdk.js
docs/        架构、插件包规范、JS API、交付指南、开发日志
examples/    示例应用（hello / motion / tool）
tools/       桌面快捷方式调用的启动、关闭脚本
dist/        构建产物（apk / pkt，已在 .gitignore 中排除）
```

## 快速开始

```bash
# 1. 打包一个示例应用
node cli/bin/pocket.js pack examples/01-hello -o dist/hello.pkt

# 2. 启动电脑端服务（默认 8570）
node cli/bin/pocket.js serve --dir dist

# 3. 手机端：打开口袋 → 「+」→ 从电脑导入（同一 Wi-Fi 自动发现）
```

宿主构建：

```bash
cd android
../.build/gradle-8.7/bin/gradle assembleRelease   # 需要 JDK 17 + Android SDK 34
```

## 文档

- [01-架构设计](docs/01-架构设计.md)
- [02-插件包规范](docs/02-插件包规范.md)
- [03-JS-API](docs/03-JS-API.md)
- [04-交付指南](docs/04-开发与构建指南.md) —— 给其他工程看：怎么产出能拖进电脑端的文件
- [05-开发日志](docs/05-开发日志.md) —— 长期维护记录
- [项目结构](docs/00-项目结构.md)

## 状态

当前版本见 [CHANGELOG](CHANGELOG.md)。
