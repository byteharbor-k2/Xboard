import {
  useEffect,
  useState,
  type FormEvent
} from "react";

import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

type SupportMessage = {
  id: number;
  sender: "ai" | "system" | "user";
  text: string;
};

const copy = {
  "zh-CN": {
    launcher: "打开客服",
    messages: "消息",
    close: "关闭客服",
    back: "返回消息",
    assistant: "SinX AI 客服",
    preview: "AI 客服界面预览",
    introduction: "你好，我是 SinX AI 客服。请告诉我你遇到的问题。",
    conversationPreview: "开始对话后，AI 与人工客服的回复会集中显示在这里。",
    sendMessage: "发送消息",
    handoff: "转人工",
    handoffNotice: "人工客服接管将在服务端工单队列接入后启用。",
    placeholder: "输入你的问题…",
    send: "发送",
    pendingReply: "AI 接口接入后，会根据知识库和账户状态在这里回复。",
    connection: "无法连接",
    subscription: "订阅问题",
    payment: "支付问题"
  },
  "en-US": {
    launcher: "Open support",
    messages: "Messages",
    close: "Close support",
    back: "Back to messages",
    assistant: "SinX AI Support",
    preview: "AI support UI preview",
    introduction: "Hi, I am SinX AI Support. Tell me how I can help.",
    conversationPreview:
      "AI and human support replies will appear here after you start a conversation.",
    sendMessage: "Send us a message",
    handoff: "Talk to a person",
    handoffNotice:
      "Human handoff will be enabled after the server-side support queue is connected.",
    placeholder: "Describe your issue…",
    send: "Send",
    pendingReply:
      "After the AI endpoint is connected, responses based on the knowledge base and account state will appear here.",
    connection: "Connection issue",
    subscription: "Subscription question",
    payment: "Payment question"
  }
};

export function SupportWidget() {
  const viewer = useAuthStore((state) => state.viewer);
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const [open, setOpen] = useState(false);
  const [conversationStarted, setConversationStarted] = useState(false);
  const [draft, setDraft] = useState("");
  const [messages, setMessages] = useState<SupportMessage[]>([]);

  useEffect(() => {
    function openSupport() {
      setOpen(true);
    }
    window.addEventListener("sinx:open-support", openSupport);
    return () => window.removeEventListener("sinx:open-support", openSupport);
  }, []);

  function startConversation() {
    setConversationStarted(true);
    if (messages.length === 0) {
      setMessages([
        {
          id: Date.now(),
          sender: "ai",
          text: labels.introduction
        }
      ]);
    }
  }

  function sendMessage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const text = draft.trim();
    if (!text) {
      return;
    }
    const timestamp = Date.now();
    setMessages((current) => [
      ...current,
      { id: timestamp, sender: "user", text },
      {
        id: timestamp + 1,
        sender: "system",
        text: labels.pendingReply
      }
    ]);
    setDraft("");
  }

  function requestHandoff() {
    setMessages((current) => [
      ...current,
      {
        id: Date.now(),
        sender: "system",
        text: labels.handoffNotice
      }
    ]);
  }

  return (
    <>
      <button
        aria-label={labels.launcher}
        className="support-launcher"
        onClick={() => setOpen(true)}
        type="button"
      >
        <span aria-hidden="true">?</span>
      </button>
      {open && (
        <aside
          aria-label={labels.messages}
          className="support-widget-panel"
        >
          <header>
            {conversationStarted ? (
              <button
                aria-label={labels.back}
                className="support-header-button"
                onClick={() => setConversationStarted(false)}
                type="button"
              >
                ←
              </button>
            ) : (
              <span className="support-header-spacer" />
            )}
            <h2>{labels.messages}</h2>
            <button
              aria-label={labels.close}
              className="support-header-button"
              onClick={() => setOpen(false)}
              type="button"
            >
              ×
            </button>
          </header>
          {!conversationStarted ? (
            <div className="support-message-home">
              <button
                className="support-conversation-preview"
                onClick={startConversation}
                type="button"
              >
                <span className="support-assistant-avatar" aria-hidden="true">
                  S
                </span>
                <span>
                  <strong>{labels.assistant}</strong>
                  <small>{labels.conversationPreview}</small>
                </span>
                <b>›</b>
              </button>
              <div className="support-home-empty">
                <span className="support-assistant-avatar large" aria-hidden="true">
                  S
                </span>
                <strong>{labels.assistant}</strong>
                <p>{labels.preview}</p>
              </div>
              <button
                className="support-start-button"
                onClick={startConversation}
                type="button"
              >
                {labels.sendMessage}
                <span aria-hidden="true">→</span>
              </button>
            </div>
          ) : (
            <div className="support-conversation">
              <div className="support-conversation-heading">
                <div>
                  <span className="support-assistant-avatar" aria-hidden="true">
                    S
                  </span>
                  <div>
                    <strong>{labels.assistant}</strong>
                    <small>{viewer?.displayName}</small>
                  </div>
                </div>
                <button onClick={requestHandoff} type="button">
                  {labels.handoff}
                </button>
              </div>
              <div className="support-message-thread">
                {messages.map((message) => (
                  <div
                    className={`support-message ${message.sender}`}
                    key={message.id}
                  >
                    {message.text}
                  </div>
                ))}
              </div>
              <div className="support-quick-actions">
                {[labels.connection, labels.subscription, labels.payment].map(
                  (option) => (
                    <button
                      key={option}
                      onClick={() => setDraft(option)}
                      type="button"
                    >
                      {option}
                    </button>
                  )
                )}
              </div>
              <form className="support-composer" onSubmit={sendMessage}>
                <textarea
                  aria-label={labels.placeholder}
                  onChange={(event) => setDraft(event.target.value)}
                  placeholder={labels.placeholder}
                  rows={2}
                  value={draft}
                />
                <button disabled={!draft.trim()} type="submit">
                  {labels.send}
                </button>
              </form>
            </div>
          )}
        </aside>
      )}
    </>
  );
}
