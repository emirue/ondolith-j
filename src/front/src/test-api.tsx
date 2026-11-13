import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { vi } from "vitest";

type Handler = (req: Request) => Response | Promise<Response>;

/** fetch 를 가짜로 바꾼다. 처리기가 던지면 연결 실패처럼 보인다. */
export function mockApi(handler: Handler) {
  const f = vi.fn(async (req: Request) => handler(req));
  vi.stubGlobal("fetch", f);
  return f;
}

export const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

export function renderWithQuery(ui: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}
