#!/usr/bin/env bash
# ============================================================
# sync-to-develop.sh — archon-dsh → develop 同步（建/刷新副本）
# 用法: ./scripts/sync-to-develop.sh
# 说明: 同步后自动补装 dsh-web 前端依赖（node_modules 被排除，
#       副本需 npm install 才能构建 dsh-web）
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

EXCLUDES=(
  --exclude '.git'
  --exclude 'target'
  --exclude 'data'
  --exclude 'backup'
  --exclude 'develop'
  --exclude '.idea'
  --exclude '.dsh-identity'
  --exclude '*.log'
  --exclude 'node_modules'
  --exclude 'dsh-web/src/main/resources/static'
  --exclude '.history'
  --exclude 'external'
)

echo "== 1/2 同步 archon-dsh → develop =="
rsync -a --delete "${EXCLUDES[@]}" "$ROOT/" "$ROOT/develop/"

echo "== 2/2 补装 dsh-web 前端依赖 =="
if [ -f "$ROOT/develop/dsh-web/src/main/webapp/package.json" ]; then
  (cd "$ROOT/develop/dsh-web/src/main/webapp" && npm install --no-audit --no-fund)
else
  echo "⚠️ 未找到 dsh-web/package.json，跳过 npm install"
fi

echo "✅ develop 已同步为 archon-dsh 的副本（含前端依赖）"
