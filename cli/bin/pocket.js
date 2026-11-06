#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const { packDirectory, packHtmlFile } = require('../lib/pack');
const { startServer } = require('../lib/serve');

const HELP = `
口袋 Pocket · 打包工具

用法：
  node cli/bin/pocket.js init <目录> [--name 名称] [--id 应用id]
  node cli/bin/pocket.js pack <目录> -o <输出.pkt> [选项]
  node cli/bin/pocket.js pack --html <文件.html> [-o 输出.pkt] [--name 名称]
  node cli/bin/pocket.js serve [--dir dist] [--port 8570] [--name 我的电脑]

pack 选项：
  -o, --out <file>       输出文件（默认 dist/<id>.pkt）
  --name <名称>          覆盖 manifest 中的应用名
  --id <id>              覆盖 manifest 中的应用 id
  --version <版本>       覆盖版本号
  --accent <#RRGGBB>     覆盖主题色

示例：
  node cli/bin/pocket.js pack examples/02-motion -o dist/motion.pkt
  node cli/bin/pocket.js pack --html D:/demo.html --name "我的小玩意"
  node cli/bin/pocket.js serve --dir dist

说明：
  · 插件包为 zip 结构，包含 manifest.json + index.html + 静态资源
  · 宿主会自动注入 pocket-sdk.js，页面无需手动引入
  · serve 会启动局域网服务，手机端「+ → 从电脑导入」即可拉取
`;

function parseArgs(argv) {
  const positional = [];
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg.startsWith('-')) {
      const key = arg.replace(/^-{1,2}/, '');
      const next = argv[i + 1];
      if (next && !next.startsWith('-')) {
        flags[key] = next;
        i++;
      } else {
        flags[key] = true;
      }
    } else {
      positional.push(arg);
    }
  }
  return { positional, flags };
}

function cmdInit(positional, flags) {
  const dir = positional[0];
  if (!dir) throw new Error('请指定目录，例如：pocket init ./my-app');

  const absDir = path.resolve(dir);
  if (fs.existsSync(absDir)) {
    throw new Error(`目录已存在：${absDir}`);
  }
  fs.mkdirSync(absDir, { recursive: true });

  const folder = path.basename(absDir);
  const id = flags.id || 'local.' + folder.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  const name = flags.name || folder;

  const manifest = {
    schema: 'pocket.app/1',
    id,
    name,
    version: '1.0.0',
    entry: 'index.html',
    description: '一句话介绍这个小玩意',
    accent: '#7C5CFF',
    display: {
      orientation: 'auto',
      statusBar: 'auto',
      fullscreen: false,
      background: '#FFFFFFFF'
    },
    permissions: ['storage', 'haptic', 'clipboard', 'share', 'external'],
    network: { whitelist: [] }
  };

  fs.writeFileSync(path.join(absDir, 'manifest.json'), JSON.stringify(manifest, null, 2), 'utf8');
  fs.writeFileSync(path.join(absDir, 'index.html'), TEMPLATE, 'utf8');

  console.log(`已创建 ${absDir}`);
  console.log(`  manifest.json  id = ${id}`);
  console.log(`  index.html     入口页面`);
  console.log('');
  console.log('下一步：编辑 index.html，然后执行');
  console.log(`  node cli/bin/pocket.js pack ${dir} -o dist/${id}.pkt`);
}

function cmdPack(positional, flags) {
  const input = flags.html || positional[0];
  if (!input) throw new Error('请指定要打包的目录或 --html 文件');

  const isHtml = !!flags.html || /\.html?$/i.test(input);
  const out = flags.out || flags.o ||
    path.join('dist', (flags.name ? slug(flags.name) : path.basename(input).replace(/\.html?$/i, '')) + '.pkt');

  const options = {
    name: flags.name,
    id: flags.id,
    version: flags.version,
    accent: flags.accent
  };

  const result = isHtml
    ? packHtmlFile(input, out, options)
    : packDirectory(input, out, options);

  console.log(`已打包 ${result.file}`);
  console.log(`  应用  ${result.manifest.name}  (${result.manifest.id})`);
  console.log(`  版本  ${result.manifest.version}`);
  console.log(`  文件  ${result.files} 个，原始 ${kb(result.sourceBytes)}，压缩后 ${kb(result.packedBytes)}`);
}

function cmdServe(positional, flags) {
  const dir = flags.dir || positional[0] || 'dist';
  const port = Number(flags.port || 8570);
  const name = flags.name || '我的电脑';

  startServer({ dir, port, name }).then((ctx) => {
    console.log('');
    console.log('  口袋 · 电脑端服务已启动');
    console.log('  目录：' + ctx.dir);
    console.log('  端口：' + port + '（局域网发现端口 ' + (port + 1) + '）');
    console.log('');
    ctx.address.forEach((a) => {
      console.log('  管理页： http://' + a.address + ':' + port + '/');
    });
    console.log('');
    console.log('  手机端：打开口袋 → 点 “+” → 从电脑导入（同一 Wi-Fi 下会自动发现）');
    console.log('  按 Ctrl+C 停止');
    console.log('');
  }).catch((error) => {
    console.error('服务启动失败：' + error.message);
    process.exit(1);
  });
}

function cmdList() {
  const dist = path.resolve('dist');
  if (!fs.existsSync(dist)) {
    console.log('dist 目录还没有产物');
    return;
  }
  const files = fs.readdirSync(dist).filter((f) => f.endsWith('.pkt'));
  if (!files.length) {
    console.log('dist 目录还没有 .pkt 产物');
    return;
  }
  for (const file of files) {
    const size = fs.statSync(path.join(dist, file)).size;
    console.log(`  ${file.padEnd(24)} ${kb(size)}`);
  }
}

function slug(text) {
  return text.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fa5]+/g, '-').replace(/^-|-$/g, '') || 'app';
}

function kb(bytes) {
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`;
}

function main() {
  const { positional, flags } = parseArgs(process.argv.slice(2));
  const command = positional.shift();

  try {
    switch (command) {
      case 'init':
        cmdInit(positional, flags);
        break;
      case 'pack':
        cmdPack(positional, flags);
        break;
      case 'list':
        cmdList();
        break;
      case 'serve':
        cmdServe(positional, flags);
        break;
      case 'help':
      case undefined:
      case '-h':
      case '--help':
        console.log(HELP);
        break;
      default:
        console.error(`未知命令：${command}`);
        console.log(HELP);
        process.exit(1);
    }
  } catch (error) {
    console.error('错误：' + error.message);
    process.exit(1);
  }
}

const TEMPLATE = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>我的小玩意</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    min-height: 100vh;
    padding: calc(var(--pocket-safe-top, 0px) + 32px) 24px
             calc(var(--pocket-safe-bottom, 0px) + 32px);
    font-family: -apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
    background: radial-gradient(120% 80% at 50% 0%, #E9E4FF 0%, #F7F6FA 55%, #FFFFFF 100%);
    color: #14121C;
    display: flex; flex-direction: column; gap: 16px;
  }
  @media (prefers-color-scheme: dark) {
    body { background: radial-gradient(120% 80% at 50% 0%, #241C4D 0%, #0C0B10 60%); color: #F2F0F7; }
  }
  h1 { font-size: 28px; letter-spacing: -0.5px; }
  p  { color: #6B6780; font-size: 14px; line-height: 1.7; }
  .card {
    background: rgba(255,255,255,.7);
    border: 1px solid rgba(124,92,255,.18);
    border-radius: 24px; padding: 20px; backdrop-filter: blur(12px);
  }
  @media (prefers-color-scheme: dark) {
    .card { background: rgba(255,255,255,.06); border-color: rgba(255,255,255,.1); }
  }
  button {
    width: 100%; margin-top: 12px; padding: 14px;
    border: none; border-radius: 16px; font-size: 15px; font-weight: 600;
    color: #fff; background: linear-gradient(135deg, #7C5CFF, #FF6B9D);
  }
</style>
</head>
<body>
  <h1>我的小玩意</h1>
  <p>这是口袋应用的模板页面。它可以读取安全区变量，也可以调用原生能力。</p>
  <div class="card">
    <p id="status">还没调用过原生能力</p>
    <button onclick="sayHi()">试一下 Toast + 触感</button>
  </div>
<script>
  async function sayHi() {
    await pocket.ui.toast('你好，口袋');
    await pocket.ui.haptic('light');
    document.getElementById('status').textContent = '原生能力调用成功';
  }
  pocket.ready().then(async () => {
    const info = await pocket.device.info();
    document.getElementById('status').textContent =
      info.brand + ' ' + info.model + ' · ' + info.dpWidth + 'dp 宽';
  });
</script>
</body>
</html>
`;

main();
