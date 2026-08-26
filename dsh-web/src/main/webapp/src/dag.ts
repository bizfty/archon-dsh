import type { TrajectoryView, TrajectoryStep } from './api';

const COLORS = {
  user: '#4f7cff',
  assistant: '#2ecc71',
  tool: '#f39c12',
  system: '#9b59b6',
  bg: '#1e1f24',
  border: '#33343d',
  text: '#e6e6eb',
};

function safeId(prefix: string, step: number): string {
  return `${prefix}_s${step}`;
}

function sanitize(label: string): string {
  return label.replace(/[^a-zA-Z0-9_\-\u4e00-\u9fff ]/g, '').slice(0, 60);
}

function nodeLabel(step: TrajectoryStep): string {
  const typeIcon = { user: '👤', assistant: '🤖', tool: '🔧', system: '⚙️' }[step.type] || '📄';
  const typeName = { user: 'User', assistant: 'Assistant', tool: step.toolName || 'Tool', system: 'System' }[step.type] || step.type;
  const content = step.content ? step.content.replace(/\s+/g, ' ').slice(0, 30) : '';
  const label = `${typeIcon} ${typeName}${content ? '\\n' + sanitize(content) : ''}`;
  return label;
}

function nodeStyle(step: TrajectoryStep): string {
  const color = COLORS[step.type] || COLORS.text;
  return `fill:${color},stroke:${COLORS.border},stroke-width:2px,color:#fff`;
}

export function generateMermaidDAG(traj: TrajectoryView): string {
  const lines: string[] = [];
  lines.push('flowchart TD');
  lines.push(`classDef userStyle fill:${COLORS.user},stroke:${COLORS.border},stroke-width:2px,color:#fff`);
  lines.push(`classDef assistantStyle fill:${COLORS.assistant},stroke:${COLORS.border},stroke-width:2px,color:#fff`);
  lines.push(`classDef toolStyle fill:${COLORS.tool},stroke:${COLORS.border},stroke-width:2px,color:#fff`);
  lines.push(`classDef systemStyle fill:${COLORS.system},stroke:${COLORS.border},stroke-width:2px,color:#fff`);

  if (traj.turns.length === 0) {
    lines.push('  empty["(暂无数据)"]');
    return lines.join('\n');
  }

  let prevTurnEnd: string | null = null;

  for (const turn of traj.turns) {
    const prefix = `T${turn.turn}`;
    lines.push(`  subgraph ${prefix}["Turn #${turn.turn}"]`);
    lines.push(`    direction TB`);

    let prevNodeId: string | null = null;
    const toolForks: string[] = [];

    for (const step of turn.steps) {
      const id = safeId(prefix, step.step);
      const label = nodeLabel(step);
      lines.push(`    ${id}["${label}"]`);
      lines.push(`    style ${id} ${nodeStyle(step)}`);

      if (step.type === 'assistant' && step.toolCalls && step.toolCalls.length > 0) {
        for (const tc of step.toolCalls) {
          const tid = `${id}_tc_${tc.id.slice(-6)}`;
          lines.push(`    ${tid}["🔧 ${sanitize(tc.name)}"]`);
          lines.push(`    style ${tid} ${nodeStyle(step)}`);
          lines.push(`    ${id} --> ${tid}`);
          toolForks.push(tid);
        }
      }

      if (prevNodeId) {
        if (toolForks.length > 0 && step.type !== 'assistant') {
          for (const fork of toolForks) {
            lines.push(`    ${fork} --> ${id}`);
          }
          toolForks.length = 0;
        } else {
          lines.push(`    ${prevNodeId} --> ${id}`);
        }
      }
      prevNodeId = id;
    }

    if (toolForks.length > 0 && prevNodeId) {
      for (const fork of toolForks) {
        lines.push(`    ${fork} --> ${prevNodeId}`);
      }
    }

    lines.push('  end');
    lines.push(`  style ${prefix} fill:${COLORS.bg},stroke:${COLORS.border},stroke-width:1px,color:${COLORS.text}`);

    if (prevTurnEnd && prevNodeId) {
      lines.push(`  ${prevTurnEnd} ==> ${safeId(prefix, turn.steps[0].step)}`);
    }
    prevTurnEnd = prevNodeId;
  }

  const firstTurn = traj.turns[0];
  if (firstTurn && firstTurn.steps.length > 0 && firstTurn.steps[0].type === 'user') {
    lines.push('  linkStyle 0 stroke:#4f7cff,stroke-width:2px,stroke-dasharray:5 5');
  }

  return lines.join('\n');
}