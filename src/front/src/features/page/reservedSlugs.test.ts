import { readFileSync } from "node:fs";
import path from "node:path";
import { expect, it } from "vitest";

const read = (p: string) => readFileSync(path.resolve(import.meta.dirname, p), "utf8");

// 1-C1 스펙 5.1: 프런트 최상위 정적 라우트와 빌드 자산 폴더는 슬러그가 될 수 없다
it("App_의_최상위_정적_라우트와_assetsDir_가_예약어에_있다", () => {
  const reserved = read("../../../../back/src/main/resources/reserved-slugs.txt")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith("#"));
  const routes = [...read("../../app/App.tsx").matchAll(/<Route path="\/([a-z][a-z0-9-]*)(?:\/\*)?"/g)].map((m) => m[1]!);
  const assetsDir = read("../../../vite.config.ts").match(/assetsDir:\s*"([^"]+)"/)?.[1] ?? "assets";
  expect(routes).toEqual(expect.arrayContaining(["login", "admin"]));
  for (const name of [...routes, assetsDir]) expect(reserved, name).toContain(name);
});
