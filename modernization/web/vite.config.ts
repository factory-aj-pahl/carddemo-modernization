import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const api = process.env.CARDDEMO_API_URL ?? 'http://127.0.0.1:8081';
const proxy = { '/api': api, '/actuator': api };
const port = Number(process.env.CARDDEMO_WEB_PORT ?? 5173);

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port, strictPort: true, proxy },
});
