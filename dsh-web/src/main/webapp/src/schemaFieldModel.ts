// schemaFieldModel.ts — schema 字段渲染模型归一层（design-client-vue.md C 档 P1）。
//
// 官方 schemastery envelope（meta 端点 schema 字段，schema.toJSON() 引用图）经
// dsh-client-schema-form 的 rehydrateSchema 重建为活校验节点树后，本模块把它投影为
// 与既有 SettingDescriptor 同构的 RenderField（渲染层 SchemaField 零语义改动），
// 并保留 descriptor 直通路径（旧后端/无 envelope 时回退，字段级兼容）。
//
// 归一语义（与 Java SchemasteryEnvelope 翻译器对称）：
// - number 节点 meta.dshType==='integer' → type 'integer'（EP 整型控件）；
// - union（成员全 const）→ type 'enum'（options 取成员 value）；
// - object → children = dict entries（保序）；array → items = inner；
// - label/description/default/min/max/step 取 meta；secret = meta.role==='secret'；
//   visibleWhen = meta.visibleWhen（archon 自定义联动语义，随 meta 透传）；
// - 其余 schemastery 类型（intersect/transform/dict/tuple/any/lazy/非 const union…）
//   → type 'unknown'（渲染端降级只读，原值保留不参与编辑）。

import { nodeAtPath, rehydrateSchema } from '@deepseek-ai/dsh-client-schema-form';
import type { SchemaNode } from '@deepseek-ai/dsh-client-schema-form';
import type { SchemasteryEnvelope, SettingDescriptor, SettingVisibleWhen } from './api';

export type RenderType = SettingDescriptor['type'] | 'unknown';

/** 渲染字段：与 SettingDescriptor 结构同构（SchemaField 直接消费）。 */
export interface RenderField {
  key: string;
  type: RenderType;
  label: string | null;
  description: string | null;
  defaultValue: unknown;
  options: string[] | null;
  min: number | null;
  max: number | null;
  step: number | null;
  /** type=object 时的子字段（保序，递归）。 */
  children?: RenderField[];
  /** type=array 时的元素字段（标量或 object）。 */
  items?: RenderField | null;
  /** 联动显隐（同层兄弟键等值）。 */
  visibleWhen?: SettingVisibleWhen | null;
  /** true=write-only secret（值不回显，path op 提交）。 */
  secret?: boolean | null;
}

const metaOf = (node: SchemaNode): Record<string, unknown> =>
  (node.meta ?? {}) as unknown as Record<string, unknown>;

function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

function str(v: unknown): string | null {
  return typeof v === 'string' ? v : null;
}

/** object 的默认空容器：meta.default 或 {}。 */
function fallbackDefault(type: RenderType, metaDefault: unknown, key: string, children?: RenderField[]): unknown {
  if (metaDefault !== undefined && metaDefault !== null) return metaDefault;
  if (type === 'boolean') return false;
  if (type === 'number' || type === 'integer') return 0;
  if (type === 'array') return [];
  if (type === 'object') {
    const out: Record<string, unknown> = {};
    for (const c of children ?? []) out[c.key] = c.defaultValue ?? '';
    return out;
  }
  if (type === 'enum') return undefined; // 无默认时由 options[0] 由 SchemaForm sync 兜底
  return '';
}

function renderNode(key: string, node: SchemaNode): RenderField {
  const meta = metaOf(node);
  const label = str(meta.label);
  const description = str(meta.description);
  const metaDefault = meta.default;
  const secret = meta.role === 'secret' || undefined;
  const visibleWhen = meta.visibleWhen as SettingVisibleWhen | null | undefined;
  const common = { key, label, description, visibleWhen: visibleWhen ?? null, secret };

  switch (node.type) {
    case 'object': {
      const children = Object.entries(node.dict ?? {}).map(([k, c]) => renderNode(k, c));
      return {
        ...common,
        type: 'object',
        defaultValue: fallbackDefault('object', metaDefault, key, children),
        options: null,
        min: null,
        max: null,
        step: null,
        children,
      };
    }
    case 'array': {
      const inner = node.inner;
      return {
        ...common,
        type: 'array',
        defaultValue: fallbackDefault('array', metaDefault, key),
        options: null,
        min: null,
        max: null,
        step: null,
        items: inner ? renderNode('', inner) : null,
      };
    }
    case 'number': {
      const integer = meta.dshType === 'integer';
      return {
        ...common,
        type: integer ? 'integer' : 'number',
        defaultValue: fallbackDefault(integer ? 'integer' : 'number', metaDefault, key),
        options: null,
        min: num(meta.min),
        max: num(meta.max),
        step: num(meta.step),
      };
    }
    case 'string':
    case 'boolean':
      return {
        ...common,
        type: node.type,
        defaultValue: fallbackDefault(node.type, metaDefault, key),
        options: null,
        min: null,
        max: null,
        step: null,
      };
    case 'union': {
      // enum 表达：union 成员全 const 字符串 → select；否则整节点降级只读
      const options: string[] = [];
      for (const member of node.list ?? []) {
        if (member.type === 'const' && typeof member.value === 'string') {
          options.push(member.value);
        } else {
          return { ...common, type: 'unknown', defaultValue: metaDefault, options: null, min: null, max: null, step: null };
        }
      }
      const enumDefault = metaDefault !== undefined && metaDefault !== null ? metaDefault : options[0];
      return {
        ...common,
        type: 'enum',
        defaultValue: enumDefault,
        options: options.length > 0 ? options : null,
        min: null,
        max: null,
        step: null,
      };
    }
    default:
      // const/intersect/transform/dict/tuple/any/lazy/never 等：只读降级
      return { ...common, type: 'unknown', defaultValue: metaDefault, options: null, min: null, max: null, step: null };
  }
}

/** 直通路径：旧后端 SettingDescriptor（无 envelope 字段时回退，字段级兼容）。 */
export function renderFromDescriptor(descriptor: SettingDescriptor): RenderField {
  const { type, ...rest } = descriptor;
  return { ...rest, type: type as RenderType };
}

/** 从 view.schema envelope 重建 object 根（失败/非 object 返回 null → 调用方回退）。 */
export function rootNodeFromEnvelope(schema?: SchemasteryEnvelope | null): SchemaNode | null {
  if (!schema) return null;
  try {
    const root = rehydrateSchema(schema);
    return root && root.type === 'object' ? root : null;
  } catch {
    return null;
  }
}

/** 顶层字段列表：优先官方 envelope 树；无则按描述符直通。 */
export function topRenderFields(
  settings: SettingDescriptor[] | undefined,
  schemaRoot: SchemaNode | null,
): RenderField[] {
  if (schemaRoot) {
    const dict = schemaRoot.dict ?? {};
    const out: RenderField[] = [];
    for (const key of Object.keys(dict)) {
      const field = renderFromPath(schemaRoot, key);
      if (field) out.push(field);
    }
    if (out.length > 0 || Object.keys(dict).length === 0) return out;
  }
  return (settings ?? []).map(renderFromDescriptor);
}

/** 官方路径寻址渲染（root object → nodeAtPath([key]) → 子树）。 */
export function renderFromPath(root: SchemaNode, key: string): RenderField | null {
  const node = nodeAtPath(root, [key]);
  if (!node) return null;
  return renderNode(key, node);
}
