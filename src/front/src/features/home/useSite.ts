import { useQuery } from "@tanstack/react-query";
import { api } from "@/shared/api/client";

export function useSite() {
  return useQuery({
    queryKey: ["site"],
    queryFn: async () => {
      const { data, response } = await api.GET("/api/site");
      if (!response.ok || !data) throw new Error(`site ${response.status}`);
      return data;
    },
  });
}
