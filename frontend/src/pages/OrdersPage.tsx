import { AppShell } from "../components/AppShell";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "BILLING",
    title: "我的订单",
    description: "查看套餐订单、支付状态和创建时间。",
    orderNumber: "订单号",
    plan: "套餐",
    period: "周期",
    amount: "订单金额",
    status: "订单状态",
    createdAt: "创建时间",
    action: "操作",
    empty: "暂无订单",
    emptyDescription: "购买套餐后，订单记录会显示在这里。"
  },
  "en-US": {
    eyebrow: "BILLING",
    title: "My orders",
    description: "Review plan orders, payment status, and creation time.",
    orderNumber: "Order",
    plan: "Plan",
    period: "Period",
    amount: "Amount",
    status: "Status",
    createdAt: "Created",
    action: "Action",
    empty: "No orders",
    emptyDescription: "Orders will appear here after you purchase a plan."
  }
};

export function OrdersPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <section className="panel user-record-panel">
        <div className="user-table-wrap">
          <table className="user-data-table">
            <thead>
              <tr>
                <th>{labels.orderNumber}</th>
                <th>{labels.plan}</th>
                <th>{labels.period}</th>
                <th>{labels.amount}</th>
                <th>{labels.status}</th>
                <th>{labels.createdAt}</th>
                <th>{labels.action}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td colSpan={7}>
                  <div className="user-empty-state compact">
                    <span aria-hidden="true">▤</span>
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
