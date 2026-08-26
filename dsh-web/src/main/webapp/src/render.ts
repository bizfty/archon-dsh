// 消息渲染：marked + DOMPurify + highlight.js（npm 依赖，Vite 打包进 bundle）。
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js';

/** 渲染 markdown → 净化后的 HTML（含代码高亮）。 */
export function renderMarkdown(text: string): string {
  let html: string;
  try {
    html = marked.parse(text, { async: false }) as string;
  } catch {
    html = escapeHtml(text);
  }
  try {
    html = DOMPurify.sanitize(html);
  } catch {
    html = escapeHtml(text);
  }
  // 代码块高亮：有语言标注按语言高亮；无标注用 highlightAuto 兜底，避免整块单色
  const container = document.createElement('div');
  container.innerHTML = html;
  container.querySelectorAll('pre code').forEach((el) => {
    const codeEl = el as HTMLElement;
    const lang = (codeEl.className.match(/language-([\w+-]+)/) || [])[1] || '';
    try {
      if (lang && hljs.getLanguage(lang)) {
        codeEl.innerHTML = hljs.highlight(codeEl.textContent || '', { language: lang }).value;
        codeEl.className = `hljs language-${lang}`;
      } else {
        codeEl.innerHTML = hljs.highlightAuto(codeEl.textContent || '').value;
        codeEl.className = 'hljs';
      }
    } catch {
      /* 高亮失败不阻塞 */
    }
  });
  return container.innerHTML;
}

/** 原始文本转义（工具结果等，不渲染 markdown）。 */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\"/g, '&quot;');
}

/** 折叠块 HTML（工具调用等）。 */
export function collapsible(title: string, bodyHtml: string): string {
  return `<details class="tool-details"><summary>${title}</summary><div class="tool-body">${bodyHtml}</div></details>`;
}
