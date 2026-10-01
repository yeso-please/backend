import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    plugins: [react()],
    define: {
      "import.meta.env.VITE_UPLOAD_LIMIT_MB": JSON.stringify(
        env.VITE_UPLOAD_LIMIT_MB || (process.env.VERCEL ? "4" : "0"),
      ),
    },
    server: {
      host: env.VITE_HOST || "127.0.0.1",
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": {
          target: env.BACKEND_URL || "http://127.0.0.1:8080",
          changeOrigin: true,
          cookieDomainRewrite: "",
          configure(proxy) {
            // Browser -> Vite is same-origin. The next hop is server-to-server;
            // do not forward the 127.0.0.1 browser Origin into Spring's CORS filter.
            proxy.on("proxyReq", (req) => req.removeHeader("origin"));
          },
        },
        "/ai-api": {
          target: env.AI_URL || "http://127.0.0.1:8000",
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/ai-api/, ""),
        },
      },
    },
    preview: { port: 4173 },
  };
});
