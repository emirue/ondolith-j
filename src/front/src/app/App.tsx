import { useQuery } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { Route, Routes } from "react-router";
import { fetchHealth } from "@/shared/api/health";
import { adminRoutes } from "@/features/admin/AdminLayout";
import { AdminGate } from "@/features/auth/AdminGate";
import { LoginPage } from "@/features/auth/LoginPage";
import { UserMenu } from "@/features/auth/UserMenu";
import { HomePage } from "@/features/home/HomePage";
import { InstallPage } from "@/features/install/InstallPage";
import { WaitingScreen } from "@/features/install/WaitingScreen";

type Screen = "install" | "waiting" | "home";

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
  if (screen === "install") return <InstallPage onSubmitted={toWaiting} />;
  if (screen === "waiting") return <WaitingScreen onRunning={toHome} />;
  if (screen === "home") return <Running />;
  return null;
}

/** 운영 상태의 화면. 주소로 고른다(1-A 스펙 7절). */
function Running() {
  return (
    <>
      <header className="flex justify-end p-4">
        <UserMenu />
      </header>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/admin" element={<AdminGate />}>{adminRoutes}</Route>
        <Route path="*" element={<main className="mx-auto max-w-lg p-6"><h1 className="text-2xl font-semibold">없는 화면입니다</h1></main>} />
      </Routes>
    </>
  );
}
