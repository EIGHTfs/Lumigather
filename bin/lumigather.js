#!/usr/bin/env node
// ============================================================
// npx / 全局安装入口
//
// 三件事：
//   1. 「是否被直接执行」判据必须走 realpath：
//      npx 与全局安装都会把 bin 变成指向 node_modules 的符号链接，用字面路径比较会判成
//      「不是直接执行」→ 进程静默 exit 0，用户只看到「跑完什么都没发生」。
//   2. 子命令 start / stop / restart / status 直接转调仓库自带的 start.sh，
//      不重写一套 PID/端口/日志管理；无子命令时保持原行为：前台启动（Ctrl+C 停）。
//   3. 被 import 时不启动服务，只导出纯函数（parseArgs / buildEnv / isDirectInvocation），便于单测。
// ============================================================
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");

// 包安装态的数据根默认值：放到用户目录，升级不会连带覆盖缓存与上传
const DEFAULT_DATA_DIRNAME = ".lumigather";
// 转调 start.sh 的子命令（与 start.sh 的用法保持一致）
const SUBCOMMANDS = ["start", "stop", "restart", "status"];

/**
 * 参数解析（纯函数，不读 process）。
 * @param {string[]} argv 不含 node 与脚本路径的参数表
 * @returns {{help:boolean, version:boolean, command:string|null, port:string|null, unknown:string[]}}
 */
function parseArgs(argv) {
  const out = { help: false, version: false, command: null, port: null, unknown: [] };
  const list = Array.isArray(argv) ? argv : [];
  for (let i = 0; i < list.length; i++) {
    const a = String(list[i]);
    if (a === "--help" || a === "-h") { out.help = true; continue; }
    if (a === "--version" || a === "-v") { out.version = true; continue; }
    if (a === "--port" && list[i + 1] !== undefined) { out.port = String(list[i + 1]); i++; continue; }
    if (a.startsWith("--port=")) { out.port = a.slice("--port=".length); continue; }
    if (!a.startsWith("-") && out.command === null && SUBCOMMANDS.includes(a)) { out.command = a; continue; }
    out.unknown.push(a);
  }
  return out;
}

/**
 * 计算要注入子进程/当前进程的环境变量（纯函数，不写 process.env）。
 * 数据根优先级与 server/lib/paths.js 一致：LUMIGATHER_DATA_DIR → GALLERY_DATA_DIR → DATA_DIR；
 * 都没设置时才用 <home>/.lumigather（包安装态的默认落点）。
 * @param {{args?:object, env?:object, home?:string}} opts
 * @returns {{LUMIGATHER_DATA_DIR:string, PORT?:string}}
 */
function buildEnv(opts) {
  const o = opts || {};
  const env = o.env || process.env;
  const home = o.home || os.homedir();
  const args = o.args || { port: null };
  const explicit = env.LUMIGATHER_DATA_DIR || env.GALLERY_DATA_DIR || env.DATA_DIR || "";
  const dataDir = String(explicit).trim()
    ? String(explicit).trim()
    : path.join(home, DEFAULT_DATA_DIRNAME);
  const patch = { LUMIGATHER_DATA_DIR: dataDir };
  if (args.port) patch.PORT = String(args.port);
  return patch;
}

/** realpath 兜底：路径不存在时退回 resolve，避免判据因异常路径直接抛错 */
function realpathOr(p) {
  try {
    return fs.realpathSync(String(p));
  } catch (_) {
    return path.resolve(String(p));
  }
}

/**
 * 是否「被直接执行」：比较 realpath，不比较字面路径。
 * @param {string|undefined} argv1 process.argv[1]
 * @param {string} self 本文件路径（__filename）
 * @returns {boolean}
 */
function isDirectInvocation(argv1, self) {
  if (!argv1) return false;                       // node -e / node 无脚本：一定不是直接执行本文件
  return realpathOr(argv1) === realpathOr(self);
}

/** 由本文件位置定位项目根（解析符号链接后取上一级） */
function projectRootFrom(self) {
  return path.resolve(path.dirname(realpathOr(self || __filename)), "..");
}

/** 读 package.json 的版本号；缺文件时返回 0.0.0，不因缺元数据让 --version 崩掉 */
function appVersion(root) {
  try {
    return String(require(path.join(root, "package.json")).version || "0.0.0");
  } catch (_) {
    return "0.0.0";
  }
}

/** bash 定位：环境变量覆盖 → /bin/bash → PATH 里的 bash */
function bashBin() {
  if (process.env.BASH) return process.env.BASH;
  if (fs.existsSync("/bin/bash")) return "/bin/bash";
  return "bash";
}

/** 帮助文本 */
function helpText(root, dataDir) {
  return [
    "用法: lumigather [子命令] [--port <端口>]",
    "",
    "子命令（转调仓库自带的 start.sh，后台运行）:",
    "  start     启动        lumigather start [--port N]",
    "  stop      停止        lumigather stop",
    "  restart   重启        lumigather restart [--port N]",
    "  status    查看状态    lumigather status",
    "",
    "不带子命令 = 前台直接启动（日志打在终端，Ctrl+C 停止）",
    "",
    "选项:",
    "  --port <端口>   监听端口（也可用环境变量 PORT，缺省由服务端决定）",
    "  --version       显示版本号",
    "  --help          显示本帮助",
    "",
    "项目根: " + root,
    "数据目录: " + dataDir,
    "          可用环境变量 LUMIGATHER_DATA_DIR 覆盖（旧名 GALLERY_DATA_DIR 仍兼容）",
  ].join("\n");
}

/**
 * 转调 start.sh：先注入数据根，保证后台进程与前台 CLI 用同一个数据根
 * （否则包安装态会退回到 <包目录>/cache-*，升级即丢）。
 * @returns {number} 子进程退出码
 */
function runStartSh(root, command, port, patch) {
  const script = path.join(root, "start.sh");
  if (!fs.existsSync(script)) {
    console.error("找不到 " + script + "：安装包不完整（start.sh 应随包分发）");
    return 2;
  }
  const args = [script, command];
  if (port) args.push("--port", String(port));
  const r = spawnSync(bashBin(), args, {
    stdio: "inherit",
    env: Object.assign({}, process.env, patch),
  });
  if (r.error) {
    console.error("调用 start.sh 失败：" + r.error.message);
    return 2;
  }
  return typeof r.status === "number" ? r.status : 1;
}

/**
 * 主流程：帮助 / 版本 / 子命令 / 前台启动。
 * @returns {number|null} 需要立即退出时返回退出码；前台启动（服务接管进程）返回 null
 */
function main(argv, ctx) {
  const o = ctx || {};
  const root = o.root || projectRootFrom(__filename);
  const args = parseArgs(argv);
  const patch = buildEnv({ args, env: o.env, home: o.home });

  if (args.help) {
    console.log(helpText(root, patch.LUMIGATHER_DATA_DIR));
    return 0;
  }
  if (args.version) {
    console.log(appVersion(root));
    return 0;
  }
  if (args.unknown.length) {
    console.error("未知参数：" + args.unknown.join(" ") + "（用 --help 看用法）");
    return 2;
  }
  if (args.command) {
    return runStartSh(root, args.command, args.port, patch);
  }

  // 无子命令：前台启动。先落环境变量，服务端与 start.sh 看到同一个数据根。
  Object.assign(process.env, patch);
  fs.mkdirSync(process.env.LUMIGATHER_DATA_DIR, { recursive: true });
  require(path.join(root, "server", "boot.cjs"));
  return null;
}

module.exports = {
  DEFAULT_DATA_DIRNAME,
  SUBCOMMANDS,
  parseArgs,
  buildEnv,
  isDirectInvocation,
  projectRootFrom,
  appVersion,
  helpText,
  runStartSh,
  main,
};

// 只有「直接执行」才启动；被 import（require/import）时只导出上面的纯函数。
if (isDirectInvocation(process.argv[1], __filename)) {
  const code = main(process.argv.slice(2));
  if (code !== null && code !== undefined) process.exit(code);
}
