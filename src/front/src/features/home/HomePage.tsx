import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { useSite } from "./useSite";

/** 홈 임시 화면(Phase 0). 사이트 이름만 보인다. */
export function HomePage() {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const site = useSite();
  return (
    <main className="mx-auto max-w-lg p-6">
      <h1 ref={heading} tabIndex={-1} className="text-2xl font-semibold outline-none">{site.data?.name ?? ""}</h1>
      {site.isError && <p role="alert">사이트 정보를 불러오지 못했습니다</p>}
    </main>
  );
}
