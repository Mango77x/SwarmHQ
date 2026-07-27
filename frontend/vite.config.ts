import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  // Served at /app by the backend (see backend/pom.xml's `frontend`
  // profile) - without this, the production build's asset paths are
  // root-relative and 404 once actually deployed under a subpath.
  base: '/app/',
  // sockjs-client (a live-updates dependency, Sprint 7) references the
  // Node global object; without this it's a ReferenceError at module
  // load that aborts the whole bundle before React ever mounts (blank
  // page, empty #root, no console error visible from outside the module).
  define: {
    global: 'globalThis',
  },
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      // ws: true handles the raw WebSocket upgrade SockJS falls back to
      // (/ws/websocket); its other transports (/ws/info, /ws/xhr_streaming,
      // ...) are plain HTTP and proxy fine without it.
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
  },
})
