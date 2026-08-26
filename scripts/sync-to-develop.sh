#!/usr/bin/env bash
# ============================================================
# sync-to-develop.sh — archon-dsh → develop 同步（建/刷新副本）
# 用法: ./scripts/sync-to-develop.sh
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

echo "== 同步 archon-dsh → develop =="
rsync -a --delete "${EXCLUDES[@]}" "$ROOT/" "$ROOT/develop/"
echo "✅ develop 已同步为 archon-dsh 的副本"
