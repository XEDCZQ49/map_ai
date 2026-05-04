import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  envPrefix: ['VITE_', 'GAODE_'],
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  define: {
    CESIUM_BASE_URL: JSON.stringify('./src/assets/Cesium')
  }
})
