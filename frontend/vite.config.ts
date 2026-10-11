import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const homeRevision = createHash("sha256")
  .update(readFileSync(new URL("./public/theme/Freedom/assets/index.html", import.meta.url)))
  .digest("hex").slice(0, 16);

export default defineConfig({
  plugins: [react()],
  define: { __FREEDOM_HOME_REVISION__: JSON.stringify(homeRevision) },
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
