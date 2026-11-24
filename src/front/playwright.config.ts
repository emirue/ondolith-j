import { defineConfig, devices } from "@playwright/test";

/**
 * 실제 jar + PostgreSQL + 빌드 산출물(vite preview, /api 는 server.proxy 를 물려받아 8080)로 본다(1-C1 스펙 8.5).
 * 빌드에만 CSP <meta> 가 있으므로 모든 시험이 그 아래에서 돈다. bypassCSP 는 켜지 않는다. jar 는 8080 하나를 쓰므로 한 번에 하나씩.
 */
export default defineConfig({
  testDir: "e2e",
  workers: 1,
  fullyParallel: false,
  timeout: 120_000,
  reporter: [["list"]],
  use: { baseURL: "http://127.0.0.1:4173", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run build && npx vite preview --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    timeout: 180_000, // 기본 60000ms(playwright types/test.d.ts)는 타입 검사 + 빌드에 짧다
    reuseExistingServer: false,
  },
});
