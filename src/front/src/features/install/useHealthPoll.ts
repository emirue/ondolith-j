import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { fetchHealth } from "@/shared/api/health";

export type PollStatus = "waiting" | "running" | "timedOut";

/**
 * 전환이 끝날 때까지 health 를 주기적으로 본다. 연결 실패·5xx·install 은 「아직 전환 중」.
 * 제한 시간이 지나면 timedOut 이지만 계속 보다가 running 이 오면 넘어간다. retry() 는 시간을 다시 잰다.
 */
export function useHealthPoll({ intervalMs = 1000, timeoutMs = 60_000 } = {}) {
  const [round, setRound] = useState(0);
  const [timedOutRound, setTimedOutRound] = useState(-1);
  useEffect(() => {
    const t = setTimeout(() => setTimedOutRound(round), timeoutMs);
    return () => clearTimeout(t);
  }, [round, timeoutMs]);
  const q = useQuery({
    queryKey: ["health", "poll"],
    queryFn: fetchHealth,
    retry: false,
    refetchInterval: (query) => (query.state.data?.state === "running" ? false : intervalMs),
    refetchIntervalInBackground: true,
  });
  const status: PollStatus = q.data?.state === "running" ? "running" : timedOutRound === round ? "timedOut" : "waiting";
  return { status, retry: () => setRound((r) => r + 1) };
}
