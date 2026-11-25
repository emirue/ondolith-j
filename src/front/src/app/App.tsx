import { useQuery } from "@tanstack/react-query";
import { lazy, Suspense, useCallback, useState } from "react";
import { Route, Routes } from "react-router";
import { fetchHealth } from "@/shared/api/health";
import { LoginPage } from "@/features/auth/LoginPage";
import { UserMenu } from "@/features/auth/UserMenu";
import { HomePage } from "@/features/home/HomePage";
import { WaitingScreen } from "@/features/install/WaitingScreen";
import { PageView } from "@/features/page/PageView";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { NoticeProvider } from "@/shared/ui/notice";
import { NotFound } from "@/shared/ui/NotFound";

type Screen = "install" | "waiting" | "home";

// 관리자 트리는 따로 받는다 — 방문자 묶음에 편집기가 없게(1-C2 스펙 8절). 배포 뒤 옛 chunk 가 404 면 오류 경계 대신 대체 화면
const AdminTree = lazy(() => import("@/features/auth/AdminGate").catch(() => ({ default: AdminLoadFailed })));

// 설치 화면도 따로 받는다 — 공개 묶음이 500 kB 경고를 넘지 않게(Task 7 Ruling).
// ponytail: 설치 chunk 를 받지 못하면 빈 화면(대체 화면 없음). 설치 중 재배포가 생기면 AdminLoadFailed 같은 대체를 둔다
const InstallPage = lazy(() => import("@/features/install/InstallPage").then((m) => ({ default: m.InstallPage })));

function AdminLoadFailed() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  return (
    <main className="mx-auto max-w-lg p-6">
      <h1 ref={heading} tabIndex={-1} className="text-2xl font-semibold outline-none">관리자 화면을 불러오지 못했습니다. 새로 고침하세요</h1>
    </main>
  );
}

/**
 * 첫 진입에서 health 로 화면을 고른다: install → 설치, running → 홈, 응답 없음(전환 중) → 대기.
 * 프런트 분기는 편의다. 막는 것은 API 다.
 */
export function App() {
  const [next, setNext] = useState<Screen | null>(null);
  const first = useQuery({ queryKey: ["health", "first"], queryFn: fetchHealth, retry: false, enabled: next === null });
  const toWaiting = useCallback(() => setNext("waiting"), []);
  const toHome = useCallback(() => setNext("home"), []);
  const screen: Screen | null =
    next ?? (first.isError ? "waiting" : first.data ? (first.data.state === "running" ? "home" : "install") : null);
  if (screen === "install") return <Suspense fallback={null}><InstallPage onSubmitted={toWaiting} /></Suspense>;
  if (screen === "waiting") return <WaitingScreen onRunning={toHome} />;
  if (screen === "home") return <Running />;
  return null;
}

/** 운영 상태의 화면. 주소로 고른다(1-A 스펙 7절). 저장 알림 칸은 머리글보다 위(FR-724). */
function Running() {
  return (
    <NoticeProvider>
      <header className="flex justify-end p-4">
        <UserMenu />
      </header>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/admin/*" element={<Suspense fallback={null}><AdminTree /></Suspense>} />
        <Route path="/:slug" element={<PageView />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </NoticeProvider>
  );
}
