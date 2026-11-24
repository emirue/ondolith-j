import { useQuery } from "@tanstack/react-query";
import { useParams } from "react-router";
import { useSite } from "@/features/home/useSite";
import { api } from "@/shared/api/client";
import { LoadError } from "@/shared/api/load";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { NotFound } from "@/shared/ui/NotFound";
import { RichHtml } from "@/shared/ui/RichHtml";

// 서버 Slug.FORMAT 과 같다(1-C1 스펙 5.1). 맞는 값만 경로에 들어가므로 인코딩할 글자가 없다
const SLUG = /^[a-z0-9][a-z0-9-]*$/;

/** /:slug — 발행된 페이지(P-202). 형식이 틀리면 요청하지 않는다. */
export function PageView() {
  const { slug = "" } = useParams();
  if (slug.length > 100 || !SLUG.test(slug)) return <NotFound />;
  return <Page slug={slug} />;
}

function Page({ slug }: { slug: string }) {
  const site = useSite();
  const page = useQuery({
    queryKey: ["page", slug],
    queryFn: async () => {
      const { data, response } = await api.GET("/api/pages/{slug}", { params: { path: { slug } } });
      if (response.status === 404) return null;
      if (!response.ok || !data) throw new LoadError(response.status);
      return data;
    },
  });
  const heading = useFocusOnMount<HTMLHeadingElement>(page.data);
  if (page.data === null) return <NotFound />;
  // 고정 문구만 — 요청 경로·슬러그·서버 오류를 되비추지 않는다(NFR-210)
  if (page.isError) return <main className="mx-auto max-w-lg p-6"><p role="alert">페이지를 불러오지 못했습니다</p></main>;
  if (!page.data) return null;
  const title = page.data.title ?? "";
  return (
    <main className="mx-auto max-w-2xl p-6">
      <title>{site.data ? `${title} – ${site.data.name}` : title}</title>
      <h1 ref={heading} tabIndex={-1} className="mb-4 text-2xl font-semibold outline-none">{title}</h1>
      <RichHtml html={page.data.body_html ?? ""} />
    </main>
  );
}
