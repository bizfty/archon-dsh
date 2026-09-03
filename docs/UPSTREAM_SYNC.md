# external/deepseek 上游参照快照 — 版本锁定与同步说明

> `external/deepseek` 是本工程 Java 复刻所参照的 **DeepSeek Harness（TypeScript）上游源码快照**，
> 用于能力面对照、映射文档撰写与移植设计参考。它不是本仓库构建的一部分。

## 当前锁定版本

| 项 | 值 |
|---|---|
| 上游 commit | `76fda729799fe9b3848dbe2c211d4b231032b81e` |
| 日期 | 2026-09-03 14:02:58 +0800 |
| 描述 | master 最新；位于 tag `dsh-v0.1.2-rc.1` 之后（`dsh-v0.1.0-rc.7-2577-g76fda72979`） |
| 同步时间 | 2026-09-03 |
| 前序锁定 | `99f6f02fecdb7dff40c3fbc9470f5907c29f74ca`（dsh-0.1.0-rc.7，2026-08-17） |

## 仓库性质（重要）

- `external/deepseek` 是**嵌套独立 git 仓库**（自带 `.git`），其文件**不被父仓库（archon-dsh）跟踪**
  （`git ls-files external/` 为空；`external/` 整体是本地参照物）。
- 因此同步上游 = 在该嵌套仓库内直接 `git fetch/reset`，对父仓库无任何 git 副作用；
  `git status` 不会显示其内容变化。
- 嵌套仓库内存在大量 `node_modules`（examples/apps/vendor 等，均被上游 `.gitignore` 忽略）。
  同步只更新**已跟踪源码**，不改动这些 ignored 目录；如需清空体积可手动删除。

## 同步方法

```bash
cd external/deepseek
git fetch origin master          # 或临时加本地 remote：
# git remote add local-sync /path/to/deepseek-harness && git fetch local-sync master
git reset --hard FETCH_HEAD      # 或 reset --hard <目标 commit>
git remote remove local-sync     # 若加了临时 remote
```

同步后应验证：

```bash
git log -1 --format='%H %ad %s'  # 应为目标 commit
git status --porcelain | wc -l   # 应为 0
```

## 能力参照原则

1. **上游语义演进记录在映射文档**：每次同步后，若上游相对前序锁定的能力面有漂移，
   须同步更新 `docs/DSH_JAVA_MAPPING.md` 的基线版本说明与「能力缺口」章节，
   以及 `docs/capability-map-draft.md` 的勘误指引，再决定 Java 侧是否跟进。
2. 上游处于 pre-release 快节奏迭代期，**不承诺 API 稳定**；Java 复刻以能力语义为准，
   不做 1:1 代码移植。
3. 本文件记录了当前锁定版本，供后续「升级对照」使用。
