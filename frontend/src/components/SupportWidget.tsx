import {
  useEffect,
  useState,
  type FormEvent
} from "react";

import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import { publicGraphQl } from "../lib/http";

type SupportMessage = {
  id: number;
  sender: "ai" | "system" | "user";
  text: string;
};

type SupportContact = {
  url: string;
  email: string;
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
    handoffNotice: "站点还未配置人工客服入口，请联系管理员开通。",
    handoffEmail: "邮件联系",
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
      "Human support has not been configured on this site yet. Please contact the operator.",
    handoffEmail: "Contact by email",
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
  // The human contact the operator configured on the site; null until the
  // contact query has answered (and stays null when none is configured).
  const [contact, setContact] = useState<SupportContact | null>(null);

  useEffect(() => {
    function openSupport() {
      setOpen(true);
    }
    window.addEventListener("sinx:open-support", openSupport);
    return () => window.removeEventListener("sinx:open-support", openSupport);
  }, []);

  // The contact is read once per open widget. The fields ride the same public
  // GraphQL query surface as siteName; until supportUrl/supportEmail are
  // registered there the request fails validation and the widget stays in the
  // not-configured state, which is also the state of an operator who left both
  // settings blank.
  useEffect(() => {
    if (!open) {
      return;
    }
    let active = true;
    publicGraphQl<{
      supportUrl: string | null;
      supportEmail: string | null;
    }>(`query SupportContact {
      supportUrl
      supportEmail
    }`)
      .then((result) => {
        if (!active) {
          return;
        }
        setContact({
          url: result.supportUrl || "",
          email: result.supportEmail || ""
        });
      })
      .catch(() => {
        if (active) {
          setContact(null);
        }
      });
    return () => {
      active = false;
    };
  }, [open]);

  const supportUrl = open && contact ? contact.url : "";
  const supportEmail = open && contact ? contact.email : "";

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

  function openHandoff() {
    if (supportUrl) {
      // Opens in a new tab with a noopener feature, so the hosted support
      // page cannot reach back into this window.
      window.open(supportUrl, "_blank", "noopener");
      return;
    }
    if (supportEmail) {
      window.open(`mailto:${supportEmail}`, "_blank", "noopener");
    }
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
                {supportUrl ? (
                  <button onClick={openHandoff} type="button">
                    {labels.handoff}
                  </button>
                ) : supportEmail ? (
                  <button
                    onClick={openHandoff}
                    title={`mailto:${supportEmail}`}
                    type="button"
                  >
                    {labels.handoffEmail}
                  </button>
                ) : (
                  <button
                    aria-disabled="true"
                    disabled
                    title={labels.handoffNotice}
                    type="button"
                  >
                    {labels.handoff}
                  </button>
                )}
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
