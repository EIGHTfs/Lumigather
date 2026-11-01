// ============================================================
// 拾光集 - 画廊配置路由
//   GET  /api/gallery  读取配置（不含密码哈希；浏览开放）
//   POST /api/gallery  保存配置（需登录）
// ============================================================
"use strict";

const path = require("path");
const { jsonRes, readJsonBody } = require("../lib/util");
const { loadConfig, saveConfig, publicConfig } = require("../lib/config");
// 品牌默认值（环境变量 → 项目根 .env → 清单 brand 段 → 内置默认，见 ../config/brand）：
// 设置页里的标题/图标为空时，前端拿它兜底——所以品牌永远盖不掉用户在 UI 里设的值。
const { readBrand } = require("../config/brand");
// 2026-10-12：IO 监测（读/写速率 + 按 label/调用点归因），见 lib/io-monitor.js
const ioMonitor = require("../lib/io-monitor");

// 每次请求重读品牌：.env 与清单都很小，读一次成本可接受；改配置后重启即生效，不吃旧缓存
function brandOf() {
  return readBrand(path.join(__dirname, ".."));
}

// GET /api/gallery
function apiGetGallery(req, res, ctx) {
  jsonRes(res, 200, { ...publicConfig(loadConfig()), brand: brandOf() });
}

// GET /api/index-cache —— 索引缓存统计（免鉴权：只含计数与上限，不含任何路径或条目内容）
// 用途：让调用方（含自动化巡检）能直接读出「缓存是否真在生效」，不必用耗时间接推断。
// 契约：ok / enabled / hit / miss / hitRate / stale / evict / invalidate / entries / bytes / limitBytes
// enabled=false 表示已用 GALLERY_INDEX_CACHE=0 关闭，此时 hit/miss 不增长属正常现象。
// 为什么放在 gallery 专属路由：index-cache 这个 lib 只有 gallery 系才有（templates/_gallery/server/lib/），
// 挂在共用级 routes-auth.js 会让 gbmd/iwara 也拿到一条指向不存在模块的公开端点（必然 500）。
function apiIndexCache(req, res, ctx) {
  const indexCache = require("../lib/index-cache");
  jsonRes(res, 200, Object.assign({ ok: true }, indexCache.stats()));
}

// GET /api/io —— 2026-10-12：扫描/IO 归因（byLabel + bySite，站点为 file:line）
function apiIo(req, res, ctx) {
  if (!ctx.requireAuth(req, res)) return;   // 含本机绝对路径与调用点，不对外开放
  jsonRes(res, 200, ioMonitor.snapshot());
}

// POST /api/gallery — 保存画廊配置（需登录）
async function apiPostGallery(req, res, ctx) {
  if (!ctx.requireAuth(req, res)) return;
  const body = await readJsonBody(req);
  const cfg = loadConfig();
  const { title, favicon, categories, directories, excludeDirs, fsRoot } = body;
  if (title !== undefined) cfg.title = title;
  if (favicon !== undefined) cfg.favicon = favicon;
  if (Array.isArray(categories)) cfg.categories = categories;
  if (Array.isArray(directories)) cfg.directories = directories;
  if (Array.isArray(excludeDirs)) cfg.excludeDirs = excludeDirs;
  if (typeof fsRoot === "string") cfg.fsRoot = fsRoot;
  saveConfig(cfg);
  jsonRes(res, 200, { ok: true, ...publicConfig(cfg), brand: brandOf() });
}

module.exports = { apiGetGallery, apiPostGallery, apiIo, apiIndexCache };
