import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";

import { listSupportConversations, listSupportMessages, replyToSupportConversation } from "../admin/supportChatApi";
import { AdminShell } from "../components/AdminShell";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

const copy = {
  "zh-CN": { title: "在线客服", description: "直接查看并回复用户的站内客服对话。", conversations: "会话", empty: "还没有用户发起客服对话。", choose: "选择左侧会话查看消息。", reply: "输入回复…", send: "发送回复", user: "用户", admin: "客服", loading: "加载中…", failed: "客服数据加载失败", sending: "发送中…" },
  "en-US": { title: "Online support", description: "Read and reply to users' in-site support conversations.", conversations: "Conversations", empty: "No support conversations yet.", choose: "Select a conversation to read its messages.", reply: "Write a reply…", send: "Send reply", user: "User", admin: "Support", loading: "Loading…", failed: "Failed to load support conversations", sending: "Sending…" }
};

export function AdminSupportPage() {
  const language = useAdminPreferences(state => state.language);
  const token = useAdminAuthStore(state => state.accessToken)!;
  const labels = copy[language];
  const client = useQueryClient();
  const [selected, setSelected] = useState("");
  const [draft, setDraft] = useState("");
  const [error, setError] = useState("");
  const conversations = useQuery({ queryKey: ["admin", "support", "conversations"], queryFn: () => listSupportConversations(token), refetchInterval: 3000 });
  const active = conversations.data?.find(conversation => conversation.userId === selected);
  useEffect(() => {
    if (!selected && conversations.data?.length) setSelected(conversations.data[0].userId);
    if (selected && conversations.data && !conversations.data.some(conversation => conversation.userId === selected)) setSelected(conversations.data[0]?.userId ?? "");
  }, [selected, conversations.data]);
  const messages = useQuery({ queryKey: ["admin", "support", "messages", selected], queryFn: () => listSupportMessages(token, selected), enabled: Boolean(selected), refetchInterval: selected ? 2500 : false });
  const reply = useMutation({ mutationFn: (content: string) => replyToSupportConversation(token, selected, content), onSuccess: async () => { setDraft(""); setError(""); await Promise.all([client.invalidateQueries({ queryKey: ["admin", "support", "messages", selected] }), client.invalidateQueries({ queryKey: ["admin", "support", "conversations"] })]); }, onError: () => setError(labels.failed) });
  function submit(event: FormEvent) {
    event.preventDefault();
    const content = draft.trim();
    if (!content || !selected || reply.isPending) return;
    reply.mutate(content);
  }

  return <AdminShell><header className="admin-page-heading"><div><p>{language === "zh-CN" ? "用户与支持" : "Users & support"}</p><h1>{labels.title}</h1><span>{labels.description}</span></div></header>
    <section className="admin-support-layout">
      <aside className="admin-card admin-support-list"><h2>{labels.conversations}</h2>{conversations.isPending ? <p>{labels.loading}</p> : conversations.isError ? <p>{labels.failed}</p> : conversations.data?.length ? conversations.data.map(conversation => <button className={selected === conversation.userId ? "active" : ""} key={conversation.userId} onClick={() => setSelected(conversation.userId)} type="button"><strong>{conversation.displayName}</strong><small>{conversation.email}</small><span>{conversation.lastSender === "ADMIN" ? `${labels.admin}: ` : `${labels.user}: `}{conversation.lastMessage}</span><time>{new Intl.DateTimeFormat(language, { dateStyle: "short", timeStyle: "short" }).format(new Date(conversation.updatedAt))}</time></button>) : <p>{labels.empty}</p>}</aside>
      <section className="admin-card admin-support-thread">{active ? <><header><div><h2>{active.displayName}</h2><span>{active.email}</span></div></header><div aria-live="polite" className="support-thread-messages">{messages.isPending ? <p>{labels.loading}</p> : (messages.data ?? []).map((message, index) => <article className={`support-chat-message ${message.sender === "ADMIN" ? "from-admin" : "from-user"}`} key={`${message.createdAt}-${index}`}><strong>{message.sender === "ADMIN" ? labels.admin : labels.user}</strong><p>{message.content}</p><time>{new Intl.DateTimeFormat(language, { dateStyle: "short", timeStyle: "short" }).format(new Date(message.createdAt))}</time></article>)}</div><form className="admin-support-reply" onSubmit={submit}><textarea aria-label={labels.reply} onChange={event => setDraft(event.target.value)} placeholder={labels.reply} rows={3} value={draft} /><button className="plan-primary-button" disabled={!draft.trim() || reply.isPending} type="submit">{reply.isPending ? labels.sending : labels.send}</button>{error && <p className="admin-operation-error">{error}</p>}</form></> : <div className="admin-support-empty">{labels.choose}</div>}</section>
    </section>
  </AdminShell>;
}
