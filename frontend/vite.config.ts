import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The browser calls relative /api URLs; in dev they go to the backend on the host.
    proxy: {
      '/api': `http://localhost:${process.env.BACKEND_PORT ?? 8080}`,
      // STOMP over WebSocket (real-time push).
      '/ws': { target: `ws://localhost:${process.env.BACKEND_PORT ?? 8080}`, ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
