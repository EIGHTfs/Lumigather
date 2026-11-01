// ============================================================
// paths 单测：运行时数据根解析
//
// 重点：不设任何环境变量时必须等于项目根 —— 这保证仓库态部署行为零变化，
// 否则老部署的数据目录会突然改位置，缓存与上传内容都会"消失"。
// ============================================================
"use strict";

const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");

const MOD = path.join(__dirname, "..", "server", "lib", "paths.js");

/** 清掉模块缓存后重新加载，让它在当前环境变量下重新求值 */
function fresh() {
  delete require.cache[require.resolve(MOD)];
  return require(MOD);
}

/** 临时设置环境变量并在回调结束后还原 */
function withEnv(pairs, fn) {
  const saved = {};
  for (const k of Object.keys(pairs)) {
    saved[k] = process.env[k];
    if (pairs[k] === undefined) delete process.env[k];
    else process.env[k] = pairs[k];
  }
  try {
    fn();
  } finally {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete process.env[k];
      else process.env[k] = saved[k];
    }
  }
}

test("未设环境变量时数据根 = 项目根", () => {
  withEnv({ LUMIGATHER_DATA_DIR: undefined, GALLERY_DATA_DIR: undefined, DATA_DIR: undefined }, () => {
    const p = fresh();
    assert.strictEqual(p.dataRoot(), p.PROJECT_ROOT, "仓库态必须落在项目根");
  });
});

test("LUMIGATHER_DATA_DIR 优先于 DATA_DIR", () => {
  withEnv({ LUMIGATHER_DATA_DIR: "/tmp/probe-lumigather", DATA_DIR: "/tmp/probe-generic" }, () => {
    assert.strictEqual(fresh().dataRoot(), "/tmp/probe-lumigather");
  });
});

test("只设 DATA_DIR 时采用它", () => {
  withEnv({ LUMIGATHER_DATA_DIR: undefined, GALLERY_DATA_DIR: undefined, DATA_DIR: "/tmp/probe-generic" }, () => {
    assert.strictEqual(fresh().dataRoot(), "/tmp/probe-generic");
  });
});

test("空字符串与纯空白视为未设置", () => {
  withEnv({ LUMIGATHER_DATA_DIR: "", GALLERY_DATA_DIR: undefined, DATA_DIR: undefined }, () => {
    const p = fresh();
    assert.strictEqual(p.dataRoot(), p.PROJECT_ROOT, "空值不应把数据根变成当前工作目录");
  });
  withEnv({ LUMIGATHER_DATA_DIR: "   ", GALLERY_DATA_DIR: undefined, DATA_DIR: undefined }, () => {
    const p = fresh();
    assert.strictEqual(p.dataRoot(), p.PROJECT_ROOT, "空白同理");
  });
});

test("相对路径会被解析成绝对路径", () => {
  withEnv({ LUMIGATHER_DATA_DIR: "relative-probe", GALLERY_DATA_DIR: undefined, DATA_DIR: undefined }, () => {
    assert.ok(path.isAbsolute(fresh().dataRoot()), "dataRoot 必须返回绝对路径");
  });
});

test("旧名 GALLERY_DATA_DIR 仍兼容（新名优先）", () => {
  // 更名过渡期：老部署 .env 里还是旧前缀，必须继续生效；两套都写时以新名为准。
  withEnv({ LUMIGATHER_DATA_DIR: undefined, GALLERY_DATA_DIR: "/tmp/probe-legacy", DATA_DIR: undefined }, () => {
    assert.strictEqual(fresh().dataRoot(), "/tmp/probe-legacy", "只设旧名时仍应生效");
  });
  withEnv(
    { LUMIGATHER_DATA_DIR: "/tmp/probe-new", GALLERY_DATA_DIR: "/tmp/probe-legacy", DATA_DIR: undefined },
    () => {
      assert.strictEqual(fresh().dataRoot(), "/tmp/probe-new", "两套名字同时存在时新名优先");
    },
  );
});
