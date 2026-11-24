import { Editor } from "@tiptap/react";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
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
});
