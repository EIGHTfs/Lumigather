#!/usr/bin/env node
// ============================================================
// 发布前静态核对（prepublishOnly 调用）
//
// 为什么这一步不跑 `npm pack`：
//   在 npm publish 的 prepublishOnly 子进程里再调 npm pack，产物位置不受
//   --pack-destination 控制（实测会落回项目根甚至不产出），守卫会误报
//   「打包失败」——一个时灵时不灵的门禁比没有门禁更糟。
//   所以这里只做**静态检查**：直接按 package.json 的 files 推算「哪些文件会进包」，
//   命中危险模式就中止发布。
//
// 真实产物的核对由 scripts/verify-pack.sh 单独执行（发布前手动跑一次），
// 这一步不替代它：静态检查防的是「白名单写错」，实物核对防的是「npm 行为意外」。
//
// 本文件不在 files 白名单里，不会进包。
// ============================================================
"use strict";

const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, "package.json"), "utf8"));
const files = Array.isArray(pkg.files) ? pkg.files : [];

// 危险模式：环境文件、凭据、密钥、运行期配置、测试与文档
const DANGER = [
  { re: /(^|\/)\.env($|\.)/, why: "环境/部署配置，可能含凭据" },
  { re: /(^|\/)config\.json$/, why: "运行期站点配置（含密码哈希）" },
  { re: /credential|secret|password|token/i, why: "疑似凭据" },
  { re: /\.(pem|key|p12|pfx)$/i, why: "密钥文件" },
  { re: /(^|\/)(test|tests|__tests__|fixtures)\//, why: "测试与夹具" },
  { re: /(^|\/)(docs|examples|skills)\//, why: "文档与示例" },
  { re: /(^|\/)cache-[^/]*\//, why: "运行时缓存" },
  { re: /(^|\/)uploads\//, why: "用户上传内容" },
  { re: /\.map$/, why: "sourcemap（体积大且暴露构建机路径）" },
  { re: /\.(tgz|pid|bak)$/, why: "中间产物" },
  { re: /(^|\/)\.trash\//, why: "回收站" },
];

const problems = [];

// ① files 字段本身的写法问题
for (const entry of files) {
  const e = String(entry);
  if (e === "." || e === "./" || e === "*") {
    problems.push(`files 里出现整目录放行「${e}」：会连运行期文件一起带进包，必须逐条写`);
    continue;
  }
  for (const d of DANGER) {
    if (d.re.test(e)) problems.push(`files 里直接写了危险条目「${e}」（${d.why}）`);
  }
}

// ② 按 files 推算实际会进包的文件，逐个查危险模式
function walk(abs, rel, out) {
  let st;
  try { st = fs.statSync(abs); } catch (_) { return; }
  if (st.isFile()) { out.push(rel); return; }
  if (!st.isDirectory()) return;
  for (const name of fs.readdirSync(abs)) {
    // npm 默认排除项：.git / node_modules
    if (name === ".git" || name === "node_modules") continue;
    walk(path.join(abs, name), rel ? rel + "/" + name : name, out);
  }
}

const included = [];
for (const entry of files) {
  const e = String(entry).replace(/\/+$/, "");
  if (e === "." || e === "./") continue;
  const abs = path.join(ROOT, e);
  walk(abs, e, included);
}

for (const rel of included) {
  for (const d of DANGER) {
    if (d.re.test(rel)) {
      problems.push(`会进包的文件「${rel}」（${d.why}）—— 请把该文件移出 files 指向的目录，或从 files 里去掉来源`);
      break;
    }
  }
}

// ③ 顶层必填字段
for (const k of ["name", "version", "bin", "engines", "license"]) {
  if (!pkg[k] || (typeof pkg[k] === "object" && Object.keys(pkg[k]).length === 0)) {
    problems.push(`package.json 缺 ${k} 字段`);
  }
}
if (pkg.private === true) problems.push("package.json 标了 private: true，发布会被拒");

if (problems.length) {
  console.error("✗ 发布前静态核对未通过：");
  for (const p of Array.from(new Set(problems))) console.error("  · " + p);
  process.exit(1);
}

console.log(`✓ 发布前静态核对通过：files ${files.length} 条，推算将进包 ${included.length} 个文件，无危险项`);
