// 公开配置不得下发任何凭据
//
// 背景：/api/lumigather 是免鉴权接口，客户端首次连上就会调它拿站点配置。
//   2026-11-19 发现它曾把 autoUpdate.githubToken（明文 GitHub PAT）原样返回 ——
//   等于任何能访问该端口的人都能拿到仓库写权限。
// 根因：publicConfig 用 `...rest` 透传整个配置对象，只显式剥了 passwordHash/passwordSalt，
//   嵌套在 autoUpdate 里的凭据就跟着漏了。修复方式改为按键名递归剥离。
// 本测试守住这条线：以后新增配置项若含凭据，必须能被自动剥掉。

"use strict";

const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");

const configModule = require(path.join(__dirname, "..", "server", "lib", "config.js"));
const publicConfig = configModule.publicConfig;

test("publicConfig：剥离 autoUpdate 里的 githubToken，只保留布尔标志", () => {
  const cfg = {
    title: "测试站点",
    autoUpdate: {
      enabled: false,
      githubRepo: "owner/repo",
      githubToken: "ghp_SHOULD_NEVER_APPEAR_IN_RESPONSE",
      interval: 3600,
    },
  };

  const safe = publicConfig(cfg);
  const serialized = JSON.stringify(safe);

  assert.strictEqual(serialized.includes("SHOULD_NEVER_APPEAR"), false, "响应里不得出现 token 值");
  assert.strictEqual("githubToken" in (safe.autoUpdate || {}), false, "autoUpdate 不得含 githubToken 字段");
  assert.strictEqual(safe.autoUpdate.hasGithubToken, true, "应回布尔标志表示「已配置」，供前端显示状态");
  // 非敏感字段必须保留，否则前端功能会坏
  assert.strictEqual(safe.autoUpdate.githubRepo, "owner/repo");
  assert.strictEqual(safe.autoUpdate.interval, 3600);
  assert.strictEqual(safe.autoUpdate.enabled, false);
});

test("publicConfig：未配置 token 时 hasGithubToken 为 false", () => {
  const safe = publicConfig({ autoUpdate: { enabled: false, githubRepo: "a/b" } });
  assert.strictEqual(safe.autoUpdate.hasGithubToken, false);
});

test("publicConfig：passwordHash / passwordSalt 不得出现", () => {
  const safe = publicConfig({
    title: "x",
    passwordHash: "HASH_SHOULD_NOT_LEAK",
    passwordSalt: "SALT_SHOULD_NOT_LEAK",
  });
  const serialized = JSON.stringify(safe);
  assert.strictEqual(serialized.includes("HASH_SHOULD_NOT_LEAK"), false);
  assert.strictEqual(serialized.includes("SALT_SHOULD_NOT_LEAK"), false);
  assert.strictEqual("passwordHash" in safe, false);
  assert.strictEqual("passwordSalt" in safe, false);
});

test("publicConfig：任意深度的凭据键都被剥离（防以后新增配置项漏掉）", () => {
  const safe = publicConfig({
    title: "x",
    brand: { title: "品牌", apiKey: "KEY_SHOULD_NOT_LEAK" },
    nested: { deeper: { secret: "SECRET_SHOULD_NOT_LEAK" } },
    normalField: "保留",
  });
  const serialized = JSON.stringify(safe);
  assert.strictEqual(serialized.includes("KEY_SHOULD_NOT_LEAK"), false);
  assert.strictEqual(serialized.includes("SECRET_SHOULD_NOT_LEAK"), false);
  assert.strictEqual(safe.normalField, "保留", "非敏感字段必须原样保留");
  assert.strictEqual(safe.brand.title, "品牌");
});

test("publicConfig：正常业务字段完整保留", () => {
  const safe = publicConfig({
    title: "拾光集",
    categories: [{ name: "崩坏3", label: "崩坏3", color: "#c084fc" }],
    directories: [{ path: "/data/x", category: "崩坏3" }],
    imagesPerRow: 20,
  });
  assert.strictEqual(safe.title, "拾光集");
  assert.strictEqual(safe.categories.length, 1);
  assert.strictEqual(safe.categories[0].color, "#c084fc");
  assert.strictEqual(safe.directories[0].category, "崩坏3");
  assert.strictEqual(safe.imagesPerRow, 20);
});
