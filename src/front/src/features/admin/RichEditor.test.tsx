import { Editor } from "@tiptap/react";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { linkHref } from "@/shared/lib/linkHref";
import { RichHtml } from "@/shared/ui/RichHtml";
import { EDITOR_TAGS, editorInvariant, editorRows, links, vectors } from "@/test-vectors";
import { EXTENSIONS, RichEditor, useRichEditor } from "./RichEditor";

// jsdom 의 Range 에는 레이아웃 API 가 없다. focus 명령의 scrollIntoView 가 prosemirror-view coordsAtPos 로 부른다
Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
Range.prototype.getBoundingClientRect = () => new DOMRect();

let current: Editor | null = null;

function Harness({ content = "<p>가나다</p>" }: { content?: string }) {
  const editor = useRichEditor(content);
  current = editor;
  return <RichEditor editor={editor} label="본문" labelId="body-label" />;
}

function setup(content?: string) {
  render(<Harness content={content} />);
  return { user: userEvent.setup(), editor: current! };
}

// 싣고 꺼낸다(1-C2 스펙 5.2). 편집기마다 새로 만들고 지운다
function load(content: string) {
  const editor = new Editor({ extensions: EXTENSIONS, content });
  const html = editor.getHTML();
  editor.destroy();
  return html;
}

const BUTTONS = ["굵게", "기울임", "제목 2", "제목 3", "글머리 목록", "번호 목록", "인용", "링크"];

describe("RichEditor", () => {
  it("jsdom_에서_편집기가_뜬다", () => {
    const editor = new Editor({ extensions: EXTENSIONS, content: "<p>a</p>" });
    expect(editor.getHTML()).toBe("<p>a</p>");
    editor.destroy();
  });

  it("도구_막대_단추마다_aria_pressed_가_상태를_따른다", async () => {
    for (const name of BUTTONS) {
      // 단추마다 새 편집기 — 앞 단추의 상태가 섞이지 않게. 링크는 고른 글자에 링크를 걸어 본다
      const { user, editor } = setup();
      const toolbar = within(screen.getByRole("toolbar", { name: "서식" }));
      expect(toolbar.getByRole("button", { name, pressed: false }), name).toBeInTheDocument();
      if (name === "링크") {
        editor.commands.setTextSelection({ from: 1, to: 3 });
        await user.type(screen.getByLabelText("링크 주소"), "/about");
        await user.click(screen.getByRole("button", { name: "적용" }));
        editor.commands.setTextSelection(2);
      } else {
        editor.commands.selectAll();
        await user.click(toolbar.getByRole("button", { name }));
      }
      expect(await toolbar.findByRole("button", { name, pressed: true }), name).toBeInTheDocument();
      cleanup();
    }
  });

  it("링크_입력이_javascript_와_protocol_relative_를_거부한다", async () => {
    for (const bad of ["javascript:alert(1)", "//evil.example"]) {
      const { user, editor } = setup();
      editor.commands.setTextSelection({ from: 1, to: 3 });
      const input = screen.getByLabelText("링크 주소");
      await user.type(input, bad);
      await user.click(screen.getByRole("button", { name: "적용" }));
      expect(screen.getByText("사용할 수 없는 링크입니다"), bad).toBeInTheDocument();
      expect(input).toHaveAttribute("aria-invalid", "true");
      expect(input).toHaveFocus();
      expect(editor.getHTML()).not.toContain("<a");
      cleanup();
    }
  });

  it("고른_글자가_없으면_안내", async () => {
    const { user, editor } = setup();
    editor.commands.setTextSelection(2);
    await user.type(screen.getByLabelText("링크 주소"), "/about");
    await user.click(screen.getByRole("button", { name: "적용" }));
    expect(screen.getByText("링크로 만들 글자를 먼저 고르세요")).toBeInTheDocument();
    expect(editor.getHTML()).not.toContain("<a");
  });

  it("저장값은_입력_문자열_그대로", async () => {
    const { user, editor } = setup();
    editor.commands.setTextSelection({ from: 1, to: 3 });
    await user.type(screen.getByLabelText("링크 주소"), "https://EXAMPLE.com/");
    await user.click(screen.getByRole("button", { name: "적용" }));
    expect(editor.getHTML()).toBe('<p><a href="https://EXAMPLE.com/">가나</a>다</p>');
  });

  it("링크_빼기", async () => {
    const { user, editor } = setup('<p><a href="/about">가나</a>다</p>');
    editor.commands.setTextSelection(2);
    await user.click(screen.getByRole("button", { name: "링크 빼기" }));
    expect(editor.getHTML()).toBe("<p>가나다</p>");
  });

  it("라벨을_누르면_편집기에_초점", async () => {
    const { user } = setup();
    const box = screen.getByRole("textbox", { name: "본문" });
    expect(box).toHaveAttribute("aria-multiline", "true");
    await user.click(screen.getByText("본문"));
    // Tiptap focus 명령은 requestAnimationFrame 안에서 초점을 준다(core/src/commands/focus.ts)
    await vi.waitFor(() => expect(box).toHaveFocus());
  });

  // ---- 편집기 ↔ 서버 짝(스펙 5절). 백엔드 RichTextTest 가 같은 editor.json 의 editor 열을 clean 한다 ----

  it("editor_json_의_모든_줄에서_편집기_출력이_editor_와_같다", () => {
    expect(editorRows).toHaveLength(13);
    const diff = editorRows.filter((r) => load(r.input) !== r.editor).map((r) => `${r.id}: ${load(r.input)}`);
    expect(diff).toEqual([]);
  });

  it("편집기_출력은_DOMPurify_가_바꾸지_않는다", () => {
    for (const r of editorRows) {
      expect(render(<RichHtml html={r.editor} />).container.querySelector(".rich")!.innerHTML, r.id).toBe(r.editor);
      cleanup();
    }
  });

  it("편집기_출력을_다시_실어도_같다", () => {
    const diff = editorRows.filter((r) => load(r.editor) !== r.editor).map((r) => `${r.id}: ${load(r.editor)}`);
    expect(diff).toEqual([]);
  });

  it("links_allow_값은_편집기에서_링크로_남는다", () => {
    for (const url of links.allow) {
      const attr = url.replaceAll("&", "&amp;").replaceAll('"', "&quot;");
      const body = new DOMParser().parseFromString(load(`<p><a href="${attr}">x</a></p>`), "text/html").body;
      // a.href 는 jsdom 이 절대 주소로 푼다 — 속성 문자열로 본다
      expect(body.querySelector("a")?.getAttribute("href"), url).toBe(url);
    }
  });

  it("vectors_입력을_실은_편집기_출력의_불변식", () => {
    expect(vectors).toHaveLength(96);
    const bad = vectors.flatMap((v) => {
      const body = new DOMParser().parseFromString(load(v.input), "text/html").body;
      const hrefs = [...body.querySelectorAll("[href]")].map((a) => a.getAttribute("href")!);
      return [...editorInvariant(body, EDITOR_TAGS), ...hrefs.filter((h) => linkHref(h) === null).map((h) => `linkHref 거부 ${h}`)].map((b) => `${v.id}: ${b}`);
    });
    expect(bad).toEqual([]);
  });

  it("끝이_목록인_문서에_트랜잭션을_내도_빈_문단이_붙지_않는다", () => {
    // editor.json 은 싣기만 해 TrailingNode(끝에 빈 문단을 붙이는 appendTransaction)를 걸지 못한다
    const editor = new Editor({ extensions: EXTENSIONS, content: "<ul><li><p>a</p></li></ul>" });
    editor.commands.setTextSelection(1);
    expect(editor.getHTML()).toBe("<ul><li><p>a</p></li></ul>");
    editor.destroy();
  });
});
