#!/usr/bin/env bash
# ============================================================
# 发布前实物清单核对（prepublishOnly 调用）
#
# 为什么要有这一步：package.json 的 files 是白名单，但「整目录放行」（如 server/）
# 会把目录里的运行期文件一并带进包 —— 例如 server/config.json（可能含密码哈希）、
# 项目根 .env（部署差异，未来可能含凭据）。只看 files 字段是看不出来的，
# 必须对**真实打出来的 tgz** 做一次清单核对。
#
# 判定：包内出现敏感文件即失败退出（npm publish 随之中止）。
# 本脚本自身不在 files 白名单里，不会进包。
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."

NPM_BIN="${NPM_BIN:-npm}"
TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

# 打真实包（不带 --dry-run，因为要拿到 tgz 才能核对内容）
TGZ_NAME="$("$NPM_BIN" pack --silent --pack-destination "$TMP_DIR")"
TGZ="$TMP_DIR/$TGZ_NAME"

if [ ! -f "$TGZ" ]; then
  echo "✗ 打包失败：$TGZ 不存在" >&2
  exit 1
fi

echo "── 发布前实物清单核对：$TGZ_NAME ──"

# 敏感文件判据：环境文件、凭据类、密钥、运行期配置
PATTERN='(^|/)\.env($|\.)|credential|secret|password|token|\.pem$|\.key$|(^|/)config\.json$'

if tar -tzf "$TGZ" | grep -Eq "$PATTERN"; then
  echo "✗ 包里出现不该分发的文件：" >&2
  tar -tzf "$TGZ" | grep -E "$PATTERN" | sed 's/^/    /' >&2
  echo "  处理：从 package.json 的 files 里去掉来源目录，或把该文件移到数据目录。" >&2
  exit 1
fi

COUNT="$(tar -tzf "$TGZ" | wc -l | tr -d ' ')"
SIZE="$(du -h "$TGZ" | cut -f1)"
echo "✓ 清单核对通过：$COUNT 个条目，$SIZE"
