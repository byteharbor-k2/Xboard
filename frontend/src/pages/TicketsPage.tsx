import { useState } from "react";

import { AppShell } from "../components/AppShell";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "SUPPORT",
    title: "我的工单",
    description: "提交问题并查看历史工单与回复状态。",
    history: "工单历史",
    newTicket: "新的工单",
    subject: "主题",
    subjectPlaceholder: "简要描述遇到的问题",
    priority: "工单级别",
    low: "低",
    medium: "中",
    high: "高",
    message: "问题描述",
    messagePlaceholder: "请描述问题、发生时间和相关现象",
    cancel: "取消",
    submit: "等待后端接口",
    state: "工单状态",
    createdAt: "创建时间",
    lastReplyAt: "最后回复时间",
    action: "操作",
    empty: "暂无工单",
    emptyDescription: "创建工单后，处理进度与回复会显示在这里。"
  },
  "en-US": {
    eyebrow: "SUPPORT",
    title: "My tickets",
    description: "Submit support requests and review their reply status.",
    history: "Ticket history",
    newTicket: "New ticket",
    subject: "Subject",
    subjectPlaceholder: "Briefly describe the issue",
    priority: "Priority",
    low: "Low",
    medium: "Medium",
    high: "High",
    message: "Description",
    messagePlaceholder: "Describe the issue, time, and relevant symptoms",
    cancel: "Cancel",
    submit: "Backend pending",
    state: "Status",
    createdAt: "Created",
    lastReplyAt: "Last reply",
    action: "Action",
    empty: "No tickets",
    emptyDescription:
      "Status and replies will appear here after you create a ticket."
  }
};

export function TicketsPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const [composing, setComposing] = useState(false);

  return (
    <AppShell>
      <header className="page-header ticket-page-heading">
        <div>
          <p className="eyebrow">{labels.eyebrow}</p>
          <h1>{labels.title}</h1>
          <p className="muted">{labels.description}</p>
        </div>
        <button
          className="primary-button compact-button"
          onClick={() => setComposing((current) => !current)}
          type="button"
        >
          {labels.newTicket}
        </button>
      </header>
      {composing && (
        <section className="panel ticket-compose-panel">
          <div className="ticket-compose-row">
            <label>
              {labels.subject}
              <input placeholder={labels.subjectPlaceholder} />
            </label>
            <label>
              {labels.priority}
              <select defaultValue="LOW">
                <option value="LOW">{labels.low}</option>
                <option value="MEDIUM">{labels.medium}</option>
                <option value="HIGH">{labels.high}</option>
              </select>
            </label>
          </div>
          <label>
            {labels.message}
            <textarea
              placeholder={labels.messagePlaceholder}
              rows={6}
            />
          </label>
          <div className="ticket-compose-actions">
            <button
              className="secondary-button compact-button"
              onClick={() => setComposing(false)}
              type="button"
            >
              {labels.cancel}
            </button>
            <button
              className="primary-button compact-button"
              disabled
              type="button"
            >
              {labels.submit}
            </button>
          </div>
        </section>
      )}
      <section className="panel user-record-panel">
        <h2>{labels.history}</h2>
        <div className="user-table-wrap">
          <table className="user-data-table">
            <thead>
              <tr>
                <th>{labels.subject}</th>
                <th>{labels.priority}</th>
                <th>{labels.state}</th>
                <th>{labels.createdAt}</th>
                <th>{labels.lastReplyAt}</th>
                <th>{labels.action}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td colSpan={6}>
                  <div className="user-empty-state compact">
                    <span aria-hidden="true">□</span>
                    <strong>{labels.empty}</strong>
                    <p>{labels.emptyDescription}</p>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </AppShell>
  );
}
