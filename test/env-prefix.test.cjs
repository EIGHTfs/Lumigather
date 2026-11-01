// ============================================================
// env 单测：环境变量读口的兼容约定
//
// 覆盖：
//   · 新名 LUMIGATHER_* 优先
//   · 旧名 GALLERY_* 回退（更名过渡期老部署仍要生效）
//   · 整数解析：缺失 / 空串 / 非数字 → undefined，交给调用方兜底
// ============================================================
"use strict";

const test = require("node:test");
const assert = require("node:assert");

const { env, envInt } = require("../server/lib/env.js");

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

test("新名优先于旧名", () => {
  withEnv({ LUMIGATHER_PROBE_X: "new", GALLERY_PROBE_X: "old" }, () => {
    assert.strictEqual(env("PROBE_X"), "new");
  });
});

test("只设旧名时回退到旧名", () => {
  withEnv({ LUMIGATHER_PROBE_X: undefined, GALLERY_PROBE_X: "old" }, () => {
    assert.strictEqual(env("PROBE_X"), "old");
  });
});

test("两套名字都没有时返回 undefined", () => {
  withEnv({ LUMIGATHER_PROBE_X: undefined, GALLERY_PROBE_X: undefined }, () => {
    assert.strictEqual(env("PROBE_X"), undefined);
  });
});

test("空串不被当作未设置（可显式覆盖为空）", () => {
  withEnv({ LUMIGATHER_PROBE_X: "", GALLERY_PROBE_X: "old" }, () => {
    assert.strictEqual(env("PROBE_X"), "", "新名为空串时按已设置处理，不再回退旧名");
  });
});

test("envInt：正常整数 / 旧名 / 非法值", () => {
  withEnv({ LUMIGATHER_PROBE_N: "32", GALLERY_PROBE_N: "64" }, () => {
    assert.strictEqual(envInt("PROBE_N"), 32, "新名优先");
  });
  withEnv({ LUMIGATHER_PROBE_N: undefined, GALLERY_PROBE_N: "64" }, () => {
    assert.strictEqual(envInt("PROBE_N"), 64, "回退旧名");
  });
  withEnv({ LUMIGATHER_PROBE_N: "abc", GALLERY_PROBE_N: undefined }, () => {
    assert.strictEqual(envInt("PROBE_N"), undefined, "非数字视为未设置");
  });
  withEnv({ LUMIGATHER_PROBE_N: "  ", GALLERY_PROBE_N: undefined }, () => {
    assert.strictEqual(envInt("PROBE_N"), undefined, "空白视为未设置");
  });
});
