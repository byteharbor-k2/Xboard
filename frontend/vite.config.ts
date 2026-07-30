import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/gateway": "http://127.0.0.1:8080",
      "/session": "http://127.0.0.1:8080",
      "/admin-session": "http://127.0.0.1:8080"
    }
  },
  build: {
    sourcemap: false
  }
});
