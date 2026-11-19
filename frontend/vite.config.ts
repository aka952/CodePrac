import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: { chunkSizeWarningLimit: 3500 },
  // the Compose stack's nginx, which sends each /api path to its service
  server: { port: 5173, proxy: { '/api': 'http://localhost:3000' } },
})
