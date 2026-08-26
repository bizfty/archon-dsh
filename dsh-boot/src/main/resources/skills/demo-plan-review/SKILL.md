---
name: demo-plan-review
description: 计划评审技能 — 对生成的计划进行评审和优化
version: 1.0.0
tags: [plan, review]
---

# 计划评审技能

对 Agent 生成的执行计划进行评审，检查其合理性、完整性和安全性。

## 评审要点

1. **目标一致性** — 计划是否覆盖了用户的核心意图
2. **步骤合理性** — 每个步骤的前置条件和预期结果是否明确
3. **风险识别** — 是否存在潜在的失败点或不可回滚的操作
4. **资源评估** — 是否需要超出当前权限或配额的资源

## 输出格式

```yaml
verdict: approve | revise | reject
issues:
  - severity: high | medium | low
    description: 问题描述
    suggestion: 修改建议
summary: 评审总结
```