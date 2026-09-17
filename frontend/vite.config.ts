import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/gateway": "http://127.0.0.1:8080",
      "/session": "http://127.0.0.1:8080",
      "/admin-session": "http://127.0.0.1:8080",
      "/control": "http://127.0.0.1:8080",
      // The subscription link is handed to clients as an absolute address on
      // the site, so in development it points here and has to come back.
      "/sub": "http://127.0.0.1:8080",
      "/api": "http://127.0.0.1:8080"
    }
  },
  build: {
    sourcemap: false
  }
});
