import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router";
import { api } from "@/shared/api/client";

/** GET /api/me — 화면 표시용 로그인 상태. 보안 판정은 서버가 한다. */
export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: async () => {
      const { data, response } = await api.GET("/api/me");
      if (!response.ok || !data) throw new Error(`me ${response.status}`);
      return data;
    },
  });
}

/** 로그아웃하고 로그인 상태를 잊은 뒤 홈으로. */
export function useLogout() {
  const queries = useQueryClient();
  const navigate = useNavigate();
  return async () => {
    await api.POST("/api/auth/logout");
    queries.removeQueries({ queryKey: ["me"] });
    queries.removeQueries({ queryKey: ["admin"] });
    navigate("/", { replace: true });
  };
}
