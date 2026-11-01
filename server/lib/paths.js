// ============================================================
// 运行时数据根目录解析
//
// 三个运行期数据目录（cache-gifs / cache-index / uploads）此前各自硬编码在项目根。
// 以 npm 包方式安装时，升级会连带覆盖掉缓存与用户上传内容，因此必须能落到包目录之外。
//
// 取值优先级（高 → 低）：
//   1. GALLERY_DATA_DIR  部署方显式指定
//   2. DATA_DIR          通用约定
//   3. <项目根>          仓库态默认 —— 不设任何变量时行为与引入本模块前完全一致
//
// 只读环境变量：不创建目录、不校验存在性，由各使用处按需 mkdir。
// ============================================================
"use strict";

const path = require("path");

/** 代码所在的项目根（server/lib/ 上两级） */
const PROJECT_ROOT = path.resolve(__dirname, "..", "..");

/**
 * 运行时数据根目录
 * @returns {string} 绝对路径
 */
function dataRoot() {
  const env = process.env.GALLERY_DATA_DIR || process.env.DATA_DIR;
  if (env && String(env).trim()) return path.resolve(String(env).trim());
  return PROJECT_ROOT;
}

module.exports = { PROJECT_ROOT, dataRoot };
