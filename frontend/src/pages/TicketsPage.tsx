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
    priority: "工单级别",
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
    priority: "Priority",
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
          onClick={() =>
            window.dispatchEvent(new CustomEvent("sinx:open-support"))
          }
          type="button"
        >
          {labels.newTicket}
        </button>
      </header>
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
