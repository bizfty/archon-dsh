import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 构建：产物输出到 dsh-web/src/main/resources/static（进 jar，boot 依赖服务）
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: {
    outDir: '../resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1200,
  },
})
