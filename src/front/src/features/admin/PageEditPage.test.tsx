import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import type { Editor } from "@tiptap/react";
import { act, cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, LocationProbe, mockApi, renderWithQuery } from "@/test-api";
import { PageEditPage } from "./PageEditPage";

// jsdom 의 Range 에는 레이아웃 API 가 없다. focus 명령의 scrollIntoView 가 prosemirror-view coordsAtPos 로 부른다(RichEditor.test 와 같다)
Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
Range.prototype.getBoundingClientRect = () => new DOMRect();

afterEach(() => vi.unstubAllGlobals());

const U = "00000000-0000-4000-8000-000000000001";
const AT = "2026-11-24T01:02:03.123456Z";
const AT2 = "2026-11-24T05:06:07.654321Z";
const IMG = `<img src="/api/media/${U}">`;
const STALE = "그사이 페이지가 바뀌었습니다. 바뀐 내용을 확인한 뒤 발행하세요";
const ALL = { "page.view": true, "page.create": true, "page.update": true, "page.publish": true, "page.delete": true };
const APPROVER = { "page.view": true, "page.publish": true };
const WRITER = { "page.view": true, "page.update": true, "page.delete": true };

type Page = { id: string; slug: string; title: string; status: string; body_html: string; created_at: string; updated_at: string };
const page = (over: Partial<Page> = {}): Page => ({
  id: U,
  slug: "about",
  title: "소개",
  status: "draft",
  body_html: "<p>본문</p>",
  created_at: AT,
  updated_at: AT,
  ...over,
});

const problem = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
const NO_CONTENT = () => new Response(null, { status: 204 });

let client: QueryClient;
function Grab() {
  client = useQueryClient();
  return null;
}

type State = { page: Page; gate?: Promise<void>; sent: { path: string; body: string }[] };

/** GET 상세는 state.page 를 낸다(state.gate 가 있으면 풀릴 때까지 기다린다). POST 는 post 가 답하고 state 를 바꿀 수 있다. */
function renderEdit({
  can = ALL as Record<string, boolean>,
  post = (_p: string, _s: State): Response | Promise<Response> => NO_CONTENT(),
  initial = page(),
  route = `/admin/pages/${U}`,
} = {}) {
  const state: State = { page: initial, sent: [] };
  const f = mockApi(async (req) => {
    const path = new URL(req.url).pathname;
    if (path === "/api/me") return json(200, { can });
    if (req.method === "GET") {
      await state.gate;
      return json(200, state.page);
    }
    state.sent.push({ path, body: await req.text() });
    return post(path, state);
  });
  renderWithQuery(
    <>
      <Grab />
      <Routes>
      <Route path="/admin/pages/new" element={<PageEditPage />} />
      <Route path="/admin/pages/:id" element={<PageEditPage />} />
      <Route path="/admin/pages" element={<LocationProbe />} />
      </Routes>
    </>,
    route,
    { notice: true },
  );
  return { f, state };
}

const gets = (f: ReturnType<typeof mockApi>) => f.mock.calls.filter(([req]) => req.method === "GET" && new URL(req.url).pathname === `/api/admin/pages/${U}`);
const settle = () => act(() => new Promise((r) => setTimeout(r, 50)));
// 편집 영역. Tiptap 은 DOM 에 편집기를 단다(dom.editor = this, 스펙 1절)
const area = () => document.querySelector<HTMLElement & { editor: Editor }>(".ProseMirror")!;
const loadedEditor = async () => {
  await waitFor(() => expect(area()?.editor.getHTML()).toBe("<p>본문</p>"));
  return area().editor;
};
const publishButton = () => screen.getByRole("button", { name: "발행" });

describe("렌더러 — 저장할 수 없으면 편집기 대신 RichHtml", () => {
  it("저장_단추가_없으면_role_textbox_가_없고_img_가_그려진다", async () => {
    renderEdit({ can: APPROVER, initial: page({ body_html: `<p>사진</p><p>${IMG}</p>` }) });
    expect(await screen.findByText("사진")).toBeInTheDocument();
    expect(document.querySelector(`img[src="/api/media/${U}"]`)).not.toBeNull();
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.getByText("소개")).toBeInTheDocument();
    expect(screen.getByText("/about")).toBeInTheDocument();
  });

  it("발행_확인_대화상자에_img_가_보인다", async () => {
    renderEdit({ can: APPROVER, initial: page({ body_html: `<p>사진</p><p>${IMG}</p>` }) });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "발행" }));
    const dialog = screen.getByRole("alertdialog", { name: "발행할까요?" });
    expect(dialog).toHaveTextContent("방문자가 /about 에서 이 내용을 봅니다");
    const preview = within(dialog).getByLabelText("발행할 본문");
    expect(preview).toHaveAttribute("tabindex", "0");
    expect(preview.querySelector(`img[src="/api/media/${U}"]`)).not.toBeNull();
  });

  it("page_update_없이_view_publish_면_편집기_없음_저장_없음_발행_단추가_aria_disabled_아님", async () => {
    renderEdit({ can: APPROVER });
    expect(await screen.findByText("본문")).toBeInTheDocument();
    expect(document.querySelector(".ProseMirror")).toBeNull();
    expect(screen.queryByRole("button", { name: "저장" })).toBeNull();
    expect(publishButton()).not.toHaveAttribute("aria-disabled");
    expect(screen.queryByText("발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다")).toBeNull();
  });

  it("발행본인데_발행_권한이_없으면_렌더러와_403_문구", async () => {
    renderEdit({ can: WRITER, initial: page({ status: "published", body_html: `<p>공개 본문</p>${IMG}` }) });
    expect(await screen.findByText("발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다")).toBeInTheDocument();
    expect(screen.getByText("공개 본문")).toBeInTheDocument();
    expect(document.querySelector(`img[src="/api/media/${U}"]`)).not.toBeNull();
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("button", { name: "저장" })).toBeNull();
    expect(screen.queryByRole("button", { name: "발행 취소" })).toBeNull();
  });
});

describe("오류", () => {
  it("422_의_첫_칸에_초점이_가고_본문이면_편집기", async () => {
    let reply = problem(422, { errors: { slug: "이미 사용 중인 주소입니다" } });
    const { state } = renderEdit({ initial: page({ body_html: "" }), post: () => reply });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "저장" }));
    await waitFor(() => expect(screen.getByLabelText("주소")).toHaveFocus());

    // 빈 편집기의 getHTML() 은 <p></p> 다(Review Focus 1)
    reply = problem(422, { errors: { body_html: "본문을 입력하세요" } });
    await u.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(area()).toHaveFocus());
    expect(area()).toHaveAccessibleDescription("본문을 입력하세요");
    expect(area()).toHaveAttribute("aria-invalid", "true");
    expect(JSON.parse(state.sent[1].body)).toEqual({ title: "소개", slug: "about", body_html: "<p></p>" });
    expect(state.sent.map((s) => s.path)).toEqual([`/api/admin/pages/${U}`, `/api/admin/pages/${U}`]);
  });

  it("409_와_403_은_서버_detail_을_그린다", async () => {
    let reply = problem(409, { detail: "이미 사용 중인 주소입니다" });
    renderEdit({ post: () => reply });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("이미 사용 중인 주소입니다");
    reply = problem(403, { detail: "발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다" });
    await u.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("발행된 페이지는 발행 권한이 있어야 고칠 수 있습니다"));
  });

  it("저장_401_뒤에도_편집기_내용이_남는다", async () => {
    const { f } = renderEdit({ post: () => problem(401, {}) });
    const editor = await loadedEditor();
    act(() => {
      editor.commands.setTextSelection(3);
      editor.commands.insertContent("더함");
    });
    const u = userEvent.setup();
    await u.click(screen.getByRole("button", { name: "저장" }));
    expect(await screen.findByRole("link", { name: "다시 로그인" })).toBeInTheDocument();
    await settle();
    expect(editor.getHTML()).toBe("<p>본문더함</p>");
    expect(gets(f)).toHaveLength(1);
  });
});

describe("발행", () => {
  it("발행_미리보기는_지금_폼에_실린_응답의_본문이다", async () => {
    // 편집기는 img 를 버린다 — 미리보기가 편집기 출력이 아니라 응답의 body_html 이어야 img 가 보인다
    renderEdit({ initial: page({ body_html: `<p>본문</p><p>${IMG}</p>` }) });
    await waitFor(() => expect(area()?.editor.getHTML()).toBe("<p>본문</p><p></p>"));
    const u = userEvent.setup();
    await u.click(publishButton());
    const preview = within(screen.getByRole("alertdialog", { name: "발행할까요?" })).getByLabelText("발행할 본문");
    expect(preview).toHaveTextContent("본문");
    expect(preview.querySelector(`img[src="/api/media/${U}"]`)).not.toBeNull();
  });

  it("dirty_면_발행이_aria_disabled_이고_안내를_읽는다", async () => {
    const { state } = renderEdit();
    const editor = await loadedEditor();
    expect(publishButton()).not.toHaveAttribute("aria-disabled");
    act(() => {
      editor.commands.insertContent("x");
    });
    await waitFor(() => expect(publishButton()).toHaveAttribute("aria-disabled", "true"));
    expect(publishButton()).toHaveAccessibleDescription("저장하지 않은 수정이 있습니다");
    const u = userEvent.setup();
    await u.click(publishButton());
    await settle();
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(state.sent).toEqual([]);
  });

  it("발행은_지금_폼에_실린_응답의_updated_at_문자열을_그대로_보낸다", async () => {
    const { state } = renderEdit();
    await loadedEditor();
    const u = userEvent.setup();
    await u.click(publishButton());
    await u.click(within(screen.getByRole("alertdialog", { name: "발행할까요?" })).getByRole("button", { name: "확인" }));
    await waitFor(() => expect(state.sent).toHaveLength(1));
    // 바이트까지 같다 — Date 로 바꾸면 소수 6자리가 3자리로 준다
    expect(state.sent).toEqual([{ path: `/api/admin/pages/${U}/publish`, body: '{"updated_at":"2026-11-24T01:02:03.123456Z"}' }]);
  });

  it("발행_409_뒤_상세를_다시_읽고_dirty_가_아니면_다시_채운다", async () => {
    let release!: () => void;
    const { f, state } = renderEdit({
      post: (_p, s) => {
        s.page = page({ body_html: "<p>바뀐 본문</p>", updated_at: AT2 });
        s.gate = new Promise((r) => (release = r));
        return problem(409, { detail: STALE });
      },
    });
    await loadedEditor();
    const publish = publishButton();
    const u = userEvent.setup();
    await u.click(publish);
    await u.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "확인" }));
    // 다시 읽는 동안은 발행이 막힌다
    await waitFor(() => expect(gets(f)).toHaveLength(2));
    expect(publish).toHaveAttribute("aria-disabled", "true");
    await act(async () => release());
    expect(await screen.findByRole("alert")).toHaveTextContent(STALE);
    await waitFor(() => expect(area().editor.getHTML()).toBe("<p>바뀐 본문</p>"));
    await waitFor(() => expect(publish).toHaveFocus());
    expect(document.activeElement).not.toBe(document.body);
    expect(publish).not.toHaveAttribute("aria-disabled");

    state.gate = undefined;
    state.sent = [];
    await u.click(publish);
    const preview = within(screen.getByRole("alertdialog")).getByLabelText("발행할 본문");
    expect(preview).toHaveTextContent("바뀐 본문");
    await u.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "확인" }));
    await waitFor(() => expect(state.sent).toHaveLength(1));
    expect(JSON.parse(state.sent[0].body)).toEqual({ updated_at: AT2 });
  });

  it("발행_성공은_알림_다시_읽기_뒤_발행_취소가_보이고_초점은_h2", async () => {
    const { f } = renderEdit({
      post: (_p, s) => {
        s.page = page({ status: "published", updated_at: AT2 });
        return NO_CONTENT();
      },
    });
    await loadedEditor();
    const u = userEvent.setup();
    await u.click(publishButton());
    await u.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "확인" }));
    expect(await screen.findByRole("button", { name: "발행 취소" })).toBeInTheDocument();
    expect(screen.getByTestId("notice")).toHaveTextContent("발행했습니다");
    expect(gets(f)).toHaveLength(2);
    const heading = screen.getByRole("heading", { name: "페이지 편집" });
    await waitFor(() => expect(heading).toHaveFocus());
    await settle();
    expect(heading).toHaveFocus();
  });

  it("발행_취소는_묻고_성공하면_초안으로_다시_읽는다", async () => {
    const { state } = renderEdit({
      initial: page({ status: "published" }),
      post: (_p, s) => {
        s.page = page({ updated_at: AT2 });
        return NO_CONTENT();
      },
    });
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "발행 취소" }));
    const dialog = screen.getByRole("alertdialog", { name: "발행을 취소할까요?" });
    expect(dialog).toHaveTextContent("방문자가 이 주소에서 「찾을 수 없습니다」를 봅니다");
    await u.click(within(dialog).getByRole("button", { name: "확인" }));
    expect(await screen.findByRole("button", { name: "발행" })).toBeInTheDocument();
    expect(screen.getByTestId("notice")).toHaveTextContent("발행을 취소했습니다");
    expect(state.sent.map((s) => s.path)).toEqual([`/api/admin/pages/${U}/unpublish`]);
  });

  it("고치는_중에_발행을_취소하면_상태와_단추는_새_응답을_따르고_고친_내용은_남는다", async () => {
    renderEdit({
      initial: page({ status: "published" }),
      post: (_p, s) => {
        s.page = page({ updated_at: AT2 });
        return NO_CONTENT();
      },
    });
    await loadedEditor();
    const u = userEvent.setup();
    await u.type(screen.getByLabelText("제목"), " 고침");
    await u.click(screen.getByRole("button", { name: "발행 취소" }));
    await u.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "확인" }));
    await waitFor(() => expect(screen.getByTestId("notice")).toHaveTextContent("발행을 취소했습니다"));
    expect(await screen.findByText("초안")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "발행 취소" })).toBeNull();
    expect(publishButton()).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByLabelText("제목")).toHaveValue("소개 고침");
    await waitFor(() => expect(screen.getByRole("heading", { name: "페이지 편집" })).toHaveFocus());
  });

  it("작성_201_뒤_상세로_옮기면_상세_응답으로_채워지고_발행이_막히지_않는다", async () => {
    renderEdit({ route: "/admin/pages/new", post: () => json(201, { id: U }) });
    const u = userEvent.setup();
    await u.type(await screen.findByLabelText("제목"), "임시 제목");
    await u.type(screen.getByLabelText("주소"), "tmp");
    await waitFor(() => expect(area()).not.toBeNull());
    act(() => {
      area().editor.commands.insertContent("임시 본문");
    });
    await u.click(screen.getByRole("button", { name: "만들기" }));
    expect(await screen.findByRole("heading", { name: "페이지 편집" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("제목")).toHaveValue("소개"));
    expect(screen.getByLabelText("주소")).toHaveValue("about");
    expect(area().editor.getHTML()).toBe("<p>본문</p>");
    expect(publishButton()).not.toHaveAttribute("aria-disabled");
  });
});

describe("다시 읽기", () => {
  it("창_초점으로는_상세를_다시_읽지_않는다", async () => {
    const { f } = renderEdit();
    await loadedEditor();
    await settle();
    // TanStack Query 5 의 focusManager 는 window 의 visibilitychange 를 듣는다
    act(() => {
      window.dispatchEvent(new Event("focus"));
      document.dispatchEvent(new Event("visibilitychange", { bubbles: true }));
    });
    await settle();
    expect(gets(f)).toHaveLength(1);
  });

  it("상세_쿼리는_admin_키_아래이고_gcTime_은_0", async () => {
    renderEdit();
    await loadedEditor();
    // 떠나면 지운다 — 로그아웃 뒤 같은 탭의 다른 계정에게 앞 사람의 초안이 응답 전에 보이지 않게
    expect(client.getQueryCache().find({ queryKey: ["admin", "page", U] })?.options.gcTime).toBe(0);
  });

  it("네트워크가_다시_이어져도_상세를_다시_읽지_않는다", async () => {
    const { f } = renderEdit();
    await loadedEditor();
    await settle();
    // onlineManager 는 window 의 online·offline 을 듣는다
    act(() => {
      window.dispatchEvent(new Event("offline"));
      window.dispatchEvent(new Event("online"));
    });
    await settle();
    expect(gets(f)).toHaveLength(1);
  });

  it("저장_204_뒤_상세를_다시_읽어_dirty_가_풀린다", async () => {
    const { f, state } = renderEdit({
      post: (path, s) => {
        if (path === `/api/admin/pages/${U}`) s.page = page({ title: "소개 고침", updated_at: AT2 });
        return NO_CONTENT();
      },
    });
    await loadedEditor();
    const u = userEvent.setup();
    await u.type(screen.getByLabelText("제목"), " 고침");
    expect(publishButton()).toHaveAttribute("aria-disabled", "true");
    await u.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(screen.getByTestId("notice")).toHaveTextContent("저장했습니다"));
    expect(JSON.parse(state.sent[0].body)).toEqual({ title: "소개 고침", slug: "about", body_html: "<p>본문</p>" });
    await waitFor(() => expect(publishButton()).not.toHaveAttribute("aria-disabled"));
    expect(gets(f)).toHaveLength(2);
    expect(screen.getByLabelText("제목")).toHaveValue("소개 고침");
    // 다시 채운 응답의 updated_at 으로 발행한다
    await u.click(publishButton());
    await u.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "확인" }));
    await waitFor(() => expect(state.sent).toHaveLength(2));
    expect(JSON.parse(state.sent[1].body)).toEqual({ updated_at: AT2 });
  });
});

describe("저장 응답을 기다리는 동안 친 글자 (1-C2 최종 검토 I-2)", () => {
  /** 저장 POST 를 붙잡는다. 서버는 받은 값을 새 updated_at 으로 저장한 것으로 한다 — 상세가 바뀌어야 다시 채우기가 돈다. */
  function held() {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const post = async (path: string, s: State) => {
      if (path === `/api/admin/pages/${U}`) {
        const b = JSON.parse(s.sent.at(-1)!.body);
        s.page = page({ title: b.title, slug: b.slug, body_html: b.body_html, updated_at: AT2 });
      }
      await gate;
      return path === "/api/admin/pages" ? json(201, { id: U }) : NO_CONTENT();
    };
    return { post, release };
  }

  it.each([
    ["본문", () => void area().editor.commands.insertContent(" 더함"), () => expect(area().editor.getHTML()).toBe("<p>본문 더함</p>")],
    ["제목", () => {}, () => expect(screen.getByLabelText("제목")).toHaveValue("소개 고침 더함")],
  ] as [string, () => void, () => void][])("저장_중에_%s_에_더한_글자가_응답_뒤에도_남고_dirty_라_발행이_막힌다", async (what, during, kept) => {
    const { post, release } = held();
    const { state } = renderEdit({ post });
    await loadedEditor();
    const u = userEvent.setup();
    await u.type(screen.getByLabelText("제목"), " 고침");
    await u.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(state.sent).toHaveLength(1));
    if (what === "제목") await u.type(screen.getByLabelText("제목"), " 더함");
    act(() => {
      during();
    });
    await act(async () => release());
    await waitFor(() => expect(screen.getByTestId("notice")).toHaveTextContent("저장했습니다"));
    await settle();
    kept();
    expect(publishButton()).toHaveAttribute("aria-disabled", "true");
  });

  it("작성_중에_본문에_더한_글자가_201_뒤_상세로_옮겨도_남고_dirty_라_발행이_막힌다", async () => {
    const { post, release } = held();
    const { state } = renderEdit({ route: "/admin/pages/new", post });
    const u = userEvent.setup();
    await u.type(await screen.findByLabelText("제목"), "소개");
    await u.type(screen.getByLabelText("주소"), "about");
    await waitFor(() => expect(area()).not.toBeNull());
    act(() => {
      area().editor.commands.insertContent("본문");
    });
    await u.click(screen.getByRole("button", { name: "만들기" }));
    await waitFor(() => expect(state.sent).toHaveLength(1));
    act(() => {
      area().editor.commands.insertContent(" 더함");
    });
    await act(async () => release());
    expect(await screen.findByRole("heading", { name: "페이지 편집" })).toBeInTheDocument();
    await settle();
    expect(area().editor.getHTML()).toBe("<p>본문 더함</p>");
    expect(screen.getByLabelText("제목")).toHaveValue("소개");
    expect(publishButton()).toHaveAttribute("aria-disabled", "true");
  });
});

describe("삭제", () => {
  it("삭제_단추가_발행본_발행_권한_없음이면_없고_안내가_보인다", async () => {
    renderEdit({ can: WRITER, initial: page({ status: "published" }) });
    expect(await screen.findByText("발행을 취소한 뒤 지울 수 있습니다")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "삭제" })).toBeNull();
  });

  it("초안이면_page_delete_만으로_삭제_단추가_있다", async () => {
    renderEdit({ can: WRITER });
    expect(await screen.findByRole("button", { name: "삭제" })).toBeInTheDocument();
    expect(screen.queryByText("발행을 취소한 뒤 지울 수 있습니다")).toBeNull();
  });

  it("삭제_설명이_발행본에서_바뀐다", async () => {
    const u = userEvent.setup();
    const published = "공개 중인 페이지입니다. 지우면 주소가 바로 「찾을 수 없습니다」가 됩니다";
    renderEdit();
    await u.click(await screen.findByRole("button", { name: "삭제" }));
    expect(screen.getByRole("alertdialog", { name: "페이지를 지울까요?" })).not.toHaveTextContent(published);
    cleanup();

    renderEdit({ initial: page({ status: "published" }) });
    await u.click(await screen.findByRole("button", { name: "삭제" }));
    expect(screen.getByRole("alertdialog", { name: "페이지를 지울까요?" })).toHaveTextContent(published);
  });

  it("삭제_성공은_목록으로_가고_알림", async () => {
    const { state } = renderEdit();
    const u = userEvent.setup();
    await u.click(await screen.findByRole("button", { name: "삭제" }));
    await u.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "확인" }));
    expect(await screen.findByTestId("location")).toHaveTextContent("/admin/pages");
    expect(screen.getByTestId("notice")).toHaveTextContent("페이지를 지웠습니다");
    expect(state.sent.map((s) => s.path)).toEqual([`/api/admin/pages/${U}/delete`]);
  });
});
