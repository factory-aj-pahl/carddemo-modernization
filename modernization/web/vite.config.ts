import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const api = process.env.CARDDEMO_API_URL ?? 'http://127.0.0.1:8081';
const proxy = { '/api': api, '/actuator': api };

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
});
