import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 构建：产物输出到 dsh-web/src/main/resources/static（进 jar，boot 依赖服务）
export default defineConfig({
  plugins: [vue()],
  base: './',
  resolve: {
    alias: {
      // 官方纯逻辑层收编（design-client-vue.md C 档 §5.1）：schemastery/schema-form
      // 为 ESM lib + external（cosmokit），浏览器包直接经 alias 解析，免 npm install。
      '@deepseek-ai/cosmokit': fileURLToPath(new URL('./vendor/@deepseek-ai/cosmokit', import.meta.url)),
      '@deepseek-ai/schemastery': fileURLToPath(new URL('./vendor/@deepseek-ai/schemastery', import.meta.url)),
      '@deepseek-ai/dsh-client-schema-form': fileURLToPath(
        new URL('./vendor/@deepseek-ai/dsh-client-schema-form', import.meta.url),
      ),
    },
  },
  build: {
    outDir: '../resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1200,
  },
})
