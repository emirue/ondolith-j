import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter, useLocation } from "react-router";
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

/** 쿼리 클라이언트와 메모리 라우터로 감싼다. route 는 시작 주소. */
export function renderWithQuery(ui: ReactElement, route = "/") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

/** 지금 주소를 보인다(이동 시험용). */
export function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search}</output>;
}
