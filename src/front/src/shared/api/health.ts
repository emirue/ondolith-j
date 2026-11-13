import { api } from "./client";

/** GET /api/health. 연결 실패·2xx 가 아니면 던진다(호출 쪽은 「아직 전환 중」으로 본다). */
export async function fetchHealth() {
  const { data, response } = await api.GET("/api/health");
  if (!response.ok || !data) throw new Error(`health ${response.status}`);
  return data;
}
