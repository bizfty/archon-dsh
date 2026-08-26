#!/usr/bin/env bash
# ============================================================
# sync-back.sh — develop → archon-dsh 回拷（预览，确认后备份再执行）
# 用法: ./scripts/sync-back.sh
#   AUTO_YES=1 ./scripts/sync-back.sh   # 跳过交互确认（CI/自动化）
# 前置: develop 内验证已全绿（编译/单测/冒烟）
# 流程: 预览 → 生产独有文件检查 → 确认 → 备份 → 回拷 → 一致性校验
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AUTO_YES="${AUTO_YES:-0}"

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

echo "== 1/6 预览（dry-run，不实际变更）=="
rsync -avn --delete "${EXCLUDES[@]}" "$ROOT/develop/" "$ROOT/" || true
echo

echo "== 2/6 检查生产独有文件（会被 --delete 删除）=="
PROD_ONLY="$(diff -rq --exclude=.git --exclude=target --exclude=data \
  --exclude=develop --exclude=backup --exclude=.idea \
  --exclude=node_modules --exclude=.dsh-identity \
  --exclude=external --exclude='*.log' \
  --exclude=static \
  "$ROOT/develop/" "$ROOT/" \
  | grep '^Only in '"$ROOT" \
  | grep -v "$ROOT/develop" || true)"
if [ -n "$PROD_ONLY" ]; then
  echo "$PROD_ONLY"
  echo "⚠️ 以上为生产独有文件，将被 --delete 删除，请确认是否应保留"
else
  echo "无生产独有文件，安全"
fi
echo

if [ "$AUTO_YES" = "1" ]; then
  echo "== 3/6 AUTO_YES=1，跳过确认 =="
else
  read -r -p "== 3/6 确认回拷？(yes/no): " ans
  [[ "$ans" == "yes" ]] || { echo "已取消"; exit 1; }
fi

TS=$(date +%Y%m%d-%H%M%S)
echo "== 4/6 备份到 backup/pre-sync-$TS =="
rsync -a --delete "${EXCLUDES[@]}" "$ROOT/" "$ROOT/backup/pre-sync-$TS/"

echo "== 5/6 执行回拷 develop → archon-dsh =="
rsync -av --delete "${EXCLUDES[@]}" "$ROOT/develop/" "$ROOT/"
echo

echo "== 6/6 回拷后一致性校验 =="
if diff -rq --exclude=.git --exclude=target --exclude=data \
  --exclude=develop --exclude=backup --exclude=.idea \
  --exclude=node_modules --exclude=.dsh-identity \
  --exclude=external --exclude='*.log' \
  --exclude=static \
  "$ROOT/develop/" "$ROOT/" > /dev/null; then
  echo "✅ develop 与 archon-dsh 完全一致（排除清单内）"
else
  echo "⚠️ 仍存在差异，请人工核对："
  diff -rq --exclude=.git --exclude=target --exclude=data \
    --exclude=develop --exclude=backup --exclude=.idea \
    --exclude=node_modules --exclude=.dsh-identity \
    --exclude=external --exclude='*.log' \
    --exclude=static \
    "$ROOT/develop/" "$ROOT/" | head -30
  exit 1
fi

echo
echo "✅ 回拷完成。archon-dsh 变更摘要："
git -C "$ROOT" diff --stat
echo
echo "请核对后执行: git add -A && git commit"
