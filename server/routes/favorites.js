// ============================================================
// 拾光集 - 收藏路由
// 收藏功能：预设不可删改的收藏分类，收藏的图片在其中平铺。
//   权限上读开放、写需登录（游客可看，登录用户可点收藏）。
//   GET    /api/favorites  列表（游客可看）
//   POST   /api/favorites  添加（需登录）
//   DELETE /api/favorites  移除（需登录）
// ============================================================
"use strict";

const fs = require("fs");
const path = require("path");

const { jsonRes, readJsonBody } = require("../lib/util");

const FAV_FILE = path.join(__dirname, "..", "favorites.json");

function loadFavorites() {
  try {
    const raw = JSON.parse(fs.readFileSync(FAV_FILE, "utf8"));
    return Array.isArray(raw) ? raw : [];
  } catch (_) {
    return [];
  }
}

function saveFavorites(arr) {
  fs.writeFileSync(FAV_FILE, JSON.stringify(arr, null, 2), "utf8");
}

// GET /api/favorites
function apiGetFavorites(req, res) {
  jsonRes(res, 200, loadFavorites());
}

// 媒体地址规范化：剥掉 scheme / host / port，只保留 pathname + search。
//
// 为什么必须在写入侧做：收藏的查重与删除都按 src 字符串**精确匹配**。
// 一旦同一文件存在两种写法（`http://host:8081/api/media?file=...` 与 `/api/media?file=...`），
// 就会被判成两张图 —— 表现为「明明收藏过却显示未收藏」，再点一次又多一条。
//
// 放在服务端的理由：客户端各写一份必然漂移（本项目曾经就是客户端在做归一化）。
// 服务端在这里统一一次，所有客户端都不用管。
// 不依赖 req.headers.host（反代下不可靠），直接用正则剥前缀。
function normalizeMediaSrc(src) {
  if (typeof src !== "string") return "";
  const trimmed = src.trim();
  if (!trimmed) return "";
  const stripped = trimmed.replace(/^[a-zA-Z][\w+.-]*:\/\/[^/]+/, "");
  return stripped.startsWith("/") ? stripped : "/" + stripped;
}

// POST /api/favorites — 添加收藏（需登录）
async function apiPostFavorite(req, res, ctx) {
  if (!ctx.requireAuth(req, res)) return;
  const body = await readJsonBody(req);
  const { name, title, cat, dir, size, root } = body;
  const src = normalizeMediaSrc(body.src);
  if (!src) return jsonRes(res, 400, { error: "src required" });
  const favs = loadFavorites();
  if (favs.find((f) => f.src === src)) {
    return jsonRes(res, 200, { ok: true, alreadyExists: true, favorites: favs });
  }
  favs.push({ src, name, title, cat, dir, size, root, addedAt: Date.now() });
  saveFavorites(favs);
  jsonRes(res, 200, { ok: true, favorites: favs });
}

// DELETE /api/favorites — 移除收藏（需登录）
async function apiDeleteFavorite(req, res, ctx) {
  if (!ctx.requireAuth(req, res)) return;
  const body = await readJsonBody(req);
  // 删除同样要规范化：否则客户端用绝对地址来删就会「删不掉」（存的是相对形态）。
  const src = normalizeMediaSrc(body?.src);
  if (!src) return jsonRes(res, 400, { error: "src required" });
  let favs = loadFavorites();
  favs = favs.filter((f) => f.src !== src);
  saveFavorites(favs);
  jsonRes(res, 200, { ok: true, favorites: favs });
}

module.exports = { apiGetFavorites, apiPostFavorite, apiDeleteFavorite, loadFavorites, saveFavorites };
