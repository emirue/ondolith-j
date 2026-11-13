import { useEffect } from "react";
import { Button } from "@/shared/ui/button";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { useHealthPoll } from "./useHealthPoll";

const TITLE = "사이트를 준비하고 있습니다";

/** 설치 뒤 운영 모드로 바뀌는 몇 초 동안 보이는 화면(스펙 2.5). */
export function WaitingScreen({ onRunning, intervalMs, timeoutMs }: { onRunning: () => void; intervalMs?: number; timeoutMs?: number }) {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const { status, retry } = useHealthPoll({ intervalMs, timeoutMs });
  useEffect(() => {
    if (status === "running") onRunning();
  }, [status, onRunning]);
  return (
    <main className="mx-auto max-w-lg p-6">
      <h1 ref={heading} tabIndex={-1} className="mb-4 text-2xl font-semibold outline-none">{TITLE}</h1>
      <p role="status" aria-live="polite">{status === "timedOut" ? "오래 걸립니다. 서버 로그를 확인하세요" : TITLE}</p>
      {status === "timedOut" && (
        <Button
          className="mt-4"
          onClick={() => {
            retry();
            heading.current?.focus(); // 버튼이 사라지면 포커스가 body 로 가지 않게
          }}
        >
          다시 시도
        </Button>
      )}
    </main>
  );
}
