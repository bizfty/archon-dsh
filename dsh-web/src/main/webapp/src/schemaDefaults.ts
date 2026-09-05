// schemaDefaults.ts — schema 驱动表单的默认值/深比较工具（P2）。
// 服务端下发 descriptor（object→children 递归 / array→items / visibleWhen），
// 前端据此构造草稿默认树与联动求值；后端 defaultValue 可能是对象/数组实例，
// 一律深拷贝防跨行/跨字段共享引用。

import type { RenderField } from './schemaFieldModel';

/** 深拷贝（structuredClone 可用时；标量原样返回）。 */
function clone(v: unknown): unknown {
  if (v !== null && typeof v === 'object' && typeof structuredClone === 'function') {
    return structuredClone(v);
  }
  return v;
}

/** 字段默认值：显式 defaultValue 优先（深拷贝）；否则按 type 语义兜底。 */
export function defaultValue(f: RenderField): unknown {
  if (f.defaultValue !== undefined && f.defaultValue !== null) {
    return clone(f.defaultValue);
  }
  switch (f.type) {
    case 'boolean':
      return false;
    case 'number':
    case 'integer':
      return 0;
    case 'object':
      return objectDefault(f);
    case 'array':
      return [];
    case 'enum':
      return f.options && f.options.length > 0 ? f.options[0] : '';
    default:
      return '';
  }
}

/** object 字段默认对象：children 逐个取默认值（含嵌套 object/array）。 */
export function objectDefault(f: RenderField): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const c of f.children ?? []) {
    out[c.key] = defaultValue(c);
  }
  return out;
}

/** 深等值（联动求值：可见条件 key 当前值 vs equals；支持嵌套 Map/数组/标量）。 */
export function deepEquals(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a)) {
    const aa = a as unknown[];
    const bb = b as unknown[];
    if (aa.length !== bb.length) return false;
    return aa.every((x, i) => deepEquals(x, bb[i]));
  }
  const ka = Object.keys(a as object);
  const kb = Object.keys(b as object);
  if (ka.length !== kb.length) return false;
  return ka.every((k) => deepEquals((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]));
}
