import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// The browser only ever talks to this origin: /api is proxied to the Spring Boot
// backend (port 8080), so the session cookie that `POST /api/auth/login` sets is
// first-party and no CORS preflight is involved.
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
    watch: {
      // Editors and formatters that save through short-lived dot-directories inside
      // src/ make chokidar fail with EBUSY on Windows, which kills the dev server.
      ignored: ['**/.*.tmpdir/**', '**/*.tmp'],
    },
  },
})
