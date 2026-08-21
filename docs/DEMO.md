# 真实 API 演示指南

以下命令用真实 DeepSeek API 验证 harness 各能力。前置：`OPENAI_API_KEY`。

## 1. 启动

```bash
mvn -o clean install                                   # 全量构建 + 测试（268 单测）
OPENAI_API_KEY=sk-xxx java -jar dsh-boot/target/dsh-boot-0.0.1-SNAPSHOT.jar
# 或指定端口：SERVER_PORT=18080
```

### 前端（TypeScript）

启动后浏览器访问 **http://localhost:8080/** 即可使用 Web UI（`dsh-web` 模块构建产物）：

- 左侧栏：新建会话 / 搜索 / 最近会话列表
- 对话视图：SSE 流式输出（markdown + 代码高亮 + XSS 净化）、工具调用折叠展示、模型选择（Enter 发送 / Shift+Enter 换行）
- 目标视图：创建 / 查看 / 更新（完成 / 暂停 / 恢复 / 标记阻塞，CAS revision 展示）
- 前端源码：`dsh-web/src/main/webapp/`（**Vue 3 SFC + Vite + Element Plus**，布局参考 external/javaai 与 deepseek web）；
  `npm install`（联网一次）→ Maven 构建时 exec-maven-plugin 自动执行 `build.sh`（`npm run build` = vite），
  产物进 **dsh-web jar 的 `static/`**，dsh-boot 通过 Maven 依赖引入该 jar，由 Spring Boot
  从 classpath 服务（boot 自身不拷贝任何前端文件）

## 2. 基础对话 + 工具调用

```bash
# 建会话
curl -s -X POST http://127.0.0.1:8080/api/sessions \
  -H 'Content-Type: application/json' -d '{"title":"demo","model":"deepseek-chat"}'
# → {"id":"sess_xxx",...}

SID=sess_xxx   # 替换

# bash 工具（模型会真实执行 pwd）
curl -s -X POST http://127.0.0.1:8080/api/sessions/$SID/chat \
  -H 'Content-Type: application/json' -d '{"message":"用 bash 运行 pwd 并告诉我当前目录"}'

# SSE 流式（token + tool 事件）
curl -s -N -X POST http://127.0.0.1:8080/api/sessions/$SID/chat/stream \
  -H 'Content-Type: application/json' -d '{"message":"用 todo_write 记录三条待办"}'

# 持久化校验（含工具调用/结果）
curl -s http://127.0.0.1:8080/api/sessions/$SID/messages
```

## 3. 各能力工具

| 能力 | 提示词示例 | 说明 |
|---|---|---|
| todo | "用 todo_write 记录待办：A、B" | 整表替换（对象数组 schema） |
| plan | "先进入计划模式规划：给项目加登录功能" | `exit_plan_mode` 提交 |
| fs | "用 read_file 读 pom.xml 的依赖部分" / "用 glob 找 **/*.java" | 窗口化读取、路径策略 |
| web_fetch | "用 web_fetch 抓取 https://example.com 并总结" | 匿名 HTTP + HTML→文本 |
| web_search | "用 web_search 搜索 DeepSeek 是什么" | DuckDuckGo（无需 key） |
| subagent | "用 subagent 委托：计算 17*23" | 持久子会话 + 深度守卫 |
| workflow | "用 workflow 运行 JS：await tools.subagent({...}) 扇出两个子代理，return 结果之和" | 模型编写 JS 编排脚本（Node 执行） |
| run_code (JS) | "用 run_code 写程序：const r=await tools.echo({text:'hi'}); return r.message" | Node 执行 |
| run_code (Python) | "用 run_code 语言 python 写：r=await tools.echo({'text':'hi'}); return r['message']" | python3 执行 |
| goal | "用 create_goal 创建目标：完成 X（maxGoalRounds 5）" | 持久化 + CAS 更新（get_goal/update_goal） |
| schedule | "用 schedule_create 安排 10 秒后提醒我喝水" | 延迟注入会话 |
| bash_persistent | "bash_persistent: cd /tmp；再 bash_persistent: pwd" | 状态跨调用保留 |
| job 后台 | "用 bash 后台运行 sleep 5 && echo done，然后 job_wait 等它" | run_in_background → job_status → job_wait |
| lsp | "用 lsp 分析 src/main 下 Java 文件的语义操作" | LSP 能力缝（4 个语义操作） |
| browser | "用 browser_navigate 打开 https://example.com，再 browser_get_text 总结" | Playwright Chromium headless |
| github | "用 github_list_issues 查看仓库 issue" | 需 `GITHUB_TOKEN` 或凭据引用 |
| postgres/mysql | "用 postgres_query 查询..." | 需可达数据库（动态连接参数） |

## 4. 用户 profile + 按用户 LLM key

```bash
# 注册 → 登录（token）→ 更新 LLM 配置（provider/model/api key）
curl -s -X POST http://127.0.0.1:8080/api/users/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"s3cret-pass","llmProvider":"deepseek","llmModel":"deepseek-chat"}'
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/users/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"s3cret-pass"}' | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")

# 配用户自己的 key（经 CredentialService 落盘）
curl -s -X PUT http://127.0.0.1:8080/api/users/me/llm -H "X-Auth-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"apiKey":"sk-user-key"}'

# 带 token、不带 model 调 OpenAI 兼容端点 → 回退用户 profile 模型 + 按用户 key 路由
curl -s -X POST http://127.0.0.1:8080/v1/chat/completions -H "Content-Type: application/json" \
  -H "X-Auth-Token: $TOKEN" -d '{"messages":[{"role":"user","content":"hi"}]}'
```

## 5. 上下文管理（压缩三件套 + 手动 /compact）

```bash
# 低阈值启动（便于演示）：--dsh.compaction.token-threshold=600

# 工具结果截断：bash 输出超 8192 字符 → 模型只看到 头+标记+尾（日志保留全文）
curl -s -X POST http://127.0.0.1:8080/api/sessions/$SID/chat \
  -H 'Content-Type: application/json' -d '{"message":"用 bash 运行 python3 -c \"print(chr(65)*30000)\""}'

# 工具输出转存：同上超限结果 → 保存到 data/spill/<会话>/*.txt，行内替换为预览+定位符
ls data/spill/

# 手动压缩（不经过模型 turn）：消息为 /compact
curl -s -X POST http://127.0.0.1:8080/api/sessions/$SID/chat \
  -H 'Content-Type: application/json' -d '{"message":"/compact"}'
# → 已压缩 N 条历史（保留 M 条，预估节省 X tokens）
# /compact please → Usage: /compact (no arguments)
```

## 6. 外部 hook（Claude Code 风格 hooks.json）

```bash
# 工作区放 hooks.json（PreToolUse matcher=github → block），重启应用
cat > hooks.json <<'EOF'
{"hooks": {"PreToolUse": [{"matcher": "github", "hooks": [{"type": "command",
  "command": "printf '{\"decision\":\"block\",\"reason\":\"沙箱禁止 GitHub\"}'"}]}]}}
EOF
# 模型调 github_list_repos → 工具返回 "已拒绝: 沙箱禁止 GitHub"
```

## 7. 人机协作（审批 + 问答）

需要 `requiresApproval=true` 的工具；模型调用后：

```bash
curl -s http://127.0.0.1:8080/api/interactions/approvals/pending   # 待审批项
curl -s -X POST http://127.0.0.1:8080/api/interactions/approvals/{id}/approve
curl -s http://127.0.0.1:8080/api/interactions/questions/pending    # 待问答
curl -s -X POST http://127.0.0.1:8080/api/interactions/questions/{id}/answer \
  -H 'Content-Type: application/json' -d '{"answer":"是"}'
```

## 8. 自动化驱动（JSON-RPC stdio）

```bash
java -jar dsh-boot/target/dsh-boot-0.0.1-SNAPSHOT.jar --dsh.sdk.stdin-server.enabled=true <<'EOF'
{"jsonrpc":"2.0","id":"1","method":"initialize"}
{"jsonrpc":"2.0","id":"2","method":"session/prompt","params":{"message":"用 bash 运行 pwd"}}
{"jsonrpc":"2.0","id":"3","method":"shutdown"}
EOF
```

## 9. 验收清单（自动测试）

```bash
# 关键门控 e2e（无 key 自跳过）：bash / todo_write / planner persona / goal / manual compact
DEEPSEEK_API_KEY=sk-xxx mvn -o -pl dsh-boot test -Dtest=DeepSeekE2ETest
mvn -o clean install   # 全量门禁（268 单测）
```
