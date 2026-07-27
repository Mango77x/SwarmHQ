import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  // Served at /app by the backend (see backend/pom.xml's `frontend`
  // profile) - without this, the production build's asset paths are
  // root-relative and 404 once actually deployed under a subpath.
  base: '/app/',
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
