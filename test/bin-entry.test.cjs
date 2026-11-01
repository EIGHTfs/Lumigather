// ============================================================
// bin 入口单测：参数解析、环境注入、realpath 判据、子命令转调
//
// 重点保护两条「装上了却像没装」的失败：
//   · import 即启动（别人 require 一下就起了服务）
//   · 符号链接判据用字面路径（npx / 全局安装下静默 exit 0）
// ============================================================
"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const BIN = path.join(__dirname, "..", "bin", "lumigather.js");
const pkg = require("../package.json");
const bin = require(BIN);

function nodeBin() {
  return process.execPath;
}

test("被 import 时不启动服务，只导出纯函数", () => {
  for (const name of ["parseArgs", "buildEnv", "isDirectInvocation", "main"]) {
    assert.strictEqual(typeof bin[name], "function", "应导出 " + name);
  }
  // 子进程里 require 一次：必须立刻结束并把控制权还给调用方
  const r = spawnSync(nodeBin(), ["-e", `require(${JSON.stringify(BIN)}); console.log("IMPORTED_ONLY");`], {
    encoding: "utf8",
    timeout: 15000,
  });
  assert.strictEqual(r.status, 0, r.stderr);
  assert.match(String(r.stdout), /IMPORTED_ONLY/);
});

test("parseArgs：子命令 / --port 两种写法 / help / version / 未知参数", () => {
  assert.deepStrictEqual(bin.parseArgs([]).command, null);
  assert.strictEqual(bin.parseArgs(["start"]).command, "start");
  assert.strictEqual(bin.parseArgs(["stop"]).command, "stop");
  assert.strictEqual(bin.parseArgs(["restart", "--port", "9000"]).port, "9000");
  assert.strictEqual(bin.parseArgs(["--port=9001", "status"]).port, "9001");
  assert.strictEqual(bin.parseArgs(["--help"]).help, true);
  assert.strictEqual(bin.parseArgs(["-v"]).version, true);
  assert.deepStrictEqual(bin.parseArgs(["--nope"]).unknown, ["--nope"]);
  // 子命令只认一个：第二个位置参数记为未知
  assert.deepStrictEqual(bin.parseArgs(["start", "extra"]).unknown, ["extra"]);
});

test("buildEnv：数据根默认落 home，显式设置优先，端口按需注入", () => {
  const base = bin.buildEnv({ args: bin.parseArgs([]), env: {}, home: "/home/tester" });
  assert.strictEqual(base.LUMIGATHER_DATA_DIR, path.join("/home/tester", ".lumigather"));
  assert.ok(!("PORT" in base), "没给 --port 时不应覆盖 PORT");

  const explicit = bin.buildEnv({
    args: bin.parseArgs(["--port", "8123"]),
    env: { LUMIGATHER_DATA_DIR: "/data/new" },
    home: "/home/tester",
  });
  assert.strictEqual(explicit.LUMIGATHER_DATA_DIR, "/data/new");
  assert.strictEqual(explicit.PORT, "8123");

  const legacy = bin.buildEnv({ args: bin.parseArgs([]), env: { GALLERY_DATA_DIR: "/data/old" }, home: "/h" });
  assert.strictEqual(legacy.LUMIGATHER_DATA_DIR, "/data/old", "旧名应兼容");

  const both = bin.buildEnv({
    args: bin.parseArgs([]),
    env: { LUMIGATHER_DATA_DIR: "/data/new", GALLERY_DATA_DIR: "/data/old" },
    home: "/h",
  });
  assert.strictEqual(both.LUMIGATHER_DATA_DIR, "/data/new", "两套都有时新名优先");
});

test("isDirectInvocation：同文件为真、他文件为假、符号链接解析后为真", () => {
  assert.strictEqual(bin.isDirectInvocation(BIN, BIN), true);
  assert.strictEqual(bin.isDirectInvocation(path.join(__dirname, "..", "server", "app.js"), BIN), false);
  assert.strictEqual(bin.isDirectInvocation(undefined, BIN), false, "node -e 场景不应判定为直接执行");

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "lumigather-binlink-"));
  try {
    const link = path.join(tmp, "lumigather");
    fs.symlinkSync(BIN, link);
    assert.strictEqual(bin.isDirectInvocation(link, BIN), true, "符号链接必须解析成同一真实路径");
    assert.strictEqual(bin.projectRootFrom(link), path.join(__dirname, ".."), "项目根也应从 realpath 推导");
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test("通过符号链接执行 --version 正常输出", () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "lumigather-binrun-"));
  try {
    const link = path.join(tmp, "lumigather");
    fs.symlinkSync(BIN, link);
    const r = spawnSync(nodeBin(), [link, "--version"], { encoding: "utf8", timeout: 15000 });
    assert.strictEqual(r.status, 0, r.stderr);
    assert.strictEqual(String(r.stdout).trim(), String(pkg.version));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test("--help 写清四个子命令与「无子命令=前台启动」", () => {
  const text = bin.helpText(path.join(__dirname, ".."), "/data/x");
  for (const word of ["start", "stop", "restart", "status", "前台直接启动", "LUMIGATHER_DATA_DIR"]) {
    assert.ok(text.includes(word), "帮助里应包含 " + word);
  }
});

test("未知参数以退出码 2 结束", () => {
  const r = spawnSync(nodeBin(), [BIN, "--nope"], { encoding: "utf8", timeout: 15000 });
  assert.strictEqual(r.status, 2);
  assert.match(String(r.stderr), /未知参数/);
});

test("子命令 status 转调 start.sh（输出含启停脚本的状态块）", () => {
  const r = spawnSync(nodeBin(), [BIN, "status"], {
    encoding: "utf8",
    timeout: 40000,
    env: Object.assign({}, process.env, { LUMIGATHER_DATA_DIR: "" }),
  });
  const out = String(r.stdout || "") + String(r.stderr || "");
  assert.match(out, /PID文件: /, "应打印 start.sh 的状态块：" + out);
});
