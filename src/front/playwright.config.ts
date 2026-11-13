import { defineConfig, devices } from "@playwright/test";

/** 실제 jar + PostgreSQL + Vite 로 설치 흐름을 본다(스펙 5절, 필수). jar 는 8080 하나를 쓰므로 한 번에 하나씩. */
export default defineConfig({
  testDir: "e2e",
  workers: 1,
  fullyParallel: false,
  timeout: 120_000,
  reporter: [["list"]],
  use: { baseURL: "http://127.0.0.1:5173", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run dev -- --host 127.0.0.1 --port 5173 --strictPort",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: false,
  },
});
