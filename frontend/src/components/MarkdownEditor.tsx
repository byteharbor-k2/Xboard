import { useRef, useState } from "react";
import { MarkdownContent } from "./MarkdownContent";

type Format = { label: string; before: string; after: string; placeholder: string; linePrefix?: boolean };

export function MarkdownEditor({ value, onChange, rows = 8, language }: { value: string; onChange: (value: string) => void; rows?: number; language: "zh-CN" | "en-US" }) {
  const [mode, setMode] = useState<"edit" | "preview">("edit");
  const textarea = useRef<HTMLTextAreaElement>(null);
  const edit = language === "zh-CN" ? "编辑 Markdown" : "Edit Markdown";
  const preview = language === "zh-CN" ? "预览" : "Preview";
  const formats: Format[] = [
    { label: "B", before: "**", after: "**", placeholder: "bold text" },
    { label: "I", before: "*", after: "*", placeholder: "italic text" },
    { label: "H", before: "## ", after: "", placeholder: "Heading", linePrefix: true },
    { label: "• List", before: "- ", after: "", placeholder: "List item", linePrefix: true },
    { label: "Link", before: "[", after: "](https://)", placeholder: "link text" },
    { label: "Image", before: "![", after: "](https://)", placeholder: "alt text" },
    { label: "Code", before: "```\n", after: "\n```", placeholder: "code" }
  ];

  function applyFormat(format: Format) {
    const input = textarea.current;
    if (!input) return;
    const start = input.selectionStart;
    const end = input.selectionEnd;
    let replaceStart = start;
    let replaceEnd = end;
    let before = format.before;
    let selected = value.slice(start, end) || format.placeholder;
    let after = format.after;
    if (format.linePrefix) {
      replaceStart = value.lastIndexOf("\n", Math.max(0, start - 1)) + 1;
      const lineEnd = value.indexOf("\n", end);
      replaceEnd = lineEnd === -1 ? value.length : lineEnd;
      const line = value.slice(replaceStart, replaceEnd);
      const prefix = format.before;
      const alreadyPrefixed = line.startsWith(prefix);
      before = alreadyPrefixed ? "" : prefix;
      selected = alreadyPrefixed ? line.slice(prefix.length) : line || format.placeholder;
      after = "";
    }
    const next = value.slice(0, replaceStart) + before + selected + after + value.slice(replaceEnd);
    onChange(next);
    const selectionStart = replaceStart + before.length;
    const selectionEnd = selectionStart + selected.length;
    requestAnimationFrame(() => {
      input.focus();
      input.setSelectionRange(selectionStart, selectionEnd);
    });
  }

  return <div className="markdown-editor">
    <div className="markdown-editor-toolbar">
      <strong>{language === "zh-CN" ? "Markdown 源文" : "Markdown source"}</strong>
      <div className="markdown-format-actions">{formats.map(format => <button aria-label={format.label} key={format.label} onClick={() => applyFormat(format)} title={format.label} type="button">{format.label}</button>)}</div>
      <div className="markdown-editor-modes"><button type="button" aria-pressed={mode === "edit"} onClick={() => setMode("edit")}>{edit}</button><button type="button" aria-pressed={mode === "preview"} onClick={() => setMode("preview")}>{preview}</button></div>
    </div>
    {mode === "edit" ? <textarea ref={textarea} aria-label={edit} rows={rows} value={value} onChange={event => onChange(event.target.value)} /> : <div className="markdown-editor-preview"><MarkdownContent source={value} /></div>}
  </div>;
}
