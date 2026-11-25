import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { useForm, type FieldPath } from "react-hook-form";
import { useNavigate, useParams } from "react-router";
import { z } from "zod";
import { useMe } from "@/features/auth/useMe";
import { api } from "@/shared/api/client";
import { LoadError, LoadFailure } from "@/shared/api/load";
import type { components } from "@/shared/api/schema";
import { SaveAlert, useSave } from "@/shared/api/useSave";
import { nameSchema } from "@/shared/lib/siteName";
import { useFocusOnMount } from "@/shared/lib/useFocusOnMount";
import { Button } from "@/shared/ui/button";
import { ConfirmButton } from "@/shared/ui/confirm";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";
import { RichHtml } from "@/shared/ui/RichHtml";
import { RichEditor, useRichEditor } from "./RichEditor";

// 서버 Slug.error 와 같은 규칙·문구. 예약어는 서버 422 가 알린다(목록을 프런트에 두지 않는다)
const pageSchema = z.object({
  title: nameSchema(200),
  slug: z.string().min(1, "필수입니다").max(100, "100자 이하").regex(/^[a-z0-9][a-z0-9-]*$/, "소문자·숫자·하이픈만 쓸 수 있습니다"),
});

type PageValues = z.infer<typeof pageSchema>;
type PageDetail = components["schemas"]["PageDetail"];
/** 상세·목록을 다시 읽는다. 내 동작의 send 가 기다린다 — 다시 읽는 동안은 그 단추의 pending 이 막는다. */
type Refresh = () => Promise<unknown>;

/**
 * /admin/pages/new(A-302 작성)와 /admin/pages/:id(A-302 수정 + A-303 단추).
 * 두 라우트가 같은 부품이라 id 마다 새로 마운트한다 — 작성 201 뒤 옮겨도 작성 때의 dirty·편집기가 남지 않게.
 */
export function PageEditPage() {
  const { id } = useParams();
  return <PageEdit key={id ?? "new"} id={id} />;
}

/**
 * 본문은 편집기가 쥔다 — 폼 상태에 사본을 두지 않는다(스펙 3.4).
 * 수정이면 「지금 폼에 실린 응답」(loaded)이 상태·단추·미리보기·발행의 updated_at 을 모두 정한다. 상세 응답의 updated_at 이 바뀌면
 * 그 응답으로 다시 채우되 고치는 중(dirty)이면 채우지 않는다(스펙 7.3, I-2). 상세는 창 초점으로 다시 읽지 않는다 — 다시 읽기는 내 동작 뒤에만.
 * 저장할 수 없으면(page.update 없음, 또는 발행본인데 page.publish 없음) 편집기 대신 RichHtml 로 그린다(16절 2).
 */
function PageEdit({ id }: { id?: string }) {
  const heading = useFocusOnMount<HTMLHeadingElement>();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const can = useMe().data?.can;
  const detail = useQuery({
    queryKey: ["admin", "page", id],
    enabled: id !== undefined,
    // 떠나면 지운다 — 로그아웃 뒤 같은 탭의 다른 계정에게 앞 사람의 초안이 응답 전에 보이지 않게
    gcTime: 0,
    // 다른 탭에 다녀온 사이(또는 네트워크가 다시 이어진 사이) 남이 고친 본문이 알림 없이 실리면 새 updated_at 으로 발행이 409 를 비켜 간다
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    queryFn: async () => {
      const { data, response } = await api.GET("/api/admin/pages/{id}", { params: { path: { id: id ?? "" } } });
      if (!response.ok || !data) throw new LoadError(response.status);
      return data;
    },
  });
  const [loaded, setLoaded] = useState<PageDetail | null>(null);
  const [touched, setTouched] = useState(false);
  const editor = useRichEditor("", () => setTouched(true));
  const form = useForm<PageValues>({ resolver: zodResolver(pageSchema), defaultValues: { title: "", slug: "" } });
  // isDirty 는 렌더에서 읽어야 react-hook-form 이 구독한다
  const dirty = form.formState.isDirty || touched;

  // 다시 채우기. dirty 가 풀릴 때도 다시 본다 — 저장의 새 응답은 dirty 가 켜진 동안 온다
  const data = detail.data;
  useEffect(() => {
    if (!data || !editor || dirty || data.updated_at === loaded?.updated_at) return;
    setLoaded(data);
    form.reset({ title: data.title ?? "", slug: data.slug ?? "" });
    // emitUpdate: false — 싣기는 고침이 아니다(onUpdate 를 내지 않아 dirty 가 켜지지 않는다, @tiptap/core setContent.ts)
    editor.commands.setContent(data.body_html ?? "", { emitUpdate: false });
  }, [data, editor, dirty]);

  // 발행·발행 취소가 성공하면 연 단추가 다른 단추로 바뀌어 초점 돌릴 곳이 없다 — 다시 채운 화면을 그린 뒤 h2 로(UserDetailPage 와 같다)
  const lost = useRef(false);
  useEffect(() => {
    if (!lost.current) return;
    lost.current = false;
    heading.current?.focus();
  }, [loaded]);

  const refresh: Refresh = () =>
    Promise.all([queries.invalidateQueries({ queryKey: ["admin", "page", id] }), queries.invalidateQueries({ queryKey: ["admin", "pages"] })]);

  const save = useSave<PageValues, components["schemas"]["Created"]>({
    send: async (values) => {
      const body = { ...values, body_html: editor?.getHTML() ?? "" };
      if (id === undefined) return api.POST("/api/admin/pages", { body });
      const r = await api.POST("/api/admin/pages/{id}", { params: { path: { id } }, body });
      if (r.response.ok) await refresh();
      return r;
    },
    success: id === undefined ? "페이지를 만들었습니다" : "저장했습니다",
    noticePath: id === undefined ? (d) => `/admin/pages/${d?.id}` : undefined,
    onSuccess: (d) => {
      if (id !== undefined) {
        // 새 응답은 이미 왔다 — dirty 를 지우면 다시 채우기가 그 응답으로 채운다
        setTouched(false);
        form.reset(form.getValues());
        return;
      }
      queries.invalidateQueries({ queryKey: ["admin", "pages"] });
      navigate(`/admin/pages/${d?.id}`);
    },
    focus: (field) => (field === "body_html" ? editor?.commands.focus() : form.setFocus(field as FieldPath<PageValues>)),
  });

  const title = id === undefined ? "새 페이지" : "페이지 편집";
  const head = <h2 ref={heading} tabIndex={-1} className="mb-4 text-xl font-semibold outline-none">{title}</h2>;
  if (id !== undefined && !loaded) {
    return (
      <>
        {head}
        {detail.isError && <LoadFailure error={detail.error} failed="페이지를 불러오지 못했습니다" />}
      </>
    );
  }

  const published = loaded?.status === "published";
  const publisher = can?.["page.publish"] === true;
  const canSave = id === undefined || (can?.["page.update"] === true && (!published || publisher));
  const errors = form.formState.errors;
  const field = (name: FieldPath<PageValues>, label: string) => {
    const error = errors[name]?.message ?? save.fieldErrors[name];
    return (
      <div className="space-y-1">
        <Label htmlFor={name}>{label}</Label>
        <Input id={name} autoComplete="off" aria-invalid={error ? true : undefined} aria-describedby={error ? `${name}-error` : undefined} {...form.register(name)} />
        {error && <p id={`${name}-error`} className="text-sm text-destructive">{error}</p>}
      </div>
    );
  };
  return (
    <>
      {head}
      {loaded && (
        <dl className="mb-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          {!canSave && (
            <>
              <dt>제목</dt>
              <dd>{loaded.title}</dd>
              <dt>주소</dt>
              <dd>/{loaded.slug}</dd>
            </>
          )}
          <dt>상태</dt>
          <dd>{published ? "발행" : "초안"}</dd>
        </dl>
      )}
      {canSave ? (
        <form onSubmit={form.handleSubmit(save.save)} noValidate className="max-w-2xl space-y-4">
          <SaveAlert failure={save.failure} />
          {field("title", "제목")}
          {field("slug", "주소")}
          <RichEditor editor={editor} label="본문" labelId="body_html-label" error={save.fieldErrors.body_html} errorId="body_html-error" />
          <Button type="submit" aria-disabled={save.pending || undefined} className="aria-disabled:opacity-50">
            {save.pending ? "처리 중…" : id === undefined ? "만들기" : "저장"}
          </Button>
        </form>
      ) : (
        <>
          {published && can?.["page.update"] === true && <p className="my-4 text-sm">발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다</p>}
          <RichHtml html={loaded?.body_html ?? ""} />
        </>
      )}
      {/* 확인 단추는 폼 밖 — 대화상자의 submit 이 React 트리를 따라 페이지 폼까지 올라가지 않게 */}
      {id !== undefined && loaded && (
        <div className="mt-6 flex flex-wrap items-start gap-2">
          {publisher && !published && <PublishButton id={id} page={loaded} dirty={dirty} refresh={refresh} done={() => (lost.current = true)} />}
          {publisher && published && <UnpublishButton id={id} refresh={refresh} done={() => (lost.current = true)} />}
          {can?.["page.delete"] === true && (!published || publisher ? <DeleteButton id={id} published={published} /> : <p className="text-sm">발행을 취소한 뒤 지울 수 있습니다</p>)}
        </div>
      )}
    </>
  );
}

/**
 * 「발행」. 미리보기와 싣는 updated_at 은 같은 응답(page = 지금 폼에 실린 응답)이다 — 문자열 그대로, Date 로 바꾸지 않는다.
 * 고치는 중이면 대화상자 대신 막힌 단추 — 화면과 다른 저장본이 나가지 않게. 고치는 중에는 대화상자가 열려 있을 수 없어 바꿔 그려도 잃는 것이 없다.
 * 409 면 다시 읽기를 기다린 뒤 끝난다 — 이 단추는 그대로 남아 409 알림과 초점이 돌아갈 Trigger 가 함께 남는다.
 */
function PublishButton({ id, page, dirty, refresh, done }: { id: string; page: PageDetail; dirty: boolean; refresh: Refresh; done: () => void }) {
  const hint = useId();
  const save = useSave({
    send: async (body: components["schemas"]["PublishInput"]) => {
      const r = await api.POST("/api/admin/pages/{id}/publish", { params: { path: { id } }, body });
      if (r.response.ok || r.response.status === 409) await refresh();
      return r;
    },
    success: "발행했습니다",
    onSuccess: done,
  });
  if (dirty) {
    return (
      <div>
        <Button type="button" aria-disabled="true" aria-describedby={hint} className="aria-disabled:opacity-50">발행</Button>
        <p id={hint} className="mt-1 text-sm">저장하지 않은 수정이 있습니다</p>
      </div>
    );
  }
  return (
    <ConfirmButton label="발행" title="발행할까요?" description={`방문자가 /${page.slug} 에서 이 내용을 봅니다`} ask save={save} values={{ updated_at: page.updated_at }}>
      <div tabIndex={0} aria-label="발행할 본문" className="max-h-64 overflow-y-auto rounded-md border p-3">
        <RichHtml html={page.body_html ?? ""} />
      </div>
    </ConfirmButton>
  );
}

function UnpublishButton({ id, refresh, done }: { id: string; refresh: Refresh; done: () => void }) {
  const save = useSave({
    send: async () => {
      const r = await api.POST("/api/admin/pages/{id}/unpublish", { params: { path: { id } } });
      if (r.response.ok) await refresh();
      return r;
    },
    success: "발행을 취소했습니다",
    onSuccess: done,
  });
  return <ConfirmButton label="발행 취소" title="발행을 취소할까요?" description="방문자가 이 주소에서 「찾을 수 없습니다」를 봅니다" ask save={save} values={null} />;
}

function DeleteButton({ id, published }: { id: string; published: boolean }) {
  const navigate = useNavigate();
  const queries = useQueryClient();
  const save = useSave({
    send: () => api.POST("/api/admin/pages/{id}/delete", { params: { path: { id } } }),
    success: "페이지를 지웠습니다",
    noticePath: () => "/admin/pages",
    onSuccess: () => {
      queries.invalidateQueries({ queryKey: ["admin", "pages"] });
      navigate("/admin/pages");
    },
  });
  return (
    <ConfirmButton
      label="삭제"
      title="페이지를 지울까요?"
      description={published ? "공개 중인 페이지입니다. 지우면 주소가 바로 「찾을 수 없습니다」가 됩니다" : undefined}
      destructive
      save={save}
      values={null}
    />
  );
}
