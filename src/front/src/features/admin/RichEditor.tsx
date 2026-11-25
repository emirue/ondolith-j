import { Link } from "@tiptap/extension-link";
import { OrderedList } from "@tiptap/extension-list";
import { EditorContent, useEditor, useEditorState, type Attributes, type Editor, type Extensions } from "@tiptap/react";
import { StarterKit } from "@tiptap/starter-kit";
import { useEffect, useId, useRef, useState } from "react";
import { linkHref } from "@/shared/lib/linkHref";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";

/**
 * 확장 = 서버 허용 목록과 같은 노드·마크·속성만(1-C2 스펙 3.1 대조표). 짝 시험이 같은 값을 가져다 쓴다.
 * 편집기는 방어가 아니다 — 방어는 서버 정화 두 번 + DOMPurify + CSP 다. 맞추는 이유는 「저장하면 내가 쓴 그대로」.
 * injectCSS 는 기본값(true): style-src 'unsafe-inline' 아래라 위반이 없다.
 * ponytail: style-src 에서 'unsafe-inline' 을 뺄 때 injectCSS: false 로 하고 @tiptap/core 의 기본 CSS 를 index.css 로 옮긴다(스펙 3.4)
 */
export const EXTENSIONS: Extensions = [
  StarterKit.configure({
    link: false,
    orderedList: false,
    code: false,
    codeBlock: false,
    strike: false,
    underline: false,
    horizontalRule: false,
    // 끝이 문단이 아닌 본문을 열고 아무 트랜잭션이나 나면 빈 <p></p> 가 붙는다 — 열고 저장만 해도 본문이 바뀐다
    trailingNode: false,
    heading: { levels: [2, 3] },
  }),
  // 속성을 href 하나로 — 붙여넣은 title·class·target·rel 이 출력에 남지 않게(서버가 지워 저장할 때마다 본문이 바뀐다)
  Link.extend({ addAttributes() { return { href: (this.parent?.() as Attributes).href }; } }).configure({
    isAllowedUri: (url) => linkHref(url) !== null,
    autolink: false,
    linkOnPaste: false,
    openOnClick: false,
    HTMLAttributes: { target: null, rel: null },
  }),
  // 속성을 start 하나로. 서버 규칙(^[0-9]{1,6}$)이 아니면 1 — renderHTML 이 1 이면 뺀다
  OrderedList.extend({
    addAttributes() {
      return {
        start: {
          default: 1,
          parseHTML: (el: HTMLElement) => {
            const v = el.getAttribute("start");
            return v !== null && /^[0-9]{1,6}$/.test(v) ? Number(v) : 1;
          },
        },
      };
    },
  }),
];

/** 편집기를 만든다. content 는 처음 한 번만 싣는다. */
export function useRichEditor(content: string, onChange?: () => void): Editor | null {
  return useEditor({ extensions: EXTENSIONS, content, onUpdate: onChange });
}

/** 본문 편집기: 라벨, 도구 막대(스펙 3.3), 링크 입력 칸, 편집 영역. 라벨을 누르면 편집기에 초점. */
export function RichEditor({ editor, label, labelId, error, errorId }: { editor: Editor | null; label: string; labelId: string; error?: string; errorId?: string }) {
  const state = useEditorState({
    editor,
    selector: ({ editor: e }) => e && {
      bold: e.isActive("bold"),
      italic: e.isActive("italic"),
      h2: e.isActive("heading", { level: 2 }),
      h3: e.isActive("heading", { level: 3 }),
      bullet: e.isActive("bulletList"),
      ordered: e.isActive("orderedList"),
      quote: e.isActive("blockquote"),
      link: e.isActive("link"),
    },
  });
  const linkId = useId();
  const linkInput = useRef<HTMLInputElement>(null);
  const [url, setUrl] = useState("");
  // 매번 새 객체 — 같은 문구로 다시 실패해도 초점 effect 가 돈다
  const [linkError, setLinkError] = useState<{ message: string } | null>(null);

  // 편집 영역 속성(role="textbox" 는 Tiptap 이 늘 단다)
  useEffect(() => {
    editor?.setOptions({
      editorProps: {
        attributes: {
          "aria-multiline": "true",
          "aria-labelledby": labelId,
          ...(error ? { "aria-invalid": "true", "aria-describedby": errorId ?? "" } : {}),
        },
      },
    });
  }, [editor, labelId, error, errorId]);

  // 오류를 그린 뒤에 초점을 옮긴다 — 칸에 aria-describedby 가 붙은 다음이라야 오류를 읽는다
  useEffect(() => {
    if (linkError) linkInput.current?.focus();
  }, [linkError]);

  if (!editor || !state) return null;

  const tools: [string, boolean, () => void][] = [
    ["굵게", state.bold, () => editor.chain().focus().toggleBold().run()],
    ["기울임", state.italic, () => editor.chain().focus().toggleItalic().run()],
    ["제목 2", state.h2, () => editor.chain().focus().toggleHeading({ level: 2 }).run()],
    ["제목 3", state.h3, () => editor.chain().focus().toggleHeading({ level: 3 }).run()],
    ["글머리 목록", state.bullet, () => editor.chain().focus().toggleBulletList().run()],
    ["번호 목록", state.ordered, () => editor.chain().focus().toggleOrderedList().run()],
    ["인용", state.quote, () => editor.chain().focus().toggleBlockquote().run()],
    ["링크", state.link, () => linkInput.current?.focus()],
  ];

  const apply = () => {
    if (editor.state.selection.empty) return setLinkError({ message: "링크로 만들 글자를 먼저 고르세요" });
    if (linkHref(url) === null) return setLinkError({ message: "사용할 수 없는 링크입니다" });
    setLinkError(null);
    // 저장값은 입력한 문자열 그대로(서버 LinkCheck 가 같은 문자열을 보게)
    editor.chain().focus().extendMarkRange("link").setLink({ href: url }).run();
  };

  return (
    <div className="space-y-2">
      <Label id={labelId} onClick={() => editor.commands.focus()}>{label}</Label>
      <div role="toolbar" aria-label="서식" className="flex flex-wrap gap-1">
        {tools.map(([name, pressed, run]) => (
          <Button key={name} type="button" variant={pressed ? "default" : "outline"} size="sm" aria-pressed={pressed} onClick={run}>{name}</Button>
        ))}
      </div>
      <div className="flex items-end gap-2">
        <div className="flex-1 space-y-1">
          <Label htmlFor={linkId}>링크 주소</Label>
          <Input
            id={linkId}
            ref={linkInput}
            value={url}
            autoComplete="off"
            aria-invalid={linkError ? true : undefined}
            aria-describedby={linkError ? `${linkId}-error` : undefined}
            onChange={(e) => setUrl(e.target.value)}
            // 칸이 페이지 폼 안이다 — Enter 가 페이지를 저장하지 않고 링크를 적용하게
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                apply();
              }
            }}
          />
        </div>
        <Button type="button" variant="outline" size="sm" onClick={apply}>적용</Button>
        <Button type="button" variant="outline" size="sm" onClick={() => editor.chain().focus().extendMarkRange("link").unsetLink().run()}>링크 빼기</Button>
      </div>
      {linkError && <p id={`${linkId}-error`} className="text-sm text-destructive">{linkError.message}</p>}
      <EditorContent editor={editor} className="rounded-lg border border-input p-3 [&_.ProseMirror]:min-h-40 [&_.ProseMirror]:outline-none" />
      {error && <p id={errorId} className="text-sm text-destructive">{error}</p>}
    </div>
  );
}
