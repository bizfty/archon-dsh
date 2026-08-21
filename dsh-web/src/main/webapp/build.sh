#!/usr/bin/env bash
# dsh-web 前端构建（由 dsh-web/pom.xml 的 exec-maven-plugin 在 generate-resources 阶段调用；
# 也可 `npm run build` / 手动执行）：
# 1) npm run build → vite build（Vue3 SFC + Element Plus）
# 2) 产物输出到 dsh-web/src/main/resources/static（进 jar，dsh-boot 依赖服务）
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"          # dsh-web/src/main/webapp

echo "[web] vite build（Vue3 + Element Plus）"
(cd "$DIR" && npm run build)

echo "[web] 完成 → $DIR/../resources/static"
