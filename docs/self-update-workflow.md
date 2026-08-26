# Archon-DSH 自身开发与更新工作流

> 目标：用 Archon-DSH 自己开发 Archon-DSH 自己（dogfooding）。
> 原则：**生产运行副本（archon-dsh）与开发副本（develop）严格隔离；改动只在 develop 验证通过后才同步回生产；备份可随时回滚。**

---

## 1. 角色与目录

| 目录 | 角色 | 说明 |
|------|------|------|
| `/home/john/workspace/archon-dsh` | **生产运行副本** | 当前正在运行的版本，git 仓库（main），保持干净基线 |
| `/home/john/workspace/archon-dsh/develop` | **开发调试副本** | archon-dsh 的完整副本，日常开发修改都在这里，**已被 .gitignore 排除** |
| `/home/john/workspace/archon-dsh/backup` | **快照备份** | 每次同步前的安全快照，**已被 .gitignore 排除** |

**铁律：**
- 只允许 `archon-dsh → develop` 和 `develop → archon-dsh` 两个方向同步，**绝不允许从 develop 再复制 develop**（防无限嵌套）
- `develop/` 与 `backup/` 永不进入 git（已在 `.gitignore`）
- 所有同步使用 `rsync`，不用 `cp -r`（后者无法处理"删除"）

---

## 2. 排除清单（所有 rsync 共用）

```bash
EXCLUDES=(
  --exclude '.git'                 # 开发副本不需要 git 元数据
  --exclude 'target'               # 构建产物
  --exclude 'data'                 # 运行时数据（各自独立）
  --exclude 'backup'               # 防嵌套
  --exclude 'develop'              # 防嵌套
  --exclude '.idea'                # IDE 配置
  --exclude '.dsh-identity'        # 身份/密钥
  --exclude '*.log'
  --exclude 'node_modules'
  --exclude 'dsh-web/src/main/resources/static'   # 前端构建产物
  --exclude '.history'
  --exclude 'external'
)
```

> ⚠️ `.gitignore` 最后一行 `develop/` 缺结尾换行，先修复：
> ```bash
> printf '\n' >> /home/john/workspace/archon-dsh/.gitignore
> ```

---

## 3. 一次性初始化：建 develop 副本

```bash
cd /home/john/workspace/archon-dsh

# 先确认 archon-dsh 处于干净/已知状态（git 基线）
git status --short

# 建副本（推荐直接用脚本，内含排除清单 + npm install）
./scripts/sync-to-develop.sh

# 手动建副本（等效，首次可加 -v 观察）
rsync -a --delete "${EXCLUDES[@]}" \
  /home/john/workspace/archon-dsh/ \
  /home/john/workspace/archon-dsh/develop/

# ⚠️ node_modules 被排除清单排除，副本必须补装前端依赖才能构建 dsh-web：
cd /home/john/workspace/archon-dsh/develop/dsh-web/src/main/webapp && npm install

# 校验：两边源码应一致（static 构建产物差异属预期）
diff -rq --exclude=.git --exclude=target --exclude=data \
  --exclude=develop --exclude=backup --exclude=.idea \
  --exclude=node_modules --exclude=static \
  /home/john/workspace/archon-dsh/ \
  /home/john/workspace/archon-dsh/develop/ | head -20
```

> 注意 rsync 源/目标**末尾的斜杠**：`src/` 表示复制目录内容；`dst/` 表示放进目录。

---

## 4. 日常开发循环（每个迭代）

```
[1] 备份基线   → backup/ 打快照（可选，git 已覆盖则跳过）
[2] 改 develop  → 编码、调试，全在 develop/ 内进行
[3] 验证通过   → 编译 → 单测 → 冒烟（见第 6 节）
[4] 回拷预览   → rsync -n dry-run 列出将变更的文件
[5] 执行回拷   → rsync --delete 同步回 archon-dsh
[6] 差异核对   → git diff --stat 审查改动
[7] 提交归档   → git commit（生产副本归档）
[8] 重启生效   → 重新构建并重启 archon-dsh
```

### 4.1 步骤 [1] 备份（可选但推荐）

```bash
TS=$(date +%Y%m%d-%H%M%S)
rsync -a --delete "${EXCLUDES[@]}" \
  /home/john/workspace/archon-dsh/ \
  /home/john/workspace/archon-dsh/backup/pre-sync-$TS/
```

### 4.2 步骤 [4] 回拷预览（必须做）

```bash
cd /home/john/workspace/archon-dsh

# dry-run：只显示将要变化的文件，不实际执行
rsync -avn --delete "${EXCLUDES[@]}" \
  /home/john/workspace/archon-dsh/develop/ \
  /home/john/workspace/archon-dsh/

# 检查"仅 archon-dsh 独有、会被 --delete 删掉"的文件
diff -rq --exclude=.git --exclude=target --exclude=data \
  --exclude=develop --exclude=backup --exclude=.idea \
  --exclude=node_modules --exclude=.dsh-identity \
  /home/john/workspace/archon-dsh/develop/ \
  /home/john/workspace/archon-dsh/ \
  | grep '^Only in /home/john/workspace/archon-dsh' \
  | grep -v 'archon-dsh/develop' || echo "无生产独有文件，安全"
```

> 若出现"生产独有文件"，逐一确认是否应保留；不应保留则让其被删除，应保留则先手动挪到 develop 或排除项。

### 4.3 步骤 [5] 执行回拷

```bash
rsync -av --delete "${EXCLUDES[@]}" \
  /home/john/workspace/archon-dsh/develop/ \
  /home/john/workspace/archon-dsh/
```

### 4.4 步骤 [6][7] 核对与提交

```bash
cd /home/john/workspace/archon-dsh
git diff --stat        # 审查改动范围
git diff               # 逐项审查（重点看配置文件、删除的文件）
git add -A && git commit -m "feat: 描述本次改动（来自 develop 同步）"
```

### 4.5 步骤 [8] 重启生效

- 若以 `java -jar target/xxx.jar` 运行：回拷后需重新 `mvn package` 并重启进程，**源码同步本身不会热生效**
- 若以 IDE 运行：重启应用即可
- 若为多模块：确认回拷后模块间引用完整（develop 全量同步，一般不会缺）

---

## 5. 建议固化为脚本

创建 `scripts/` 目录（archon-dsh 内），放两个脚本，避免手敲出错。

### 5.1 `scripts/sync-to-develop.sh`（archon-dsh → develop，含前端依赖补装）

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXCLUDES=(--exclude '.git' --exclude 'target' --exclude 'data'
  --exclude 'backup' --exclude 'develop' --exclude '.idea'
  --exclude '.dsh-identity' --exclude '*.log' --exclude 'node_modules'
  --exclude 'dsh-web/src/main/resources/static' --exclude '.history'
  --exclude 'external')
rsync -a --delete "${EXCLUDES[@]}" "$ROOT/" "$ROOT/develop/"
echo "✅ develop 已同步为 archon-dsh 的副本"
```

### 5.2 `scripts/sync-back.sh`（develop → archon-dsh，先预览后执行）

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXCLUDES=(--exclude '.git' --exclude 'target' --exclude 'data'
  --exclude 'backup' --exclude 'develop' --exclude '.idea'
  --exclude '.dsh-identity' --exclude '*.log' --exclude 'node_modules'
  --exclude 'dsh-web/src/main/resources/static' --exclude '.history'
  --exclude 'external')

echo "== 预览（dry-run）=="
rsync -avn --delete "${EXCLUDES[@]}" "$ROOT/develop/" "$ROOT/"

read -r -p "确认回拷？(yes/no): " ans
[[ "$ans" == "yes" ]] || { echo "已取消"; exit 1; }

TS=$(date +%Y%m%d-%H%M%S)
rsync -a --delete "${EXCLUDES[@]}" "$ROOT/" "$ROOT/backup/pre-sync-$TS/"
rsync -av --delete "${EXCLUDES[@]}" "$ROOT/develop/" "$ROOT/"
echo "✅ 已回拷，请在 archon-dsh 下 git diff 核对并 commit"
```

```bash
chmod +x scripts/sync-to-develop.sh scripts/sync-back.sh
```

---

## 6. 验证门槛（回拷前必须全绿）

```bash
cd /home/john/workspace/archon-dsh/develop

# ① 全量编译
mvn -q -DskipTests compile

# ② 改动模块及其依赖的单测
mvn -q test -pl dsh-<改动模块> -am

# ③ 冒烟（可选但推荐）：起一个隔离实例
#    - 用独立端口与 data 目录，避免与生产冲突
#    - 例：mvn spring-boot:run -pl dsh-boot \
#         -Dspring-boot.run.arguments="--server.port=18080 --dsh.data.dir=/tmp/dsh-dev-data"
#    - 健康检查：curl http://localhost:18080/actuator/health 期望 UP
```

**全绿才允许执行 `sync-back.sh`。**

---

## 7. develop 实例运行隔离（防冲突）

在 develop 里调试运行 Archon 时：
- **端口**：使用独立端口（如 18080），避开生产的 8080
- **数据目录**：使用独立目录（如 `/tmp/dsh-dev-data`），避开生产 `data/`
- **身份/密钥**：develop 里不复制 `.dsh-identity`（已在排除清单），必要时生成开发专用身份
- **禁止嵌套**：不要让 develop 里的实例再创建 `develop/develop` 副本；若必须演练"建副本"，建到 `/tmp` 下

---

## 8. 防嵌套与卫生检查

- 每次同步前确认 `develop/` 内**没有** `develop/`、`backup/` 子目录：
  ```bash
  ls /home/john/workspace/archon-dsh/develop/ | grep -E '^(develop|backup)$' && echo "⚠️ 发现嵌套！" || echo "无嵌套，OK"
  ```
- `git status` 永远不应出现 `develop/`、`backup/`（被 ignore）；若出现，说明 ignore 失效，立即修复
- 每轮迭代结束，`develop` 与 `archon-dsh` 应完全一致（可用第 3 节 diff 命令复核）

---

## 9. 回滚

| 场景 | 方法 |
|------|------|
| 回拷后发现 bug（未 commit） | `git checkout -- .` 丢弃，或从 `backup/pre-sync-<时间戳>/` 恢复 |
| 已 commit 但需撤销 | `git revert <commit>` 或 `git reset --hard <上一commit>` |
| 运行异常需回退版本 | 从 `backup/` 快照 `rsync` 回 archon-dsh，重启 |

---

## 10. 常见坑速查

| 坑 | 规避 |
|----|------|
| `cp -r` 同步导致删除不生效 | 一律用 rsync，回拷带 `--delete` |
| rsync 忘记末尾斜杠 | 源/目标都以 `/` 结尾，否则目录层级错乱 |
| 回拷把 `data/`、`target/` 带回去 | 排除清单必须完整（见第 2 节） |
| 回拷把生产独有文件删掉 | 回拷前必跑 4.2 的"Only in"检查 |
| 同步了源码但没重启 | 记住：回拷 ≠ 生效，需重新构建并重启 |
| 在 develop 里又建了副本 | 防嵌套检查 + develop 实例不触发建副本操作 |
| `.gitignore` 末尾无换行 | 用 `printf '\n' >> .gitignore` 修复 |

---

*文档版本：v1.0（2026-08-26）*
