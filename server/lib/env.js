// ============================================================
// 环境变量读取：新名 LUMIGATHER_* 优先，旧名 GALLERY_* 兼容回退
//
// 项目更名后两套名字并存一段时间：
//   · 新部署写 .env / 系统环境用 LUMIGATHER_* 即可；
//   · 老部署的 .env、外部脚本、监控仍用 GALLERY_*，必须继续生效（否则升级即静默失效）。
// 统一在这里做「新优先、旧回退」，调用点不再各写一遍 || 表达式。
//
// .env 装载也放在本模块（它只管环境变量，不依赖配置内容）：
//   每行 KEY=VALUE，# 开头为注释；不覆盖已存在的真实环境变量（真实 env 优先级最高）。
//   任何一次读取都会先确保装载完成，因此「进程启动就 require 的模块」也能读到 .env，
//   不会出现「先读的人看不到 .env、跑过一轮请求后才生效」的顺序问题。
// ============================================================
"use strict";

const fs = require("fs");
const path = require("path");

const NEW_PREFIX = "LUMIGATHER_";
const OLD_PREFIX = "GALLERY_";

// 本文件位于 <项目根>/server/lib/env.js
const DOTENV = path.join(path.resolve(__dirname, "..", ".."), ".env");

/**
 * 装载项目根 .env（幂等，只读一次）。
 * 键名两套都认：LUMIGATHER_*（新）与 GALLERY_*（旧）；加载后由 env() 决定优先顺序。
 */
function loadDotEnvOnce() {
  if (loadDotEnvOnce.__done) return;
  loadDotEnvOnce.__done = true;
  try {
    if (!fs.existsSync(DOTENV)) return;
    for (const raw of fs.readFileSync(DOTENV, "utf8").split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || line.startsWith("#")) continue;
      const i = line.indexOf("=");
      if (i <= 0) continue;
      const k = line.slice(0, i).trim();
      let v = line.slice(i + 1).trim();
      if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
      if (!(k in process.env)) process.env[k] = v;
    }
  } catch (_) {
    /* .env 缺失或不可读：忽略，走默认值 */
  }
}

/**
 * 读一个环境变量：先看新名，再看旧名。
 * @param {string} name 不带前缀的名字（如 "INDEX_CACHE_MB"）
 * @returns {string|undefined} 两套名字都没有时返回 undefined
 */
function env(name) {
  loadDotEnvOnce();
  const v = process.env[NEW_PREFIX + name];
  if (v !== undefined) return v;
  return process.env[OLD_PREFIX + name];
}

/**
 * 读整数环境变量：缺失、空串、非数字一律返回 undefined，交给调用方兜底默认值。
 * @param {string} name 不带前缀的名字
 * @returns {number|undefined}
 */
function envInt(name) {
  const raw = env(name);
  if (raw === undefined || String(raw).trim() === "") return undefined;
  const n = parseInt(raw, 10);
  return Number.isFinite(n) ? n : undefined;
}

module.exports = { env, envInt, loadDotEnvOnce, NEW_PREFIX, OLD_PREFIX, DOTENV };
