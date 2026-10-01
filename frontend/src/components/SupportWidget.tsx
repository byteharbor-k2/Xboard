import { useEffect, useRef, useState, type FormEvent } from "react";
import { graphQl } from "../lib/http";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

type SupportMessage = { sender: "USER" | "ADMIN"; content: string; createdAt: string };
const copy = {
  "zh-CN": { open: "联系人工客服", close: "关闭客服", title: "人工客服", loading: "正在加载对话…", empty: "您好，有什么可以帮您？", failed: "客服消息暂时无法加载，请重试。", placeholder: "输入消息…", send: "发送", sending: "发送中…", user: "我", admin: "人工客服" },
  "en-US": { open: "Contact support", close: "Close support", title: "Human support", loading: "Loading conversation…", empty: "Hello! How can we help?", failed: "Support messages could not be loaded. Please retry.", placeholder: "Write a message…", send: "Send", sending: "Sending…", user: "You", admin: "Support" }
};

export function SupportWidget() {
  const token = useAuthStore(state => state.accessToken)!;
  const language = useUserPreferences(state => state.language);
  const labels = copy[language];
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<SupportMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");
  const endOfMessages = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const show = () => setOpen(true);
    window.addEventListener("sinx:open-support", show);
    return () => window.removeEventListener("sinx:open-support", show);
  }, []);

  useEffect(() => {
    if (!open || !token) return;
    let active = true;
    let running = false;
    const refresh = async () => {
      if (running) return;
      running = true;
      try {
        const result = await graphQl<{ viewerSupportMessages: SupportMessage[] }>(token, "query SupportMessages { viewerSupportMessages { sender content createdAt } }");
        if (active) { setMessages(result.viewerSupportMessages); setError(""); }
      } catch { if (active) setError(labels.failed); }
      finally { running = false; if (active) setLoading(false); }
    };
    setLoading(true);
    void refresh();
    const timer = window.setInterval(() => void refresh(), 2500);
    return () => { active = false; window.clearInterval(timer); };
  }, [open, token, labels.failed]);

  useEffect(() => { endOfMessages.current?.scrollIntoView({ behavior: "smooth", block: "end" }); }, [messages]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const content = draft.trim();
    if (!content || sending) return;
    setSending(true);
    setError("");
    try {
      await graphQl<{ sendSupportMessage: SupportMessage }>(token, "mutation SendSupportMessage($content: String!) { sendSupportMessage(content: $content) { sender content createdAt } }", { content });
      setDraft("");
      const result = await graphQl<{ viewerSupportMessages: SupportMessage[] }>(token, "query SupportMessages { viewerSupportMessages { sender content createdAt } }");
      setMessages(result.viewerSupportMessages);
    } catch { setError(labels.failed); }
    finally { setSending(false); }
  }

  return <>
    <button aria-label={labels.open} className="support-launcher" onClick={() => setOpen(true)} type="button"><span aria-hidden="true">?</span><span className="support-launcher-label">{labels.open}</span></button>
    {open && <aside aria-label={labels.title} className="support-widget-panel human-support-panel"><header><h2>{labels.title}</h2><button aria-label={labels.close} className="support-header-button" onClick={() => setOpen(false)} type="button">×</button></header><div className="human-support-content"><div aria-live="polite" className="support-thread-messages">{loading && messages.length === 0 ? <p>{labels.loading}</p> : messages.length === 0 ? <p className="support-thread-empty">{labels.empty}</p> : messages.map((message, index) => <article className={`support-chat-message ${message.sender === "ADMIN" ? "from-admin" : "from-user"}`} key={`${message.createdAt}-${index}`}><strong>{message.sender === "ADMIN" ? labels.admin : labels.user}</strong><p>{message.content}</p><time>{new Intl.DateTimeFormat(language, { dateStyle: "short", timeStyle: "short" }).format(new Date(message.createdAt))}</time></article>)}<div ref={endOfMessages} /></div><form className="support-chat-composer" onSubmit={submit}><textarea aria-label={labels.placeholder} onChange={event => setDraft(event.target.value)} placeholder={labels.placeholder} rows={2} value={draft} /><button disabled={!draft.trim() || sending} type="submit">{sending ? labels.sending : labels.send}</button></form>{error && <p className="support-chat-error" role="alert">{error}</p>}</div></aside>}
  </>;
}
