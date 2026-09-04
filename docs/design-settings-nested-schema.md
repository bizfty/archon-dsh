# P2 设置描述符嵌套 schema 与递归渲染专项设计

> 基线：archon-dsh HEAD（内核②全量已落地，`plan-f2e88817` completed，工作区 clean）
> 配套：[design-schema-ui.md](design-schema-ui.md)（内核② D1–D7）、[design-frontend-replication.md](design-frontend-replication.md)
> 档位：P2（2026-09-04 用户圈定）——**descriptor 表达力扩展（object/array 嵌套 + 最小联动）+ SchemaForm 递归渲染**；
> 范围外：path 级 set/unset + revision CAS（P3 官方 settings 同款语义，本次不做）、整包 PUT、拖拽/低代码工具。
> 惯例：本设计文档 **human review 批准后** → 拆正式 DAG（全 task）→ 实现 → 验证（vite build + mvn verify 全绿不回退）→ 分阶段 git 提交。

---

## 1. 目标与边界

**目标**：让设置项从"扁平 6 类型"升级为"schema 树"，且保持内核②铁律——**后端 descriptor 是唯一事实源，前端零硬编码、纯递归渲染**：

- `object`：一组子键（如 `profile { name, age, bio }`）→ 前端分组/折叠卡片递归渲染子字段。
- `array`：元素列表（元素可为标量或 object，如 `prompts []`、`proxies [{host,port}]`）→ 前端行式编辑器 + 增删。
- **最小联动** `visibleWhen`：同层兄弟键等值时字段才显示（如 `enable-tools = true` 才显示 `tools 白名单`）——轻量 DSL，供设置页这类静态联动；复杂条件后续再扩。
- 后端值存取升级：嵌套值以 JSON 持久化，`get/all/meta` 返回结构化 Map/List（wire 不变，仍是单键语义）。

**不做**（P3 官方 settings 语义，另开设计）：
- 覆盖标记（inherited/overridden 分层状态）、revision CAS、secret redact、path 级 unset/重置为默认。
- 官方 schema-form 的"可执行 schema 下发 + rehydrate 为校验器/默认值源"模型——我们 Java 侧 descriptor 已是 schema 树的事实源，前端只需渲染（校验事实源仍在后端 PUT 时执行）。范围差以诚实标注。

## 2. 现状核实（文件级，2026-09-04）

### 后端
| 点 | 现状 | 对 P2 的影响 |
|---|---|---|
| `dsh-settings/.../SettingDescriptor.java` | record 9 字段 `(key,type,label,description,defaultValue,options,min,max,step)`，type 注释限 string/number/integer/boolean/enum | 需树化：加 `children/items/visibleWhen`；record 加字段破坏 4 个构造调用点 → 引静态工厂一次性迁移 |
| `SettingsService.java` | `defaults/descriptors` 两个 ConcurrentHashMap（保序 LinkedHashMap）；`get` 覆盖>默认；`parse()` 只解 bool/int/double/string；`set()` 用 `String.valueOf(value)` 持久化 | **核心缺口**：嵌套 Map/List 会被 `toString` 存坏 → `parse/set` 需 JSON 编解码 |
| `StorageService`（dsh-storage） | `Optional<String> get(ns,key)` / `put(ns,key,String)` / `keys(ns)` 文本后端 | 存 JSON 字符串即可，无接口改动 |
| `SettingsController.java`（dsh-api） | `PUT /{ns}/{key}` body `SetBody(Object value)`（Jackson 已能接任意 JSON）；`GET /meta` → `{namespace, settings:[descriptor], values:all}` | 端点形状**无需改**；meta 自动带新字段（null 时 @JsonInclude 省略）；PUT 嵌套 body 直接透传给 service.set |
| 调用点 | `AgentSettingsConfig`（3 标量描述符）、`SettingsMetaControllerTest`（2）、`SettingDescriptorTest`（工厂 1） | 全部迁移到静态工厂，语义零变化 |
| `dsh-settings/pom.xml` | 仅依赖 dsh-storage + test starter | **无 compile 级 Jackson** → 加 `jackson-databind`（父 BOM 管版本） |
| 前端测试设施 | `package.json` scripts 仅 dev/build（无 vitest）；deps 无 lodash | 前端验证 = vite build + 浏览器 smoke（沿用 sui-7 口径）；等值比较用 `JSON.stringify` |

### 前端
| 点 | 现状 | 对 P2 的影响 |
|---|---|---|
| `api.ts` | `SettingDescriptor` 平铺 interface（同 record 9 字段）；`putSetting(ns,key,value)` 已 `JSON.stringify({value})` 传任意 JSON | 类型树化：type union + `children/items/visibleWhen`；putSetting 无需改 |
| `SchemaForm.vue`（102 行） | v-for 扁平 settings → 按 type 渲染 el-switch/el-input-number/el-select/el-input；`draft` 扁平行对象；保存 for 循环**逐顶层键 PUT** | 需递归化；逐顶层键 PUT 保留（嵌套值整键 JSON 提交） |
| `SettingsPage.vue`（92 行） | fetchSettingsMeta → 每 namespace 卡片 → SchemaForm；`onChanged` 单键回写 | 仅类型联动适配，基本不动 |

## 3. 决策（D1–D6）

### D1 SettingDescriptor 树化（零破坏迁移 + 静态工厂）

`SettingDescriptor` record **新增三字段（尾部）**，语义由 type 决定：

```java
public record SettingDescriptor(
        String key, String type, String label, String description, Object defaultValue,
        List<String> options, Double min, Double max, Double step,
        List<SettingDescriptor> children,   // type = object 时的子描述符（保序）
        SettingDescriptor items,            // type = array 时的元素描述符
        VisibleWhen visibleWhen) {          // 联动：同层兄弟键等值时显示
}
```

- type 常量类 `SettingDescriptor.TYPE_STRING/NUMBER/INTEGER/BOOLEAN/ENUM/OBJECT/ARRAY`（wire 仍是字符串，不引枚举——避免大改与 JSON 序列化特例）。
- `VisibleWhen(String key, Object equals)`：**仅当同层对象值 `layer[key]` 与 `equals` 等值（JSON.stringify 语义，覆盖标量与简单结构）时该字段可见**（浅语义第一版；隐藏 ≠ 删除，值保留在 draft/PUT 中，文档注明）。
- **静态工厂**（canonical 构造改 12 参后，存量 4 处调用点一次性迁移，编译期保证找全）：
  - `leaf(key, type, label, description, defaultValue, options, min, max, step)` — 现有 9 参语义；
  - `group(key, label, description, SettingDescriptor... children)` — type=object；
  - `list(key, label, description, SettingDescriptor items)` — type=array；
  - 联动经 `leaf/group/list` 同参 + `visibleWhen` 尾参的重载传入（实现期以不过度膨胀为准，原则：**标量叶子走 leaf、嵌套走 group/list、联动走带 visibleWhen 重载**）。
- record 上标 `@JsonInclude(JsonInclude.Include.NON_NULL)`：wire 上 `children/items/visibleWhen/options/min/max/step` 为 null 时省略 → 存量 meta 形状干净、前端兼容。

**兼容**：存量 3 个 agent 描述符经 `leaf(...)` 迁移后 meta 形状除省略 null 字段外与现在完全一致；`GET /meta`、`PUT/{ns}/{key}`、`GET /{ns}/{key}` 端点 URL 不变。

### D2 嵌套值 JSON 持久化（SettingsService.parse/set 升级）

- `dsh-settings/pom.xml` 加 `tools.jackson.core:jackson-databind`（Jackson 3 databind，Boot 4.1 BOM 管版本；与 dsh-github 同惯例）+ `com.fasterxml.jackson.core:jackson-annotations`（`@JsonInclude` 等注解制品，mapper 兼容读取，同 dsh-api DTO 惯例）。
- `SettingsService` 注入/持有 `ObjectMapper`：
  - `set(ns,key,value)`：`value instanceof Map || value instanceof List` → `writeValueAsString(value)` 存储；**否则仍 `String.valueOf(value)`**（存量标量零变化，存量存储文本无需迁移）。
  - `parse(text)`：trim 后以 `{` 或 `[` 开头 → `readValue(text, Object.class)`（解为 Map/List/标量混合，默认用 `LinkedHashMap` 保序）；否则走现有 bool/int/double/string 分支。
- `get/all/meta` 调用链自动获得结构化值：默认值直出（`defaults` 本存 Java 对象），覆盖值 JSON 反序列化 → `all()` 合并 → Jackson 序列化下发嵌套 JSON。**无新端点、无 wire 破坏**。
- **防御兜底**：若历史上曾把 Map/List 以 Java `toString` 存入（形如 `{a=1}` / `[a, b]`，首字符同为 `{`/`[` 但非法 JSON），`parse` 的 JSON 分支 `try/catch` 捕获解析失败 → 回落原始字符串（不炸存量、不误判为对象）。现状标量存量键不触发该分支，纯兜底。

### D3 联动 DSL（最小）

- 只做**同层兄弟键等值显隐**：`VisibleWhen(key, equals)`；求值在渲染层（`JSON.stringify(layer[key]) === JSON.stringify(equals)`，无 lodash、无引擎）。
- 不做的（文档标注 P3+）：跨层路径引用、不等/范围条件、联动重置默认值。理由：当前设置页均为单层/浅嵌套表单，静态等值联动覆盖 90% 场景；多等值（`in`）后续加 `VisibleWhen.of(...)` 重载不破坏。

### D4 wire 契约（无新增端点）

| 端点 | 变化 |
|---|---|
| `GET /api/settings/meta` | `settings[]` 递归描述符（object 带 children、array 带 items、联动带 visibleWhen，null 省略）；`values` 中嵌套键为 JSON 对象/数组 |
| `PUT /api/settings/{namespace}/{key}` | body `{value}` 已接受任意 JSON（controller 不动）；`service.set` 负责 JSON 化持久化 |
| `GET /api/settings/{namespace}/{key}` | value 返回结构化 JSON（经 parse） |

### D5 前端递归渲染（SchemaField.vue + SchemaForm.vue 改造）

- `api.ts`：`SettingType` union 扩 `'object' | 'array'`；`SettingDescriptor` 递归 interface（`children?`/`items?`/`visibleWhen?: {key,equals}|null`）；`putSetting` 签名不变。
- **新增 `SchemaField.vue`（递归字段编辑器）**，props：`field: SettingDescriptor`、`layerValue: any`（当前对象层值）→ 职责：
  - **叶子**（string/number/integer/boolean/enum）：现有 el-input/el-input-number/el-switch/el-select 映射（原样迁移）；
  - **object**：`el-collapse` 分组（标题 = label）内嵌子字段递归（相对键，`layerValue[child.key]`）；
  - **array**：行式列表——每行 = items 描述符（标量 → 行内控件；object → 行卡片内嵌递归 children），行尾[删除]，列表尾[+ 添加]（添加项 = items 默认值深拷贝，无默认则标量空/对象 `{}`）；
  - **visibleWhen**：渲染 children 时逐 child 判 `visible(child, layerValue)`，不满足 v-if 隐藏（不删值）。
- `SchemaForm.vue`：改为容器——顶层 `v-for` 每 settings 键渲染 `SchemaField`；`draft` 树（每键值可对象/数组）；**保存仍逐顶层键 PUT**（整键嵌套 JSON 提交，向后端单键契约一致）；`deepDefault(field)`（对象 → 合并 defaultValue 与 children 默认；数组 → defaultValue 深拷贝/`[]`；叶子沿用现有 defaultFor）用 `structuredClone` 防 reactive 共享引用。
- 联动等值比较一律 `JSON.stringify` 函数式，不引条件引擎、不引 lodash。
- 样式沿用现有 CSS 变量（EP 组件 + 现有 sf-* 类扩展 sf-group/sf-array/sf-row-actions）。

### D6 验证口径与红线（同 sui-7 惯例）

1. **后端**：`mvn verify` 全绿不回退（520 baseline + 新增）——新增单测：
   - SettingDescriptor：工厂树构造/type 常量/`@JsonInclude` 序列化省略 null/递归 children/items 往返；
   - SettingsService：嵌套对象与数组 `set→get→all` JSON 往返；存量标量 bool/int/double/string 不回退；JSON 解析失败兜底原文本；
   - SettingsController（MockMvc）：meta 嵌套 namespace 形状 + PUT 嵌套 body → GET 返回结构化 JSON。
2. **前端**：`vite build` 通过；grep 校验无新硬编码字段映射表。
3. **浏览器 smoke（可选但建议）**：`@Profile("settings-demo")` 的示例描述符配置（如 `example/profile`：object 含 string/integer/enum + `example/toolbox`：array 元素 object + 1 个 visibleWhen 联动 + 嵌套默认值），**默认不激活**；验证时 `--spring.profiles.active=settings-demo` 起服务 → 浏览器打开设置页确认嵌套分组/数组增删/联动显隐/PUT 后刷新仍在。
4. **文档回写**：design-schema-ui.md §6 追加 P2 落地记录；design-frontend-replication.md 内核②标注 P2 表达力扩展 ✅；本设计 §6 落地记录填充。
5. **红线**：任何一步不回退既有测试/构建；分阶段 git 提交（dsh-settings → dsh-api → dsh-web → docs）。

## 4. 落点拆解（批准后转正式 DAG）

| 步骤 | 内容 | 依赖 |
|---|---|---|
| **p2-1** | dsh-settings：SettingDescriptor 树化（+children/items/visibleWhen/type 常量/工厂/@JsonInclude）+ pom 加 jackson-databind + 树构造与序列化单测 | — |
| **p2-2** | dsh-settings：SettingsService.set/parse JSON 化（Map/List 分支 + 兜底）+ 嵌套往返单测 + 存量标量不回退单测 | p2-1 |
| **p2-3** | 调用点迁移（AgentSettingsConfig ×3 / SettingsMetaControllerTest ×2 / SettingDescriptorTest 工厂）+ SettingsController MockMvc 嵌套 meta/PUT 测试 | p2-2 |
| **p2-4** | 前端：api.ts 类型树化 + SchemaField.vue（递归/object/array/visibleWhen/深默认值）+ SchemaForm.vue 容器化改造 + vite build | p2-3（wire 定稿） |
| **p2-5** | 验证收口：mvn verify 全量 + vite build + （settings-demo profile + 浏览器 smoke）+ 文档回写 + 分阶段 git 提交 | p2-4 |

## 5. 风险与回退

| 风险 | 缓解 |
|---|---|
| record 12 参 canonical 构造破坏存量调用点 | 静态工厂一次性迁移（编译期找全 4 处），leaf 语义与现 9 参完全一致 |
| 嵌套值持久化依赖 Jackson compile 依赖 | 加 `jackson-databind`（父 BOM 管版本）；无嵌套存量键 → 无数据迁移 |
| JSON 解析失败炸存量覆盖值 | parse JSON 分支 try/catch 回落原文本 |
| 前端递归过深/性能 | 设置页规模极小（每 namespace ≤ 数十键，深度 ≤3）；仅浅层联动求值，无引擎 |
| 范围蔓延（P3 语义被顺手做） | 明确不做 path CAS/覆盖标记/redact；如需另开设计 |

## 6. 落地记录（2026-09-04，plan-bcf55baf completed）

- **p2-1** SettingDescriptor 树化：record 尾加 `children/items/visibleWhen` + `Types` 常量 + 静态工厂
  `leaf/choice/number/group/list/withVisible` + `VisibleWhen` record + `@JsonInclude(NON_NULL)`；
  pom 加 Jackson 3 databind + com.fasterxml 注解（Boot 4.1 BOM）→ SettingDescriptorTest 3→7。
- **p2-2** SettingsService JSON 化：注入 ObjectMapper；`encode` Map/List→JSON、标量 String.valueOf 不变；
  `parse` {/[ 开头反序列化 + 失败回落原文 → SettingsServiceTest 3→7（嵌套往返/存量标量/坏 JSON 兜底）。
- **p2-3** 调用点迁移：AgentSettingsConfig ×3 + SettingsMetaControllerTest ×2 → leaf 工厂（语义零变化）；
  SettingsMetaControllerTest 2→5（嵌套 meta 形状 + PUT 嵌套 → GET 结构化 JSON）；agent 两测试补 mapper。
- **p2-4** 前端：api.ts SettingType union + 递归 interface；SchemaField.vue 递归编辑器（object/array/visibleWhen/
  深默认）；SchemaForm.vue 容器化（逐键 PUT 不变）；schemaDefaults.ts → vite build 通过。
- **p2-5** 收口：mvn verify 全绿（531 + 11 = 542 tests / 0 fail）；docs 三处回写（本文件 + design-schema-ui.md §7 +
  design-frontend-replication.md）；分阶段提交 dsh-settings → dsh-agent → dsh-api → dsh-web → docs。

红线达成：mvn verify 不回退 ✅ · vite build ✅ · 端点与逐键 PUT 契约不变 ✅ · 存量标量存储零迁移 ✅ ·
grep 单源校验（SchemaForm 0 控件硬编码、无 TOOL_TITLES/SUMMARY_KEYS 残留）✅

