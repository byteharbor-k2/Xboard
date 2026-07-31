import { AppShell } from "../components/AppShell";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "REFERRALS",
    title: "我的邀请",
    description: "管理邀请码并查看邀请与佣金概况。",
    availableCodes: "可用邀请码",
    invitedUsers: "已邀请用户",
    commission: "累计佣金",
    codes: "邀请码",
    code: "邀请码",
    createdAt: "创建时间",
    used: "使用情况",
    status: "状态",
    action: "操作",
    empty: "暂无邀请码",
    emptyDescription: "邀请码功能接入后，可在这里创建和管理邀请码。"
  },
  "en-US": {
    eyebrow: "REFERRALS",
    title: "My invitations",
    description: "Manage invitation codes and review referral rewards.",
    availableCodes: "Available codes",
    invitedUsers: "Invited users",
    commission: "Total commission",
    codes: "Invitation codes",
    code: "Code",
    createdAt: "Created",
    used: "Usage",
    status: "Status",
    action: "Action",
    empty: "No invitation codes",
    emptyDescription:
      "You will be able to create and manage codes after the invitation service is connected."
  }
};

export function InvitationsPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <section className="referral-summary-grid">
        <article className="panel">
          <span>{labels.availableCodes}</span>
          <strong>—</strong>
        </article>
        <article className="panel">
          <span>{labels.invitedUsers}</span>
          <strong>—</strong>
        </article>
        <article className="panel">
          <span>{labels.commission}</span>
          <strong>—</strong>
        </article>
      </section>
      <section className="panel user-record-panel">
        <h2>{labels.codes}</h2>
        <div className="user-table-wrap">
          <table className="user-data-table">
            <thead>
              <tr>
                <th>{labels.code}</th>
                <th>{labels.createdAt}</th>
                <th>{labels.used}</th>
                <th>{labels.status}</th>
                <th>{labels.action}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td colSpan={5}>
                  <div className="user-empty-state compact">
                    <span aria-hidden="true">＋</span>
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
