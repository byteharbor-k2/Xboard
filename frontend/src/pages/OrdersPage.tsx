import { useQuery } from "@tanstack/react-query";

import { AppShell } from "../components/AppShell";
import { fetchViewerOrders } from "../lib/orders";
import { ApiError } from "../lib/http";
import { navigate } from "../lib/navigation";
import {
  billingPeriodLabel,
  formatDateTime,
  formatMoney,
  orderStatusLabel,
  orderTypeLabel
} from "../lib/subscription";
import { useAuthStore } from "../store/auth";
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
    emptyDescription: "购买套餐后，订单记录会显示在这里。",
    loading: "正在读取订单…",
    failed: "订单读取失败。",
    view: "查看详情",
    viewAll: "订单详情",
    notPending: "—",
    pendingHint: "订单已创建，等待支付。"
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
    emptyDescription: "Orders will appear here after you purchase a plan.",
    loading: "Loading orders…",
    failed: "Orders could not be loaded.",
    view: "View",
    viewAll: "Order details",
    notPending: "—",
    pendingHint: "Placed, waiting for payment."
  }
};

export function OrdersPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const orders = useQuery({
    queryKey: ["viewer-orders"],
    queryFn: () => fetchViewerOrders(accessToken)
  });

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <section className="panel user-record-panel">
        {orders.isPending && <p className="muted">{labels.loading}</p>}
        {orders.isError && (
          <p className="error-message">
            {orders.error instanceof ApiError
              ? orders.error.message
              : labels.failed}
          </p>
        )}
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
              {orders.data?.length === 0 && (
                <tr>
                  <td colSpan={7}>
                    <div className="user-empty-state compact">
                      <span aria-hidden="true">▤</span>
                      <strong>{labels.empty}</strong>
                      <p>{labels.emptyDescription}</p>
                    </div>
                  </td>
                </tr>
              )}
              {orders.data?.map((order) => (
                <tr key={order.id}>
                  <td>
                    <button
                      className="order-number order-number-link"
                      onClick={() =>
                        navigate(`/account/orders/${order.tradeNo}`)
                      }
                      type="button"
                    >
                      {order.tradeNo}
                    </button>
                  </td>
                  <td>
                    {order.planName}
                    <span className="order-type-tag">
                      {orderTypeLabel(order.orderType, language)}
                    </span>
                  </td>
                  <td>{billingPeriodLabel(order.period, language)}</td>
                  <td>
                    {formatMoney(order.totalAmount, order.currency, language)}
                  </td>
                  <td>
                    <span
                      className={`status-pill order-status-${order.status.toLowerCase()}`}
                      title={
                        order.status === "PENDING"
                          ? labels.pendingHint
                          : undefined
                      }
                    >
                      {orderStatusLabel(order.status, language)}
                    </span>
                  </td>
                  <td>
                    {formatDateTime(order.createdAt, language)}
                  </td>
                  <td>
                    {order.status === "PENDING" ? (
                      <button
                        className="text-button"
                        onClick={() =>
                          navigate(`/account/orders/${order.tradeNo}`)
                        }
                        type="button"
                      >
                        {labels.view}
                      </button>
                    ) : (
                      <span className="muted">{labels.notPending}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </AppShell>
  );
}
