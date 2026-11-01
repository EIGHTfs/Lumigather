#!/usr/bin/env node
// ============================================================
// npx / 全局安装入口
//
// 判据必须走 realpath：npx 与全局安装都会把 bin 变成指向 node_modules 的符号链接。
// 若用 __filename 的字面路径去推断项目根，链接场景下会指向 bin 的父目录而失败，
// 且失败方式极隐蔽 —— 进程直接退出、用户只看到"跑完什么都没发生"。
//
// 本文件只做三件事：定位项目根、补数据目录与端口、载入服务端入口。
// 不复制任何服务端逻辑，仓库态用 start.sh 启动的行为完全不受影响。
// ============================================================
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");

// 解析符号链接后再定位项目根：npx 缓存目录、全局安装目录都是链接
const SELF = fs.realpathSync(__filename);
const PROJECT_ROOT = path.resolve(path.dirname(SELF), "..");

const argv = process.argv.slice(2);

if (argv.includes("--help") || argv.includes("-h")) {
  console.log([
    "用法: lumigather [--port <端口>]",
    "",
    "  --port <端口>   监听端口（也可用环境变量 PORT，缺省由服务端决定）",
    "  --help          显示本帮助",
    "",
    "数据目录: " + (process.env.GALLERY_DATA_DIR || process.env.DATA_DIR || path.join(os.homedir(), ".lumigather")),
    "          可用环境变量 GALLERY_DATA_DIR 覆盖",
  ].join("\n"));
  process.exit(0);
}

// 数据根：包方式安装时默认落到用户目录，避免升级时连带覆盖缓存与用户上传内容。
// 显式设置了环境变量则尊重设置（与 server/lib/paths.js 的优先级保持一致）。
if (!process.env.GALLERY_DATA_DIR && !process.env.DATA_DIR) {
  process.env.GALLERY_DATA_DIR = path.join(os.homedir(), ".lumigather");
}

// 端口：--port N / --port=N 优先；未给则保留已有 PORT 环境变量，交给服务端取缺省
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === "--port" && argv[i + 1]) { process.env.PORT = argv[i + 1]; break; }
  if (a.startsWith("--port=")) { process.env.PORT = a.slice("--port=".length); break; }
}

// 数据目录必须存在：缓存与上传都写在它下面
fs.mkdirSync(process.env.GALLERY_DATA_DIR, { recursive: true });

// 交给服务端入口，由它负责监听、日志与优雅退出
require(path.join(PROJECT_ROOT, "server", "boot.cjs"));
