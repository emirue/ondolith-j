import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// 1-C1 스펙 8.3. 빌드 산출물에만 건다 — 개발 서버는 인라인 스크립트(React Refresh)를 넣는다.
// style-src 'unsafe-inline': Radix 대화상자(스크롤 잠금)가 <style> 요소를 넣는다(1-C1 E2E 위반 보고로 확인). script-src 는 넓히지 않는다.
// frame-ancestors 는 <meta> 로 걸리지 않아 Phase 4 의 CloudFront 응답 헤더가 더한다
const CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; font-src 'self'; connect-src 'self'; "
  + "object-src 'none'; base-uri 'none'; form-action 'self'; frame-src 'none'";

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    {
      name: "csp",
      apply: "build",
      // <meta> CSP 는 그 뒤의 요소부터 걸린다 — head 맨 앞에
      transformIndexHtml: () => [{ tag: "meta", attrs: { "http-equiv": "Content-Security-Policy", content: CSP }, injectTo: "head-prepend" }],
    },
  ],
  resolve: { alias: { "@": path.resolve(import.meta.dirname, "./src") } },
  // 개발·E2E: Vite 가 화면을 내고 /api 는 jar(8080)로 넘긴다(스펙 2.5). vite preview 도 이 값을 물려받는다(preview.proxy 기본값)
  server: { proxy: { "/api": "http://127.0.0.1:8080" } },
  test: { environment: "jsdom", setupFiles: ["./src/test-setup.ts"], include: ["src/**/*.test.{ts,tsx}"] },
});
