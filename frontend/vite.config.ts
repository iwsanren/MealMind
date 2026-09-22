import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
    watch: {
      // Bind-mounted volumes (e.g. Docker Desktop on Windows) don't propagate
      // native filesystem events, so the watcher falls back to polling.
      usePolling: process.env.CHOKIDAR_USEPOLLING === 'true',
    },
  },
})
