import { expect as baseExpect, test as base, type BrowserContext, type Page } from "@playwright/test";
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readdirSync, rmSync } from "node:fs";
import { createConnection } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";

/**
 * 시험마다 새로 띄우는 것: PostgreSQL 컨테이너, 빈 작업 폴더, 그 폴더에서 띄운 실제 jar(설정 파일 없음 → 설치 모드).
 * sql 은 그 DB 에 문장 하나를 돌리고 psql 출력(-tA)을 돌려준다.
 */
export type Stack = { dbHost: string; dbPort: number; dbUser: string; dbPassword: string; dbName: string; sql(statement: string): string };

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

export type CspViolation = { directive: string; blocked: string; url: string };
export type Watch = { violations: CspViolation[]; dialogs: string[]; allow(match: (v: CspViolation) => boolean): void; check(): void };

/**
 * 문맥의 모든 페이지에서 CSP 위반 이벤트(securitypolicyviolation)와 대화상자를 모은다(1-C1 스펙 8.5).
 * 콘솔 오류는 쓰지 않는다 — 요청 실패가 섞인다. check() 는 allow 로 받지 않은 위반 0건과 대화상자 0건을 단언한다.
 */
export async function watch(target: BrowserContext): Promise<Watch> {
  const violations: CspViolation[] = [];
  const dialogs: string[] = [];
  const allowed: ((v: CspViolation) => boolean)[] = [];
  await target.exposeFunction("__cspViolation", (v: CspViolation) => void violations.push(v));
  await target.addInitScript(() =>
    document.addEventListener("securitypolicyviolation", (e) =>
      Reflect.get(window, "__cspViolation")({ directive: e.effectiveDirective, blocked: e.blockedURI, url: location.href })));
  const onDialog = (p: Page) => p.on("dialog", (d) => {
    dialogs.push(d.message());
    d.dismiss().catch(() => {}); // 페이지가 닫히는 중이면 거부된다
  });
  target.pages().forEach(onDialog);
  target.on("page", onDialog);
  return {
    violations,
    dialogs,
    allow: (match) => void allowed.push(match),
    check() {
      baseExpect(violations.filter((v) => !allowed.some((m) => m(v))), "받지 않은 CSP 위반").toEqual([]);
      baseExpect(dialogs, "대화상자").toEqual([]);
    },
  };
}

export const test = base.extend<{ stack: Stack; guard: Watch }>({
  // 모든 시험: 기본 문맥의 CSP 위반·대화상자가 끝에 0건(예외는 시험이 guard.allow 로 적는다). 새 문맥은 watch 를 직접 부른다
  guard: [async ({ context }, use) => {
    const w = await watch(context);
    await use(w);
    w.check();
  }, { auto: true }],
  // eslint-disable-next-line no-empty-pattern
  stack: async ({}, use, info) => {
    const jar = jarPath();
    if (await portOpen(8080)) throw new Error("127.0.0.1:8080 을 이미 누가 쓰고 있다");
    const name = `ondolith-e2e-db-${process.pid}-${info.workerIndex}-${Date.now()}`;
    const work = mkdtempSync(path.join(tmpdir(), "ondolith-e2e-"));
    // jar 출력은 test-results/ 아래 파일로 남긴다(CI 는 실패하면 올린다). 경로는 콘솔에 찍지 않는다
    const log = info.outputPath("jar.log");
    mkdirSync(path.dirname(log), { recursive: true });
    const logFd = openSync(log, "w");
    let app: ChildProcess | undefined;
    try {
      docker("run", "-d", "--rm", "--name", name, "-p", "127.0.0.1::5432", "-e", "POSTGRES_USER=app",
        "-e", `POSTGRES_PASSWORD=${DB_PASS}`, "-e", "POSTGRES_DB=app", "postgres:18-alpine");
      const dbPort = Number(docker("port", name, "5432/tcp").split(":").pop());
      await until("DB 가 준비", 60, () => {
        docker("exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "app");
        return true;
      });
      // 기동 정보 줄은 jar 절대 경로와 OS 사용자 이름을 찍는다 — 로그 파일에 홈 경로가 남지 않게 끈다
      app = spawn("java", ["-jar", jar, "--server.port=8080", "--server.address=127.0.0.1", "--spring.main.log-startup-info=false"],
        { cwd: work, stdio: ["ignore", logFd, logFd] });
      await until("jar 가 health 에 응답", 90, async () => (await fetch("http://127.0.0.1:8080/api/health")).ok);
      await use({
        dbHost: "127.0.0.1", dbPort, dbUser: "app", dbPassword: DB_PASS, dbName: "app",
        sql: (statement) => docker("exec", name, "psql", "-U", "app", "-d", "app", "-tAc", statement),
      });
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
      closeSync(logFd);
    }
  },
});

export { expect } from "@playwright/test";

export const ADMIN_EMAIL = "admin@ondolith.test";
export const ADMIN_PASSWORD = "e2e-admin-pass";

/** 설치 폼을 마우스로 채워 설치를 끝내고 홈(사이트 이름)까지 기다린다. */
export async function installSite(page: Page, stack: Stack, site = "E2E 사이트") {
  await page.goto("/");
  await page.getByLabel("DB 호스트").fill(stack.dbHost);
  await page.getByLabel("DB 포트").fill(String(stack.dbPort));
  await page.getByLabel("DB 사용자").fill(stack.dbUser);
  await page.getByLabel("DB 비밀번호").fill(stack.dbPassword);
  await page.getByLabel("DB 이름").fill(stack.dbName);
  await page.getByLabel("SSL 모드").selectOption("disable");
  await page.getByLabel("사이트 이름").fill(site);
  await page.getByLabel("관리자 이메일").fill(ADMIN_EMAIL);
  await page.getByLabel("관리자 비밀번호").fill(ADMIN_PASSWORD);
  await page.getByLabel("비밀번호 확인").fill(ADMIN_PASSWORD);
  await page.getByRole("button", { name: "설치" }).click();
  await baseExpect(page.getByRole("heading", { name: site })).toBeVisible({ timeout: 60_000 });
}

/** `/login?next=/admin` 에서 관리자(또는 준 계정)로 로그인하고 「관리자」 제목까지 기다린다. */
export async function loginAdmin(page: Page, email = ADMIN_EMAIL, password = ADMIN_PASSWORD) {
  await page.goto("/login?next=/admin");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
  await baseExpect(page.getByRole("heading", { name: "관리자", exact: true })).toBeVisible();
}
