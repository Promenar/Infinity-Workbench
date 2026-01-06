import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        entryFileNames: `assets/[name].js.bundle`,
        chunkFileNames: `assets/[name].js.bundle`,
        assetFileNames: `assets/[name].[ext].bundle`
      }
    }
  }
})
