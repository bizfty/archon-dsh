#!/usr/bin/env bash
# ============================================================
# sync-back.sh — develop → archon-dsh 回拷（先预览，确认后备份再执行）
# 用法: ./scripts/sync-back.sh
# 前置: develop 内验证已全绿（编译/单测/冒烟）
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

echo "== 1/4 预览（dry-run，不实际变更）=="
rsync -avn --delete "${EXCLUDES[@]}" "$ROOT/develop/" "$ROOT/" || true
echo

echo "== 2/4 检查生产独有文件（会被 --delete 删除）=="
diff -rq --exclude=.git --exclude=target --exclude=data \
  --exclude=develop --exclude=backup --exclude=.idea \
  --exclude=node_modules --exclude=.dsh-identity \
  --exclude=external --exclude='*.log' \
  "$ROOT/develop/" "$ROOT/" \
  | grep '^Only in '"$ROOT" \
  | grep -v "$ROOT/develop" \
  && echo "⚠️ 以上为生产独有文件，请确认是否应保留" \
  || echo "无生产独有文件，安全"
echo

read -r -p "== 3/4 确认回拷？(yes/no): " ans
[[ "$ans" == "yes" ]] || { echo "已取消"; exit 1; }

TS=$(date +%Y%m%d-%H%M%S)
echo "== 备份到 backup/pre-sync-$TS =="
rsync -a --delete "${EXCLUDES[@]}" "$ROOT/" "$ROOT/backup/pre-sync-$TS/"

echo "== 4/4 执行回拷 develop → archon-dsh =="
rsync -av --delete "${EXCLUDES[@]}" "$ROOT/develop/" "$ROOT/"
echo
echo "✅ 已回拷。请在 archon-dsh 下执行: git diff --stat 核对，确认后 git commit"
