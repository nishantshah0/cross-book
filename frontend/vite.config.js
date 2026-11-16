import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
// the API the dev server proxies to; API_URL points it at an app on another port
const api = process.env.API_URL || "http://localhost:8080";
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": api,
      "/ws": { target: api.replace(/^http/, "ws"), ws: true },
    },
  },
});
