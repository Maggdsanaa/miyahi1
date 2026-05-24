import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': {
        target: 'https://jsonblob.com',
        changeOrigin: true,
        rewrite: (path) =>
          path.replace('/api/data', '/api/jsonBlob/019e5a6a-7eef-74ad-8540-ee66c20836d0'),
      },
    },
  },
})
