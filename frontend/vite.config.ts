import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3000,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true
      },
      '/ws': {
        target: 'ws://localhost:8081',
        ws: true
      },
      '/uploads': {
        target: 'http://localhost:8081',
        changeOrigin: true
      }
    },
    // 禁用 SPA 回退，避免模型文件请求被路由到 index.html
    middlewareMode: false,
    // 自定义中间件确保 /models 路径的请求直接访问静态文件
    fs: {
      strict: false,
      allow: ['..']
    }
  },
  build: {
    rollupOptions: {
      output: {
        entryFileNames: 'assets/[name]-[hash]-v2.js',
        chunkFileNames: 'assets/[name]-[hash]-v2.js',
        assetFileNames: (assetInfo) => {
          const info = assetInfo.name?.split('.') || []
          const ext = info[info.length - 1]
          return `assets/[name]-[hash]-v2[extname]`
        }
      }
    }
  }
})
