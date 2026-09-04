# P3 设置官方语义落地：path 级 set/unset · revision CAS · 覆盖标记 · secret redact

> 基线：archon-dsh HEAD（P2 描述符树化 + SchemaForm 递归渲染已落地，`plan-…` completed，工作区 clean）
> 配套：[design-settings-nested-schema.md](design-settings-nested-schema.md)（P2）、[design-schema-ui.md](design-schema-ui.md)
> 官方对照：`external/deepseek/packages/settings/settings/src/{index.ts,types.ts,redact.ts}` + tests（@ `76fda7297`，已源码级核实）
> 档位：P3（2026-09-04 用户圈定）——**path 级 set/unset + revision CAS + 覆盖标记 + secret redact**，先 human review 再拆 DAG。
> 原则沿用：**只增语义不破坏既有消费**（AgentLoop 读取零漂移）、schema 单源、wire 走既有端点族、分阶段提交。

---

## 1. 目标与边界

**目标**：把官方 settings 的四个"配置面语义"落到 Java dsh-settings + wire + 前端：

1. **path 级 set/unset**——配置 UI 持不完整（redacted）视图也能按路径精确写/删，不整文档重提（否则会静默删除 wire 从未返回的 secret）。
2. **revision CAS**——namespace 级单调修订号；写携带 `expectedRevision`，stale 写入被拒（`SETTINGS_CONFLICT`），防并发覆盖。
3. **覆盖标记（presence）**——user 层字段在场即"用户覆盖"（inherited/overridden 语义），meta 下发 user 视图，UI 显示覆盖态 + 可"恢复默认"。
4. **secret redact**——schema 声明 secret 槽位；wire 边界一律剥离 secret 值 + 下发 `secrets[{path,set}]` sidecar；UI 渲染 write-only 输入。

**不做**（范围外，文档标注）：
- `base` 组成层（官方 entry-config；仓库 defaults 已扮演 schema 默认，两层模型够用，未来加层不破坏 revision/redact/path）。
- 跨字段 `validate` 回调、`watch`/`settings/updated` 事件（Java 端事件总线另议）、settings-file 本地文档 provider（`writable/hasDocument` 语义：仓库 storage 即写源 → writable=true 恒，UI 不渲染这两态）。
- 迁移存量真实覆盖的自动化（经核实无生产覆盖存量，仅测试 InMemory 写入；迁移做防御性兜底）。

## 2. 官方语义核实（源码级，§ 已确认）

### 2.1 价值解析与视图（types.ts + index.ts describe）
- 解析链：**schema defaults → base → user**；`scope.get()` 返回 resolved。
- `describe({redactSecrets})` → 每 namespace：
  `{ns, schema(toJSON envelope), value(redacted resolved), revision, base?(redacted), user?(redacted), applies(live|restart), secrets?(RedactedSecret[])}`
- **覆盖标记 = presence**：`user` 字段在场 → 该键 user-overridden（非显式枚举状态）。
- `revision`：raw user section 的单调号；UI 读 view 拿 revision，写回 `expectedRevision`。

### 2.2 写面（index.ts）
- `update(patch)`：merge 进 user section；`replace(section)`：整换（缺键回继承）；**`mutate(pathOps)`**：path op 序列。
- `SettingsPathOp`：`{op:'set', path:string[], value}` | `{op:'unset', path:string[]}`；**空 path = section 根**（unset → `{}`）；set 沿 path **创建中间对象**。
- path op 的存在理由（源码注释原文）：redacted 视图**按构造从未收到 secret 字段**，若用整文档 replace 重建会静默删除 secret —— 故持不完整视图的调用者必须用 path 命名字段。
- **CAS**：写队列内串行化后比对 `expectedRevision !== registration.revision` → `SettingsConflictError(code='SETTINGS_CONFLICT', expected, actual)`。`expectedRevision` **可选**（不传 = 不校验）。
- revision 仅在 **raw section 实际变化**时 +1（deep-equal 不推进）；`document-updated` 事件通知（presence 翻转也通知，本设计不做事件）。

### 2.3 redact（redact.ts）
- 按 schema 活节点遍历 `object`(properties)/`dict`(entries)/`array`(items)：`role('secret')` 字段 → **值从 detached 副本剥离** + `secrets.push({path, set: value!==undefined})`。
- object 属性位**恒枚举**（即使未设置，UI 知道槽位存在）；dict/array 项仅实际存在处记录。
- 输入不 mutated；wire 面 MUST 过 redact（同进程 UI 才允许 verbatim）。

## 3. 仓库现状（文件级，已核）

| 面 | 现状 | P3 缺口 |
|---|---|---|
| `SettingsService` | defaults(Map) + overrides(storage `settings.{ns}.{key}` 文本，JSON 化/标量)；get/getString/getInt/set/all/describe/registerDefaults/registerDescriptor；**无 unset/无 delete 暴露/无 revision/无 base/无 redact/无写校验/无 presence 视图** | 需 user 层单文档 + path op + CAS + redact + 校验 |
| `StorageService/Backend` | `get/put/delete/keys(ns)` 字符串 kv（InMemory/Jpa/JsonFile） | 无 CAS 原语 → revision/文档存为普通键，**进程内锁 + 值比对**实现 CAS |
| 消费点 | `AgentLoopService`(204-220) 用 `get/getInt("agent",…)` 读温度/步数/并行；`AgentSettingsConfig` 注册 3 defaults+descriptors；`SettingsController` 整键 GET/PUT + `GET {ns}` + `GET /meta`；P2 已加嵌套 example | 读取语义必须零漂移；controller 需新 op 面 |
| wire | `GET /api/settings/meta` → `[{namespace, settings:[SettingDescriptor], values:Map}]`；`PUT {ns}/{key}` body`{value}` | meta 需 view 化（value/user/revision/secrets/applies）；写面加 ops + DELETE |
| 前端 | api.ts `SettingsNamespace{namespace,settings,values}`；SchemaForm 逐顶层键 PUT（不带 revision）；无 presence/secret UI | view 类型 + 冲突重载 + 覆盖态 + write-only secret |

## 4. 决策（D1–D8）

### D1 存储模型：user 层单文档 + 迁移（对齐官方 document 语义）

每 namespace 的 **user 层 = 一个 JSON 文档**，存 storage 固定键：
- 文档：`settings.{ns}.doc`（JSON 对象文本；`SettingsService` 内部解析/持有 `Map<String,Object>`）
- 修订号：`settings.{ns}.rev`（整数文本；String→int）
- **不再使用**每键条目 `settings.{ns}.{key}`（旧模型废弃，仅迁移用）

**迁移（防御性）**：`loadUser(ns)` 首次访问时——若 `doc` 不存在但存在旧 `settings.{ns}.{key}` 条目 → 逐键并入文档、写 doc、`rev=1`、**删除旧条目**；无旧条目 → 空文档 + rev=0。生产无真实存量（核实），测试级 InMemory 迁移覆盖单测。`set` 保留签名（内部 = `mutate([{op:set,path:[key],value}])`）→ 存量测试零改。

理由：path 空路径 = 整文档 replace/unset、revision/redact/presence 全在文档粒度上，与官方一一对应；避免"key 粒度 + path 值内"的两套寻址。

### D2 写面：mutate(pathOps) + CAS（SettingsService 升级）

新增（保留既有 `set/registerDefaults/…` 签名，内部改道）：

```java
record SettingsPathOp(String op, List<String> path, Object value) {} // op: set|unset
long mutate(String ns, List<SettingsPathOp> ops, Long expectedRevision); // 返回新 revision
long update(String ns, Map<String,Object> patch, Long expectedRevision);    // merge → ops(set per patch key)
long replace(String ns, Map<String,Object> section, Long expectedRevision); // 整换 user 文档
long unset(String ns, List<String> path, Long expectedRevision);            // mutate unset
Map<String,Object> userSection(String ns);   // 原始 user 层（内部/同进程；redact 前）
long revision(String ns);                    // 当前修订号
```

- **锁与 CAS**：每 namespace 一把锁（`ConcurrentHashMap<String,Object>` monitor）。写流程：锁内 `loadUser` → `expectedRevision != null && expectedRevision != rev` → 抛 `SettingsConflictException(code='SETTINGS_CONFLICT', ns, expected, actual)`（自定义 RuntimeException，controller 层映射 409）→ 应用 ops（detached 深拷贝改）→ **写校验（D5）通过** → 深比较文档是否实际变化（变化才）`rev+1` + 写 doc/rev。
- `applyPathOp`（官方语义）：空 path → unset:`{}` / set:整体替换（须 plain object）；非空 → 沿 path 取子 Map（缺失按需建 LinkedHashMap）set，或逐级下探后 remove（unset）；key 不存在时 unset 幂等（文档不变 → revision 不推进，与官方 deep-equal 门一致）。数组索引 path 段解析为 int（越界 set 拒绝 400、unset 幂等）。
- `set(ns,key,value)` 旧签名 = `mutate([{set,[key],value}], null)`（无 CAS，向后兼容存量调用/测试）。

### D3 读面与覆盖标记（presence）

- `get(ns,key)/getString/getInt/all` 语义**不变**：解析链 **defaults → user 文档**（user 键在场覆盖 defaults；用户显式 `unset` 后回 defaults）。实现：`loadUser` 文档并入 `defaults` 得 resolved（`all()` 即此）。
- **覆盖标记 = presence**：`key ∈ user 文档` ⇔ user-overridden；嵌套路径同理（`path ∈ user 文档`）。新增 `boolean overridden(ns, List<String> path)` 供内部/测试。
- 注意与旧行为差异：旧 `all()` 遍历 storage keys 把覆盖并入 defaults；新实现从文档解析——**对已迁移数据结果一致**（迁移已把旧条目并入 doc）。

### D4 secret redact（descriptor 声明 + 树遍历剥离）

- `SettingDescriptor` 叶子加 `Boolean secret`（canonical 尾部 + `@JsonInclude(NON_NULL)`；`leaf` 工厂加带 secret 重载；默认 null=非 secret）。语义 = 官方 `meta.role('secret')`，作用于该字段的**值**。
- `SettingsService`/独立 `SettingsRedactor`：`(descriptor 树, value)` → `{value(剥离副本), secrets:[{path:String[], set:boolean}]}`：
  - 遍历与官方 redact.ts 对齐：object → properties 逐子（secret 子 → 剥离 + 记录恒枚举；否则递归）；array → items 逐元素（secret 元素仅实际存在记录）；**visibleWhen 不参与 redact**（纯展示）。schema 无对应（value 有未知键）→ 原样透传（宽容，不丢数据）。
  - **secret 键 unset/未设置**：`set=false` 仍进 secrets（UI 渲染"未设置"槽）。
- 内部消费（AgentLoop 等）**不经 redact**（同进程 verbatim，官方同语义）；仅 wire 边界（D6 controller 出参）过 redact。

### D5 写校验（descriptor 树校验器）

官方写路径过 schema；仓库补对称最小校验（否则 CAS/path 允许任意坏值）：

- `SettingValidator.validate(ns, key, value)` / `validateDocument(ns, Map)`：
  - 该键有描述符 → leaf：类型强校验（number/integer 数值 + min/max/step、boolean、enum∈options、string）；object → 子键逐描述符递归；array → items 描述符逐元素递归。
  - **未知键宽容透传**（防未来字段与无描述符 namespace）；**无描述符命名空间 → 不校验**（现状保持，registerDefaults-only 的 hidden 不受影响）。
  - 失败 → `IllegalArgumentException`（controller 400）；secret 键允许写（值只在写面单向进入，wire 永不回读）。

### D6 wire 契约（meta 升级 + 写面扩展）

**GET `/api/settings/meta`** → 每 namespace view（breaking，前后端同提交）：

```json
{ "namespace": "agent",
  "settings": [ /* SettingDescriptor 树，secret 字段带 "secret":true */ ],
  "value": { /* resolved，redacted */ },
  "user": { /* raw user 层，redacted；presence=覆盖 */ } | 省略(空),
  "revision": 3,
  "secrets": [ {"path": ["apiKey"], "set": true} ],
  "applies": "live" }
```

- `applies` 默认 `"live"`；`SettingsService` 增 `registerApplies(ns, "live"|"restart")`（缺省 live；agent 声明 live）。旧 `values` 字段移除（语义并入 `value`，前端同步改）。

**写面**（沿用 `/api/settings` 前缀）：
- `PUT /{ns}/{key}` body `{value, expectedRevision?}`——**兼容保留**（内部 mutate set）；带 revision 时 CAS。
- `POST /{ns}/ops` body `{ops:[{op:"set"|"unset", path:[...], value?}], expectedRevision?}` → 200 `{revision}`。
- `DELETE /{ns}/{key}` = unset [key]（恢复默认）；secret 清除同此。
- 409：`SettingsConflictException` → `{code:"SETTINGS_CONFLICT", namespace, expected, actual}`。
- 400：校验/路径非法 → `{error}`。

### D7 前端（view 类型 + SchemaForm/Field 升级）

- `api.ts`：`SettingDescriptor` 加 `secret?: boolean`；`SettingsNamespace` → `SettingsNamespaceView {namespace, settings, value:Record<string,unknown>, user?:Record, revision:number, secrets:{path:string[],set:boolean}[], applies}`；`SettingDescriptor` 加 `items?/children?` 沿用。
- `fetchSettingsMeta` 改返回 view[]；SchemaForm props 改（view）：
  - 值源 = `view.value`；**覆盖态** = path ∈ `view.user` → 行尾徽标"已覆盖/默认" + 覆盖项"恢复默认"（DELETE /unset op）；
  - **secret 字段**：渲染 write-only——未设置 → 空输入 + [设置]；已设置 → `••••••` 占位 + [重设](unset→设置)（值不显示不回读）；不修改则不提交该 path；
  - **保存**：收集变更 → `POST ops`（set/unset path 序列）+ `expectedRevision: view.revision`；`409` → 提示"设置已被他人修改" + 自动 reload meta（不静默覆盖）；保存成功 → 用响应 revision 刷新本地 view；
  - 逐顶层键 PUT 逻辑替换为 path op（整键嵌套仍可表达为 set [key]）；
  - visibleWhen 联动/P2 object/array 递归**保持**。
- `SettingsPage`：适配新类型 + 冲突提示 + 空态沿用。

### D8 验证口径与红线 + 落点拆解

**红线**：
1. `AgentLoop` 读取零漂移（`get/getInt` 解析链不变）——现有 AgentLoopSettingsTest/AgentSettingsConfigTest 不改语义通过（set 签名保留）。
2. `mvn verify` 全量不回退（542 baseline + 新增）；`vite build` 通过。
3. grep 校验：wire 出参必经 redact（controller 无 verbatim value 下发路径）；前端无 secret 值硬编码读回。
4. 分阶段提交：dsh-settings → dsh-agent → dsh-api → dsh-web → docs。

**新增单测**：
- dsh-settings：迁移（旧键→doc+rev+删旧）；mutate set/unset/空 path/数组 path/幂等 unset 不推进 rev；CAS（expected 命中/过期/缺省）；update/replace；presence overridden；validator（leaf/object/array/enum/min-max/未知键宽容/无描述符跳过）；redact（嵌套 secret 剥离 + secrets 恒枚举 + 数组项 + 透传未知）。
- dsh-api：meta view 形状（value/user/revision/secrets/applies + redacted）；PUT 兼容+CAS；POST ops 200/400/409；DELETE reset。
- dsh-agent：AgentLoop 行为回归（不改断言，仅适配新 service 构造若有）。

**落点拆解**（批准后转正式 DAG，全 task）：
| # | 内容 |
|---|---|
| p3-1 | dsh-settings：存储模型单文档 + 迁移 + revision + mutate/update/replace/unset + CAS + presence（含单测） |
| p3-2 | dsh-settings：validator + redactor + descriptor secret 字段 + applies 注册（含单测） |
| p3-3 | dsh-api：SettingsController meta view + POST ops + DELETE + 409/400 映射（含 MockMvc） |
| p3-4 | dsh-agent：AgentSettingsConfig 声明 applies=live + secret 示例（如无真 secret 则仅测试面）；回归测试适配 |
| p3-5 | 前端：api.ts view 类型 + SchemaForm ops 保存/CAS 冲突重载 + presence 徽标/恢复默认 + secret write-only（vite build） |
| p3-6 | 收口：mvn verify 全量 + vite build + smoke（浏览器：覆盖/恢复/secret 写-only/409）+ 文档回写 + 分阶段提交 |

## 5. 风险与回退

| 风险 | 缓解 |
|---|---|
| 存储模型迁移破坏存量 | 核实无生产覆盖存量；迁移防御性 + 单测；读取 lazy、失败回落旧模型并告警（实现期定） |
| meta breaking 影响前端 | 前后端同仓同阶段提交；vite build + smoke 验证 view 消费 |
| CAS 锁粒度/并发 | namespace 级 monitor + 锁内校验写；单测覆盖顺序 stale |
| validator 误伤存量/未来键 | 未知键宽容 + 无描述符 namespace 跳过；leaf 校验与 P2 描述符同源 |
| 范围蔓延（事件/base） | 明确不做并标注未来项；如需另开设计 |

## 6. 落地记录（2026-09-04，plan-70e6a4e3 completed）

- **p3-1** 存储与写面：`SettingsService` user 层**单文档**（`settings.{ns}.doc` JSON + `settings.{ns}.rev` 整数）+
  lazy 迁移旧每键条目（并入 doc、rev=1、删旧）；`mutate`/`update`(深合并)/`replace`/`unset` + `SettingsPathOp`
  （set/unset、空 path、数组 int 段、顶层 String 标量归一保存量 set 语义）+ namespace 级锁 + **revision CAS**
  （`SettingsConflictException` code=`SETTINGS_CONFLICT` expected/actual）+ 深比较仅实际变化 rev+1；
  `overridden`/`revision`/`userSection`。新增 `SettingsPathCasTest` 11 例。
- **p3-2** validator/redactor：`SettingDescriptor` 加 `Boolean secret`（canonical 尾 + `withSecret`，`@JsonInclude` 省略 null）+
  `SettingsService.registerApplies(ns, live|restart)` 缺省 live；`SettingsRedactor` 按树剥离 + `secrets[{path,set}]`
  （object 属性恒枚举含缺失容器下钻、array 逐项、未知键透传）；`SettingValidator` 树强校验并接入写路径落盘前；
  新增 `SettingsRedactorTest` 5 + `SettingValidatorTest` 7。
- **p3-3** wire：`SettingsController` GET /meta → **redacted view**（value/user?/revision/secrets/applies，移除旧 values）；
  `PUT {ns}/{key}` body `{value, expectedRevision?}` 兼容+CAS；新 `POST {ns}/ops`（set/unset ops + expectedRevision）；
  `DELETE {ns}/{key}`=unset；`get`/`all` 亦过 redact（secret 不回明文，只回持有状态）；`GlobalExceptionHandler`
  `SettingsConflictException`→409 `SETTINGS_CONFLICT`。`SettingsMetaControllerTest` 5→11。
- **p3-4** dsh-agent：`AgentSettingsConfig` 构造加 `registerApplies("agent","live")`；AgentLoop 读取零漂移
  （set 签名保留、解析链不变），回归测试语义不改通过。
- **p3-5** 前端：`api.ts` `SettingsNamespaceView{namespace,settings,value,user?,revision,secrets,applies}` +
  `SettingsSecret`/`SettingPathOp` + `putSettingOps`（409→conflict 错误）；`SchemaField` 递归 path 透传 + **secret
  write-only** 行（已设置/未设置徽章 + 保存/清除，值不回读）；`SchemaForm` 容器改 **ops 批量保存**（diff 顶层变更 +
  secret ops + 恢复默认 unset，`expectedRevision=view.revision`）、409 冲突提示 + emit conflict（SettingsPage 重载）、
  presence「已覆盖」徽标 + 恢复默认、待保存计数；`SettingsPage` reload 语义。`vite build` 通过。
- **p3-6** 收口：`mvn verify` 全绿不回退（542 baseline + 29 新增 = **571 tests / 0 fail**）；`vite build` 通过；
  grep 红线（wire 出参必经 redact——meta/get/all 无 verbatim 明文路径；前端无逐键 PUT 调用残留）；docs 三处回写；
  分阶段提交 dsh-settings → dsh-agent → dsh-api → dsh-web → docs。

**范围外（诚实标注）**：`base` 组成层（entry-config；仓库 defaults 已当 schema 默认）、跨字段 `validate` 回调、
`settings/updated` / `document-updated` 事件、settings-file provider（writable/hasDocument）。
