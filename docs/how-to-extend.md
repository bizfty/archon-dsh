# 如何扩展 DSH（How to Extend）

> 位置：`docs/how-to-extend.md`
> 依据：`docs/design-extension-points.md`（支柱③ M5 裁决 §8：扩展注册表不做，本文档化现状）
> 目的：新增 gate / tool / system-prompt section / chat command 的分步指南。**这里写的是
> 现状机制**（Spring 装配），不是新抽象 —— 支柱③裁决：Spring 集合注入已够用，不引入统一注册表。

## 1. 现状：三类扩展形态（design-extension-points.md §2 摘录）

| 形态 | 机制 | 典例 | 适合新增什么 |
|---|---|---|---|
| A. 接口集合注入型有序链 | 实现接口 → Spring 收集同接口全部 bean → `order()` 升序执行 | `ToolPreExecuteGate`/`ToolPostProcessor` → `ToolExecutionPipeline`；`SystemPromptSection` → `SystemPromptService` | 门控/审批/后处理/提示段 |
| B. 运行时注册表 | `register(...)` + `resolve(...)` | `ToolRegistry`（工具）、`AgentScopeRegistry`（作用域遮蔽） | 动态工具/作用域贡献 |
| C. observe-only 事件总线 | `SessionEventBus.addListener(...)`（order 排序、异常隔离） | 持久化/UI 推送/遥测 | 观察者（不可短路） |
| D. 聊天命令 | `ChatCommand` 接口 → `CommandRegistry`（M6 新增） | `/compact`、`/help` | 文本命令 |

**边界**：`SessionEventBus` 是 observe-only —— 需要**短路/替换**语义的扩展点（工具门控、
提示组装）不走总线，走形态 A 的有序链。此设计决定不变（M5 裁决）。

## 2. 新增一个工具（Tool）

```java
// 例：dsh-tool 某新能力
@Tool(name = "my-tool", description = "做什么")
public class MyTool implements AgentTool {
    @Override public String name() { return "my-tool"; }
    @Override public ToolSchema getSchema() { /* 参数 schema */ }
    @Override public ToolResult execute(ToolCall call, ToolContext context) { ... }
}
```
- 放任意被扫描的 jar（`com.bizfty.anchon.dsh.**`），`ToolRegistry` 启动时经
  `ApplicationContext.getBeansOfType(AgentTool.class)` 自动收集。
- 动态注册：`toolRegistry.registerDynamic(tool)`。
- 工具可见性由 Agent 作用域过滤（见 §4）；并行安全需 `isConcurrencySafe()`（`AgentTool` 默认，
  检查接口约定）。
- 测试：放模块 `src/test`，参考 `AgentLoopServiceTest` 的 EchoTool 模式（`ToolRegistry` +
  mock `ApplicationContext`）。

## 3. 新增一个工具门（Gate）或后处理（PostProcessor）

```java
// 例：审批门（参考 dsh-interaction ApprovalGate）
@Component
public class MyGate implements ToolPreExecuteGate {
    @Override public int order() { return 50; }          // 升序；先于/后于既有门
    @Override public ToolResult beforeExecute(ToolContext ctx, String toolName, String args) {
        if (需拒绝) return ToolResult.failure("拒绝原因");   // 单调拒绝：一拒即停
        return ToolResult.success("放行");                  // 放行语义见接口注释
    }
}
```
- 实现 `ToolPreExecuteGate`（工具执行前）/ `ToolPostProcessor`（执行后），Spring 自动收进
  `ToolExecutionPipeline`，按 `order()` 升序；门控**单调拒绝**（一旦拒绝后续门不再放行）。
- 既有实现者作参照：`ApprovalGate`（审批，interaction）、`HookGate`/`HookPostProcessor`
  （hooks）、`RepeatToolReminder`（guard，PostProcessor）。
- 安全关键门不要设低 order 让别的门可绕过它（无禁用开关 —— 支柱③ D7 未启用"停用保护位"）。

## 4. 新增一个 system-prompt 段（Section）或作用域贡献

```java
// A. 全局段：构造注入 SystemPromptService 的 sections
@Component
public class MySection implements SystemPromptSection {
    @Override public String key() { return "my-section"; }
    @Override public int order() { return 100; }
    @Override public String render(SystemPromptContext ctx) { return "……"; }
}

// B. 按 agent 作用域贡献（同键遮蔽）：AgentScopeRegistry.register(...)
@Component
public class MyScopeContributor {
    MyScopeContributor(AgentScopeRegistry registry) {
        registry.register(AgentScopeRegistration.builder()
            .agentId("*")            // 或精确 agent id
            .sectionKey("my-section")
            .order(10)
            .section(MySection::new) // 附加段
            .toolFilter(name -> !name.equals("sensitive-tool")) // 工具可见性（只收窄）
            .build());
    }
}
```
- 遮蔽语义：同 (agentId, sectionKey) order 大者胜出；精确 id 优先于通配 `*`。
- 参照：dsh-agent `AgentScopeRegistry` 源码与既有注册（preset/settings 扩展）。

## 5. 新增一个聊天命令（Chat Command）

```java
@Component
public class MyChatCommand implements ChatCommand {
    @Override public String name() { return "mytool"; }          // 不含 /，小写
    @Override public String description() { return "一行说明（/help 列出）"; }
    @Override public String execute(SessionId sessionId, String args) {
        // args：命令名后剩余文本；无参命令请校验空并回 Usage
        return "……人类可读结果……";
    }
}
```
- 放 dsh-agent `command` 子包（或任意被扫描 jar），Spring 收进 `CommandRegistry`；
  `SessionController` 聊天入口（chat/stream）自动分发 `/<name>`；`/help` 内建列命令。
- 需要做重活（如压缩）时委托 `AgentLoopService` 门面（见 `CompactChatCommand` → M4 常驻入队语义）。
- 参照测试：`CommandRegistryTest`。
- 命令面未膨胀前的边界：不做参数解析/权限/子命令（M5 D8 裁决）；膨胀时再扩展。

## 6. 新增一个事件观察者（Observer，不可短路）

```java
@Component
public class MyObserver {
    MyObserver(SessionEventBus bus) {
        bus.addListener(new SessionEventListener() {
            @Override public int order() { return 100; }        // 小者先；持久化监听器 = -200
            @Override public void onEvent(SessionEvent event) {
                if (event.type() == SessionEventType.TOOL_RESULT) { ... }
            }
        });
    }
}
```
- observe-only：不可改变事件流/阻止执行；监听器异常被总线隔离（不影响其它监听器与主流程）。
- 参照：dsh-session `SessionEventPersistenceListener`（REQUIRES_NEW 落库）。

## 7. 通用约定

1. **模块归属**：新增实现放「接口所在模块的下游或同模块」——避免环依赖（如 gate 接口在
   dsh-tool，实现可在 dsh-guard/interaction 等依赖 dsh-tool 的模块）。
2. **order 语义**：形态 A/C 一律升序（小先执行）。同 order 不保证顺序 → 需顺序时给显式间隔。
3. **契约锁**：AgentLoop 行为由 19 个 dsh-agent 测试类 + dsh-api 60 测试锁死 —— 新扩展不得
   改变既有 turn/工具/取消/排队语义；新增用例放相应模块 `src/test`。
4. **为什么没有统一 DshExtensionRegistry**：支柱③裁决（design-extension-points.md §8 D2）——
   Spring 集合注入已覆盖有序链收集，统一注册表的增量收益（集中视图/启用开关）暂不抵其成本；
   命令面特判已单独收编为 CommandRegistry（低成本、真实去特判）。若未来需跨模块"停用某扩展"，
   再评估引入注册表（该文档 §5 D4-D7 已列决策上下文）。
