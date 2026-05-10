import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig(({ mode }) => ({
  base: mode === 'production' ? '/byd-dashcast/' : '/',
  plugins: [vue()],
}));
