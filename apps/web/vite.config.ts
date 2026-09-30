import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development the API runs on :8080 and this proxies to it, so the browser
// sees one origin: the session cookie and the CSRF cookie both just work.
// In production the API serves these files itself, for the same reason.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/v1": "http://localhost:8080" },
  },
});
