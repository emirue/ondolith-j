import { test as base } from "@playwright/test";
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { existsSync, mkdtempSync, readdirSync, rmSync } from "node:fs";
import { createConnection } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";

/** 시험마다 새로 띄우는 것: PostgreSQL 컨테이너, 빈 작업 폴더, 그 폴더에서 띄운 실제 jar(설정 파일 없음 → 설치 모드). */
export type Stack = { dbHost: string; dbPort: number; dbUser: string; dbPassword: string; dbName: string };

const BACK = path.resolve(import.meta.dirname, "../../back");
const DB_PASS = "e2e-pass-not-real";

function jarPath(): string {
  const dir = path.join(BACK, "target");
  const jar = existsSync(dir) ? readdirSync(dir).find((f) => /^ondolith-j-.*\.jar$/.test(f) && !f.endsWith("-plain.jar")) : undefined;
  if (!jar) throw new Error(`jar 가 없다: ${dir}. 먼저 src/back 에서 ./mvnw -q -B -DskipTests package`);
  return path.join(dir, jar);
}

const docker = (...args: string[]) => execFileSync("docker", args, { encoding: "utf8" }).trim();

async function until(what: string, seconds: number, check: () => Promise<boolean> | boolean) {
  for (let i = 0; i < seconds * 2; i++) {
    try {
      if (await check()) return;
    } catch {
      // 아직
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`${seconds}초 안에 ${what} 되지 않음`);
}

const portOpen = (port: number) =>
  new Promise<boolean>((resolve) => {
    const s = createConnection({ host: "127.0.0.1", port }, () => {
      s.destroy();
      resolve(true);
    });
    s.on("error", () => resolve(false));
  });

export const test = base.extend<{ stack: Stack }>({
  // eslint-disable-next-line no-empty-pattern
  stack: async ({}, use, info) => {
    const jar = jarPath();
    if (await portOpen(8080)) throw new Error("127.0.0.1:8080 을 이미 누가 쓰고 있다");
    const name = `ondolith-e2e-db-${process.pid}-${info.workerIndex}-${Date.now()}`;
    const work = mkdtempSync(path.join(tmpdir(), "ondolith-e2e-"));
    let app: ChildProcess | undefined;
    try {
      docker("run", "-d", "--rm", "--name", name, "-p", "127.0.0.1::5432", "-e", "POSTGRES_USER=app",
        "-e", `POSTGRES_PASSWORD=${DB_PASS}`, "-e", "POSTGRES_DB=app", "postgres:18-alpine");
      const dbPort = Number(docker("port", name, "5432/tcp").split(":").pop());
      await until("DB 가 준비", 60, () => {
        docker("exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "app");
        return true;
      });
      app = spawn("java", ["-jar", jar, "--server.port=8080", "--server.address=127.0.0.1"], { cwd: work, stdio: "ignore" });
      await until("jar 가 health 에 응답", 90, async () => (await fetch("http://127.0.0.1:8080/api/health")).ok);
      await use({ dbHost: "127.0.0.1", dbPort, dbUser: "app", dbPassword: DB_PASS, dbName: "app" });
    } finally {
      if (app && app.exitCode === null) {
        const exited = new Promise((r) => app!.once("exit", r));
        app.kill("SIGTERM");
        await exited;
      }
      try {
        docker("rm", "-f", name);
      } catch {
        // 이미 없음
      }
      rmSync(work, { recursive: true, force: true });
    }
  },
});

export { expect } from "@playwright/test";
